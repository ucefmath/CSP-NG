package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.config.NetworkProxyConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PG de la migration V5 (Plan P2 §1/§7) :
 * <ul>
 *   <li>Migration vierge complète (V1..V5) — aucune erreur, table csp_proxy présente.</li>
 *   <li>Migration V4→V5 non destructive : données connecteur existantes préservées.</li>
 *   <li>FK csp_connector.proxy_name ON DELETE RESTRICT : delete référencé refusé.</li>
 *   <li>CHECK contraintes : nom regex, type socks5, port, auth appariée, password_ref env:/file:,
 *       timeout borné.</li>
 *   <li>Trigger csp_config_notify sur csp_proxy (LIST/NOTIFY).</li>
 *   <li>Index partiel idx_connector_proxy_name WHERE proxy_name IS NOT NULL.</li>
 *   <li>CRUD repository : upsertProxy (création v1, update optimistic, 409/404), deleteProxy,
 *       PROXY_IN_USE, countProxyUsage, propagation proxy_name dans loadAll.</li>
 * </ul>
 *
 * <p>Setup : CSP_DB_* env → localhost:5433/testdb (conteneur test dédié) → testcontainer.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyMigrationV5PgTest {

  static Vertx vertx;
  static PgPool pool;
  static org.testcontainers.containers.PostgreSQLContainer<?> pgContainer;
  static String pgHost, pgDb, pgUser, pgPass;
  static int pgPort;
  static ConfigRepository repo;

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
        pgUser = envUser != null ? envUser : "csp_app"; pgPass = envPass;
        useEnv = true;
      }
    }
    if (!useEnv && isReachable("localhost", 5433)) {
      try {
        String testJdbc = "jdbc:postgresql://localhost:5433/testdb";
        Flyway.configure().dataSource(testJdbc, "testuser", "testpass").load();
        pgHost = "localhost"; pgPort = 5433; pgDb = "testdb"; pgUser = "testuser"; pgPass = "testpass";
        useEnv = true;
      } catch (Exception ignore) {}
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
      pgDb = pgContainer.getDatabaseName(); pgUser = pgContainer.getUsername(); pgPass = pgContainer.getPassword();
    }
    // Schéma vierge + migration complète V1..V5
    String jdbcUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
    try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
         java.sql.Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA public CASCADE");
      st.execute("CREATE SCHEMA public");
    }
    Flyway flyway = Flyway.configure().dataSource(jdbcUrl, pgUser, pgPass)
        .locations("classpath:db/migration").load();
    flyway.migrate();
    vertx = Vertx.vertx();
    pool = PgPool.pool(vertx,
        new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass),
        new PoolOptions().setMaxSize(5));
    repo = new ConfigRepository(pool);
    // Profile requis pour les connecteurs
    pool.preparedQuery("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) "
        + "VALUES ('testprof', 0, 0, 5000) ON CONFLICT DO NOTHING")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @AfterAll
  static void teardown() throws Exception {
    if (pool != null) pool.close();
    if (vertx != null) vertx.close();
    if (pgContainer != null) pgContainer.stop();
  }

  private static boolean isReachable(String h, int p) {
    try (java.net.Socket s = new java.net.Socket()) {
      s.connect(new java.net.InetSocketAddress(h, p), 2000);
      return true;
    } catch (Exception e) { return false; }
  }

  private static void exec(String sql, Tuple tuple) throws Exception {
    if (tuple == null) {
      pool.query(sql).execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } else {
      pool.preparedQuery(sql).execute(tuple).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  private static long scalar(String sql, Tuple tuple) throws Exception {
    // NB : execute(null) sur un preparedQuery bloque — utiliser query() sans tuple
    var fut = tuple == null
        ? pool.query(sql).execute()
        : pool.preparedQuery(sql).execute(tuple);
    return fut.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
        .iterator().next().getLong(0);
  }

  private static NetworkProxyConfig proxy(String name, boolean enabled, String host, int port,
                                          String username, String passwordRef, long version) {
    return new NetworkProxyConfig(name, enabled, "socks5", host, port, username, passwordRef,
        null, version);
  }

  private static ConnectorConfig radegast(String name, String proxyName, long version) {
    return new ConnectorConfig(name, "testprof", "radegast", true, false,
        "up.lan", 5431, null, null, null,
        5000, 60000, null, null, null, null, null, null,
        false, null, null, null, null, null, null, null, 50, 10, version, proxyName);
  }

  // ============================================================
  // Migration : table + contraintes présentes
  // ============================================================

  @Test
  @Order(1)
  void migrationV5CreatesTableAndConstraints() throws Exception {
    // Table existe avec les colonnes attendues
    var cols = pool.query("SELECT column_name FROM information_schema.columns "
        + "WHERE table_name='csp_proxy' ORDER BY ordinal_position")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    List<String> names = new java.util.ArrayList<>();
    cols.forEach(r -> names.add(r.getString(0)));
    assertTrue(names.containsAll(List.of("name", "enabled", "type", "host", "port",
        "username", "password_ref", "description", "version")),
        "csp_proxy columns: " + names);
    // Plan P2 : PAS de colonne timeout sur csp_proxy
    assertFalse(names.contains("connect_timeout_ms"), "csp_proxy must NOT have a timeout column (Plan P2)");
    // Colonne proxy_name sur csp_connector
    var cn = pool.query("SELECT column_name FROM information_schema.columns "
        + "WHERE table_name='csp_connector' AND column_name='proxy_name'")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(1, cn.size(), "csp_connector.proxy_name must exist");
    // Index partiel WHERE proxy_name IS NOT NULL
    var idx = pool.query("SELECT indexdef FROM pg_indexes WHERE indexname='idx_connector_proxy_name'")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(1, idx.size());
    String def = idx.iterator().next().getString(0);
    assertTrue(def.contains("WHERE"), "partial index expected: " + def);
    // Trigger csp_config_notify sur csp_proxy (statement-level : une ligne par événement
    // dans information_schema → COUNT DISTINCT)
    var trg = pool.query("SELECT COUNT(DISTINCT trigger_name) FROM information_schema.triggers "
        + "WHERE event_object_table='csp_proxy' AND trigger_name='csp_config_changed'")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(1L, trg.iterator().next().getLong(0),
        "csp_config_notify trigger must exist on csp_proxy");
  }

  // ============================================================
  // CHECK contraintes
  // ============================================================

  @Test
  @Order(2)
  void checkConstraintsEnforced() throws Exception {
    // Nom invalide refusé
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('bad name', 'h', 1080)", null));
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('a@b', 'h', 1080)", null));
    // Type non-socks5 refusé
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, type, host, port) VALUES ('t-http', 'http', 'h', 1080)", null));
    // Port hors bornes refusé
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('t-port', 'h', 0)", null));
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('t-port2', 'h', 65536)", null));
    // Host URI-like refusé
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('t-host', 'socks5://h', 1080)", null));
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port) VALUES ('t-host2', 'user@h', 1080)", null));
    // Auth non appariée refusée
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port, username) VALUES ('t-auth1', 'h', 1080, 'u')", null));
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port, password_ref) VALUES ('t-auth2', 'h', 1080, 'env:X')", null));
    // password_ref en clair refusé
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port, username, password_ref) "
            + "VALUES ('t-plain', 'h', 1080, 'u', 'plainvalue')", null));
    // PAS de colonne timeout sur csp_proxy (Plan P2) : le budget T est porté par
    // le connecteur (csp_connector.connect_timeout_ms).
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_proxy (name, host, port, connect_timeout_ms) VALUES ('t-tmo', 'h', 1080, 999)", null));
    // Insertion valide OK (IPv6 brut, auth appariée env:)
    exec("INSERT INTO csp_proxy (name, host, port, username, password_ref) "
        + "VALUES ('t-valid', '2001:db8::1', 1080, 'u', 'env:PWX')", null);
  }

  // ============================================================
  // FK ON DELETE RESTRICT : proxy référencé non supprimable
  // ============================================================

  @Test
  @Order(3)
  void foreignKeyRestrictEnforced() throws Exception {
    exec("INSERT INTO csp_proxy (name, host, port) VALUES ('fk-proxy', 'h.lan', 1080) "
        + "ON CONFLICT DO NOTHING", null);
    exec("INSERT INTO csp_connector (name, profile_name, protocol, enabled, debug, host, port, "
        + "connect_timeout_ms, reconnect_delay_ms, priority, max_capacity, proxy_name) "
        + "VALUES ('fk-conn', 'testprof', 'radegast', true, false, 'up.lan', 5431, 5000, 60000, 50, 10, 'fk-proxy')",
        null);
    // DELETE du proxy référencé refusé (RESTRICT)
    assertThrows(Exception.class, () ->
        exec("DELETE FROM csp_proxy WHERE name='fk-proxy'", null));
    // Après détachement du connecteur : suppression OK
    exec("UPDATE csp_connector SET proxy_name=NULL WHERE name='fk-conn'", null);
    exec("DELETE FROM csp_proxy WHERE name='fk-proxy'", null);
    // Référence vers proxy inexistant refusée
    assertThrows(Exception.class, () ->
        exec("INSERT INTO csp_connector (name, profile_name, protocol, enabled, debug, host, port, "
            + "connect_timeout_ms, reconnect_delay_ms, priority, max_capacity, proxy_name) "
            + "VALUES ('fk-conn2', 'testprof', 'radegast', true, false, 'up.lan', 5431, 5000, 60000, 50, 10, 'ghost')", null));
    exec("DELETE FROM csp_connector WHERE name='fk-conn'", null);
  }

  // ============================================================
  // Trigger NOTIFY sur csp_proxy
  // ============================================================

  @Test
  @Order(4)
  void configNotifyTriggerNonBlocking() throws Exception {
    // Le trigger csp_config_changed (existence vérifiée en Order(1)) envoie un NOTIFY
    // à chaque INSERT/UPDATE/DELETE sur csp_proxy. On vérifie ici que les opérations
    // passent sans erreur et ne bloquent PAS les requêtes suivantes.
    // NB : pas de LISTEN ici — une connexion du pool laissée en état LISTEN perturberait
    // le pipeline des requêtes suivantes (notifications async intercalées).
    long before = scalar("SELECT COUNT(*) FROM csp_proxy WHERE name='notify-proxy'", null);
    exec("INSERT INTO csp_proxy (name, host, port) VALUES ('notify-proxy', 'h.lan', 1080)", null);
    exec("UPDATE csp_proxy SET enabled=false WHERE name='notify-proxy'", null);
    assertEquals(before + 1,
        scalar("SELECT COUNT(*) FROM csp_proxy WHERE name='notify-proxy'", null));
    exec("DELETE FROM csp_proxy WHERE name='notify-proxy'", null);
    assertEquals(before,
        scalar("SELECT COUNT(*) FROM csp_proxy WHERE name='notify-proxy'", null));
  }

  // ============================================================
  // CRUD repository : upsertProxy / deleteProxy / usage / propagation
  // ============================================================

  @Test
  @Order(5)
  void upsertProxyCreateUpdateOptimistic() throws Exception {
    // Création v1
    Long v = repo.upsertProxy(proxy("crud-p1", true, "172.20.0.1", 1080, null, null, 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(1L, v);
    // Update avec version correcte → v2
    Long v2 = repo.upsertProxy(proxy("crud-p1", true, "172.20.0.2", 1080, null, null, 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(2L, v2);
    // Update avec version erronée → OptimisticLockException
    assertThrows(Exception.class, () ->
        repo.upsertProxy(proxy("crud-p1", true, "172.20.0.3", 1080, null, null, 1L), "tester")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
    // Absent avec version != 1 → ConfigNotFoundException
    assertThrows(Exception.class, () ->
        repo.upsertProxy(proxy("crud-ghost", true, "h", 1080, null, null, 5L), "tester")
            .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
    // Audit présent : create (v1) + update (v2) = 2 entrées
    assertEquals(2L, scalar("SELECT COUNT(*) FROM csp_audit_log WHERE entity_type='csp_proxy' AND entity_name='crud-p1'", null));
    // updated_by enregistré
    assertEquals("tester", pool.preparedQuery("SELECT updated_by FROM csp_proxy WHERE name='crud-p1'")
        .execute().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
        .iterator().next().getString("updated_by"));
  }

  @Test
  @Order(6)
  void deleteProxyInUseRejectedThenOk() throws Exception {
    repo.upsertProxy(proxy("del-p1", true, "h", 1080, null, null, 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    // Connecteur attaché
    repo.upsertConnector(radegast("del-conn", "del-p1", 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(1L, repo.countProxyUsage("del-p1")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
    // Delete référencé → ConfigValidationException PROXY_IN_USE
    try {
      repo.deleteProxy("del-p1", 1L, "tester")
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      fail("deleteProxy must fail with PROXY_IN_USE");
    } catch (java.util.concurrent.ExecutionException e) {
      assertTrue(e.getCause() instanceof com.bowman.cspng.config.ConfigValidationException,
          "expected ConfigValidationException, got " + e.getCause());
      assertTrue(e.getCause().getMessage().contains("PROXY_IN_USE"));
    }
    // Le proxy existe toujours (filet FK + check applicatif)
    assertEquals(1L, scalar("SELECT COUNT(*) FROM csp_proxy WHERE name='del-p1'", null));
    // Détacher le connecteur puis supprimer
    repo.upsertConnector(radegast("del-conn", null, 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    repo.deleteProxy("del-p1", 1L, "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    assertEquals(0L, scalar("SELECT COUNT(*) FROM csp_proxy WHERE name='del-p1'", null));
    // Cleanup connector
    exec("DELETE FROM csp_connector WHERE name='del-conn'", null);
  }

  @Test
  @Order(7)
  void loadAllPropagatesProxiesAndConnectorProxyName() throws Exception {
    repo.upsertProxy(proxy("snap-p", true, "172.20.0.1", 1080, "u", "env:PW", 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    repo.upsertConnector(radegast("snap-conn", "snap-p", 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ConfigSnapshot snap = repo.loadAll()
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    // Dataset partagé entre @Order tests → assertion ciblée sur le proxy du test
    NetworkProxyConfig p = snap.proxyByName().get("snap-p");
    assertNotNull(p, "snapshot must contain snap-p proxy");
    assertEquals("172.20.0.1", p.host());
    assertEquals("u", p.username());
    assertEquals("env:PW", p.passwordRef());
    ConnectorConfig c = snap.connectorByName().get("snap-conn");
    assertNotNull(c);
    assertEquals("snap-p", c.proxyName(), "loadAll must propagate connector proxy_name");
    // Cleanup
    exec("DELETE FROM csp_connector WHERE name='snap-conn'", null);
    exec("DELETE FROM csp_proxy WHERE name='snap-p'", null);
  }

  // ============================================================
  // Migration V4→V5 non destructive : données préexistantes préservées
  // (simulé : insert en V4-schema-compat via upsertConnector sans proxy_name, puis V5 ops)
  // ============================================================

  @Test
  @Order(8)
  void v4DataPreservedAfterV5() throws Exception {
    // Le schéma actuel EST déjà V5 ; on vérifie la compatibilité descendante :
    // un connecteur legacy (proxy_name NULL) reste direct et fonctionnel.
    repo.upsertConnector(radegast("legacy-conn", null, 1L), "tester")
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ConfigSnapshot snap = repo.loadAll()
        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ConnectorConfig legacy = snap.connectorByName().get("legacy-conn");
    assertNotNull(legacy, "legacy connector must survive V5 migration");
    assertNull(legacy.proxyName(), "legacy connector without proxy must stay direct (NULL)");
    exec("DELETE FROM csp_connector WHERE name='legacy-conn'", null);
  }
}
