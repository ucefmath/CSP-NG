package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cspv2.CspV2Constants;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manager réseau CSPv2 pur wire — single event-loop, aucun CDI.
 */
public final class CspV2Manager {

  static final Logger LOG = Logger.getLogger(CspV2Manager.class.getName());

  final Vertx vertx;
  final Context context;
  final CspV2Settings settings;
  final CspV2ConnectionListener appListener;

  NetServer server;
  NetClient client;

  volatile boolean running;
  private volatile boolean listening;
  private volatile int actualPort = -1;

  int globalCount;
  final Map<String, Integer> perNodeCount;
  final Map<String, ConnectionHandle> establishedByPeer;
  final Map<ConnectionHandle, ConnectionTracker> trackers;

  final Set<String> dialingPeers;
  final Map<String, Long> reconnectTimers;
  final Map<String, CspV2ReconnectPolicy> reconnectPolicies;

  final Map<String, Integer> profileCursors;

  private volatile CspV2ManagerSnapshot snapshot;

  private Promise<Void> stopPromise;
  long shutdownTimerId = -1;
  boolean resourcesClosing;
  boolean resourcesClosed;
  long resourceCloseTimerId = -1;

  // -----------------------------------------------------------------------
  static final class ConnectionTracker {
    final String peerNodeId;
    final CspV2ConnectionRole role;
    boolean released;
    boolean established;
    boolean duplicate;

    ConnectionTracker(String peerNodeId, CspV2ConnectionRole role) {
      this.peerNodeId = peerNodeId; this.role = role;
    }
  }

  public record CspV2ManagerSnapshot(boolean running, boolean listening,
      int activeConnections, int establishedConnections,
      int reconnectScheduled, int actualPort) {}

  CspV2Manager(Vertx vertx, Context context, CspV2Settings settings,
               CspV2ConnectionListener appListener) {
    this.vertx = vertx; this.context = context;
    this.settings = settings; this.appListener = appListener;
    this.perNodeCount = new HashMap<>();
    this.establishedByPeer = new HashMap<>();
    this.trackers = new LinkedHashMap<>();
    this.dialingPeers = new HashSet<>();
    this.reconnectTimers = new HashMap<>();
    this.reconnectPolicies = new HashMap<>();
    this.profileCursors = new HashMap<>();
    this.snapshot = new CspV2ManagerSnapshot(false, false, 0, 0, 0, -1);
  }

  // -----------------------------------------------------------------------
  // Context helpers
  // -----------------------------------------------------------------------
  boolean onManagerContext() { return Vertx.currentContext() == context; }

  void executeOnManagerContext(Runnable action) {
    if (onManagerContext()) action.run();
    else context.runOnContext(v -> action.run());
  }

  <T> Future<T> marshalToContext(java.util.function.Supplier<Future<T>> fn) {
    if (onManagerContext()) return fn.get();
    Promise<T> p = Promise.promise();
    context.runOnContext(v -> fn.get().onComplete(p));
    return p.future();
  }

  // -----------------------------------------------------------------------
  // Factory
  // -----------------------------------------------------------------------
  public static Future<CspV2Manager> start(Vertx vertx, CspV2Settings settings,
                                           CspV2TlsMaterial material,
                                           CspV2ConnectionListener listener) {
    Objects.requireNonNull(vertx, "vertx");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(material, "material");
    Objects.requireNonNull(listener, "listener");
    if (!settings.enabled())
      return Future.failedFuture(new IllegalArgumentException("settings disabled"));

    Context ctx = vertx.getOrCreateContext();
    if (ctx == null || !ctx.isEventLoopContext()) {
      return Future.failedFuture(new CspV2ConnectionException("no event-loop context"));
    }
    Promise<CspV2Manager> promise = Promise.promise();
    ctx.runOnContext(v -> doStart(vertx, Vertx.currentContext(), settings, material, listener, promise));
    return promise.future();
  }

