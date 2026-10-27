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
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileProviderIdentConfig;
import com.bowman.cspng.config.importer.CccamCfgParser;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.ConnectorProbe;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class CccamImportService {

  private static final Logger LOG = Logger.getLogger(CccamImportService.class.getName());

  @Inject Vertx vertx;
  @Inject ConfigHolder configHolder;
  @Inject ConfigRepository configRepository;
  @Inject DnsResolverService dnsResolverService;
  @Inject SecretMaterializer secretMaterializer;
  @Inject RuntimeCore runtimeCore;
  @Inject RuntimeSecretResolver runtimeSecretResolver;
  @Inject ImportJobRegistry registry;

  @ConfigProperty(name = "csp.import.probe-timeout-ms", defaultValue = "15000")
  long probeTimeoutMs;

  @ConfigProperty(name = "csp.import.max-concurrent-probes", defaultValue = "4")
  int maxConcurrentProbes;

  // No-arg for CDI proxy
  public CccamImportService() {}

  // For tests
  public CccamImportService(Vertx vertx, ConfigHolder configHolder, ConfigRepository configRepository,
                            DnsResolverService dnsResolver, SecretMaterializer secretMaterializer,
                            RuntimeCore runtimeCore, ImportJobRegistry registry,
                            long probeTimeoutMs, int maxConcurrentProbes) {
    this(vertx, configHolder, configRepository, dnsResolver, secretMaterializer, runtimeCore,
        null, registry, probeTimeoutMs, maxConcurrentProbes);
  }

  // For tests — with secret resolver (proxy import)
  public CccamImportService(Vertx vertx, ConfigHolder configHolder, ConfigRepository configRepository,
                            DnsResolverService dnsResolver, SecretMaterializer secretMaterializer,
                            RuntimeCore runtimeCore, RuntimeSecretResolver runtimeSecretResolver,
                            ImportJobRegistry registry,
                            long probeTimeoutMs, int maxConcurrentProbes) {
    this.vertx = vertx;
    this.configHolder = configHolder;
    this.configRepository = configRepository;
    this.dnsResolverService = dnsResolver;
    this.secretMaterializer = secretMaterializer;
    this.runtimeCore = runtimeCore;
    this.runtimeSecretResolver = runtimeSecretResolver;
    this.registry = registry;
    this.probeTimeoutMs = probeTimeoutMs;
    this.maxConcurrentProbes = maxConcurrentProbes;
  }

  /** Legacy overload — import direct sans proxy (digest v1 inchangé). */
  public void processAsync(String jobId, String content, String targetProfile, String actor) {
    processAsync(jobId, content, targetProfile, actor, null);
  }

  /**
   * Import cccam.cfg avec proxy SOCKS5 optionnel.
   *
   * <p>Avec proxy : AUCUNE résolution DNS locale (déléguée au proxy ATYP domain),
   * digest v2 (protocole+proxyName+hostname normalisé+port+user+secret, SHA-256 —
   * le digest n'expose jamais le secret), probe et persist avec proxyName.
   * Sans proxy : comportement legacy inchangé (DNS locale + digest v1).
   *
   * <p>Fail-closed : proxy absent/disabled/secret KO → lignes en erreur, JAMAIS de direct.
   */
  public void processAsync(String jobId, String content, String targetProfile, String actor, String proxyName) {
    ImportJob pending = registry.get(jobId);
    if (pending == null) {
      LOG.warning("processAsync job not found " + jobId);
      return;
    }
    // Mark RUNNING
    ImportJob running = new ImportJob(jobId, actor, targetProfile, JobStatus.RUNNING,
        pending.startedAt(), null, List.of(), null, List.of());
    registry.update(running);

    doProcess(jobId, content, targetProfile, actor, proxyName)
        .onComplete(ar -> {
          if (ar.failed()) {
            LOG.log(Level.WARNING, "Import job " + jobId + " failed: " + ar.cause().getMessage(), ar.cause());
            // Ensure job marked COMPLETED even on unexpected failure
            ImportJob cur = registry.get(jobId);
            if (cur != null && cur.status() != JobStatus.COMPLETED) {
              ImportJob failed = new ImportJob(jobId, actor, targetProfile, JobStatus.COMPLETED,
                  cur.startedAt(), Instant.now(),
                  cur.results() != null ? cur.results() : List.of(),
                  cur.summary() != null ? cur.summary() : new ImportSummary(0,0,0,0,0,0,0),
                  cur.missingCaidProviders() != null ? cur.missingCaidProviders() : List.of());
              registry.update(failed);
            }
          }
        });
  }

  private Future<Void> doProcess(String jobId, String content, String targetProfile, String actor, String proxyName) {
    CccamCfgParser.ParseResult parseResult = CccamCfgParser.parse(content);
    List<CccamCfgParser.ParsedLine> parsed = parseResult.parsedLines();
    List<CccamCfgParser.ParseError> errors = parseResult.errors();

    // Build initial LineResults for parse errors.
    // Thread-safe : les lanes de probes concurrentes complètent sur les event-loops
    // Vert.x (multi-threads) et ajoutent ici leurs LineResult.
    List<LineResult> allResults = java.util.Collections.synchronizedList(new ArrayList<>());
    for (CccamCfgParser.ParseError e : errors) {
      allResults.add(new LineResult(e.lineNumber(), "UNKNOWN", null, 0, null,
          LineStatus.PARSE_ERROR, sanitizeReason(e.reason()), null));
    }

    if (parsed.isEmpty()) {
      ImportSummary summary = buildSummary(allResults);
      // No parsed lines => no missing even if brace-like content was in parse errors (they are not IMPORTED)
      ImportJob completed = new ImportJob(jobId, actor, targetProfile, JobStatus.COMPLETED,
          registry.get(jobId).startedAt(), Instant.now(),
          List.copyOf(allResults), summary, List.of());
      registry.update(completed);
      // Audit even for empty
      return auditBestEffort(actor, targetProfile, summary);
    }

    // --- Résolution proxy optionnel (fail-closed, jamais de fallback direct) ---
    final AbstractConnector.NetworkProxyOptions proxyOpts;
    if (proxyName != null && !proxyName.isBlank()) {
      AbstractConnector.NetworkProxyOptions resolved = resolveProxyForImport(proxyName, parsed, allResults);
      if (resolved == null) {
        // Fail-closed : toutes les lignes déjà marquées PROBE_FAILED — compléter le job
        allResults.sort(Comparator.comparingInt(LineResult::lineNumber));
        ImportSummary summary = buildSummary(allResults);
        ImportJob completed = new ImportJob(jobId, actor, targetProfile, JobStatus.COMPLETED,
            registry.get(jobId).startedAt(), Instant.now(),
            List.copyOf(allResults), summary, List.of());
        registry.update(completed);
        return auditBestEffort(actor, targetProfile, summary);
      }
      proxyOpts = resolved;
    } else {
      proxyOpts = null;
    }

    // For each parsed line, resolve DNS (LEGACY direct seulement — avec proxy, la résolution
    // DNS de la cible est déléguée au serveur SOCKS5 via ATYP domain, AUCUN DNS local)
    List<Future<Set<String>>> dnsFutures = new ArrayList<>();
    for (CccamCfgParser.ParsedLine pl : parsed) {
      if (proxyOpts != null) {
        dnsFutures.add(Future.succeededFuture(Set.of())); // placeholder — no local DNS with proxy
      } else {
        dnsFutures.add(dnsResolverService.resolve(pl.host())
            .recover(err -> Future.failedFuture(err)));
      }
    }

    // Wait for all DNS (use Future.all style but we want per-line failure handling)
    // Use composite: execute all and collect results individually
    Promise<Void> dnsDone = Promise.promise();
    Map<Integer, Set<String>> resolvedMap = new HashMap<>();
    Map<Integer, String> dnsErrorMap = new HashMap<>();

    // Execute sequentially with Future chain to gather all
    Future<Void> chain = Future.succeededFuture();
    for (int i = 0; i < parsed.size(); i++) {
      int idx = i;
      Future<Set<String>> f = dnsFutures.get(idx);
      chain = chain.compose(v -> f
          .onSuccess(ips -> resolvedMap.put(idx, ips))
          .onFailure(err -> dnsErrorMap.put(idx, sanitizeReason(err.getMessage())))
          .recover(err -> Future.succeededFuture())
          .mapEmpty());
    }

    return chain.compose(v -> {
      // Compute digest for successful DNS lines (v1 direct) or all lines (v2 proxy — pas de DNS local)
      Map<Integer, String> digestMap = new HashMap<>();
      Set<String> candidateDigests = new HashSet<>();
      for (int i = 0; i < parsed.size(); i++) {
        if (dnsErrorMap.containsKey(i)) continue;
        CccamCfgParser.ParsedLine pl = parsed.get(i);
        String protocol = pl.type() == CccamCfgParser.Type.C ? "cccam" : "newcamd";
        String secretMaterial = buildSecretMaterial(pl);
        String digest;
        if (proxyOpts != null) {
          // Digest v2 : protocole + proxyName + hostname normalisé + port + user + secret.
          // Dédup différente direct/proxy : la même ligne via proxy ≠ direct.
          // Le digest SHA-256 n'expose jamais le secret.
          digest = computeDigestV2(protocol, proxyName, normalizeHostname(pl.host()),
              pl.port(), pl.username(), secretMaterial);
        } else {
          Set<String> ips = resolvedMap.get(i);
          digest = computeDigest(protocol, ips, pl.port(), pl.username(), secretMaterial);
        }
        digestMap.put(i, digest);
        candidateDigests.add(digest);
      }

      // Bulk lookup existing digests
      Future<Set<String>> existingFuture;
      if (candidateDigests.isEmpty()) {
        existingFuture = Future.succeededFuture(Set.of());
      } else {
        existingFuture = configRepository.findExistingDedupDigests(candidateDigests)
            .recover(err -> {
              LOG.log(Level.WARNING, "findExistingDedupDigests failed: " + err.getMessage());
              return Future.succeededFuture(Set.of());
            });
      }

      return existingFuture.compose(existingDigests -> {
        // Intra-file duplicate detection
        Map<String, Integer> seen = new HashMap<>();
        Map<Integer, LineStatus> preProbeStatus = new HashMap<>();
        Map<Integer, String> preProbeReason = new HashMap<>();
        Map<Integer, String> duplicateOf = new HashMap<>();

        // Existing name set for slug collision check: from snapshot.
        // Thread-safe : generateUniqueName/add appelés depuis les lanes concurrentes.
        Set<String> existingNames = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
          if (configHolder.isInitialized()) {
            existingNames.addAll(configHolder.get().connectorByName().keySet());
          }
        } catch (Exception e) {
          LOG.fine("Could not load existing connector names: " + e.getMessage());
        }
        Set<String> generatedNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // Prepare for probe: list of indices that need probing
        List<Integer> toProbe = new ArrayList<>();

        for (int i = 0; i < parsed.size(); i++) {
          if (dnsErrorMap.containsKey(i)) {
            preProbeStatus.put(i, LineStatus.DNS_RESOLVE_FAILED);
            preProbeReason.put(i, dnsErrorMap.get(i));
            continue;
          }
          String digest = digestMap.get(i);
          if (existingDigests.contains(digest)) {
            preProbeStatus.put(i, LineStatus.DUPLICATE);
            preProbeReason.put(i, "duplicate of existing connector");
            continue;
          }
          if (seen.containsKey(digest)) {
            preProbeStatus.put(i, LineStatus.DUPLICATE);
            preProbeReason.put(i, "duplicate of line " + (seen.get(digest) + 1));
            continue;
          }
          seen.put(digest, i);
          toProbe.add(i);
        }

        // Add immediate results for non-probed
        for (int i = 0; i < parsed.size(); i++) {
          if (preProbeStatus.containsKey(i)) {
            CccamCfgParser.ParsedLine pl = parsed.get(i);
            LineStatus st = preProbeStatus.get(i);
            String reason = preProbeReason.get(i);
            String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
            allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
                st, reason, null));
          }
        }

        // --- Probes CONCURRENTS à borne configurable (Plan P2 §6) ---
        // N lanes = csp.import.max-concurrent-probes (défaut 4, clampé 1..64).
        // Chaque lane traite ses indices round-robin SÉQUENTIELLEMENT ; la
        // concurrence GLOBALE est bornée à N lanes — overlap effectif possible
        // entre lanes, plafond garanti, aucun dépassement.
        // Les callbacks de probes complètent sur les event-loops Vert.x (multi-
        // threads possibles) : allResults/existingNames/generatedNames sont
        // thread-safe (Collections.synchronizedList / concurrent sets).
        int lanes = Math.max(1, Math.min(64, maxConcurrentProbes));
        List<io.vertx.core.Future> laneFutures = new ArrayList<>(lanes);
        for (int lane = 0; lane < lanes; lane++) {
          Future<Void> laneChain = Future.succeededFuture();
          for (int k = lane; k < toProbe.size(); k += lanes) {
            int currentIdx = toProbe.get(k);
            CccamCfgParser.ParsedLine pl = parsed.get(currentIdx);
            String protocol = pl.type() == CccamCfgParser.Type.C ? "cccam" : "newcamd";
            String digest = digestMap.get(currentIdx);

            laneChain = laneChain.compose(v2 -> {
            // Perform probe
            Future<ConnectorProbe.ProbeResult> probeFuture;
            try {
              // Chemin legacy (sans proxy) : signature 6-args HISTORIQUE — préserve
              // les overrides de doProbe des call sites existants (tests legacy).
              // Chemin proxy : signature 7-args avec options proxy résolues.
              probeFuture = proxyOpts != null
                  ? doProbe(protocol, pl.host(), pl.port(), pl.username(), pl.password(), pl.desKey14(), proxyOpts)
                  : doProbe(protocol, pl.host(), pl.port(), pl.username(), pl.password(), pl.desKey14());
            } catch (Exception e) {
              String reason = sanitizeReason(e.getMessage());
              String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
              allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
                  LineStatus.PROBE_FAILED, reason, null));
              return Future.succeededFuture();
            }

            return probeFuture
                .recover(err -> Future.succeededFuture(new ConnectorProbe.ProbeResult(false, null, sanitizeReason(err.getMessage()))))
                .compose(probeResult -> {
                  if (!probeResult.success()) {
                    String reason = probeResult.errorMessage() != null ? sanitizeReason(probeResult.errorMessage()) : "probe failed";
                    String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
                    allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
                        LineStatus.PROBE_FAILED, reason, null));
                    return Future.succeededFuture();
                  }

                  // Probe succeeded -> materialize + persist
                  String connectorName = generateUniqueName(protocol, pl.host(), pl.port(), existingNames, generatedNames);
                  generatedNames.add(connectorName);
                  existingNames.add(connectorName);

                  // Materialize secrets
                  Future<String> pwdFuture = secretMaterializer.materializePassword(connectorName, pl.password());
                  Future<String> desFuture;
                  if (pl.desKey14() != null) {
                    desFuture = secretMaterializer.materializeDesKey(connectorName, pl.desKey14());
                  } else {
                    desFuture = Future.succeededFuture(null);
                  }

                  return pwdFuture
                      .compose(pwdRef -> desFuture.map(desRef -> new String[]{pwdRef, desRef}))
                      .compose(refs -> {
                        String pwdRef = refs[0];
                        String desRef = refs[1];
                        // Persist avec proxyName (null = direct) — protégé par FK csp_proxy
                        ConnectorConfig cfg = new ConnectorConfig(
                            connectorName, targetProfile, protocol, true, false,
                            pl.host(), pl.port(), pl.username(), pwdRef, desRef,
                            5000, 10000, null, null, null, null, null, null,
                            false, null, null, null, null, null, null, null, 50, 0, 1L,
                            proxyName);
                        return configRepository.upsertConnector(cfg, actor)
                            .map(vv -> cfg)
                            .recover(err -> {
                              // Persist failed -> cleanup secrets
                              LOG.log(Level.WARNING, "upsertConnector failed for " + connectorName + ": " + err.getMessage());
                              // Cleanup orphan secrets best-effort
                              secretMaterializer.cleanup(connectorName)
                                  .onComplete(ar -> {
                                    if (ar.failed()) LOG.fine("cleanup failed for " + connectorName);
                                  });
                              return Future.failedFuture(err);
                            });
                      })
                      .onSuccess(cfg -> {
                        // best-effort setDedupDigest
                        configRepository.setDedupDigest(connectorName, digest, actor)
                            .onComplete(ar -> {
                              if (ar.failed()) LOG.log(Level.WARNING, "setDedupDigest failed for " + connectorName + ": " + ar.cause().getMessage());
                            });
                        String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
                        allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
                            LineStatus.IMPORTED, null, connectorName));
                      })
                      .recover(err -> {
                        String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
                        String reason = sanitizeReason(err.getMessage());
                        allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
                            LineStatus.PERSIST_FAILED, reason, null));
                        return Future.succeededFuture();
                      })
                      .mapEmpty();
                });
            });
          }
          laneFutures.add(laneChain);
        }
        // Toutes les lanes encapsulent leurs échecs (recover interne) : join/all
        // attend l'achèvement de CHAQUE lane — arrêt propre garanti, aucun orphelin.
        Future<Void> probeChain = io.vertx.core.CompositeFuture.all(laneFutures).mapEmpty();

        return probeChain.compose(v3 -> {
          // All done: sort results by lineNumber
          allResults.sort(Comparator.comparingInt(LineResult::lineNumber));
          ImportSummary summary = buildSummary(allResults);
          List<MissingCaidProvider> missing = computeMissingCaidProviders(parsed, allResults);
          ImportJob completed = new ImportJob(jobId, actor, targetProfile, JobStatus.COMPLETED,
              registry.get(jobId).startedAt(), Instant.now(),
              List.copyOf(allResults), summary, missing);
          registry.update(completed);
          return auditBestEffort(actor, targetProfile, summary);
        });
      });
    });
  }

  private Future<Void> auditBestEffort(String actor, String targetProfile, ImportSummary summary) {
    return configRepository.insertImportAudit(actor, targetProfile,
            summary.total(), summary.imported(), summary.duplicate(),
            summary.dnsFailed(), summary.probeFailed(), summary.persistFailed(), summary.parseError())
        .recover(err -> {
          LOG.log(Level.WARNING, "insertImportAudit failed: " + err.getMessage());
          return Future.succeededFuture();
        })
        .mapEmpty();
  }

  private ImportSummary buildSummary(List<LineResult> results) {
    int total = results.size();
    int imported = 0, dup = 0, dns = 0, probe = 0, persist = 0, parse = 0;
    for (LineResult r : results) {
      switch (r.status()) {
        case IMPORTED -> imported++;
        case DUPLICATE -> dup++;
        case DNS_RESOLVE_FAILED -> dns++;
        case PROBE_FAILED -> probe++;
        case PERSIST_FAILED -> persist++;
        case PARSE_ERROR -> parse++;
      }
    }
    return new ImportSummary(total, imported, dup, dns, probe, persist, parse);
  }

  private List<MissingCaidProvider> computeMissingCaidProviders(
      List<CccamCfgParser.ParsedLine> parsed, List<LineResult> allResults) {
    Map<Integer, LineStatus> statusByLine = new HashMap<>();
    for (LineResult r : allResults) statusByLine.put(r.lineNumber(), r.status());
    // Collect distinct decls from IMPORTED lines only
    Set<CccamCfgParser.CaidProviderDecl> importedDecls = new HashSet<>();
    for (CccamCfgParser.ParsedLine pl : parsed) {
      LineStatus st = statusByLine.get(pl.lineNumber());
      if (st == LineStatus.IMPORTED) {
        importedDecls.addAll(pl.caidProviderDecls());
      }
    }
    if (importedDecls.isEmpty()) return List.of();
    // Snapshot unavailable => conservative empty (do not mark all as unknown)
    if (configHolder == null || !configHolder.isInitialized()) {
      LOG.log(Level.WARNING, "computeMissingCaidProviders: snapshot unavailable, returning empty");
      return List.of();
    }
    Set<Integer> allCaIds = new HashSet<>();
    Map<Integer, Set<Integer>> providersByCaid = new HashMap<>();
    try {
      ConfigSnapshot snap = configHolder.get();
      for (ProfileConfig pc : snap.profiles()) {
        int caId = pc.caId();
        allCaIds.add(caId);
        Set<Integer> set = providersByCaid.computeIfAbsent(caId, k -> new HashSet<>());
        for (ProfileProviderIdentConfig pi : snap.providerIdentsFor(pc.name())) {
          set.add(pi.providerIdent());
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "computeMissingCaidProviders failed to load snapshot, returning empty");
      return List.of();
    }
    // Deduplicate and sort
    Comparator<MissingCaidProvider> cmp = Comparator
        .comparingInt(MissingCaidProvider::caId)
        .thenComparingInt(MissingCaidProvider::providerIdent);
    Set<MissingCaidProvider> sorted = new java.util.TreeSet<>(cmp);
    for (CccamCfgParser.CaidProviderDecl decl : importedDecls) {
      int caId = decl.caId();
      int prov = decl.providerIdent();
      boolean known = allCaIds.contains(caId);
      if (!known) {
        sorted.add(new MissingCaidProvider(caId, prov, false));
      } else {
        Set<Integer> provs = providersByCaid.getOrDefault(caId, Set.of());
        if (!provs.contains(prov)) {
          sorted.add(new MissingCaidProvider(caId, prov, true));
        }
      }
    }
    return List.copyOf(sorted);
  }

  private String buildSecretMaterial(CccamCfgParser.ParsedLine pl) {
    if (pl.desKey14() != null) {
      String hex = bytesToHexUpper(pl.desKey14());
      return pl.password() + ":" + hex;
    } else {
      return pl.password();
    }
  }

  static String computeDigest(String protocol, Set<String> ips, int port, String username, String secretMaterial) {
    List<String> sorted = new ArrayList<>(ips);
    Collections.sort(sorted);
    String joined = String.join(",", sorted);
    String input = "csp-import-dedup-v1:" + protocol + ":" + joined + ":" + port + ":" + username + ":" + secretMaterial;
    return sha256Hex(input);
  }

  /**
   * Digest v2 (import avec proxy) : protocole + proxyName + hostname normalisé + port
   * + username + secret. SHA-256 — le digest n'expose jamais le secret ni le digest lui-même
   * en clair dans les résultats. Dédup différente direct (v1/IPs résolues) vs proxy (v2/hostname).
   */
  static String computeDigestV2(String protocol, String proxyName, String normalizedHost,
                                int port, String username, String secretMaterial) {
    String input = "csp-import-dedup-v2:" + protocol + ":" + proxyName + ":"
        + normalizedHost + ":" + port + ":" + username + ":" + secretMaterial;
    return sha256Hex(input);
  }

  /** Hostname normalisé pour digest v2 : trim + lowercase + IPv6 sans crochets. */
  static String normalizeHostname(String host) {
    if (host == null) return "";
    String h = host.trim().toLowerCase(java.util.Locale.ROOT);
    if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
      h = h.substring(1, h.length() - 1);
    }
    return h;
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return bytesToHexLower(hash);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Résout le proxy d'import UNE fois avant tout probe/persist (worker thread).
   * Fail-closed strict : absent/disabled/secret KO → toutes les lignes PROBE_FAILED,
   * JAMAIS de repli direct.
   *
   * @return options résolues, ou null si fail-closed (lignes déjà marquées en erreur)
   */
  private AbstractConnector.NetworkProxyOptions resolveProxyForImport(String proxyName,
      List<CccamCfgParser.ParsedLine> parsed, List<LineResult> allResults) {
    NetworkProxyConfig p = null;
    try {
      if (configHolder != null && configHolder.isInitialized()) {
        p = configHolder.get().proxyByName().get(proxyName);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Import proxy lookup failed: " + sanitizeReason(e.getMessage()));
    }
    if (p == null) {
      markAllProbeFailed(parsed, allResults, "proxy '" + proxyName + "' not available (fail-closed, no direct)");
      return null;
    }
    if (!p.enabled()) {
      markAllProbeFailed(parsed, allResults, "proxy '" + proxyName + "' disabled (fail-closed, no direct)");
      return null;
    }
    String username = null;
    String password = null;
    if (p.hasAuth()) {
      if (p.passwordRef() == null || p.passwordRef().isBlank()) {
        markAllProbeFailed(parsed, allResults, "proxy '" + proxyName + "' misconfigured (fail-closed)");
        return null;
      }
      try {
        if (runtimeSecretResolver == null) {
          markAllProbeFailed(parsed, allResults, "proxy secret resolver unavailable (fail-closed)");
          return null;
        }
        password = runtimeSecretResolver.resolvePassword(p.passwordRef(), "proxy:" + proxyName);
        username = p.username();
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Import proxy secret resolution failed: " + sanitizeReason(e.getMessage()));
        markAllProbeFailed(parsed, allResults, "proxy secret resolution failed (fail-closed, no direct)");
        return null;
      }
    }
    try {
      // PAS de timeout porté par le proxy (Plan P2) : la durée dédiée du probe
      // est csp.import.probe-timeout-ms (bornée dans doProbe/ConnectorProbe).
      return new AbstractConnector.NetworkProxyOptions(
          p.host(), p.port(), username, password);
    } catch (Exception e) {
      markAllProbeFailed(parsed, allResults, "proxy options invalid (fail-closed)");
      return null;
    }
  }

  private void markAllProbeFailed(List<CccamCfgParser.ParsedLine> parsed, List<LineResult> allResults, String reason) {
    for (CccamCfgParser.ParsedLine pl : parsed) {
      String typeStr = pl.type() == CccamCfgParser.Type.C ? "C" : "N";
      allResults.add(new LineResult(pl.lineNumber(), typeStr, pl.host(), pl.port(), pl.username(),
          LineStatus.PROBE_FAILED, reason, null));
    }
  }

  private static String bytesToHexUpper(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte v : b) sb.append(String.format("%02X", v & 0xFF));
    return sb.toString();
  }

  private static String bytesToHexLower(byte[] b) {
    StringBuilder sb = new StringBuilder(b.length * 2);
    for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
    return sb.toString();
  }

  private static String sanitizeReason(String s) {
    if (s == null) return "unknown";
    String t = s.trim();
    // Never include line breaks
    t = t.replaceAll("[\\r\\n]+", " ");
    // Filtrer les patterns de type credential dans les messages d'erreur
    t = t.replaceAll("(?i)(password|passwd|pass|pwd|secret|key|token|deskey)\\s*[=:]\\s*\\S+", "$1=[REDACTED]");
    if (t.length() > 200) t = t.substring(0, 200);
    return t;
  }

  // Kept for test compatibility (deprecated) — delegates to SecretMaterializer.normalizeSlug
  static String normalizeSlugLocal(String input) {
    if (input == null || input.isBlank()) return "host";
    return SecretMaterializer.normalizeSlug(input);
  }

  private String generateUniqueName(String protocol, String host, int port, Set<String> existingNames, Set<String> generatedNames) {
    String effectiveHost = (host == null || host.isBlank()) ? "host" : host;
    String slug = SecretMaterializer.normalizeSlug(effectiveHost);
    String base = "imported-" + protocol + "-" + slug + "-" + port;
    // Réservation ATOMIQUE : generatedNames.add(c) est l'opération de réservation —
    // deux lanes concurrentes sur le même slug ne peuvent PAS obtenir le même nom
    // (Set concurrent : un seul add réussit). Pas de fenêtre contains→return→add.
    if (!existingNames.contains(base) && generatedNames.add(base)) return base;
    int suffix = 2;
    while (true) {
      String candidate = base + "-" + suffix;
      if (!existingNames.contains(candidate) && generatedNames.add(candidate)) return candidate;
      suffix++;
      if (suffix > 1000) throw new IllegalStateException("Cannot generate unique name for " + base);
    }
  }

  protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port, String username, String password, byte[] desKey14) {
    return doProbe(protocol, host, port, username, password, desKey14, null);
  }

  /** Probe avec proxy SOCKS5 optionnel — secrets proxy déjà résolus par l'appelant (worker). */
  protected Future<ConnectorProbe.ProbeResult> doProbe(String protocol, String host, int port,
                                                       String username, String password, byte[] desKey14,
                                                       AbstractConnector.NetworkProxyOptions proxyOpts) {
    var cacheVerticle = runtimeCore != null ? runtimeCore.getCacheVerticle() : null;
    return ConnectorProbe.probe(vertx, cacheVerticle, protocol, host, port, username, password, desKey14, probeTimeoutMs, proxyOpts);
  }
}
