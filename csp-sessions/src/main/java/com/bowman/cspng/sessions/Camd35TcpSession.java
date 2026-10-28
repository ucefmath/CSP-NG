package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.camd35.Camd35CacheExCodec;
import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.IpMatcher;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import com.bowman.cspng.users.UserRecord;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Session serveur Camd35 / CS378x (camd35 sur TCP).
 *
 * <p>Contrats P4/P5 (docs/INBOUND-CAMS-PLAN.md §P4/§P5) :
 * <ul>
 *   <li><b>Auth & ucrc</b> : extraction de l'ucrc (4 octets clairs), lookup via
 *       {@link ProtocolAuthResolver#authenticateByUcrc(String, long)}, résolution du secret
 *       sur worker thread ({@link SecretResolver}, jamais sur event-loop).</li>
 *   <li><b>Preauth timeout</b> : 10s maximum avant complétion de l'authentification.</li>
 *   <li><b>Framing incrémental</b> : décodeur incrémental borné avec coalescence/fragmentation.</li>
 *   <li><b>Commandes</b> :
 *       <ul>
 *         <li>CMD 0x00 (ECM) : dérivation DVB 12 bits, validation de section, routage strict.</li>
 *         <li>CMD 0x03 (ECM cascading) : lecture frame complète + trailer 52 octets, réponse 0x44 (NOK).</li>
 *         <li>CMD 55 (Keepalive décimal) : réponse {@code [55, 1, 0]}.</li>
 *         <li>CMD 0x06/19 (EMM) / 0x05 (AU) / inconnus : droppés silencieusement sans réponse 0x44.</li>
 *         <li>CRC invalide : fermeture SANS réponse.</li>
 *         <li>Section ECM invalide : fermeture socket (fail closed strict).</li>
 *       </ul>
 *   </li>
 *   <li><b>Quotas & ACL</b> : max connections, IP mask, permissions de profils et services
 *       filtrés avant routage.</li>
 *   <li><b>Corrélation idx</b> : gestion des réponses hors-ordre et protection contre doublons idx.</li>
 *   <li><b>Auth Lease / Epoch check</b> : check périodique ≤ 1s, révocation dynamique et fail-closed.</li>
 * </ul>
 */
public class Camd35TcpSession extends AbstractSession implements com.bowman.cspng.connectors.cacheex.CacheExEmitter {

  public static final long PREAUTH_TIMEOUT_MS = 10_000L;
  public static final int MAX_INPUT_BUFFER_SIZE = 8_192;

  private final ProtocolAuthResolver authResolver;
  private final SecretResolver secretResolver;
  private final SessionEcmRouter router;
  private final int maxInflight;
  private final int maxPerIp;
  private final int idleTimeoutSec;

  private NetSocket socket;
  private Context sessionContext;
  private long preauthTimerId = -1L;
  private long epochCheckTimerId = -1L;
  private long validUntilTimerId = -1L;
  private long idleTimerId = -1L;
  private volatile boolean authInProgress = false;

  private byte[] aesKey;
  private byte[] clientUcrc;
  private ProtocolAuthResolver.AuthResult authResult;
  private Camd35FrameCodec.Decoder decoder;
  private Buffer incomingBuffer = Buffer.buffer();

  private final Map<Integer, Long> inFlightIdx = new ConcurrentHashMap<>();

  // Cache-Exchange entrant (contrat v2 §6.2)
  private final CacheExStatsRegistry statsRegistry; // nullable
  /** Pont vers le vrai cache sans CacheVerticle (worker C1) — nullable. */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExBridge cacheExBridge;
  private volatile com.bowman.cspng.users.CacheExPolicy cacheexPolicy =
      com.bowman.cspng.users.CacheExPolicy.OFF;
  /** CW-cycle check (V15) — nullable ; gate enabled/caids appliquée avant check. */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  private volatile com.bowman.cspng.config.CwCycleConfig cwCycleConfig;
  /** Node id de session (8 octets) — échangé via 0x3D/0x3E. */
  private final byte[] sessionNodeId = new byte[8];
  private volatile byte[] remoteNodeId;
  /** Filtre reçu du CLIENT (mode 2 : gate d'émission). */
  private volatile java.util.List<Camd35CacheExCodec.Camd35FilterEntry> clientFilterEntries;
  private final java.util.concurrent.atomic.AtomicInteger pendingPushQueue = new java.util.concurrent.atomic.AtomicInteger();
  private static final int MAX_SEND_QUEUE = 2000;
  /** Chemins amont appris par hash + métadonnées de cycle (forwarding C2). */
  private final com.bowman.cspng.connectors.cacheex.UpstreamPathStore upstreamPaths =
      new com.bowman.cspng.connectors.cacheex.UpstreamPathStore();
  private final java.util.Map<String, Boolean> pushedHashesLru =
      java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
          return size() > 1000;
        }
      });

  public Camd35TcpSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                          ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                          SessionEcmRouter router, int maxInflight, int maxPerIp, int idleTimeoutSec) {
    this(vertx, cacheVerticle, registry, authResolver, secretResolver, router,
        maxInflight, maxPerIp, idleTimeoutSec, null);
  }

  /** Constructeur complet V15 : registry de stats cacheex par pair (nullable). */
  public Camd35TcpSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                          ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                          SessionEcmRouter router, int maxInflight, int maxPerIp, int idleTimeoutSec,
                          CacheExStatsRegistry statsRegistry) {
    super(vertx, cacheVerticle, registry);
    this.statsRegistry = statsRegistry;
    new java.security.SecureRandom().nextBytes(sessionNodeId);
    this.authResolver = authResolver;
    this.secretResolver = secretResolver;
    this.router = router != null ? router : SessionEcmRouter.failClosed();
    this.maxInflight = maxInflight > 0 ? Math.min(64, maxInflight) : 32;
    this.maxPerIp = maxPerIp > 0 ? Math.min(100, maxPerIp) : 5;
    this.idleTimeoutSec = idleTimeoutSec > 0 ? idleTimeoutSec : 60;
  }

  public Camd35TcpSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                          ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                          SessionEcmRouter router) {
    this(vertx, cacheVerticle, registry, authResolver, secretResolver, router, 32, 5, 60);
  }

  public Camd35TcpSession(Vertx vertx, SessionRegistry registry,
                          ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                          SessionEcmRouter router) {
    this(vertx, null, registry, authResolver, secretResolver, router, 32, 5, 60);
  }

  public void accept(NetSocket socket) {
    this.socket = socket;
    this.remoteAddress = socket.remoteAddress().host();
    this.alive = true;
    this.sessionContext = Vertx.currentContext();

    socket.exceptionHandler(t -> logger.log(Level.WARNING, "Camd35 socket error: " + t.getMessage()));
    socket.closeHandler(v -> cleanup());

    // Timeout de pré-authentification 10s
    preauthTimerId = vertx.setTimer(PREAUTH_TIMEOUT_MS, id -> {
      if (!authenticated && alive) {
        logger.warning("Camd35 preauth timeout (10s) from " + remoteAddress);
        AuthAudit.authFail("cs378x", username, remoteAddress, "preauth-timeout");
        close();
      }
    });

    resetIdleTimer();
    socket.handler(this::onDataReceived);
  }

  private void resetIdleTimer() {
    if (idleTimerId != -1L) {
      vertx.cancelTimer(idleTimerId);
      idleTimerId = -1L;
    }
    if (idleTimeoutSec > 0 && alive) {
      idleTimerId = vertx.setTimer(idleTimeoutSec * 1000L, id -> {
        if (alive) {
          logger.warning("Camd35 idle timeout (" + idleTimeoutSec + "s) from " + remoteAddress);
          close();
        }
      });
    }
  }

  private void onDataReceived(Buffer data) {
    if (!alive || socket == null) return;
    resetIdleTimer();

    if (!authenticated) {
      handlePreauthData(data);
    } else {
      handleAuthenticatedData(data);
    }
  }

  private void handlePreauthData(Buffer data) {
    if (incomingBuffer.length() + data.length() > MAX_INPUT_BUFFER_SIZE) {
      logger.warning("Camd35 preauth buffer overflow from " + remoteAddress + " — closing");
      AuthAudit.authFail("cs378x", username, remoteAddress, "preauth-overflow");
      close();
      return;
    }
    incomingBuffer.appendBuffer(data);
    if (authInProgress) return;

    if (incomingBuffer.length() < Camd35Constants.UCRC_SIZE) {
      return; // Attendre au moins 4 octets d'ucrc
    }

    byte[] rawUcrc = incomingBuffer.getBytes(0, Camd35Constants.UCRC_SIZE);
    long ucrc = ((rawUcrc[0] & 0xFFL) << 24)
              | ((rawUcrc[1] & 0xFFL) << 16)
              | ((rawUcrc[2] & 0xFFL) << 8)
              | (rawUcrc[3] & 0xFFL);

    authInProgress = true;

    authResolver.authenticateByUcrc("cs378x", ucrc)
        .compose(auth -> {
          this.authResult = auth;
          this.clientUcrc = rawUcrc.clone();
          // Résolution du mot de passe asynchrone sur worker thread
          return secretResolver.resolvePasswordAsync(auth.secretRef(), "cs378x:" + auth.userName());
        })
        .onComplete(ar -> {
          authInProgress = false;
          if (!alive || socket == null) return;

          if (ar.failed() || ar.result() == null || ar.result().isBlank()) {
            logger.warning("Camd35 auth failed for ucrc 0x" + Long.toHexString(ucrc)
                + " from " + remoteAddress + " : " + (ar.failed() ? ar.cause().getMessage() : "empty secret"));
            AuthAudit.authFail("cs378x", "?", remoteAddress, "unknown-ucrc");
            close();
            return;
          }

          String password = ar.result();
          this.aesKey = Camd35Crypto.aesKey(password);
          this.username = authResult.userName();

          // Vérification quota connexions & IP mask
          int maxConn = 1;
          if (registry.userDirectory() != null) {
            UserRecord rec = registry.userDirectory().lookupCached(username);
            if (rec != null) {
              maxConn = rec.maxConnections();
              if (rec.ipMask() != null && !rec.ipMask().isBlank() && !"*".equals(rec.ipMask())) {
                if (!IpMatcher.isAllowed(remoteAddress, rec.ipMask())) {
                  logger.warning("Camd35 IP denied for user " + username + " from " + remoteAddress);
                  AuthAudit.authFail("cs378x", username, remoteAddress, "ip-denied");
                  close();
                  return;
                }
              }
            }
          }

          if (!registry.tryRegister(this, maxConn, maxPerIp)) {
            logger.warning("Camd35 user " + username + " exceeded connections quota (" + maxConn + ") or IP quota (" + maxPerIp + ")");
            AuthAudit.authFail("cs378x", username, remoteAddress, "quota-connections");
            close();
            return;
          }

          if (preauthTimerId != -1L) {
            vertx.cancelTimer(preauthTimerId);
            preauthTimerId = -1L;
          }

          releasePreauthLease();
          this.authenticated = true;
          this.decoder = new Camd35FrameCodec.Decoder(aesKey);
          logger.fine("Camd35 user " + username + " authenticated from " + remoteAddress);
          AuthAudit.authOk("cs378x", username, remoteAddress);
          applyCacheExPolicy();

          armAuthEpochCheck();
          armValidUntilTimer();

          // Traiter le buffer accumulé
          byte[] allBytes = incomingBuffer.getBytes();
          incomingBuffer = Buffer.buffer();
          if (allBytes.length > 0) {
            processDecoderBytes(allBytes);
          }
        });
  }

  private void armAuthEpochCheck() {
    if (epochCheckTimerId != -1L) {
      vertx.cancelTimer(epochCheckTimerId);
      epochCheckTimerId = -1L;
    }
    epochCheckTimerId = vertx.setPeriodic(1000L, id -> {
      if (!alive || !authenticated) return;
      authResolver.currentGeneration().onComplete(ar -> {
        if (!alive || !authenticated) return;
        if (ar.failed()) {
          logger.warning("Camd35 auth generation check failed (fail-closed) for " + username);
          AuthAudit.authFail("cs378x", username, remoteAddress, "generation-fail");
          close();
          return;
        }
        long currentEpoch = ar.result();
        if (authResult != null && currentEpoch > authResult.epoch()) {
          if (authResult.ucrc() != null) {
            authResolver.authenticateByUcrc("cs378x", authResult.ucrc()).onComplete(arAuth -> {
              if (!alive || !authenticated) return;
              if (arAuth.failed() || arAuth.result() == null) {
                logger.warning("Camd35 auth revoked on epoch bump for " + username);
                AuthAudit.authFail("cs378x", username, remoteAddress, "expired");
                close();
                return;
              }
              this.authResult = arAuth.result();
            });
          }
        }
      });
    });
  }

  private void armValidUntilTimer() {
    if (validUntilTimerId != -1L) {
      vertx.cancelTimer(validUntilTimerId);
      validUntilTimerId = -1L;
    }
    if (authResult != null && authResult.userValidUntil() != null) {
      long delay = Duration.between(Instant.now(), authResult.userValidUntil()).toMillis();
      if (delay <= 0) {
        logger.warning("Camd35 account already expired for " + username);
        AuthAudit.authFail("cs378x", username, remoteAddress, "expired");
        close();
      } else {
        validUntilTimerId = vertx.setTimer(delay, id -> {
          if (alive) {
            logger.warning("Camd35 account expired for " + username);
            AuthAudit.authFail("cs378x", username, remoteAddress, "expired");
            close();
          }
        });
      }
    }
  }

  private void handleAuthenticatedData(Buffer data) {
    if (data.length() > MAX_INPUT_BUFFER_SIZE) {
      logger.warning("Camd35 authenticated payload too large (" + data.length() + " bytes) — closing");
      close();
      return;
    }
    processDecoderBytes(data.getBytes());
  }

  private void processDecoderBytes(byte[] bytes) {
    Camd35FrameCodec.DecodeResult result = decoder.feed(bytes);
    while (result.status() == Camd35FrameCodec.Status.FRAME) {
      handleFrame(result.frame());
      result = decoder.feed(new byte[0]); // Continuer à vider les frames coalescées
    }

    if (result.status() == Camd35FrameCodec.Status.CRC_MISMATCH) {
      logger.warning("Camd35 CRC mismatch on frame from " + username + " — closing without reply");
      close();
    } else if (result.status() == Camd35FrameCodec.Status.OVERFLOW) {
      logger.warning("Camd35 framing overflow/corrupted from " + username + " — closing");
      close();
    }
  }

  private void handleFrame(Camd35FrameCodec.Camd35Frame frame) {
    if (!alive || socket == null) return;

    // Vérifier validité temporelle du compte
    if (authResult.userValidUntil() != null && authResult.userValidUntil().isBefore(Instant.now())) {
      logger.warning("Camd35 account expired for " + username);
      AuthAudit.authFail("cs378x", username, remoteAddress, "expired");
      close();
      return;
    }

    switch (frame.cmd()) {
      case Camd35Constants.CMD_ECM -> processEcmRequest(frame);
      case Camd35Constants.CMD_ECM_CASCADING -> {
        // Choix utilisateur standard vérifié : lire frame complète + NOK 0x44
        sendNokReply(frame);
      }
      case Camd35Constants.CMD_KEEPALIVE -> {
        // Réponse keepalive [55, 1, 0]
        Camd35FrameCodec.Camd35Frame resp = new Camd35FrameCodec.Camd35Frame(
            clientUcrc, Camd35Constants.CMD_KEEPALIVE, frame.srvid(), frame.caid(),
            frame.prid(), frame.idx(), frame.pid(), new byte[]{0}, new byte[0]);
        sendFrame(resp);
      }
      case Camd35Constants.CMD_EMM, Camd35Constants.CMD_EMM_19, Camd35Constants.CMD_AU -> {
        // Dropper silencieusement sans réponse 0x44
        logger.fine("Camd35 dropping unhandled cmd 0x" + Integer.toHexString(frame.cmd()) + " from " + username);
      }
      case Camd35Constants.CMD_CACHEEX_FILTER -> handleCacheExFilter(frame);
      case Camd35Constants.CMD_CACHEEX_ID_REQ -> handleCacheExIdRequest(frame);
      case Camd35Constants.CMD_CACHEEX_ID_ANSWER -> handleCacheExIdAnswer(frame);
      case Camd35Constants.CMD_CACHEEX_PUSH -> handleCacheExPush(frame);
      default -> {
        logger.fine("Camd35 unknown command 0x" + Integer.toHexString(frame.cmd()) + " dropped");
      }
    }
  }

  private void processEcmRequest(Camd35FrameCodec.Camd35Frame frame) {
    byte[] ecm = frame.data();
    if (ecm == null || ecm.length < 4 || ecm.length > Camd35Constants.MAX_ECM_SIZE) {
      logger.warning("Camd35 invalid ECM length (" + (ecm == null ? 0 : ecm.length) + ") from " + username + " — closing");
      close();
      return;
    }

    // Validation de section DVB (SCT_LEN)
    int declaredLen = 3 + (((ecm[1] & 0x0F) << 8) | (ecm[2] & 0xFF));
    if (declaredLen != ecm.length) {
      logger.warning("Camd35 ECM declared DVB len " + declaredLen + " != wire data len " + ecm.length + " — closing");
      close();
      return;
    }

    int idx = frame.idx();

    // Contrôle in-flight max
    if (maxInflight > 0 && inFlightIdx.size() >= maxInflight) {
      logger.warning("Camd35 in-flight quota reached (" + inFlightIdx.size() + "/" + maxInflight + ") for " + username);
      sendNokReply(frame);
      return;
    }

    // Contrôle rate limit global utilisateur (selon config allowedEcmRate : > 0 = limite req/min, 0 ou -1 = illimité)
    int ecmRate = (authResult != null) ? authResult.allowedEcmRate() : -1;
    if (ecmRate > 0 && !registry.checkUserRateLimit(username, ecmRate)) {
      logger.warning("Camd35 user " + username + " rate limit exceeded (" + ecmRate + " req/min)");
      sendNokReply(frame);
      return;
    }

    // Protection doublon idx
    if (inFlightIdx.putIfAbsent(idx, System.currentTimeMillis()) != null) {
      logger.warning("Camd35 duplicate idx " + idx + " from " + username + " — sending NOK");
      sendNokReply(frame);
      return;
    }

    // Vérifier ACL service / profiles
    int srvid = frame.srvid();
    if (authResult.blockedServices() != null) {
      for (Set<Integer> blocked : authResult.blockedServices().values()) {
        if (blocked != null && blocked.contains(srvid)) {
          inFlightIdx.remove(idx);
          sendNokReply(frame);
          return;
        }
      }
    }

    // Domaine : EcmKey avec dataHash sur le corps amputé (ecm[3..])
    byte[] amputatedBody = Arrays.copyOfRange(ecm, 3, ecm.length);
    int dataHash = computeHash(amputatedBody);
    EcmKey key = new EcmKey(ecm[0] & 0xFF, frame.srvid(), 0, frame.caid(), dataHash);

    EcmRequest ecmRequest = new EcmRequest(
        key, amputatedBody, profileName != null ? profileName : "default",
        remoteAddress, username, System.currentTimeMillis(), null, 5000);

    router.routeEcm(ecmRequest, authResult, frame.prid())
        .onComplete(ar -> {
          inFlightIdx.remove(idx);
          if (!alive || socket == null) return;

          if (authResult != null && authResult.ucrc() != null) {
            authResolver.authenticateByUcrc("cs378x", authResult.ucrc()).onComplete(arAuth -> {
              if (!alive || socket == null) return;
              if (arAuth.failed() || arAuth.result() == null) {
                logger.warning("Camd35 user revoked mid-flight for " + username);
                AuthAudit.authFail("cs378x", username, remoteAddress, "expired");
                sendNokReply(frame);
                close();
                return;
              }
              this.authResult = arAuth.result();
              if (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) {
                sendCwReply(frame, ar.result().cw());
              } else {
                sendNokReply(frame);
              }
            });
          } else {
            if (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) {
              sendCwReply(frame, ar.result().cw());
            } else {
              sendNokReply(frame);
            }
          }
        });
  }

  // ============================================================
  // Cache-Exchange entrant (contrat v2 §6.2) — CS378x 0x3C-0x3F
  // ============================================================

  /** Applique la politique cacheex du compte après auth. */
  private void applyCacheExPolicy() {
    com.bowman.cspng.users.CacheExPolicy policy =
        authResult != null && authResult.cacheex() != null
            ? authResult.cacheex() : com.bowman.cspng.users.CacheExPolicy.OFF;
    this.cacheexPolicy = policy;
    if (!policy.enabled()) return;
    if (statsRegistry != null) {
      statsRegistry.registerInbound(cacheExPeerId(), "cs378x", policy.mode(),
          profileName != null ? profileName : "", policy.maxHop(), MAX_SEND_QUEUE);
      statsRegistry.updateState(cacheExPeerId(), true, "connected", false);
      String hexNodeId = remoteNodeId != null && remoteNodeId.length == 8
          ? java.util.HexFormat.of().withUpperCase().formatHex(remoteNodeId) : null;
      statsRegistry.updatePeerIdentity(cacheExPeerId(), remoteAddress, hexNodeId);
    }
    if (policy.mode() == 3) {
      // Mode 3 : NOUS recevons du client — annoncer NOTRE filtre (gate d'acceptation).
      try {
        sendOwnFilter(Camd35CacheExCodec.entriesFromSpec(policy.filter()));
      } catch (IllegalArgumentException e) {
        logger.warning("Camd35 cacheex filter invalid for " + username + ": " + e.getMessage());
      }
    } else if (policy.mode() == 2) {
      // Mode 2 : NOUS émettons vers le client — s'abonner aux CW du cache (SPI
      // existant si CacheVerticle, sinon enregistrement auprès du pont worker).
      if (cacheVerticle != null) {
        cacheVerticle.addTransport(this);
      } else if (cacheExBridge != null) {
        cacheExBridge.registerEmitter(cacheExPeerId(), this);
      }
    }
  }

  /** Mode 3 : envoie NOTRE filtre (0x3C, 242 octets) — vide = accept-all. */
  private void sendOwnFilter(java.util.List<Camd35CacheExCodec.Camd35FilterEntry> entries) {
    byte[] body = Camd35CacheExCodec.encodeFilterBody(entries);
    Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
        clientUcrc != null ? clientUcrc : new byte[4], Camd35Constants.CMD_CACHEEX_FILTER, 0, 0, 0, 0, 0, body, new byte[0]);
    sendFrame(frame);
    logger.fine("Camd35 CacheEx FILTER sent to " + username
        + " (" + (entries.isEmpty() ? "accept-all" : entries.size() + " entries") + ")");
  }

  /** FILTER 0x3C reçu du client — mode 2 : gate d'émission vers ce client. */
  private void handleCacheExFilter(Camd35FrameCodec.Camd35Frame frame) {
    if (cacheexPolicy.mode() != 2) {
      logger.fine("Camd35 CacheEx FILTER from " + username + " ignored — not in emit mode");
      return;
    }
    try {
      java.util.List<Camd35CacheExCodec.Camd35FilterEntry> entries =
          Camd35CacheExCodec.parseFilterBody(frame.data());
      clientFilterEntries = entries.isEmpty() ? null : entries;
      if (statsRegistry != null) {
        statsRegistry.updateState(cacheExPeerId(), true, "connected", clientFilterEntries != null);
      }
      logger.fine("Camd35 CacheEx FILTER received from " + username
          + " (" + entries.size() + " entries)");
    } catch (Exception e) {
      logger.fine("Camd35 CacheEx FILTER parse error from " + username + ": " + e.getMessage());
    }
  }

  /** ID request 0x3D : sauver le node id du client, répondre 0x3E avec le nôtre. */
  private void handleCacheExIdRequest(Camd35FrameCodec.Camd35Frame frame) {
    if (frame.data() != null && frame.data().length >= 8) {
      this.remoteNodeId = Arrays.copyOf(frame.data(), 8);
      if (statsRegistry != null && cacheexPolicy.enabled()) {
        String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
        statsRegistry.updatePeerIdentity(cacheExPeerId(), remoteAddress, hexNodeId);
      }
    }
    byte[] body = new byte[12];
    System.arraycopy(sessionNodeId, 0, body, 0, 8);
    Camd35FrameCodec.Camd35Frame answer = new Camd35FrameCodec.Camd35Frame(
        clientUcrc != null ? clientUcrc : new byte[4],
        Camd35Constants.CMD_CACHEEX_ID_ANSWER, 0, 0, 0, 0, 0, body, new byte[0]);
    sendFrame(answer);
    if (statsRegistry != null && cacheexPolicy.enabled()) {
      statsRegistry.updateState(cacheExPeerId(), true, "connected",
          cacheexPolicy.mode() == 2 && clientFilterEntries != null);
    }
  }

  /** ID answer 0x3E : sauver le node id du client. */
  private void handleCacheExIdAnswer(Camd35FrameCodec.Camd35Frame frame) {
    if (frame.data() != null && frame.data().length >= 8) {
      this.remoteNodeId = Arrays.copyOf(frame.data(), 8);
      if (statsRegistry != null && cacheexPolicy.enabled()) {
        String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
        statsRegistry.updatePeerIdentity(cacheExPeerId(), remoteAddress, hexNodeId);
      }
    }
  }

  /** PUSH 0x3F reçu du client — accepté uniquement en mode 3 (nous recevons). */
  private void handleCacheExPush(Camd35FrameCodec.Camd35Frame frame) {
    if (cacheexPolicy.mode() != 3) {
      logger.fine("Camd35 CacheEx PUSH from " + username + " ignored — account not in receive mode");
      countDrop("invalid");
      return;
    }

    // R3 anti-flood RX : budget allowedEcmRate, fenêtre dédiée "<user>/cacheex".
    int pushRate = (authResult != null) ? authResult.allowedEcmRate() : -1;
    if (pushRate > 0 && registry != null
        && !registry.checkUserRateLimit(username + "/cacheex", pushRate)) {
      countDrop("flood");
      logger.fine("Camd35 CacheEx PUSH dropped — RX flood from user " + username);
      return;
    }
    Camd35CacheExCodec.Camd35Push push;
    try {
      push = Camd35CacheExCodec.parsePush(frame);
    } catch (Exception e) {
      countDrop("invalid");
      return;
    }
    if (remoteNodeId == null && !push.nodeIds().isEmpty()) {
      byte[] first = push.nodeIds().get(0);
      if (first != null && first.length == 8) {
        this.remoteNodeId = Arrays.copyOf(first, 8);
        if (statsRegistry != null && cacheexPolicy.enabled()) {
          String hexNodeId = java.util.HexFormat.of().withUpperCase().formatHex(this.remoteNodeId);
          statsRegistry.updatePeerIdentity(cacheExPeerId(), remoteAddress, hexNodeId);
        }
      }
    }
    // rc : E_FOUND(0) / E_UNHANDLED(100) — miroir camd35_cacheex_push_in
    if (push.rc() != 0 && push.rc() != 100) {
      countDrop("invalid", push.caid(), push.prid(), push.srvid());
      return;
    }
    for (byte[] n : push.nodeIds()) {
      if (Arrays.equals(n, sessionNodeId)) {
        countDrop("loop", push.caid(), push.prid(), push.srvid());
        return;
      }
    }
    if (push.nodeIds().size() > cacheexPolicy.maxHop()) {
      countDrop("hop", push.caid(), push.prid(), push.srvid());
      return;
    }
    // Gate : NOTRE filtre configuré (mode 3)
    try {
      java.util.List<Camd35CacheExCodec.Camd35FilterEntry> gate =
          Camd35CacheExCodec.entriesFromSpec(cacheexPolicy.filter());
      if (!gate.isEmpty()) {
        boolean matches = false;
        for (Camd35CacheExCodec.Camd35FilterEntry e : gate) {
          if ((push.caid() & e.cmask()) == e.caid()
              && (e.prid() == 0 || e.prid() == push.prid())) {
            matches = true;
            break;
          }
        }
        if (!matches) {
          countDrop("filtered", push.caid(), push.prid(), push.srvid());
          return;
        }
      }
    } catch (IllegalArgumentException e) {
      countDrop("invalid", push.caid(), push.prid(), push.srvid());
      return;
    }
    // R2: Validation parité (0, 0x80, 0x81) AVANT toute allocation ou suivi quorum
    // Compat OSCam : 0 = INCONNU accepté (bypass cycle compté IGNORED, servable) ;
    // autres valeurs => rejet AVANT tout traitement.
    if (push.oddEven() != 0x80 && push.oddEven() != 0x81 && push.oddEven() != 0) {
      countDrop("invalid", push.caid(), push.prid(), push.srvid());
      logger.fine("Camd35 session CacheEx PUSH dropped — invalid oddEven 0x"
          + Integer.toHexString(push.oddEven()));
      return;
    }
    int oe = push.oddEven();

    byte[] cw = push.cw();

    // CW-cycle check (V15) : rejet AVANT injection (métadonnée pair [18])
    if (cwCycleChecker != null && cwCycleConfig != null && cwCycleConfig.enabled()
        && cwCycleConfig.appliesToCaid(push.caid())) {
      com.bowman.cspng.filters.CwCycleChecker.Verdict cycleVerdict = cwCycleChecker.check(
          profileName, push.caid(), push.prid(), push.srvid(), oe, cw,
          push.cycleTimeSec(), push.nextCwCycle(), System.currentTimeMillis());
      if (cwCycleChecker.shouldReject(cycleVerdict)) {
        countDrop("invalid", push.caid(), push.prid(), push.srvid());
        logger.fine("Camd35 session CacheEx PUSH dropped — cw-cycle " + cycleVerdict);
        return;
      }
    }

    String peerId = cacheExPeerId();
    String realOrigin = com.bowman.cspng.connectors.cacheex.CacheExIngressGate.extractOrigin(
        push.nodeIds(), remoteAddress);
    com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate = ingressGate;
    if (gate != null) {
      var verdict = gate.preInject(profileName, push.caid(), push.prid(), push.srvid(),
          oe, push.dataHash(), cw, peerId, realOrigin,
          com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
      if (!verdict.accept()) {
        countDrop(verdict.dropReason(), push.caid(), push.prid(), push.srvid());
        return;
      }
    } else {
      if (!DcwValidator.checksumDcw(cw)) {
        countDrop("checksum", push.caid(), push.prid(), push.srvid());
        return;
      }
      if (DcwValidator.hasFiveZeroes(cw) || DcwValidator.hasZeroDcw(cw)) {
        countDrop("invalid", push.caid(), push.prid(), push.srvid());
        return;
      }
    }

    // Mémoriser le chemin amont + métadonnée de cycle pour re-push futur (C2)
    // UNIQUEMENT après acceptation de la gate (éviter la pollution sur rejet)
    noteUpstreamPath(push.dataHash(), push.nodeIds(), push.cycleTimeSec(), push.nextCwCycle());

    EcmKey key = new EcmKey(oe, push.srvid(), 0, push.caid(), push.dataHash());
    CwReply reply = new CwReply(key, oe, cw.clone(), peerId, remoteAddress,
        System.currentTimeMillis());
    String peerProfile = profileName != null ? profileName : "default";
    if (cacheVerticle != null) {
      EcmRequest synthetic = new EcmRequest(key, new byte[0],
          peerProfile, remoteAddress, username,
          System.currentTimeMillis(), null, 5000);
      cacheVerticle.handleReply(synthetic, reply);
      if (statsRegistry != null) statsRegistry.countReceived(cacheExPeerId(), push.caid(), push.prid(), push.srvid());
      if (gate != null) gate.postInjected(peerId);
    } else if (cacheExBridge != null && cacheExBridge.feedReceived(
        peerProfile, listenerId, push.caid(), push.prid(), push.srvid(), oe, cw,
        push.dataHash(), push.cycleTimeSec(), push.nextCwCycle(),
        push.nodeIds(), remoteAddress, peerId)) {
      if (statsRegistry != null) statsRegistry.countReceived(cacheExPeerId(), push.caid(), push.prid(), push.srvid());
      if (gate != null) gate.postInjected(peerId);
    } else {
      countDrop("invalid", push.caid(), push.prid(), push.srvid());
      logger.fine("Camd35 session CacheEx PUSH dropped — no routable cache (worker bridge absent?)");
    }
  }

  /**
   * CacheTransport — mode 2 : pousser les CW du cache vers le client,
   * gate = filtre 0x3C reçu, borne de file 2000 messages (D3).
   */
  @Override
  public void onReply(CwReply reply) {
    if (cacheexPolicy.mode() != 2) return;
    if (!alive || !authenticated || socket == null || aesKey == null) return;
    if (reply == null || reply.isEmpty()) return;
    if (reply.originAddress() != null && reply.originAddress().equals(remoteAddress)) return;

    String dedupKey = reply.key().dataHash() + ":" + remoteAddress;
    if (pushedHashesLru.containsKey(dedupKey)) return;

    // Gate : filtre du client (0x3C)
    java.util.List<Camd35CacheExCodec.Camd35FilterEntry> gate = clientFilterEntries;
    if (gate != null && !gate.isEmpty()) {
      boolean matches = false;
      for (Camd35CacheExCodec.Camd35FilterEntry e : gate) {
        if ((reply.key().caId() & e.cmask()) == e.caid()) {
          matches = true;
          break;
        }
      }
      if (!matches) {
        countDrop("filtered", reply.key().caId(), 0, reply.key().serviceId());
        return;
      }
    }

    for (;;) {
      int pending = pendingPushQueue.get();
      if (pending >= MAX_SEND_QUEUE) {
        countDrop("queue", reply.key().caId(), 0, reply.key().serviceId());
        return;
      }
      if (pendingPushQueue.compareAndSet(pending, pending + 1)) break;
    }

    // Chemin de nodes (forwarding C2) : notre sessionNodeId FIRST + chemin amont appris
    com.bowman.cspng.connectors.cacheex.UpstreamPathStore.PathEntry stored =
        upstreamPaths.get(reply.key().dataHash());
    java.util.List<byte[]> knownPath = stored != null ? stored.nodes() : java.util.List.of();
    java.util.List<byte[]> allNodes = new java.util.ArrayList<>(knownPath.size() + 1);
    allNodes.add(sessionNodeId);
    for (byte[] n : knownPath) {
      if (!Arrays.equals(n, sessionNodeId)) allNodes.add(n);
    }
    if (allNodes.size() > cacheexPolicy.maxHop()) {
      countDrop("hop", reply.key().caId(), 0, reply.key().serviceId());
      logger.fine("Camd35 session CacheEx PUSH(OUT) skipped — hop count "
          + allNodes.size() + " > max " + cacheexPolicy.maxHop());
      return;
    }
    byte[] body = Camd35CacheExCodec.encodePushBody(
        new byte[16], reply.key().dataHash(), reply.cw(), allNodes);
    // Champ pid [18..19] : buf[18] = cycletime (bit 0x80 flag next-cycle), buf[19] = odd/even
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
        clientUcrc != null ? clientUcrc : new byte[4],
        Camd35Constants.CMD_CACHEEX_PUSH,
        reply.key().serviceId() & 0xFFFF, reply.key().caId() & 0xFFFF,
        0, 0, pidField, body, new byte[0], 0 /* rc = E_FOUND */);
    pushedHashesLru.put(dedupKey, Boolean.TRUE);
    io.vertx.core.Context ctx = sessionContext;
    if (ctx != null) {
      ctx.runOnContext(v -> {
        try {
          if (alive && socket != null) {
            sendFrame(frame);
          }
        } finally {
          pendingPushQueue.decrementAndGet();
        }
      });
    } else {
      pendingPushQueue.decrementAndGet();
    }
    if (statsRegistry != null) statsRegistry.countPushed(cacheExPeerId(), reply.key().caId(), 0, reply.key().serviceId());
  }

  @Override
  public void onRequest(EcmRequest request) {
    // No-op
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

  /**
   * Porte d'ingress qualité/quarantaine/quorum (lots 1/2/4/5) — nullable.
   * Absente : garde historique {@code DcwValidator} inchangée.
   */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate;

  /** Injection de la porte d'ingress (nullable — aucun effet tant que null). */
  public void setIngressGate(com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate) {
    this.ingressGate = gate;
  }

  /**
   * Mémorise le chemin de nodes amont d'un hash (forwarding C2) : le re-push
   * émettra {@code [sessionNodeId, ...upstreamPath]} (sans doublon).
   */
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

  private void countDrop(String reason) {
    if (statsRegistry != null) statsRegistry.countDropped(cacheExPeerId(), reason);
  }

  private void countDrop(String reason, int caid, int provid, int srvid) {
    if (statsRegistry != null) statsRegistry.countDropped(cacheExPeerId(), reason, caid, provid, srvid);
  }

  private void sendCwReply(Camd35FrameCodec.Camd35Frame req, byte[] cw) {
    Camd35FrameCodec.Camd35Frame cwFrame = new Camd35FrameCodec.Camd35Frame(
        clientUcrc, Camd35Constants.CMD_CW, req.srvid(), req.caid(),
        req.prid(), req.idx(), req.pid(), cw, new byte[0]);
    sendFrame(cwFrame);
  }

  private void sendNokReply(Camd35FrameCodec.Camd35Frame req) {
    Camd35FrameCodec.Camd35Frame nok = new Camd35FrameCodec.Camd35Frame(
        clientUcrc, Camd35Constants.CMD_NOK_NOTFOUND, req.srvid(), req.caid(),
        req.prid(), req.idx(), req.pid(), new byte[0], new byte[0]);
    sendFrame(nok);
  }

  private void sendFrame(Camd35FrameCodec.Camd35Frame frame) {
    if (socket == null || aesKey == null) return;
    byte[] wire = Camd35FrameCodec.encode(frame, aesKey);
    socket.write(Buffer.buffer(wire));
  }

  protected void cleanup() {
    alive = false;
    if (cacheexPolicy.enabled()) {
      try {
        if (cacheexPolicy.mode() == 2) {
          if (cacheVerticle != null) {
            cacheVerticle.removeTransport(this);
          }
          if (cacheExBridge != null) {
            cacheExBridge.unregisterEmitter(cacheExPeerId());
          }
        }
      } catch (Exception ignored) {}
      if (statsRegistry != null) {
        statsRegistry.updateState(cacheExPeerId(), false, "disconnected", false);
      }
    }
    if (preauthTimerId != -1L) {
      vertx.cancelTimer(preauthTimerId);
      preauthTimerId = -1L;
    }
    if (epochCheckTimerId != -1L) {
      vertx.cancelTimer(epochCheckTimerId);
      epochCheckTimerId = -1L;
    }
    if (validUntilTimerId != -1L) {
      vertx.cancelTimer(validUntilTimerId);
      validUntilTimerId = -1L;
    }
    if (idleTimerId != -1L) {
      vertx.cancelTimer(idleTimerId);
      idleTimerId = -1L;
    }
    inFlightIdx.clear();
    if (socket != null) {
      socket.handler(null);
    }
    fireCloseHooks();
    registry.unregister(this);
  }

  @Override
  public void close() {
    alive = false;
    if (socket != null) {
      socket.close();
    }
    cleanup();
  }

  private static int computeHash(byte[] data) {
    int h = 0;
    for (byte b : data) h = 31 * h + (b & 0xFF);
    return h;
  }
}
