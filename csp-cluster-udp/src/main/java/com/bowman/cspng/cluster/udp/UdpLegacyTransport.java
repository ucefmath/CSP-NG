package com.bowman.cspng.cluster.udp;

import com.bowman.cspng.cache.CacheListener;
import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingReplyFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.ResendFrame;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwRuleScope;
import com.bowman.cspng.domain.dcw.LegacyGuard;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.SocketAddress;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transport UDP legacy — implémente {@link CacheTransport} en enveloppant un
 * {@link DatagramSocket} Vert.x pour communiquer avec les pairs CSP/OSCam.
 *
 * <h3>Fonctionnalités</h3>
 * <ul>
 *   <li>Envoi unicast + multicast des {@code TYPE_REQUEST} et {@code TYPE_REPLY}.</li>
 *   <li>Réception et routage des paquets entrants vers {@link CacheVerticle}.</li>
 *   <li>Implémente {@link CacheListener} pour émettre {@code TYPE_RESENDREQ}
 *       sur le hook {@code onDelayAlert}.</li>
 *   <li>Mitigations de sécurité §2.6 : rate-limiting, pré-validation taille,
 *       anti-amplification, try/catch global.</li>
 * </ul>
 */
public class UdpLegacyTransport implements CacheTransport, CacheListener {

  private static final Logger LOG = Logger.getLogger(UdpLegacyTransport.class.getName());

  static final String UDP_PREFIX = "udp:";
  private static final String INFINISPAN_PREFIX = "infinispan:";

  // --- Taille valides connues (plan §2.3) ---
  private static final int[] VALID_SIZES = {9, 12, 13, 16, 20};

  private final DatagramSocket socket;
  private final PeerRegistry peerRegistry;
  private final UdpStats stats;
  private final CacheVerticle cacheVerticle;
  private final int localPort;
  private final int maxPacketsPerSec;

  // Rate-limiter par IP source, mémoire bornée (plan §2.6 point 1)
  private final Cache<String, RateEntry> rateLimitCache;

  // Anti-rafale RESENDREQ : throttle 50ms par clé (plan §2.5)
  private final Cache<EcmKey, Long> resendThrottle;

  // Port local pour les trames PINGREQ et RESENDREQ
  private volatile int bindPort;

  /**
   * Allowlist stricte (worker) : seuls les pairs connus (statiques) sont traites.
   * Defaut false = comportement legacy central inchange. Avec strict=true et
   * {@code autoAddPeers=false}, aucun pair inconnu n'est servi ni appris.
   */
  private volatile boolean strictAllowlist = false;

  public void setStrictAllowlist(boolean strict) {
    this.strictAllowlist = strict;
  }

  public boolean strictAllowlist() {
    return strictAllowlist;
  }

  public UdpLegacyTransport(DatagramSocket socket, PeerRegistry peerRegistry,
                            UdpStats stats, CacheVerticle cacheVerticle,
                            int localPort, int maxPacketsPerSec) {
    this.socket = socket;
    this.peerRegistry = peerRegistry;
    this.stats = stats;
    this.cacheVerticle = cacheVerticle;
    this.localPort = localPort;
    this.maxPacketsPerSec = maxPacketsPerSec;

    this.rateLimitCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(1, TimeUnit.MINUTES)
        .build();

    this.resendThrottle = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build();
  }

  public void setBindPort(int bindPort) {
    this.bindPort = bindPort;
  }

  public int bindPort() {
    return bindPort;
  }

  public UdpStats stats() {
    return stats;
  }

  public PeerRegistry peerRegistry() {
    return peerRegistry;
  }

