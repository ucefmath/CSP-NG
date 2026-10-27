package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.config.*;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Test d'integration PostgreSQL L5a — autonome, sans skip.
 *
 * <p>Sur DB vierge, Flyway s'exécute programmatique en @BeforeAll avec la vraie
 * migration du classpath (V1__init_config_schema.sql) avec le comportement Quarkus
 * par défaut (placeholderReplacement=true). La seed utilise une référence canonique
 * {@code env:CSP_PROFILE_DES_KEY} sans syntaxe Flyway, donc aucune propriété
 * {@code quarkus.flyway.placeholder-replacement} n'est nécessaire.
 *
 * <p>Scenarios : loadAll, singleton missing, secret refs, optimistic lock, audit atomique,
 * holder, NOTIFY brut, watcher reel, coalescing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgIntegrationTest {

    static Vertx vertx;
    static PgPool pool;
    static PostgreSQLContainer<?> pgContainer;
    static String pgHost, pgDb, pgUser, pgPass;
    static int pgPort;

    @BeforeAll
    static void setup() throws Exception {
        // 1. Try env-provided PG first (ex: docker pg-test-5433)
        String envHost = env("CSP_DB_HOST", null);
        String envPort = env("CSP_DB_PORT", null);
        String envDb = env("CSP_DB_NAME", null);
        String envUser = env("CSP_DB_USER", null);
        String envPass = env("CSP_DB_PASSWORD", null);

        boolean useEnv = false;
        if (envHost != null && !envHost.isBlank() && envPass != null && !envPass.isBlank()) {
            int p = envPort != null ? Integer.parseInt(envPort) : 5432;
            if (isReachable(envHost, p)) {
                pgHost = envHost; pgPort = p; pgDb = envDb != null ? envDb : "csp_config";
                pgUser = envUser != null ? envUser : "csp_app"; pgPass = envPass;
                useEnv = true;
                System.out.println("[PgIntegrationTest] Using env PG: " + pgHost + ":" + pgPort + "/" + pgDb);
            }
        }

        // 2. Fallback : docker pg-test-5433 deja lance (testuser/testpass/testdb) via localhost:5433
        if (!useEnv && isReachable("localhost", 5433)) {
            // tente localhost:5433 (pg-test-5433 du CI local)
            try {
                String testJdbc = "jdbc:postgresql://localhost:5433/testdb";
                Flyway testProbe = Flyway.configure().dataSource(testJdbc, "testuser", "testpass").load();
                // probe ok -> utilise ce PG
                pgHost = "localhost"; pgPort = 5433; pgDb = "testdb"; pgUser = "testuser"; pgPass = "testpass";
                useEnv = true; // reuse flag pour sauter testcontainers
                System.out.println("[PgIntegrationTest] Using local pg-test-5433 at localhost:5433/testdb");
            } catch (Exception e) {
                // ignore, passera a testcontainers
            }
        }
        // 3. Sinon demarre Testcontainers vierge (avec DOCKER_HOST rootless si besoin)
        if (!useEnv) {
            // Force DOCKER_HOST rootless si non defini
            if (System.getenv("DOCKER_HOST") == null) {
                java.io.File sock = new java.io.File("/run/user/1000/docker.sock");
                if (sock.exists()) {
                    System.setProperty("DOCKER_HOST", "unix:///run/user/1000/docker.sock");
                    System.out.println("[PgIntegrationTest] Set DOCKER_HOST=unix:///run/user/1000/docker.sock");
                }
            }
            try {
                pgContainer = new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("csp_config")
                    .withUsername("csp_app")
                    .withPassword("csp_test_pass");
                pgContainer.start();
                pgHost = pgContainer.getHost();
                pgPort = pgContainer.getMappedPort(5432);
                pgDb = pgContainer.getDatabaseName();
                pgUser = pgContainer.getUsername();
                pgPass = pgContainer.getPassword();
                System.out.println("[PgIntegrationTest] Started Testcontainers PG: " + pgHost + ":" + pgPort + "/" + pgDb);
            } catch (Exception e) {
                // Dernier recours : localhost:5433 direct
                if (isReachable("localhost", 5433)) {
                    pgHost = "localhost"; pgPort = 5433; pgDb = "testdb"; pgUser = "testuser"; pgPass = "testpass";
                    System.out.println("[PgIntegrationTest] Fallback to localhost:5433/testdb after Testcontainers failure: " + e.getMessage());
                } else {
                    throw e;
                }
            }
        }

        // 3. Recree schema vierge puis Flyway programmatique — comportement Quarkus par defaut (placeholderReplacement=true)
        String jdbcUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
        // Drop/create pour garantir DB vierge (spec: preuve migration reussie sur schema vierge)
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            System.out.println("[PgIntegrationTest] Schema public recrée vierge sur " + jdbcUrl);
        }
        Flyway flyway = Flyway.configure()
            .dataSource(jdbcUrl, pgUser, pgPass)
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();
        System.out.println("[PgIntegrationTest] Flyway migrate done on " + jdbcUrl + " (placeholderReplacement=default)");

        vertx = Vertx.vertx();
        pool = PgPool.pool(vertx,
            new PgConnectOptions().setHost(pgHost).setPort(pgPort)
                .setDatabase(pgDb).setUser(pgUser).setPassword(pgPass),
            new PoolOptions().setMaxSize(5));
    }

    @AfterAll
    static void teardown() {
        if (pool != null) pool.close();
        if (vertx != null) vertx.close();
        if (pgContainer != null) pgContainer.stop();
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v != null ? v : def;
    }

    private static boolean isReachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) { return false; }
    }

    // -- helpers --

    private long countAudit(String entityType) {
        return pool.preparedQuery("SELECT COUNT(*) AS c FROM csp_audit_log WHERE entity_type=$1")
            .execute(Tuple.of(entityType))
            .toCompletionStage().toCompletableFuture().join()
            .iterator().next().getLong("c");
    }

    private long countAllAudit() {
        return pool.preparedQuery("SELECT COUNT(*) AS c FROM csp_audit_log")
            .execute()
            .toCompletionStage().toCompletableFuture().join()
            .iterator().next().getLong("c");
    }

    // -- tests --

    @Test
    @Order(1)
    void loadAllProducesValidSnapshot() {
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigSnapshot snapshot = repo.loadAll().toCompletionStage().toCompletableFuture().join();

        assertNotNull(snapshot);
        assertNotNull(snapshot.root().proxyName());
        assertNotNull(snapshot.logging());
        assertNotNull(snapshot.cache());
        assertNotNull(snapshot.clusterUdp());
        assertNotNull(snapshot.webui());

        assertEquals(30_000L, snapshot.cache().cwMaxAgeMs());
        assertEquals(100_000L, snapshot.cache().maximumSize());
        assertEquals(8099, snapshot.clusterUdp().bindPort());

        // Seed des_key_ref doit etre env:CSP_PROFILE_DES_KEY (canonique sans ${}) et passer validation
        assertTrue(snapshot.profileListeners().stream().anyMatch(l -> "env:CSP_PROFILE_DES_KEY".equals(l.desKeyRef())),
            "seed des_key_ref must be env:CSP_PROFILE_DES_KEY");
        var errors = ConfigValidator.validate(snapshot);
        assertTrue(errors.isEmpty(), "Validated snapshot should have no errors: " + errors);
    }

    @Test
    @Order(2)
    void singletonRowsFallbackToDefaultWhenMissing() {
        ConfigRepository repo = new ConfigRepository(pool);
        // Supprime la ligne singleton root, puis verifie fallback DEFAULT
        pool.preparedQuery("DELETE FROM csp_root_config WHERE id=1").execute()
            .toCompletionStage().toCompletableFuture().join();
        ConfigSnapshot missing = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertEquals(RootConfig.DEFAULT.proxyName(), missing.root().proxyName(),
            "missing singleton should fallback to DEFAULT");
        // Restaure pour les tests suivants
        pool.preparedQuery("INSERT INTO csp_root_config (id, proxy_name) VALUES (1,'CSP-NG') ON CONFLICT (id) DO NOTHING")
            .execute().toCompletionStage().toCompletableFuture().join();
        ConfigSnapshot restored = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertNotNull(restored.root().proxyName());
    }

    @Test
    @Order(3)
    void secretRefValidationRejectsRawValues() {
        // Construit un snapshot avec un secret brut (non ${...}) -> validation doit echouer
        ConnectorConfig rawConn = new ConnectorConfig("raw-conn", "default", "newcamd", true, false,
            "10.0.0.1", 15000, "user", "plainPassword", null,
            5000, 10000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(new ProfileConfig("default", true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,1L)),
            List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(rawConn), List.of(), CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());
        var errors = ConfigValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("password_ref") && e.contains("must be env:")),
            "raw password_ref should be rejected: " + errors);

        // Formats valides doivent passer (canonique + legacy)
        assertTrue(ConfigValidator.isValidSecretRef("env:MY_VAR"));
        assertTrue(ConfigValidator.isValidSecretRef("file:/run/secrets/pw"));
        assertTrue(ConfigValidator.isValidSecretRef("vault:secret/path"));
        assertTrue(ConfigValidator.isValidSecretRef("${MY_VAR}"));
        assertTrue(ConfigValidator.isValidSecretRef("${env:MY_VAR}"));
        assertTrue(ConfigValidator.isValidSecretRef("${file:/run/secrets/pw}"));
        assertTrue(ConfigValidator.isValidSecretRef("${vault:secret/path}"));
        assertFalse(ConfigValidator.isValidSecretRef("rawSecret"));
        assertFalse(ConfigValidator.isValidSecretRef(""));
        assertFalse(ConfigValidator.isValidSecretRef("${}"));
    }

    @Test
    @Order(4)
    void optimisticLockDetectsConflictAndNoPartialAudit() {
        ConfigRepository repo = new ConfigRepository(pool);
        long beforeAudit = countAudit("csp_root_config");
        // Cherche version actuelle
        ConfigSnapshot snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap.root().version();
        // Premier update OK avec actor
        repo.updateRoot("phase-1", ver, "tester").toCompletionStage().toCompletableFuture().join();
        long afterFirstAudit = countAudit("csp_root_config");
        assertEquals(beforeAudit + 1, afterFirstAudit, "audit should be written atomically");

        // Second update avec ancienne version echoue — pas d'audit partiel
        try {
            repo.updateRoot("should-fail", ver, "tester").toCompletionStage().toCompletableFuture().join();
            fail("Expected OptimisticLockException");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause instanceof OptimisticLockException);
        }
        long afterFailAudit = countAudit("csp_root_config");
        assertEquals(afterFirstAudit, afterFailAudit, "failed optimistic lock must not write audit");
    }

    @Test
    @Order(5)
    void auditWrittenAtomicallyForUpsertAndDelete() {
        ConfigRepository repo = new ConfigRepository(pool);
        long before = countAllAudit();
        ProfileConfig profile = new ProfileConfig(
            "audit-profile", true, false, false, 0x1702, 0x0001,
            null, null, 5000L, null, false, 0,
            null, null, null, null, 1L);
        repo.upsertProfile(profile, "auditor").toCompletionStage().toCompletableFuture().join();
        long afterUpsert = countAllAudit();
        assertTrue(afterUpsert > before, "upsert should write audit");

        // Verifie que l'audit contient changed_by = auditor
        var row = pool.preparedQuery("SELECT changed_by FROM csp_audit_log WHERE entity_type='csp_profile' AND entity_name='audit-profile' ORDER BY id DESC LIMIT 1")
            .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals("auditor", row.getString("changed_by"));

        var snapAudit = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long verAudit = snapAudit.profileByName().get("audit-profile").version();
        repo.deleteProfile("audit-profile", verAudit, "auditor2").toCompletionStage().toCompletableFuture().join();
        long afterDelete = countAllAudit();
        assertTrue(afterDelete > afterUpsert);
        var row2 = pool.preparedQuery("SELECT changed_by FROM csp_audit_log WHERE entity_type='csp_profile' AND entity_name='audit-profile' ORDER BY id DESC LIMIT 1")
            .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals("auditor2", row2.getString("changed_by"));
    }

    @Test
    @Order(6)
    void snapshotVersionIncrementsAfterUpdate() {
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigSnapshot before = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long bv = before.root().version();
        repo.updateRoot("v" + System.currentTimeMillis(), bv).toCompletionStage().toCompletableFuture().join();
        ConfigSnapshot after = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertTrue(after.root().version() > bv);
    }

    @Test
    @Order(7)
    void upsertProfileWorks() {
        ConfigRepository repo = new ConfigRepository(pool);
        ProfileConfig profile = new ProfileConfig(
            "test-profile", true, false, false, 0x1702, 0x0001,
            null, null, 5000L, null, false, 0,
            null, null, null, null, 1L);
        Long v = repo.upsertProfile(profile).toCompletionStage().toCompletableFuture().join();
        assertTrue(v >= 1);
        repo.deleteProfile("test-profile", v, "actor").toCompletionStage().toCompletableFuture().join();
    }

    @Test
    @Order(8)
    void configHolderIntegration() {
        ConfigHolder holder = new ConfigHolder();
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigSnapshot snapshot = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        holder.set(snapshot);
        assertTrue(holder.isInitialized());
        assertNotNull(holder.get().root().proxyName());
    }

    @Test
    @Order(9)
    void updateTriggersNotifyAndWatcherReloads() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigHolder holder = new ConfigHolder();
        ConfigSnapshot initial = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        holder.set(initial);
        long beforeVersion = initial.root().version();

        // Watcher reel
        Vertx watcherVertx = Vertx.vertx();
        ConfigWatcher watcher = new ConfigWatcher(repo, holder, pool, watcherVertx, 300_000L);
        watcher.start().toCompletionStage().toCompletableFuture().join();
        // Petite pause pour etre sur que LISTEN est actif
        Thread.sleep(300);

        // UPDATE qui trigger NOTIFY
        repo.updateRoot("notify-test-" + System.currentTimeMillis(), beforeVersion, "watcher-test")
            .toCompletionStage().toCompletableFuture().join();

        // Attendre que holder soit mis a jour via NOTIFY (poll 5s)
        long deadline = System.currentTimeMillis() + 5000;
        ConfigSnapshot after = null;
        while (System.currentTimeMillis() < deadline) {
            after = holder.get();
            if (after.root().version() > beforeVersion && after.root().proxyName().startsWith("notify-test")) break;
            Thread.sleep(100);
        }
        assertNotNull(after);
        assertTrue(after.root().version() > beforeVersion, "watcher should have reloaded via NOTIFY");
        assertTrue(after.root().proxyName().startsWith("notify-test"));

        watcher.stop().toCompletionStage().toCompletableFuture().join();
        watcherVertx.close();
    }

    @Test
    @Order(10)
    void watcherCoalescingBurstDoesNotDuplicateLoad() throws Exception {
        // Compte les loadAll via wrapper
        AtomicInteger loadCount = new AtomicInteger();
        ConfigRepository countingRepo = new ConfigRepository(pool) {
            @Override public io.vertx.core.Future<ConfigSnapshot> loadAll() {
                loadCount.incrementAndGet();
                return super.loadAll();
            }
        };
        ConfigHolder holder = new ConfigHolder();
        holder.set(countingRepo.loadAll().toCompletionStage().toCompletableFuture().join());
        Vertx wVertx = Vertx.vertx();
        ConfigWatcher watcher = new ConfigWatcher(countingRepo, holder, pool, wVertx, 60_000L);
        watcher.start().toCompletionStage().toCompletableFuture().join();
        Thread.sleep(200);
        loadCount.set(0);
        // Rafale de 10 NOTIFY rapides — simule via appels handleConfigChanged coalesces
        // On ne peut pas envoyer 10 NOTIFY PG sans 10 UPDATE, donc on invoque le watcher directement
        // via 10 NOTIFY PG successifs rapides : on fait 1 update qui trigger 1 notify, mais on teste
        // le coalescing en appelant reload via burst de handleConfigChanged indirects
        // Pour l'instant verifie que fallback + NOTIFY coalesce : on declenche 5 handleConfigChanged rapides
        // en comptant que le watcher n'execute pas 5 loadAll en parallele
        for (int i = 0; i < 5; i++) {
            // simulate burst by publishing multiple NOTIFY via direct SQL NOTIFY
            pool.query("NOTIFY csp_config_changed, ''").execute().toCompletionStage().toCompletableFuture().join();
        }
        Thread.sleep(1500); // laisse le temps au coalescing
        // Avec coalescing, loadCount devrait etre <=2 (1 initial + 1 dirty) pas 5
        int c = loadCount.get();
        assertTrue(c <= 2, "burst NOTIFY should be coalesced, got loadCount=" + c);
        watcher.stop().toCompletionStage().toCompletableFuture().join();
        wVertx.close();
    }

    @Test
    @Order(11)
    void rawNotifyStillReceived() throws Exception {
        // Test bas niveau NOTIFY sans watcher, comme avant mais autonome
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigSnapshot before = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long beforeVersion = before.root().version();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> channelRef = new AtomicReference<>();
        Vertx notifyVertx = Vertx.vertx();
        PgPool notifyPool = PgPool.pool(notifyVertx,
            new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass),
            new PoolOptions().setMaxSize(1));
        var listenReady = notifyPool.getConnection()
            .compose(conn -> {
                if (conn instanceof io.vertx.pgclient.PgConnection pgConn) {
                    pgConn.notificationHandler(n -> { channelRef.set(n.getChannel()); latch.countDown(); });
                }
                return conn.query("LISTEN csp_config_changed").execute().mapEmpty();
            });
        listenReady.toCompletionStage().toCompletableFuture().join();
        Thread.sleep(300);
        repo.updateRoot("raw-notify-" + System.currentTimeMillis(), beforeVersion).toCompletionStage().toCompletableFuture().join();
        boolean notified = latch.await(5, TimeUnit.SECONDS);
        assertTrue(notified);
        assertEquals("csp_config_changed", channelRef.get());
        notifyPool.close();
        notifyVertx.close();
    }
}
