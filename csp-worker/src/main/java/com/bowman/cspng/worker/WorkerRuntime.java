package com.bowman.cspng.worker;

import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.Camd35TcpConnector;
import com.bowman.cspng.connectors.CcCamConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.NewcamdConnector;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardSource;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.sessions.AbstractSession;
import com.bowman.cspng.sessions.AuthAudit;
import com.bowman.cspng.sessions.Camd35TcpSession;
import com.bowman.cspng.sessions.CcCamSession;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.users.IpMatcher;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dataplane worker reel (Gemini FIX : pas un simple polling agent).
 *
 * <ul>
 *   <li>Listeners TCP CCcam + CS378x servis depuis le desired (NetServers
 *       dedies, sessions {@link CcCamSession}/{@link Camd35TcpSession}
 *       reutilisees, jamais reimplementees).</li>
 *   <li>Connecteurs amont autorises (newcamd/cccam/radegast) instancies depuis
 *       le desired avec secrets resolus localement {@code env:/file:} ; toute
 *       config locale independante est refusee (fail-closed au demarrage si
 *       detectee — le runtime n'expose aucun setter public de connecteur).</li>
 *   <li>Auth clients sans DB : {@link ProtocolAuthResolver.Fake} reconstruit
 *       depuis users + secret refs du desired ; catalogue publie injecte dans
 *       un {@link CardDiscoveryService} memoire (pas de PG).</li>
 *   <li>CSP inter-noeuds : pont {@link WorkerCspV2Bridge} (protocole CSPv2
 *       existant, mTLS) ; UDP cache legacy via {@link WorkerUdpExchange}
 *       (consult pending + feed + export, allowlist stricte host+port).</li>
 *   <li>Revocation/expiry : {@link #failClosed(String)} purge stores+inflight
 *       et ferme sessions/listeners et le pont CSPv2 (effectif, teste).</li>
 * </ul>
 */
public final class WorkerRuntime implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkerRuntime.class.getName());

  private final Vertx vertx;
  private final String nodeId;
  private final WorkerSecretResolver secretResolver;
  private final WorkerState state;
  private final SessionRegistry registry;
  private final CwsConnectorManager connectors;
  private final com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine adaptiveRoutingEngine =
      new com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine();
  private final CardDiscoveryService discovery;
  private volatile ProtocolAuthResolver auth = new ProtocolAuthResolver.Fake();
  /** Checker CW-cycle partagé du worker (B1) : créé paresseusement, jamais reset sauf
   * changement sémantique de policy (les renouvellements TTL ne touchent à rien). */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  /** Dernière config CW-cycle appliquée (comparaison sémantique, version exclue). */
  private volatile com.bowman.cspng.config.CwCycleConfig appliedCwCycle;
  /** Stats cacheex par pair du worker (remontées au central via WorkerStatsView.cacheex). */
  private final com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry cacheExStats =
      new com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry();
  /** Pont cacheex vers les vrais caches du worker (C1) — jamais de cache fantôme. */
  private final WorkerCacheExBridge cacheExBridge = new WorkerCacheExBridge(cacheExStats);
  // ---- Qualité pairs / hits / quarantaine / quorum (lots 2-5), mémoire bornée ----
  private final com.bowman.cspng.connectors.cacheex.PeerQualityTracker peerQualityTracker =
      new com.bowman.cspng.connectors.cacheex.PeerQualityTracker();
  private final com.bowman.cspng.connectors.cacheex.CacheHitAttribution cacheHitAttribution =
      new com.bowman.cspng.connectors.cacheex.CacheHitAttribution();
  private final com.bowman.cspng.connectors.cacheex.PeerQuarantine peerQuarantine =
      new com.bowman.cspng.connectors.cacheex.PeerQuarantine();
  private final com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker quorumTracker =
      new com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker();
  private final com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate =
      new com.bowman.cspng.connectors.cacheex.CacheExIngressGate();

  /** Porte d'ingress worker (configs relayées via {@link WorkerState}, jamais de secret). */
  com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate() {
    return ingressGate;
  }

  private static final class ServedListener {
    NetServer server;
    StrictEcmRouter router;
    RelayWorkerContract.ListenerView view;
    final Set<AbstractSession> sessions = ConcurrentHashMap.newKeySet();
  }

  private final Map<Long, ServedListener> listeners = new ConcurrentHashMap<>();
  private final Map<String, String> appliedConnectorSpecs = new ConcurrentHashMap<>();
  private volatile WorkerCspV2Bridge cspv2;
  private volatile long appliedVersion = -1L;

  // Echange UDP legacy (proxy cache inter-noeuds, allowlist stricte).
  private volatile WorkerUdpExchange udpExchange;
  private volatile int udpPort;
  private volatile java.util.Set<String> servedProfiles = java.util.Set.of();
  private volatile java.util.List<String> cacheNames = java.util.List.of();

  public void setUdpPort(int udpPort) {
    if (udpPort < 0 || udpPort > 65535) throw new IllegalArgumentException("udpPort 0..65535");
    this.udpPort = udpPort;
  }

  public WorkerUdpExchange udpExchange() {
    return udpExchange;
  }

  /**
   * Purge active des trackers qualité/quarantaine/quorum (lots 2/4/5) —
   * appelée par le timer de poll existant de {@code WorkerMain}. Sans ce
   * balayage, une entrée jamais retouchée (ex. clé quorum jamais reconfirmée)
   * n'est libérée que paresseusement au prochain accès (jamais si aucun accès
   * ultérieur). Fenêtre quorum = valeur LIVE relayée ({@code state.quorum()})
   * : plus précis que le worker central (qui n'a qu'un plafond conservateur).
   * Petits calculs bornés, jamais d'E/S, isolés par try/catch (un échec ne
   * doit jamais interrompre le poll loop).
   */
  public void sweepPeerQualityState() {
    try {
      peerQualityTracker.evictExpired();
    } catch (Exception ignored) {
    }
    try {
      peerQuarantine.evictExpired();
    } catch (Exception ignored) {
    }
    try {
      quorumTracker.evictExpired(state.quorum().windowMs());
    } catch (Exception ignored) {
    }
  }

  private final java.util.ArrayDeque<RelayWorkerContract.WorkerLogEntry> logQueue = new java.util.ArrayDeque<>(2048);
  private final Object logLock = new Object();
  private final java.util.concurrent.atomic.AtomicLong logSeq = new java.util.concurrent.atomic.AtomicLong();
  private java.util.logging.Handler julHandler;

  public void recordLog(long timestamp, String level, String logger, String message) {
    long seq = logSeq.incrementAndGet();
    RelayWorkerContract.WorkerLogEntry entry = new RelayWorkerContract.WorkerLogEntry(seq, timestamp, level, logger, message);
    synchronized (logLock) {
      if (logQueue.size() >= 2000) {
        logQueue.pollFirst();
      }
      logQueue.addLast(entry);
    }
  }

  public java.util.List<RelayWorkerContract.WorkerLogEntry> drainLogs(int max) {
    int count = Math.max(1, Math.min(200, max));
    java.util.List<RelayWorkerContract.WorkerLogEntry> list = new java.util.ArrayList<>(count);
    synchronized (logLock) {
      for (int i = 0; i < count; i++) {
        var e = logQueue.pollFirst();
        if (e == null) break;
        list.add(e);
      }
    }
    return list;
  }

  public WorkerRuntime(Vertx vertx, String nodeId, WorkerState state) {
    this.vertx = vertx;
    this.nodeId = nodeId;
    this.state = state;
    this.secretResolver = new WorkerSecretResolver(vertx);
    this.registry = new SessionRegistry(vertx);
    this.connectors = new CwsConnectorManager(vertx, null);
    this.discovery = new CardDiscoveryService(vertx);
    // Porte d'ingress : policy DCW + configs V16 relayées (live via state).
    ingressGate.setDcwPolicySupplier(state::dcwPolicy);
    ingressGate.setQuality(peerQualityTracker, peerQuarantine, state::quarantine);
    ingressGate.setQuorum(quorumTracker, state::quorum);

    try {
      this.julHandler = new java.util.logging.Handler() {
        @Override
        public void publish(java.util.logging.LogRecord r) {
          if (r == null) return;
          try {
            String lvl = "INFO";
            if (r.getLevel().intValue() >= java.util.logging.Level.SEVERE.intValue()) lvl = "ERROR";
            else if (r.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) lvl = "WARN";
            else if (r.getLevel().intValue() < java.util.logging.Level.INFO.intValue()) lvl = "DEBUG";
            String lgr = r.getLoggerName() != null ? r.getLoggerName() : "worker";
            if (lgr.startsWith("com.bowman.cspng.")) lgr = lgr.substring("com.bowman.cspng.".length());
            if (lgr.length() > 128) lgr = lgr.substring(0, 128);

            String msg = r.getMessage();
            if (msg == null) msg = "";
            Object[] params = r.getParameters();
            if (params != null && params.length > 0) {
              try {
                if (msg.contains("{0}")) {
                  msg = java.text.MessageFormat.format(msg, params);
                }
              } catch (Exception ignored) {}
            }
            Throwable thrown = r.getThrown();
            if (thrown != null) {
              msg = msg.isEmpty() ? thrown.toString() : msg + " — " + thrown.toString();
            }
            if (msg.length() > 2048) msg = msg.substring(0, 2048);

            recordLog(r.getMillis(), lvl, lgr, msg);
          } catch (Exception ignored) {}
        }
        @Override public void flush() {}
        @Override public void close() {}
      };
      julHandler.setLevel(java.util.logging.Level.ALL);
      java.util.logging.Logger.getLogger("").addHandler(julHandler);
    } catch (Exception ignored) {}
  }

  public WorkerState state() {
    return state;
  }

  public CwsConnectorManager connectors() {
    return connectors;
  }

  public CardDiscoveryService discovery() {
    return discovery;
  }

  private volatile WorkerCspV2Bridge attachedBridge;
  private volatile StrictEcmRouter uplinkRouter;
  private volatile AtomicLong uplinkEpoch;

  /** Attache le pont CSPv2 (cree au demarrage si port configure). */
  public synchronized void attachCspV2(WorkerCspV2Bridge bridge, StrictEcmRouter uplink,
                                       AtomicLong epoch) {
    this.attachedBridge = bridge;
    this.uplinkRouter = uplink;
    if (uplink != null) uplink.setAdaptiveRoutingEngine(adaptiveRoutingEngine);
    this.uplinkEpoch = epoch;
    if (bridge != null) bridge.start();
  }

  /** Rafraichit profils/epoch du pont apres chaque desired applique. */
  public synchronized void refreshCspV2Uplink(Set<String> profiles, long epoch) {
    if (uplinkEpoch != null) uplinkEpoch.set(epoch);
    if (attachedBridge != null) attachedBridge.updateProfiles(profiles, epoch);
    if (uplinkRouter != null) {
      uplinkRouter.setRelayPolicySupplier(state::policy, -1L);
      uplinkRouter.setRelayStores(state.stores());
    }
  }

  public long appliedVersion() {
    return appliedVersion;
  }

  public int listenerCount() {
    return listeners.size();
  }

  public int sessionCount() {
    int n = 0;
    for (ServedListener sl : listeners.values()) n += sl.sessions.size();
    return n;
  }

  /**
   * Applique un desired authentifie. Resolutions de secrets sur worker thread
   * (jamais event-loop). Retourne true si la config a change (ack requis),
   * false si identique (heartbeat) — jamais d'exception contenant un secret.
   */
  public Future<Boolean> applyDesired(RelayWorkerContract.DesiredWorkerConfig desired, long nowMs) {
    if (desired == null || !nodeId.equals(desired.nodeId())) {
      return Future.failedFuture(new IllegalArgumentException("desired node mismatch"));
    }
    if (nowMs > desired.expiresAtMs()) {
      return Future.failedFuture(new IllegalStateException("desired expired"));
    }
    if (appliedVersion >= 0 && desired.desiredVersion() < appliedVersion) {
      return Future.failedFuture(new IllegalStateException("desired rollback refused"));
    }
    if (appliedVersion >= 0 && desired.desiredVersion() == appliedVersion
        && state.appliedDesired() == desired.desiredVersion()
        && !state.expired(nowMs)) {
      if (state.renewExpiry(desired.desiredVersion(), desired.expiresAtMs(), nowMs)) {
        return Future.succeededFuture(false);
      }
      return Future.failedFuture(new IllegalStateException("lease renew refused"));
    }
    Promise<Boolean> p = Promise.promise();
    vertx.executeBlocking(() -> {
      try {
        applyBlocking(desired);
        return true;
      } catch (Exception e) {
        failClosed("apply-failed");
        throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
      }
    }, false).onComplete(ar -> {
      if (ar.failed()) p.fail(new IllegalStateException("apply failed: " + (ar.cause() != null ? ar.cause().getMessage() : "unknown"), ar.cause()));
      else p.complete(true);
    });
    return p.future();
  }

  private void applyBlocking(RelayWorkerContract.DesiredWorkerConfig desired) throws Exception {
    long now = System.currentTimeMillis();
    if (!state.validateDesired(desired, now)) {
      throw new IllegalStateException("policy apply refused (stale/expired/invalid)");
    }
    this.auth = WorkerAuth.build(desired);
    reconcileConnectors(desired);
    seedDiscovery(desired);
    reconcileListeners(desired);
    reconcileCwCycle(desired);
    reconcileUdp(desired);
    reconcileCspV2(desired);
    if (!state.applyDesired(desired, System.currentTimeMillis())) {
      throw new IllegalStateException("policy apply refused (stale/expired)");
    }
    if (uplinkRouter != null) {
      uplinkRouter.setDcwPolicySupplier(state::dcwPolicy);
    }
    java.util.Set<String> profs = new java.util.HashSet<>();
    java.util.List<String> cnames = new java.util.ArrayList<>();
    for (var lv : desired.listeners()) profs.add(lv.profile());
    for (var c : desired.caches()) cnames.add(c.name());
    servedProfiles = java.util.Set.copyOf(profs);
    cacheNames = java.util.List.copyOf(cnames);
    appliedVersion = desired.desiredVersion();
  }

  /**
   * Reconcilie les pairs CSPv2 mTLS autorises depuis le desired (Sonnet FIX #1).
   * Construit la vraie liste CspV2PeerSettings avec host/port/profils.
   */
  private void reconcileCspV2(RelayWorkerContract.DesiredWorkerConfig desired) {
    if (attachedBridge == null) return;
    Set<String> profs = new HashSet<>();
    for (var lv : desired.listeners()) profs.add(lv.profile());
    List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> peers = buildCspV2Peers(desired);
    if (uplinkEpoch != null) uplinkEpoch.set(desired.desiredVersion());
    attachedBridge.updatePeersAndProfiles(peers, profs, desired.desiredVersion());
    if (uplinkRouter != null) {
      uplinkRouter.setRelayPolicySupplier(state::policy, -1L);
      uplinkRouter.setRelayStores(state.stores());
    }
  }

  public List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> buildCspV2Peers(
      RelayWorkerContract.DesiredWorkerConfig desired) {
    if (desired == null || desired.nodes() == null) return List.of();
    List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> out = new ArrayList<>();
    Map<String, Set<String>> profilesByCache = new HashMap<>();
    Map<Long, String> profileByListener = new HashMap<>();
    for (var l : desired.listeners()) profileByListener.put(l.listenerId(), l.profile());
    for (var link : desired.links()) {
      if (!link.enabled()) continue;
      String p = profileByListener.get(link.listenerId());
      if (p != null) {
        profilesByCache.computeIfAbsent(link.cacheName(), k -> new HashSet<>()).add(p);
      }
    }
    Set<String> allServedProfiles = new HashSet<>();
    for (var l : desired.listeners()) allServedProfiles.add(l.profile());

    for (var n : desired.nodes()) {
      if (nodeId.equals(n.nodeName())) continue;
      if (!n.enabled() || !n.authorized() || n.revoked()) continue;
      RelayWorkerContract.CacheView nodeCache = null;
      for (var c : desired.caches()) {
        if ("node".equals(c.kind()) && n.nodeName().equals(c.nodeName()) && c.enabled()) {
          nodeCache = c;
          break;
        }
      }
      boolean hasDialEndpoint = nodeCache != null && !nodeCache.peers().isEmpty();
      String host = "localhost";
      int port = 14641;
      boolean dialEnabled = false;
      if (hasDialEndpoint) {
        var pv = nodeCache.peers().get(0);
        if (pv.host() != null && !pv.host().isBlank() && pv.port() > 0) {
          host = pv.host();
          port = pv.port();
          dialEnabled = true;
        }
      }
      if ("127.0.0.1".equals(host)) host = "localhost";
      Set<String> allowedProfs = nodeCache != null ? profilesByCache.get(nodeCache.name()) : null;
      List<String> profList = allowedProfs != null && !allowedProfs.isEmpty()
          ? new ArrayList<>(allowedProfs)
          : (!allServedProfiles.isEmpty() ? new ArrayList<>(allServedProfiles) : List.of("default"));
      try {
        out.add(new com.bowman.cspng.cluster.tcp.CspV2PeerSettings(
            true, n.nodeName(), host, port, profList, dialEnabled));
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Worker skipping invalid CSPv2 peer setting for " + n.nodeName() + ": " + e.getMessage());
      }
    }
    return out;
  }

  /** Echange UDP : socket unique, allowlist = pairs des caches udp du desired. */
  private void reconcileUdp(RelayWorkerContract.DesiredWorkerConfig desired) throws Exception {
    if (udpPort <= 0) {
      WorkerUdpExchange ex = udpExchange;
      udpExchange = null;
      if (ex != null) ex.failClosed();
      return;
    }
    WorkerUdpExchange ex = udpExchange;
    if (ex == null) {
      ex = new WorkerUdpExchange(vertx, state);
      ex.setIngressGate(ingressGate);
      try {
        int bound = ex.start(udpPort).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        LOG.info("Worker UDP exchange bound on port " + bound);
        udpExchange = ex;
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "reconcileUdp start failed on port " + udpPort, e);
        throw e;
      }
    }
    java.util.Set<String> profs = new java.util.HashSet<>();
    for (var lv : desired.listeners()) profs.add(lv.profile());
    ex.updateBindings(desired, profs);
  }

  // -- Connecteurs amont --

  /**
   * Réconcilie le contrôle CW-cycle (B1) : convertit la vue transportée, ne
   * (re)crée le checker partagé qu'en cas de changement SÉMANTIQUE (jamais sur
   * simple renouvellement TTL/version), puis pousse (checker, config) vers les
   * objets live : routeurs, connecteurs, sessions. Les objets créés ensuite
   * reçoivent le couple courant via {@link #wireCwCycle}.
   */
  private void reconcileCwCycle(RelayWorkerContract.DesiredWorkerConfig desired) {
    RelayWorkerContract.CwCycleView view = desired.cwCycle();
    if (view == null) view = RelayWorkerContract.CwCycleView.DEFAULT;
    com.bowman.cspng.config.CwCycleConfig cfg;
    try {
      cfg = view.toConfig();
    } catch (IllegalArgumentException e) {
      LOG.log(Level.WARNING, "Worker ignoring invalid cw-cycle view: " + e.getMessage());
      return;
    }
    com.bowman.cspng.config.CwCycleConfig prev = appliedCwCycle;
    com.bowman.cspng.filters.CwCycleChecker chk = cwCycleChecker;
    if (chk == null || !sameCwSemantics(prev, cfg)) {
      chk = new com.bowman.cspng.filters.CwCycleChecker(
          cfg.enabled(), cfg.caids(), cfg.maxList(), cfg.keepTimeSec(),
          cfg.onBad(), cfg.dropOld(), cfg.usePeerCycleMetadata());
      cwCycleChecker = chk;
      appliedCwCycle = cfg;
    }
    pushCwCycle(chk, cfg);
  }

  /** Égalité sémantique (version/horodatage/acteur exclus : pas de reset inutile). */
  static boolean sameCwSemantics(com.bowman.cspng.config.CwCycleConfig a,
                                 com.bowman.cspng.config.CwCycleConfig b) {
    if (a == null || b == null) return a == b;
    return a.enabled() == b.enabled()
        && a.caids().equals(b.caids())
        && a.maxList() == b.maxList()
        && a.keepTimeSec() == b.keepTimeSec()
        && a.onBad().equals(b.onBad())
        && a.dropOld() == b.dropOld()
        && a.usePeerCycleMetadata() == b.usePeerCycleMetadata();
  }

  /** Pousse (checker, config) vers routeurs/connecteurs/sessions live. */
  private void pushCwCycle(com.bowman.cspng.filters.CwCycleChecker chk,
                           com.bowman.cspng.config.CwCycleConfig cfg) {
    for (ServedListener sl : listeners.values()) {
      try {
        if (sl.router != null) sl.router.setCwCycle(chk, cfg);
      } catch (Exception ignored) {
      }
      try {
        for (AbstractSession s : new ArrayList<>(sl.sessions)) {
          if (s instanceof CcCamSession ccs) ccs.setCwCycle(chk, cfg);
          else if (s instanceof Camd35TcpSession c35s) c35s.setCwCycle(chk, cfg);
        }
      } catch (Exception ignored) {
      }
    }
    try {
      for (AbstractConnector c : connectors.getConnectors().values()) {
        if (c instanceof CcCamConnector cc) cc.setCwCycle(chk, cfg);
        else if (c instanceof Camd35TcpConnector c35) c35.setCwCycle(chk, cfg);
      }
    } catch (Exception ignored) {
    }
  }

  /** Câble le couple CW-cycle courant sur un objet créé (routeur/connecteur/session). */
  private void wireCwCycle(StrictEcmRouter router) {
    com.bowman.cspng.filters.CwCycleChecker chk = cwCycleChecker;
    com.bowman.cspng.config.CwCycleConfig cfg = appliedCwCycle;
    if (chk != null && cfg != null && router != null) {
      try {
        router.setCwCycle(chk, cfg);
      } catch (Exception ignored) {
      }
    }
  }

  private void wireCwCycle(AbstractConnector c) {
    com.bowman.cspng.filters.CwCycleChecker chk = cwCycleChecker;
    com.bowman.cspng.config.CwCycleConfig cfg = appliedCwCycle;
    if (chk == null || cfg == null || c == null) return;
    try {
      if (c instanceof CcCamConnector cc) cc.setCwCycle(chk, cfg);
      else if (c instanceof Camd35TcpConnector c35) c35.setCwCycle(chk, cfg);
    } catch (Exception ignored) {
    }
  }

  private void wireCwCycle(AbstractSession s) {
    com.bowman.cspng.filters.CwCycleChecker chk = cwCycleChecker;
    com.bowman.cspng.config.CwCycleConfig cfg = appliedCwCycle;
    if (chk == null || cfg == null || s == null) return;
    try {
      if (s instanceof CcCamSession ccs) ccs.setCwCycle(chk, cfg);
      else if (s instanceof Camd35TcpSession c35s) c35s.setCwCycle(chk, cfg);
    } catch (Exception ignored) {
    }
  }

  /**
   * Câble la porte d'ingress qualité/quarantaine/quorum (lots 1/2/4/5) sur un
   * objet créé. La porte est stable (fournisseurs live sur {@link WorkerState}),
   * aucun push ultérieur nécessaire.
   */
  private void wirePeerQuality(StrictEcmRouter router) {
    if (router == null) return;
    try {
      router.setHitAttribution(cacheHitAttribution);
    } catch (Exception ignored) {
    }
  }

  /** Câble la porte d'ingress sur un connecteur créé. */
  private void wirePeerQuality(AbstractConnector c) {
    if (c == null) return;
    try {
      if (c instanceof CcCamConnector cc) cc.setIngressGate(ingressGate);
      else if (c instanceof Camd35TcpConnector c35) c35.setIngressGate(ingressGate);
    } catch (Exception ignored) {
    }
  }

  /** Câble la porte d'ingress sur une session créée. */
  private void wirePeerQuality(AbstractSession s) {
    if (s == null) return;
    try {
      if (s instanceof CcCamSession ccs) ccs.setIngressGate(ingressGate);
      else if (s instanceof Camd35TcpSession c35s) c35s.setIngressGate(ingressGate);
    } catch (Exception ignored) {
    }
  }

  private void reconcileConnectors(RelayWorkerContract.DesiredWorkerConfig desired) {
    Map<String, RelayWorkerContract.ProxyView> proxies = new HashMap<>();
    for (var px : desired.proxies()) proxies.put(px.name(), px);
    Set<String> wanted = new HashSet<>();
    for (var c : desired.connectors()) {
      wanted.add(c.name());
      String spec = specOf(c);
      String prev = appliedConnectorSpecs.get(c.name());
      if (spec.equals(prev) && connectors.getConnector(c.name()) != null) continue;
      // Recreate (ancienne generation deconnectee proprement).
      try {
        var old = connectors.getConnector(c.name());
        if (old != null) {
          connectors.removeConnector(c.name());
          discovery.onConnectorDisconnected(c.name(), appliedVersion);
        }
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Worker remove stale connector failed");
      }
      try {
        AbstractConnector created = buildConnector(c, proxies);
        appliedConnectorSpecs.put(c.name(), spec);
        long gen = desired.desiredVersion();
        created.start().onComplete(ar -> {
          if (ar.succeeded()) {
            discovery.onConnectorConnected(c.name(), gen);
            LOG.info("Worker upstream connected: " + c.name());
          } else {
            LOG.log(Level.WARNING, "Worker upstream connect failed: " + c.name());
          }
        });
      } catch (Exception e) {
        // Fail-closed par connecteur : les autres restent servis.
        LOG.log(Level.WARNING, "Worker upstream skipped (fail-closed): " + c.name());
      }
    }
    for (String name : new ArrayList<>(appliedConnectorSpecs.keySet())) {
      if (!wanted.contains(name)) {
        try {
          connectors.removeConnector(name);
          discovery.onConnectorDisconnected(name, appliedVersion);
        } catch (Exception ignored) {
        }
        cacheExBridge.unregisterEmitter(name);
        appliedConnectorSpecs.remove(name);
      }
    }
  }

  private static String specOf(RelayWorkerContract.ConnectorView c) {
    return c.protocol() + "|" + c.profile() + "|" + c.host() + "|" + c.port() + "|"
        + c.username() + "|" + c.passwordRef() + "|" + c.desKeyRef() + "|" + c.proxyName()
        + "|" + c.connectTimeoutMs() + "|" + c.cccamVersion() + "|" + c.reshareCap() + "|"
        + c.cardDiscovery() + "|" + c.reshareGrant() + "|" + c.newcamdDialect() + "|" + c.cacheexMode()
        + "|" + c.cacheexFilter() + "|" + c.cacheexMaxSend() + "|" + c.cacheexMaxHop()
        + "|" + c.newcamdPasswordEncoding();
  }

  private AbstractConnector buildConnector(RelayWorkerContract.ConnectorView c,
                                           Map<String, RelayWorkerContract.ProxyView> proxies) throws Exception {
    String proto = c.protocol() == null ? "" : c.protocol().toLowerCase(Locale.ROOT);
    AbstractConnector.NetworkProxyOptions proxyOpts = proxyOptions(c, proxies);
    int connectTimeout = proxyOpts != null ? clamp(c.connectTimeoutMs()) : c.connectTimeoutMs();
    if ("newcamd".equals(proto)) {
      if (c.passwordRef() == null || c.passwordRef().isBlank()) throw new IllegalStateException("passwordRef required");
      if (c.desKeyRef() == null || c.desKeyRef().isBlank()) throw new IllegalStateException("desKeyRef required");
      if ("oscam_ncd525".equalsIgnoreCase(c.newcamdDialect())) throw new UnsupportedOperationException("oscam_ncd525 unsupported");
      String password = secretResolver.resolveSync(c.passwordRef());
      byte[] desKey14 = secretResolver.resolveDesKey14(c.desKeyRef());
      String username = c.username() != null ? c.username() : "";
      try {
        NewcamdConnector nc = connectors.addNewcamdConnector(c.name(), c.host(), c.port(),
            desKey14, username, password, c.profile(), connectTimeout, proxyOpts);
        nc.setCardDiscovery(Boolean.TRUE.equals(c.cardDiscovery()));
        nc.setReshareGrant(c.reshareGrant());
        nc.setNewcamdDialect(c.newcamdDialect());
        nc.setNewcamdPasswordEncoding(c.newcamdPasswordEncoding());
        nc.setReshareCap(c.reshareCap() != null ? c.reshareCap() : 0);
        nc.setCardDiscoveryService(discovery);
        return nc;
      } finally {
        ArraysFill(desKey14);
      }
    } else if ("cccam".equals(proto)) {
      if (c.passwordRef() == null || c.passwordRef().isBlank()) throw new IllegalStateException("passwordRef required");
      String password = secretResolver.resolveSync(c.passwordRef());
      String username = c.username() != null ? c.username() : "";
      String version = c.cccamVersion() != null ? c.cccamVersion() : CcConstants.DEFAULT_VERSION;
      // Cacheex actif via le pont (C1) : pas de CacheVerticle sur le worker, les push
      // sont injectés dans le vrai cache (lookup hash du routeur) et l'émission est
      // évaluée sur chaque feed dispatch + forwarding.
      int cacheexMode = c.cacheexMode() != null ? c.cacheexMode() : CcConstants.CACHEEX_MODE_OFF;
      int cacheexMaxHop = c.cacheexMaxHop() != null ? c.cacheexMaxHop() : CcConstants.CACHEEX_DEFAULT_MAXHOP;
      int cacheexMaxSend = (c.cacheexMaxSend() != null && c.cacheexMaxSend() > 0)
          ? c.cacheexMaxSend() : CcCamConnector.DEFAULT_MAX_SEND_QUEUE;
      CcCamConnector cc = connectors.addCcCamConnector(c.name(), c.host(), c.port(), username, password,
          c.profile(), version, CcConstants.DEFAULT_BUILD, cacheexMode, cacheexMaxHop,
          connectTimeout, proxyOpts, c.cacheexFilter(), cacheexMaxSend, cacheExStats);
      cc.setReshareCap(c.reshareCap() != null ? c.reshareCap() : 0);
      cc.setCardDiscoveryService(discovery);
      cc.setCacheExBridge(cacheExBridge);
      wireCwCycle(cc);
      wirePeerQuality(cc);
      if (cacheexMode == 3) {
        // (Enregistrement de la paire fait par le connecteur lui-même au start.)
        cacheExBridge.registerEmitter(c.name(), cc);
      }
      return cc;
    } else if ("radegast".equals(proto)) {
      var r = connectors.addRadegastConnector(c.name(), c.host(), c.port(), c.profile(),
          connectTimeout, proxyOpts);
      r.setReshareCap(c.reshareCap() != null ? c.reshareCap() : 0);
      r.setCardDiscoveryService(discovery);
      return r;
    } else if ("cs378x".equals(proto)) {
      if (c.passwordRef() == null || c.passwordRef().isBlank()) throw new IllegalStateException("passwordRef required");
      String password = secretResolver.resolveSync(c.passwordRef());
      String username = c.username() != null ? c.username() : "";
      int cacheexMode = c.cacheexMode() != null ? c.cacheexMode() : 0;
      int cacheexMaxHop = c.cacheexMaxHop() != null ? c.cacheexMaxHop() : 10;
      int cacheexMaxSend = (c.cacheexMaxSend() != null && c.cacheexMaxSend() > 0)
          ? c.cacheexMaxSend() : com.bowman.cspng.connectors.Camd35TcpConnector.DEFAULT_MAX_SEND_QUEUE;
      var c35 = connectors.addCamd35TcpConnector(c.name(), c.host(), c.port(),
          username, password, c.profile(), cacheexMode, cacheexMaxHop,
          connectTimeout, proxyOpts, c.cacheexFilter(), cacheexMaxSend, cacheExStats);
      c35.setReshareCap(c.reshareCap() != null ? c.reshareCap() : 0);
      c35.setCardDiscoveryService(discovery);
      c35.setCacheExBridge(cacheExBridge);
      wireCwCycle(c35);
      wirePeerQuality(c35);
      if (cacheexMode == 3) {
        // (Enregistrement de la paire fait par le connecteur lui-même au start.)
        cacheExBridge.registerEmitter(c.name(), c35);
      }
      return c35;
    }
    throw new IllegalStateException("unsupported upstream protocol: " + proto);
  }

  private AbstractConnector.NetworkProxyOptions proxyOptions(RelayWorkerContract.ConnectorView c,
                                                             Map<String, RelayWorkerContract.ProxyView> proxies) {
    if (c.proxyName() == null || c.proxyName().isBlank()) return null;
    var px = proxies.get(c.proxyName());
    if (px == null || !px.enabled()) throw new IllegalStateException("unknown/disabled proxy");
    String user = null;
    String pass = null;
    if (px.username() != null && !px.username().isBlank()) {
      if (px.passwordRef() == null || px.passwordRef().isBlank()) throw new IllegalStateException("proxy auth incomplete");
      pass = secretResolver.resolveSync(px.passwordRef());
      user = px.username();
    }
    return new AbstractConnector.NetworkProxyOptions(px.host(), px.port(), user, pass);
  }

  private static int clamp(int t) {
    if (t < 1000) return 1000;
    if (t > 300000) return 300000;
    return t;
  }

  private static void ArraysFill(byte[] b) {
    if (b != null) java.util.Arrays.fill(b, (byte) 0);
  }

  // -- Catalogue publie -> discovery memoire --

  private void seedDiscovery(RelayWorkerContract.DesiredWorkerConfig desired) {
    boolean hasRealClusterConnector = false;
    if (desired.connectors() != null) {
      for (var c : desired.connectors()) {
        if ("cluster".equals(c.name())) {
          hasRealClusterConnector = true;
          break;
        }
      }
    }
    if (!hasRealClusterConnector) {
      discovery.onConnectorConnected("cluster", desired.desiredVersion());
    }
    for (var cap : desired.catalog()) {
      if (!"published".equals(cap.state())) continue;
      discovery.publishClusterCapability(cap.profile(), cap.caid(), cap.provider(), cap.cardId());
      if (!hasRealClusterConnector) {
        discovery.onCardDiscovered("cluster", cap.profile(), cap.caid(), cap.provider(),
            cap.cardId(), 1, 1, CardSource.Trust.MANUAL, 10, null, desired.desiredVersion());
      }
      for (var c : desired.connectors()) {
        if (c.profile() != null && !c.profile().equals(cap.profile())) continue;
        discovery.onConnectorConnected(c.name(), desired.desiredVersion());
        discovery.onCardDiscovered(c.name(), cap.profile(), cap.caid(), cap.provider(),
            cap.cardId(), 1, 1, CardSource.Trust.MANUAL, 10, null, desired.desiredVersion());
      }
    }
  }

  // -- Listeners --

  private void reconcileListeners(RelayWorkerContract.DesiredWorkerConfig desired) throws Exception {
    Set<Long> wanted = new HashSet<>();
    for (var lv : desired.listeners()) {
      wanted.add(lv.listenerId());
      ServedListener existing = listeners.get(lv.listenerId());
      if (existing != null && sameListener(existing.view, lv)) continue;
      closeListener(lv.listenerId());
      openListener(lv);
    }
    for (Long id : new ArrayList<>(listeners.keySet())) {
      if (!wanted.contains(id)) closeListener(id);
    }
  }

  private static boolean sameListener(RelayWorkerContract.ListenerView a,
                                      RelayWorkerContract.ListenerView b) {
    return a.port() == b.port()
        && a.protocol().equals(b.protocol())
        && a.profile().equals(b.profile())
        && String.valueOf(a.bindAddress()).equals(String.valueOf(b.bindAddress()));
  }

  private void openListener(RelayWorkerContract.ListenerView lv) throws Exception {
    NetServerOptions opts = new NetServerOptions();
    opts.setHost(lv.bindAddress() != null ? lv.bindAddress() : "0.0.0.0");
    opts.setPort(lv.port());
    NetServer server = vertx.createNetServer(opts);
    StrictEcmRouter router = new StrictEcmRouter(connectors, null, null, discovery,
        lv.cardMode() != null ? lv.cardMode() : "manual");
    router.setAdaptiveRoutingEngine(adaptiveRoutingEngine);
    router.setDcwPolicySupplier(state::dcwPolicy);
    router.setRelayPolicySupplier(state::policy, lv.listenerId());
    router.setRelayStores(state.stores());
    router.setExternalConsult(this::externalConsult);
    router.setCacheFeedListener(this::onCacheFeed);
    wireCwCycle(router);
    wirePeerQuality(router);
    cacheExBridge.attachRouter(lv.listenerId(), lv.profile(), router);
    ServedListener sl = new ServedListener();
    sl.server = server;
    sl.router = router;
    sl.view = lv;
    List<String> allow = parseList(lv.allowList());
    List<String> deny = parseList(lv.denyList());
    final ServedListener ref = sl;
    server.connectHandler(socket -> {
      String remoteIp = socket.remoteAddress() != null ? socket.remoteAddress().host() : null;
      if (!isIpAllowed(remoteIp, allow, deny)) {
        AuthAudit.preauthReject(remoteIp, "worker-listener-acl");
        socket.close();
        return;
      }
      if (!registry.tryAcquirePreauthIp(remoteIp, lv.maxPerIp())) {
        socket.close();
        return;
      }
      AbstractSession.PreauthLease lease = new AbstractSession.PreauthLease(registry, remoteIp);
      try {
        if ("cccam".equals(lv.protocol())) {
          CcCamSession session = new CcCamSession(vertx, null, registry, auth,
              secretResolver, router, discovery, null,
              lv.cardMode(), 0,
              lv.maxCards() > 0 ? lv.maxCards() : 512,
              lv.maxInflight() > 0 ? lv.maxInflight() : 32,
              lv.maxPerIp() > 0 ? lv.maxPerIp() : 5,
              lv.idleTimeoutSec() > 0 ? lv.idleTimeoutSec() : 60,
              cacheExStats);
          session.setProfileName(lv.profile());
          session.setListenerId(lv.listenerId());
          session.setCacheExBridge(cacheExBridge);
          wireCwCycle(session);
          wirePeerQuality(session);
          session.setPreauthLease(lease);
          ref.sessions.add(session);
          session.addCloseHook(() -> ref.sessions.remove(session));
          session.accept(socket);
        } else if ("cs378x".equals(lv.protocol())) {
          Camd35TcpSession session = new Camd35TcpSession(vertx, null, registry, auth,
              secretResolver, router,
              lv.maxInflight() > 0 ? lv.maxInflight() : 32,
              lv.maxPerIp() > 0 ? lv.maxPerIp() : 5,
              lv.idleTimeoutSec() > 0 ? lv.idleTimeoutSec() : 60,
              cacheExStats);
          session.setProfileName(lv.profile());
          session.setListenerId(lv.listenerId());
          session.setCacheExBridge(cacheExBridge);
          wireCwCycle(session);
          wirePeerQuality(session);
          session.setPreauthLease(lease);
          ref.sessions.add(session);
          session.addCloseHook(() -> ref.sessions.remove(session));
          session.accept(socket);
        } else {
          lease.release();
          socket.close();
        }
      } catch (Exception ex) {
        lease.release();
        try {
          socket.close();
        } catch (Exception ignored) {
        }
      }
    });
    try {
      server.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      try {
        server.close();
      } catch (Exception ignored) {
      }
      throw new IllegalStateException("Worker listen failed on port " + lv.port());
    }
    listeners.put(lv.listenerId(), sl);
    LOG.info("Worker serving " + lv.protocol() + " on port " + lv.port());
  }

  private void closeListener(long id) {
    ServedListener sl = listeners.remove(id);
    if (sl == null) return;
    for (AbstractSession s : new ArrayList<>(sl.sessions)) {
      try {
        s.close();
      } catch (Exception ignored) {
      }
    }
    sl.sessions.clear();
    cacheExBridge.detachRouter(id);
    // Purge inflight du routeur (pas de repeuplement post-detachement).
    try {
      sl.router.cache().clear();
    } catch (Exception ignored) {
    }
    try {
      if (io.vertx.core.Context.isOnEventLoopThread()) sl.server.close();
      else sl.server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
    LOG.info("Worker listener closed: " + id);
  }

  private static List<String> parseList(String s) {
    if (s == null || s.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    for (String part : s.split(",")) {
      String t = part.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  /**
   * Consultation externe (routeur) : caches udp via echange allowliste, caches
   * node via pont CSPv2 cible, dans l'ordre deterministe de la policy.
   * Premier succes gagne ; echec ferme sinon (le dispatch amont tourne en
   * concurrence). Jamais de confusion local/distant : le resultat porte son
   * cache d'origine.
   */
  private Future<StrictEcmRouter.ExternalReply> externalConsult(
      long listenerId,
      com.bowman.cspng.domain.EcmRequest request,
      ProtocolAuthResolver.AuthResult authContext,
      int provider,
      Set<String> eligible,
      java.util.List<String> consultable) {
    RelayCachePolicy pol = state.policy();
    if (pol == null) return Future.failedFuture("no policy");
    io.vertx.core.Promise<StrictEcmRouter.ExternalReply> p = io.vertx.core.Promise.promise();
    consultNext(pol, listenerId, request, authContext, provider, eligible,
        new java.util.ArrayList<>(consultable), p);
    return p.future();
  }

  private void consultNext(RelayCachePolicy pol,
                           long listenerId,
                           com.bowman.cspng.domain.EcmRequest request,
                           ProtocolAuthResolver.AuthResult authContext,
                           int provider,
                           Set<String> eligible,
                           java.util.List<String> remaining,
                           io.vertx.core.Promise<StrictEcmRouter.ExternalReply> p) {
    if (remaining.isEmpty()) {
      p.fail("no external cache answered");
      return;
    }
    String cn = remaining.remove(0);
    var cfg = pol.cache(cn);
    if (cfg == null || !"udp".equals(cfg.kind()) && !"node".equals(cfg.kind())) {
      consultNext(pol, listenerId, request, authContext, provider, eligible, remaining, p);
      return;
    }
    long epoch = 0L;
    try {
      epoch = authContext.epoch();
    } catch (Exception ignored) {
    }
    Future<com.bowman.cspng.domain.CwReply> f;
    if ("udp".equals(cfg.kind())) {
      WorkerUdpExchange ex = udpExchange;
      if (ex == null) {
        consultNext(pol, listenerId, request, authContext, provider, eligible, remaining, p);
        return;
      }
      f = ex.consult(request, eligible, cn, listenerId, epoch, provider,
          request.maxWaitMs() > 0 ? request.maxWaitMs() : 5000L);
    } else {
      WorkerCspV2Bridge bridge = attachedBridge;
      if (bridge == null || cfg.nodeName() == null) {
        consultNext(pol, listenerId, request, authContext, provider, eligible, remaining, p);
        return;
      }
      f = bridge.consultNode(cfg.nodeName(), request,
          request.maxWaitMs() > 0 ? request.maxWaitMs() : 5000L);
    }
    f.onComplete(ar -> {
      if (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) {
        p.tryComplete(new StrictEcmRouter.ExternalReply(cn, ar.result()));
      } else {
        if (ar.failed()) {
          LOG.warning("External consult on cache " + cn + " failed: " + ar.cause().getMessage());
        }
        consultNext(pol, listenerId, request, authContext, provider, eligible, remaining, p);
      }
    });
  }

  /**
   * Feed dispatch local : export UDP uniquement (les compteurs par connecteur
   * viennent des stats dispatch des routeurs, jamais d'un compteur ad hoc).
   * Ne se declenche que sur le chemin dispatch (jamais sur feed distant).
   */
  private void onCacheFeed(long listenerId, String cacheName,
                           com.bowman.cspng.connectors.routing.StrictCacheKey key,
                           com.bowman.cspng.domain.CwReply reply) {
    try {
      WorkerUdpExchange ex = udpExchange;
      if (ex != null) ex.onLocalFeed(listenerId, cacheName, reply);
    } catch (Exception ignored) {
    }
    // Cacheex : évalue les paires d'émission (connecteurs mode 3, sessions mode 2).
    // Idempotent via dédup par cible (pas de double push) ; jamais de reflet source.
    try {
      cacheExBridge.onLocalFeed(reply);
    } catch (Exception ignored) {
    }
  }

  /** Stats worker->central : compteurs reels, latences mesurees, tailles bornees. */
  public RelayWorkerContract.WorkerStatsView collectStats() {
    // Fusion des stats dispatch de tous les routeurs listeners (par nom).
    java.util.Map<String, long[]> merged = new java.util.TreeMap<>();
    java.util.Map<String, long[]> latSum = new java.util.TreeMap<>();
    for (ServedListener sl : listeners.values()) {
      try {
        for (var snap : sl.router.dispatchStats().snapshot()) {
          long[] acc = merged.computeIfAbsent(snap.name(), k -> new long[3]);
          acc[0] += snap.ok();
          acc[1] += snap.fail();
          acc[2] += snap.timeout();
          long[] lat = latSum.computeIfAbsent(snap.name(), k -> new long[2]);
          lat[0] += snap.latSumMs();
          lat[1] += snap.latCount();
        }
      } catch (Exception ignored) {
      }
    }
    List<RelayWorkerContract.ConnectorStatsView> conns = new ArrayList<>();
    for (String name : new java.util.TreeSet<>(appliedConnectorSpecs.keySet())) {
      boolean online = false;
      try {
        var c = connectors.getConnector(name);
        online = c != null && c.isConnected();
      } catch (Exception ignored) {
      }
      long[] acc = merged.getOrDefault(name, new long[3]);
      long[] lat = latSum.getOrDefault(name, new long[2]);
      conns.add(new RelayWorkerContract.ConnectorStatsView(
          name, online, acc[0], acc[1], acc[2],
          lat[1] > 0 ? lat[0] / lat[1] : null));
      if (conns.size() >= 64) break;
    }
    RelayWorkerContract.UdpStatsView udp = null;
    WorkerUdpExchange ex = udpExchange;
    if (ex != null) {
      var s = ex.stats();
      udp = new RelayWorkerContract.UdpStatsView(
          s.sentPending(), s.sentEntries(), s.receivedPending(), s.receivedEntries(),
          s.notAllowlisted() + ex.droppedUnsolicited(), s.rateLimited(), ex.avgRttMs());
    }
    List<RelayWorkerContract.CacheStatsView> caches = new ArrayList<>();
    for (String cn : cacheNames) {
      try {
        if (state.stores().hasStats(cn)) {
          caches.add(new RelayWorkerContract.CacheStatsView(
              cn, state.stores().consultHits(cn), state.stores().feedPuts(cn)));
        }
      } catch (Exception ignored) {
      }
      if (caches.size() >= 64) break;
    }
    List<RelayWorkerContract.NodeStatsView> nodes = new ArrayList<>();
    WorkerCspV2Bridge bridge = attachedBridge;
    RelayCachePolicy pol = state.policy();
    if (bridge != null && pol != null) {
      for (String cn : cacheNames) {
        try {
          var cfg = pol.cache(cn);
          if (cfg == null || !"node".equals(cfg.kind()) || cfg.nodeName() == null) continue;
          nodes.add(new RelayWorkerContract.NodeStatsView(
              cfg.nodeName(), pol.isNodeUsable(cfg.nodeName()),
              bridge.nodeStats().consultOk(cfg.nodeName()),
              bridge.nodeStats().avgRttMs(cfg.nodeName())));
        } catch (Exception ignored) {
        }
        if (nodes.size() >= 64) break;
      }
    }
    List<RelayWorkerContract.CacheExPairStatsView> cxpairs = new ArrayList<>();
    try {
      for (var ps : cacheExStats.snapshot()) {
        cxpairs.add(new RelayWorkerContract.CacheExPairStatsView(
            ps.peerId(), ps.direction(), ps.protocol(), ps.mode(), ps.profile(),
            ps.online(), ps.state(), ps.maxHop(), ps.maxSendQueue(), ps.filterActive(),
            ps.pushedCw(), ps.receivedCw(), ps.droppedCw(), ps.dropReasons(),
            ps.lastPushAtMs(), ps.lastReceiveAtMs()));
        if (cxpairs.size() >= 64) break;
      }
    } catch (Exception ignored) {
    }
    RelayWorkerContract.CwCycleStatsView cwStats = null;
    try {
      com.bowman.cspng.filters.CwCycleChecker chk = cwCycleChecker;
      if (chk != null) {
        cwStats = new RelayWorkerContract.CwCycleStatsView(
            chk.enabled(), chk.trackedEntries(), chk.checks(), chk.badCycles(),
            chk.droppedCount(), chk.loggedCount(), chk.ignoredCount());
      }
    } catch (Exception ignored) {
    }
    List<RelayWorkerContract.PeerQualityStatView> pqViews = new ArrayList<>();
    try {
      var qcfg = state.quarantine();
      for (var q : peerQualityTracker.snapshot()) {
        boolean isQ = qcfg != null && qcfg.enabled() && "enforce".equalsIgnoreCase(qcfg.mode())
            && peerQuarantine.shouldSkip(q.peerId(), qcfg);
        pqViews.add(new RelayWorkerContract.PeerQualityStatView(
            q.peerId(), q.accepted(), q.invalid(), q.total(), q.invalidRatioBps(), q.windowMs(), isQ));
        if (pqViews.size() >= 64) break;
      }
    } catch (Exception ignored) {
    }
    List<RelayWorkerContract.CacheHitOriginStatView> hitViews = new ArrayList<>();
    try {
      for (var h : cacheHitAttribution.snapshot()) {
        hitViews.add(new RelayWorkerContract.CacheHitOriginStatView(h.origin(), h.hits(), h.lastHitAtMs()));
        if (hitViews.size() >= 64) break;
      }
    } catch (Exception ignored) {
    }
    List<RelayWorkerContract.PeerQuarantineStatView> qViews = new ArrayList<>();
    try {
      for (var q : peerQuarantine.snapshot()) {
        qViews.add(new RelayWorkerContract.PeerQuarantineStatView(
            q.peerId(), q.reason(), q.quarantinedAtMs(), q.expiresAtMs(), q.observedOnly()));
        if (qViews.size() >= 64) break;
      }
    } catch (Exception ignored) {
    }
    RelayWorkerContract.QuorumStatsView qmView = null;
    try {
      var qs = quorumTracker.stats();
      if (qs != null && (qs.keys() > 0 || qs.observed() > 0 || qs.quorum() > 0)) {
        qmView = new RelayWorkerContract.QuorumStatsView(
            qs.keys(), qs.observed(), qs.quorum(), qs.pending(), qs.mismatch(), qs.expired());
      }
    } catch (Exception ignored) {
    }
    return new RelayWorkerContract.WorkerStatsView(
        conns, udp, caches, nodes, cxpairs, cwStats, pqViews, hitViews, qViews, qmView,
        System.currentTimeMillis());
  }

  static boolean isIpAllowed(String remoteIp, List<String> allow, List<String> deny) {
    if (remoteIp == null) return false;
    for (String m : deny) if (IpMatcher.isAllowed(remoteIp, m)) return false;
    if (allow.isEmpty()) return true;
    for (String m : allow) if (IpMatcher.isAllowed(remoteIp, m)) return true;
    return false;
  }

  // -- CSPv2 --

  public synchronized void startCspV2(int port, String certFile, String keyFile, String trustFile,
                                      StrictEcmRouter uplinkRouter) {
    stopCspV2();
    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge(vertx, nodeId, port,
        certFile, keyFile, trustFile, uplinkRouter);
    bridge.start();
    this.cspv2 = bridge;
  }

  public synchronized void stopCspV2() {
    if (cspv2 != null) {
      try {
        cspv2.stop();
      } catch (Exception ignored) {
      }
      cspv2 = null;
    }
    if (attachedBridge != null) {
      try {
        attachedBridge.stop();
      } catch (Exception ignored) {
      }
    }
  }

  // -- Fail-closed effectif --

  /** Revocation/expiry : purge stores+inflight, ferme sessions/listeners/pont. */
  public synchronized void failClosed(String reason) {
    LOG.warning("Worker fail-closed (" + reason + ")");
    stopCspV2();
    WorkerUdpExchange ex = udpExchange;
    udpExchange = null;
    if (ex != null) {
      try {
        ex.failClosed();
      } catch (Exception ignored) {
      }
    }
    for (Long id : new ArrayList<>(listeners.keySet())) closeListener(id);
    try {
      connectors.stopAll();
    } catch (Exception ignored) {
    }
    try {
      state.failClosed();
    } catch (Exception ignored) {
    }
    appliedVersion = -1L;
    appliedConnectorSpecs.clear();
  }

  @Override
  public void close() {
    failClosed("close");
    if (julHandler != null) {
      try {
        java.util.logging.Logger.getLogger("").removeHandler(julHandler);
      } catch (Exception ignored) {}
    }
    try {
      connectors.stopAll();
    } catch (Exception ignored) {
    }
  }
}
