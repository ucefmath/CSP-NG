package com.bowman.cspng.worker;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.cluster.udp.PeerRegistry;
import com.bowman.cspng.cluster.udp.UdpLegacyTransport;
import com.bowman.cspng.cluster.udp.UdpStats;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.connectors.routing.RelayCachePolicy;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.RelayCacheStores;
import com.bowman.cspng.connectors.routing.StrictCacheKey;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.DcwRuleScope;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.SocketAddress;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Echange cache UDP legacy cote worker (proxy cache inter-noeuds).
 *
 * <p>Regles dures (anti-fuite / anti-boucle) :
 * <ul>
 *   <li>Allowlist stricte : {@code autoAddPeers=false}, pairs statiques issus
 *       du desired uniquement, transport en {@code strictAllowlist} (inconnu
 *       => drop comptabilise, sans reponse — pas d'amplification).</li>
 *   <li>UDP distingue du cache interne : les entrees UDP vivent dans les
 *       stores des caches kind={@code udp} (jamais melangees au local) et
 *       portent l'origine {@code udp:&lt;host&gt;}.</li>
 *   <li>Consultation : emission TYPE_REQUEST uniquement vers les pairs du
 *       cache concerne, avec pending par {@link EcmKey} ; reponse acceptee
 *       seulement si pending vivant + lien feed actif + origine non vide +
 *       checksum valide.</li>
 *   <li>Requetes distantes : servies depuis l'index local (dernieres CW
 *       ecrites par le dispatch, granularite {@link EcmKey} comme le legacy),
 *       <b>jamais re-emises</b> (pas de storm) ; le cache UDP local n'est
 *       jamais reserve (pas d'echo).</li>
 *   <li>Detachement immediat : lien coupe => pendings du cache echoues,
 *       plus d'emission ni de feed pour ce cache.</li>
 * </ul>
 */
public final class WorkerUdpExchange implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(WorkerUdpExchange.class.getName());
  private static final int MAX_PENDING = 2048;
  private static final int MAX_LOCAL_INDEX = 4096;
  private static final long CONSULT_TIMEOUT_CAP_MS = 5000L;

  private final Vertx vertx;
  private final WorkerState state;
  private final UdpStats stats = new UdpStats();

  /**
   * Porte d'ingress (lots 2/4) en usage enregistrement seul — nullable.
   * Le chemin UDP worker valide déjà avec le contexte du pending ; la porte
   * n'y refait aucune décision (pas de quorum sur les consults) : seuls
   * {@code postInjected}/{@code recordContentInvalid} sont appelés, avec
   * l'identité allowlistée vérifiée {@code udp:<host>:<port>}.
   */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate;

  /** Injection de la porte d'ingress (nullable — enregistrement seul). */
  public void setIngressGate(com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate) {
    this.ingressGate = gate;
  }

  private volatile DatagramSocket socket;
  private volatile UdpLegacyTransport transport;
  private volatile int bindPort;

  /** cacheName -> binding (pairs + bornes). */
  private final Map<String, UdpBinding> bindings = new ConcurrentHashMap<>();
  /** Profils servis par ce worker (gating des requetes distantes). */
  private volatile Set<String> servedProfiles = Set.of();

  private static final class Pending {
    final String cacheName;
    final long listenerId;
    final Set<String> eligible;
    final long epoch;
    final int provider;
    final EcmRequest request;
    final Promise<CwReply> promise = Promise.promise();
    final long startMs = System.currentTimeMillis();
    volatile Long timerId;

    Pending(String cacheName, long listenerId, Set<String> eligible,
            long epoch, int provider, EcmRequest request) {
      this.cacheName = cacheName;
      this.listenerId = listenerId;
      this.eligible = eligible != null ? Set.copyOf(eligible) : Set.of();
      this.epoch = epoch;
      this.provider = provider;
      this.request = request;
    }
  }

  private final Map<EcmKey, Pending> pending = new ConcurrentHashMap<>();

  private static final class Indexed {
    final CwReply reply;
    final long maxAgeMs;
    Indexed(CwReply reply, long maxAgeMs) { this.reply = reply; this.maxAgeMs = maxAgeMs; }
  }

  private final Map<EcmKey, Indexed> latestLocal = java.util.Collections.synchronizedMap(
      new LinkedHashMap<>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<EcmKey, Indexed> e) {
          return size() > MAX_LOCAL_INDEX;
        }
      });

  private final AtomicLong rttSumMs = new AtomicLong();
  private final AtomicLong rttCount = new AtomicLong();
  private final AtomicLong droppedUnsolicited = new AtomicLong();

  private static final class UdpBinding {
    final List<SocketAddress> peers = new ArrayList<>();
    long maxAgeMs = 8000L;
    int maxEntries = 10000;
    int maxInflight = 512;
  }

  public WorkerUdpExchange(Vertx vertx, WorkerState state) {
    this.vertx = vertx;
    this.state = state;
  }

  public UdpStats stats() {
    return stats;
  }

  /** RTT moyen mesure des consults UDP reussies, null si aucun echange. */
  public Long avgRttMs() {
    long c = rttCount.get();
    if (c == 0) return null;
    return rttSumMs.get() / c;
  }

  public long droppedUnsolicited() {
    return droppedUnsolicited.get();
  }

  /** Demarre la socket (une fois) ; 0 = deja demarre. */
  public synchronized Future<Integer> start(int port) {
    if (socket != null) return Future.succeededFuture(bindPort);
    Promise<Integer> p = Promise.promise();
    DatagramSocket s = null;
    try {
      PeerRegistry registry = new PeerRegistry();
      registry.setAutoAddPeers(false);
      // UNE SEULE socket partagee transport/ecoute : l'emission part du port
      // lie (match exact allowlist host+port des deux cotes).
      s = vertx.createDatagramSocket(new DatagramSocketOptions());
      UdpLegacyTransport t = new UdpLegacyTransport(
          s, registry, stats, null, port, 2000);
      t.setStrictAllowlist(true);
      t.setWorkerHooks(new UdpLegacyTransport.UdpWorkerHooks() {
        @Override
        public void onRemoteRequest(EcmRequest request, SocketAddress sender) {
          serveRemote(request, sender);
        }

        @Override
        public void onRemoteReply(EcmRequest request, CwReply reply, SocketAddress sender) {
          feedRemote(request, reply, sender);
        }

        @Override
        public CwReply peekLocal(EcmKey key, SocketAddress sender) {
          return peekLocalFor(key, sender);
        }
      });
      final DatagramSocket sock = s;
      sock.handler(t::handlePacket);
      sock.exceptionHandler(e -> LOG.log(Level.FINE, "Worker UDP socket error"));
      sock.listen(port, "0.0.0.0").onComplete(ar -> {
        if (ar.failed()) {
          Throwable cause = ar.cause() != null ? ar.cause() : new RuntimeException("udp bind failed");
          LOG.log(Level.SEVERE, "Worker UDP listen failed on port " + port, cause);
          try {
            sock.close();
          } catch (Exception ignored) {
          }
          p.fail(cause);
          return;
        }
        socket = sock;
        transport = t;
        t.setBindPort(sock.localAddress() != null ? sock.localAddress().port() : port);
        bindPort = sock.localAddress() != null ? sock.localAddress().port() : port;
        p.complete(bindPort);
      });
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Worker UDP init failed on port " + port, e);
      p.fail(e);
    }
    return p.future();
  }

  /**
   * Reconcilie bindings + pairs statiques depuis le desired (allowlist exacte).
   * Les caches disparus voient leurs pendings echoues immediatement.
   */
  public synchronized void updateBindings(RelayWorkerContract.DesiredWorkerConfig desired,
                                         Set<String> servedProfiles) {
    this.servedProfiles = servedProfiles != null ? Set.copyOf(servedProfiles) : Set.of();
    Set<String> wanted = new java.util.HashSet<>();
    if (desired != null) {
      for (var c : desired.caches()) {
        if (!"udp".equals(c.kind()) || !c.enabled()) continue;
        wanted.add(c.name());
        UdpBinding b = bindings.computeIfAbsent(c.name(), k -> new UdpBinding());
        b.peers.clear();
        for (var peer : c.peers()) {
          try {
            b.peers.add(io.vertx.core.net.SocketAddress.inetSocketAddress(peer.port(), peer.host()));
          } catch (Exception e) {
            LOG.log(Level.WARNING, "Worker ignoring invalid udp peer");
          }
        }
        b.maxAgeMs = c.maxAgeMs();
        b.maxEntries = c.maxEntries();
        b.maxInflight = c.maxInflight();
      }
    }
    for (String gone : new ArrayList<>(bindings.keySet())) {
      if (!wanted.contains(gone)) {
        bindings.remove(gone);
        failPendingsOf(gone);
        try {
          state.stores().clear(gone);
        } catch (Exception ignored) {
        }
      }
    }
    rebuildStaticPeers();
  }

  private void rebuildStaticPeers() {
    UdpLegacyTransport t = transport;
    if (t == null) return;
    try {
      PeerRegistry r = t.peerRegistry();
      r.clear(false);
      for (var b : bindings.values()) {
        for (var peer : b.peers) r.addStaticPeer(peer.host(), peer.port());
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Worker udp registry rebuild failed");
    }
  }

  /**
   * Consultation sortante vers les pairs d'un cache udp (premier cache avec
   * pairs). Echec ferme rapide si aucun pair / transport arrete.
   */
  public Future<CwReply> consult(EcmRequest request, Set<String> eligible, String cacheName,
                                 long listenerId, long epoch, int provider, long maxWaitMs) {
    UdpBinding b = bindings.get(cacheName);
    UdpLegacyTransport t = transport;
    if (b == null || b.peers.isEmpty() || t == null) {
      return Future.failedFuture("no udp peers for cache " + cacheName);
    }
    RelayCachePolicy pol = state.policy();
    if (pol != null && !pol.canConsult(listenerId, cacheName)) {
      return Future.failedFuture("udp consult not allowed");
    }
    if (pending.size() >= MAX_PENDING) {
      return Future.failedFuture("udp pending overflow");
    }
    EcmKey key = request.key();
    Pending existing = pending.get(key);
    if (existing != null) return existing.promise.future();
    Pending full = new Pending(cacheName, listenerId, eligible, epoch, provider, request);
    Pending prev = pending.putIfAbsent(key, full);
    if (prev != null) return prev.promise.future();
    long timeout = maxWaitMs > 0 ? Math.min(maxWaitMs, CONSULT_TIMEOUT_CAP_MS) : 3000L;
    full.timerId = vertx.setTimer(timeout, id -> {
      if (pending.remove(key, full)) {
        full.promise.tryComplete(CwReply.empty(key));
      }
    });
    try {
      byte[] frame = ClusterUdpCodec.encodeRequest(request);
      for (var peer : b.peers) t.sendTo(peer, frame);
      stats.incSentPending();
    } catch (Exception e) {
      if (pending.remove(key, full)) {
        if (full.timerId != null) vertx.cancelTimer(full.timerId);
        full.promise.tryComplete(CwReply.empty(key));
      }
    }
    return full.promise.future();
  }

  /** Export : CW fraiche du dispatch local vers les pairs du cache (lien export live). */
  public void onLocalFeed(long listenerId, String cacheName, CwReply reply) {
    try {
      RelayCachePolicy pol = state.policy();
      if (pol == null || !pol.canExport(listenerId, cacheName)) return;
      UdpBinding b = bindings.get(cacheName);
      UdpLegacyTransport t = transport;
      if (b == null || b.peers.isEmpty() || t == null) return;
      if (reply == null || reply.isEmpty() || reply.cw() == null || reply.cw().length != 16) return;
      // Export UDP legacy : provider inconnu => garde historique checksum (aucune relaxation).
      var val = state.dcwPolicy().validate(reply.cw(), null, reply.key().caId(), DcwRuleScope.UNKNOWN_PROVIDER,
          reply.key().serviceId(), "local", com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
      if (!val.isValid()) return;
      byte[] frame = ClusterUdpCodec.encodeReply(reply);
      for (var peer : b.peers) t.sendTo(peer, frame);
      stats.incSentEntries();
      // Index local (granularite EcmKey, comme le legacy) pour servir les requetes.
      latestLocal.put(reply.key(), new Indexed(reply, b.maxAgeMs));
    } catch (Exception e) {
      LOG.log(Level.FINE, "Worker udp export failed");
    }
  }

  private void serveRemote(EcmRequest request, SocketAddress sender) {
    try {
      String cacheName = cacheOfSender(sender);
      if (cacheName == null) return;
      RelayCachePolicy pol = state.policy();
      if (pol == null || !pol.anyExportLink(cacheName)) return;
      CwReply hit = peekLocalFor(request.key(), sender);
      if (hit == null) return;
      UdpLegacyTransport t = transport;
      if (t == null) return;
      t.sendTo(sender, ClusterUdpCodec.encodeReply(hit));
      stats.incSentEntries();
    } catch (Exception e) {
      LOG.log(Level.FINE, "Worker udp serve failed");
    }
  }

  private CwReply peekLocalFor(EcmKey key, SocketAddress sender) {
    try {
      String cacheName = cacheOfSender(sender);
      if (cacheName == null) return null;
      RelayCachePolicy pol = state.policy();
      if (pol == null || !pol.anyExportLink(cacheName)) return null;
      if (servedProfiles.isEmpty()) return null;
      Indexed idx = latestLocal.get(key);
      if (idx == null || idx.reply == null || idx.reply.isEmpty()) return null;
      long ageMs = Math.max(500, Math.min(60000, idx.maxAgeMs));
      if (System.currentTimeMillis() - idx.reply.timestamp() > ageMs) {
        latestLocal.remove(key, idx);
        return null;
      }
      var val = state.dcwPolicy().validate(idx.reply.cw(), null, key.caId(), DcwRuleScope.UNKNOWN_PROVIDER,
          key.serviceId(), "local", com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
      if (!val.isValid()) {
        latestLocal.remove(key, idx);
        return null;
      }
      return idx.reply;
    } catch (Exception e) {
      return null;
    }
  }

  private void feedRemote(EcmRequest request, CwReply reply, SocketAddress sender) {
    try {
      if (reply == null || reply.isEmpty() || reply.cw() == null || reply.cw().length != 16) return;
      String origin = reply.connectorName();
      if (origin == null || origin.isBlank()) return;
      Pending pen = pending.get(reply.key());
      if (pen == null) {
        droppedUnsolicited.incrementAndGet();
        return;
      }
      // Reponse correlee a un pending : contexte (profil/CAID/provider/SID) porte par la requete locale ;
      // identite de regle = endpoint UDP autorise (transport), jamais le connectorName auto-declare.
      var val = state.dcwPolicy().validate(reply.cw(), pen.request.profileName(), pen.request.key().caId(), pen.provider,
          pen.request.key().serviceId(), udpSourceId(sender), com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
      if (!val.isValid()) {
        droppedUnsolicited.incrementAndGet();
        com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate = ingressGate;
        // Variante UDP : qualité seule, jamais de quarantaine sur une adresse
        // d'expédition usurpable (cf. contrat PeerQuarantine).
        if (gate != null) gate.recordContentInvalidUdp(udpSourceId(sender));
        return;
      }
      RelayCachePolicy pol = state.policy();
      if (pol == null || !pol.canFeed(pen.listenerId, pen.cacheName)) {
        if (pending.remove(reply.key(), pen)) {
          if (pen.timerId != null) vertx.cancelTimer(pen.timerId);
          pen.promise.tryComplete(CwReply.empty(reply.key()));
        }
        return;
      }
      var cfg = pol.cache(pen.cacheName);
      RelayCacheStores stores = state.stores();
      if (cfg != null) stores.ensure(pen.cacheName, cfg);
      StrictCacheKey skey = new StrictCacheKey(
          pen.request.profileName(), pen.request.key().caId(), pen.provider,
          pen.request.key().serviceId(), pen.request.key().commandTag(),
          pen.request.body(), pen.epoch, pen.eligible);
      stores.put(pen.cacheName, skey, reply,
          cfg != null ? cfg.maxAgeMs() : 8000L);
      rttSumMs.addAndGet(Math.max(0, System.currentTimeMillis() - pen.startMs));
      rttCount.incrementAndGet();
      com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate = ingressGate;
      // Variante UDP : qualité seule, jamais de quarantaine sur une adresse
      // d'expédition usurpable (cf. contrat PeerQuarantine).
      if (gate != null) gate.postInjectedUdp(udpSourceId(sender));
      if (pending.remove(reply.key(), pen)) {
        if (pen.timerId != null) vertx.cancelTimer(pen.timerId);
        pen.promise.tryComplete(reply);
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Worker udp feed failed");
    }
  }

  /**
   * Cache udp dont l'allowlist contient exactement cet expediteur (host+port).
   * Deux caches peuvent partager un host avec des ports differents sans
   * confusion (null si aucun).
   */
  /** Identite transport d'un pair UDP pour les regles scopees sourceId : {@code udp:<host>:<port>}. */
  static String udpSourceId(SocketAddress sender) {
    return sender == null ? null : "udp:" + sender.host() + ":" + sender.port();
  }

  private String cacheOfSender(SocketAddress sender) {
    if (sender == null) return null;
    for (var e : bindings.entrySet()) {
      for (var peer : e.getValue().peers) {
        if (sender.equals(peer)) return e.getKey();
      }
    }
    return null;
  }

  private void failPendingsOf(String cacheName) {
    for (var e : new ArrayList<>(pending.entrySet())) {
      if (cacheName.equals(e.getValue().cacheName) && pending.remove(e.getKey(), e.getValue())) {
        if (e.getValue().timerId != null) vertx.cancelTimer(e.getValue().timerId);
        e.getValue().promise.tryComplete(CwReply.empty(e.getKey()));
      }
    }
  }

  /** Revocation/expiry : echoue les pendings, ferme la socket, purge l'index. */
  public synchronized void failClosed() {
    for (var e : new ArrayList<>(pending.entrySet())) {
      if (pending.remove(e.getKey(), e.getValue())) {
        try {
          if (e.getValue().timerId != null) vertx.cancelTimer(e.getValue().timerId);
        } catch (Exception ignored) {
        }
        e.getValue().promise.tryComplete(CwReply.empty(e.getKey()));
      }
    }
    latestLocal.clear();
    bindings.clear();
    try {
      if (transport != null) transport.setWorkerHooks(null);
    } catch (Exception ignored) {
    }
    DatagramSocket s = socket;
    socket = null;
    transport = null;
    if (s != null) {
      try {
        s.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  public void close() {
    failClosed();
  }
}
