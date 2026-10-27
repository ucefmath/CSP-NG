package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.OptimisticLockException;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
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

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ConfigRepositoryListenerPgTest — Persistance listener PostgreSQL réelle, ID positive UPDATE vs INSERT, versioning optimiste et tous les champs préservés")
class ConfigRepositoryListenerPgTest {

  static Vertx vertx;
  static PgPool pool;
  static org.testcontainers.containers.PostgreSQLContainer<?> pgContainer;
  static String pgHost, pgDb, pgUser, pgPass;
  static int pgPort;

  @BeforeAll
  static void setup() throws Exception {
    String envHost = System.getenv("CSP_DB_HOST");
    String envPort = System.getenv("CSP_DB_PORT");
    String envDb = System.getenv("CSP_DB_NAME");
    String envUser = System.getenv("CSP_DB_USER");
    String envPass = System.getenv("CSP_DB_PASSWORD");
    boolean useEnv = false;
    if (envHost != null && !envHost.isBlank() && envPass != null && !envPass.isBlank()) {
      int p = envPort != null ? Integer.parseInt(envPort) : 5432;
      if (isReachable(envHost, p)) {
        pgHost = envHost; pgPort = p; pgDb = envDb != null ? envDb : "csp_config";
        pgUser = envUser != null ? envUser : "csp_app"; pgPass = envPass; useEnv = true;
      }
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
    vertx = Vertx.vertx();
    pool = PgPool.pool(vertx,
        new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb)
            .setUser(pgUser).setPassword(pgPass),
        new PoolOptions().setMaxSize(10));
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
  @DisplayName("Création d'un profil puis création d'un listener (id=0) -> insertion avec version 1")
  void createListenerInsertsWithVersion1() {
    ConfigRepository repo = new ConfigRepository(pool);

    // 1. Créer le profil requis
    ProfileConfig p = new ProfileConfig(
        "test_profile", true, false, false, 0x0100, 0, false, false,
        5000L, null, false, 0, null, null, null, false, 1L);
    long pVer = await(repo.upsertProfile(p, "test_admin"));
    assertEquals(1L, pVer);

    // 2. Insérer le listener avec id=0
    ProfileListenerConfig l1 = new ProfileListenerConfig(
        0L, "test_profile", "cccam", 12000, "0.0.0.0",
        true, null, "127.0.0.1", "10.0.0.1", 1L,
        "wildcard", true, 3, 256, 16, 10, "0102030405060708", 120);

    long ver1 = await(repo.upsertListener(l1, "test_admin"));
    assertEquals(1L, ver1);

    ConfigSnapshot snap = await(repo.loadAll());
    ProfileListenerConfig read = snap.profileListeners().stream()
        .filter(l -> "test_profile".equals(l.profileName()))
        .findFirst().orElseThrow();
    assertTrue(read.id() > 0, "ID généré par la séquence PostgreSQL");
    assertEquals("test_profile", read.profileName());
    assertEquals("cccam", read.protocol());
    assertEquals(12000, read.port());
    assertEquals("wildcard", read.cardMode());
    assertTrue(read.strictPolicy());
    assertEquals(3, read.reshareCap());
    assertEquals(256, read.maxCards());
    assertEquals(16, read.maxInflight());
    assertEquals(10, read.maxPerIp());
    assertEquals("0102030405060708", read.nodeId());
    assertEquals(120, read.idleTimeoutSec());
    assertEquals(1L, read.version());
  }

  @Test
  @Order(2)
  @DisplayName("Mise à jour d'un listener existant (id > 0, version 1) -> UPDATE sans duplication, version 2")
  void updateListenerUpdatesExistingRowWithoutDuplicate() {
    ConfigRepository repo = new ConfigRepository(pool);

    ConfigSnapshot snap = await(repo.loadAll());
    ProfileListenerConfig existing = snap.profileListeners().stream()
        .filter(l -> "test_profile".equals(l.profileName()))
        .findFirst().orElseThrow();
    long existingId = existing.id();

    // Mise à jour avec le MÊME id > 0 et version 1
    ProfileListenerConfig update = new ProfileListenerConfig(
        existingId, "test_profile", "cccam", 12001, "0.0.0.0",
        true, "file:/run/secrets/des", "127.0.0.1,192.168.1.0/24", null, 1L,
        "manual", true, 5, 512, 32, 20, "1122334455667788", 300);

    long ver2 = await(repo.upsertListener(update, "test_admin"));
    assertEquals(2L, ver2, "Version doit être incrémentée à 2");

    ConfigSnapshot snap2 = await(repo.loadAll());
    ProfileListenerConfig readUpdated = snap2.profileListeners().stream()
        .filter(l -> "test_profile".equals(l.profileName()))
        .findFirst().orElseThrow();
    assertEquals(existingId, readUpdated.id(), "L'ID doit être STRICTEMENT identique");
    assertEquals(12001, readUpdated.port());
    assertEquals("file:/run/secrets/des", readUpdated.desKeyRef());
    assertEquals("127.0.0.1,192.168.1.0/24", readUpdated.allowList());
    assertNull(readUpdated.denyList());
    assertEquals("manual", readUpdated.cardMode());
    assertEquals(5, readUpdated.reshareCap());
    assertEquals(512, readUpdated.maxCards());
    assertEquals(32, readUpdated.maxInflight());
    assertEquals(20, readUpdated.maxPerIp());
    assertEquals("1122334455667788", readUpdated.nodeId());
    assertEquals(300, readUpdated.idleTimeoutSec());
    assertEquals(2L, readUpdated.version());
  }

  @Test
  @Order(3)
  @DisplayName("Mise à jour avec version obsolète (version 1 alors que version actuelle est 2) -> OptimisticLockException")
  void staleVersionThrowsOptimisticLockException() {
    ConfigRepository repo = new ConfigRepository(pool);

    ConfigSnapshot snap = await(repo.loadAll());
    ProfileListenerConfig existing = snap.profileListeners().stream()
        .filter(l -> "test_profile".equals(l.profileName()))
        .findFirst().orElseThrow();

    // Tentative avec version 1 (stale)
    ProfileListenerConfig staleUpdate = new ProfileListenerConfig(
        existing.id(), "test_profile", "cccam", 12002, "0.0.0.0",
        true, null, null, null, 1L,
        "manual", true, 0, 512, 32, 5, null, 60);

    assertThrows(OptimisticLockException.class, () ->
        await(repo.upsertListener(staleUpdate, "test_admin")));
  }
}