  private static void doStart(Vertx vertx, Context ctx, CspV2Settings settings,
                              CspV2TlsMaterial material,
                              CspV2ConnectionListener listener,
                              Promise<CspV2Manager> promise) {
    NetServerOptions serverOpts;
    NetClientOptions clientOpts;
    try {
      serverOpts = CspV2TlsOptionsFactory.serverOptions(settings, material);
      clientOpts = CspV2TlsOptionsFactory.clientOptions(settings, material);
    } catch (Exception e) { promise.fail(e); return; }
    serverOpts.setAcceptBacklog(Math.max(1, settings.maxConnections()));

    CspV2Manager mgr = new CspV2Manager(vertx, ctx, settings, listener);
    NetServer srv;
    NetClient cli;
    try {
      srv = vertx.createNetServer(serverOpts);
      cli = vertx.createNetClient(clientOpts);
    } catch (Exception e) { promise.fail(e); return; }
    mgr.server = srv;
    mgr.client = cli;
    mgr.running = true;

    srv.connectHandler(socket -> mgr.executeOnManagerContext(() -> mgr.onAccept(socket)));

    srv.listen(settings.listenPort(), settings.listenHost())
        .onComplete(ar -> mgr.executeOnManagerContext(() -> {
          if (ar.failed()) {
            mgr.running = false;
            Future<Void> cf = cli != null ? cli.close() : Future.succeededFuture();
            Future<Void> sf = srv != null ? srv.close() : Future.succeededFuture();
            Future.all(cf, sf).onComplete(ignored -> promise.fail(
                new CspV2ConnectionException("listen failed", ar.cause())));
            return;
          }
          mgr.listening = true;
          mgr.actualPort = srv.actualPort();
          mgr.updateSnapshot();
          for (CspV2PeerSettings peer : settings.peers()) {
            if (peer.enabled() && settings.shouldInitiate(peer))
              mgr.scheduleConnect(peer);
          }
          promise.complete(mgr);
        }));
  }

  // -----------------------------------------------------------------------
  // Accepteur
  // -----------------------------------------------------------------------
  void onAccept(NetSocket socket) {
    if (!running) { closeSocket(socket); return; }
    String peerNodeId;
    try { peerNodeId = CspV2Identity.extractNodeId(socketPeerCerts(socket)); }
    catch (Exception e) { closeSocket(socket); return; }
    CspV2PeerSettings peer = findEnabledPeer(peerNodeId);
    if (peer == null) { closeSocket(socket); return; }
    if (!reserve(peerNodeId)) { closeSocket(socket); return; }

    ConnectionTracker tracker = new ConnectionTracker(peerNodeId, CspV2ConnectionRole.ACCEPTOR);
    PeerListener pl = new PeerListener(peerNodeId, tracker, this);
    CspV2Connection conn;
    try { conn = CspV2Connection.open(vertx, socket, settings, pl, CspV2ConnectionRole.ACCEPTOR); }
    catch (Exception e) { release(peerNodeId); closeSocket(socket); return; }
    pl.bind(new ConnectionHandle.RealHandle(conn));
  }

  // -----------------------------------------------------------------------
  // Initiateur
  // -----------------------------------------------------------------------
  void scheduleConnect(CspV2PeerSettings peer) {
    if (!running) return;
    if (!peer.dialEnabled()) return; // accept-only: aucun dial sortant
    String nid = peer.nodeId();
    if (hasActiveEstablished(nid) || dialingPeers.contains(nid) || reconnectTimers.containsKey(nid)) return;
    doConnect(peer);
  }

  void scheduleReconnect(CspV2PeerSettings peer) {
    if (!running) return;
    if (!peer.dialEnabled()) return; // accept-only: aucun reconnect sortant
    String nid = peer.nodeId();
    if (hasActiveEstablished(nid) || dialingPeers.contains(nid) || reconnectTimers.containsKey(nid)) return;

    CspV2ReconnectPolicy policy = reconnectPolicies.computeIfAbsent(nid,
        k -> CspV2ReconnectPolicy.create(settings.reconnectInitialMs(), settings.reconnectMaxMs()));
    long delay = policy.nextDelayMs();

    long timerId = vertx.setTimer(delay, id ->
      executeOnManagerContext(() -> {
        reconnectTimers.remove(nid, id);
        if (!running || hasActiveEstablished(nid)) return;
        doConnect(peer);
      }));
    reconnectTimers.put(nid, timerId);
    updateSnapshot();
  }

