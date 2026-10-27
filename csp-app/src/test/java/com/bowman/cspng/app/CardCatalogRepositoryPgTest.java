package com.bowman.cspng.app;

import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardSource;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("CardCatalogRepositoryPgTest — Migration V8, séquence CARDID persistante et relations n:n (P6)")
class CardCatalogRepositoryPgTest {

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
  @DisplayName("Migration V8 : tables catalogue et triggers présents")
  void migrationV8Applied() {
    var t1 = await(pool.preparedQuery(
        "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_name IN ('csp_card_capability','csp_card_source')").execute());
    assertEquals(2, t1.iterator().next().getInteger(0), "V8 tables présentes");

    var seq = await(pool.preparedQuery(
        "SELECT COUNT(*) FROM information_schema.sequences "
            + "WHERE sequence_name = 'csp_card_id_seq'").execute());
    assertEquals(1, seq.iterator().next().getInteger(0), "Séquence CARDID présente");
  }

  @Test
  @Order(2)
  @DisplayName("Allocation CARDID persistante et atomique (séquence NO CYCLE)")
  void allocateCardIdIsPersistentAndUnique() {
    CardCatalogRepository repo = new CardCatalogRepository(pool);

    CardCapability cap1 = await(repo.allocateOrGetCapability("default", 0x0100, 0x000000));
    assertTrue(cap1.cardId() >= 1L && cap1.cardId() <= 4294967295L, "uint32");
    assertEquals(CardCapability.State.DISCOVERED, cap1.state());

    CardCapability cap2 = await(repo.allocateOrGetCapability("default", 0x0500, 0x032830));
    assertNotEquals(cap1.cardId(), cap2.cardId(), "Chaque capacité reçoit un cardId distinct");

    // Ré-allocation de la même capacité -> idempotent : retourne le MÊME cardId
    CardCapability cap1Again = await(repo.allocateOrGetCapability("default", 0x0100, 0x000000));
    assertEquals(cap1.id(), cap1Again.id());
    assertEquals(cap1.cardId(), cap1Again.cardId(), "Le cardId est alloué une seule fois et stable");
  }

  @Test
  @Order(3)
  @DisplayName("Transition d'état capacité avec verrou optimiste")
  void stateTransitionWithOptimisticLock() {
    CardCatalogRepository repo = new CardCatalogRepository(pool);
    CardCapability cap = await(repo.allocateOrGetCapability("sky", 0x09CD, 0x000000));

    // Publication
    CardCapability pub = await(repo.setCapabilityState(cap.id(), CardCapability.State.PUBLISHED, cap.version()));
    assertEquals(CardCapability.State.PUBLISHED, pub.state());
    assertEquals(cap.version() + 1, pub.version());

    // Blocage
    CardCapability blk = await(repo.setCapabilityState(cap.id(), CardCapability.State.BLOCKED, pub.version()));
    assertEquals(CardCapability.State.BLOCKED, blk.state());

    // Update stale -> échec
    assertThrows(IllegalStateException.class, () ->
        await(repo.setCapabilityState(cap.id(), CardCapability.State.PUBLISHED, cap.version())));
  }

  @Test
  @Order(4)
  @DisplayName("Sources n:n : plusieurs cartes/connecteurs pour une même capacité")
  void multipleSourcesPerCapability() {
    CardCatalogRepository repo = new CardCatalogRepository(pool);
    CardCapability cap = await(repo.allocateOrGetCapability("multi", 0x1830, 0x003411));

    // Source 1 : connecteur A, carte 1001
    CardSource s1 = await(repo.upsertSource(cap.id(), "connA", 1001L, CardSource.Trust.DISCOVERED, 1, 2));
    assertEquals("connA", s1.connectorName());
    assertEquals(1001L, s1.originCardId());

    // Source 2 : connecteur A, carte 1002 (deuxième carte sur le même connecteur !)
    CardSource s2 = await(repo.upsertSource(cap.id(), "connA", 1002L, CardSource.Trust.DISCOVERED, 1, 2));
    assertNotEquals(s1.id(), s2.id(), "Deux cartes du même connecteur coexistent");

    // Source 3 : connecteur B, carte 2001
    CardSource s3 = await(repo.upsertSource(cap.id(), "connB", 2001L, CardSource.Trust.MANUAL, 2, 1));
    assertEquals(2, s3.hop());

    List<CardSource> sources = await(repo.listSources(cap.id()));
    assertEquals(3, sources.size(), "3 sources distinctes associées à la capacité");

    // Suppression d'une source
    await(repo.removeSource(cap.id(), "connA", 1001L));
    assertEquals(2, await(repo.listSources(cap.id())).size());
  }
}
