package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.admin.ImportModels.*;
import com.bowman.cspng.app.runtime.DnsResolverService;
import com.bowman.cspng.app.runtime.RuntimeCore;
import com.bowman.cspng.app.runtime.RuntimeSecretResolver;
import com.bowman.cspng.app.runtime.SecretMaterializer;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.config.NetworkProxyConfig;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.ConnectorProbe;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests import cccam.cfg avec proxy SOCKS5 optionnel (Plan P2 §6) :
 * <ul>
 *   <li>Digest v2 ≠ digest v1 (dédup direct/proxy différente) — hostname normalisé.</li>
 *   <li>Avec proxy : AUCUNE résolution DNS locale (espion DNS ne doit jamais être appelé).</li>
 *   <li>Fail-closed : proxy absent / disabled / secret KO → PROBE_FAILED, jamais de direct.</li>
 *   <li>Persist : le connecteur importé porte proxyName.</li>
 *   <li>Sans proxy : comportement legacy (digest v1 avec IPs résolues) intact.</li>
 * </ul>
 *
 * <p>Sans base PG — repository mocké, secrets test seulement.
 */
class CccamImportServiceProxyTest {

  Vertx vertx;
  Path tmpDir;
  ConfigHolder holder;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    tmpDir = Files.createTempDirectory("csp-import-proxy-test");
    holder = new ConfigHolder();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) vertx.close();
    if (tmpDir != null) {
      try { Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }
  }

  // -- helpers --

  private static NetworkProxyConfig proxy(String name, boolean enabled, String username, String passwordRef) {
    return new NetworkProxyConfig(name, enabled, "socks5", "127.0.0.1", 1080,
        username, passwordRef, null, 1L);
  }

  private void setSnapshot(NetworkProxyConfig... proxies) {
    var base = ConfigSnapshot.empty();
    holder.set(new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), base.connectors(), base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        List.of(proxies), System.currentTimeMillis()));
  }

  /** DNS spy : échoue le test si appelé (chemin proxy = zéro DNS locale). */
  static class DnsSpy extends DnsResolverService {
    final AtomicBoolean called = new AtomicBoolean(false);
    DnsSpy(Vertx vertx) { super(vertx, 60, true); }
    @Override public Future<Set<String>> resolve(String hostOrIp) {
      called.set(true);
      return Future.succeededFuture(Set.of("10.0.0.99"));
    }
  }

  static class CaptureRepo extends ConfigRepository {
    final ConcurrentLinkedQueue<ConnectorConfig> upsertedConnectors = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> digests = new ConcurrentLinkedQueue<>();
    CaptureRepo() { super((Pool) null); }
    @Override public Future<java.util.Set<String>> findExistingDedupDigests(java.util.Set<String> candidates) {
      return Future.succeededFuture(Set.of());
    }
    @Override public Future<Void> setDedupDigest(String connectorName, String digest, String actor) {
      digests.add(digest);
      return Future.succeededFuture();
    }
    @Override public Future<Long> upsertConnector(ConnectorConfig conn, String actor) {
      upsertedConnectors.add(conn);
      return Future.succeededFuture(2L);
    }
    @Override public Future<Void> insertImportAudit(String actor, String targetProfile,
                                                    int total, int imported, int duplicate,
                                                    int dnsFailed, int probeFailed, int persistFailed, int parseError) {
      return Future.succeededFuture();
    }
  }

  /** Service avec probe mocké — capture les proxyOptions reçues. */
  private CccamImportService service(CaptureRepo repo, DnsSpy dns, RuntimeSecretResolver resolver,
                                     boolean probeSuccess,
                                     AtomicReference<AbstractConnector.NetworkProxyOptions> capturedProxyOpts) {
    RuntimeCore rc = new RuntimeCore(vertx, null);
    ImportJobRegistry registry = new ImportJobRegistry();
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    return new CccamImportService(vertx, holder, repo, dns, sm, rc, resolver, registry, 15000, 4) {
      @Override
      protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port,
                                                            String username, String password, byte[] desKey14,
                                                            AbstractConnector.NetworkProxyOptions proxyOpts) {
        if (capturedProxyOpts != null) capturedProxyOpts.set(proxyOpts);
        if (probeSuccess) return Future.succeededFuture(new ConnectorProbe.ProbeResult(true, null, null));
        return Future.succeededFuture(new ConnectorProbe.ProbeResult(false, null, "mock probe failed"));
      }
    };
  }

  private ImportJob runImport(CccamImportService svc, ImportJobRegistry registry, String cfg, String proxyName) throws Exception {
    String jobId = java.util.UUID.randomUUID().toString();
    registry.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    if (proxyName == null) {
      svc.processAsync(jobId, cfg, "testprof", "tester");
    } else {
      svc.processAsync(jobId, cfg, "testprof", "tester", proxyName);
    }
    long deadline = System.currentTimeMillis() + 10000;
    while (System.currentTimeMillis() < deadline) {
      ImportJob j = registry.get(jobId);
      if (j != null && j.status() == JobStatus.COMPLETED) return j;
      Thread.sleep(50);
    }
    fail("import job did not complete in time");
    return null;
  }

  private ImportJobRegistry registryOf(CccamImportService svc) {
    // Le registry est injecté via constructeur test — récupération par champ
    try {
      var f = CccamImportService.class.getDeclaredField("registry");
      f.setAccessible(true);
      return (ImportJobRegistry) f.get(svc);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ============================================================
  // Digest v2 vs v1 — dédup différente direct/proxy
  // ============================================================

  @Test
  void digestV2DiffersFromV1AndNormalizesHostname() {
    // v1 : basé sur les IPs résolues
    String v1 = CccamImportService.computeDigest("cccam", Set.of("10.0.0.1"), 12000, "user", "pass");
    // v2 : basé sur proxy + hostname normalisé
    String v2 = CccamImportService.computeDigestV2("cccam", "P", "upstream.lan", 12000, "user", "pass");
    assertNotEquals(v1, v2, "direct (v1) and via-proxy (v2) digests MUST differ");

    // v2 : hostname normalisé (case, crochets IPv6) — la normalisation est à l'appelant
    String v2a = CccamImportService.computeDigestV2("cccam", "P",
        CccamImportService.normalizeHostname("Upstream.LAN"), 12000, "user", "pass");
    assertEquals(v2, v2a, "hostname case must be normalized");
    String v2brackets = CccamImportService.computeDigestV2("newcamd", "P",
        CccamImportService.normalizeHostname("[2001:DB8::1]"), 15000, "u", "p");
    String v2raw = CccamImportService.computeDigestV2("newcamd", "P",
        CccamImportService.normalizeHostname("2001:db8::1"), 15000, "u", "p");
    assertEquals(v2raw, v2brackets, "IPv6 brackets must be normalized");
    // proxy différent → digest différent
    String v2other = CccamImportService.computeDigestV2("cccam", "Q", "upstream.lan", 12000, "user", "pass");
    assertNotEquals(v2, v2other, "different proxy → different digest");
    // secret ne fuit pas dans le digest (hash hex)
    assertTrue(v2.matches("[0-9a-f]{64}"), "digest must be SHA-256 hex");
    assertFalse(v2.contains("pass"), "digest must not contain secret material");
  }

  @Test
  void normalizeHostnameTest() {
    assertEquals("host.lan", CccamImportService.normalizeHostname("  Host.LAN  "));
    assertEquals("2001:db8::1", CccamImportService.normalizeHostname("[2001:DB8::1]"));
    assertEquals("", CccamImportService.normalizeHostname(null));
  }

  // ============================================================
  // Avec proxy : zéro DNS locale + probe reçoit proxyOptions + persist proxyName
  // ============================================================

  @Test
  void importWithProxySkipsDnsAndPersistsProxyName() throws Exception {
    setSnapshot(proxy("P", true, null, null));
    CaptureRepo repo = new CaptureRepo();
    DnsSpy dns = new DnsSpy(vertx);
    AtomicReference<AbstractConnector.NetworkProxyOptions> captured = new AtomicReference<>();
    CccamImportService svc = service(repo, dns, null, true, captured);
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", "P");
    assertEquals(JobStatus.COMPLETED, job.status());
    assertEquals(1, job.summary().imported(), "line should be IMPORTED, results=" + job.results());
    assertFalse(dns.called.get(), "NO local DNS resolution allowed when proxy is set");
    assertNotNull(captured.get(), "probe must receive proxy options");
    assertEquals("127.0.0.1", captured.get().proxyHost);
    assertEquals(1080, captured.get().proxyPort);
    assertEquals(1, repo.upsertedConnectors.size());
    assertEquals("P", repo.upsertedConnectors.peek().proxyName(),
        "persisted connector must carry proxyName");
  }

  @Test
  void importWithoutProxyResolvesDnsLegacy() throws Exception {
    setSnapshot(); // aucun proxy
    CaptureRepo repo = new CaptureRepo();
    DnsSpy dns = new DnsSpy(vertx);
    CccamImportService svc = service(repo, dns, null, true, null);
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", null);
    assertEquals(1, job.summary().imported());
    assertTrue(dns.called.get(), "legacy direct import MUST resolve DNS");
    assertEquals(1, repo.upsertedConnectors.size());
    assertNull(repo.upsertedConnectors.peek().proxyName(), "legacy import has no proxyName");
  }

  // ============================================================
  // Fail-closed : proxy absent / disabled / secret KO
  // ============================================================

  @Test
  void importWithUnknownProxyFailsClosed() throws Exception {
    setSnapshot(proxy("P", true, null, null));
    CaptureRepo repo = new CaptureRepo();
    DnsSpy dns = new DnsSpy(vertx);
    CccamImportService svc = service(repo, dns, null, true, null);
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", "GHOST");
    assertEquals(JobStatus.COMPLETED, job.status());
    assertEquals(0, job.summary().imported());
    assertEquals(1, job.summary().probeFailed(), "all lines must be PROBE_FAILED (fail-closed), summary=" + job.summary());
    assertTrue(job.results().get(0).reason().contains("GHOST"), "reason: " + job.results().get(0).reason());
    assertFalse(dns.called.get(), "no DNS on fail-closed proxy path");
    assertTrue(repo.upsertedConnectors.isEmpty(), "nothing persisted on fail-closed");
  }

  @Test
  void importWithDisabledProxyFailsClosed() throws Exception {
    setSnapshot(proxy("P", false, null, null)); // kill switch
    CaptureRepo repo = new CaptureRepo();
    DnsSpy dns = new DnsSpy(vertx);
    CccamImportService svc = service(repo, dns, null, true, null);
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", "P");
    assertEquals(0, job.summary().imported());
    assertEquals(1, job.summary().probeFailed());
    assertTrue(job.results().get(0).reason().contains("disabled"), "reason: " + job.results().get(0).reason());
    assertTrue(repo.upsertedConnectors.isEmpty());
  }

  @Test
  void importWithUnresolvableProxySecretFailsClosed() throws Exception {
    setSnapshot(proxy("P", true, "user", "env:MISSING_PROXY_VAR_XYZ"));
    CaptureRepo repo = new CaptureRepo();
    DnsSpy dns = new DnsSpy(vertx);
    // Resolver réel sans la variable → résolution échoue
    RuntimeSecretResolver resolver = new RuntimeSecretResolver(vertx, name -> null, List.of(Path.of("/tmp")));
    CccamImportService svc = service(repo, dns, resolver, true, null);
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", "P");
    assertEquals(0, job.summary().imported());
    assertEquals(1, job.summary().probeFailed());
    assertTrue(job.results().get(0).reason().contains("secret"),
        "reason must mention secret fail-closed, got: " + job.results().get(0).reason());
    assertTrue(repo.upsertedConnectors.isEmpty());
  }

  // ============================================================
  // Dedup : même ligne via proxy ≠ déjà importée en direct (digest différent)
  // ============================================================

  @Test
  void dedupViaProxyDiffersFromDirect() throws Exception {
    setSnapshot(proxy("P", true, null, null));
    CaptureRepo repo = new CaptureRepo() {
      @Override public Future<java.util.Set<String>> findExistingDedupDigests(java.util.Set<String> candidates) {
        // Simule : la ligne a déjà été importée EN DIRECT (digest v1 présent en base)
        String directDigest = CccamImportService.computeDigest("cccam", Set.of("10.0.0.99"), 12000, "user1", "pass1");
        return Future.succeededFuture(Set.of(directDigest));
      }
    };
    DnsSpy dns = new DnsSpy(vertx);
    CccamImportService svc = service(repo, dns, null, true, null);
    // Import via proxy : digest v2 (proxy + hostname) ≠ v1 (IPs direct) → PAS duplicate
    ImportJob job = runImport(svc, registryOf(svc),
        "C: upstream.lan 12000 user1 pass1\n", "P");
    assertEquals(1, job.summary().imported(), "via-proxy import must NOT be a duplicate of the direct import");
    assertEquals(0, job.summary().duplicate());
  }
}
