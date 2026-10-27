package com.bowman.cspng.app;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.catalog.CardSource;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.connectors.routing.StrictPartitionedCache;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("InboundCamsLifecycleIntegrationTest — Lifecycle Injection, Découverte PG réelle, États Manual/Published/Blocked, et Provider Wire non-zéro")
class InboundCamsLifecycleIntegrationTest {

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

  private static final byte[] CW_CONST = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
  private static final byte[] SERVER_NODE_ID = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

  private static class DummyConnector extends AbstractConnector {
    DummyConnector(String name, String profile) {
      super(null, null, name, "127.0.0.1", 1000, profile);
    }
    @Override public boolean isConnected() { return true; }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      return Future.succeededFuture(new CwReply(request.key(), request.key().commandTag(), CW_CONST, getName(), "127.0.0.1", System.currentTimeMillis()));
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  @Test
  @Order(1)
  @DisplayName("CardDiscoveryService avec CardCatalogRepository PG : persistance réelle et allocation PG ID")
  void cardDiscoveryWithRealPgCatalog() {
    CardCatalogRepository catalogRepo = new CardCatalogRepository(pool);
    CardDiscoveryService discovery = new CardDiscoveryService(vertx, catalogRepo);

    discovery.onConnectorConnected("cws1", 1L);

    // Découverte d'une carte (upstreamReshare=2, connectorCap=5, generation=1L)
    await(discovery.onCardDiscovered("cws1", "default", 0x0100, 0x003311, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L));

    var cap = discovery.getCapability("default", 0x0100, 0x003311);
    assertNotNull(cap);
    assertTrue(cap.persistentCardId > 0, "Card ID persistant alloué");
    assertEquals(CardCapability.State.DISCOVERED, cap.state);

    // En mode manual (défaut) : non annoncé tant qu'il n'est pas PUBLISHED
    List<byte[]> initialAnnouncements = discovery.getInitialAnnouncements(
        "default", "manual", 2, 512, SERVER_NODE_ID, CardDiscoveryService.UserAclContext.unconstrained());
    assertEquals(0, initialAnnouncements.size(), "Non annoncé en mode manual si non PUBLISHED");

    // Transition vers PUBLISHED
    discovery.updateCapabilityState("default", 0x0100, 0x003311, CardCapability.State.PUBLISHED);
    List<byte[]> pubAnnouncements = discovery.getInitialAnnouncements(
        "default", "manual", 2, 512, SERVER_NODE_ID, CardDiscoveryService.UserAclContext.unconstrained());
    assertEquals(1, pubAnnouncements.size(), "Annoncé en mode manual une fois PUBLISHED");

    // Transition vers BLOCKED
    discovery.updateCapabilityState("default", 0x0100, 0x003311, CardCapability.State.BLOCKED);
    List<byte[]> blkAnnouncements = discovery.getInitialAnnouncements(
        "default", "wildcard", 2, 512, SERVER_NODE_ID, CardDiscoveryService.UserAclContext.unconstrained());
    assertEquals(0, blkAnnouncements.size(), "Non annoncé en mode wildcard si BLOCKED");
  }

  @Test
  @Order(2)
  @DisplayName("StrictEcmRouter avec provider wire non-zéro : préservé dans cache et fail-closed si capacité BLOCKED")
  void strictRouterPreservesWireProviderAndEnforcesBlockedState() {
    CardCatalogRepository catalogRepo = new CardCatalogRepository(pool);
    CardDiscoveryService discovery = new CardDiscoveryService(vertx, catalogRepo);
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);

    DummyConnector conn1 = new DummyConnector("cws1", "default");
    cm.addTestConnector(conn1);

    StrictPartitionedCache cache = new StrictPartitionedCache();
    StrictEcmRouter router = new StrictEcmRouter(cm, cache, catalogRepo, discovery);

    int wireProvider = 0x003311;
    discovery.onConnectorConnected("cws1", 1L);
    await(discovery.onCardDiscovered("cws1", "default", 0x0100, wireProvider, 1001L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L));

    EcmKey key = new EcmKey(0x80, 1, 0, 0x0100, 9999);
    EcmRequest req = new EcmRequest(key, new byte[]{1, 2, 3}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000L);
    ProtocolAuthResolver.AuthResult auth = new ProtocolAuthResolver.AuthResult(
        "u1", "file:/s", 1L, 1L, Instant.now().plusSeconds(3600), 1L, Set.of("default"), Map.of(), Map.of(), Set.of("cws1"), null);

    // 1. Capacité PUBLISHED -> CW résolue
    discovery.updateCapabilityState("default", 0x0100, wireProvider, CardCapability.State.PUBLISHED);
    CwReply reply = await(router.routeEcm(req, auth, wireProvider));
    assertFalse(reply.isEmpty(), "CW résolue");
    assertArrayEquals(CW_CONST, reply.cw());

    // 2. Capacité BLOCKED -> Fail closed immédiat
    discovery.updateCapabilityState("default", 0x0100, wireProvider, CardCapability.State.BLOCKED);
    CwReply blkReply = await(router.routeEcm(req, auth, wireProvider));
    assertTrue(blkReply.isEmpty(), "Capacité bloquée -> fail closed immédiat sans interroger le connecteur");
  }
}
