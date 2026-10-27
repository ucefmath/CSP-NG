package com.bowman.cspng.app;

import com.bowman.cspng.config.BulkPatches;
import com.bowman.cspng.config.BulkUnknownItemsException;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.config.CwCycleConfig;
import com.bowman.cspng.config.OptimisticLockException;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
import com.bowman.cspng.users.CacheExPolicy;
import com.bowman.cspng.users.UserBulkPatch;
import com.bowman.cspng.users.UserProtocolSecretRepository;
import com.bowman.cspng.users.UserRepository;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PG V15 (contrats v2) : migration V15, bulk atomique 4 ressources
 * (versions par ligne, rollback total), CRUD cw-cycle, politique cacheex
 * des secrets users.
 *
 * <p>Infrastructure : localhost:5433 (container dédié csp-ng-test-pg-proxy,
 * jamais prod) ou Testcontainers — même pattern que
 * {@link UserProtocolSecretRepositoryPgTest}. Schéma reconstruit + Flyway.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkCacheexCwCyclePgTest {

  static Vertx vertx;
  static PgPool pool;
  static org.testcontainers.containers.PostgreSQLContainer<?> pgContainer;
  static ConfigRepository configRepository;
  static UserRepository userRepository;
  static UserProtocolSecretRepository secretRepository;

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
    vertx = Vertx.vertx();
    pool = PgPool.pool(vertx,
        new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb)
            .setUser(pgUser).setPassword(pgPass),
        new PoolOptions().setMaxSize(10));
    configRepository = new ConfigRepository(pool);
    userRepository = new UserRepository(pool);
    secretRepository = new UserProtocolSecretRepository(pool);
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

  /** Ensemble de présence simulant le JsonNode du patch (contrat v2 §2). */
  private static java.util.Set<String> present(String... keys) {
    return new java.util.HashSet<>(java.util.Arrays.asList(keys));
  }

  private static Throwable awaitFail(io.vertx.core.Future<?> f) {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> ko = new AtomicReference<>();
    f.onComplete(ar -> {
      if (ar.succeeded()) ko.set(new AssertionError("expected failure"));
      else ko.set(ar.cause());
      latch.countDown();
    });
    try {
      assertTrue(latch.await(15, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
    assertNotNull(ko.get());
    return ko.get();
  }

  // ============================================================
  // V15 : migration appliquée
  // ============================================================

  @Test
  @Order(1)
  @DisplayName("V15 : colonnes cacheex + table cw-cycle présentes")
  void v15MigrationApplied() {
    var cols = await(pool.preparedQuery(
        "SELECT table_name, column_name FROM information_schema.columns"
            + " WHERE table_name IN ('csp_connector','csp_user_protocol_secret','csp_cw_cycle_config')"
            + " AND column_name IN ('cacheex_max_hop','cacheex_mode','cacheex_filter','enabled')")
        .execute());
    Set<String> found = new java.util.HashSet<>();
    cols.forEach(r -> found.add(r.getString("table_name") + "." + r.getString("column_name")));
    assertTrue(found.contains("csp_connector.cacheex_max_hop"), "csp_connector.cacheex_max_hop");
    assertTrue(found.contains("csp_user_protocol_secret.cacheex_mode"), "secret cacheex_mode");
    assertTrue(found.contains("csp_user_protocol_secret.cacheex_max_hop"), "secret cacheex_max_hop");
    assertTrue(found.contains("csp_user_protocol_secret.cacheex_filter"), "secret cacheex_filter");
    assertTrue(found.contains("csp_cw_cycle_config.enabled"), "csp_cw_cycle_config");
  }

  // ============================================================
  // Bulk connecteurs : succès + rollback conflit + rollback inconnu
  // ============================================================

  private static ConnectorConfig seedConnector(String name, int port) {
    return new ConnectorConfig(name, "bulk-prof", "newcamd", true, false,
        "127.0.0.1", port, "user-" + name, "env:P_" + name, "env:D_" + name,
        5000, 5000, null, null, null, null, null, null,
        false, null, null, 0, null, null, null, null, null,
        50, 10, 1L, null, false, null, "legacy", null, "plaintext");
  }

  private static void seedProfileAndConnectors() {
    await(configRepository.upsertProfile(new ProfileConfig("bulk-prof", true, false, false,
        0x0100, 0, null, null, 1000L, null, false, 0, null, null, null, null, 1L), "tester"));
    await(configRepository.upsertConnector(seedConnector("conn-b1", 10001), "tester"));
    await(configRepository.upsertConnector(seedConnector("conn-b2", 10002), "tester"));
    await(configRepository.upsertConnector(seedConnector("conn-b3", 10003), "tester"));
  }

  @Test
  @Order(2)
  @DisplayName("Bulk connecteurs : patch atomique, nouvelles versions, valeurs appliquées")
  void bulkConnectorsSuccess() {
    seedProfileAndConnectors();
    var patch = new BulkPatches.ConnectorPatch(false, null, 7000, null, null, null, null,
        null, null, null, null, 10, null, null, null, null, null, null, null, 2,
        "0100:00006A", 1500, 8);
    var res = await(configRepository.bulkUpdateConnectors(List.of(
        new BulkPatches.BulkItem("conn-b1", 1L),
        new BulkPatches.BulkItem("conn-b2", 1L)), patch,
        present("enabled", "connectTimeoutMs", "priority", "cacheexMode", "cacheexFilter",
            "cacheexMaxSend", "cacheexMaxHop"), "tester"));
    assertEquals(2, res.size());
    // Ordre déterministe croissant
    assertEquals("conn-b1", res.get(0).id());
    assertEquals("conn-b2", res.get(1).id());
    assertEquals(2L, res.get(0).version());
    assertEquals(2L, res.get(1).version());

    var snap = await(configRepository.loadAll());
    var byName = snap.connectorByName();
    assertFalse(byName.get("conn-b1").enabled());
    assertEquals(10, byName.get("conn-b1").priority());
    assertEquals(7000, byName.get("conn-b1").connectTimeoutMs());
    assertEquals(2, byName.get("conn-b1").cacheexMode());
    assertEquals("0100:00006A", byName.get("conn-b1").cacheexFilter());
    assertEquals(1500, byName.get("conn-b1").cacheexMaxSend());
    assertEquals(8, byName.get("conn-b1").cacheexMaxHop());
    // conn-b3 intact
    assertTrue(byName.get("conn-b3").enabled());
    assertEquals(1L, byName.get("conn-b3").version());
  }

  @Test
  @Order(3)
  @DisplayName("Bulk connecteurs : conflit de version => 409 + ROLLBACK total")
  void bulkConnectorsConflictRollsBack() {
    // conn-b1 est en v2 (test précédent), on fournit v1 stale + conn-b2 v2 OK
    var patch = new BulkPatches.ConnectorPatch(true, null, null, null, null, null, null,
        null, null, null, null, 99, null, null, null, null, null, null, null, null,
        null, null, null);
    Throwable t = awaitFail(configRepository.bulkUpdateConnectors(List.of(
        new BulkPatches.BulkItem("conn-b1", 1L),
        new BulkPatches.BulkItem("conn-b2", 2L)), patch, present("enabled", "priority"), "tester"));
    assertInstanceOf(OptimisticLockException.class, t, "conflit => 409");

    // ROLLBACK : conn-b2 n'a PAS été modifié (priority restée 10, version 2)
    var snap = await(configRepository.loadAll());
    var byName = snap.connectorByName();
    assertEquals(2L, byName.get("conn-b2").version());
    assertEquals(10, byName.get("conn-b2").priority());
    assertEquals(2L, byName.get("conn-b1").version());
  }

  @Test
  @Order(4)
  @DisplayName("Bulk connecteurs : item inconnu => 400 UNKNOWN_ITEMS + ROLLBACK total")
  void bulkConnectorsUnknownRollsBack() {
    var patch = new BulkPatches.ConnectorPatch(true, null, null, null, null, null, null,
        null, null, null, null, 99, null, null, null, null, null, null, null, null,
        null, null, null);
    Throwable t = awaitFail(configRepository.bulkUpdateConnectors(List.of(
        new BulkPatches.BulkItem("conn-b2", 2L),
        new BulkPatches.BulkItem("ghost", 1L)), patch, present("enabled", "priority"), "tester"));
    assertInstanceOf(BulkUnknownItemsException.class, t);
    assertEquals(List.of("ghost"), ((BulkUnknownItemsException) t).unknownIds());

    var snap = await(configRepository.loadAll());
    assertEquals(2L, snap.connectorByName().get("conn-b2").version());
    assertEquals(10, snap.connectorByName().get("conn-b2").priority());
  }

  // ============================================================
  // Bulk : sémantique null explicite (contrat v2 §2 révisé C3)
  // ============================================================

  @Test
  @Order(45)
  @DisplayName("Bulk : null explicite sur NULLABLE => reset (filtre effacé)")
  void bulkNullResetsNullable() {
    // conn-b1 a cacheexFilter="0100:00006A" (seed Order 2) ; reset via null explicite
    var patch = new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null);
    var res = await(configRepository.bulkUpdateConnectors(List.of(
        new BulkPatches.BulkItem("conn-b1", 2L)), patch, present("cacheexFilter"), "tester"));
    assertEquals(3L, res.get(0).version());

    var snap = await(configRepository.loadAll());
    assertNull(snap.connectorByName().get("conn-b1").cacheexFilter(),
        "null explicite sur colonne NULLABLE = reset");
  }

  @Test
  @Order(46)
  @DisplayName("Bulk : null explicite sur NOT NULL => 400 + rollback total")
  void bulkNullOnNotNullFails() {
    var patch = new BulkPatches.ConnectorPatch(null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null);
    // priority est NOT NULL : null explicite => 400, rien d'appliqué
    Throwable t = awaitFail(configRepository.bulkUpdateConnectors(List.of(
        new BulkPatches.BulkItem("conn-b2", 2L),
        new BulkPatches.BulkItem("conn-b3", 1L)), patch, present("priority"), "tester"));
    assertInstanceOf(com.bowman.cspng.config.ConfigValidationException.class, t);

    var snap = await(configRepository.loadAll());
    assertEquals(2L, snap.connectorByName().get("conn-b2").version());
    assertEquals(1L, snap.connectorByName().get("conn-b3").version());
  }

  @Test
  @Order(47)
  @DisplayName("Bulk users : [] vide un set, null explicite sur set => 400")
  void bulkUserSetSemantics() {
    // [] sur profiles = vider le set (remplacement)
    var clearPatch = new UserBulkPatch(null, null, null, null, null, null, null, null,
        Set.of(), null, null, null);
    var res = await(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u1", 2L)), clearPatch,
        present("profiles"), "tester"));
    assertEquals(3L, res.get(0).version());
    assertTrue(await(userRepository.get("bulk-u1")).orElseThrow().profiles().isEmpty(),
        "[] vide le set");

    // null explicite sur set => 400 (omettre pour inchangé)
    var nullSetPatch = new UserBulkPatch(null, null, null, null, null, null, null, null,
        null, null, null, null);
    Throwable t = awaitFail(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u1", 3L)), nullSetPatch,
        present("profiles"), "tester"));
    assertInstanceOf(IllegalArgumentException.class, t);
    assertEquals(3L, await(userRepository.get("bulk-u1")).orElseThrow().version());

    // null explicite sur email (NULLABLE) => reset NULL
    var nullEmailPatch = new UserBulkPatch(null, null, null, null, null, null, null, null,
        null, null, null, null);
    var res2 = await(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u2", 2L)), nullEmailPatch,
        present("email"), "tester"));
    assertEquals(3L, res2.get(0).version());
    assertNull(await(userRepository.get("bulk-u2")).orElseThrow().email());

    // null explicite sur enabled (NOT NULL) => 400
    var nullEnabledPatch = new UserBulkPatch(null, null, null, null, null, null, null, null,
        null, null, null, null);
    Throwable t2 = awaitFail(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u2", 3L)), nullEnabledPatch,
        present("enabled"), "tester"));
    assertInstanceOf(IllegalArgumentException.class, t2);
    assertEquals(3L, await(userRepository.get("bulk-u2")).orElseThrow().version());
  }

  // ============================================================
  // Bulk profils / listeners / users
  // ============================================================

  @Test
  @Order(5)
  @DisplayName("Bulk profils : patch + rollback conflit")
  void bulkProfiles() {
    var patch = new BulkPatches.ProfilePatch(false, null, null, null, null, 2000L, null,
        null, null, null, null, null, null);
    var res = await(configRepository.bulkUpdateProfiles(
        List.of(new BulkPatches.BulkItem("bulk-prof", 1L)), patch, present("enabled", "maxCwWaitMs"), "tester"));
    assertEquals(2L, res.get(0).version());

    var snap = await(configRepository.loadAll());
    assertFalse(snap.profileByName().get("bulk-prof").enabled());
    assertEquals(2000L, snap.profileByName().get("bulk-prof").maxCwWaitMs());

    Throwable t = awaitFail(configRepository.bulkUpdateProfiles(
        List.of(new BulkPatches.BulkItem("bulk-prof", 1L)), patch, present("enabled", "maxCwWaitMs"), "tester"));
    assertInstanceOf(OptimisticLockException.class, t);
    assertEquals(2L, await(configRepository.loadAll()).profileByName().get("bulk-prof").version());
  }

  @Test
  @Order(6)
  @DisplayName("Bulk listeners : patch + rollback conflit")
  void bulkListeners() {
    await(configRepository.upsertListener(new ProfileListenerConfig(0, "bulk-prof", "cccam",
        14001, "127.0.0.1", true, null, null, null, 1L, "manual", true, 0,
        512, 32, 5, null, 60), "tester"));
    await(configRepository.upsertListener(new ProfileListenerConfig(0, "bulk-prof", "cs378x",
        14002, "127.0.0.1", true, null, null, null, 1L, "manual", true, 0,
        512, 32, 5, null, 60), "tester"));
    var rows = await(pool.preparedQuery(
        "SELECT id, version FROM csp_profile_listener WHERE profile_name='bulk-prof' ORDER BY id")
        .execute());
    List<BulkPatches.BulkItem> items = new java.util.ArrayList<>();
    rows.forEach(r -> items.add(new BulkPatches.BulkItem(
        String.valueOf(r.getLong("id")), r.getLong("version"))));
    assertEquals(2, items.size());

    var patch = new BulkPatches.ListenerPatch(false, null, null, null, null, 16, null, null, null, null);
    var res = await(configRepository.bulkUpdateListeners(items, patch,
        present("enabled", "maxInflight"), "tester"));
    assertEquals(2, res.size());

    var snap = await(configRepository.loadAll());
    for (var l : snap.profileListeners()) {
      if (l.profileName().equals("bulk-prof")) {
        assertFalse(l.enabled());
        assertEquals(16, l.maxInflight());
      }
    }

    // Conflit : versions stale
    Throwable t = awaitFail(configRepository.bulkUpdateListeners(items, patch,
        present("enabled", "maxInflight"), "tester"));
    assertInstanceOf(OptimisticLockException.class, t);
  }

  @Test
  @Order(7)
  @DisplayName("Bulk users : patch + last-admin guard + rollback conflit")
  void bulkUsers() {
    // Seed direct SQL (hash bcrypt factice conforme au CHECK)
    await(pool.preparedQuery(
        "INSERT INTO csp_user (name, password_hash, admin, enabled, version) VALUES "
            + "('bulk-admin', '$2a$10$abcdefghijklmnopqrstuu', true, true, 1),"
            + "('bulk-u1', '$2a$10$abcdefghijklmnopqrstuu', false, true, 1),"
            + "('bulk-u2', '$2a$10$abcdefghijklmnopqrstuu', false, true, 1)"
            + " ON CONFLICT (name) DO NOTHING").execute());

    var patch = new UserBulkPatch(null, null, null, null, null, 5, null, null, null, null, null, null);
    var res = await(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u1", 1L),
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u2", 1L)), patch,
        present("maxConnections"), "tester"));
    assertEquals(2, res.size());
    var byId = res.stream().collect(Collectors.toMap(UserBulkPatch.BulkResult::id, r -> r.version()));
    assertEquals(2L, byId.get("bulk-u1"));
    assertEquals(2L, byId.get("bulk-u2"));

    var u1 = await(userRepository.get("bulk-u1")).orElseThrow();
    assertEquals(5, u1.maxConnections());

    // Conflit : rollback total (bulk-u2 garde version 2)
    var patch2 = new UserBulkPatch(null, null, null, null, null, 9, null, null, null, null, null, null);
    Throwable t = awaitFail(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u1", 1L),
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-u2", 2L)), patch2,
        present("maxConnections"), "tester"));
    assertInstanceOf(com.bowman.cspng.users.UserOptimisticLockException.class, t);
    assertEquals(2L, await(userRepository.get("bulk-u2")).orElseThrow().version());
    assertEquals(5, await(userRepository.get("bulk-u2")).orElseThrow().maxConnections());

    // Garde last-admin : désactiver le seul admin actif => rejet, rien d'appliqué
    var disablePatch = new UserBulkPatch(null, null, false, null, null, null, null, null, null, null, null, null);
    Throwable t2 = awaitFail(userRepository.bulkUpdateUsers(List.of(
        new java.util.AbstractMap.SimpleImmutableEntry<>("bulk-admin", 1L)), disablePatch,
        present("enabled"), "tester"));
    assertInstanceOf(com.bowman.cspng.users.LastAdminException.class, t2);
    assertTrue(await(userRepository.get("bulk-admin")).orElseThrow().enabled());
  }

  // ============================================================
  // CW-cycle CRUD + snapshot
  // ============================================================

  @Test
  @Order(8)
  @DisplayName("CW-cycle : défaut snapshot, update versionné, conflit, reload snapshot")
  void cwCycleCrud() {
    var snap0 = await(configRepository.loadAll());
    assertNotNull(snap0.cwCycle());
    assertFalse(snap0.cwCycle().enabled());

    var cfg = new CwCycleConfig(true, List.of(0x0100, 0x0500), 600, 20, "log", false, true, 1L);
    long v2 = await(configRepository.updateCwCycle(cfg, "tester"));
    assertEquals(2L, v2);

    var snap = await(configRepository.loadAll());
    assertTrue(snap.cwCycle().enabled());
    assertEquals(List.of(0x0100, 0x0500), snap.cwCycle().caids());
    assertEquals(600, snap.cwCycle().maxList());
    assertEquals(20, snap.cwCycle().keepTimeSec());
    assertEquals("log", snap.cwCycle().onBad());
    assertFalse(snap.cwCycle().dropOld());
    assertTrue(snap.cwCycle().usePeerCycleMetadata());

    Throwable t = awaitFail(configRepository.updateCwCycle(cfg, "tester"));
    assertInstanceOf(OptimisticLockException.class, t);
  }

  // ============================================================
  // Secrets users : politique cacheex persistée (refs only)
  // ============================================================

  @Test
  @Order(9)
  @DisplayName("Secrets users : upsert/get avec politique cacheex, puis off")
  void userSecretCacheExPolicy() {
    var upsertReq = new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
        "file:/run/secrets/bulk-u1", null, true,
        new CacheExPolicy(3, 7, "0100:00006A"));
    var rec = await(secretRepository.upsert("bulk-u1",
        UserProtocolSecretRepository.Protocol.CCAM, upsertReq, 0, "tester"));
    assertEquals(1L, rec.version());
    assertNotNull(rec.cacheex());
    assertEquals(3, rec.cacheex().mode());
    assertEquals(7, rec.cacheex().maxHop());
    assertEquals("0100:00006A", rec.cacheex().filter());

    var got = await(secretRepository.get("bulk-u1",
        UserProtocolSecretRepository.Protocol.CCAM)).orElseThrow();
    assertEquals(3, got.cacheex().mode());
    assertTrue(got.cacheex().enabled());

    // Passage à off : colonnes NULLées
    var offReq = new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
        "file:/run/secrets/bulk-u1", null, true, CacheExPolicy.OFF);
    var rec2 = await(secretRepository.upsert("bulk-u1",
        UserProtocolSecretRepository.Protocol.CCAM, offReq, rec.version(), "tester"));
    assertFalse(rec2.cacheex().enabled());
    assertEquals(0, rec2.cacheex().mode());
  }
}
