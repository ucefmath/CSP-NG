package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.users.PostgresUserDirectory;
import com.bowman.cspng.users.crypto.MD5Crypt;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresUserDirectoryPgTest {

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
    try(java.sql.Connection c=java.sql.DriverManager.getConnection(jdbcUrl,pgUser,pgPass); java.sql.Statement st=c.createStatement()){ st.execute("DROP SCHEMA public CASCADE"); st.execute("CREATE SCHEMA public"); }
    Flyway flyway=Flyway.configure().dataSource(jdbcUrl,pgUser,pgPass).locations("classpath:db/migration").load(); flyway.migrate();
    vertx=Vertx.vertx();
    pool=PgPool.pool(vertx,new PgConnectOptions().setHost(pgHost).setPort(pgPort).setDatabase(pgDb).setUser(pgUser).setPassword(pgPass), new PoolOptions().setMaxSize(5));
  }
  @AfterAll static void teardown(){ if(pool!=null) pool.close(); if(vertx!=null) vertx.close(); if(pgContainer!=null) pgContainer.stop(); }
  private static boolean isReachable(String host,int port){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(host,port),2000); return true;}catch(Exception e){ return false; } }
  private void insertUser(String name,String hash){ pool.preparedQuery("INSERT INTO csp_user (name, display_name, password_hash, enabled, max_connections) VALUES ($1,$2,$3,true,2) ON CONFLICT (name) DO UPDATE SET password_hash=$3").execute(Tuple.of(name,name,hash)).toCompletionStage().toCompletableFuture().join(); }

  @Test @Order(1) void lookupAndAuthWithAcls() throws Exception {
    String hash=MD5Crypt.crypt("secret","salty123"); insertUser("pguser",hash);
    pool.preparedQuery("INSERT INTO csp_user_profile (user_name, profile) VALUES ($1,$2) ON CONFLICT DO NOTHING").execute(Tuple.of("pguser","default")).toCompletionStage().toCompletableFuture().join();
    pool.preparedQuery("INSERT INTO csp_user_service (user_name, profile, sid, mode) VALUES ($1,$2,$3,0) ON CONFLICT DO NOTHING").execute(Tuple.of("pguser","default",100)).toCompletionStage().toCompletableFuture().join();
    pool.preparedQuery("INSERT INTO csp_user_connector (user_name, connector) VALUES ($1,$2) ON CONFLICT DO NOTHING").execute(Tuple.of("pguser","conn1")).toCompletionStage().toCompletableFuture().join();
    PostgresUserDirectory dir=new PostgresUserDirectory(pool,vertx,100); dir.start().toCompletionStage().toCompletableFuture().join();
    var opt=dir.lookup("pguser").toCompletionStage().toCompletableFuture().get(); assertTrue(opt.isPresent()); assertEquals("pguser",opt.get().name()); assertTrue(opt.get().profiles().contains("default")); assertTrue(opt.get().allowedServices().containsKey("default")); assertTrue(opt.get().allowedConnectors().contains("conn1"));
    assertTrue(dir.authenticate("pguser","secret").toCompletionStage().toCompletableFuture().get()); assertFalse(dir.authenticate("pguser","wrong").toCompletionStage().toCompletableFuture().get());
    assertNotNull(dir.lookupCached("pguser")); var miss=dir.lookup("unknownXYZ").toCompletionStage().toCompletableFuture().get(); assertTrue(miss.isEmpty()); assertNull(dir.lookupCached("unknownXYZ"));
    pool.preparedQuery("INSERT INTO csp_user_profile (user_name, profile) VALUES ($1,$2) ON CONFLICT DO NOTHING").execute(Tuple.of("pguser","extra")).toCompletionStage().toCompletableFuture().join();
    long deadline=System.currentTimeMillis()+4000; while(System.currentTimeMillis()<deadline && dir.lookupCached("pguser")!=null) Thread.sleep(100);
    assertNull(dir.lookupCached("pguser"));
    var opt2=dir.lookup("pguser").toCompletionStage().toCompletableFuture().get(); assertTrue(opt2.get().profiles().contains("extra"));
    dir.invalidateAll(); var f1=dir.lookup("pguser"); var f2=dir.lookup("pguser"); assertSame(f1,f2); f1.toCompletionStage().toCompletableFuture().get(); f2.toCompletionStage().toCompletableFuture().get();
    dir.stop().toCompletionStage().toCompletableFuture().join();
  }
  @Test @Order(2) void triggerOnFourTablesInvalidates() throws Exception {
    String hash=MD5Crypt.crypt("pwd","salt1234"); insertUser("triguser",hash);
    PostgresUserDirectory dir=new PostgresUserDirectory(pool,vertx,100); dir.start().toCompletionStage().toCompletableFuture().join(); dir.lookup("triguser").toCompletionStage().toCompletableFuture().join(); assertNotNull(dir.lookupCached("triguser"));
    for(String table: new String[]{"csp_user","csp_user_profile","csp_user_service","csp_user_connector"}){ dir.lookup("triguser").toCompletionStage().toCompletableFuture().join(); assertNotNull(dir.lookupCached("triguser"));
      if(table.equals("csp_user")) pool.preparedQuery("UPDATE csp_user SET email='a@b.c' WHERE name=$1").execute(Tuple.of("triguser")).toCompletionStage().toCompletableFuture().join();
      else if(table.equals("csp_user_profile")) pool.preparedQuery("INSERT INTO csp_user_profile (user_name, profile) VALUES ($1,$2) ON CONFLICT DO NOTHING").execute(Tuple.of("triguser","p"+System.nanoTime())).toCompletionStage().toCompletableFuture().join();
      else if(table.equals("csp_user_service")){ int sid=(int)(System.nanoTime()%10000); pool.preparedQuery("INSERT INTO csp_user_service (user_name, profile, sid, mode) VALUES ($1,$2,$3,0) ON CONFLICT DO NOTHING").execute(Tuple.of("triguser","default",sid)).toCompletionStage().toCompletableFuture().join(); }
      else pool.preparedQuery("INSERT INTO csp_user_connector (user_name, connector) VALUES ($1,$2) ON CONFLICT DO NOTHING").execute(Tuple.of("triguser","c"+System.nanoTime())).toCompletionStage().toCompletableFuture().join();
      long deadline=System.currentTimeMillis()+3000; while(System.currentTimeMillis()<deadline && dir.lookupCached("triguser")!=null) Thread.sleep(100); assertNull(dir.lookupCached("triguser"),"invalidate after "+table);
    } dir.stop().toCompletionStage().toCompletableFuture().join();
  }
  @Test @Order(3) void flywayV2Migrated(){ var rows=pool.preparedQuery("SELECT column_name FROM information_schema.columns WHERE table_name='csp_user' AND column_name IN ('version','valid_from','valid_until')").execute().toCompletionStage().toCompletableFuture().join(); int count=0; for(var r:rows) count++; assertTrue(count>=3); var trig=pool.preparedQuery("SELECT trigger_name FROM information_schema.triggers WHERE event_object_table IN ('csp_user','csp_user_profile','csp_user_service','csp_user_connector') AND trigger_name LIKE '%user%'").execute().toCompletionStage().toCompletableFuture().join(); int trigCount=0; for(var r:trig) trigCount++; assertTrue(trigCount>=4); }

  @Test @Order(4) void lookupFreshBypassesCacheAndRevokesSession() throws Exception {
    String hash = MD5Crypt.crypt("adminpass", "salt1234");
    pool.preparedQuery("INSERT INTO csp_user (name, display_name, password_hash, enabled, admin, max_connections) VALUES ($1,$2,$3,true,true,2) ON CONFLICT (name) DO UPDATE SET enabled=true, admin=true, password_hash=$3").execute(Tuple.of("freshadmin", "Fresh Admin", hash)).toCompletionStage().toCompletableFuture().join();
    PostgresUserDirectory dir = new PostgresUserDirectory(pool, vertx, 100);
    dir.start().toCompletionStage().toCompletableFuture().join();

    // 1. Pré-chauffage du cache Caffeine (enabled=true)
    var optCached = dir.lookup("freshadmin").toCompletionStage().toCompletableFuture().get();
    assertTrue(optCached.isPresent());
    assertTrue(optCached.get().enabled());
    assertNotNull(dir.lookupCached("freshadmin"), "Pre-cached in Caffeine");

    // 2. Création de session pour freshadmin
    com.bowman.cspng.web.admin.AdminSessionManager sm = new com.bowman.cspng.web.admin.AdminSessionManager(vertx, java.time.Duration.ofHours(8), java.time.Duration.ofHours(24), 5, 1000);
    com.bowman.cspng.web.admin.AdminSession session = sm.createSession(optCached.get());
    String token = session.token();
    assertTrue(sm.getSession(token).isPresent());

    // 3. Désactivation directe en DB sans attendre NOTIFY
    pool.preparedQuery("UPDATE csp_user SET enabled=false, version=version+1 WHERE name='freshadmin'").execute().toCompletionStage().toCompletableFuture().join();

    // 4. lookupFresh lit directement en DB et retourne enabled=false
    var optFresh = dir.lookupFresh("freshadmin").toCompletionStage().toCompletableFuture().get();
    assertTrue(optFresh.isPresent());
    assertFalse(optFresh.get().enabled(), "lookupFresh must read disabled directly from DB");

    // 5. Validation que AdminCookieAuthenticationMechanism révoque immédiatement
    com.bowman.cspng.web.admin.AdminCookieAuthenticationMechanism mechanism = new com.bowman.cspng.web.admin.AdminCookieAuthenticationMechanism(sm, dir);
    io.vertx.ext.web.RoutingContext rc = (io.vertx.ext.web.RoutingContext) java.lang.reflect.Proxy.newProxyInstance(
        io.vertx.ext.web.RoutingContext.class.getClassLoader(),
        new Class<?>[]{io.vertx.ext.web.RoutingContext.class},
        (p, m, args) -> {
          if ("request".equals(m.getName())) {
            io.vertx.core.http.Cookie cookie = new io.vertx.core.http.impl.CookieImpl("CSPADMINSESSION", token);
            return java.lang.reflect.Proxy.newProxyInstance(
                io.vertx.core.http.HttpServerRequest.class.getClassLoader(),
                new Class<?>[]{io.vertx.core.http.HttpServerRequest.class},
                (rp, rm, rargs) -> {
                  if ("getHeader".equals(rm.getName())) {
                    if ("Cookie".equalsIgnoreCase((String) rargs[0])) return "CSPADMINSESSION=" + token;
                    return null;
                  }
                  if ("getCookie".equals(rm.getName())) return cookie;
                  return null;
                }
            );
          }
          return null;
        }
    );

    var identity = mechanism.authenticate(rc, null).await().indefinitely();
    assertNull(identity, "Authentication must fail for disabled user");
    assertTrue(sm.getSession(token).isEmpty(), "Session MUST be revoked immediately without waiting for cache expiration or LISTEN");

    dir.stop().toCompletionStage().toCompletableFuture().join();
  }
}
