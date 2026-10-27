package com.bowman.cspng.app;

import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Pool;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminBootstrapDirectTest {

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

    @SuppressWarnings("unchecked")
    private <T> jakarta.enterprise.inject.Instance<T> unsatisfiedInstance() {
        return (jakarta.enterprise.inject.Instance<T>) Proxy.newProxyInstance(
            jakarta.enterprise.inject.Instance.class.getClassLoader(),
            new Class[]{jakarta.enterprise.inject.Instance.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("isUnsatisfied".equals(name)) return true;
                if ("isAmbiguous".equals(name)) return false;
                if ("get".equals(name)) throw new IllegalStateException("unsatisfied");
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == void.class) return null;
                if (method.getReturnType() == java.util.Iterator.class) return java.util.Collections.emptyIterator();
                return null;
            });
    }

    @SuppressWarnings("unchecked")
    private <T> jakarta.enterprise.inject.Instance<T> satisfiedInstance(T instance) {
        return (jakarta.enterprise.inject.Instance<T>) Proxy.newProxyInstance(
            jakarta.enterprise.inject.Instance.class.getClassLoader(),
            new Class[]{jakarta.enterprise.inject.Instance.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("isUnsatisfied".equals(name)) return false;
                if ("isAmbiguous".equals(name)) return false;
                if ("get".equals(name)) return instance;
                if ("iterator".equals(name)) return java.util.Collections.singleton(instance).iterator();
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == void.class) return null;
                return null;
            });
    }

    private AdminBootstrap createBootstrap(String profile) throws Exception {
        AdminBootstrap ab = new AdminBootstrap(vertx);
        var fPool = AdminBootstrap.class.getDeclaredField("pgPoolInstance");
        fPool.setAccessible(true);
        fPool.set(ab, satisfiedInstance((Pool)pool));
        var fRepo = AdminBootstrap.class.getDeclaredField("userRepositoryInstance");
        fRepo.setAccessible(true);
        UserRepository repo = new UserRepository(pool, new PasswordHasher(4), vertx);
        fRepo.set(ab, satisfiedInstance(repo));
        var fPgEnabled = AdminBootstrap.class.getDeclaredField("pgEnabled");
        fPgEnabled.setAccessible(true); fPgEnabled.set(ab, true);
        var fBootstrapReq = AdminBootstrap.class.getDeclaredField("bootstrapRequired");
        fBootstrapReq.setAccessible(true); fBootstrapReq.set(ab, true);
        var fProfile = AdminBootstrap.class.getDeclaredField("quarkusProfile");
        fProfile.setAccessible(true); fProfile.set(ab, profile);
        var fBcrypt = AdminBootstrap.class.getDeclaredField("bcryptCost");
        fBcrypt.setAccessible(true); fBcrypt.set(ab, 4);
        var fPoolDirect = AdminBootstrap.class.getDeclaredField("pgPool");
        fPoolDirect.setAccessible(true); fPoolDirect.set(ab, pool);
        return ab;
    }

    @Test
    @Order(1)
    void existingAdminWithNullVarsNoOp() throws Exception {
        UserRepository repo = new UserRepository(pool, new PasswordHasher(4), vertx);
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        var create = new UserRepository.CreateUserRequest("existingadmin", "EA", "TestPass123456", null, true, true, false, false, 1, null, -1, java.util.Set.of(), java.util.Map.of(), java.util.Map.of(), java.util.Set.of(), null, null);
        repo.create(create, "test").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        AdminBootstrap ab = createBootstrap("test");
        assertDoesNotThrow(() -> ab.checkAndBootstrap(null, null));
        long cnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(1, cnt);
    }

    @Test
    @Order(2)
    void absentWithNullVarsFailsFastInProd() throws Exception {
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        AdminBootstrap ab = createBootstrap("prod");
        assertThrows(IllegalStateException.class, () -> ab.checkAndBootstrap(null, null));
        assertThrows(IllegalStateException.class, () -> ab.checkAndBootstrap("", ""));
        assertThrows(IllegalStateException.class, () -> ab.checkAndBootstrap("user", null));
    }

    @Test
    @Order(3)
    void absentWithNullVarsNoOpInTest() throws Exception {
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        AdminBootstrap ab = createBootstrap("test");
        assertDoesNotThrow(() -> ab.checkAndBootstrap(null, null));
        long cnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(0, cnt);
    }

    @Test
    @Order(4)
    void absentWithCredsCreates() throws Exception {
        pool.query("DELETE FROM csp_user").execute().toCompletionStage().toCompletableFuture().join();
        AdminBootstrap ab = createBootstrap("test");
        ab.checkAndBootstrap("bootstrapuser", "TestPass123456");
        var row = pool.preparedQuery("SELECT password_hash FROM csp_user WHERE lower(name)=lower($1)").execute(io.vertx.sqlclient.Tuple.of("bootstrapuser")).toCompletionStage().toCompletableFuture().join();
        assertTrue(row.size() >0);
        String hash = row.iterator().next().getString("password_hash");
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"));
        AdminBootstrap ab2 = createBootstrap("test");
        assertDoesNotThrow(() -> ab2.checkAndBootstrap("another", "TestPass123456"));
        long cnt = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_user WHERE enabled=true AND admin=true").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(1, cnt);
    }
}
