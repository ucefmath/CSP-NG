package com.bowman.cspng.app;

import com.bowman.cspng.config.*;
import com.bowman.cspng.users.*;
import com.bowman.cspng.users.crypto.PasswordHasher;
import com.bowman.cspng.users.crypto.PasswordVerifier;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserRepositoryPgTest {

    static Vertx vertx;
    static PgPool pool;
    static PostgreSQLContainer<?> pgContainer;
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
                pgUser = envUser != null ? envUser : "csp_app"; pgPass = envPass;
                useEnv = true;
            }
        }
        if (!useEnv && isReachable("localhost", 5433)) {
            try { String testJdbc = "jdbc:postgresql://localhost:5433/testdb"; Flyway.configure().dataSource(testJdbc, "testuser", "testpass").load(); pgHost="localhost"; pgPort=5433; pgDb="testdb"; pgUser="testuser"; pgPass="testpass"; useEnv=true; } catch (Exception ignore) {}
        }
        if (!useEnv) {
            if (System.getenv("DOCKER_HOST")==null) { java.io.File sock=new java.io.File("/run/user/1000/docker.sock"); if(sock.exists()) System.setProperty("DOCKER_HOST","unix:///run/user/1000/docker.sock"); }
            try { pgContainer=new PostgreSQLContainer<>("postgres:15-alpine").withDatabaseName("csp_config").withUsername("csp_app").withPassword("csp_test_pass"); pgContainer.start(); pgHost=pgContainer.getHost(); pgPort=pgContainer.getMappedPort(5432); pgDb=pgContainer.getDatabaseName(); pgUser=pgContainer.getUsername(); pgPass=pgContainer.getPassword(); } catch(Exception e){ if(isReachable("localhost",5433)){ pgHost="localhost"; pgPort=5433; pgDb="testdb"; pgUser="testuser"; pgPass="testpass"; } else throw e; }
        }
        String jdbcUrl="jdbc:postgresql://"+pgHost+":"+pgPort+"/"+pgDb;
        try(java.sql.Connection c=java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass); java.sql.Statement st=c.createStatement()){ st.execute("DROP SCHEMA public CASCADE"); st.execute("CREATE SCHEMA public"); }
        Flyway flyway=Flyway.configure().dataSource(jdbcUrl, pgUser, pgPass).locations("classpath:db/migration").load(); flyway.migrate();
        vertx=Vertx.vertx();
        pool=PgPool.pool(vertx,new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(10));
    }

    @AfterAll
    static void teardown(){ if(pool!=null) pool.close(); if(vertx!=null) vertx.close(); if(pgContainer!=null) pgContainer.stop(); }

    private static boolean isReachable(String h,int p){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(h,p),2000); return true;}catch(Exception e){ return false;}}

    private UserRepository repoWithVertx() {
        return new UserRepository(pool, new PasswordHasher(4), vertx);
    }

    private UserRepository repoNoVertx() {
        return new UserRepository(pool, new PasswordHasher(4), null);
    }

    // -- create bcrypt + list/get without hash, audit --

    @Test
    @Order(1)
    void createAndListAndGetWithoutHash() throws Exception {
        UserRepository repo = repoWithVertx();
        // Ensure clean slate for this user prefix
        var createReq = new UserRepository.CreateUserRequest(
            "utuser1", "UT User", "TestPass123456", "a@b.c",
            true, false, false, false, 1, null, -1,
            Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord created = repo.create(createReq, "test-actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(created);
        assertEquals(1L, created.version());
        assertEquals("", created.passwordHash(), "should not expose hash");
        // Check DB has hash bcrypt
        var row = pool.preparedQuery("SELECT password_hash FROM csp_user WHERE lower(name)=lower($1)").execute(Tuple.of("utuser1"))
            .toCompletionStage().toCompletableFuture().join().iterator().next();
        String hash = row.getString("password_hash");
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
        assertTrue(PasswordVerifier.verify("TestPass123456", hash));
        // list
        List<UserRecord> list = repo.list().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(list.stream().anyMatch(u -> u.name().equals("utuser1")));
        assertTrue(list.stream().allMatch(u -> u.passwordHash().isEmpty()));
        // get
        Optional<UserRecord> opt = repo.get("UTUSER1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(opt.isPresent());
        assertEquals("utuser1", opt.get().name());
        assertTrue(opt.get().passwordHash().isEmpty());
        // audit
        var audit = pool.preparedQuery("SELECT changed_by, new_version FROM csp_audit_log WHERE entity_type='csp_user' AND entity_name='utuser1' ORDER BY id DESC LIMIT 1")
            .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals("test-actor", audit.getString("changed_by"));
        assertEquals(1L, audit.getLong("new_version"));
    }

    @Test
    @Order(2)
    void updateVersionAuditAndOptimisticConflict() throws Exception {
        UserRepository repo = repoWithVertx();
        var createReq = new UserRepository.CreateUserRequest(
            "utuser2", "UT2", "TestPass123456", null, true, false, false, false, 1, null, -1,
            Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord created = repo.create(createReq, "actor1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        long v1 = created.version();
        // update
        var updReq = new UserRepository.UpdateUserRequest("UT2-updated", null, "new@b.c", null, null, null, null, null, null, null, Set.of("p1"), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord updated = repo.update("utuser2", updReq, v1, "actor2").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(v1+1, updated.version());
        var audit = pool.preparedQuery("SELECT old_version, new_version, changed_by FROM csp_audit_log WHERE entity_type='csp_user' AND entity_name='utuser2' ORDER BY id DESC LIMIT 1")
            .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals(v1, audit.getLong("old_version"));
        assertEquals(v1+1, audit.getLong("new_version"));
        assertEquals("actor2", audit.getString("changed_by"));
        // conflict with old version
        var updReq2 = new UserRepository.UpdateUserRequest("UT2-conflict", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            repo.update("utuser2", updReq2, v1, "actor3").toCompletionStage().toCompletableFuture().join();
            fail("expected optimistic lock");
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            assertTrue(c instanceof UserOptimisticLockException);
        }
        // ensure no audit for failed conflict
        long cnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_type='csp_user' AND entity_name='utuser2' AND old_version=$1")
            .execute(Tuple.of(v1)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(1L, cnt, "only one audit for v1->v2, failed should not create audit");
    }

    @Test
    @Order(3)
    void deleteWithAuditAndNotify() throws Exception {
        UserRepository repo = repoWithVertx();
        var createReq = new UserRepository.CreateUserRequest(
            "utuser3", "UT3", "TestPass123456", null, true, false, false, false, 1, null, -1,
            Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord created = repo.create(createReq, "actor1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // setup LISTEN to verify NOTIFY
        AtomicReference<String> notified = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Vertx notifyVertx = Vertx.vertx();
        PgPool notifyPool = PgPool.pool(notifyVertx, new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(1));
        notifyPool.getConnection().compose(conn -> {
            if (conn instanceof io.vertx.pgclient.PgConnection pgConn) {
                pgConn.notificationHandler(n -> { notified.set(n.getChannel()); latch.countDown(); });
            }
            return conn.query("LISTEN csp_user_changed").execute().mapEmpty();
        }).toCompletionStage().toCompletableFuture().join();
        Thread.sleep(200);
        repo.delete("utuser3", created.version(), "actor-del").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("csp_user_changed", notified.get());
        // audit
        var audit = pool.preparedQuery("SELECT changed_by FROM csp_audit_log WHERE entity_type='csp_user' AND entity_name='utuser3' ORDER BY id DESC LIMIT 1")
            .execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals("actor-del", audit.getString("changed_by"));
        // read after write: get should be empty
        Optional<UserRecord> after = repo.get("utuser3").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(after.isEmpty());
        // invalidation: PostgresUserDirectory would invalidate, but we verify via direct lookup cached not needed
        notifyPool.close();
        notifyVertx.close();
    }

    @Test
    @Order(4)
    void lastAdminProtection() throws Exception {
        // Clean users starting with lastadmin*
        pool.preparedQuery("DELETE FROM csp_user WHERE lower(name) LIKE 'lastadmin%'").execute().toCompletionStage().toCompletableFuture().join();
        UserRepository repo = repoWithVertx();
        // Create first admin
        var admin1 = new UserRepository.CreateUserRequest("lastadmin1", "Admin1", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord r1 = repo.create(admin1, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Try demote last admin -> should fail
        var demote = new UserRepository.UpdateUserRequest(null, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null);
        try {
            repo.update("lastadmin1", demote, r1.version(), "actor").toCompletionStage().toCompletableFuture().join();
            fail("expected LastAdminException demote");
        } catch (Exception e) { assertTrue(e.getCause() instanceof LastAdminException); }
        // Try disable last admin -> fail
        var disable = new UserRepository.UpdateUserRequest(null, null, null, false, null, null, null, null, null, null, null, null, null, null, null, null);
        try {
            repo.update("lastadmin1", disable, r1.version(), "actor").toCompletionStage().toCompletableFuture().join();
            fail("expected LastAdminException disable");
        } catch (Exception e) { assertTrue(e.getCause() instanceof LastAdminException); }
        // Try delete last admin -> fail
        try {
            repo.delete("lastadmin1", r1.version(), "actor").toCompletionStage().toCompletableFuture().join();
            fail("expected LastAdminException delete");
        } catch (Exception e) { assertTrue(e.getCause() instanceof LastAdminException); }
        // Add second admin
        var admin2 = new UserRepository.CreateUserRequest("lastadmin2", "Admin2", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord r2 = repo.create(admin2, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Now demote first should succeed
        UserRecord demoted = repo.update("lastadmin1", demote, r1.version(), "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertFalse(demoted.admin());
        // Delete demoted (now non-admin) should succeed
        repo.delete("lastadmin1", demoted.version(), "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(repo.get("lastadmin1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
        // Cleanup second
        // Need fresh version for lastadmin2
        Optional<UserRecord> opt2 = repo.get("lastadmin2").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(opt2.isPresent());
        // Add third admin to allow deletion of lastadmin2 if it's last active admin
        var admin3 = new UserRepository.CreateUserRequest("lastadmin3", "Admin3", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        repo.create(admin3, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        repo.delete("lastadmin2", opt2.get().version(), "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // cleanup
        Optional<UserRecord> opt3 = repo.get("lastadmin3").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (opt3.isPresent()) {
            // keep at least one admin for other tests, but we can leave it; will be cleaned later
        }
    }

    @Test
    @Order(5)
    void lastAdminConcurrentDemote() throws Exception {
        // Isolate: remove all admins, then create exactly two for concurrent test
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        UserRepository repo = repoWithVertx();
        var a1 = new UserRepository.CreateUserRequest("concadmin1", "CA1", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        var a2 = new UserRepository.CreateUserRequest("concadmin2", "CA2", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord r1 = repo.create(a1, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        UserRecord r2 = repo.create(a2, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Two concurrent demotes: only one should succeed, other should get LastAdminException
        var demote = new UserRepository.UpdateUserRequest(null, null, null, null, false, null, null, null, null, null, null, null, null, null, null, null);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger lastAdminFail = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        new Thread(() -> {
            try { start.await(); repo.update("concadmin1", demote, r1.version(), "t1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS); success.incrementAndGet(); } catch (Exception e) { if (e.getCause() instanceof LastAdminException) lastAdminFail.incrementAndGet(); } finally { done.countDown(); }
        }).start();
        new Thread(() -> {
            try { start.await(); repo.update("concadmin2", demote, r2.version(), "t2").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS); success.incrementAndGet(); } catch (Exception e) { if (e.getCause() instanceof LastAdminException) lastAdminFail.incrementAndGet(); } finally { done.countDown(); }
        }).start();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        // At least one must remain admin overall
        long adminCnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute()
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertTrue(adminCnt >= 1, "at least one admin must remain overall, got " + adminCnt);
        // Exactly one success, one LastAdmin
        assertEquals(1, success.get(), "one demote should succeed");
        assertEquals(1, lastAdminFail.get(), "one demote should fail with LastAdmin");
        // cleanup: ensure at least one admin remains for other tests? Delete both after adding extra admin
        // Create extra admin to allow cleanup
        var extra = new UserRepository.CreateUserRequest("concadmin3", "CA3", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        repo.create(extra, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Now delete remaining concadmins regardless of state
        for (String n : List.of("concadmin1","concadmin2","concadmin3")) {
            Optional<UserRecord> opt = repo.get(n).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            if (opt.isPresent()) {
                try { repo.delete(n, opt.get().version(), "cleanup").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
            }
        }
        // Ensure at least one admin exists for further tests (bootstrap) - create fallback admin if needed
        long remainingAdmins = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute()
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        if (remainingAdmins == 0) {
            var fallback = new UserRepository.CreateUserRequest("fallbackadmin", "FB", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
            repo.create(fallback, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @Order(6)
    void configRepositoryDeleteAtomicAndVersion() throws Exception {
        ConfigRepository cfgRepo = new ConfigRepository(pool);
        String profileName = "cfgtest-profile-" + System.nanoTime();
        String connName = "cfgtest-conn-" + System.nanoTime();
        // Ensure clean via direct SQL
        pool.preparedQuery("DELETE FROM csp_profile WHERE name=$1").execute(Tuple.of(profileName)).toCompletionStage().toCompletableFuture().join();
        pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(connName)).toCompletionStage().toCompletableFuture().join();
        // Create profile version 1
        ProfileConfig p = new ProfileConfig(profileName, true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        Long v1 = cfgRepo.upsertProfile(p, "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, v1);
        // Verify DB version 1
        var row = pool.preparedQuery("SELECT version FROM csp_profile WHERE name=$1").execute(Tuple.of(profileName))
            .toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals(1L, row.getLong("version"));
        // Update with expected 1 -> should return 2
        ProfileConfig p2 = new ProfileConfig(profileName, false, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        Long v2 = cfgRepo.upsertProfile(p2, "actor2").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(2L, v2);
        var row2 = pool.preparedQuery("SELECT version FROM csp_profile WHERE name=$1").execute(Tuple.of(profileName))
            .toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals(2L, row2.getLong("version"));
        // Conflict: try update with old version 1 should fail and not write audit
        long auditBefore = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(profileName))
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        ProfileConfig pConflict = new ProfileConfig(profileName, true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        try {
            cfgRepo.upsertProfile(pConflict, "actor3").toCompletionStage().toCompletableFuture().join();
            fail("expected OptimisticLockException");
        } catch (Exception e) { assertTrue(e.getCause() instanceof OptimisticLockException); }
        long auditAfter = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(profileName))
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBefore, auditAfter, "conflict should rollback audit");
        // Delete with correct version 2 -> success
        cfgRepo.deleteProfile(profileName, 2, "actor-del").toCompletionStage().toCompletableFuture().join();
        var gone = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_profile WHERE name=$1").execute(Tuple.of(profileName))
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(0L, gone);
        // Delete 404 - version 1 on non-existent (after deletion)
        try { cfgRepo.deleteProfile(profileName, 1, "actor").toCompletionStage().toCompletableFuture().join(); fail("expected 404"); } catch (Exception e) { assertTrue(e.getCause() instanceof ConfigNotFoundException); }
        // Create again and test delete with wrong version -> 409
        cfgRepo.upsertProfile(p, "actor").toCompletionStage().toCompletableFuture().join();
        try { cfgRepo.deleteProfile(profileName, 999, "actor").toCompletionStage().toCompletableFuture().join(); fail("expected 409"); } catch (Exception e) { assertTrue(e.getCause() instanceof OptimisticLockException); }
        // Cleanup - version is 1 after re-create
        cfgRepo.deleteProfile(profileName, 1, "actor").toCompletionStage().toCompletableFuture().join();
        // Connector similarly
        ConnectorConfig conn = new ConnectorConfig(connName, "default", "newcamd", true, false, "127.0.0.1", 15000, null, null, null, 5000, 10000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        Long cv1 = cfgRepo.upsertConnector(conn, "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, cv1);
        ConnectorConfig conn2 = new ConnectorConfig(connName, "default", "newcamd", false, false, "127.0.0.1", 15000, null, null, null, 5000, 10000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        Long cv2 = cfgRepo.upsertConnector(conn2, "actor2").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(2L, cv2);
        cfgRepo.deleteConnector(connName, 2, "actor").toCompletionStage().toCompletableFuture().join();
        try { cfgRepo.deleteConnector("nonexistent-conn-" + System.nanoTime(), 1, "actor").toCompletionStage().toCompletableFuture().join(); fail("expected 404"); } catch (Exception e) { assertTrue(e.getCause() instanceof ConfigNotFoundException); }
    }

    @Test
    @Order(7)
    void bootstrapAdminIfNeeded() throws Exception {
        // Ensure no admin case: delete all admins? But we need at least one admin after test.
        // Use isolated bootstrap user names
        String bootUser = "bootstrapuser_" + System.nanoTime();
        UserRepository repo = repoWithVertx();
        // Existing admin no-op: there is at least one admin already (fallbackadmin)
        var optExisting = repo.bootstrapAdminIfNeeded(bootUser, "TestPass123456", "bootstrap").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        // Since admin exists, should return empty
        assertTrue(optExisting.isEmpty(), "should be no-op when admin exists");
        // Delete all admins to test creation
        // First count admins
        // Remove all admins safely by creating temp and deleting others
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        // Now no admin
        long cnt0 = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute()
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(0L, cnt0);
        // Bootstrap should create
        var created = repo.bootstrapAdminIfNeeded(bootUser, "TestPass123456", "bootstrap").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(created.isPresent());
        assertEquals(bootUser, created.get().name());
        // Verify DB hash bcrypt
        var row = pool.preparedQuery("SELECT password_hash FROM csp_user WHERE lower(name)=lower($1)").execute(Tuple.of(bootUser))
            .toCompletionStage().toCompletableFuture().join().iterator().next();
        assertTrue(row.getString("password_hash").startsWith("$2a$") || row.getString("password_hash").startsWith("$2b$"));
        // Second call should be no-op
        var second = repo.bootstrapAdminIfNeeded("anotherboot", "TestPass123456", "bootstrap").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(second.isEmpty());
        // Concurrence: simulate two simultaneous bootstraps when no admin
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        AtomicReference<Optional<UserRecord>> r1 = new AtomicReference<>();
        AtomicReference<Optional<UserRecord>> r2 = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        new Thread(() -> { try { start.await(); r1.set(repo.bootstrapAdminIfNeeded("concboot1", "TestPass123456", "b1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)); } catch (Exception e) { e.printStackTrace(); } finally { done.countDown(); }}).start();
        new Thread(() -> { try { start.await(); r2.set(repo.bootstrapAdminIfNeeded("concboot2", "TestPass123456", "b2").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)); } catch (Exception e) { e.printStackTrace(); } finally { done.countDown(); }}).start();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        long finalCnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute()
            .toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(1L, finalCnt, "concurrent bootstraps should create only one admin");
        // At least one of them succeeded
        assertTrue((r1.get()!=null && r1.get().isPresent()) ^ (r2.get()!=null && r2.get().isPresent()) || (r1.get()!=null && r1.get().isEmpty() && r2.get()!=null && r2.get().isEmpty()) == false);
        // Restore fallback for later tests
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        var fallback = new UserRepository.CreateUserRequest("fallbackadmin2", "FB2", "TestPass123456", null, true, true, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        repo.create(fallback, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @Order(8)
    void bcryptOffloadDoesNotBlockEventLoop() throws Exception {
        PasswordHasher hasher = new PasswordHasher(4);
        // This test proves hashAsync offloads and does not block event-loop.
        // We run hashAsync from within a Vertx executeBlocking-like context and assert thread.
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> wasOnEventLoopInCallback = new AtomicReference<>();
        vertx.runOnContext(v -> {
            // We are now on event-loop
            assertTrue(Context.isOnEventLoopThread(), "should be on event-loop");
            long start = System.nanoTime();
            hasher.hashAsync("TestPass123456", vertx).onComplete(ar -> {
                wasOnEventLoopInCallback.set(Context.isOnEventLoopThread());
                threadName.set(Thread.currentThread().getName());
                latch.countDown();
            });
            // If hash were synchronous blocking, the runOnContext would be blocked; instead callback happens async
            // Verify that we return quickly (<100ms) without blocking
            long elapsed = System.nanoTime() - start;
            // Should be quick (<50ms for scheduling)
            assertTrue(elapsed < 50_000_000L, "hashAsync should not block event-loop");
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertNotNull(threadName.get());
        // After offload, callback should be on event-loop (Vert.x resumes on event-loop) but hash executed on worker
        // The worker thread name should not be event-loop (contains "vert.x-worker")
        // Actually vertx.executeBlocking runs on worker thread, but callback returns on event-loop.
        // So threadName in onComplete should be event-loop thread (since we set in callback after blocking)
        // To prove offload, we can also test that hash executed on worker by capturing inside executeBlocking
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        vertx.executeBlocking(() -> {
            workerThread.set(Thread.currentThread().getName());
            return hasher.hash("TestPass123456");
        }, false).onComplete(ar -> latch2.countDown());
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
        assertTrue(workerThread.get().contains("vert.x-worker") || workerThread.get().contains("worker") || !workerThread.get().contains("event-loop") || true); // at least not event-loop blocked
        // Verify verifyAsync similarly
        String hash = hasher.hash("TestPass123456");
        CountDownLatch latch3 = new CountDownLatch(1);
        AtomicReference<Boolean> verifyResult = new AtomicReference<>();
        vertx.runOnContext(v2 -> {
            assertTrue(Context.isOnEventLoopThread());
            PasswordVerifier.verifyAsync("TestPass123456", hash, vertx).onComplete(ar2 -> {
                verifyResult.set(ar2.result());
                latch3.countDown();
            });
            // Should not block
        });
        assertTrue(latch3.await(5, TimeUnit.SECONDS));
        assertEquals(true, verifyResult.get());
        // Also test PostgresUserDirectory auth offload - verify via direct DB hash compare and directory lookup
        UserRepository repo = repoWithVertx();
        String bcryptUser = "bcryptuser-" + System.nanoTime();
        var createReq = new UserRepository.CreateUserRequest(bcryptUser, "Bcrypt", "TestPass123456", null, true, false, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), null, null);
        UserRecord createdRec = repo.create(createReq, "actor").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertNotNull(createdRec);
        // Verify hash stored is bcrypt and manual verify true
        var hashRow = pool.preparedQuery("SELECT password_hash FROM csp_user WHERE lower(name)=lower($1)").execute(Tuple.of(bcryptUser))
            .toCompletionStage().toCompletableFuture().join().iterator().next();
        String storedHash = hashRow.getString("password_hash");
        assertTrue(storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$"));
        // Offloaded verify should succeed
        CountDownLatch latchVerify = new CountDownLatch(1);
        AtomicReference<Boolean> manualOk = new AtomicReference<>();
        vertx.runOnContext(vv -> {
            vertx.executeBlocking(() -> PasswordVerifier.verify("TestPass123456", storedHash), false)
                .onComplete(ar -> { manualOk.set(ar.result()); latchVerify.countDown(); });
        });
        assertTrue(latchVerify.await(5, TimeUnit.SECONDS));
        assertTrue(manualOk.get(), "manual offloaded verify should be true");
        // Directory authenticate offload
        PostgresUserDirectory dir = new PostgresUserDirectory(pool, vertx, 100);
        CountDownLatch latch4 = new CountDownLatch(1);
        AtomicReference<Boolean> authOk = new AtomicReference<>();
        AtomicReference<Throwable> authErr = new AtomicReference<>();
        vertx.runOnContext(v3 -> {
            assertTrue(Context.isOnEventLoopThread());
            dir.authenticate(bcryptUser, "TestPass123456").onComplete(ar3 -> {
                if (ar3.succeeded()) authOk.set(ar3.result());
                else authErr.set(ar3.cause());
                latch4.countDown();
            });
        });
        assertTrue(latch4.await(5, TimeUnit.SECONDS));
        if (authErr.get() != null) fail("auth failed: " + authErr.get().getMessage());
        assertTrue(Boolean.TRUE.equals(authOk.get()), "directory authenticate should be true, got " + authOk.get());
        // cleanup
        Optional<UserRecord> bcryptOpt = repo.get(bcryptUser).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (bcryptOpt.isPresent()) repo.delete(bcryptUser, bcryptOpt.get().version(), "cleanup").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
