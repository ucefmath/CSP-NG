package com.bowman.cspng.app;

import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.OptimisticLockException;
import com.bowman.cspng.config.PeerQuarantineConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PG V16 (lots 4-5) : migration strictement additive, singletons
 * quarantaine/quorum (défauts off), CRUD avec verrou optimiste, chargement
 * via {@code loadAll} (tolérance pre-V16 non testée ici : schéma migré).
 *
 * <p>Même harnais que {@link BulkCacheexCwCyclePgTest} (container dédié ou
 * Testcontainers, schéma reconstruit + Flyway).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PeerQualityQuorumPgTest {

  static Vertx vertx;
  static PgPool pool;
  static org.testcontainers.containers.PostgreSQLContainer<?> pgContainer;
  static ConfigRepository configRepository;

  @BeforeAll
  static void setup() throws Exception {
    String envHost = System.getenv("CSP_DB_HOST");
    String envPort = System.getenv("CSP_DB_PORT");
    String envDb = System.getenv("CSP_DB_NAME");
    String envUser = System.getenv("CSP_DB_USER");
    String envPass = System.getenv("CSP_DB_PASSWORD");
    String pgHost;
    int pgPort;
    String pgDb;
    String pgUser;
    String pgPass;
    boolean useEnv = false;
    if (envHost != null && !envHost.isBlank() && envPass != null && !envPass.isBlank()) {
      int p = envPort != null ? Integer.parseInt(envPort) : 5432;
      if (isReachable(envHost, p)) {
        pgHost = envHost; pgPort = p; pgDb = envDb != null ? envDb : "csp_config";
        pgUser = envUser != null ? envUser : "csp_app"; pgPass = envPass; useEnv = true;
      } else {
        pgHost = null; pgPort = 0; pgDb = null; pgUser = null; pgPass = null;
      }
    } else {
      pgHost = null; pgPort = 0; pgDb = null; pgUser = null; pgPass = null;
    }
    if (!useEnv && isReachable("localhost", 5433)) {
      pgHost = "localhost"; pgPort = 5433; pgDb = "testdb"; pgUser = "testuser";
      pgPass = "testpass"; useEnv = true;
    }
    if (!useEnv) {
      if (System.getenv("DOCKER_HOST") == null) {
        java.io.File sock = new java.io.File("/run/user/1000/docker.sock");
        if (sock.exists()) System.setProperty("DOCKER_HOST", "unix:///run/user/1000/docker.sock");
      }
      pgContainer = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:15-alpine")
          .withDatabaseName("csp_config").withUsername("csp_app").withPassword("csp_test_pass");
      pgContainer.start();
      pgHost = pgContainer.getHost(); pgPort = pgContainer.getMappedPort(5432);
      pgDb = pgContainer.getDatabaseName(); pgUser = pgContainer.getUsername();
      pgPass = pgContainer.getPassword();
    }
    String jdbcUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
         java.sql.Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA public CASCADE");
      st.execute("CREATE SCHEMA public");
    }
    Flyway.configure().dataSource(jdbcUrl, pgUser, pgPass)
        .locations("classpath:db/migration").load().migrate();
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
         java.sql.Statement st = c.createStatement();
         java.sql.ResultSet rs = st.executeQuery(
             "SELECT max(version) FROM flyway_schema_history WHERE success = true")) {
      assertTrue(rs.next() && rs.getString(1) != null, "flyway_schema_history doit exister");
      assertTrue(rs.getString(1).compareTo("16") >= 0, "V16 doit être appliquée, eu : " + rs.getString(1));
    }
    vertx = Vertx.vertx();
    pool = PgPool.pool(vertx,
        new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb)
            .setUser(pgUser).setPassword(pgPass),
        new PoolOptions().setMaxSize(10));
    configRepository = new ConfigRepository(pool);
  }

  @AfterAll
  static void teardown() {
    if (pool != null) pool.close();
    if (vertx != null) vertx.close();
    if (pgContainer != null) pgContainer.stop();
  }

  private static boolean isReachable(String h, int p) {
    try (java.net.Socket s = new java.net.Socket()) {
      s.connect(new java.net.InetSocketAddress(h, p), 2000);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static <T> T await(io.vertx.core.Future<T> f) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<T> ok = new AtomicReference<>();
    AtomicReference<Throwable> ko = new AtomicReference<>();
    f.onComplete(ar -> {
      if (ar.succeeded()) ok.set(ar.result());
      else ko.set(ar.cause());
      latch.countDown();
    });
    try {
      assertTrue(latch.await(15, TimeUnit.SECONDS), "future should complete");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    if (ko.get() != null) {
      if (ko.get() instanceof RuntimeException re) throw re;
      throw new RuntimeException(ko.get());
    }
    return ok.get();
  }

  @Test
  @Order(1)
  @DisplayName("V16 : singletons présents avec défauts off (opt-in)")
  void v16DefaultsOff() {
    var q = await(configRepository.getPeerQuarantine());
    assertFalse(q.enabled(), "quarantaine off par défaut");
    assertEquals("observe", q.mode());
    assertEquals(1L, q.version());
    var quorum = await(configRepository.getCacheExQuorum());
    assertEquals("off", quorum.mode(), "quorum off par défaut");
    assertEquals(2, quorum.minOrigins());
    assertEquals(1L, quorum.version());
  }

  @Test
  @Order(2)
  @DisplayName("V16 : loadAll expose quarantine + quorum")
  void loadAllExposesV16() {
    var snap = await(configRepository.loadAll());
    assertNotNull(snap.quarantine());
    assertNotNull(snap.quorum());
    assertFalse(snap.quarantine().enabled());
    assertEquals("off", snap.quorum().mode());
  }

  @Test
  @Order(3)
  @DisplayName("V16 : update quarantaine (optimistic lock, bump version)")
  void updateQuarantine() {
    var current = await(configRepository.getPeerQuarantine());
    var cfg = new PeerQuarantineConfig(true, "observe", 20, 8_000,
        120_000L, 300_000L, 128, current.version());
    long nv = await(configRepository.updatePeerQuarantine(cfg, "tester"));
    assertEquals(current.version() + 1, nv);
    var reloaded = await(configRepository.getPeerQuarantine());
    assertTrue(reloaded.enabled());
    assertEquals("observe", reloaded.mode());
    assertEquals(20, reloaded.minSamples());

    // Version périmée => conflit (pas d'écriture silencieuse).
    Throwable t = null;
    try {
      await(configRepository.updatePeerQuarantine(cfg, "tester"));
    } catch (Exception e) {
      t = e;
    }
    assertNotNull(t, "version stale doit échouer");
    assertTrue(t instanceof OptimisticLockException, "conflit optimiste attendu, eu : " + t);
  }

  @Test
  @Order(4)
  @DisplayName("V16 : update quorum ciblé (profils/CAIDs, optimistic lock)")
  void updateQuorum() {
    var current = await(configRepository.getCacheExQuorum());
    var cfg = new CacheExQuorumConfig("enforce", 3, 15_000L, 512,
        List.of("sat"), List.of(0x100, 0x500), current.version());
    long nv = await(configRepository.updateCacheExQuorum(cfg, "tester"));
    assertEquals(current.version() + 1, nv);
    var reloaded = await(configRepository.getCacheExQuorum());
    assertEquals("enforce", reloaded.mode());
    assertEquals(3, reloaded.minOrigins());
    assertEquals(List.of("sat"), reloaded.profiles());
    assertEquals(List.of(0x100, 0x500), reloaded.caids());

    var snap = await(configRepository.loadAll());
    assertEquals("enforce", snap.quorum().mode(), "loadAll reflète le quorum");
    assertTrue(snap.quorum().appliesTo("sat", 0x100));
    assertFalse(snap.quorum().appliesTo("dvbt", 0x100));
  }
}
