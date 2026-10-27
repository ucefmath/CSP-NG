package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConnectorConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportDedupDigestPgTest {

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
    // Ensure default profile exists for connector FK (V4 may have altered connector to require profile_name NOT NULL)
    pool.preparedQuery("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ('default',0,0,5000) ON CONFLICT DO NOTHING").execute().toCompletionStage().toCompletableFuture().join();
  }
  @AfterAll static void teardown(){ if(pool!=null) pool.close(); if(vertx!=null) vertx.close(); if(pgContainer!=null) pgContainer.stop(); }
  private static boolean isReachable(String h,int p){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(h,p),2000); return true;}catch(Exception e){ return false;}}

  @Test @Order(1)
  void findExistingEmpty() throws Exception {
    ConfigRepository repo = new ConfigRepository(pool);
    var found = repo.findExistingDedupDigests(Set.of()).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertTrue(found.isEmpty());
    found = repo.findExistingDedupDigests(Set.of("abc", "def")).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertTrue(found.isEmpty());
  }

  @Test @Order(2)
  void setAndFindDigest() throws Exception {
    ConfigRepository repo = new ConfigRepository(pool);
    String conn = "conn-dedup-" + System.nanoTime();
    ConnectorConfig cc = new ConnectorConfig(conn, "default", "cccam", true, false, "127.0.0.1", 12000, "user1", "file:/tmp/pwd", null, 5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
    repo.upsertConnector(cc, "tester").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    String digest = "abc123digest";
    repo.setDedupDigest(conn, digest, "tester").toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    var found = repo.findExistingDedupDigests(Set.of(digest, "other")).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(1, found.size());
    assertTrue(found.contains(digest));
    // bulk both found
    String digest2 = "secondDigest";
    String conn2 = "conn-dedup2-" + System.nanoTime();
    ConnectorConfig cc2 = new ConnectorConfig(conn2, "default", "cccam", true, false, "127.0.0.2", 12001, "user2", "file:/tmp/pwd2", null, 5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
    repo.upsertConnector(cc2, "tester").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    repo.setDedupDigest(conn2, digest2, "tester").toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    var found2 = repo.findExistingDedupDigests(Set.of(digest, digest2, "missing")).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    assertEquals(2, found2.size());
    // cleanup
    pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(conn)).toCompletionStage().toCompletableFuture().join();
    pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(conn2)).toCompletionStage().toCompletableFuture().join();
  }

  @Test @Order(3)
  void insertImportAudit() throws Exception {
    ConfigRepository repo = new ConfigRepository(pool);
    long before = pool.query("SELECT COUNT(*) as c FROM csp_audit_log WHERE event_type='CONNECTOR_IMPORT'").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
    repo.insertImportAudit("actor1", "default", 5, 3, 1, 0, 1, 0, 0).toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    long after = pool.query("SELECT COUNT(*) as c FROM csp_audit_log WHERE event_type='CONNECTOR_IMPORT'").execute().toCompletionStage().toCompletableFuture().join().iterator().next().getLong("c");
    assertEquals(before+1, after);
    var row = pool.query("SELECT summary, details_json::text as dj, changed_by FROM csp_audit_log WHERE event_type='CONNECTOR_IMPORT' ORDER BY id DESC LIMIT 1").execute().toCompletionStage().toCompletableFuture().join().iterator().next();
    assertEquals("actor1", row.getString("changed_by"));
    String summary = row.getString("summary");
    assertTrue(summary.contains("imported 3"));
    String details = row.getString("dj");
    assertNotNull(details);
    assertTrue(details.contains("profile") && details.contains("default"));
    assertTrue(details.contains("imported") && details.contains("3"));
    // ensure no secrets in details
    assertFalse(details.contains("password"));
  }
}
