package com.bowman.cspng.app;

import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserRepositoryAclPartialTest {

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
    void partialUpdateAllowedKeepsBlocked() throws Exception {
        UserRepository repo = new UserRepository(pool, new PasswordHasher(4), vertx);
        String name = "aclpartial-" + System.nanoTime();
        var create = new UserRepository.CreateUserRequest(name, "ACL", "TestPass123456", null, true, false, false, false, 1, null, -1,
            Set.of("p1"), Map.of("p1", Set.of(100)), Map.of("p1", Set.of(200)), Set.of(), null, null);
        var rec = repo.create(create, "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, rec.allowedServices().get("p1").size());
        assertTrue(rec.allowedServices().get("p1").contains(100));
        assertTrue(rec.blockedServices().get("p1").contains(200));

        // Update only allowed (should keep blocked)
        var updAllowed = new UserRepository.UpdateUserRequest(null, null, null, null, null, null, null, null, null, null,
            null, Map.of("p1", Set.of(101, 102)), null, null, null, null);
        var rec2 = repo.update(name, updAllowed, rec.version(), "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        // Fetch fresh
        var fetched = repo.get(name).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Set.of(101, 102), fetched.allowedServices().get("p1"), "allowed should be replaced");
        assertEquals(Set.of(200), fetched.blockedServices().get("p1"), "blocked should be kept");

        // Update only blocked (should keep allowed)
        var updBlocked = new UserRepository.UpdateUserRequest(null, null, null, null, null, null, null, null, null, null,
            null, null, Map.of("p1", Set.of(300)), null, null, null);
        var rec3 = repo.update(name, updBlocked, fetched.version(), "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var fetched2 = repo.get(name).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Set.of(101, 102), fetched2.allowedServices().get("p1"), "allowed should be kept");
        assertEquals(Set.of(300), fetched2.blockedServices().get("p1"), "blocked should be replaced");

        // Update both (should replace both)
        var updBoth = new UserRepository.UpdateUserRequest(null, null, null, null, null, null, null, null, null, null,
            null, Map.of("p1", Set.of(400)), Map.of("p1", Set.of(500)), null, null, null);
        repo.update(name, updBoth, fetched2.version(), "actor").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        var fetched3 = repo.get(name).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
        assertEquals(Set.of(400), fetched3.allowedServices().get("p1"));
        assertEquals(Set.of(500), fetched3.blockedServices().get("p1"));

        // Cleanup
        pool.preparedQuery("DELETE FROM csp_user WHERE lower(name)=lower($1)").execute(Tuple.of(name)).toCompletionStage().toCompletableFuture().join();
    }
}
