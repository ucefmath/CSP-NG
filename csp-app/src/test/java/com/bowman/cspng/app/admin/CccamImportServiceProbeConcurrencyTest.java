package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.admin.ImportModels.*;
import com.bowman.cspng.app.runtime.DnsResolverService;
import com.bowman.cspng.app.runtime.RuntimeCore;
import com.bowman.cspng.app.runtime.SecretMaterializer;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.connectors.ConnectorProbe;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de CONCURRENCE RÉELLE des probes d'import (Plan P2 §6) :
 * <ul>
 *   <li>Overlap effectif &gt; 1 : avec maxConcurrentProbes=2, deux probes
 *       tournent SIMULTANÉMENTENT (maxObserved == 2, complétion différée).</li>
 *   <li>Plafond garanti : jamais plus de N probes simultanés (cap 2 et cap 4).</li>
 *   <li>Cap 1 : strictement séquentiel (maxObserved == 1).</li>
 *   <li>Noms uniques sous concurrence : même slug de base → suffixes -2, -3, -4
 *       distincts (sets concurrents de generateUniqueName).</li>
 *   <li>Résultats stables : triés par lineNumber quel que soit l'ordre de complétion.</li>
 * </ul>
 *
 * <p>Le mock doProbe ne bloque JAMAIS de thread : il rend une future complétée
 * par un timer Vert.x (150 ms) — les lanes démarrent et l'overlap est mesuré
 * par AtomicInteger max/active.
 */
class CccamImportServiceProbeConcurrencyTest {