  void doConnect(CspV2PeerSettings peer) {
    if (!running || client == null) return;
    if (!peer.dialEnabled()) return; // accept-only: aucun dial sortant
    String nid = peer.nodeId();
    if (hasActiveEstablished(nid)) return;
    // add-if-absent : si déjà dialing, skip
    if (!dialingPeers.add(nid)) return;

    client.connect(peer.port(), peer.host(), peer.host())
        .onComplete(ar -> executeOnManagerContext(() -> {
          dialingPeers.remove(nid);
          if (!running) { if (ar.succeeded()) closeSocket(ar.result()); return; }
          if (ar.failed()) { scheduleReconnect(peer); return; }

          NetSocket socket = ar.result();
          if (hasActiveEstablished(nid)) { closeSocket(socket); return; }
          if (!reserve(nid)) { closeSocket(socket); scheduleReconnect(peer); return; }

          ConnectionTracker tracker = new ConnectionTracker(nid, CspV2ConnectionRole.INITIATOR);
          PeerListener pl = new PeerListener(nid, tracker, this);
          CspV2Connection conn;
          try { conn = CspV2Connection.open(vertx, socket, settings, pl, CspV2ConnectionRole.INITIATOR); }
          catch (Exception e) { release(nid); closeSocket(socket); scheduleReconnect(peer); return; }
          pl.bind(new ConnectionHandle.RealHandle(conn));
        }));
  }

  // -----------------------------------------------------------------------
  // CspV2PeerReply
  // -----------------------------------------------------------------------

  /**
   * Réponse d'un pair spécifique avec son nodeId.
   * Validé non-null et pattern nodeId au constructeur.
   */
  public record CspV2PeerReply(String peerNodeId, CspV2Payload.DcwReply reply, boolean checksumPolicyNegotiated) {
    public CspV2PeerReply(String peerNodeId, CspV2Payload.DcwReply reply) {
      this(peerNodeId, reply, false);
    }

    public CspV2PeerReply {
      Objects.requireNonNull(reply, "reply");
      if (peerNodeId == null || peerNodeId.isBlank()
          || !CspV2Constants.NODE_ID_PATTERN.matcher(peerNodeId).matches()) {
        throw new IllegalArgumentException("invalid peerNodeId");
      }
    }
  }

  // -----------------------------------------------------------------------
  // Outbound request
  // -----------------------------------------------------------------------

  /**
   * Envoie une requête ECM vers un pair et retourne la réponse avec le nodeId du pair.
   * Round-robin par profil, candidats triés par nodeId.
   */
  public Future<CspV2PeerReply> requestWithPeer(CspV2Payload.EcmRequest req) {
    Objects.requireNonNull(req, "req");
    return marshalToContext(() -> doRequestWithPeer(req));
  }

  /** Compat : retourne seulement la réponse DcwReply. */
  public Future<CspV2Payload.DcwReply> request(CspV2Payload.EcmRequest req) {
    return requestWithPeer(req).map(CspV2PeerReply::reply);
  }

  Future<CspV2PeerReply> doRequestWithPeer(CspV2Payload.EcmRequest req) {
    String profile = req.profile();
    List<ConnectionHandle> candidates = new ArrayList<>();
    for (Map.Entry<String, ConnectionHandle> e : establishedByPeer.entrySet()) {
      ConnectionHandle h = e.getValue();
      if (h == null || h.isClosed() || h.state() != CspV2ConnectionState.ESTABLISHED) continue;
      CspV2PeerSettings p = findEnabledPeer(e.getKey());
      if (p != null && p.profiles().contains(profile)) candidates.add(h);
    }
    if (candidates.isEmpty())
      return Future.failedFuture(new CspV2ConnectionException("no peer available"));

    candidates.sort(Comparator.comparing(ConnectionHandle::peerNodeId));
    int cursor = profileCursors.getOrDefault(profile, 0);
    if (cursor < 0) cursor = 0;
    int index = cursor % candidates.size();
    cursor++;
    if (cursor < 0) cursor = 0;
    profileCursors.put(profile, cursor);
    ConnectionHandle selected = candidates.get(index);
    String peerNodeId = selected.peerNodeId();
    return selected.request(req).map(reply -> new CspV2PeerReply(peerNodeId, reply, selected.supportsChecksumPolicy()));
  }

