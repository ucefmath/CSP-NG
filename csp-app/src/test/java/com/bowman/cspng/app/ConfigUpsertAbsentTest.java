package com.bowman.cspng.app;

import com.bowman.cspng.config.*;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import io.vertx.core.Vertx;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigUpsertAbsentTest {

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
        pool=PgPool.pool(vertx,new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(5));
    }
    @AfterAll static void teardown(){ if(pool!=null) pool.close(); if(vertx!=null) vertx.close(); if(pgContainer!=null) pgContainer.stop(); }
    private static boolean isReachable(String h,int p){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(h,p),2000); return true;}catch(Exception e){ return false;}}

    @Test
    void absentWithExpected5DoesNotCreateAndNoAudit() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String name = "absent-profile-" + System.nanoTime();
        // Ensure absent
        pool.preparedQuery("DELETE FROM csp_profile WHERE name=$1").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join();
        long auditBefore = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        ProfileConfig p = new ProfileConfig(name, true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,5L);
        try {
            repo.upsertProfile(p, "actor").toCompletionStage().toCompletableFuture().join();
            fail("expected 404 or 409 for absent+expected=5");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause instanceof ConfigNotFoundException || cause instanceof OptimisticLockException, "got " + cause);
        }
        // Ensure not created
        long cnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_profile WHERE name=$1").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(0, cnt, "should not have created");
        long auditAfter = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBefore, auditAfter, "no audit on failure");
        // Same for connector
        String cname = "absent-conn-" + System.nanoTime();
        pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(cname)).toCompletionStage().toCompletableFuture().join();
        long auditBeforeC = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(cname)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        ConnectorConfig cc = new ConnectorConfig(cname, "default", "newcamd", true, false, "127.0.0.1", 15000, null, null, null, 5000, 10000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 0, 5L);
        try {
            repo.upsertConnector(cc, "actor").toCompletionStage().toCompletableFuture().join();
            fail("expected failure for absent connector + expected 5");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue(cause instanceof ConfigNotFoundException || cause instanceof OptimisticLockException);
        }
        long cntC = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_connector WHERE name=$1").execute(Tuple.of(cname)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(0, cntC);
        long auditAfterC = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(cname)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBeforeC, auditAfterC);
    }

    @Test
    void createWithVersion1Succeeds() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String name = "create-ok-" + System.nanoTime();
        pool.preparedQuery("DELETE FROM csp_profile WHERE name=$1").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join();
        ProfileConfig p = new ProfileConfig(name, true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        Long v = repo.upsertProfile(p, "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, v);
        // cleanup
        repo.deleteProfile(name, v, "actor").toCompletionStage().toCompletableFuture().join();
    }

    @Test
    void version0ThrowsValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ProfileConfig("x", true, false, false, 0,0,null,null,5000L,null,false,0,null,null,null,null,0L));
        assertThrows(IllegalArgumentException.class, () -> new ConnectorConfig("y", "default", "newcamd", true, false, "127.0.0.1", 15000, null, null, null, 5000, 10000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 0, 0L));
    }
}