  Vertx vertx;
  Path tmpDir;
  ConfigHolder holder;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    tmpDir = Files.createTempDirectory("csp-import-conc-test");
    holder = new ConfigHolder();
    holder.set(ConfigSnapshot.empty());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) vertx.close();
    if (tmpDir != null) {
      try { Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
          .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
    }
  }

  /** Tracker d'overlap des probes : actifs simultanés, max observé. */
  static class OverlapTracker {
    final AtomicInteger active = new AtomicInteger();
    final AtomicInteger maxObserved = new AtomicInteger();
    final ConcurrentLinkedQueue<Promise<ConnectorProbe.ProbeResult>> waiting =
        new ConcurrentLinkedQueue<>();

    Future<ConnectorProbe.ProbeResult> begin(Vertx vertx, boolean success, long completeAfterMs) {
      int now = active.incrementAndGet();
      maxObserved.accumulateAndGet(now, Math::max);
      Promise<ConnectorProbe.ProbeResult> p = Promise.promise();
      waiting.add(p);
          vertx.setTimer(completeAfterMs, v -> {
            active.decrementAndGet();
            if (success) p.complete(new ConnectorProbe.ProbeResult(true, null, null));
            else p.complete(new ConnectorProbe.ProbeResult(false, null, "mock probe failed"));
          });
      return p.future();
    }
  }

  static class CaptureRepo extends ConfigRepository {
    final ConcurrentLinkedQueue<ConnectorConfig> upserted = new ConcurrentLinkedQueue<>();
    CaptureRepo() { super((Pool) null); }
    @Override public Future<java.util.Set<String>> findExistingDedupDigests(java.util.Set<String> candidates) {
      return Future.succeededFuture(Set.of());
    }
    @Override public Future<Void> setDedupDigest(String connectorName, String digest, String actor) {
      return Future.succeededFuture();
    }
    @Override public Future<Long> upsertConnector(ConnectorConfig conn, String actor) {
      upserted.add(conn);
      return Future.succeededFuture(2L);
    }
    @Override public Future<Void> insertImportAudit(String actor, String targetProfile,
                                                    int total, int imported, int duplicate,
                                                    int dnsFailed, int probeFailed, int persistFailed, int parseError) {
      return Future.succeededFuture();
    }
  }

  private CccamImportService service(ImportJobRegistry registry, CaptureRepo repo,
                                     OverlapTracker tracker, int maxConcurrent, long completeAfterMs) {
    RuntimeCore rc = new RuntimeCore(vertx, null);
    SecretMaterializer sm = new SecretMaterializer(vertx, tmpDir);
    DnsResolverService dns = new DnsResolverService(vertx, 60, true) {
      @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.1")); }
    };
    return new CccamImportService(vertx, holder, repo, dns, sm, rc, null, registry, 15000, maxConcurrent) {
      @Override
      protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port,
                                                            String username, String password, byte[] desKey14) {
        return tracker.begin(vertx, true, completeAfterMs);
      }
    };
  }

  private ImportJob runImport(CccamImportService svc, ImportJobRegistry registry, String cfg) throws Exception {
    String jobId = java.util.UUID.randomUUID().toString();
    registry.put(new ImportJob(jobId, "tester", "testprof", JobStatus.PENDING, java.time.Instant.now(), null, List.of(), null));
    svc.processAsync(jobId, cfg, "testprof", "tester");
    long deadline = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < deadline) {
      ImportJob j = registry.get(jobId);
      if (j != null && j.status() == JobStatus.COMPLETED) return j;
      Thread.sleep(25);
    }
    fail("import job did not complete in time");
    return null;
  }

  private static String lines(int n, String host, int basePort) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append("C: ").append(host).append(' ').append(basePort + i)
        .append(" user").append(i).append(" pass").append(i).append('\n');
    }
    return sb.toString();
  }

  @Test
  void overlapEffectifAvecCapDeux() throws Exception {
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    CccamImportService svc = service(registry, repo, tracker, 2, 150);
    ImportJob job = runImport(svc, registry, lines(6, "overlap.lan", 12000));
    assertEquals(6, job.summary().imported(), "all 6 lines IMPORTED, summary=" + job.summary());
    // OVERLAP EFFECTIF : deux probes ont coexisté (complétion différée 150 ms)
    assertEquals(2, tracker.maxObserved.get(),
        "with maxConcurrentProbes=2, exactly 2 probes must overlap (got " + tracker.maxObserved.get() + ")");
    assertEquals(0, tracker.active.get(), "all probes completed/cleaned up");
    assertEquals(6, repo.upserted.size());
  }

  @Test
  void plafondRespecteAvecCapQuatre() throws Exception {
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    CccamImportService svc = service(registry, repo, tracker, 4, 150);
    ImportJob job = runImport(svc, registry, lines(8, "cap4.lan", 13000));
    assertEquals(8, job.summary().imported());
    assertTrue(tracker.maxObserved.get() >= 2,
        "overlap must occur with 4 lanes / 8 lines (got max " + tracker.maxObserved.get() + ")");
    assertTrue(tracker.maxObserved.get() <= 4,
        "plafond VIOLÉ : maxConcurrentProbes=4 mais " + tracker.maxObserved.get() + " probes simultanés");
  }

  @Test
  void capUnStrictementSequentiel() throws Exception {
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    CccamImportService svc = service(registry, repo, tracker, 1, 60);
    ImportJob job = runImport(svc, registry, lines(4, "cap1.lan", 14000));
    assertEquals(4, job.summary().imported());
    assertEquals(1, tracker.maxObserved.get(),
        "cap=1 : strictement séquentiel, jamais 2 probes simultanés (got " + tracker.maxObserved.get() + ")");
  }

  @Test
  void nomsUniquesSousConcurrenceMemeSlug() throws Exception {
    // 4 lignes même host:port (slug de base IDENTIQUE) mais users différents
    // (digests distincts, pas de dup) → generateUniqueName doit produire 4 noms
    // uniques SOUS CONCURRENCE (suffixes -2, -3, -4).
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    CccamImportService svc = service(registry, repo, tracker, 4, 100);
    String cfg = "C: coll.lan 15000 userA passA\n"
        + "C: coll.lan 15000 userB passB\n"
        + "C: coll.lan 15000 userC passC\n"
        + "C: coll.lan 15000 userD passD\n";
    ImportJob job = runImport(svc, registry, cfg);
    assertEquals(4, job.summary().imported(), "4 distinct lines must all import, summary=" + job.summary());
    assertEquals(0, job.summary().duplicate());
    List<String> names = job.results().stream()
        .map(LineResult::connectorName)
        .collect(Collectors.toList());
    assertEquals(4, names.stream().distinct().count(), "generated connector names must be unique under concurrency: " + names);
    // Le slug de base est identique : les noms partagent le préfixe
    assertTrue(names.get(0).startsWith("imported-cccam-coll-lan-15000"), "base slug: " + names.get(0));
  }

  @Test
  void collisionForceeDeuxLanesMemeSlugReservationAtomique() throws Exception {
    // COLLISION FORCÉE : 2 lanes exactement, même slug de base, complétions
    // simultanées (150 ms) — la réservation atomique (generatedNames.add) doit
    // garantir que les DEUX lanes obtiennent des noms DISTINCTS même si elles
    // évaluent generateUniqueName dans la même fenêtre. Sans réservation
    // atomique (contains→return→add), ce test peut produire un doublon.
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    CccamImportService svc = service(registry, repo, tracker, 2, 150);
    String cfg = "C: coll2.lan 15500 userX passX\n"
        + "C: coll2.lan 15500 userY passY\n";
    ImportJob job = runImport(svc, registry, cfg);
    assertEquals(2, job.summary().imported());
    List<String> names = job.results().stream()
        .map(LineResult::connectorName)
        .collect(Collectors.toList());
    assertEquals(2, names.stream().distinct().count(),
        "réservation atomique : 2 lanes même slug → 2 noms distincts, got " + names);
    // Exactement un porteur du slug de base, l'autre suffixé
    long baseCount = names.stream().filter(n -> n.equals("imported-cccam-coll2-lan-15500")).count();
    long suffixCount = names.stream().filter(n -> n.equals("imported-cccam-coll2-lan-15500-2")).count();
    assertEquals(1, baseCount, "exactement un connecteur au slug de base");
    assertEquals(1, suffixCount, "exactement un connecteur suffixé -2");
  }

  @Test
  void resultatsTriesParLigneQuelQueSoitOrdreCompletion() throws Exception {
    ImportJobRegistry registry = new ImportJobRegistry();
    CaptureRepo repo = new CaptureRepo();
    OverlapTracker tracker = new OverlapTracker();
    // Complétion à durées DIFFÉRENTES (30/90/60/120 ms) → ordre de complétion ≠ ordre des lignes
    CccamImportService svc = new CccamImportService(vertx, holder, repo,
        new DnsResolverService(vertx, 60, true) {
          @Override public Future<Set<String>> resolve(String h) { return Future.succeededFuture(Set.of("10.0.0.1")); }
        }, new SecretMaterializer(vertx, tmpDir), new RuntimeCore(vertx, null), null,
        registry, 15000, 3) {
      private final java.util.concurrent.atomic.AtomicInteger probeCounter = new java.util.concurrent.atomic.AtomicInteger();
      @Override
      protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port,
                                                            String username, String password, byte[] desKey14) {
        int n = probeCounter.getAndIncrement();
        long delay = new long[]{30, 90, 60, 120, 45, 75}[n % 6];
        Promise<ConnectorProbe.ProbeResult> p = Promise.promise();
        vertx.setTimer(delay, v -> p.complete(new ConnectorProbe.ProbeResult(true, null, null)));
        return p.future();
      }
    };
    ImportJob job = runImport(svc, registry, lines(6, "order.lan", 16000));
    assertEquals(6, job.summary().imported());
    // Résultats STABLES : triés par lineNumber croissant
    List<Integer> lineNums = job.results().stream().map(LineResult::lineNumber).collect(Collectors.toList());
    assertEquals(lineNums.stream().sorted().collect(Collectors.toList()), lineNums,
        "results must be sorted by lineNumber");
  }
}