  /** Package-private : compat tests existants (retourne Future<DcwReply>). */
  Future<CspV2Payload.DcwReply> doRequest(CspV2Payload.EcmRequest req) {
    return doRequestWithPeer(req).map(CspV2PeerReply::reply);
  }

  /**
   * Consultation ciblee d'un node nomme (worker : cache kind=node).
   * Echec ferme si pas de connexion ETABLIE avec ce node exact
   * (jamais de fallback vers un autre node : pas de confusion de provenance).
   */
  public Future<CspV2PeerReply> requestFromPeer(String nodeId, CspV2Payload.EcmRequest req) {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(req, "req");
    return marshalToContext(() -> {
      ConnectionHandle h = establishedByPeer.get(nodeId);
      if (h == null || h.isClosed() || h.state() != CspV2ConnectionState.ESTABLISHED)
        return Future.failedFuture(new CspV2ConnectionException("no established peer: " + nodeId));
      CspV2PeerSettings p = findEnabledPeer(nodeId);
      if (p == null || !p.profiles().contains(req.profile()))
        return Future.failedFuture(new CspV2ConnectionException("peer not allowed: " + nodeId));
      return h.request(req).map(reply -> new CspV2PeerReply(nodeId, reply, h.supportsChecksumPolicy()));
    });
  }

  /** Flag de la connexion établie avec ce node. Faux si absente ou non négociée. */
  public boolean peerChecksumNegotiated(String nodeId) {
    if (nodeId == null) return false;
    ConnectionHandle h = establishedByPeer.get(nodeId);
    return h != null && !h.isClosed() && h.supportsChecksumPolicy();
  }

  // -----------------------------------------------------------------------
  // Stop
  // -----------------------------------------------------------------------
  public Future<Void> stop() {
    if (onManagerContext()) return doStop();
    Promise<Void> p = Promise.promise();
    context.runOnContext(v -> doStop().onComplete(p));
    return p.future();
  }

  Future<Void> doStop() {
    if (stopPromise != null) return stopPromise.future();
    stopPromise = Promise.promise();
    running = false;
    updateSnapshot();

    for (Long tid : new ArrayList<>(reconnectTimers.values())) vertx.cancelTimer(tid);
    reconnectTimers.clear();
    dialingPeers.clear();

    List<ConnectionHandle> handles = new ArrayList<>(trackers.keySet());
    if (handles.isEmpty()) { closeResources(); return stopPromise.future(); }

    for (ConnectionHandle h : handles) {
      try { h.drain(); } catch (Exception ignore) {}
    }

    shutdownTimerId = vertx.setTimer(settings.shutdownDrainMs(), tid ->
      executeOnManagerContext(() -> {
        if (resourcesClosed) return;
        // Force close handles
        for (ConnectionHandle h : new ArrayList<>(trackers.keySet())) {
          try { h.close(); } catch (Exception ignore) {}
        }
        // Fallback: release all remaining trackers + clear established
        forceReleaseAll();
        closeResources();
      }));

    maybeFinishStop();
    return stopPromise.future();
  }

  void maybeFinishStop() {
    if (stopPromise == null || resourcesClosed) return;
    if (trackers.isEmpty()) {
      if (shutdownTimerId != -1) { vertx.cancelTimer(shutdownTimerId); shutdownTimerId = -1; }
      closeResources();
    }
  }

