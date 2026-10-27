package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.admin.ImportModels.*;
import com.bowman.cspng.app.runtime.DnsResolverService;
import com.bowman.cspng.app.runtime.RuntimeCore;
import com.bowman.cspng.app.runtime.SecretMaterializer;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileProviderIdentConfig;
import com.bowman.cspng.connectors.ConnectorProbe;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CccamImportServiceTest {

  static Vertx vertx;
  static PgPool pool;
  static PostgreSQLContainer<?> pgContainer;
  static String pgHost, pgDb, pgUser, pgPass;
  static int pgPort;
  static Path tmpDir;

  static ConfigHolder holder;
  static ConfigRepository repo;

  @BeforeAll
  static void setup() throws Exception {
    tmpDir = Files.createTempDirectory("csp-import-test");
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
    pool.preparedQuery("INSERT INTO csp_profile (name, ca_id, network_id, max_cw_wait_ms) VALUES ('testprof',0,0,5000) ON CONFLICT DO NOTHING").execute().toCompletionStage().toCompletableFuture().join();
    holder = new ConfigHolder();
    repo = new ConfigRepository(pool);
    reloadHolder();
  }

  @AfterAll
  static void teardown() throws Exception {
    if(pool!=null) pool.close();
    if(vertx!=null) vertx.close();
    if(pgContainer!=null) pgContainer.stop();
    if(tmpDir!=null) {
      try { Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try{ Files.deleteIfExists(p);}catch(Exception ignored){}});} catch(Exception ignored){}
    }
  }
  private static boolean isReachable(String h,int p){ try(java.net.Socket s=new java.net.Socket()){ s.connect(new java.net.InetSocketAddress(h,p),2000); return true;}catch(Exception e){ return false;}}
  static void reloadHolder() throws Exception {
    ConfigSnapshot snap = repo.loadAll().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    holder.set(snap);
  }

  // Helpers to create service with mocked DNS and probe
  CccamImportService serviceWithMocks(DnsResolverService dns, SecretMaterializer sm, Future<ConnectorProbe.ProbeResult> probeResult, boolean probeSuccess) {
    RuntimeCore rc = new RuntimeCore(vertx, null);
    ImportJobRegistry reg = new ImportJobRegistry();
    DnsResolverService dnsToUse = dns != null ? dns : new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String hostOrIp) { return Future.succeededFuture(Set.of("1.2.3.4")); }
    };
    SecretMaterializer smToUse = sm != null ? sm : new SecretMaterializer(vertx, tmpDir);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dnsToUse, smToUse, rc, reg, 15000, 4) {
      @Override
      protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port, String username, String password, byte[] desKey14) {
        if (probeResult != null) return probeResult;
        if (probeSuccess) return Future.succeededFuture(new ConnectorProbe.ProbeResult(true, null, null));
        return Future.succeededFuture(new ConnectorProbe.ProbeResult(false, null, "mock probe failed"));
      }
    };
    return svc;
  }

  @Test @Order(1)
  void importSuccess() throws Exception {
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.1")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: myhost.example.com 12000 user1 pass1\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    // wait for completion
    long deadline = System.currentTimeMillis()+8000;
    while (System.currentTimeMillis()<deadline) {
      ImportJob j = reg.get(jobId);
      if (j!=null && j.status()==JobStatus.COMPLETED) break;
      Thread.sleep(100);
    }
    ImportJob completed = reg.get(jobId);
    assertNotNull(completed);
    assertEquals(JobStatus.COMPLETED, completed.status());
    assertEquals(1, completed.results().size());
    assertEquals(LineStatus.IMPORTED, completed.results().get(0).status());
    assertNotNull(completed.results().get(0).connectorName());
    // verify persisted
    reloadHolder();
    assertTrue(holder.get().connectorByName().containsKey(completed.results().get(0).connectorName()));
    // verify no password in reason
    assertTrue(completed.results().get(0).reason()==null || !completed.results().get(0).reason().contains("pass1"));
    // cleanup
    pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(completed.results().get(0).connectorName())).toCompletionStage().toCompletableFuture().join();
    reloadHolder();
    sm.cleanup(completed.results().get(0).connectorName()).toCompletionStage().toCompletableFuture().join();
  }

  @Test @Order(2)
  void dedupIntraFile() throws Exception {
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.2")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: hostdup.example.com 12000 user1 pass1\nC: hostdup.example.com 12000 user1 pass1\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    long deadline = System.currentTimeMillis()+8000;
    while (System.currentTimeMillis()<deadline) {
      ImportJob j = reg.get(jobId);
      if (j!=null && j.status()==JobStatus.COMPLETED) break;
      Thread.sleep(100);
    }
    ImportJob c = reg.get(jobId);
    assertEquals(2, c.results().size());
    long imported = c.results().stream().filter(r->r.status()==LineStatus.IMPORTED).count();
    long dup = c.results().stream().filter(r->r.status()==LineStatus.DUPLICATE).count();
    assertEquals(1, imported);
    assertEquals(1, dup);
    // cleanup imported connector
    for (var r : c.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(3)
  void dedupAgainstExisting() throws Exception {
    // first import one connector directly via service, then second import same line should be duplicate without probe
    ImportJobRegistry reg1 = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.3")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc1 = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg1, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId1 = java.util.UUID.randomUUID().toString();
    reg1.put(new ImportJob(jobId1, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: hostExist.example.com 12000 userX passX\n";
    svc1.processAsync(jobId1, cfg, "testprof", "tester");
    long deadline = System.currentTimeMillis()+8000;
    while (System.currentTimeMillis()<deadline) { ImportJob j=reg1.get(jobId1); if(j!=null&&j.status()==JobStatus.COMPLETED) break; Thread.sleep(100);}
    ImportJob c1 = reg1.get(jobId1);
    assertEquals(LineStatus.IMPORTED, c1.results().get(0).status());
    reloadHolder();
    String connectorName = c1.results().get(0).connectorName();
    // second import same line, should be duplicate without invoking probe (probe mock would fail if called, we make it distinct)
    ImportJobRegistry reg2 = new ImportJobRegistry();
    CccamImportService svc2 = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg2, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ fail("probe should not be called for duplicate"); return Future.succeededFuture(new ConnectorProbe.ProbeResult(false,null,"should not")); }
    };
    String jobId2 = java.util.UUID.randomUUID().toString();
    reg2.put(new ImportJob(jobId2, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    svc2.processAsync(jobId2, cfg, "testprof", "tester");
    deadline = System.currentTimeMillis()+8000;
    while (System.currentTimeMillis()<deadline) { ImportJob j=reg2.get(jobId2); if(j!=null&&j.status()==JobStatus.COMPLETED) break; Thread.sleep(100);}
    ImportJob c2 = reg2.get(jobId2);
    assertEquals(LineStatus.DUPLICATE, c2.results().get(0).status());
    // cleanup
    pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(connectorName)).toCompletionStage().toCompletableFuture().join();
    reloadHolder();
    sm.cleanup(connectorName).toCompletionStage().toCompletableFuture().join();
  }

  @Test @Order(4)
  void dnsFail() throws Exception {
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dnsFail = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.failedFuture("DNS fail mock"); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dnsFail, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ fail("probe should not be called on DNS fail"); return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: nonexist.example.com 12000 user1 pass1\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    long deadline = System.currentTimeMillis()+5000;
    while (System.currentTimeMillis()<deadline) { ImportJob j=reg.get(jobId); if(j!=null&&j.status()==JobStatus.COMPLETED) break; Thread.sleep(100);}
    ImportJob c = reg.get(jobId);
    assertEquals(LineStatus.DNS_RESOLVE_FAILED, c.results().get(0).status());
    // ensure no file created
    String slug = CccamImportService.normalizeSlugLocal("nonexist.example.com");
    Path maybe = tmpDir.resolve("csp-connector-imported-cccam-" + slug + "-12000.pwd");
    assertFalse(Files.exists(maybe), "no secret file should be created on DNS fail");
  }

  @Test @Order(5)
  void probeFailNoSecret() throws Exception {
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.99")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(false,null,"timeout after 15000ms")); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: probefail.example.com 12000 user1 pass1\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    long deadline = System.currentTimeMillis()+5000;
    while (System.currentTimeMillis()<deadline) { ImportJob j=reg.get(jobId); if(j!=null&&j.status()==JobStatus.COMPLETED) break; Thread.sleep(100);}
    ImportJob c = reg.get(jobId);
    assertEquals(LineStatus.PROBE_FAILED, c.results().get(0).status());
    assertTrue(c.results().get(0).reason().contains("timeout"));
    // ensure no file
    String slug = CccamImportService.normalizeSlugLocal("probefail.example.com");
    Path maybe = tmpDir.resolve("csp-connector-imported-cccam-" + slug + "-12000.pwd");
    assertFalse(Files.exists(maybe), "no orphan secret on probe fail");
  }

  @Test @Order(6)
  void digestUsesSortedIps() {
    Set<String> ips1 = Set.of("2.0.0.1", "1.0.0.1");
    Set<String> ips2 = Set.of("1.0.0.1", "2.0.0.1");
    String d1 = CccamImportService.computeDigest("cccam", ips1, 12000, "user", "pass");
    String d2 = CccamImportService.computeDigest("cccam", ips2, 12000, "user", "pass");
    assertEquals(d1, d2);
  }

  // ---- Helpers for missing CAID/provider tests ----

  private void setSnapshotForMissingTests(ProfileConfig profile, List<ProfileProviderIdentConfig> idents) throws Exception {
    ConfigSnapshot base = holder.get();
    // keep existing profiles but replace with supplied
    List<ProfileConfig> profiles = profile != null ? List.of(profile) : List.of();
    ConfigSnapshot snap = new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        profiles, base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(), base.plugins(), base.sidCacheLinks(),
        base.extendedNewcamd(), base.connectorCaidProfiles(),
        idents != null ? idents : List.of(), System.currentTimeMillis());
    holder.set(snap);
  }

  private ImportJob waitFor(JobStatus expected, ImportJobRegistry reg, String jobId) throws Exception {
    long deadline = System.currentTimeMillis() + 8000;
    while (System.currentTimeMillis() < deadline) {
      ImportJob j = reg.get(jobId);
      if (j != null && j.status() == expected) return j;
      Thread.sleep(100);
    }
    return reg.get(jobId);
  }

  @Test @Order(7)
  void missingCaidUnknown() throws Exception {
    ProfileConfig prof = new ProfileConfig("testprof", true, false, false, 0x0500, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    ProfileProviderIdentConfig ident = new ProfileProviderIdentConfig(1L, "testprof", 0x030B00, null, 1L);
    setSnapshotForMissingTests(prof, List.of(ident));
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.10.0.1")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: host-missing-caid.example.com 12000 user1 pass1 yes { 0100:000000 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    assertNotNull(completed);
    assertEquals(1, completed.results().size());
    assertEquals(LineStatus.IMPORTED, completed.results().get(0).status());
    assertEquals(1, completed.missingCaidProviders().size());
    var missing = completed.missingCaidProviders().get(0);
    assertEquals(0x0100, missing.caId());
    assertEquals(0x000000, missing.providerIdent());
    assertFalse(missing.caIdKnown());
    // cleanup
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(8)
  void missingProviderForKnownCaid() throws Exception {
    ProfileConfig prof = new ProfileConfig("testprof", true, false, false, 0x0100, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    // provider 000000 known, but not 111111
    ProfileProviderIdentConfig ident = new ProfileProviderIdentConfig(1L, "testprof", 0x000000, null, 1L);
    setSnapshotForMissingTests(prof, List.of(ident));
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.10.0.2")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: host-prov-missing.example.com 12000 user1 pass1 yes { 0100:111111 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    assertEquals(1, completed.missingCaidProviders().size());
    var m = completed.missingCaidProviders().get(0);
    assertEquals(0x0100, m.caId());
    assertEquals(0x111111, m.providerIdent());
    assertTrue(m.caIdKnown());
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(9)
  void noMissingWhenAlreadyConfigured() throws Exception {
    ProfileConfig prof = new ProfileConfig("testprof", true, false, false, 0x0100, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    ProfileProviderIdentConfig ident = new ProfileProviderIdentConfig(1L, "testprof", 0x000000, null, 1L);
    setSnapshotForMissingTests(prof, List.of(ident));
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.10.0.3")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: host-configured.example.com 12000 user1 pass1 yes { 0100:000000 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    assertTrue(completed.missingCaidProviders().isEmpty());
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(10)
  void missingDedupAndSorted() throws Exception {
    ProfileConfig profA = new ProfileConfig("profA", true, false, false, 0x0100, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    ProfileConfig profB = new ProfileConfig("profB", true, false, false, 0x0500, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    // profA has provider 000000, profB has no providers -> provider missing for 0500
    ProfileProviderIdentConfig ident = new ProfileProviderIdentConfig(1L, "profA", 0x000000, null, 1L);
    ConfigSnapshot base = holder.get();
    ConfigSnapshot snap = new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        List.of(profA, profB), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(), base.plugins(), base.sidCacheLinks(),
        base.extendedNewcamd(), base.connectorCaidProfiles(),
        List.of(ident), System.currentTimeMillis());
    holder.set(snap);
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.10.0.4")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    // Two lines, duplicate decls across lines, unsorted order
    String cfg = "C: host1.example.com 12000 user1 pass1 yes { 0500:030B00, 0100:111111 }\n"
        + "C: host2.example.com 12000 user2 pass2 yes { 0100:111111, 0600:000000 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    // Expected missing: 0100:111111 (caIdKnown true), 0500:030B00 (caIdKnown true? profB caId 0500 but no provider -> missing true), 0600:000000 (caIdKnown false)
    // Sorted by caId then provider: 0100:111111, 0500:030B00, 0600:000000
    assertEquals(3, completed.missingCaidProviders().size());
    assertEquals(0x0100, completed.missingCaidProviders().get(0).caId());
    assertEquals(0x0500, completed.missingCaidProviders().get(1).caId());
    assertEquals(0x0600, completed.missingCaidProviders().get(2).caId());
    assertTrue(completed.missingCaidProviders().get(0).caIdKnown());
    assertFalse(completed.missingCaidProviders().get(2).caIdKnown());
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(11)
  void missingOnlyImportedContributes() throws Exception {
    // Snapshot with caId 0100 known provider 000000
    ProfileConfig prof = new ProfileConfig("testprof", true, false, false, 0x0100, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
    ProfileProviderIdentConfig ident = new ProfileProviderIdentConfig(1L, "testprof", 0x000000, null, 1L);
    setSnapshotForMissingTests(prof, List.of(ident));
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) {
        if (h.contains("dnserror")) return Future.failedFuture("DNS fail");
        return Future.succeededFuture(Set.of("10.10.0.5"));
      }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, holder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){
        if (h.contains("probefail")) return Future.succeededFuture(new ConnectorProbe.ProbeResult(false,null,"probe fail"));
        return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null));
      }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    // 4 lines: 1 IMPORTED with 0200:000000 (unknown caid), 1 DUPLICATE (same as 1), 1 PROBE_FAILED with 0300:000000, 1 DNS_FAILED with 0400:000000, 1 parse error line (malformed)
    // Use duplicate by repeating first line exactly (same host/port/user/pass)
    String cfg = "C: host-ok.example.com 12000 user1 pass1 yes { 0200:000000 }\n"
        + "C: host-ok.example.com 12000 user1 pass1 yes { 0200:000000 }\n"
        + "C: probefail.example.com 12000 user2 pass2 yes { 0300:000000 }\n"
        + "C: dnserror.example.com 12000 user3 pass3 yes { 0400:000000 }\n"
        + "C: host 12000 u p yes { ZZ:000000 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    // Only first line's decl should be counted -> 0200 unknown
    assertEquals(1, completed.missingCaidProviders().size());
    assertEquals(0x0200, completed.missingCaidProviders().get(0).caId());
    // Ensure no 0300/0400 contributed
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }

  @Test @Order(12)
  void missingReturnsEmptyWhenSnapshotUnavailable() throws Exception {
    // holder with no init -> simulate unavailable
    ConfigHolder emptyHolder = new ConfigHolder(); // not set => not initialized
    ImportJobRegistry reg = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.10.0.9")); }
    };
    RuntimeCore rc = new RuntimeCore(vertx, null);
    CccamImportService svc = new CccamImportService(vertx, emptyHolder, repo, dns, sm, rc, reg, 15000, 4) {
      @Override protected Future<ConnectorProbe.ProbeResult> doProbe(String p,String h,int port,String u,String pw,byte[] k){ return Future.succeededFuture(new ConnectorProbe.ProbeResult(true,null,null)); }
    };
    String jobId = java.util.UUID.randomUUID().toString();
    reg.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    String cfg = "C: host-unavail.example.com 12000 user1 pass1 yes { 0100:000000 }\n";
    svc.processAsync(jobId, cfg, "testprof", "tester");
    ImportJob completed = waitFor(JobStatus.COMPLETED, reg, jobId);
    // conservative: no missing suggestions when snapshot unavailable
    assertTrue(completed.missingCaidProviders().isEmpty());
    for (var r : completed.results()) if (r.connectorName()!=null) {
      pool.preparedQuery("DELETE FROM csp_connector WHERE name=$1").execute(Tuple.of(r.connectorName())).toCompletionStage().toCompletableFuture().join();
      sm.cleanup(r.connectorName()).toCompletionStage().toCompletableFuture().join();
    }
    reloadHolder();
  }
}
