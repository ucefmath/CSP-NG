package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.camd35.Camd35CacheExCodec;
import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Connecteur client CS378x (camd35 sur TCP) — ECM + CacheEx.
 *
 * <p>Auth camd35 : l'ucrc CLAIR (CRC32(MD5(username))) préfixe chaque frame et identifie
 * le compte côté serveur ; la clé AES-128 (MD5(password)) chiffre le payload — un mot de
 * passe erroné produit un CRC invalide et la fermeture par le serveur (fail-closed).
 *
 * <p>CacheEx (miroir comportemental module-camd35-cacheex.c — aucune copie de code) :
 * <ul>
 *   <li>mode 2 (NOUS recevons) : après connexion on envoie notre FILTER (0x3C) et
 *       l'échange d'id (0x3D/0x3E) ; les PUSH 0x3F entrants sont validés
 *       (rc, anti-boucle nodeId, hop, checksum CW, gate filtre) puis injectés au cache ;</li>
 *   <li>mode 3 (NOUS émettons) : {@link CacheTransport#onReply} pousse les CW du cache
 *       vers le pair, gate = filtre 0x3C reçu du pair, borne de file
 *       {@code cacheexMaxSendQueue} (nombre de messages — D3), dédup LRU bornée ;</li>
 *   <li>compteurs réels par pair via {@link CacheExStatsRegistry} (nullable).</li>
 * </ul>
 */
public class Camd35TcpConnector extends AbstractConnector implements com.bowman.cspng.connectors.cacheex.CacheExEmitter {

  private static final long RECONNECT_DELAY = 5_000L;
  private static final long KEEPALIVE_INTERVAL = 60_000L;
  /** Borne de file push par défaut (contrat v2 §6.1 — nombre de messages, pas un débit). */
  public static final int DEFAULT_MAX_SEND_QUEUE = 2000;

  private final String username;
  private final String password; // résolu par le caller — JAMAIS loggé
  private final int connectTimeoutMs;

  // Cache-Exchange
  private final int cacheexMode;          // 0=off, 2=receive (nous recevons), 3=send (nous émettons)
  private final int cacheexMaxHop;
  private final String cacheexFilterCfg;   // spec "caid:provid" annoncé au pair en mode 2
  private final int cacheexMaxSendQueue;
  private final CacheExStatsRegistry statsRegistry;
  /** Pont vers le vrai cache sans CacheVerticle (worker C1) — nullable. */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExBridge cacheExBridge;
  private final List<Camd35CacheExCodec.Camd35FilterEntry> ownFilterEntries;
  /** CW-cycle check (V15) — nullable ; gate enabled/caids appliquée avant check. */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  private volatile com.bowman.cspng.config.CwCycleConfig cwCycleConfig;
  /**
   * Porte d'ingress qualité/quarantaine/quorum (lots 1/2/4/5) — nullable.
   * Absente : garde historique {@code DcwValidator} inchangée.
   */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate;

  /** Injection de la porte d'ingress (nullable — aucun effet tant que null). */
  public void setIngressGate(com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate) {
    this.ingressGate = gate;
  }
  private final AtomicInteger pendingPushQueue = new AtomicInteger();
  /** Gate anti-flood RX par pair (R3) — distincte de la file TX bornée. */
  private final com.bowman.cspng.connectors.cacheex.RxFloodGate rxFloodGate =
      new com.bowman.cspng.connectors.cacheex.RxFloodGate();
  /** Chemins amont appris par hash + métadonnées de cycle (forwarding C2). */
  private final com.bowman.cspng.connectors.cacheex.UpstreamPathStore upstreamPaths =
      new com.bowman.cspng.connectors.cacheex.UpstreamPathStore();

  private byte[] ucrcBytes;
  private byte[] aesKey;
  private final byte[] nodeId = new byte[8];
  private volatile byte[] remoteNodeId;

  private NetClient client;
  private NetSocket socket;
  private long keepaliveTimerId = -1L;
  private Camd35FrameCodec.Decoder decoder;

  // ECM : corrélation par idx
  private final Map<Integer, PendingEcm> pendingEcms = new ConcurrentHashMap<>();
  private final AtomicInteger idxCounter = new AtomicInteger(1);
  private static final int MAX_PENDING_ECMS = 64;

  // Push sortants : dédup hash:peer LRU bornée + nodes connus par hash (anti-boucle/chemin)
  private final Map<String, Boolean> pushedHashesLru = Collections.synchronizedMap(
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > 1000;
        }
      });

  private static final class PendingEcm {
    final Promise<CwReply> promise;
    final EcmKey key;
    final long createdAtMs = System.currentTimeMillis();

    PendingEcm(Promise<CwReply> promise, EcmKey key) {
      this.promise = promise;
      this.key = key;
    }
  }

  public Camd35TcpConnector(Vertx vertx, CacheVerticle cacheVerticle,
                            String name, String host, int port,
                            String username, String password, String profile,
                            int cacheexMode, int cacheexMaxHop) {
    this(vertx, cacheVerticle, name, host, port, username, password, profile,
        cacheexMode, cacheexMaxHop, 5000, null, null, DEFAULT_MAX_SEND_QUEUE, null);
  }

  public Camd35TcpConnector(Vertx vertx, CacheVerticle cacheVerticle,
                            String name, String host, int port,
                            String username, String password, String profile,
                            int cacheexMode, int cacheexMaxHop,
                            int connectTimeoutMs, NetworkProxyOptions proxyOptions,
                            String cacheexFilterCfg, int cacheexMaxSendQueue,
                            CacheExStatsRegistry statsRegistry) {
    super(vertx, cacheVerticle, name, host, port, profile, proxyOptions);
    this.username = username != null ? username : "";
    this.password = password;
    this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 5000;
    this.cacheexMode = cacheexMode;
    this.cacheexMaxHop = cacheexMaxHop;
    this.cacheexFilterCfg = cacheexFilterCfg;
    this.cacheexMaxSendQueue = cacheexMaxSendQueue > 0 ? cacheexMaxSendQueue : DEFAULT_MAX_SEND_QUEUE;
    this.statsRegistry = statsRegistry;
    this.ownFilterEntries = parseFilterEntries(cacheexFilterCfg);
    new SecureRandom().nextBytes(nodeId);
  }

  /** Spec "caid:provid" → entrées filtre camd35 (cmask 0xFFFF = CAID exact, srvid 0 = tous). */
  private static List<Camd35CacheExCodec.Camd35FilterEntry> parseFilterEntries(String spec) {
    if (spec == null || spec.isBlank()) return List.of();
    List<Camd35CacheExCodec.Camd35FilterEntry> out = new java.util.ArrayList<>();
    for (String raw : spec.split(",")) {
      String part = raw.trim();
      if (part.isEmpty()) continue;
      int colon = part.indexOf(':');
      if (colon <= 0 || colon == part.length() - 1) {
        throw new IllegalArgumentException("invalid cacheex filter entry (expected caid:provid): " + part);
      }
      String caidStr = part.substring(0, colon).trim();
      String pridStr = part.substring(colon + 1).trim();
      if (!caidStr.matches("[0-9a-fA-F]{1,4}") || !pridStr.matches("[0-9a-fA-F]{1,6}")) {
        throw new IllegalArgumentException("invalid cacheex filter entry (hex expected): " + part);
      }
      int caid = Integer.parseInt(caidStr, 16);
      int provid = "0".equals(pridStr) ? 0 : (int) Long.parseLong(pridStr, 16);
      out.add(new Camd35CacheExCodec.Camd35FilterEntry(caid, 0xFFFF, provid, 0));
    }
    return out.size() > Camd35CacheExCodec.FILTER_MAX_ENTRIES
        ? out.subList(0, Camd35CacheExCodec.FILTER_MAX_ENTRIES) : out;
  }

  // ============================================================
  // Cycle de vie
  // ============================================================

  @Override
  public Future<Void> start() {
    stopped = false;
    if (statsRegistry != null && cacheexMode != 0) {
      statsRegistry.registerOutbound(name, "cs378x", cacheexMode, profile,
          cacheexMaxHop, cacheexMaxSendQueue);
    }
    nextGeneration();
    Promise<Void> promise = Promise.promise();
    if (password == null || password.isEmpty()) {
      promise.fail("cs378x connector " + name + " requires a resolved password");
      return promise.future();
    }
    this.ucrcBytes = Camd35Crypto.ucrc(username);
    this.aesKey = Camd35Crypto.aesKey(password);
    client = createNetClient(buildNetClientOptions(connectTimeoutMs));
    doConnect(promise);
    return promise.future();
  }

  private void doConnect(Promise<Void> promise) {
    final int gen = currentGeneration();
    client.connect(port, host, ar -> {
      if (isStale(gen)) {
        if (ar.succeeded()) {
          try { ar.result().close(); } catch (Exception ignore) {}
        }
        if (!promise.future().isComplete()) promise.fail("Connector stopped");
        return;
      }
      if (ar.failed()) {
        logger.warning("CS378x failed to connect to " + host + ":" + port + ": "
            + ar.cause().getMessage());
        scheduleReconnect();
        if (!promise.future().isComplete()) promise.fail(ar.cause());
        return;
      }
      socket = ar.result();
      decoder = new Camd35FrameCodec.Decoder(aesKey);
      connected = true;
      socket.exceptionHandler(t ->
          logger.log(Level.WARNING, "CS378x connection error: " + t.getMessage()));
      socket.closeHandler(v -> {
        if (isStale(gen)) return;
        connected = false;
        stopKeepalive();
        failAllPending("Disconnected");
        logger.info("CS378x disconnected from " + host + ":" + port);
        if (statsRegistry != null && cacheexMode != 0) {
          statsRegistry.updateState(name, false, "disconnected", false);
        }
        scheduleReconnect();
      });
      socket.handler(this::onData);

      if (cacheexMode != 0) {
        if (cacheVerticle != null) {
          cacheVerticle.addTransport(this);
        }
        if (statsRegistry != null) {
          statsRegistry.updateState(name, true, "connected", false);
          String hexNodeId = remoteNodeId != null && remoteNodeId.length == 8
              ? java.util.HexFormat.of().withUpperCase().formatHex(remoteNodeId) : null;
          statsRegistry.updatePeerIdentity(name, host + ":" + port, hexNodeId);
        }
        // Échange d'id (0x3D avec notre node id) — miroir camd35_cacheex_push_request_remote_id
        sendCacheExIdRequest();
        // Mode 2 (nous recevons) : annoncer NOTRE filtre au pair
        if (cacheexMode == 2) {
          sendOwnFilter();
        }
      }
      startKeepalive();
      logger.info("CS378x connected to " + host + ":" + port
          + " (cacheex mode=" + cacheexMode + ")");
      promise.complete();
    });
  }

  private void scheduleReconnect() {
    if (stopped) return;
    vertx.setTimer(RECONNECT_DELAY, id -> {
      if (stopped) return;
      Promise<Void> p = Promise.promise();
      doConnect(p);
    });
  }

  @Override
  public void stop() {
    stopped = true;
    nextGeneration();
    stopKeepalive();
    failAllPending("Connector stopped");
    connected = false;
    if (statsRegistry != null && cacheexMode != 0) {
      statsRegistry.updateState(name, false, "disabled", false);
      statsRegistry.unregister(name);
    }
    if (socket != null) socket.close();
    if (client != null) client.close();
  }

  @Override
  public Future<LoginState> loginFuture() {
    // camd35 : pas de login applicatif — l'ucrc/AES porte l'auth, la première frame fait foi
    return Future.succeededFuture(connected ? LoginState.OK : LoginState.FAILED);
  }

  private void startKeepalive() {
    if (keepaliveTimerId >= 0) return;
    keepaliveTimerId = vertx.setPeriodic(KEEPALIVE_INTERVAL, id -> {
      if (connected) {
        Camd35FrameCodec.Camd35Frame ka = new Camd35FrameCodec.Camd35Frame(
            ucrcBytes, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0xFFFF, new byte[0], new byte[0]);
        sendFrame(ka);
      }
    });
  }

  private void stopKeepalive() {
    if (keepaliveTimerId >= 0) {
      vertx.cancelTimer(keepaliveTimerId);
      keepaliveTimerId = -1;
    }
  }

  // ============================================================
  // ECM (cmd 0x00 → 0x01)
  // ============================================================

  @Override
  public Future<CwReply> sendEcm(EcmRequest request) {
    Promise<CwReply> promise = Promise.promise();
    if (!connected || socket == null) {
      promise.fail("Not connected");
      return promise.future();
    }
    if (pendingEcms.size() >= MAX_PENDING_ECMS) {
      promise.fail("Pending ECM quota reached");
      return promise.future();
    }
    int idx = idxCounter.incrementAndGet() & 0xFFFF;
    PendingEcm pending = new PendingEcm(promise, request.key());
    pendingEcms.put(idx, pending);

    // data = section DVB COMPLETE (cmd 0 camd35 : l'en-tête DVB fait foi)
    byte[] ecm = request.body() != null ? request.body() : new byte[0];
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        ucrcBytes, Camd35Constants.CMD_ECM,
        request.key().serviceId() & 0xFFFF, request.key().caId() & 0xFFFF,
        0, idx, 0xFFFF, ecm, new byte[0]);
    if (!sendFrame(frame)) {
      pendingEcms.remove(idx);
      promise.fail("Send failed");
    }
    return promise.future();
  }

  private void handleCwReply(Camd35FrameCodec.Camd35Frame frame) {
    int idx = frame.idx();
    PendingEcm pending = pendingEcms.remove(idx);
    if (pending == null) return;
    byte[] cw = frame.data();
    if (cw == null || cw.length != Camd35Constants.CW_SIZE) {
      pending.promise.fail("Invalid CW reply length");
      return;
    }
    CwReply reply = new CwReply(pending.key, pending.key.commandTag(),
        cw.clone(), name, host + ":" + port, System.currentTimeMillis());
    pending.promise.complete(reply);
  }

  private void failAllPending(String reason) {
    for (PendingEcm p : pendingEcms.values()) {
      p.promise.fail(reason);
    }
    pendingEcms.clear();
  }

  // ============================================================
  // Réception (décodage incrémental + dispatch cacheex)
  // ============================================================

  private void onData(Buffer data) {
    if (!connected || decoder == null) return;
    Camd35FrameCodec.DecodeResult result = decoder.feed(data.getBytes());
    while (result.status() == Camd35FrameCodec.Status.FRAME) {
      handleFrame(result.frame());
      result = decoder.feed(new byte[0]);
    }
    if (result.status() == Camd35FrameCodec.Status.CRC_MISMATCH) {
      logger.warning("CS378x CRC mismatch from " + host + ":" + port
          + " (wrong password?) — closing");
      connected = false;
      if (socket != null) socket.close();
    } else if (result.status() == Camd35FrameCodec.Status.OVERFLOW) {
      logger.warning("CS378x framing overflow from " + host + ":" + port + " — closing");
      connected = false;
      if (socket != null) socket.close();
    }
  }

  private void handleFrame(Camd35FrameCodec.Camd35Frame frame) {
    switch (frame.cmd()) {
      case Camd35Constants.CMD_CW -> handleCwReply(frame);
      case Camd35Constants.CMD_CACHEEX_PUSH -> handleCachePush(frame);
      case Camd35Constants.CMD_CACHEEX_ID_REQ -> handleIdRequest(frame);
      case Camd35Constants.CMD_CACHEEX_ID_ANSWER -> handleIdAnswer(frame);
      case Camd35Constants.CMD_KEEPALIVE -> logger.finest("CS378x keepalive from " + host);
      default -> logger.fine("CS378x unhandled cmd 0x"
          + Integer.toHexString(frame.cmd()) + " from " + host);
    }
  }

  private void handleIdRequest(Camd35FrameCodec.Camd35Frame frame) {
    // Le pair annonce son node id (body[0..7]) et demande le nôtre — miroir OSCam 0x3D
    if (frame.data() != null && frame.data().length >= 8) {
      this.remoteNodeId = Arrays.copyOf(frame.data(), 8);
      if (statsRegistry != null && cacheexMode != 0) {
        String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
        statsRegistry.updatePeerIdentity(name, host + ":" + port, hexNodeId);
      }
    }
    sendCacheExIdAnswer();
    if (statsRegistry != null && cacheexMode != 0) {
      statsRegistry.updateState(name, connected, connected ? "connected" : "disconnected", false);
    }
  }

  private void handleIdAnswer(Camd35FrameCodec.Camd35Frame frame) {
    if (frame.data() != null && frame.data().length >= 8) {
      this.remoteNodeId = Arrays.copyOf(frame.data(), 8);
      if (statsRegistry != null && cacheexMode != 0) {
        String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
        statsRegistry.updatePeerIdentity(name, host + ":" + port, hexNodeId);
      }
    }
  }

  /** PUSH 0x3F entrant — accepté uniquement en mode 2 (nous recevons). */
  private void handleCachePush(Camd35FrameCodec.Camd35Frame frame) {
    if (cacheexMode != 2) {
      logger.fine("CS378x CacheEx PUSH ignored — not in receive mode (current=" + cacheexMode + ")");
      return;
    }

    // R3 anti-flood RX par pair (borne TX maxSendQueue non concernée).
    if (!rxFloodGate.tryAcquire(
        com.bowman.cspng.connectors.cacheex.RxFloodGate.DEFAULT_MAX_PER_MINUTE)) {
      countDrop(CacheExStatsRegistry.REASON_FLOOD);
      logger.fine("CS378x CacheEx PUSH dropped — RX flood from " + name);
      return;
    }
    Camd35CacheExCodec.Camd35Push push;
    try {
      push = Camd35CacheExCodec.parsePush(frame);
    } catch (Exception e) {
      countDrop(CacheExStatsRegistry.REASON_INVALID);
      logger.fine("CS378x CacheEx PUSH parse error: " + e.getMessage());
      return;
    }
    if (remoteNodeId == null && !push.nodeIds().isEmpty()) {
      byte[] first = push.nodeIds().get(0);
      if (first != null && first.length == 8) {
        this.remoteNodeId = Arrays.copyOf(first, 8);
        if (statsRegistry != null && cacheexMode != 0) {
          String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
          statsRegistry.updatePeerIdentity(name, host + ":" + port, hexNodeId);
        }
      }
    }
    // rc : E_FOUND(0) ou E_UNHANDLED(100) — miroir camd35_cacheex_push_in
    if (push.rc() != 0 && push.rc() != 100) {
      countDrop(CacheExStatsRegistry.REASON_INVALID, push.caid(), push.prid(), push.srvid());
      return;
    }
    // Anti-boucle : notre node id dans le chemin
    for (byte[] n : push.nodeIds()) {
      if (Arrays.equals(n, nodeId)) {
        countDrop(CacheExStatsRegistry.REASON_LOOP, push.caid(), push.prid(), push.srvid());
        return;
      }
    }
    // Max-hop
    if (push.nodeIds().size() > cacheexMaxHop) {
      countDrop(CacheExStatsRegistry.REASON_HOP, push.caid(), push.prid(), push.srvid());
      logger.fine("CS378x CacheEx PUSH skipped — hop count " + push.nodeIds().size()
          + " > max " + cacheexMaxHop);
      return;
    }
    // Gate filtre (NOTRE filtre configuré, mode 2)
    if (!ownFilterEntries.isEmpty()) {
      boolean matches = false;
      for (Camd35CacheExCodec.Camd35FilterEntry e : ownFilterEntries) {
        if (e.caid() == push.caid()
            && (e.prid() == 0 || e.prid() == push.prid())) {
          matches = true;
          break;
        }
      }
      if (!matches) {
        countDrop(CacheExStatsRegistry.REASON_FILTERED, push.caid(), push.prid(), push.srvid());
        return;
      }
    }
    // R2: Validation parité (0, 0x80, 0x81) AVANT toute allocation ou suivi quorum
    // Compat OSCam : 0 = INCONNU accepté (bypass cycle compté) ; autres => rejet fail-closed.
    if (push.oddEven() != 0x80 && push.oddEven() != 0x81 && push.oddEven() != 0) {
      countDrop(CacheExStatsRegistry.REASON_INVALID, push.caid(), push.prid(), push.srvid());
      logger.fine("CS378x CacheEx PUSH dropped — invalid oddEven 0x"
          + Integer.toHexString(push.oddEven()));
      return;
    }
    int oe = push.oddEven();

    byte[] cw = push.cw();

    // CW-cycle check (V15) : rejet AVANT injection (métadonnée pair [18])
    if (cwCycleChecker != null && cwCycleConfig != null && cwCycleConfig.enabled()
        && cwCycleConfig.appliesToCaid(push.caid())) {
      com.bowman.cspng.filters.CwCycleChecker.Verdict cycleVerdict = cwCycleChecker.check(
          profile, push.caid(), push.prid(), push.srvid(), oe, cw,
          push.cycleTimeSec(), push.nextCwCycle(), System.currentTimeMillis());
      if (cwCycleChecker.shouldReject(cycleVerdict)) {
        countDrop(CacheExStatsRegistry.REASON_INVALID, push.caid(), push.prid(), push.srvid());
        logger.fine("CS378x CacheEx PUSH dropped — cw-cycle " + cycleVerdict);
        return;
      }
    }

    String origin = host + ":" + port;
    String realOrigin = com.bowman.cspng.connectors.cacheex.CacheExIngressGate.extractOrigin(
        push.nodeIds(), origin);
    com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate = ingressGate;
    if (gate != null) {
      com.bowman.cspng.connectors.cacheex.CacheExIngressGate.Verdict verdict = gate.preInject(
          profile, push.caid(), push.prid(), push.srvid(), oe, push.dataHash(),
          cw, name, realOrigin,
          com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
      if (!verdict.accept()) {
        countDrop(verdict.dropReason(), push.caid(), push.prid(), push.srvid());
        logger.fine("CS378x CacheEx PUSH dropped — " + verdict.detail() + " from " + name);
        return;
      }
    } else {
      if (!DcwValidator.checksumDcw(cw)) {
        countDrop(CacheExStatsRegistry.REASON_CHECKSUM, push.caid(), push.prid(), push.srvid());
        return;
      }
      if (DcwValidator.hasFiveZeroes(cw) || DcwValidator.hasZeroDcw(cw)) {
        countDrop(CacheExStatsRegistry.REASON_INVALID, push.caid(), push.prid(), push.srvid());
        return;
      }
    }

    // Mémoriser le chemin amont + métadonnée de cycle pour re-push futur (C2)
    noteUpstreamPath(push.dataHash(), push.nodeIds(), push.cycleTimeSec(), push.nextCwCycle());

    EcmKey key = new EcmKey(oe, push.srvid(), push.pid(), push.caid(), push.dataHash());
    CwReply reply = new CwReply(key, oe, cw.clone(), name, origin, System.currentTimeMillis());
    if (cacheVerticle != null) {
      EcmRequest synthetic = new EcmRequest(key, new byte[0], profile, origin, name,
          System.currentTimeMillis(), null, 5000);
      cacheVerticle.handleReply(synthetic, reply);
      if (statsRegistry != null) statsRegistry.countReceived(name, push.caid(), push.prid(), push.srvid());
      com.bowman.cspng.connectors.cacheex.CacheExPushLog.accepted("cs378x", name, realOrigin,
          push.caid(), push.prid(), push.srvid(), oe, push.nodeIds().size());
      if (gate != null) gate.postInjected(name);
    } else if (cacheExBridge != null && cacheExBridge.feedReceived(
        profile, -1, push.caid(), push.prid(), push.srvid(), oe, cw,
        push.dataHash(), push.cycleTimeSec(), push.nextCwCycle(),
        push.nodeIds(), origin, name)) {
      if (statsRegistry != null) statsRegistry.countReceived(name, push.caid(), push.prid(), push.srvid());
      com.bowman.cspng.connectors.cacheex.CacheExPushLog.accepted("cs378x", name, realOrigin,
          push.caid(), push.prid(), push.srvid(), oe, push.nodeIds().size());
      if (gate != null) gate.postInjected(name);
    } else {
      countDrop(CacheExStatsRegistry.REASON_INVALID, push.caid(), push.prid(), push.srvid());
      logger.fine("CS378x CacheEx PUSH dropped — no routable cache (worker bridge absent?)");
    }
  }

  // ============================================================
  // CacheTransport — mode 3 : push des CW du cache vers le pair
  // ============================================================

  @Override
  public void onReply(CwReply reply) {
    if (cacheexMode != 3) return;
    if (!connected || socket == null) return;
    if (reply == null || reply.isEmpty()) return;
    if (reply.originAddress() != null && reply.originAddress().equals(host + ":" + port)) return;

    String dedupKey = reply.key().dataHash() + ":" + host + ":" + port;
    if (pushedHashesLru.containsKey(dedupKey)) return;

    // Chemin de nodes (forwarding C2) : own node FIRST + chemin amont appris
    com.bowman.cspng.connectors.cacheex.UpstreamPathStore.PathEntry stored =
        upstreamPaths.get(reply.key().dataHash());
    java.util.List<byte[]> knownPath = stored != null ? stored.nodes() : List.of();
    java.util.List<byte[]> allNodes = new java.util.ArrayList<>(knownPath.size() + 1);
    allNodes.add(nodeId);
    for (byte[] n : knownPath) {
      if (!java.util.Arrays.equals(n, nodeId)) allNodes.add(n);
    }
    if (allNodes.size() > cacheexMaxHop) {
      countDrop(CacheExStatsRegistry.REASON_HOP, reply.key().caId(), 0, reply.key().serviceId());
      return;
    }

    // Borne de file d'émission (D3 : NOMBRE de messages en attente)
    for (;;) {
      int pending = pendingPushQueue.get();
      if (pending >= cacheexMaxSendQueue) {
        countDrop(CacheExStatsRegistry.REASON_QUEUE, reply.key().caId(), 0, reply.key().serviceId());
        logger.fine("CS378x CacheEx PUSH(OUT) dropped — send queue full ("
            + pending + " >= " + cacheexMaxSendQueue + ")");
        return;
      }
      if (pendingPushQueue.compareAndSet(pending, pending + 1)) break;
    }

    byte[] body = Camd35CacheExCodec.encodePushBody(
        new byte[16], reply.key().dataHash(), reply.cw(), allNodes);
    // Champ pid u16 [18..19] : buf[18] = cycletime (bit 0x80 flag), buf[19] = odd/even.
    // Métadonnée : chemin stocké d'abord (valeur wire exacte), sinon checker si actif.
    int cycleTimeSec = 0;
    boolean nextCwCycle = false;
    if (stored != null && stored.cycleTimeSec() > 0) {
      cycleTimeSec = stored.cycleTimeSec();
      nextCwCycle = stored.nextCwCycle();
    } else if (cwCycleChecker != null && cwCycleConfig != null && cwCycleConfig.enabled()
        && cwCycleConfig.usePeerCycleMetadata()) {
      int[] meta = cwCycleChecker.cycleMetadataFor(reply.key().caId(), reply.key().serviceId());
      if (meta != null) {
        cycleTimeSec = meta[0];
        nextCwCycle = meta[1] == 1;
      }
    }
    // Normalisation TX (miroir OSCam :1230) : tag non-80/81 => 0 (INCONNU).
    int emitTag = reply.key().commandTag();
    if (emitTag != 0x80 && emitTag != 0x81) emitTag = 0;
    int pidField = ((cycleTimeSec & 0x7F) | (nextCwCycle ? 0x80 : 0x00)) << 8
        | (emitTag & 0xFF);
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        ucrcBytes, Camd35Constants.CMD_CACHEEX_PUSH,
        reply.key().serviceId() & 0xFFFF, reply.key().caId() & 0xFFFF,
        0, 0, pidField,
        body, new byte[0], 0 /* rc = E_FOUND */);

    pushedHashesLru.put(dedupKey, Boolean.TRUE);
    vertx.runOnContext(v -> {
      try {
        sendFrame(frame);
      } finally {
        pendingPushQueue.decrementAndGet();
      }
    });
    if (statsRegistry != null) statsRegistry.countPushed(name, reply.key().caId(), 0, reply.key().serviceId());
  }

  @Override
  public void onRequest(EcmRequest request) {
    // No-op : pas de propagation des requêtes ECM vers le pair
  }

  // ============================================================
  // Émission camd35
  // ============================================================

  /** Envoie l'échange d'id 0x3D avec NOTRE node id (body 12 octets, node id à [0..7]). */
  private void sendCacheExIdRequest() {
    byte[] body = new byte[12];
    System.arraycopy(nodeId, 0, body, 0, 8);
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        ucrcBytes, Camd35Constants.CMD_CACHEEX_ID_REQ, 0, 0, 0, 0, 0, body, new byte[0]);
    sendFrame(frame);
  }

  /** Répond 0x3E avec NOTRE node id. */
  private void sendCacheExIdAnswer() {
    byte[] body = new byte[12];
    System.arraycopy(nodeId, 0, body, 0, 8);
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        ucrcBytes, Camd35Constants.CMD_CACHEEX_ID_ANSWER, 0, 0, 0, 0, 0, body, new byte[0]);
    sendFrame(frame);
  }

  /** Mode 2 : annonce NOTRE filtre (0x3C, 242 octets) — vide = accept-all. */
  private void sendOwnFilter() {
    byte[] body = Camd35CacheExCodec.encodeFilterBody(ownFilterEntries);
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        ucrcBytes, Camd35Constants.CMD_CACHEEX_FILTER, 0, 0, 0, 0, 0, body, new byte[0]);
    sendFrame(frame);
    logger.fine("CS378x CacheEx FILTER sent ("
        + (ownFilterEntries.isEmpty() ? "accept-all" : ownFilterEntries.size() + " entries") + ")");
  }

  private boolean sendFrame(Camd35FrameCodec.Camd35Frame frame) {
    if (socket == null || aesKey == null || !connected) return false;
    try {
      byte[] wire = Camd35FrameCodec.encode(frame, aesKey);
      socket.write(Buffer.buffer(wire));
      return true;
    } catch (Exception e) {
      logger.log(Level.WARNING, "CS378x send failed: " + e.getMessage());
      return false;
    }
  }

  /** Mémorise le chemin amont d'un hash (forwarding C2). */
  public void noteUpstreamPath(int dataHash, java.util.List<byte[]> upstreamPath) {
    upstreamPaths.note(dataHash, upstreamPath);
  }

  /**
   * Mémorise le chemin amont + métadonnée de cycle wire (forwarding C2 complet).
   */
  public void noteUpstreamPath(int dataHash, java.util.List<byte[]> upstreamPath,
                               int cycleTimeSec, boolean nextCwCycle) {
    upstreamPaths.note(dataHash, upstreamPath, cycleTimeSec, nextCwCycle);
  }

  /** Injection du pont cacheex worker (nullable — central = CacheVerticle). */
  public void setCacheExBridge(com.bowman.cspng.connectors.cacheex.CacheExBridge bridge) {
    this.cacheExBridge = bridge;
  }

  /** Injection du contrôle CW-cycle (nullable — aucun effet si config absente/off). */
  public void setCwCycle(com.bowman.cspng.filters.CwCycleChecker checker,
                          com.bowman.cspng.config.CwCycleConfig config) {
    this.cwCycleChecker = checker;
    this.cwCycleConfig = config;
  }

  private void countDrop(String reason) {
    if (statsRegistry != null) statsRegistry.countDropped(name, reason);
  }

  private void countDrop(String reason, int caid, int provid, int srvid) {
    if (statsRegistry != null) statsRegistry.countDropped(name, reason, caid, provid, srvid);
  }

  // ============================================================
  // Infos cartes (inconnues en camd35 — pas d'annonce de cartes sur ce protocole)
  // ============================================================

  public boolean hasCards() {
    return false;
  }

  public int cardCount() {
    return 0;
  }

  public boolean hasCardForCaid(int caId) {
    return false;
  }

  public boolean hasCardForCaidAndProvider(int caId, int provider) {
    return false;
  }
}