  private void closeResources() {
    if (resourcesClosing || resourcesClosed) return;
    resourcesClosing = true;

    Future<Void> f1 = (server != null) ? server.close() : Future.succeededFuture();
    Future<Void> f2 = (client != null) ? client.close() : Future.succeededFuture();

    Future.all(f1, f2).onComplete(ar ->
      executeOnManagerContext(() -> {
        if (resourceCloseTimerId != -1) { vertx.cancelTimer(resourceCloseTimerId); resourceCloseTimerId = -1; }
        resourcesClosed = true;
        resourcesClosing = false;
        listening = false;
        if (shutdownTimerId != -1) { vertx.cancelTimer(shutdownTimerId); shutdownTimerId = -1; }
        updateSnapshot();
        if (stopPromise != null) stopPromise.tryComplete();
      }));

    resourceCloseTimerId = vertx.setTimer(settings.drainMs(), tid ->
      executeOnManagerContext(() -> {
        if (!resourcesClosed) {
          if (resourceCloseTimerId != -1 && resourceCloseTimerId == tid) resourceCloseTimerId = -1;
          resourcesClosed = true;
          resourcesClosing = false;
          listening = false;
          if (shutdownTimerId != -1) { vertx.cancelTimer(shutdownTimerId); shutdownTimerId = -1; }
          updateSnapshot();
          if (stopPromise != null) stopPromise.tryComplete();
        }
      }));
  }

  /** Force release all trackers and cleanup maps (used in shutdown fallback). */
  void forceReleaseAll() {
    // Release all unreleased trackers
    for (ConnectionTracker t : new ArrayList<>(trackers.values())) {
      if (!t.released) { t.released = true; release(t.peerNodeId); }
    }
    // Remove established entries associated with tracked handles
    for (ConnectionHandle h : trackers.keySet()) {
      establishedByPeer.remove(h.peerNodeId(), h);
    }
    trackers.clear();
    updateSnapshot();
  }

  // -----------------------------------------------------------------------
  // Réservation
  // -----------------------------------------------------------------------
  boolean reserve(String peerNodeId) {
    if (globalCount >= settings.maxConnections()) return false;
    int n = perNodeCount.getOrDefault(peerNodeId, 0);
    if (n >= settings.maxConnectionsPerNode()) return false;
    globalCount++;
    perNodeCount.put(peerNodeId, n + 1);
    updateSnapshot();
    return true;
  }

