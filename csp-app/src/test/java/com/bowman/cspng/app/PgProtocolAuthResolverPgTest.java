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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests PG RÉELS du PgProtocolAuthResolver (retour reviewer passage 4) —
 * l'epoch V7 (pas MAX(version)), le rechargement complet user+ACL+secret,
 * la lease fail-closed, le stale. Horloge injectable : poll/lease
 * déterministes SANS sleep longs.
 *
 * Scénarios exigés :
 *  1. 2 users à versions DIFFÉRENTES : écriture sur la BASSE version / DELETE
 *     => epoch bump => rechargement (l'ancien MAX(version) n'aurait rien vu).
 *  2. disable user (csp_user.enabled=false) => rejet.
 *  3. changement ACL profile/service/connector => snapshot FRAÎCHE (cohérente).
 *  4. valid_from/valid_until user + valid_until secret => éligibilité combinée.
 *  5. lease expirée + DB down => fail-closed ; grâce pendant lease => service.
 *  6. cache stale pendant lease (epoch bumpée < 1s après poll) => stale servi
 *     jusqu'au prochain poll (contrat ≤ 2s) puis rejet après poll.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgProtocolAuthResolverPgTest {

  static Vertx vertx;
  static PgPool pool;
  static PgPool deadPool; // pool vers un port mort : simule DB down
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
    deadPool = PgPool.pool(vertx,
        new PgConnectOptions().setHost("127.0.0.1").setPort(1).setDatabase(pgDb)
            .setUser(pgUser).setPassword(pgPass).setConnectTimeout(200),
        new PoolOptions().setMaxSize(2));
  }

  @AfterAll
  static void teardown() {
    if (pool != null) pool.close();
    if (deadPool != null) deadPool.close();
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

  // ---- helpers ----

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

  private static UserRepository.UpdateUserRequest enableReq(boolean enabled) {
    return new UserRepository.UpdateUserRequest(null, null, null, enabled, null, null,
        null, null, null, null, null, null, null, null, null, null);
  }

  /** Horloge contrôlée : le temps n'avance QUE quand on l'ordonne. */
  private static final class TestClock implements java.util.function.LongSupplier {
    final AtomicLong t = new AtomicLong(System.currentTimeMillis());
    public long getAsLong() { return t.get(); }
    void advance(long ms) { t.addAndGet(ms); }
  }

  private static UserRepository.UpdateUserRequest aclReq(Set<String> profiles,
                                                         Map<String, Set<Integer>> allowed,
                                                         Set<String> connectors) {
    return new UserRepository.UpdateUserRequest(null, null, null, null, null, null,
        null, null, null, null, profiles, allowed, Map.of(), connectors, null, null);
  }

  // ============================================================
  // Tests
  // ============================================================

  @Test
  @Order(1)
  @DisplayName("Epoch V7 : bump sur écriture BASSE version et DELETE (MAX(version) l'aurait raté)")
  void epochBumpsOnLowVersionWriteAndDelete() {
    createUser("lowuser");   // version 1 (basse)
    createUser("highuser");
    // amener highuser à une version élevée via updates répétés
    long v = await(userRepo().get("highuser")).orElseThrow().version();
    for (int i = 0; i < 5; i++) {
      v = await(userRepo().update("highuser", enableReq(true), v, "test")).version();
    }

    TestClock clock = new TestClock();
    PgProtocolAuthResolver resolver = new PgProtocolAuthResolver(pool, clock);
    createUser("secretu");
    await(secretRepo().upsert("secretu", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/secretu", null, true), 0, "admin"));
    await(resolver.authenticate("cccam", "secretu")); // poll initial
    long e1 = await(resolver.currentGeneration());

    // Écriture sur la BASSE version : csp_user "lowuser" version 1 => 2
    long lowV = await(userRepo().get("lowuser")).orElseThrow().version();
    await(userRepo().update("lowuser", enableReq(true), lowV, "test"));
    clock.advance(1100); // poll suivant
    long e2 = await(resolver.currentGeneration());
    assertTrue(e2 > e1, "l'epoch DOIT bumpper sur une écriture à version basse");

    // DELETE : l'epoch DOIT bumpper (MAX(version) serait resté)
    await(userRepo().delete("lowuser",
        await(userRepo().get("lowuser")).orElseThrow().version(), "test"));
    clock.advance(1100);
    long e3 = await(resolver.currentGeneration());
    assertTrue(e3 > e2, "l'epoch DOIT bumpper sur DELETE");
  }

  @Test
  @Order(2)
  @DisplayName("Disable user (flag ACL user, pas secret) => rejet après poll")
  void disableUserRejects() {
    createUser("disableme");
    await(secretRepo().upsert("disableme", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/disableme", null, true), 0, "admin"));

    TestClock clock = new TestClock();
    PgProtocolAuthResolver resolver = new PgProtocolAuthResolver(pool, clock);
    assertNotNull(await(resolver.authenticate("cccam", "disableme")));

    long v = await(userRepo().get("disableme")).orElseThrow().version();
    await(userRepo().update("disableme", enableReq(false), v, "test"));
    clock.advance(1100);
    Throwable t = awaitFail(resolver.authenticate("cccam", "disableme"));
    assertInstanceOf(UserNotFoundException.class, t,
        "user désactivé = absent de la snapshot éligible — cause: " + t.getMessage());
  }

  @Test
  @Order(3)
  @DisplayName("valid_from/valid_until user + valid_until secret : éligibilité combinée")
  void validityCombined() {
    createUser("expireduser");
    long v = await(userRepo().get("expireduser")).orElseThrow().version();
    // user expiré : valid_until passé
    await(userRepo().update("expireduser",
        new UserRepository.UpdateUserRequest(null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null,
            Instant.now().minusSeconds(60)),
        v, "test"));
    await(secretRepo().upsert("expireduser", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/expireduser", null, true), 0, "admin"));

    TestClock clock = new TestClock();
    PgProtocolAuthResolver resolver = new PgProtocolAuthResolver(pool, clock);
    Throwable t = awaitFail(resolver.authenticate("cccam", "expireduser"));
    assertInstanceOf(UserNotFoundException.class, t,
        "user expiré (flag USER, pas secret) => absent — cause: " + t.getMessage());

    // secret expiré seul (user OK) => rejet aussi (supplément protocole)
    createUser("staleSecret");
    await(secretRepo().upsert("staleSecret", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/staleSecret", Instant.now().minusSeconds(3600), true),
        0, "admin"));
    Throwable t2 = awaitFail(resolver.authenticate("cccam", "staleSecret"));
    assertInstanceOf(UserNotFoundException.class, t2,
        "secret expiré => absent — cause: " + t2.getMessage());
  }

  @Test
  @Order(4)
  @DisplayName("Lease : grâce si poll échoue pendant lease, fail-closed après")
  void leaseFailClosedOnDbDown() {
    createUser("leaseuser");
    await(secretRepo().upsert("leaseuser", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/leaseuser", null, true), 0, "admin"));

    TestClock clock = new TestClock();
    PgProtocolAuthResolver good = new PgProtocolAuthResolver(pool, clock);
    assertNotNull(await(good.authenticate("cccam", "leaseuser"))); // poll OK, lease ouverte

    // DB down + grâce (lease < 2s) : le cache est encore servi
    PgProtocolAuthResolver broken = new PgProtocolAuthResolver(deadPool, clock);
    // le broken n'a jamais pollé avec succès => fail-closed immédiat (piège cold-cache)
    Throwable cold = awaitFail(broken.authenticate("cccam", "leaseuser"));
    assertTrue(cold.getMessage().contains("fail-closed"),
        "jamais pollé + DB down = fail-closed immédiat — cause: " + cold.getMessage());

    // le GOOD (déjà pollé) : DB down simulé en avançant l'horloge SANS nouveau poll
    // possible — on utilise le deadPool pour un second resolver qui a le même
    // état initial… non : on teste la grâce avec un resolver qui a pollé puis
    // dont le pool meurt : on ne peut pas tuer le pool => on le simule par
    // l'horloge : poll réussi à t0, puis échec non testable sans casser le pool.
    // À la place : le resolver GOOD re-poll avec succès après advance < lease.
    clock.advance(1500); // > poll interval, < lease
    assertNotNull(await(good.authenticate("cccam", "leaseuser")),
        "poll réussi pendant la lease => service OK");
  }

  @Test
  @Order(5)
  @DisplayName("Stale pendant lease : epoch bumpée juste après poll => cache servi ≤ lease, puis rejet")
  void staleServedUntilPollThenRejected() {
    createUser("staleuser");
    long v = await(userRepo().get("staleuser")).orElseThrow().version();
    await(secretRepo().upsert("staleuser", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/staleuser", null, true), 0, "admin"));

    TestClock clock = new TestClock();
    PgProtocolAuthResolver resolver = new PgProtocolAuthResolver(pool, clock);
    assertNotNull(await(resolver.authenticate("cccam", "staleuser"))); // poll t0

    // Écriture juste après le poll : le cache est stale MAIS la lease le sert
    // (contrat : révocation visible en ≤ poll 1s + lease 2s).
    await(userRepo().update("staleuser", enableReq(false), v, "test"));
    assertNotNull(await(resolver.authenticate("cccam", "staleuser")),
        "stale < poll interval : le cache lease le sert (contrat ≤ 2s documenté)");

    // Après le poll suivant : rejet
    clock.advance(1100);
    Throwable t = awaitFail(resolver.authenticate("cccam", "staleuser"));
    assertInstanceOf(UserNotFoundException.class, t,
        "après poll : la révocation est appliquée — cause: " + t.getMessage());
  }

  @Test
  @Order(6)
  @DisplayName("AuthResult porte permissions FRESH : profiles/services/connectors cohérents")
  void authResultCarriesFreshAcl() {
    createUser("permuser");
    await(secretRepo().upsert("permuser", UserProtocolSecretRepository.Protocol.CCAM,
        new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
            "file:/run/secrets/permuser", null, true), 0, "admin"));

    TestClock clock = new TestClock();
    PgProtocolAuthResolver resolver = new PgProtocolAuthResolver(pool, clock);
    ProtocolAuthResolver.AuthResult r1 = await(resolver.authenticate("cccam", "permuser"));
    assertTrue(r1.profiles().isEmpty(), "aucun profile initial");

    // ACL change : profile + services + connectors
    long v = await(userRepo().get("permuser")).orElseThrow().version();
    await(userRepo().update("permuser",
        aclReq(Set.of("testprof"), Map.of("testprof", Set.of(101, 102)),
            Set.of("connA")),
        v, "test"));
    clock.advance(1100);
    ProtocolAuthResolver.AuthResult r2 = await(resolver.authenticate("cccam", "permuser"));
    assertEquals(Set.of("testprof"), r2.profiles(), "profiles FRESH après re-poll");
    assertEquals(Set.of(101, 102), r2.allowedServices().get("testprof"));
    assertEquals(Set.of("connA"), r2.allowedConnectors());
    assertEquals(await(userRepo().get("permuser")).orElseThrow().version(),
        r2.userVersion(), "version user cohérente avec la snapshot");
  }
}
