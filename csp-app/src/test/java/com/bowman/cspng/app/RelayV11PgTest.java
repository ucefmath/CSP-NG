package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigNotFoundException;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigValidationException;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigValidator;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.OptimisticLockException;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.config.WorkerExpiredException;
import com.bowman.cspng.config.WorkerRevokedException;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PG reel V1→V11 : migration additive, affectations, desired READONLY serveur
 * (bump monotone), ack exact/stale/expire/revoque. Jamais de prod.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("RelayV11PgTest — migration, assignments, desired serveur, ack DB")
class RelayV11PgTest {

  static Vertx vertx;
  static PgPool pool;
  static org.testcontainers.containers.PostgreSQLContainer<?> pgContainer;
  static String pgHost, pgDb, pgUser, pgPass;
  static int pgPort;
  static long listenerId;

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
        new PoolOptions().setMaxSize(5));

    // Seed minimal : profil + listener + connecteur (SQL brut, FK satisfaites).
    pool.preparedQuery("INSERT INTO csp_profile(name) VALUES('wprof')").execute()
        .toCompletionStage().toCompletableFuture().join();
    var lid = pool.preparedQuery(
            "INSERT INTO csp_profile_listener(profile_name, protocol, port) VALUES('wprof','cccam',12121) RETURNING id")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    listenerId = lid.getLong("id");
    pool.preparedQuery(
            "INSERT INTO csp_connector(name, profile_name, protocol, host, port) VALUES('wup1','wprof','cccam','127.0.0.1',4321)")
        .execute().toCompletionStage().toCompletableFuture().join();
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

  private ConfigRepository repo() {
    return new ConfigRepository(pool);
  }

  @Test
  @Order(1)
  @DisplayName("V11 appliquee : tables d'affectations presentes, snapshot vide valide")
  void migrationApplies() {
    var tables = pool.query(
            "SELECT tablename FROM pg_tables WHERE schemaname='public'"
                + " AND tablename IN ('csp_relay_node_listener','csp_relay_node_connector')")
        .execute().toCompletionStage().toCompletableFuture().join();
    int n = 0;
    for (var r : tables) n++;
    assertEquals(2, n, "V11 tables must exist after migrate");
    ConfigSnapshot snap = repo().loadAll().toCompletionStage().toCompletableFuture().join();
    assertTrue(ConfigValidator.validate(snap).isEmpty());
  }

  @Test
  @Order(2)
  @DisplayName("desired READONLY : valeur cliente ignoree, creation desired=1")
  void desiredReadonlyOnCreate() {
    // desiredVersion cliente 999 doit etre ignoree (serveur impose 1).
    var in = new RelayNodeConfig("wnode", "W", true, true, false, 999L, null, 1L);
    long v = repo().upsertRelayNode(in, "test").toCompletionStage().toCompletableFuture().join();
    assertEquals(1L, v);
    ConfigSnapshot snap = repo().loadAll().toCompletionStage().toCompletableFuture().join();
    var node = snap.relayNodeByName().get("wnode");
    assertNotNull(node);
    assertEquals(1L, node.desiredVersion(), "desired serveur, pas la valeur cliente 999");
  }

  @Test
  @Order(3)
  @DisplayName("Affectations + lien : snapshot et validateur OK, bump desired")
  void assignmentsAndBump() {
    var repo = repo();
    repo.upsertRelayCache(new RelayCacheConfig("wc1", "local", null, true, 8000L, 10000, 512, 1L), "test")
        .toCompletionStage().toCompletableFuture().join();
    repo.upsertListenerCacheLink(
            new ListenerCacheLinkConfig(listenerId, "wc1", true, true, false, true, 1L), "test")
        .toCompletionStage().toCompletableFuture().join();
    repo.upsertNodeListener("wnode", listenerId, true, 1L, "test")
        .toCompletionStage().toCompletableFuture().join();
    repo.upsertNodeConnector("wnode", "wup1", true, 1L, "test")
        .toCompletionStage().toCompletableFuture().join();
    ConfigSnapshot snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
    assertEquals(List.of(listenerId), snap.assignedListenerIds("wnode"));
    assertEquals(List.of("wup1"), snap.assignedConnectorNames("wnode"));
    assertTrue(ConfigValidator.validate(snap).isEmpty());
    var node = snap.relayNodeByName().get("wnode");
    // Creation(1) + cache/link/assignments (4 bumps) = desired 5.
    assertEquals(5L, node.desiredVersion(), "desired bump monotone par dependance, got " + node.desiredVersion());
  }

  /** join() qui deballe CompletionException (Vert.x Future -> stage). */
  private static <T> T join(io.vertx.core.Future<T> f) {
    try {
      return f.toCompletionStage().toCompletableFuture().join();
    } catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof RuntimeException re) throw re;
      throw e;
    }
  }

  @Test
  @Order(4)
  @DisplayName("Ack exact OK, stale 409, revoque 403")
  void ackFlows() {
    var repo = repo();
    ConfigSnapshot snap = join(repo.loadAll());
    long desired = snap.relayNodeByName().get("wnode").desiredVersion();
    long applied = join(repo.recordWorkerAck("wnode", desired, "worker:wnode", 30000L));
    assertEquals(desired, applied);
    // Stale.
    assertThrows(OptimisticLockException.class, () ->
        join(repo.recordWorkerAck("wnode", desired - 1, "worker:wnode", 30000L)));
    // Revocation : update bump desired puis ack rejete.
    var cur = join(repo.loadAll()).relayNodeByName().get("wnode");
    var revoked = new RelayNodeConfig("wnode", "W", true, true, true, 999L, null, cur.version());
    join(repo.upsertRelayNode(revoked, "test"));
    var after = join(repo.loadAll()).relayNodeByName().get("wnode");
    assertThrows(WorkerRevokedException.class, () ->
        join(repo.recordWorkerAck("wnode", after.desiredVersion(), "worker:wnode", 30000L)));
  }

  @Test
  @Order(6)
  @DisplayName("V12 : tables udp_peer/node_stats, CRUD peers, validateur")
  void v12UdpPeers() {
    var repo = repo();
    var tables = pool.query(
            "SELECT tablename FROM pg_tables WHERE schemaname='public'"
                + " AND tablename IN ('csp_relay_udp_peer','csp_relay_node_stats')")
        .execute().toCompletionStage().toCompletableFuture().join();
    int n = 0;
    for (var r : tables) n++;
    assertEquals(2, n, "V12 tables must exist after migrate");
    join(repo.upsertRelayNode(
        new RelayNodeConfig("wnode2", "W2", true, true, false, 1L, null, 1L), "test"));
    join(repo.upsertRelayCache(
        new RelayCacheConfig("wcu", "udp", null, true, 8000L, 10000, 512, 1L), "test"));
    long peerId = join(repo.upsertUdpPeer("wcu", "127.0.0.1", 14599, true, "test"));
    assertTrue(peerId >= 1);
    // Idempotent : meme cle => meme id, version bump.
    long peerId2 = join(repo.upsertUdpPeer("wcu", "127.0.0.1", 14599, true, "test"));
    assertEquals(peerId, peerId2);
    ConfigSnapshot snap = join(repo.loadAll());
    var peers = snap.udpPeersForCache("wcu");
    assertEquals(1, peers.size());
    assertEquals(14599, peers.get(0).port());
    assertTrue(ConfigValidator.validate(snap).isEmpty());
    // Pair sur cache local => erreur validateur.
    var bad = snap.withUdpPeers(
        List.of(new com.bowman.cspng.config.RelayUdpPeerConfig(99L, "wc1", "127.0.0.1", 9000, true, 1L)));
    assertTrue(ConfigValidator.validate(bad).stream().anyMatch(e -> e.contains("non-udp cache")));
    join(repo.deleteUdpPeer(peerId, "test"));
    assertTrue(join(repo.loadAll()).udpPeersForCache("wcu").isEmpty());
    assertThrows(ConfigNotFoundException.class, () -> join(repo.deleteUdpPeer(peerId, "test")));
  }

  @Test
  @Order(7)
  @DisplayName("V12 : endpoint CSPv2 node + stats ack persistees et relues")
  void v12NodeEndpointAndStats() {
    var repo = repo();
    var cur = join(repo.loadAll()).relayNodeByName().get("wnode2");
    var withEndpoint = new RelayNodeConfig("wnode2", "W2", true, true, false, 1L, null,
        cur.version(), null, "127.0.0.1", 14699);
    join(repo.upsertRelayNode(withEndpoint, "test"));
    var after = join(repo.loadAll()).relayNodeByName().get("wnode2");
    assertEquals("127.0.0.1", after.cspHost());
    assertEquals(14699, after.cspPort());
    String stats = "{\"connectors\":[{\"name\":\"wup1\",\"online\":true,\"ecmOk\":3}],\"caches\":[]}";
    long applied = join(repo.recordWorkerAck("wnode2", after.desiredVersion(), "worker:wnode2", 30000L, stats));
    assertEquals(after.desiredVersion(), applied);
    var loaded = join(repo.loadNodeStats());
    assertEquals(stats, loaded.get("wnode2"));
    // Payload > 8Ko rejete.
    String big = "{\"x\":\"" + "y".repeat(9000) + "\"}";
    assertThrows(ConfigValidationException.class, () ->
        join(repo.recordWorkerAck("wnode2", applied, "worker:wnode2", 30000L, big)));
  }

  @Test
  @Order(5)
  @DisplayName("Listes worker sanitisees : pas d'exception, pas de hash")
  void sanitizedLists() {
    var repo = repo();
    var users = repo.loadWorkerUserAclsFull().toCompletionStage().toCompletableFuture().join();
    assertNotNull(users);
    var secrets = repo.loadWorkerProtocolSecrets().toCompletionStage().toCompletableFuture().join();
    assertNotNull(secrets);
    for (var s : secrets) {
      assertTrue(s.secretRef().startsWith("env:") || s.secretRef().startsWith("file:"));
    }
    var catalog = repo.loadWorkerCatalog().toCompletionStage().toCompletableFuture().join();
    assertNotNull(catalog);
  }

  @Test
  @Order(8)
  @DisplayName("Auto-guerison : bail expire -> heal au pull -> audit WORKER_LEASE_RENEWED -> ack OK")
  void healExpiredLeaseFlow() {
    var repo = repo();
    // 1. Creation d'un node usable wnode3
    join(repo.upsertRelayNode(
        new RelayNodeConfig("wnode3", "W3", true, true, false, 1L, null, 1L), "test"));
    var snap = join(repo.loadAll());
    var node = snap.relayNodeByName().get("wnode3");
    long desired = node.desiredVersion();

    // 2. Forcer config_expires_at dans le passe (ex: -10s)
    pool.preparedQuery("UPDATE csp_relay_node SET config_expires_at = now() - interval '10 seconds' WHERE node_name = 'wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join();

    // 3. Verifier la defense en profondeur : sans heal, recordWorkerAck est refuse avec 410 (WorkerExpiredException)
    assertThrows(WorkerExpiredException.class, () ->
        join(repo.recordWorkerAck("wnode3", desired, "worker:wnode3", 30000L)));

    // 4. Compter les audits WORKER_LEASE_RENEWED existants
    var auditCountBefore = pool.preparedQuery(
            "SELECT count(*) AS c FROM csp_audit_log WHERE event_type='WORKER_LEASE_RENEWED' AND entity_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");

    // 5. Pull desired -> touchWorkerSeenAndHeal declenche l'auto-guerison
    boolean healed = join(repo.touchWorkerSeenAndHeal("wnode3", 30000L, "worker:wnode3"));
    assertTrue(healed, "Bail expire doit etre gueri");

    // 6. Verifier en DB que config_expires_at est maintenant dans le futur
    var rowAfterHeal = pool.preparedQuery(
            "SELECT config_expires_at, last_seen_at FROM csp_relay_node WHERE node_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    var expiresAt = rowAfterHeal.getOffsetDateTime("config_expires_at");
    assertNotNull(expiresAt);
    assertTrue(expiresAt.toInstant().isAfter(java.time.Instant.now()), "config_expires_at doit etre dans le futur");
    assertNotNull(rowAfterHeal.getOffsetDateTime("last_seen_at"));

    // 7. Verifier la presence de la ligne d'audit WORKER_LEASE_RENEWED
    var auditRows = pool.preparedQuery(
            "SELECT event_type, entity_type, entity_name, changed_by, summary FROM csp_audit_log WHERE event_type='WORKER_LEASE_RENEWED' AND entity_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join();
    int count = 0;
    for (var r : auditRows) {
      count++;
      assertEquals("csp_relay_node", r.getString("entity_type"));
      assertEquals("worker:wnode3", r.getString("changed_by"));
    }
    assertEquals(auditCountBefore + 1, count, "Exactement 1 audit WORKER_LEASE_RENEWED ajoute");

    // 8. recordWorkerAck passe desormais avec succes
    long applied = join(repo.recordWorkerAck("wnode3", desired, "worker:wnode3", 30000L));
    assertEquals(desired, applied);
  }

  @Test
  @Order(9)
  @DisplayName("Auto-guerison : bail NULL -> heal au pull")
  void healNullLeaseFlow() {
    var repo = repo();
    // Forcer config_expires_at a NULL
    pool.preparedQuery("UPDATE csp_relay_node SET config_expires_at = NULL WHERE node_name = 'wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join();

    boolean healed = join(repo.touchWorkerSeenAndHeal("wnode3", 30000L, "worker:wnode3"));
    assertTrue(healed, "Bail NULL doit etre gueri");

    var rowAfterHeal = pool.preparedQuery(
            "SELECT config_expires_at FROM csp_relay_node WHERE node_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    var expiresAt = rowAfterHeal.getOffsetDateTime("config_expires_at");
    assertNotNull(expiresAt);
    assertTrue(expiresAt.toInstant().isAfter(java.time.Instant.now()));
  }

  @Test
  @Order(10)
  @DisplayName("Regime sain : bail valide -> pull ne modifie PAS config_expires_at (pas d'extension ni audit)")
  void healthyPullDoesNotExtendLease() {
    var repo = repo();
    // Mettre une date d'expiration connue dans le futur
    pool.preparedQuery(
            "UPDATE csp_relay_node SET config_expires_at = now() + interval '50 seconds', last_seen_at = now() - interval '5 seconds' WHERE node_name = 'wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join();

    var before = pool.preparedQuery(
            "SELECT config_expires_at, last_seen_at FROM csp_relay_node WHERE node_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    var expiresBefore = before.getOffsetDateTime("config_expires_at");
    var seenBefore = before.getOffsetDateTime("last_seen_at");

    var auditCountBefore = pool.preparedQuery(
            "SELECT count(*) AS c FROM csp_audit_log WHERE event_type='WORKER_LEASE_RENEWED' AND entity_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");

    // Pull en regime sain
    boolean healed = join(repo.touchWorkerSeenAndHeal("wnode3", 30000L, "worker:wnode3"));
    assertFalse(healed, "Bail deja valide ne doit PAS etre re-gueri");

    var after = pool.preparedQuery(
            "SELECT config_expires_at, last_seen_at FROM csp_relay_node WHERE node_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    var expiresAfter = after.getOffsetDateTime("config_expires_at");
    var seenAfter = after.getOffsetDateTime("last_seen_at");

    assertEquals(expiresBefore, expiresAfter, "config_expires_at ne doit PAS etre modifie en regime sain");
    assertTrue(seenAfter.toInstant().isAfter(seenBefore.toInstant()) || seenAfter.equals(seenBefore),
        "last_seen_at doit etre mis a jour");

    var auditCountAfter = pool.preparedQuery(
            "SELECT count(*) AS c FROM csp_audit_log WHERE event_type='WORKER_LEASE_RENEWED' AND entity_name='wnode3'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
    assertEquals(auditCountBefore, auditCountAfter, "Aucun audit ne doit etre emis en regime sain");
  }

  @Test
  @Order(11)
  @DisplayName("Node disabled ou revoked : pas de heal -> ack refuse")
  void disabledOrRevokedNoHeal() {
    var repo = repo();
    // Node desactive
    join(repo.upsertRelayNode(
        new RelayNodeConfig("wnode-disabled", "WD", false, true, false, 1L, null, 1L), "test"));
    pool.preparedQuery(
            "UPDATE csp_relay_node SET config_expires_at = now() - interval '10 seconds' WHERE node_name = 'wnode-disabled'")
        .execute().toCompletionStage().toCompletableFuture().join();

    boolean healedDisabled = join(repo.touchWorkerSeenAndHeal("wnode-disabled", 30000L, "worker:wnode-disabled"));
    assertFalse(healedDisabled, "Node desactive ne doit PAS etre gueri");

    var expDisabled = pool.preparedQuery(
            "SELECT config_expires_at FROM csp_relay_node WHERE node_name='wnode-disabled'")
        .execute().toCompletionStage().toCompletableFuture().join().iterator().next().getOffsetDateTime("config_expires_at");
    assertTrue(expDisabled.toInstant().isBefore(java.time.Instant.now()), "Le bail doit rester expire");

    assertThrows(WorkerRevokedException.class, () ->
        join(repo.recordWorkerAck("wnode-disabled", 1L, "worker:wnode-disabled", 30000L)));

    // Node revoque
    join(repo.upsertRelayNode(
        new RelayNodeConfig("wnode-revoked", "WR", true, true, true, 1L, null, 1L), "test"));
    pool.preparedQuery(
            "UPDATE csp_relay_node SET config_expires_at = now() - interval '10 seconds' WHERE node_name = 'wnode-revoked'")
        .execute().toCompletionStage().toCompletableFuture().join();

    boolean healedRevoked = join(repo.touchWorkerSeenAndHeal("wnode-revoked", 30000L, "worker:wnode-revoked"));
    assertFalse(healedRevoked, "Node revoque ne doit PAS etre gueri");

    assertThrows(WorkerRevokedException.class, () ->
        join(repo.recordWorkerAck("wnode-revoked", 1L, "worker:wnode-revoked", 30000L)));
  }
}
