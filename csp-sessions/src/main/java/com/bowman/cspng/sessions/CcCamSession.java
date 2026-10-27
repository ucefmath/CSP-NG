package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cccam.CcServerCodec;
import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.IpMatcher;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import com.bowman.cspng.users.UserRecord;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Session serveur CCcam (inbound CCcam 2.1.x / 2.3.x).
 *
 * <p>Contrats P4/P5/P8 (docs/INBOUND-CAMS-PLAN.md §P4/§P5/§P8) :
 * <ul>
 *   <li><b>Handshake miroir</b> : seed 12+4 aléatoire généré côté serveur,
 *       mutation in-place ({@link CcServerCodec#initServerHandshake}),
 *       username case-exact, token vérifié avec password résolu de manière asynchrone
 *       sur worker thread ({@link SecretResolver}).</li>
 *   <li><b>Preauth timeout</b> : 10s maximum.</li>
 *   <li><b>CLI_DATA & SRV_DATA</b> : extraction du {@code clientNodeId} pour le CW scrambling,
 *       envoi de SRV_DATA (72 octets) et initial cards.</li>
 *   <li><b>Annonces de cartes (P8)</b> : mode manual vs wildcard, plafonds de reshare,
 *       debounce delta et retraits immédiats via {@link CardDiscoveryService}.</li>
 *   <li><b>Réponses CW & synchro</b> : {@link CcServerCodec#encodeCwReply} avec le CW
 *       scramblé + flag sacrificiel [0] pour le ré-alignement miroir.</li>
 *   <li><b>Validation section ECM</b> : SCT_LEN vérifié sur wire complet, corps amputé
 *       acheminé vers le domaine {@link EcmRequest}.</li>
 *   <li><b>Ordonnancement FIFO</b> : file d'attente FIFO stricte des réponses ECM (CW & NOK).</li>
 *   <li><b>Auth Lease / Epoch check</b> : check périodique ≤ 1s, révocation dynamique et fail-closed.</li>
 * </ul>
 */
public class CcCamSession extends AbstractSession implements com.bowman.cspng.connectors.cacheex.CacheExEmitter {

  public static final long PREAUTH_TIMEOUT_MS = 10_000L;
  public static final int MAX_INPUT_BUFFER_SIZE = 8_192;
  public static final int MAX_DECLARED_PAYLOAD = 1_023;

  private static final int STATE_NEED_HASH = 0;
  private static final int STATE_NEED_USER = 1;
  private static final int STATE_NEED_PASS_TOKEN = 2;
  private static final int STATE_NEED_CLI_DATA = 3;
  private static final int STATE_NORMAL = 4;

  private final ProtocolAuthResolver authResolver;
  private final SecretResolver secretResolver;
  private final SessionEcmRouter router;
  private final CardDiscoveryService discoveryService;
  private final byte[] serverNodeId;
  private final String cardMode;
  private final int listenerCap;
  private final int maxCards;
  private final int maxInflight;
  private final int maxPerIp;
  private final int idleTimeoutSec;
  private final SecureRandom random = new SecureRandom();

  private NetSocket socket;
  private Context sessionContext;
  private long preauthTimerId = -1L;
  private long epochCheckTimerId = -1L;
  private long validUntilTimerId = -1L;
  private long idleTimerId = -1L;
  private volatile boolean authInProgress = false;

  private final CcCrypt encBlock = new CcCrypt(); // serveur → client
  private final CcCrypt decBlock = new CcCrypt(); // client → serveur

  private byte[] clientNodeId;
  private ProtocolAuthResolver.AuthResult authResult;
  private CardDiscoveryService.CardAnnouncementListener announcementListener;
  private final Set<Long> announcedCardIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private int state = STATE_NEED_HASH;
  private Buffer accumulator = Buffer.buffer();
  private boolean headerPhase = true;
  private int currentCmd = 0;
  private int currentPayloadLen = 0;

  private final AtomicInteger inFlightCount = new AtomicInteger(0);
  private final Queue<PendingReply> pendingQueue = new ArrayDeque<>();
  private long nextSeq = 1L;

  // Cache-Exchange entrant (contrat v2 §6.2) — policy portée par AuthResult
  private final CacheExStatsRegistry statsRegistry; // nullable
  /** Pont vers le vrai cache sans CacheVerticle (worker C1) — nullable. */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExBridge cacheExBridge;
  /** CW-cycle check (V15) — nullable ; gate enabled/caids appliquée avant check. */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  private volatile com.bowman.cspng.config.CwCycleConfig cwCycleConfig;
  private volatile com.bowman.cspng.users.CacheExPolicy cacheexPolicy =
      com.bowman.cspng.users.CacheExPolicy.OFF;
  /** Filtre reçu du CLIENT (mode 2 : gate d'émission vers le client). */
  private volatile List<byte[]> clientCacheexFilter;
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

  private static class PendingReply {
    final long seq;
    final int cardId;
    volatile boolean completed = false;
    volatile CwReply cwReply = null;

    PendingReply(long seq, int cardId) {
      this.seq = seq;
      this.cardId = cardId;
    }
  }

  public CcCamSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                      ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                      SessionEcmRouter router, CardDiscoveryService discoveryService,
                      byte[] serverNodeId, String cardMode, int listenerCap, int maxCards,
                      int maxInflight, int maxPerIp, int idleTimeoutSec) {
    this(vertx, cacheVerticle, registry, authResolver, secretResolver, router, discoveryService,
        serverNodeId, cardMode, listenerCap, maxCards, maxInflight, maxPerIp, idleTimeoutSec, null);
  }

  /** Constructeur complet V15 : registry de stats cacheex par pair (nullable). */
  public CcCamSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                      ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                      SessionEcmRouter router, CardDiscoveryService discoveryService,
                      byte[] serverNodeId, String cardMode, int listenerCap, int maxCards,
                      int maxInflight, int maxPerIp, int idleTimeoutSec,
                      CacheExStatsRegistry statsRegistry) {
    super(vertx, cacheVerticle, registry);
    this.statsRegistry = statsRegistry;
    this.authResolver = authResolver;
    this.secretResolver = secretResolver;
    this.router = router != null ? router : SessionEcmRouter.failClosed();
    this.discoveryService = discoveryService;
    this.cardMode = cardMode != null ? cardMode : "manual";
    this.listenerCap = Math.max(0, Math.min(15, listenerCap));
    this.maxCards = maxCards > 0 ? Math.min(4096, maxCards) : 512;
    this.maxInflight = maxInflight > 0 ? Math.min(64, maxInflight) : 32;
    this.maxPerIp = maxPerIp > 0 ? Math.min(100, maxPerIp) : 5;
    this.idleTimeoutSec = idleTimeoutSec > 0 ? idleTimeoutSec : 60;
    this.serverNodeId = serverNodeId != null && serverNodeId.length == 8
        ? serverNodeId.clone() : new byte[8];
    if (serverNodeId == null || serverNodeId.length != 8) {
      random.nextBytes(this.serverNodeId);
    }
  }

  public CcCamSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                      ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                      SessionEcmRouter router, CardDiscoveryService discoveryService,
                      byte[] serverNodeId, String cardMode, int listenerCap, int maxCards) {
    this(vertx, cacheVerticle, registry, authResolver, secretResolver, router, discoveryService,
        serverNodeId, cardMode, listenerCap, maxCards, 32, 5, 60);
  }

  public CcCamSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                      ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                      SessionEcmRouter router, byte[] serverNodeId) {
    this(vertx, cacheVerticle, registry, authResolver, secretResolver, router, null, serverNodeId, "manual", 0, 512);
  }

  public CcCamSession(Vertx vertx, SessionRegistry registry,
                      ProtocolAuthResolver authResolver, SecretResolver secretResolver,
                      SessionEcmRouter router, byte[] serverNodeId) {
    this(vertx, null, registry, authResolver, secretResolver, router, null, serverNodeId, "manual", 0, 512);
  }

  public void accept(NetSocket socket) {
    this.socket = socket;
    this.remoteAddress = socket.remoteAddress().host();
    this.alive = true;
    this.sessionContext = Vertx.currentContext();

    socket.exceptionHandler(t -> logger.log(Level.WARNING, "CCcam socket error: " + t.getMessage()));
    socket.closeHandler(v -> cleanup());

    preauthTimerId = vertx.setTimer(PREAUTH_TIMEOUT_MS, id -> {
      if (!authenticated && alive) {
        logger.warning("CCcam preauth timeout (10s) from " + remoteAddress);
        AuthAudit.authFail("cccam", username, remoteAddress, "preauth-timeout");
        close();
      }
    });

    resetIdleTimer();

    // 1. Seed 16 octets (12 random + 4 checksums) envoyé en clair
    byte[] seed = CcServerCodec.generateSeed(random);
    socket.write(Buffer.buffer(seed));

    // 2. Initialisation des blocs avec mutation in-place (contrat OSCam l.4263-4273)
    CcServerCodec.initServerHandshake(seed, encBlock, decBlock);

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
          logger.warning("CCcam idle timeout (" + idleTimeoutSec + "s) from " + remoteAddress);
          close();
        }
      });
    }
  }

  private void onDataReceived(Buffer data) {
    if (!alive || socket == null) return;
    resetIdleTimer();
    if (accumulator.length() + data.length() > MAX_INPUT_BUFFER_SIZE) {
      logger.warning("CCcam input buffer overflow from " + remoteAddress + " — closing");
      if (!authenticated) {
        AuthAudit.authFail("cccam", username, remoteAddress, "preauth-overflow");
      }
      close();
      return;
    }
    accumulator.appendBuffer(data);
    processBuffer();
  }

  private void processBuffer() {
    boolean progress = true;
    while (progress && alive) {
      progress = false;
      switch (state) {
        case STATE_NEED_HASH -> {
          if (accumulator.length() >= 20) {
            byte[] clientHash = accumulator.getBytes(0, 20);
            decBlock.crypt(clientHash, 20, CcConstants.DECRYPT);
            accumulator = Buffer.buffer(accumulator.getBytes(20, accumulator.length()));
            state = STATE_NEED_USER;
            progress = true;
          }
        }
        case STATE_NEED_USER -> {
          if (accumulator.length() >= 20) {
            byte[] userBytes = accumulator.getBytes(0, 20);
            decBlock.crypt(userBytes, 20, CcConstants.DECRYPT);
            accumulator = Buffer.buffer(accumulator.getBytes(20, accumulator.length()));

            // Vérification caractères non imprimables
            for (byte b : userBytes) {
              if (b > 0 && b < 0x20) {
                logger.warning("CCcam illegal username from " + remoteAddress);
                AuthAudit.authFail("cccam", "?", remoteAddress, "illegal-username");
                close();
                return;
              }
            }
            int nullIdx = 0;
            while (nullIdx < 20 && userBytes[nullIdx] != 0) nullIdx++;
            String user = new String(userBytes, 0, nullIdx, StandardCharsets.US_ASCII);
            if (user.length() > 20 || user.isEmpty()) {
              logger.warning("CCcam username invalid length from " + remoteAddress);
              AuthAudit.authFail("cccam", user.isEmpty() ? "?" : user, remoteAddress, "username-length");
              close();
              return;
            }
            this.username = user;
            state = STATE_NEED_PASS_TOKEN;
            progress = true;
          }
        }
        case STATE_NEED_PASS_TOKEN -> {
          if (accumulator.length() >= 6 && !authInProgress) {
            byte[] token = accumulator.getBytes(0, 6);
            accumulator = Buffer.buffer(accumulator.getBytes(6, accumulator.length()));
            authInProgress = true;

            authResolver.authenticate("cccam", username)
                .compose(auth -> {
                  this.authResult = auth;
                  return secretResolver.resolvePasswordAsync(auth.secretRef(), "cccam:" + auth.userName());
                })
                .onComplete(ar -> {
                  authInProgress = false;
                  if (!alive || socket == null) return;

                  if (ar.failed() || ar.result() == null || ar.result().isBlank()) {
                    logger.warning("CCcam auth failed for user " + username + " from " + remoteAddress);
                    AuthAudit.authFail("cccam", username, remoteAddress, "unknown-user");
                    close();
                    return;
                  }

                  String password = ar.result();
                  boolean passOk = CcServerCodec.checkPasswordToken(token, password, encBlock, decBlock);
                  if (!passOk) {
                    logger.warning("CCcam password mismatch for user " + username);
                    AuthAudit.authFail("cccam", username, remoteAddress, "password-mismatch");
                    close();
                    return;
                  }

                  // Quotas connexions & IP
                  int maxConn = 1;
                  if (registry.userDirectory() != null) {
                    UserRecord rec = registry.userDirectory().lookupCached(username);
                    if (rec != null) {
                      maxConn = rec.maxConnections();
                      if (rec.ipMask() != null && !rec.ipMask().isBlank() && !"*".equals(rec.ipMask())) {
                        if (!IpMatcher.isAllowed(remoteAddress, rec.ipMask())) {
                          logger.warning("CCcam IP denied for user " + username);
                          AuthAudit.authFail("cccam", username, remoteAddress, "ip-denied");
                          close();
                          return;
                        }
                      }
                    }
                  }

                  if (!registry.tryRegister(this, maxConn, maxPerIp)) {
                    logger.warning("CCcam user " + username + " exceeded connections quota (" + maxConn + ") or IP quota (" + maxPerIp + ")");
                    AuthAudit.authFail("cccam", username, remoteAddress, "quota-connections");
                    close();
                    return;
                  }

                  // Envoyer ACK 20
                  byte[] ack = CcServerCodec.buildLoginAck();
                  encBlock.crypt(ack, 20, CcConstants.ENCRYPT);
                  socket.write(Buffer.buffer(ack));

                  state = STATE_NEED_CLI_DATA;
                  processBuffer(); // continuer le parsing
                });
          }
        }
        case STATE_NEED_CLI_DATA -> {
          DecodedFrame frame = tryReadFrame();
          if (frame == null) return;

          if (frame.cmd != CcConstants.MSG_CLI_DATA || frame.payload.length < 66) {
            logger.warning("CCcam invalid CLI_DATA from " + username);
            AuthAudit.authFail("cccam", username, remoteAddress, "invalid-cli-data");
            close();
            return;
          }

          clientNodeId = CcServerCodec.extractPeerNodeId(frame.payload);

          // Envoyer CLI_DATA ACK (frame vide)
          sendFrame(CcConstants.MSG_CLI_DATA, new byte[0]);

          // Envoyer SRV_DATA (72 octets)
          byte[] srvData = CcServerCodec.buildSrvData(serverNodeId, "2.3.2", "4000");
          sendFrame(CcConstants.MSG_SRV_DATA, srvData);

          // Annonces de cartes initiales (P8)
          if (discoveryService != null) {
            String profile = profileName != null ? profileName : "default";
            CardDiscoveryService.UserAclContext acl = new CardDiscoveryService.UserAclContext(
                authResult != null ? authResult.profiles() : Set.of(),
                authResult != null ? authResult.allowedConnectors() : Set.of()
            );
            List<byte[]> initialCards = discoveryService.getInitialAnnouncements(
                profile, cardMode, listenerCap, maxCards, serverNodeId, acl);
            for (byte[] cardPayload : initialCards) {
              int cardId = CcCardCodec.parseNewCard(cardPayload).cardId;
              announcedCardIds.add((long) cardId);
              sendFrame(CcConstants.MSG_NEW_CARD, cardPayload);
            }

            this.announcementListener = new CardDiscoveryService.CardAnnouncementListener() {
              @Override
              public void onCapabilityAvailable(CardDiscoveryService.CapabilityRuntimeState cap) {
                if (alive && socket != null) {
                  byte[] payload = buildCardAnnouncement(cap);
                  if (payload != null) {
                    announcedCardIds.add(cap.persistentCardId);
                    sendFrame(CcConstants.MSG_NEW_CARD, payload);
                  } else {
                    if (announcedCardIds.remove(cap.persistentCardId)) {
                      byte[] rem = CcCardCodec.buildCardRemoved((int) cap.persistentCardId);
                      sendFrame(CcConstants.MSG_CARD_REMOVED, rem);
                    }
                  }
                }
              }

              @Override
              public void onCapabilityWithdrawn(long cardId) {
                if (alive && socket != null) {
                  if (announcedCardIds.remove(cardId)) {
                    byte[] rem = CcCardCodec.buildCardRemoved((int) cardId);
                    sendFrame(CcConstants.MSG_CARD_REMOVED, rem);
                  }
                }
              }

              @Override
              public CardDiscoveryService.UserAclContext getAclContext() {
                return new CardDiscoveryService.UserAclContext(
                    authResult != null ? authResult.profiles() : Set.of(),
                    authResult != null ? authResult.allowedConnectors() : Set.of()
                );
              }

              @Override
              public String getCardMode() {
                return cardMode;
              }

              @Override
              public int getListenerCap() {
                return listenerCap;
              }

              @Override
              public byte[] getServerNodeId() {
                return serverNodeId;
              }
            };
            discoveryService.addListener(announcementListener);
          }

          if (preauthTimerId != -1L) {
            vertx.cancelTimer(preauthTimerId);
            preauthTimerId = -1L;
          }

          releasePreauthLease();
          authenticated = true;
          AuthAudit.authOk("cccam", username, remoteAddress);
          armAuthEpochCheck();
          armValidUntilTimer();
          applyCacheExPolicy();

          state = STATE_NORMAL;
          progress = true;
        }
        case STATE_NORMAL -> {
          DecodedFrame frame = tryReadFrame();
          if (frame == null) return;

          handleNormalMessage(frame.cmd, frame.payload);
          progress = true;
        }
      }
    }
  }

  private record DecodedFrame(int cmd, byte[] payload) {}

  private DecodedFrame tryReadFrame() {
    if (headerPhase) {
      if (accumulator.length() < 4) return null;
      byte[] hdr = accumulator.getBytes(0, 4);
      decBlock.crypt(hdr, 4, CcConstants.DECRYPT);
      accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
      int[] header = CcFrameCodec.decodeHeader(hdr);
      currentCmd = header[1];
      currentPayloadLen = header[2];
      if (currentPayloadLen > MAX_DECLARED_PAYLOAD || currentPayloadLen < 0) {
        logger.warning("CCcam declared payload " + currentPayloadLen + " > " + MAX_DECLARED_PAYLOAD + " — closing");
        close();
        return null;
      }
      if (currentPayloadLen == 0) {
        return new DecodedFrame(currentCmd, new byte[0]);
      }
      headerPhase = false;
    }

    if (!headerPhase) {
      if (accumulator.length() < currentPayloadLen) return null;
      byte[] payload = accumulator.getBytes(0, currentPayloadLen);
      decBlock.crypt(payload, currentPayloadLen, CcConstants.DECRYPT);
      accumulator = Buffer.buffer(accumulator.getBytes(currentPayloadLen, accumulator.length()));
      headerPhase = true;
      return new DecodedFrame(currentCmd, payload);
    }
    return null;
  }

  private byte[] buildCardAnnouncement(CardDiscoveryService.CapabilityRuntimeState cap) {
    if (!alive || socket == null || cap == null) return null;
    if (cap.state == CardCapability.State.BLOCKED) return null;

    boolean isWildcard = "wildcard".equalsIgnoreCase(cardMode);
    if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) return null;

    Set<String> profs = authResult != null ? authResult.profiles() : Set.of();
    if (profs == null || profs.isEmpty() || !profs.contains(cap.profile)) {
      return null; // Profils vide ou non autorisé -> AUCUNE annonce
    }

    Set<String> allowed = authResult != null ? authResult.allowedConnectors() : Set.of();
    int minHop = 999;
    int maxReshare = -1;

    for (CardDiscoveryService.SourceRuntimeState src : cap.sources.values()) {
      if (discoveryService != null && !discoveryService.isSourceOnline(src.connectorName(), src.generation())) {
        continue;
      }
      if (allowed != null && !allowed.isEmpty() && !allowed.contains(src.connectorName())) {
        continue;
      }
      if (src.effectiveReshare() >= 0) {
        minHop = Math.min(minHop, src.hop());
        maxReshare = Math.max(maxReshare, src.effectiveReshare());
      }
    }

    if (minHop >= 999 || maxReshare < 0) {
      return null;
    }

    int outReshare = Math.min(maxReshare, listenerCap);
    List<CcCardCodec.CcProvider> provs = new java.util.ArrayList<>();
    if (cap.provider > 0) {
      provs.add(new CcCardCodec.CcProvider(cap.provider, new byte[4]));
    }
    return CcCardCodec.buildNewCard(
        (int) cap.persistentCardId, 0, cap.caid, minHop, outReshare,
        new byte[8], provs, List.of(serverNodeId));
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
          logger.warning("CCcam auth generation check failed (fail-closed) for " + username);
          AuthAudit.authFail("cccam", username, remoteAddress, "generation-fail");
          close();
          return;
        }
        long currentEpoch = ar.result();
        if (authResult != null && currentEpoch > authResult.epoch()) {
          authResolver.authenticate("cccam", username).onComplete(arAuth -> {
            if (!alive || !authenticated) return;
            if (arAuth.failed() || arAuth.result() == null) {
              logger.warning("CCcam auth revoked on epoch bump for " + username);
              AuthAudit.authFail("cccam", username, remoteAddress, "expired");
              close();
              return;
            }
            this.authResult = arAuth.result();
            reconcileAnnouncements();
          });
        }
      });
    });
  }

  private void reconcileAnnouncements() {
    if (!alive || socket == null || discoveryService == null) return;
    String profile = profileName != null ? profileName : "default";
    CardDiscoveryService.UserAclContext acl = new CardDiscoveryService.UserAclContext(
        authResult != null ? authResult.profiles() : Set.of(),
        authResult != null ? authResult.allowedConnectors() : Set.of()
    );
    List<byte[]> currentEligible = discoveryService.getInitialAnnouncements(
        profile, cardMode, listenerCap, maxCards, serverNodeId, acl);
    Set<Long> newSet = new java.util.HashSet<>();
    for (byte[] p : currentEligible) {
      long cid = (long) CcCardCodec.parseNewCard(p).cardId;
      newSet.add(cid);
      if (announcedCardIds.add(cid)) {
        sendFrame(CcConstants.MSG_NEW_CARD, p);
      }
    }
    announcedCardIds.removeIf(cid -> {
      if (!newSet.contains(cid)) {
        byte[] rem = CcCardCodec.buildCardRemoved((int) (long) cid);
        sendFrame(CcConstants.MSG_CARD_REMOVED, rem);
        return true;
      }
      return false;
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
        logger.warning("CCcam account already expired for " + username);
        AuthAudit.authFail("cccam", username, remoteAddress, "expired");
        close();
      } else {
        validUntilTimerId = vertx.setTimer(delay, id -> {
          if (alive) {
            logger.warning("CCcam account expired for " + username);
            AuthAudit.authFail("cccam", username, remoteAddress, "expired");
            close();
          }
        });
      }
    }
  }

  private void handleNormalMessage(int cmd, byte[] payload) {
    if (!alive || socket == null) return;

    if (authResult != null && authResult.userValidUntil() != null
        && authResult.userValidUntil().isBefore(Instant.now())) {
      logger.warning("CCcam account expired for " + username);
      AuthAudit.authFail("cccam", username, remoteAddress, "expired");
      close();
      return;
    }

    if (cmd == CcConstants.MSG_CW_ECM) {
      processEcmRequest(payload);
    } else if (cmd == CcConstants.MSG_CACHE_PUSH) {
      handleCacheExPush(payload);
    } else if (cmd == CcConstants.MSG_CACHE_FILTER) {
      handleCacheExFilter(payload);
    } else if (cmd == CcConstants.MSG_KEEPALIVE) {
      logger.fine("CCcam keepalive from " + username);
    }
  }

  // ============================================================
  // Cache-Exchange entrant (contrat v2 §6.2)
  // ============================================================

  /** Applique la politique cacheex du compte après auth (AuthResult.cacheex). */
  private void applyCacheExPolicy() {
    com.bowman.cspng.users.CacheExPolicy policy =
        authResult != null && authResult.cacheex() != null
            ? authResult.cacheex() : com.bowman.cspng.users.CacheExPolicy.OFF;
    this.cacheexPolicy = policy;
    if (!policy.enabled()) return;
    if (statsRegistry != null) {
      statsRegistry.registerInbound(cacheExPeerId(), "cccam", policy.mode(),
          profileName != null ? profileName : "", policy.maxHop(), MAX_SEND_QUEUE);
      statsRegistry.updateState(cacheExPeerId(), true, "connected", false);
    }
    if (policy.mode() == 3) {
      // Mode 3 : NOUS recevons du client — annoncer NOTRE filtre (gate d'acceptation).
      // Spec strict fail-closed (IAE => fermeture, pas de fail-open).
      try {
        byte[] filterPayload = CcCacheExCodec.encodeFilterFromSpec(policy.filter());
        sendFrame(CcConstants.MSG_CACHE_FILTER, filterPayload);
      } catch (IllegalArgumentException e) {
        logger.warning("CCcam cacheex filter invalid for " + username + ": " + e.getMessage());
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

  /** PUSH 0x81 reçu du client — accepté uniquement en mode 3 (nous recevons). */
  private void handleCacheExPush(byte[] payload) {
    if (cacheexPolicy.mode() != 3) {
      logger.fine("CCcam CacheEx PUSH from " + username + " ignored — account not in receive mode");
      countDrop("invalid");
      return;
    }

    // R3 anti-flood RX : même budget que allowedEcmRate (req/min, config existante)
    // mais fenêtre DÉDIÉE "<user>/cacheex" — un flood de push ne doit ni affamer
    // les ECM ni être masqué par eux. Compteur 'flood' dédié (fine-log, pas de spam).
    int pushRate = (authResult != null) ? authResult.allowedEcmRate() : -1;
    if (pushRate > 0 && registry != null
        && !registry.checkUserRateLimit(username + "/cacheex", pushRate)) {
      countDrop("flood");
      logger.fine("CCcam CacheEx PUSH dropped — RX flood from user " + username);
      return;
    }
    CcCacheExCodec.PushRecord push;
    try {
      push = CcCacheExCodec.parsePush(payload);
    } catch (Exception e) {
      countDrop("invalid");
      return;
    }
    if (!CcCacheExCodec.isPushValid(push)) {
      countDrop("invalid");
      return;
    }
    // Anti-boucle : notre serverNodeId dans le chemin
    if (CcCacheExCodec.containsNode(push.nodeIds(), serverNodeId)) {
      countDrop("loop");
      return;
    }
    if (push.nodeIds().size() > cacheexPolicy.maxHop()) {
      countDrop("hop");
      return;
    }
    // Gate : NOTRE filtre configuré (mode 3)
    try {
      java.util.List<CcCacheExCodec.FilterSpec> specs =
          CcCacheExCodec.parseFilterSpec(cacheexPolicy.filter());
      if (!specs.isEmpty()) {
        boolean matches = false;
        for (CcCacheExCodec.FilterSpec spec : specs) {
          if (spec.matches(push.caid(), push.prid())) {
            matches = true;
            break;
          }
        }
        if (!matches) {
          countDrop("filtered");
          return;
        }
      }
    } catch (IllegalArgumentException e) {
      countDrop("invalid");
      return;
    }
    // R2: Validation parité (0, 0x80, 0x81) AVANT toute allocation ou suivi quorum
    // Compat OSCam : 0 = INCONNU accepté (bypass cycle compté IGNORED, servable) ;
    // autres valeurs => rejet AVANT tout traitement (ni cycle, ni store, ni injection).
    if (push.oddEven() != 0x80 && push.oddEven() != 0x81 && push.oddEven() != 0) {
      countDrop("invalid");
      logger.fine("CCcam session CacheEx PUSH dropped — invalid oddEven 0x"
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
        countDrop("invalid");
        logger.fine("CCcam session CacheEx PUSH dropped — cw-cycle " + cycleVerdict);
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
        countDrop(verdict.dropReason());
        return;
      }
    } else {
      if (!DcwValidator.checksumDcw(cw)) {
        countDrop("checksum");
        return;
      }
      if (DcwValidator.hasFiveZeroes(cw) || DcwValidator.hasZeroDcw(cw)) {
        countDrop("invalid");
        return;
      }
    }

    // Mémoriser le chemin amont + métadonnée de cycle pour re-push futur (C2)
    // UNIQUEMENT après acceptation de la gate (éviter la pollution sur rejet)
    noteUpstreamPath(push.dataHash(), push.nodeIds(), push.cycleTimeSec(), push.nextCwCycle());

    EcmKey key = new EcmKey(oe, push.srvid(), 0, push.caid(), push.dataHash());
    CwReply reply = new CwReply(key, oe, cw.clone(), username, remoteAddress,
        System.currentTimeMillis());
    String peerProfile = profileName != null ? profileName : "default";
    if (cacheVerticle != null) {
      EcmRequest synthetic = new EcmRequest(key, new byte[0],
          peerProfile, remoteAddress, username,
          System.currentTimeMillis(), null, 5000);
      cacheVerticle.handleReply(synthetic, reply);
      if (statsRegistry != null) statsRegistry.countReceived(cacheExPeerId());
      if (gate != null) gate.postInjected(peerId);
    } else if (cacheExBridge != null && cacheExBridge.feedReceived(
        peerProfile, listenerId, push.caid(), push.prid(), push.srvid(), oe, cw,
        push.dataHash(), push.cycleTimeSec(), push.nextCwCycle(),
        push.nodeIds(), remoteAddress, username)) {
      if (statsRegistry != null) statsRegistry.countReceived(cacheExPeerId());
      if (gate != null) gate.postInjected(peerId);
    } else {
      countDrop("invalid");
      logger.fine("CCcam session CacheEx PUSH dropped — no routable cache (worker bridge absent?)");
    }
  }

  /** FILTER 0x82 reçu du client — mode 2 : gate d'émission vers ce client. */
  private void handleCacheExFilter(byte[] payload) {
    if (cacheexPolicy.mode() != 2) {
      logger.fine("CCcam CacheEx FILTER from " + username + " ignored — not in emit mode");
      return;
    }
    try {
      CcCacheExCodec.FilterRecord filter = CcCacheExCodec.parseFilter(payload);
      java.util.List<byte[]> filterList = new java.util.ArrayList<>();
      if (!filter.isAcceptAll()) {
        int count = Math.min(filter.cevnum(), 30);
        for (int i = 0; i < count; i++) {
          if (filter.caids()[i] != 0) {
            byte[] caidBytes = new byte[4];
            caidBytes[2] = (byte) ((filter.caids()[i] >>> 8) & 0xFF);
            caidBytes[3] = (byte) (filter.caids()[i] & 0xFF);
            filterList.add(caidBytes);
          }
        }
      }
      clientCacheexFilter = filterList.isEmpty() ? null : filterList;
      if (statsRegistry != null) {
        statsRegistry.updateState(cacheExPeerId(), true, "connected", clientCacheexFilter != null);
      }
      logger.fine("CCcam CacheEx FILTER received from " + username
          + " (" + (filter.isAcceptAll() ? "accept-all" : filter.cevnum() + " entries") + ")");
    } catch (Exception e) {
      logger.fine("CCcam CacheEx FILTER parse error from " + username + ": " + e.getMessage());
    }
  }

  /**
   * CacheTransport — mode 2 : pousser les CW du cache vers le client,
   * gate = filtre reçu du client, borne de file 2000 messages (D3).
   */
  @Override
  public void onReply(CwReply reply) {
    if (cacheexPolicy.mode() != 2) return;
    if (!alive || !authenticated || socket == null) return;
    if (reply == null || reply.isEmpty()) return;
    if (reply.originAddress() != null && reply.originAddress().equals(remoteAddress)) return;

    String dedupKey = reply.key().dataHash() + ":" + remoteAddress;
    if (pushedHashesLru.containsKey(dedupKey)) return;

    // Gate : filtre du client (reçu via 0x82)
    List<byte[]> gate = clientCacheexFilter;
    if (gate != null && !gate.isEmpty()) {
      boolean caidMatch = false;
      for (byte[] filterCaid : gate) {
        int filterCaidInt = ((filterCaid[2] & 0xFF) << 8) | (filterCaid[3] & 0xFF);
        if (filterCaidInt == reply.key().caId()) {
          caidMatch = true;
          break;
        }
      }
      if (!caidMatch) {
        countDrop("filtered");
        return;
      }
    }

    // Borne de file d'émission (nombre de messages)
    for (;;) {
      int pending = pendingPushQueue.get();
      if (pending >= MAX_SEND_QUEUE) {
        countDrop("queue");
        return;
      }
      if (pendingPushQueue.compareAndSet(pending, pending + 1)) break;
    }

    // Chemin de nodes (forwarding C2) : notre serverNodeId FIRST + chemin amont
    // appris (sans doublon) — l'anti-boucle et le max-hop restent vérifiables
    // par les pairs aval.
    com.bowman.cspng.connectors.cacheex.UpstreamPathStore.PathEntry stored =
        upstreamPaths.get(reply.key().dataHash());
    java.util.List<byte[]> knownPath = stored != null ? stored.nodes() : java.util.List.of();
    java.util.List<byte[]> allNodes = new java.util.ArrayList<>(knownPath.size() + 1);
    allNodes.add(serverNodeId);
    for (byte[] n : knownPath) {
      if (!Arrays.equals(n, serverNodeId)) allNodes.add(n);
    }
    if (allNodes.size() > cacheexPolicy.maxHop()) {
      countDrop("hop");
      logger.fine("CCcam session CacheEx PUSH(OUT) skipped — hop count "
          + allNodes.size() + " > max " + cacheexPolicy.maxHop());
      return;
    }

    // Métadonnée de cycle (wire [18]) : chemin stocké d'abord (valeur wire exacte),
    // sinon lookup checker si le check est actif.
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

    byte[] pushPayload = CcCacheExCodec.encodePush(
        reply.key().caId(), 0, reply.key().serviceId(),
        CcConstants.E_FOUND, reply.key().commandTag(),
        new byte[16], reply.key().dataHash(), reply.cw(),
        allNodes, cycleTimeSec, nextCwCycle);
    pushedHashesLru.put(dedupKey, Boolean.TRUE);
    Context ctx = sessionContext;
    if (ctx != null) {
      ctx.runOnContext(v -> {
        try {
          if (alive && socket != null) {
            sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
          }
        } finally {
          pendingPushQueue.decrementAndGet();
        }
      });
    } else {
      pendingPushQueue.decrementAndGet();
    }
    if (statsRegistry != null) statsRegistry.countPushed(cacheExPeerId());
  }

  @Override
  public void onRequest(EcmRequest request) {
    // No-op : les requêtes ECM ne sont pas propagées aux clients
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
   * émettra {@code [serverNodeId, ...upstreamPath]} (sans doublon), ce qui
   * préserve l'anti-boucle et le max-hop de bout en bout.
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

  private void processEcmRequest(byte[] payload) {
    int l = 4 + payload.length;
    if (l < 17) {
      sendNok2();
      return;
    }
    byte[] erEcm = Arrays.copyOfRange(payload, 13, payload.length);
    int sctLen = 3 + (((erEcm[1] & 0x0F) << 8) | (erEcm[2] & 0xFF));
    if (sctLen > erEcm.length || sctLen < 4) {
      sendNok2();
      return;
    }

    int caid = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    int provider = ((payload[2] & 0xFF) << 24) | ((payload[3] & 0xFF) << 16)
                 | ((payload[4] & 0xFF) << 8)  | (payload[5] & 0xFF);
    int cardId = ((payload[6] & 0xFF) << 24) | ((payload[7] & 0xFF) << 16)
               | ((payload[8] & 0xFF) << 8)  | (payload[9] & 0xFF);
    int srvid = ((payload[10] & 0xFF) << 8) | (payload[11] & 0xFF);

    if (authResult.blockedServices() != null) {
      for (Set<Integer> blocked : authResult.blockedServices().values()) {
        if (blocked != null && blocked.contains(srvid)) {
          sendNok2();
          return;
        }
      }
    }

    // Contrôle in-flight max
    if (maxInflight > 0 && inFlightCount.get() >= maxInflight) {
      logger.warning("CCcam in-flight quota reached (" + inFlightCount.get() + "/" + maxInflight + ") for " + username);
      sendNok2();
      return;
    }

    // Contrôle rate limit global utilisateur (selon config allowedEcmRate : > 0 = limite req/min, 0 ou -1 = illimité)
    int ecmRate = (authResult != null) ? authResult.allowedEcmRate() : -1;
    if (ecmRate > 0 && !registry.checkUserRateLimit(username, ecmRate)) {
      logger.warning("CCcam user " + username + " rate limit exceeded (" + ecmRate + " req/min)");
      sendNok2();
      return;
    }

    byte[] amputatedBody = Arrays.copyOfRange(erEcm, 3, sctLen);
    int dataHash = computeHash(amputatedBody);
    EcmKey key = new EcmKey(erEcm[0] & 0xFF, srvid, 0, caid, dataHash);

    EcmRequest ecmRequest = new EcmRequest(
        key, amputatedBody, profileName != null ? profileName : "default",
        remoteAddress, username, System.currentTimeMillis(), null, 5000);

    long seq = nextSeq++;
    PendingReply pending = new PendingReply(seq, cardId);
    pendingQueue.add(pending);
    inFlightCount.incrementAndGet();

    router.routeEcm(ecmRequest, authResult, provider)
        .onComplete(ar -> {
          inFlightCount.decrementAndGet();
          if (!alive || socket == null) {
            pending.completed = true;
            return;
          }
          authResolver.authenticate("cccam", username).onComplete(arAuth -> {
            if (!alive || socket == null) {
              pending.completed = true;
              return;
            }
            if (arAuth.failed() || arAuth.result() == null) {
              logger.warning("CCcam user revoked mid-flight for " + username);
              AuthAudit.authFail("cccam", username, remoteAddress, "expired");
              pending.cwReply = null;
              pending.completed = true;
              drainPendingQueue();
              close();
              return;
            }
            this.authResult = arAuth.result();
            pending.cwReply = (ar.succeeded() && ar.result() != null && !ar.result().isEmpty()) ? ar.result() : null;
            pending.completed = true;
            drainPendingQueue();
          });
        });
  }

  private void drainPendingQueue() {
    if (!alive || socket == null) return;
    while (!pendingQueue.isEmpty() && pendingQueue.peek().completed) {
      PendingReply top = pendingQueue.poll();
      if (!alive || socket == null) return;

      if (top.cwReply != null && !top.cwReply.isEmpty()) {
        // Mid-route pending check : compte expiré ou révoqué pendant le transit ?
        if (authResult != null && authResult.userValidUntil() != null && authResult.userValidUntil().isBefore(Instant.now())) {
          logger.warning("CCcam account expired before CW reply for " + username);
          AuthAudit.authFail("cccam", username, remoteAddress, "expired");
          sendNok2();
          close();
          return;
        }
        byte[] cwFrame = CcServerCodec.encodeCwReply(top.cwReply.cw(), clientNodeId, top.cardId, encBlock);
        socket.write(Buffer.buffer(cwFrame));
      } else {
        sendNok2();
      }
    }
  }

  private void sendNok2() {
    if (socket == null) return;
    byte[] nokFrame = CcServerCodec.encodeNok(CcConstants.MSG_CW_NOK2, encBlock);
    socket.write(Buffer.buffer(nokFrame));
  }

  private void sendFrame(int cmd, byte[] payload) {
    if (socket == null) return;
    byte[] frame = CcFrameCodec.encode(cmd, payload);
    encBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
    socket.write(Buffer.buffer(frame));
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
    pendingQueue.clear();
    inFlightCount.set(0);
    accumulator = Buffer.buffer();
    headerPhase = true;
    currentCmd = 0;
    currentPayloadLen = 0;
    if (discoveryService != null && announcementListener != null) {
      discoveryService.removeListener(announcementListener);
    }
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
