package com.bowman.cspng.app;

import com.bowman.cspng.config.*;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V3ExtendedCaidProfilePgTest {

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
            try {
                String testJdbc = "jdbc:postgresql://localhost:5433/testdb";
                Flyway.configure().dataSource(testJdbc, "testuser", "testpass").load();
                pgHost="localhost"; pgPort=5433; pgDb="testdb"; pgUser="testuser"; pgPass="testpass"; useEnv=true;
            } catch (Exception ignore) {}
        }
        if (!useEnv) {
            if (System.getenv("DOCKER_HOST")==null) { java.io.File sock=new java.io.File("/run/user/1000/docker.sock"); if(sock.exists()) System.setProperty("DOCKER_HOST","unix:///run/user/1000/docker.sock"); }
            pgContainer=new PostgreSQLContainer<>("postgres:15-alpine").withDatabaseName("csp_config").withUsername("csp_app").withPassword("csp_test_pass");
            pgContainer.start();
            pgHost=pgContainer.getHost(); pgPort=pgContainer.getMappedPort(5432); pgDb=pgContainer.getDatabaseName(); pgUser=pgContainer.getUsername(); pgPass=pgContainer.getPassword();
        }
        String jdbcUrl="jdbc:postgresql://"+pgHost+":"+pgPort+"/"+pgDb;
        try(java.sql.Connection c=java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass); java.sql.Statement st=c.createStatement()){ st.execute("DROP SCHEMA public CASCADE"); st.execute("CREATE SCHEMA public"); }
        Flyway flyway=Flyway.configure().dataSource(jdbcUrl, pgUser, pgPass).locations("classpath:db/migration").load(); flyway.migrate();
        vertx=Vertx.vertx();
        pool=PgPool.pool(vertx,new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(5));
    }
    @AfterAll static void teardown(){ if(pool!=null) pool.close(); if(vertx!=null) vertx.close(); if(pgContainer!=null) pgContainer.stop(); }
    private static boolean isReachable(String h,int p){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(h,p),2000); return true;}catch(Exception e){ return false;}}

    // -- helpers --
    private long countAudit(String entity){ return pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(entity)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c"); }
    private void exec(String sql, Tuple t){ pool.preparedQuery(sql).execute(t).toCompletionStage().toCompletableFuture().join(); }
    private void execRaw(String sql){ pool.query(sql).execute().toCompletionStage().toCompletableFuture().join(); }

    @Test @Order(1)
    void seedExtendedDisabledAndSchema() {
        var row = pool.preparedQuery("SELECT enabled, listen_port, bind_address, catch_all, version FROM csp_extended_newcamd WHERE id=1").execute().toCompletionStage().toCompletableFuture().join().iterator().next();
        assertEquals(false, row.getBoolean("enabled"));
        assertEquals(2233, row.getInteger("listen_port"));
        assertEquals("0.0.0.0", row.getString("bind_address"));
        assertEquals(false, row.getBoolean("catch_all"));
        assertEquals(1L, row.getLong("version"));
        // tables exist
        pool.query("SELECT 1 FROM csp_connector_caid_profile LIMIT 1").execute().toCompletionStage().toCompletableFuture().join();
        pool.query("SELECT 1 FROM csp_profile_provider_ident LIMIT 1").execute().toCompletionStage().toCompletableFuture().join();
        // indexes
        var idx = pool.query("SELECT indexname FROM pg_indexes WHERE tablename='csp_connector_caid_profile'").execute().toCompletionStage().toCompletableFuture().join();
        assertTrue(idx.size() >= 2);
        // triggers existence
        var trig = pool.query("SELECT trigger_name FROM information_schema.triggers WHERE event_object_table='csp_connector_caid_profile'").execute().toCompletionStage().toCompletableFuture().join();
        assertTrue(trig.size() >= 2);
        // bind_address check
        try {
            pool.preparedQuery("UPDATE csp_extended_newcamd SET bind_address='' WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
            fail("blank bind should fail");
        } catch (Exception e) {
            // expected 23514
            Throwable c = e.getCause()!=null?e.getCause():e;
            assertTrue(c.getMessage().contains("btrim") || c.getMessage().contains("check") || c.toString().contains("23514"));
            // revert
            pool.preparedQuery("UPDATE csp_extended_newcamd SET bind_address='0.0.0.0' WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
        }
    }

    @Test @Order(2)
    void dynamicWithoutMapFails() throws Exception {
        String conn = "dyn-nomap-" + System.nanoTime();
        String prof = "prof-dyn-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        // try dynamic connector without map via raw tx (deferred fails at commit)
        try {
            pool.withTransaction(c -> c.preparedQuery("INSERT INTO csp_connector (name, profile_name, protocol, host, port) VALUES ($1,NULL,'newcamd','127.0.0.1',15000)")
                .execute(Tuple.of(conn))).toCompletionStage().toCompletableFuture().join();
            fail("should have failed deferred");
        } catch (Exception e) {
            assertTrue(e.getCause()!=null && e.getCause().getMessage().contains("requires >=1"));
        }
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(3)
    void fixedWithMapFails() throws Exception {
        String conn = "fixed-map-" + System.nanoTime();
        String prof = "prof-fixed-" + System.nanoTime();
        String mapProf = "prof-map-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(mapProf));
        exec("INSERT INTO csp_connector (name, profile_name, protocol, host, port) VALUES ($1,$2,'newcamd','127.0.0.1',15000)", Tuple.of(conn, prof));
        try {
            pool.withTransaction(c -> c.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,0,1702,$2)")
                .execute(Tuple.of(conn, mapProf))).toCompletionStage().toCompletableFuture().join();
            fail("fixed with map should fail");
        } catch (Exception e) {
            assertTrue(e.getCause().getMessage().contains("fixed profile"));
        }
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(mapProf));
    }

    @Test @Order(4)
    void atomicDynamicCreatePass() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-atom-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-atom-" + System.nanoTime();
        ConnectorConfig cc = new ConnectorConfig(conn, null, "newcamd", true, false, "127.0.0.1", 15001, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        ConnectorCaidProfileConfig map = new ConnectorCaidProfileConfig(0, conn, 0, 0x1702, prof, 1L);
        Long v = repo.upsertConnectorWithCaidMap(cc, List.of(map), "tester").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, v);
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertTrue(snap.caidProfileMapFor(conn).containsKey(0x1702));
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(5)
    void transitionDynamicToFixedDeletesMap() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String profDyn = "prof-dyn2-" + System.nanoTime();
        String profFixed = "prof-fix2-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(profDyn));
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(profFixed));
        String conn = "conn-trans-" + System.nanoTime();
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true, false, "127.0.0.1", 15002, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        repo.upsertConnectorWithCaidMap(dyn, List.of(new ConnectorCaidProfileConfig(0,conn,0,100,profDyn,1L)), "t").toCompletionStage().toCompletableFuture().join();
        var snap1 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap1.connectorByName().get(conn).version();
        // now fixed
        ConnectorConfig fixed = new ConnectorConfig(conn, profFixed, "newcamd", true, false, "127.0.0.1", 15002, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,ver);
        Long v2 = repo.upsertConnector(fixed, "t").toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, v2);
        var snap2 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertTrue(snap2.caidProfilesForConnector(conn).isEmpty());
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(profDyn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(profFixed));
    }

    @Test @Order(6)
    void duplicateCaidAndOrdinalFail() throws Exception {
        String prof = "prof-dup-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-dup-" + System.nanoTime();
        pool.withTransaction(c -> c.preparedQuery("INSERT INTO csp_connector (name, profile_name, protocol, host, port) VALUES ($1,NULL,'newcamd','127.0.0.1',15003)").execute(Tuple.of(conn))
            .compose(v -> c.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,0,100,$2)").execute(Tuple.of(conn, prof)))).toCompletionStage().toCompletableFuture().join();
        // duplicate caId
        try {
            pool.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,1,100,$2)").execute(Tuple.of(conn, prof)).toCompletionStage().toCompletableFuture().join();
            fail("duplicate caId should fail 23505");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        // duplicate ordinal
        try {
            pool.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,0,101,$2)").execute(Tuple.of(conn, prof)).toCompletionStage().toCompletableFuture().join();
            fail("duplicate ordinal should fail");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        // cleanup
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(7)
    void noncontiguousRepositoryReject() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-ncont-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-ncont-" + System.nanoTime();
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true, false, "127.0.0.1", 15004, null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        // map with gap 0 and 2 (missing 1)
        var map = List.of(
            new ConnectorCaidProfileConfig(0,conn,0,100,prof,1L),
            new ConnectorCaidProfileConfig(0,conn,2,101,prof,1L)
        );
        try {
            repo.upsertConnectorWithCaidMap(dyn, map, "t").toCompletionStage().toCompletableFuture().join();
            fail("noncontiguous should be rejected");
        } catch (Exception e) {
            Throwable c = e.getCause()!=null?e.getCause():e;
            assertTrue(c instanceof ConfigValidationException || c instanceof IllegalArgumentException);
        }
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(8)
    void mapProfileAbsentAndDisabledFail() throws Exception {
        String profValid = "prof-abs-valid-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(profValid));
        String conn = "conn-abs-" + System.nanoTime();
        pool.withTransaction(c -> c.preparedQuery("INSERT INTO csp_connector (name, profile_name, protocol, host, port) VALUES ($1,NULL,'newcamd','127.0.0.1',15005)").execute(Tuple.of(conn))
            .compose(v -> c.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,0,200,$2)").execute(Tuple.of(conn, profValid)))).toCompletionStage().toCompletableFuture().join();
        // absent profile
        try {
            pool.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,1,201,'no-such')").execute(Tuple.of(conn)).toCompletionStage().toCompletableFuture().join();
            fail("absent profile should fail 23503");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        String profDis = "prof-dis-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms, enabled) VALUES ($1,0,0,5000,false)", Tuple.of(profDis));
        try {
            pool.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,1,202,$2)").execute(Tuple.of(conn, profDis)).toCompletionStage().toCompletableFuture().join();
            fail("disabled profile should fail via trigger");
        } catch (Exception e) { assertTrue(e.getCause().getMessage().contains("disabled")); }
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(profValid));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(profDis));
    }

    @Test @Order(9)
    void disableProfileReferencedFailAndDeleteRestrict() throws Exception {
        String prof = "prof-ref-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-ref-" + System.nanoTime();
        pool.withTransaction(c -> c.preparedQuery("INSERT INTO csp_connector (name, profile_name, protocol, host, port) VALUES ($1,NULL,'newcamd','127.0.0.1',15006)").execute(Tuple.of(conn))
            .compose(v -> c.preparedQuery("INSERT INTO csp_connector_caid_profile (connector_name, ordinal, ca_id, profile_name) VALUES ($1,0,300,$2)").execute(Tuple.of(conn, prof)))).toCompletionStage().toCompletableFuture().join();
        // disable should fail deferred
        try {
            pool.withTransaction(c -> c.preparedQuery("UPDATE csp_profile SET enabled=false WHERE name=$1").execute(Tuple.of(prof))).toCompletionStage().toCompletableFuture().join();
            fail("disable referenced should fail");
        } catch (Exception e) { assertTrue(e.getCause().getMessage().contains("Cannot disable")); }
        // delete restrict
        try {
            pool.preparedQuery("DELETE FROM csp_profile WHERE name=$1").execute(Tuple.of(prof)).toCompletionStage().toCompletableFuture().join();
            fail("delete restrict should fail 23503");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(10)
    void portCollisionExtendedListener() throws Exception {
        // ensure extended disabled initially
        pool.preparedQuery("UPDATE csp_extended_newcamd SET enabled=false WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
        String prof = "prof-coll-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        // listener enabled on 2233 0.0.0.0
        long lid = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',2233,'0.0.0.0',true) RETURNING id").execute(Tuple.of(prof)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        try {
            // test repository update should fail due to collision
            ConfigRepository repo = new ConfigRepository(pool);
            var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
            ExtendedNewcamdConfig ext = snap.extendedNewcamd();
            ExtendedNewcamdConfig upd = new ExtendedNewcamdConfig(true, 2233, "0.0.0.0", false, false, "env:TEST_DES", null, null, 2048, ext.version());
            try { repo.updateExtendedNewcamd(upd, "t").toCompletionStage().toCompletableFuture().join(); fail(); } catch (Exception e){ assertTrue(e.getCause()!=null); }
        } finally {
            pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(lid)).toCompletionStage().toCompletableFuture().join();
            exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
            pool.preparedQuery("UPDATE csp_extended_newcamd SET enabled=false WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
        }
        // IPv6 wildcard
        String prof3 = "prof-coll3-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof3));
        long lid3 = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',2235,'::',true) RETURNING id").execute(Tuple.of(prof3)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        try {
            pool.withTransaction(c -> c.preparedQuery("UPDATE csp_extended_newcamd SET enabled=true, listen_port=2235, bind_address='2001:db8::1', des_key_ref='env:TEST_DES' WHERE id=1").execute()).toCompletionStage().toCompletableFuture().join();
            fail("IPv6 wildcard should collide with IPv6 specific");
        } catch (Exception e) { assertTrue(e.getCause().getMessage().contains("Port collision")); }
        // cross family wildcard vs specific must PASS (different families)
        try {
            pool.withTransaction(c -> c.preparedQuery("UPDATE csp_extended_newcamd SET enabled=true, listen_port=2235, bind_address='192.168.1.1', des_key_ref='env:TEST_DES' WHERE id=1").execute()).toCompletionStage().toCompletableFuture().join();
            // should succeed (no collision) -> verify no exception
        } catch (Exception e) { fail("IPv6 wildcard vs IPv4 specific must PASS, but got collision: " + e.getCause().getMessage()); }
        finally {
            pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(lid3)).toCompletionStage().toCompletableFuture().join();
            exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof3));
            pool.preparedQuery("UPDATE csp_extended_newcamd SET enabled=false, listen_port=2233, bind_address='0.0.0.0' WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
        }

        // listener vs listener exact and wildcard
        String pA = "prof-lla-" + System.nanoTime();
        String pB = "prof-llb-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(pA));
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(pB));
        long la = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',28000,'127.0.0.1',true) RETURNING id").execute(Tuple.of(pA)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        long lb =0;
        try {
            pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',28000,'127.0.0.1',true) RETURNING id").execute(Tuple.of(pB)).toCompletionStage().toCompletableFuture().join();
            fail("exact collision should fail");
        } catch (Exception e) { assertTrue(e.getCause().getMessage().contains("Port collision")); }
        // wildcard vs specific
        try {
            lb = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',28000,'0.0.0.0',true) RETURNING id").execute(Tuple.of(pB)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
            fail("wildcard collision should fail");
        } catch (Exception e) { assertTrue(e.getCause().getMessage().contains("Port collision")); }
        // disabled and different port pass
        long lb2 = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',28000,'127.0.0.2',false) RETURNING id").execute(Tuple.of(pB)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        long lb3 = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',28001,'127.0.0.1',true) RETURNING id").execute(Tuple.of(pB)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        // cleanup
        pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(la)).toCompletionStage().toCompletableFuture().join();
        pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(lb2)).toCompletionStage().toCompletableFuture().join();
        pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(lb3)).toCompletionStage().toCompletableFuture().join();
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(pA));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(pB));
    }

    @Test @Order(11)
    void replaceCaidMapOptimisticAndSuccess() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-rep-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-rep-" + System.nanoTime();
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true,false,"127.0.0.1",15010,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        repo.upsertConnectorWithCaidMap(dyn, List.of(new ConnectorCaidProfileConfig(0,conn,0,500,prof,1L)), "a").toCompletionStage().toCompletableFuture().join();
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap.connectorByName().get(conn).version();
        long auditBefore = countAudit(conn);
        // wrong version should fail and not change
        try {
            repo.replaceCaidProfileMap(conn, List.of(new ConnectorCaidProfileConfig(0,conn,0,501,prof,1L)), ver-1, "a").toCompletionStage().toCompletableFuture().join();
            fail("optimistic should fail");
        } catch (Exception e) { assertTrue(e.getCause() instanceof OptimisticLockException); }
        var snap2 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertEquals(500, snap2.caidProfilesForConnector(conn).get(0).caId());
        assertEquals(auditBefore, countAudit(conn));
        // success
        long auditBefore2 = auditBefore;
        Long newVer = repo.replaceCaidProfileMap(conn, List.of(new ConnectorCaidProfileConfig(0,conn,0,501,prof,1L)), ver, "a").toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, newVer);
        var snap3 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertEquals(501, snap3.caidProfilesForConnector(conn).get(0).caId());
        assertEquals(auditBefore2+1, countAudit(conn));
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(12)
    void providerReplaceSuccessAndFailures() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-prov-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap.profileByName().get(prof).version();
        // success
        Long nv = repo.replaceProviderIdents(prof, List.of(new ProfileProviderIdentConfig(0,prof,100,"lab",1L)), ver, "a").toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, nv);
        // disabled fail
        exec("UPDATE csp_profile SET enabled=false WHERE name=$1", Tuple.of(prof));
        snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver2 = snap.profileByName().get(prof).version();
        try { repo.replaceProviderIdents(prof, List.of(new ProfileProviderIdentConfig(0,prof,101,"x",1L)), ver2, "a").toCompletionStage().toCompletableFuture().join(); fail(); } catch (Exception e){ assertTrue(e.getCause() instanceof ConfigValidationException); }
        // unknown profile
        try { repo.replaceProviderIdents("no-such-prof-xyz", List.of(), 1L, "a").toCompletionStage().toCompletableFuture().join(); fail(); } catch (Exception e){ assertTrue(e.getCause() instanceof ConfigNotFoundException); }
        // conflict rollback
        exec("UPDATE csp_profile SET enabled=true WHERE name=$1", Tuple.of(prof));
        snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver3 = snap.profileByName().get(prof).version();
        try { repo.replaceProviderIdents(prof, List.of(new ProfileProviderIdentConfig(0,prof,102,"y",1L)), ver3-1, "a").toCompletionStage().toCompletableFuture().join(); fail(); } catch (Exception e){ assertTrue(e.getCause() instanceof OptimisticLockException); }
        var snap4 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertEquals(1, snap4.providerIdentsFor(prof).size()); // still 100
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(13)
    void extendedUpdateAuditNotifyConflict() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        ExtendedNewcamdConfig ext = snap.extendedNewcamd();
        long ver = ext.version();
        long auditBefore = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_type='csp_extended_newcamd'").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        // success
        ExtendedNewcamdConfig upd = new ExtendedNewcamdConfig(true, 2233, "0.0.0.0", true, false, "env:TEST_DES", null,null,2048, ver);
        Long nv = repo.updateExtendedNewcamd(upd, "actorX").toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, nv);
        long auditAfter = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_type='csp_extended_newcamd'").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBefore+1, auditAfter);
        // conflict
        try { repo.updateExtendedNewcamd(upd, "actorX").toCompletionStage().toCompletableFuture().join(); fail(); } catch (Exception e){ assertTrue(e.getCause() instanceof OptimisticLockException); }
        // reset
        var snap2 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        ExtendedNewcamdConfig reset = new ExtendedNewcamdConfig(false, 2233, "0.0.0.0", false, false, null,null,null,2048, snap2.extendedNewcamd().version());
        repo.updateExtendedNewcamd(reset, "reset").toCompletionStage().toCompletableFuture().join();
        // NOTIFY test
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ch = new AtomicReference<>();
        Vertx v2 = Vertx.vertx();
        PgPool pool2 = PgPool.pool(v2, new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(1));
        pool2.getConnection().compose(conn -> {
            if (conn instanceof io.vertx.pgclient.PgConnection pg) pg.notificationHandler(n -> { ch.set(n.getChannel()); latch.countDown(); });
            return conn.query("LISTEN csp_config_changed").execute().mapEmpty();
        }).toCompletionStage().toCompletableFuture().join();
        Thread.sleep(200);
        var snap3 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        repo.updateExtendedNewcamd(new ExtendedNewcamdConfig(false, 2233, "0.0.0.0", false, false, null,null,null,2048, snap3.extendedNewcamd().version()), "notif").toCompletionStage().toCompletableFuture().join();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals("csp_config_changed", ch.get());
        pool2.close(); v2.close();
    }

    @Test @Order(14)
    void loadAllHydratesAndHelpers() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-hyd-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-hyd-" + System.nanoTime();
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true,false,"127.0.0.1",15020,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        repo.upsertConnectorWithCaidMap(dyn, List.of(
            new ConnectorCaidProfileConfig(0,conn,1,200,prof,1L),
            new ConnectorCaidProfileConfig(0,conn,0,100,prof,1L)
        ), "a").toCompletionStage().toCompletableFuture().join();
        exec("INSERT INTO csp_profile_provider_ident (profile_name, provider_ident, label) VALUES ($1, 999, 'lab')", Tuple.of(prof));
        ConfigSnapshot snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertNotNull(snap.extendedNewcamd());
        assertNotNull(snap.connectorCaidProfiles());
        assertNotNull(snap.profileProviderIdents());
        var map = snap.caidProfileMapFor(conn);
        assertEquals(2, map.size());
        // ordered by ordinal 0->100, 1->200
        assertEquals(List.of(100,200), List.copyOf(map.keySet()));
        assertEquals(prof, map.get(100));
        var list = snap.caidProfilesForConnector(conn);
        assertEquals(0, list.get(0).ordinal());
        assertEquals(100, list.get(0).caId());
        var prov = snap.providerIdentsFor(prof);
        assertEquals(1, prov.size());
        assertEquals(999, prov.get(0).providerIdent());
        // anti-NPE
        assertTrue(snap.listenersForProfile(null).isEmpty());
        assertTrue(snap.connectorsForProfile(null).isEmpty());
        assertTrue(snap.caidProfileMapFor(null).isEmpty());
        assertTrue(snap.providerIdentsFor(null).isEmpty());
        // cleanup
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(15)
    void replaceProviderIdentsNullNormalized() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-nullprov-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        exec("INSERT INTO csp_profile_provider_ident (profile_name, provider_ident, label) VALUES ($1, 1, 'a')", Tuple.of(prof));
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap.profileByName().get(prof).version();
        long auditBefore = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(prof)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        // null -> List.of() should clear all and bump version
        Long nv = repo.replaceProviderIdents(prof, null, ver, "actor").toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, nv);
        var snap2 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertTrue(snap2.providerIdentsFor(prof).isEmpty());
        long auditAfter = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(prof)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBefore+1, auditAfter);
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(16)
    void concurrentReplaceCaidMapExactlyOneSucceeds() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        String prof = "prof-conc-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-conc-" + System.nanoTime();
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true,false,"127.0.0.1",15030,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        repo.upsertConnectorWithCaidMap(dyn, List.of(new ConnectorCaidProfileConfig(0,conn,0,100,prof,1L)), "a").toCompletionStage().toCompletableFuture().join();
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long ver = snap.connectorByName().get(conn).version();
        long auditBefore = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(conn)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        // two concurrent replaces with same expectedVersion
        var f1 = repo.replaceCaidProfileMap(conn, List.of(new ConnectorCaidProfileConfig(0,conn,0,101,prof,1L)), ver, "t1");
        var f2 = repo.replaceCaidProfileMap(conn, List.of(new ConnectorCaidProfileConfig(0,conn,0,102,prof,1L)), ver, "t2");
        int successes = 0, conflicts = 0;
        try { f1.toCompletionStage().toCompletableFuture().join(); successes++; } catch (Exception e) { if ((e.getCause()!=null?e.getCause():e) instanceof OptimisticLockException) conflicts++; }
        try { f2.toCompletionStage().toCompletableFuture().join(); successes++; } catch (Exception e) { if ((e.getCause()!=null?e.getCause():e) instanceof OptimisticLockException) conflicts++; }
        assertEquals(1, successes, "exactly one success");
        assertEquals(1, conflicts, "exactly one optimistic lock");
        var snap2 = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        assertEquals(ver+1, snap2.connectorByName().get(conn).version());
        assertEquals(1, snap2.caidProfilesForConnector(conn).size());
        int finalCa = snap2.caidProfilesForConnector(conn).get(0).caId();
        assertTrue(finalCa==101 || finalCa==102);
        long auditAfter = pool.preparedQuery("SELECT COUNT(*) as c FROM csp_audit_log WHERE entity_name=$1").execute(Tuple.of(conn)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
        assertEquals(auditBefore+1, auditAfter, "only one audit");
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(17)
    void concurrentDisableProfileVsInsertMapNoDisabledFinalState() throws Exception {
        String prof = "prof-conc-dis-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(prof));
        String conn = "conn-conc-dis-" + System.nanoTime();
        // start with dynamic connector with valid map to prof (enabled)
        ConfigRepository repo = new ConfigRepository(pool);
        ConnectorConfig dyn = new ConnectorConfig(conn, null, "newcamd", true,false,"127.0.0.1",15031,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        repo.upsertConnectorWithCaidMap(dyn, List.of(new ConnectorCaidProfileConfig(0,conn,0,200,prof,1L)), "a").toCompletionStage().toCompletableFuture().join();
        // concurrent: disable profile vs replace map adding same disabled profile (or re-insert)
        // Disable transaction: UPDATE csp_profile SET enabled=false
        // Insert transaction: replace map with same prof (still enabled check should fail if disable wins)
        var snap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long verConn = snap.connectorByName().get(conn).version();
        long verProf = snap.profileByName().get(prof).version();
        // Use raw transactions for concurrency: two withTransaction in parallel
        var disableFuture = pool.withTransaction(c -> c.preparedQuery("UPDATE csp_profile SET enabled=false WHERE name=$1").execute(Tuple.of(prof))).toCompletionStage().toCompletableFuture();
        // Small delay to increase interleaving
        Thread.sleep(50);
        var replaceFuture = repo.replaceCaidProfileMap(conn, List.of(new ConnectorCaidProfileConfig(0,conn,0,201,prof,1L)), verConn, "b").toCompletionStage().toCompletableFuture();
        int disableSuccess=0, disableFail=0, replaceSuccess=0, replaceFail=0;
        try { disableFuture.join(); disableSuccess++; } catch (Exception e) { disableFail++; }
        try { replaceFuture.join(); replaceSuccess++; } catch (Exception e) { replaceFail++; }
        // At least one must fail (disable should fail if map still references, or replace should fail if profile disabled)
        assertTrue(disableFail==1 || replaceFail==1, "at least one tx must fail, disableSuccess="+disableSuccess+" disableFail="+disableFail+" replaceSuccess="+replaceSuccess+" replaceFail="+replaceFail);
        var finalSnap = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        // No final state where dynamic map points to disabled profile
        if (finalSnap.profileByName().containsKey(prof)) {
            boolean profEnabled = finalSnap.profileByName().get(prof).enabled();
            if (!profEnabled) {
                // if disabled, map must be empty or not contain that profile (but our connector is dynamic, so map must not contain disabled)
                var maps = finalSnap.caidProfilesForConnector(conn);
                for (var m : maps) assertNotEquals(prof, m.profileName(), "disabled profile must not be referenced");
            }
        }
        // cleanup
        exec("DELETE FROM csp_connector WHERE name=$1", Tuple.of(conn));
        pool.preparedQuery("UPDATE csp_profile SET enabled=true WHERE name=$1").execute(Tuple.of(prof)).toCompletionStage().toCompletableFuture().join();
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(prof));
    }

    @Test @Order(18)
    void bindAddressCidrAndIntegerShorthandRejected() {
        // CIDR
        try {
            pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ('default','newcamd',29999,'192.168.1.1/24',true)").execute().toCompletionStage().toCompletableFuture().join();
            fail("CIDR should be rejected");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        try {
            pool.preparedQuery("UPDATE csp_extended_newcamd SET bind_address='::/64' WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
            fail("CIDR IPv6 should be rejected");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        // integer shorthand
        try {
            pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ('default','newcamd',29998,'0',true)").execute().toCompletionStage().toCompletableFuture().join();
            fail("integer shorthand should be rejected");
        } catch (Exception e) { assertTrue(e.getCause()!=null); }
        // valid host literals should pass (no exception)
        String profTmp = "prof-bind-ok-" + System.nanoTime();
        exec("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ($1,0,0,5000)", Tuple.of(profTmp));
        long id = pool.preparedQuery("INSERT INTO csp_profile_listener (profile_name, protocol, port, bind_address, enabled) VALUES ($1,'newcamd',29997,'192.168.1.1',true) RETURNING id").execute(Tuple.of(profTmp)).toCompletionStage().toCompletableFuture().join().iterator().next().getLong("id");
        pool.preparedQuery("DELETE FROM csp_profile_listener WHERE id=$1").execute(Tuple.of(id)).toCompletionStage().toCompletableFuture().join();
        exec("DELETE FROM csp_profile WHERE name=$1", Tuple.of(profTmp));
    }

    @Test @Order(19)
    void loadAllIsRepeatableRead() throws Exception {
        ConfigRepository repo = new ConfigRepository(pool);
        ConfigSnapshot before = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        String oldRoot = before.root().proxyName();
        String marker = "marker-profile-" + System.nanoTime();
        String markerRoot = "marker-root-" + System.nanoTime();
        String jdbcUrl = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
        java.sql.Connection connA = java.sql.DriverManager.getConnection(jdbcUrl, pgUser, pgPass);
        connA.setAutoCommit(false);
        connA.createStatement().execute("LOCK TABLE csp_profile IN ACCESS EXCLUSIVE MODE");
        // start loadAll async (will block on SELECT csp_profile)
        var loadFuture = repo.loadAll().toCompletionStage().toCompletableFuture();
        // poll pg_stat_activity for blocked query with bounded timeout (not sleep alone)
        long deadline = System.currentTimeMillis() + 5000;
        boolean blocked = false;
        while (System.currentTimeMillis() < deadline) {
            var rows = pool.query("SELECT count(*) as c FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%csp_profile%' AND state='active'").execute().toCompletionStage().toCompletableFuture().join();
            long c = rows.iterator().next().getLong("c");
            if (c > 0) { blocked = true; break; }
            // also check if loadFuture already done (should not)
            if (loadFuture.isDone()) break;
            Thread.sleep(100);
        }
        // If not detected via pg_stat, still ensure loadFuture is not done (blocked)
        if (!blocked) {
            // fallback: ensure loadFuture not done within 500ms
            Thread.sleep(300);
            assertFalse(loadFuture.isDone(), "loadAll should be blocked on csp_profile lock");
            blocked = true;
        }
        assertTrue(blocked, "loadAll should be blocked");
        // Tx A modifies root and inserts marker, then commit
        connA.createStatement().executeUpdate("UPDATE csp_root_config SET proxy_name='" + markerRoot + "', version=version+1 WHERE id=1");
        connA.createStatement().executeUpdate("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ('" + marker + "',0,0,5000)");
        connA.commit();
        connA.close();
        // loadAll should now resume and return snapshot coherent from before (old root, no marker)
        ConfigSnapshot snap = loadFuture.get(5, TimeUnit.SECONDS);
        assertEquals(oldRoot, snap.root().proxyName(), "REPEATABLE READ must see old root");
        assertFalse(snap.profileByName().containsKey(marker), "REPEATABLE READ must not see marker profile");
        // cleanup
        pool.preparedQuery("DELETE FROM csp_profile WHERE name=$1").execute(Tuple.of(marker)).toCompletionStage().toCompletableFuture().join();
        // restore root (need version)
        var cur = repo.loadAll().toCompletionStage().toCompletableFuture().join();
        long curVer = cur.root().version();
        pool.preparedQuery("UPDATE csp_root_config SET proxy_name=$1, version=$2 WHERE id=1").execute(Tuple.of(oldRoot, curVer+1)).toCompletionStage().toCompletableFuture().join();
        // Actually restore via repo is cleaner: fetch and update
        // For simplicity, set back via SQL with version increment
        pool.query("SELECT version FROM csp_root_config WHERE id=1").execute().toCompletionStage().toCompletableFuture().join();
    }
}