  void release(String peerNodeId) {
    if (globalCount <= 0) globalCount = 0;
    else globalCount--;
    perNodeCount.compute(peerNodeId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
    updateSnapshot();
  }

  void releaseTracker(ConnectionTracker tracker, ConnectionHandle handle) {
    if (tracker.released) return;
    tracker.released = true;
    trackers.remove(handle);
    release(tracker.peerNodeId);
    maybeFinishStop();
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------
  boolean hasActiveEstablished(String peerNodeId) {
    ConnectionHandle h = establishedByPeer.get(peerNodeId);
    return h != null && !h.isClosed() && h.state() == CspV2ConnectionState.ESTABLISHED;
  }

  static List<java.security.cert.Certificate> socketPeerCerts(NetSocket socket) {
    try { return socket.peerCertificates(); } catch (Exception e) { return List.of(); }
  }

  static void closeSocket(NetSocket socket) {
    try { socket.close(); } catch (Exception ignore) {}
  }

  CspV2PeerSettings findEnabledPeer(String nodeId) {
    return settings.peers().stream()
        .filter(p -> p.enabled() && p.nodeId().equals(nodeId))
        .findFirst().orElse(null);
  }

  // -----------------------------------------------------------------------
  // Snapshot
  // -----------------------------------------------------------------------
  public CspV2ManagerSnapshot snapshot() { return snapshot; }

  void updateSnapshot() {
    int estab = 0;
    for (ConnectionHandle h : establishedByPeer.values()) {
      if (h != null && !h.isClosed() && h.state() == CspV2ConnectionState.ESTABLISHED) estab++;
    }
    snapshot = new CspV2ManagerSnapshot(running, listening, globalCount, estab,
        reconnectTimers.size(), actualPort);
  }

  // --- accessors ---
  int globalCount() { return globalCount; }
  Map<String, ConnectionHandle> establishedByPeer() { return Collections.unmodifiableMap(establishedByPeer); }
  int reconnectScheduled() { return reconnectTimers.size(); }
  boolean isRunning() { return running; }
  boolean isListening() { return listening; }
  Context context() { return context; }
  int actualPort() { return actualPort; }
  Set<String> dialingPeers() { return Collections.unmodifiableSet(dialingPeers); }

  // -----------------------------------------------------------------------
  // PeerListener
  // -----------------------------------------------------------------------
  static final class PeerListener implements CspV2ConnectionListener {

    final String peerNodeId;
    final ConnectionTracker tracker;
    final CspV2Manager mgr;
    private ConnectionHandle handle;
    private boolean bound;
    private boolean estabPending, closedPending;
    private String closedReason;

    PeerListener(String peerNodeId, ConnectionTracker tracker, CspV2Manager mgr) {
      this.peerNodeId = peerNodeId; this.tracker = tracker; this.mgr = mgr;
    }

    void bind(ConnectionHandle h) {
      this.handle = h; this.bound = true;
      if (closedPending) doOnClosed(peerNodeId, closedReason);
      else if (!tracker.released) {
        mgr.trackers.put(handle, tracker);
        if (estabPending) doOnEstablished(peerNodeId);
      }
    }

    // --- marshal ---
    private void ensureContext(Runnable action) {
      if (mgr.onManagerContext()) action.run();
      else mgr.context.runOnContext(v -> action.run());
    }

    /** Délègue à l'appListener en attrapant throw et null Future. */
    @Override
    public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
      if (mgr.onManagerContext()) return delegateEcm(peer, req);
      Promise<CspV2Payload.DcwReply> p = Promise.promise();
      mgr.context.runOnContext(v -> delegateEcm(peer, req).onComplete(p));
      return p.future();
    }

    private Future<CspV2Payload.DcwReply> delegateEcm(String peer, CspV2Payload.EcmRequest req) {
      try {
        Future<CspV2Payload.DcwReply> f = mgr.appListener.onEcmRequest(peer, req);
        if (f == null) return Future.failedFuture(new CspV2ConnectionException("null future from listener"));
        return f;
      } catch (Exception e) {
        return Future.failedFuture(new CspV2ConnectionException("listener threw", e));
      }
    }

    @Override
    public void onEstablished(String peer) {
      ensureContext(() -> {
        if (!bound) { estabPending = true; return; }
        if (estabPending) return;
        estabPending = true;
        doOnEstablished(peer);
      });
    }

    private void doOnEstablished(String peer) {
      if (tracker.established) return;
      ConnectionHandle existing = mgr.establishedByPeer.get(peer);
      if (existing != null && existing != handle && !existing.isClosed()
          && existing.state() == CspV2ConnectionState.ESTABLISHED) {
        tracker.duplicate = true;
        if (handle != null) { try { handle.drain(); } catch (Exception e) {} }
        return;
      }
      mgr.establishedByPeer.put(peer, handle);
      tracker.established = true;
      if (tracker.role == CspV2ConnectionRole.INITIATOR) {
        CspV2ReconnectPolicy pol = mgr.reconnectPolicies.get(peer);
        if (pol != null) pol.reset();
      }
      mgr.updateSnapshot();
      try { mgr.appListener.onEstablished(peer); } catch (Exception e) {}
    }

    @Override
    public void onClosed(String peer, String reason) {
      ensureContext(() -> {
        if (!bound) { closedPending = true; closedReason = reason; return; }
        doOnClosed(peer, reason);
      });
    }

    private void doOnClosed(String peer, String reason) {
      mgr.releaseTracker(tracker, handle);
      if (tracker.established) {
        mgr.establishedByPeer.remove(peer, handle);
        try { mgr.appListener.onClosed(peer, reason); } catch (Exception e) {}
      }
      if (tracker.role == CspV2ConnectionRole.INITIATOR && mgr.running
          && !tracker.duplicate && !mgr.hasActiveEstablished(peer)) {
        CspV2PeerSettings ps = mgr.findEnabledPeer(peer);
        if (ps != null) mgr.scheduleReconnect(ps);
      }
      mgr.updateSnapshot();
    }
  }
}