  /**
   * Delegation worker (echange UDP strict). Quand definis, les paquets entrants
   * sont remis aux hooks avec l'adresse expediteur (unicast possible) au lieu
   * du {@code cacheVerticle} central. Null = comportement legacy inchange.
   */
  public interface UdpWorkerHooks {
    /** Requete distante : servir depuis les stores locaux (jamais re-emettre). */
    void onRemoteRequest(EcmRequest request, SocketAddress sender);
    /** Reply distante : alimenter le store du cache concerne (gating appelant). */
    void onRemoteReply(EcmRequest request, CwReply reply, SocketAddress sender);
    /** Lookup local sans effet (chemin RESENDREQ) ; null si absent. */
    CwReply peekLocal(EcmKey key, SocketAddress sender);
  }

  private volatile UdpWorkerHooks workerHooks;

  public void setWorkerHooks(UdpWorkerHooks hooks) {
    this.workerHooks = hooks;
  }

  /**
   * Politique DCW pour la validation contextuelle AVANT injection centrale (lot 1).
   *
   * <p>Utilisée <b>uniquement</b> sur le chemin central (sans {@code workerHooks}) :
   * le chemin worker ne pré-valide jamais ici car seul le hook connaît le
   * pending (profil/provider/SID) et applique déjà la policy avec ce contexte
   * ({@code WorkerUdpExchange.feedRemote}). Null = {@link DcwQualityPolicy#DEFAULT}
   * (gardes historiques).
   */
  private volatile Supplier<DcwQualityPolicy> dcwPolicySupplier;

  public void setDcwPolicySupplier(Supplier<DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
  }

  DcwQualityPolicy effectiveDcwPolicy() {
    Supplier<DcwQualityPolicy> s = dcwPolicySupplier;
    if (s != null) {
      try {
        DcwQualityPolicy p = s.get();
        if (p != null) return p;
      } catch (Exception ignored) {
        // fail-closed vers DEFAULT ci-dessous
      }
    }
    return DcwQualityPolicy.DEFAULT;
  }

  // -----------------------------------------------------------------------------------------------
  // CacheTransport — émission
  // -----------------------------------------------------------------------------------------------

  @Override
  public void onRequest(EcmRequest request) {
    byte[] frame = ClusterUdpCodec.encodeRequest(request);
    stats.incSentPending();
    sendToAll(frame);
  }

  @Override
  public void onReply(CwReply reply) {
    if (reply == null || reply.isEmpty()) return;
    String origin = reply.originAddress();
    if (origin != null && (origin.startsWith(UDP_PREFIX) || origin.startsWith(INFINISPAN_PREFIX))) {
      return;
    }
    byte[] frame = ClusterUdpCodec.encodeReply(reply);
    stats.incSentEntries();
    sendToAll(frame);
  }

  // -----------------------------------------------------------------------------------------------
  // CacheListener — hooks
  // -----------------------------------------------------------------------------------------------

  @Override
  public void onDelayAlert(int successFacteur, EcmRequest request, boolean alwaysWait, long maxWaitMs) {
    // Throttle 50ms par clé (anti-rafale, plan §2.5)
    Long lastResend = resendThrottle.getIfPresent(request.key());
    long now = System.currentTimeMillis();
    if (lastResend != null && (now - lastResend) < 50) {
      return;
    }
    resendThrottle.put(request.key(), now);

    ResendFrame resend = new ResendFrame(bindPort, request.key());
    byte[] frame = ClusterUdpCodec.encodeResendRequest(resend);
    stats.incSentResendReqs();
    sendToAll(frame);
  }

  // -----------------------------------------------------------------------------------------------
  // Handler de paquets entrants (appelé depuis l'event-loop du DatagramSocket)
  // -----------------------------------------------------------------------------------------------

  /**
   * Point d'entrée unique pour tous les paquets UDP reçus. Appelé depuis le handler
   * du {@link DatagramSocket}.
   *
   * <p>Cette méthode ne doit <b>jamais</b> lever d'exception non capturée (sinon
   * l'event-loop entier plante). Tout le parsing est protégé par un {@code try/catch}
   * englobant (§2.6 point 4).
   */
  public void handlePacket(DatagramPacket packet) {
    try {
      handlePacketInternal(packet);
    } catch (Exception e) {
      stats.incParseErrors();
      LOG.log(Level.FINE, "Dropped malformed UDP packet from " + packet.sender()
          + ": " + e.getMessage());
    }
  }

