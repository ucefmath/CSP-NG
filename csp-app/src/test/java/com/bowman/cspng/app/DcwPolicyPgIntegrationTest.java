package com.bowman.cspng.app;

import com.bowman.cspng.app.runtime.WorkerDesiredBuilder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigValidationException;
import com.bowman.cspng.config.ConfigValidator;
import com.bowman.cspng.config.DcwRuleConfig;
import com.bowman.cspng.config.DcwServiceMappingConfig;
import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.LegacyGuard;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Intégration PostgreSQL réelle (Flyway V1..V13) : API repository DCW -> transaction (validation,
 * audit, bump desired) -> snapshot loadAll -> ConfigValidator -> desired worker -> policy compilée.
 * Même harnais que ImportDedupDigestPgTest (PG local 5433 / env / Testcontainers).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DcwPolicyPgIntegrationTest {

  static Vertx vertx;
  static PgPool pool;
  static PostgreSQLContainer<?> pg;
  static ConfigRepository repo;
  static final byte[] VALID = {1, 2, 3, 6, 4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33};
  static final byte[] BAD_CS = {1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 10, 11, 12, 0};

  @BeforeAll
  static void setup() throws Exception {
    String host, db, user, pass;
    int port;
    if (reachable("localhost", 5433)) {
      host = "localhost"; port = 5433; db = "testdb"; user = "testuser"; pass = "testpass";
    } else {
      if (System.getenv("DOCKER_HOST") == null && new java.io.File("/run/user/1000/docker.sock").exists()) {
        System.setProperty("DOCKER_HOST", "unix:///run/user/1000/docker.sock");
      }
      pg = new PostgreSQLContainer<>("postgres:15-alpine").withDatabaseName("csp_config").withUsername("csp_app").withPassword("csp_test_pass");
      pg.start();
      host = pg.getHost(); port = pg.getMappedPort(5432); db = pg.getDatabaseName(); user = pg.getUsername(); pass = pg.getPassword();
    }
    String jdbc = "jdbc:postgresql://" + host + ":" + port + "/" + db;
    try (var c = java.sql.DriverManager.getConnection(jdbc, user, pass); var st = c.createStatement()) {
      st.execute("DROP SCHEMA public CASCADE");
      st.execute("CREATE SCHEMA public");
    }
    Flyway.configure().dataSource(jdbc, user, pass).locations("classpath:db/migration").load().migrate();
    vertx = Vertx.vertx();
    pool = PgPool.pool(vertx, new PgConnectOptions().setHost(host).setPort(port).setDatabase(db).setUser(user).setPassword(pass),
        new PoolOptions().setMaxSize(4));
    repo = new ConfigRepository(pool);
    sql("INSERT INTO csp_relay_node (node_name, enabled, authorized, revoked) VALUES ('w1', true, true, false)");
  }

  @AfterAll
  static void teardown() {
    if (pool != null) pool.close();
    if (vertx != null) vertx.close();
    if (pg != null) pg.stop();
  }

  static boolean reachable(String h, int p) {
    try (var s = new java.net.Socket()) { s.connect(new java.net.InetSocketAddress(h, p), 1500); return true; } catch (Exception e) { return false; }
  }

  static <T> T await(Future<T> f) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  static void sql(String q) throws Exception {
    await(pool.query(q).execute());
  }

  static long scalar(String q) throws Exception {
    return await(pool.query(q).execute()).iterator().next().getLong(0);
  }

  @Test @Order(1)
  @DisplayName("Défaut V13 : policy inherit/off ; snapshot valide")
  void defaults() throws Exception {
    var p = await(repo.getDcwPolicy());
    assertEquals("inherit", p.defaultChecksumMode());
    assertEquals("off", p.repairMode());
    ConfigSnapshot snap = await(repo.loadAll());
    assertTrue(ConfigValidator.validate(snap).isEmpty(), ConfigValidator.validate(snap).toString());
  }

  @Test @Order(2)
  @DisplayName("Création règle none/ignore provider exact + règle block provider NULL : audit + bump desired")
  void createRulesAuditAndBump() throws Exception {
    long before = scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'");
    long audits = scalar("SELECT count(*) FROM csp_audit_log WHERE entity_type='csp_dcw_rule'");
    long id1 = await(repo.createDcwRule(new DcwRuleConfig(0, null, 0x0500, 0x032830, 0, null, null, "none", "ignore", "", true, 1, 0), "admin"));
    String digest = com.bowman.cspng.domain.dcw.DcwDigest.digestSha256Hex(VALID);
    long id2 = await(repo.createDcwRule(new DcwRuleConfig(0, null, 0x1884, null, 0, null, digest, "block", "inherit", "", true, 1, 0), "admin"));
    assertTrue(id1 > 0 && id2 > 0);
    assertEquals(before + 2, scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'"));
    assertEquals(audits + 2, scalar("SELECT count(*) FROM csp_audit_log WHERE entity_type='csp_dcw_rule'"));
    assertEquals(1, scalar("SELECT count(*) FROM csp_dcw_rule WHERE provider IS NULL"));
  }

  @Test @Order(3)
  @DisplayName("Invalide (block sans digest, conflit de scope) : rejet 400-type, aucune écriture, aucun bump")
  void invalidRejectedBeforeWrite() throws Exception {
    long before = scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'");
    long rules = scalar("SELECT count(*) FROM csp_dcw_rule");
    var ex = assertThrows(ExecutionException.class, () -> await(repo.createDcwRule(
        new DcwRuleConfig(0, null, 0, null, 0, null, null, "block", "inherit", "", true, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, ex.getCause());
    var ex2 = assertThrows(ExecutionException.class, () -> await(repo.createDcwRule(
        new DcwRuleConfig(0, null, 0x0500, 0x032830, 0, null, null, "none", "require_valid", "", true, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, ex2.getCause());
    assertEquals(rules, scalar("SELECT count(*) FROM csp_dcw_rule"));
    assertEquals(before, scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'"));
  }

  @Test @Order(4)
  @DisplayName("Mapping approuvé : persisté, chargé snapshot -> desired worker -> domaine")
  void mappingEndToEnd() throws Exception {
    await(repo.createDcwServiceMapping(new DcwServiceMappingConfig(0, "default", 0x1884, 0x30, 5, 6, true, true, 0L, 1, 0), "admin"));
    var ex = assertThrows(ExecutionException.class, () -> await(repo.createDcwServiceMapping(
        new DcwServiceMappingConfig(0, "default", 0x1884, 0x30, 7, 7, true, true, 0L, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, ex.getCause());
    ConfigSnapshot snap = await(repo.loadAll());
    assertTrue(ConfigValidator.validate(snap).isEmpty(), ConfigValidator.validate(snap).toString());
    assertEquals(1, snap.dcwPolicy().mappings().size());
    var desired = WorkerDesiredBuilder.build(snap, "w1", 60_000, System.currentTimeMillis(), List.of(), List.of(), List.of());
    var dom = desired.dcwPolicy().toDomain();
    assertEquals(2, dom.rules().size(), "règles profil null transmises");
    // mapping filtré : profil 'default' non servi par w1 (aucun listener assigné)
    assertEquals(0, dom.mappings().size());
    // Sémantique compilée : exception IGNORE seulement sur provider exact connu ; legacy sinon
    assertTrue(dom.validate(BAD_CS, "p", 0x0500, 0x032830, 1, "c", LegacyGuard.CHECKSUM_REQUIRED).isValid());
    assertFalse(dom.validate(BAD_CS, "p", 0x0500, com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER, 1, "udp:x", LegacyGuard.CHECKSUM_REQUIRED).isValid());
    assertFalse(dom.validate(VALID, "p", 0x1884, 0, 1, "c", LegacyGuard.PERMISSIVE).isValid(), "BLOCK wildcard provider");
    assertEquals(ChecksumMode.INHERIT, dom.defaultChecksumMode());
  }

  @Test @Order(5)
  @DisplayName("Policy PUT : optimistic lock + version ; enabled=false ignore règles custom")
  void policyUpdate() throws Exception {
    var cur = await(repo.getDcwPolicy());
    await(repo.saveDcwPolicy(false, false, true, false, "inherit", "observe", true, cur.version(), "admin"));
    assertThrows(ExecutionException.class, () -> await(repo.saveDcwPolicy(true, false, false, false, "inherit", "off", true, cur.version(), "admin")));
    var dom = await(repo.loadAll()).dcwPolicy().toDomain();
    assertFalse(dom.enabled());
    assertTrue(dom.validate(VALID, "p", 0x1884, 0, 1, "c", LegacyGuard.PERMISSIVE).isValid(), "BLOCK ignoré si disabled");
  }

  @Test @Order(6)
  @DisplayName("Ligne corrompue hors API (conflit inséré en SQL brut) : snapshot refusé par le validateur (fail-closed)")
  void corruptedRowFailsSnapshotValidation() throws Exception {
    sql("INSERT INTO csp_dcw_rule (caid, provider, action, checksum_mode) VALUES (1280, 206896, 'none', 'require_valid')");
    ConfigSnapshot snap = await(repo.loadAll());
    assertFalse(ConfigValidator.validate(snap).isEmpty(), "ConfigWatcher refusera le swap (dernier bon conservé)");
  }

  @Test @Order(7)
  @DisplayName("Cap 1000 règles appliqué avant écriture")
  void capEnforced() throws Exception {
    sql("DELETE FROM csp_dcw_rule");
    sql("INSERT INTO csp_dcw_rule (caid, sid, source_id, action, checksum_mode) "
        + "SELECT 1, g, 's' || g, 'none', 'ignore' FROM generate_series(1, 1000) g");
    var ex = assertThrows(ExecutionException.class, () -> await(repo.createDcwRule(
        new DcwRuleConfig(0, null, 2, null, 0, null, null, "none", "ignore", "", true, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, ex.getCause());
    assertEquals(1000, scalar("SELECT count(*) FROM csp_dcw_rule"));
  }

  @Test @Order(8)
  @DisplayName("Profil inconnu ou mal orthographié : rejet 400 dans la transaction, aucune écriture, aucun audit, aucun bump")
  void unknownProfileRejectedBeforeWrite() throws Exception {
    long beforeBump = scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'");
    long beforeAudits = scalar("SELECT count(*) FROM csp_audit_log WHERE entity_type='csp_dcw_rule'");
    long beforeRules = scalar("SELECT count(*) FROM csp_dcw_rule");

    var exRule = assertThrows(ExecutionException.class, () -> await(repo.createDcwRule(
        new DcwRuleConfig(0, "non_existent_profile_xyz", 0x1884, null, 0, null, null, "none", "ignore", "", true, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, exRule.getCause());
    assertTrue(exRule.getCause().getMessage().contains("unknown profile"));

    var exMap = assertThrows(ExecutionException.class, () -> await(repo.createDcwServiceMapping(
        new DcwServiceMappingConfig(0, "non_existent_profile_xyz", 0x1884, 0x30, 5, 6, true, true, 0L, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, exMap.getCause());
    assertTrue(exMap.getCause().getMessage().contains("unknown profile"));

    assertEquals(beforeRules, scalar("SELECT count(*) FROM csp_dcw_rule"));
    assertEquals(beforeAudits, scalar("SELECT count(*) FROM csp_audit_log WHERE entity_type='csp_dcw_rule'"));
    assertEquals(beforeBump, scalar("SELECT desired_version FROM csp_relay_node WHERE node_name='w1'"));
  }

  @Test @Order(9)
  @DisplayName("deleteProfile refuse la suppression d'un profil référencé par une règle DCW ou un service mapping")
  void deleteProfileRefusedIfReferencedByDcw() throws Exception {
    sql("DELETE FROM csp_dcw_rule");
    sql("INSERT INTO csp_profile (name, ca_id, network_id, version) VALUES ('temp_dcw_p', 6276, 0, 1)");

    long ruleId = await(repo.createDcwRule(
        new DcwRuleConfig(0, "temp_dcw_p", 0x1884, null, 0, null, null, "none", "ignore", "", true, 1, 0), "admin"));
    assertTrue(ruleId > 0);

    var exDel = assertThrows(ExecutionException.class, () -> await(repo.deleteProfile("temp_dcw_p", 1L, "admin")));
    assertInstanceOf(ConfigValidationException.class, exDel.getCause());
    assertTrue(exDel.getCause().getMessage().contains("referenced by DCW rules or service mappings"));

    // Profil toujours présent
    assertEquals(1, scalar("SELECT count(*) FROM csp_profile WHERE name='temp_dcw_p'"));

    // Après suppression de la règle, deleteProfile réussit
    await(repo.deleteDcwRule(ruleId, "admin"));
    await(repo.deleteProfile("temp_dcw_p", 1L, "admin"));
    assertEquals(0, scalar("SELECT count(*) FROM csp_profile WHERE name='temp_dcw_p'"));
  }

  @Test @Order(10)
  @DisplayName("Contraintes de clé étrangère PG : rejet 23503 sur orphelins bruts et ON UPDATE CASCADE fonctionnel")
  void foreignKeyConstraintEnforcedDirectly() throws Exception {
    sql("INSERT INTO csp_profile (name, ca_id, network_id, version) VALUES ('fk_test_p', 6276, 0, 1)");

    // 1. Insertion brute orpheline dans csp_dcw_rule rejetée par FK (23503)
    var exRule = assertThrows(ExecutionException.class, () ->
        sql("INSERT INTO csp_dcw_rule (profile, caid, action, checksum_mode) VALUES ('non_existent_profile_xyz', 6276, 'none', 'ignore')"));
    assertInstanceOf(io.vertx.pgclient.PgException.class, exRule.getCause());
    assertEquals("23503", ((io.vertx.pgclient.PgException) exRule.getCause()).getSqlState());

    // 2. Insertion brute orpheline dans csp_dcw_service_mapping rejetée par FK (23503)
    var exMap = assertThrows(ExecutionException.class, () ->
        sql("INSERT INTO csp_dcw_service_mapping (profile, caid, provider, source_sid, target_sid, approved) VALUES ('non_existent_profile_xyz', 6276, 48, 1, 2, true)"));
    assertInstanceOf(io.vertx.pgclient.PgException.class, exMap.getCause());
    assertEquals("23503", ((io.vertx.pgclient.PgException) exMap.getCause()).getSqlState());

    // 3. Insertion valide
    sql("INSERT INTO csp_dcw_rule (profile, caid, action, checksum_mode) VALUES ('fk_test_p', 6276, 'none', 'ignore')");
    sql("INSERT INTO csp_dcw_service_mapping (profile, caid, provider, source_sid, target_sid, approved) VALUES ('fk_test_p', 6276, 48, 1, 2, true)");

    // 4. Suppression directe brute de csp_profile rejetée par FK (23503 / ON DELETE RESTRICT)
    var exDel = assertThrows(ExecutionException.class, () ->
        sql("DELETE FROM csp_profile WHERE name = 'fk_test_p'"));
    assertInstanceOf(io.vertx.pgclient.PgException.class, exDel.getCause());
    assertEquals("23503", ((io.vertx.pgclient.PgException) exDel.getCause()).getSqlState());

    // 5. Renommage en cascade ON UPDATE CASCADE
    sql("UPDATE csp_profile SET name = 'fk_test_p_renamed' WHERE name = 'fk_test_p'");
    assertEquals(1, scalar("SELECT count(*) FROM csp_dcw_rule WHERE profile = 'fk_test_p_renamed'"));
    assertEquals(1, scalar("SELECT count(*) FROM csp_dcw_service_mapping WHERE profile = 'fk_test_p_renamed'"));

    // Nettoyage
    sql("DELETE FROM csp_dcw_rule WHERE profile = 'fk_test_p_renamed'");
    sql("DELETE FROM csp_dcw_service_mapping WHERE profile = 'fk_test_p_renamed'");
    sql("DELETE FROM csp_profile WHERE name = 'fk_test_p_renamed'");
  }

  @Test @Order(11)
  @DisplayName("Mapping CAID:Provider global (sourceSid=targetSid=0) : couple cible persisté, doublon inverse rejeté, update persisté")
  void caidProviderMappingPersistenceAndReverseDuplicateRejection() throws Exception {
    // 1. Création d'un mapping global de couples : (0x1884, 0x000000) -> (0x1813, 0x000068)
    long id = await(repo.createDcwServiceMapping(
        new DcwServiceMappingConfig(0, "default", 0x1884, 0x000000, 0x1813, 0x000068, 0, 0, true, true, 0L, 1, 0), "admin"));
    assertTrue(id > 0);

    // 2. Rechargement depuis la base : le couple cible et les SID wildcard sont bien persistés
    var reloaded = await(repo.getDcwPolicy()).mappings().stream()
        .filter(m -> m.id() == id).findFirst().orElseThrow();
    assertEquals(0x1813, reloaded.targetCaid(), "target_caid du mapping global persisté");
    assertEquals(0x000068, reloaded.targetProvider(), "target_provider du mapping global persisté");
    assertEquals(0, reloaded.sourceSid(), "source_sid wildcard (0) persisté");
    assertEquals(0, reloaded.targetSid(), "target_sid wildcard (0) persisté");

    // 3. Doublon inverse rejeté dans la transaction (aucune écriture)
    var ex = assertThrows(ExecutionException.class, () -> await(repo.createDcwServiceMapping(
        new DcwServiceMappingConfig(0, "default", 0x1813, 0x000068, 0x1884, 0x000000, 0, 0, true, true, 0L, 1, 0), "admin")));
    assertInstanceOf(ConfigValidationException.class, ex.getCause());
    // 0x1813 = 6163, 0x000068 = 104 (littéraux décimaux : 0x… n'est pas portable avant PG16)
    assertEquals(0, scalar("SELECT count(*) FROM csp_dcw_service_mapping WHERE profile = 'default'"
        + " AND caid = 6163 AND provider = 104"), "le doublon inverse ne doit pas être écrit");

    // 4. Mise à jour du couple cible : persistée en base
    await(repo.updateDcwServiceMapping(id, new DcwServiceMappingConfig(id, "default", 0x1884, 0x000000,
        0x1818, 0x00006C, 0, 0, true, true, 0L, 1, 0), 1L, "admin"));
    var updated = await(repo.getDcwPolicy()).mappings().stream()
        .filter(m -> m.id() == id).findFirst().orElseThrow();
    assertEquals(0x1818, updated.targetCaid(), "target_caid mis à jour persisté");
    assertEquals(0x00006C, updated.targetProvider(), "target_provider mis à jour persisté");

    // 5. Insertion SQL brute de (source_sid=0, target_sid=5) échoue sur la contrainte SQL ck_dcw_mapping_sid_pair
    var sqlEx = assertThrows(ExecutionException.class, () -> sql(
        "INSERT INTO csp_dcw_service_mapping (profile, caid, provider, target_caid, target_provider, source_sid, target_sid, approved, enabled, version, updated_at, updated_by)"
        + " VALUES ('default', 6276, 0, 6163, 104, 0, 5, true, true, 1, now(), 'admin')"));
    assertTrue(sqlEx.getCause().getMessage().contains("ck_dcw_mapping_sid_pair"),
        "l'insertion doit échouer sur la contrainte ck_dcw_mapping_sid_pair");

    // 6. repo.createDcwRule(...) fonctionne sans erreur
    long ruleId = await(repo.createDcwRule(
        new DcwRuleConfig(0, "default", 0x1884, null, 0, null, null, "none", "ignore", "", true, 1L, 0L), "admin"));
    assertTrue(ruleId > 0, "règle DCW créée sans erreur");
  }
}
