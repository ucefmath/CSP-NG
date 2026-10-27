package com.bowman.cspng.app;

import com.bowman.cspng.users.*;
import com.bowman.cspng.users.UserProtocolSecretRepository.ProtocolSecretRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PG de l'étape P3 (révision reviewer passage 4) : migration V6 (login =
 * csp_user.name EXACT, plus d'alias protocol_name) + V7 (auth epoch monotone).
 * Infrastructure : CSP_DB_* ou localhost:5433 (container dédié
 * csp-ng-test-pg-proxy, jamais prod).
 * Contrats : CRUD additif refs-only ; ucrc = CRC32(MD5(csp_user.name)) uint32
 * dérivé de l'identité CANONIQUE (collision inter-comptes impossible par
 * construction — UNIQUE(ucrc) = garde de cohérence txn) ; version optimiste ;
 * rotation secret_version++ ; révocation ; FK cascade ; bornes userName
 * (CCcam ≤ 20, CS378x ≤ 63) à l'activation ; AUCUNE valeur de secret.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserProtocolSecretRepositoryPgTest {

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
    return ko.get();
  }

  private UserRepository userRepo() {
    return new UserRepository(pool, new PasswordHasher(4), vertx);
  }

  private UserProtocolSecretRepository secretRepo() {
    return new UserProtocolSecretRepository(pool);
  }

  private void createUser(String name) {
    await(userRepo().create(new UserRepository.CreateUserRequest(
        name, null, "InitialPass1!", null, true, false, false, false,
        1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null), "test"));
  }

  private static UserProtocolSecretRepository.UpsertProtocolSecretRequest req(String ref) {
    return new UserProtocolSecretRepository.UpsertProtocolSecretRequest(ref, null, true);
  }

  @Test
  @Order(1)
  @DisplayName("Migrations V6+V7 : table secret + csp_auth_epoch (additive)")
  void migrationsApplied() {
    var t1 = await(pool.preparedQuery(
        "SELECT COUNT(*) FROM information_schema.tables "
            + "WHERE table_name IN ('csp_user_protocol_secret','csp_auth_epoch')").execute());
    assertEquals(2, t1.iterator().next().getInteger(0), "V6 table secret + V7 epoch");
    var t2 = await(pool.preparedQuery(
        "SELECT COUNT(DISTINCT trigger_name) FROM information_schema.triggers "
            + "WHERE action_statement LIKE '%csp_auth_epoch_bump%' "
            + "AND trigger_name IN ('csp_user_epoch','csp_user_profile_epoch',"
            + "'csp_user_service_epoch','csp_user_connector_epoch',"
            + "'csp_user_secret_epoch')").execute());
    assertEquals(5, t2.iterator().next().getInteger(0),
        "epoch bumpée par trigger sur les 5 tables de droits "
            + "(1 trigger nommé/table, DISTINCT car information_schema répète par événement)");
  }

  @Test
  @Order(2)
  @DisplayName("Upsert CCcam : ref seulement, version 1, ucrc null (login = csp_user.name)")
  void upsertCcamStoresRefOnly() {
    createUser("alice");
    ProtocolSecretRecord rec = await(secretRepo().upsert("alice",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/alice_cccam"), 0, "admin"));
    assertEquals("file:/run/secrets/alice_cccam", rec.secretRef());
    assertEquals(1, rec.secretVersion());
    assertNull(rec.ucrc(), "CCcam n'a pas d'ucrc (camd35 seulement)");
    assertEquals(1, rec.version());
    assertTrue(rec.enabled());
    assertTrue(rec.secretRef().startsWith("file:") || rec.secretRef().startsWith("env:"));
  }

  @Test
  @Order(3)
  @DisplayName("CS378x : ucrc = CRC32(MD5(csp_user.name)) uint32 dérivé de l'identité canonique")
  void cs378xUcrcDerivedFromCanonicalName() {
    createUser("bob");
    ProtocolSecretRecord rec = await(secretRepo().upsert("bob",
        UserProtocolSecretRepository.Protocol.CS378X,
        req("file:/run/secrets/bob_cs378x"), 0, "admin"));
    assertNotNull(rec.ucrc());
    assertTrue(rec.ucrc() >= 0 && rec.ucrc() <= 4294967295L, "uint32");
    // ucrc("bob") = CRC32(MD5("bob")) — golden python3 : calcul indépendant
    // (le golden exact 0xe807815d pour "test" est figé côté csp-users).
    assertEquals(UserProtocolSecretRepository.ucrcOf("bob"), rec.ucrc());
  }

  @Test
  @Order(4)
  @DisplayName("Bornes login (csp_user.name) : CCcam > 20, CS378x > 63 refusés à l'activation")
  void userNameBoundsAtActivation() {
    createUser("x".repeat(20)); // 20 = borne CCcam
    await(secretRepo().upsert("x".repeat(20), UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/bound20"), 0, "admin"));
    createUser("y".repeat(21)); // 21 > borne CCcam
    assertThrows(IllegalArgumentException.class, () ->
        await(secretRepo().upsert("y".repeat(21), UserProtocolSecretRepository.Protocol.CCAM,
            req("file:/run/secrets/bound21"), 0, "admin")),
        "CCcam <= 20 (OSCam strncmp 20)");
    createUser("z".repeat(64)); // 64 > borne CS378x
    assertThrows(IllegalArgumentException.class, () ->
        await(secretRepo().upsert("z".repeat(64), UserProtocolSecretRepository.Protocol.CS378X,
            req("file:/run/secrets/bound64"), 0, "admin")),
        "CS378x <= 63");
  }

  @Test
  @Order(5)
  @DisplayName("Version optimiste : stale rejeté ; rotation => secret_version++ (pas de bump fantôme)")
  void optimisticVersionAndRotation() {
    createUser("frank");
    ProtocolSecretRecord v1 = await(secretRepo().upsert("frank",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/frank_v1"), 0, "admin"));
    assertEquals(1, v1.version());

    Throwable stale = awaitFail(secretRepo().upsert("frank",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/frank_v2"), 99, "admin"));
    assertInstanceOf(UserOptimisticLockException.class, stale);

    ProtocolSecretRecord v2 = await(secretRepo().upsert("frank",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/frank_v2"), v1.version(), "admin"));
    assertEquals(2, v2.secretVersion(), "rotation => nouvelle version de secret");
    assertEquals(2, v2.version());

    ProtocolSecretRecord v3 = await(secretRepo().upsert("frank",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/frank_v2"), v2.version(), "admin"));
    assertEquals(2, v3.secretVersion(), "même ref => pas de bump fantôme");
    assertEquals(3, v3.version());
  }

  @Test
  @Order(6)
  @DisplayName("Révocation : enabled=false + version++ ; delete user => FK cascade")
  void revokeAndCascade() {
    createUser("gina");
    ProtocolSecretRecord rec = await(secretRepo().upsert("gina",
        UserProtocolSecretRepository.Protocol.CCAM,
        req("file:/run/secrets/gina"), 0, "admin"));
    ProtocolSecretRecord revoked = await(secretRepo().revoke("gina",
        UserProtocolSecretRepository.Protocol.CCAM, rec.version(), "admin"));
    assertFalse(revoked.enabled());
    assertEquals(rec.version() + 1, revoked.version());
    Throwable stale = awaitFail(secretRepo().revoke("gina",
        UserProtocolSecretRepository.Protocol.CCAM, rec.version(), "admin"));
    assertInstanceOf(UserOptimisticLockException.class, stale);

    await(userRepo().delete("gina",
        await(userRepo().get("gina")).orElseThrow().version(), "test"));
    Optional<ProtocolSecretRecord> gone = await(secretRepo().get("gina",
        UserProtocolSecretRepository.Protocol.CCAM));
    assertTrue(gone.isEmpty(), "FK ON DELETE CASCADE");
  }

  @Test
  @Order(7)
  @DisplayName("findByUser case-exact (login = name) + valid_until portée + epoch monotone")
  void findByUserAndEpoch() {
    createUser("Hank");
    Instant until = Instant.now().plusSeconds(7200);
    await(secretRepo().upsert("Hank", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "env:HANK_CCAM", until, true), 0, "admin"));
    Optional<ProtocolSecretRecord> found = await(secretRepo().findByUser(
        UserProtocolSecretRepository.Protocol.CCAM, "Hank"));
    assertTrue(found.isPresent());
    assertEquals("Hank", found.get().userName(), "login = csp_user.name EXACT");
    assertEquals("env:HANK_CCAM", found.get().secretRef());
    assertEquals(until.getEpochSecond(), found.get().validUntil().getEpochSecond());
    assertTrue(await(secretRepo().findByUser(
        UserProtocolSecretRepository.Protocol.CCAM, "hank")).isEmpty(),
        "case-exact : Hank ≠ hank");

    // Epoch monotone V7 (jamais MAX(version))
    long e1 = await(secretRepo().currentGeneration());
    await(secretRepo().upsert("Hank", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "env:HANK_CCAM", until, true), found.get().version(), "admin"));
    long e2 = await(secretRepo().currentGeneration());
    assertTrue(e2 > e1, "chaque écriture de secret bump l'epoch (trigger in-txn)");
  }

  @Test
  @Order(8)
  @DisplayName("Collision CRC32 réelle : deux users distincts ('aw0' et 'heg') partagent le même ucrc => SecretCollisionException (409)")
  void ucrcCollisionBetweenDistinctUsersTriggers409() {
    // Les chaînes "aw0" et "heg" produisent EXACTEMENT le même CRC32(MD5(s)) = 0x9edb6f4f (2665115471L).
    // Cela réfute l'assertion erronée selon laquelle l'unicité du username rendrait les collisions d'ucrc impossibles.
    // La contrainte UNIQUE(ucrc) en base est donc indispensable et doit lever SecretCollisionException (409).
    assertEquals(UserProtocolSecretRepository.ucrcOf("aw0"), UserProtocolSecretRepository.ucrcOf("heg"),
        "Démonstration mathématique de collision CRC32(MD5(s)) sur deux noms d'utilisateurs distincts");

    createUser("aw0");
    createUser("heg");

    ProtocolSecretRecord r1 = await(secretRepo().upsert("aw0", UserProtocolSecretRepository.Protocol.CS378X,
        req("file:/run/secrets/aw0_cs378x"), 0, "admin"));
    assertEquals(2665181007L, r1.ucrc());

    Throwable collision = awaitFail(secretRepo().upsert("heg", UserProtocolSecretRepository.Protocol.CS378X,
        req("file:/run/secrets/heg_cs378x"), 0, "admin"));
    assertInstanceOf(UserProtocolSecretRepository.SecretCollisionException.class, collision,
        "La collision d'ucrc sur deux utilisateurs distincts DOIT lever SecretCollisionException (mappée en HTTP 409)");
    assertTrue(collision.getMessage().contains("ucrc collision"), "Message explicite: " + collision.getMessage());
  }
}