  private void handlePacketInternal(DatagramPacket packet) {
    SocketAddress sender = packet.sender();
    String senderHost = sender.host();
    Buffer data = packet.data();
    int length = data.length();

    // (1) Rate-limiting par IP source (§2.6 point 1)
    if (!checkRateLimit(senderHost)) {
      stats.incRateLimited();
      return;
    }

    // (2) Pré-validation taille avant tout parsing (§2.6 point 2)
    if (!isValidSize(length)) {
      stats.incInvalidSize();
      return;
    }

    // (3) Blacklist
    if (peerRegistry.isBlocked(senderHost)) {
      return;
    }

    // (3b) Allowlist stricte : expediteur non exactement allowliste (host+port)
    // => drop comptabilise, sans reponse. Le repli host-only de isKnown() est
    // volontairement ignore ici (spoof de port depuis un host autorise).
    if (strictAllowlist && !peerRegistry.isKnownExact(sender)) {
      stats.incNotAllowlisted();
      return;
    }

    byte[] bytes = data.getBytes();
    int type = bytes[0] & 0xFF;

    switch (type) {
      case ClusterUdpCodec.TYPE_REQUEST -> handleIncomingRequest(bytes, sender);
      case ClusterUdpCodec.TYPE_REPLY -> handleIncomingReply(bytes, sender);
      case ClusterUdpCodec.TYPE_PINGREQ -> handleIncomingPingReq(bytes, sender);
      case ClusterUdpCodec.TYPE_PINGRPL -> handleIncomingPingRpl(bytes, sender);
      case ClusterUdpCodec.TYPE_RESENDREQ -> handleIncomingResendReq(bytes, sender);
      default -> {
        // Taille valide mais type inconnu (ni 1-5)
        stats.incUnknownType();
      }
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Traitement par type
  // -----------------------------------------------------------------------------------------------

  private void handleIncomingRequest(byte[] bytes, SocketAddress sender) {
    EcmRequest request = ClusterUdpCodec.decodeRequest(bytes);
    stats.incReceivedPending();
    peerRegistry.registerDynamicPeer(sender);
    // Note : le request décode n'a pas tous les champs (body, profileName, etc.)
    // mais cela suffit pour le pending local. On enrichit avec ce qu'on peut.
    EcmRequest enriched = new EcmRequest(request.key(), request.body(), null,
        sender.host(), null, System.currentTimeMillis(), request.arbiterNumber(), 0L);
    UdpWorkerHooks hooks = workerHooks;
    if (hooks != null) {
      try {
        hooks.onRemoteRequest(enriched, sender);
      } catch (Exception e) {
        LOG.log(Level.FINE, "Worker hook onRemoteRequest failed: " + e.getMessage());
      }
    } else {
      cacheVerticle.handleRemoteRequest(enriched);
    }
  }

  private void handleIncomingReply(byte[] bytes, SocketAddress sender) {
    CwReply decoded = ClusterUdpCodec.decodeReply(bytes);
    if (decoded.isEmpty()) {
      // Reply vide protocolaire (miss distant) : comptée puis transmise telle quelle.
      stats.incReceivedDiscarded();
    } else {
      stats.incReceivedEntries();
      // Observation honnête (4 triplets DVB) — la DÉCISION se fait par policy ci-dessous.
      byte[] cw = decoded.cw();
      if (cw.length == 16) {
        if (!validateCwChecksum(cw)) {
          stats.incReceivedBadChecksum();
        }
        if (hasZeroCw(cw)) {
          stats.incReceivedZeroCw();
        }
      }
    }
    peerRegistry.registerDynamicPeer(sender);
    CwReply marked = new CwReply(decoded.key(), decoded.commandTag(), decoded.cw(),
        decoded.connectorName(), UDP_PREFIX + sender.host(), System.currentTimeMillis());
    // Construire une EcmRequest minimale pour handleReply
    EcmRequest request = new EcmRequest(marked.key(), new byte[0], null,
        sender.host(), null, System.currentTimeMillis(), null, 0L);
    UdpWorkerHooks hooks = workerHooks;
    if (hooks != null) {
      // Chemin worker : AUCUNE pré-validation ici — le hook connaît le pending
      // (profil/provider/SID) et valide avec ce contexte avant injection.
      try {
        hooks.onRemoteReply(request, marked, sender);
      } catch (Exception e) {
        LOG.log(Level.FINE, "Worker hook onRemoteReply failed: " + e.getMessage());
      }
      return;
    }
    // Chemin central : validation contextuelle AVANT injection au cache.
    // Contexte UDP legacy : pas de profil ni de provider exploitable => provider
    // inconnu, garde historique CHECKSUM_REQUIRED (aucune relaxation), identité
    // de règle = endpoint vérifié (host+port), parité préservée telle quelle
    // (0 OSCam accepté comme valeur, jamais converti).
    if (!decoded.isEmpty()) {
      DcwQualityPolicy policy = effectiveDcwPolicy();
      var validation = policy.validate(decoded.cw(), null, decoded.key().caId(),
          DcwRuleScope.UNKNOWN_PROVIDER, decoded.key().serviceId(),
          UDP_PREFIX + sender.host() + ":" + sender.port(), LegacyGuard.CHECKSUM_REQUIRED);
      if (!validation.isValid()) {
        stats.incReceivedRejectedDcw();
        LOG.log(Level.FINE, "Central UDP reply rejected before cache: "
            + validation.reason());
        return;
      }
    }
    cacheVerticle.handleReply(request, marked);
  }

  private void handleIncomingPingReq(byte[] bytes, SocketAddress sender) {
    PingFrame ping = ClusterUdpCodec.decodePingRequest(bytes);
    stats.incPingReceived();
    peerRegistry.registerDynamicPeer(sender);
    // Répondre par PINGRPL en écho brut des 8 octets reçus (plan §2.3 piège ping)
    byte[] echoBytes = new byte[8];
    System.arraycopy(bytes, 1, echoBytes, 0, 8);
    PingReplyFrame rpl = new PingReplyFrame(echoBytes);
    byte[] rplBytes = ClusterUdpCodec.encodePingReply(rpl);
    sendTo(sender, rplBytes);
  }

  private void handleIncomingPingRpl(byte[] bytes, SocketAddress sender) {
    PingReplyFrame rpl = ClusterUdpCodec.decodePingReply(bytes);
    stats.incPongReceived();
    // RTT : best-effort, valeur structurellement fausse pour OSCam (plan §2.3),
    // on se contente de compter.
  }

  private void handleIncomingResendReq(byte[] bytes, SocketAddress sender) {
    ResendFrame resend = ClusterUdpCodec.decodeResendRequest(bytes);
    stats.incReceivedResendReqs();
    // Anti-amplification (§2.6 point 3) : ne répondre que si l'IP source
    // (packet.sender(), PAS le port du payload) correspond à un pair connu.
    if (!peerRegistry.isKnownHost(sender.host())) {
      stats.incAntiAmplificationRejected();
      return;
    }
    // Chercher dans le cache local (lecture SANS effet de bord — pas de verrou,
    // pas de pending, pas de notification de transport).
    UdpWorkerHooks hooks = workerHooks;
    CwReply cached = hooks != null ? safePeekLocal(hooks, resend.key(), sender)
        : cacheVerticle.peekLocalCache(resend.key());
    if (cached != null && !cached.isEmpty()) {
      byte[] rplBytes = ClusterUdpCodec.encodeReply(cached);
      sendTo(sender, rplBytes);
      stats.incReSentEntries();
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Envoi
  // -----------------------------------------------------------------------------------------------

  /**
   * Envoie un buffer à tous les pairs connus (unicast + multicast).
   */
  void sendToAll(byte[] data) {
    Buffer buffer = Buffer.buffer(data);
    Collection<SocketAddress> peers = peerRegistry.allPeers();
    for (SocketAddress peer : peers) {
      sendTo(peer, data);
    }
    SocketAddress mc = peerRegistry.multicastAddress();
    if (mc != null) {
      socket.send(buffer, mc.port(), mc.host());
    }
  }

  private static CwReply safePeekLocal(UdpWorkerHooks hooks, EcmKey key, SocketAddress sender) {
    try {
      return hooks.peekLocal(key, sender);
    } catch (Exception e) {
      LOG.log(Level.FINE, "Worker hook peekLocal failed: " + e.getMessage());
      return null;
    }
  }

  /**
   * Envoie un buffer à un pair spécifique (unicast ; public pour l'echange worker).
   */
  public void sendTo(SocketAddress target, byte[] data) {
    socket.send(Buffer.buffer(data), target.port(), target.host());
  }

  // -----------------------------------------------------------------------------------------------
  // Ping périodique
  // -----------------------------------------------------------------------------------------------

  /**
   * Émet un TYPE_PINGREQ vers tous les pairs.
   */
  public void sendPing() {
    PingFrame ping = new PingFrame(System.currentTimeMillis(), bindPort);
    byte[] frame = ClusterUdpCodec.encodePingRequest(ping);
    stats.incPingSent();
    sendToAll(frame);
  }

  // -----------------------------------------------------------------------------------------------
  // Validation
  // -----------------------------------------------------------------------------------------------

  /**
   * Pré-validation stricte de la taille de paquet avant tout parsing.
   * Tailles valides : 9 (PINGRPL), 12 (REQUEST sans arbitre), 13 (PINGREQ / REPLY empty),
   * 16 (RESENDREQ), 20 (REQUEST avec arbitre), >=29 (REPLY avec CW).
   */
  static boolean isValidSize(int length) {
    for (int s : VALID_SIZES) {
      if (length == s) return true;
    }
    return length >= 29 && length <= ClusterUdpCodec.MAX_RX_FRAME_SIZE;
  }

  /**
   * Rate-limiting par IP source, mémoire bornée via Caffeine.
   */
  private boolean checkRateLimit(String host) {
    RateEntry entry = rateLimitCache.get(host, k -> new RateEntry());
    return entry.incrementAndCheck(maxPacketsPerSec);
  }

  /**
   * Vérifie le checksum DCW DVB complet (4 triplets : octet 3/7/11/15 = somme
   * des 3 précédents mod 256). Utilisé pour l'observation statistique ; la
   * décision d'injection relève de {@link DcwQualityPolicy} (modes scopés,
   * demi-CW, digest).
   */
  static boolean validateCwChecksum(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    return DcwQualityPolicy.validateChecksumFull(cw);
  }

  /**
   * Détecte une CW tout-zéro (16 octets à 0).
   */
  static boolean hasZeroCw(byte[] cw) {
    for (byte b : cw) {
      if (b != 0) return false;
    }
    return true;
  }

  // -----------------------------------------------------------------------------------------------
  // Rate limit entry
  // -----------------------------------------------------------------------------------------------

  /**
   * Compteur glissant par seconde pour le rate-limiting.
   */
  static class RateEntry {
    private final AtomicInteger count = new AtomicInteger(0);
    private volatile long windowStart = System.currentTimeMillis();

    boolean incrementAndCheck(int maxPerSec) {
      long now = System.currentTimeMillis();
      long start = windowStart;
      if (now - start >= 1000) {
        // Nouvelle fenêtre
        if (count.compareAndSet(count.get(), 1)) {
          windowStart = now;
          return true; // toujours autoriser le premier de la fenêtre
        }
        // Sinon, un autre thread a déjà réinitialisé
        windowStart = now;
        count.set(1);
        return true;
      }
      int current = count.incrementAndGet();
      return current <= maxPerSec;
    }
  }
}
