package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcCacheExCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Connecteur CCcam — côté CLIENT (se connecte à un serveur CCcam upstream).
 *
 * <h3>Périmètre MVP (itération 1)</h3>
 * <ul>
 *   <li>Handshake complet client CCcam</li>
 *   <li>Gestion des cartes (MSG_NEW_CARD / MSG_CARD_REMOVED)</li>
 *   <li>Envoi ECM avec sélection de carte (hop le plus bas)</li>
 *   <li>Réception CW avec étape de synchronisation obligatoire</li>
 *   <li>Fake-checks : CMD_05 en mode PLAIN, CMD_0B AES-128-ECB</li>
 *   <li>Keepalive périodique (60s)</li>
 *   <li>Reconnexion basique</li>
 *   <li>Cache-Exchange MVP : modes 2 (receive) et 3 (send), FILTER + PUSH, feature exchange</li>
 * </ul>
 *
 * <h3>Reports explicites</h3>
 * <ul>
 *   <li>Rôle serveur CCcam — lot séparé ultérieur</li>
 *   <li>Mode étendu / pipelining ECM — 1 ECM en vol à la fois</li>
 *   <li>CMD_0C/0D/0E (chiffrement CW couche 2.2.x) — ignoré</li>
 *   <li>Système de rating élaboré — simple filtre caid/provider + hop le plus bas</li>
 *   <li>Vérification anti-boucle node_id — les remote_nodes des cartes sont ignorés</li>
 *   <li>Cache-Exchange AIO features (0x83/0x84 bitfield non-zero, 0x85 triggers) — lot ultérieur</li>
 * </ul>
 */
public class CcCamConnector extends AbstractConnector implements com.bowman.cspng.connectors.cacheex.CacheExEmitter {

  private static final int RECONNECT_DELAY = 5_000;
  private static final long KEEPALIVE_INTERVAL = 60_000;

  private final String username;
  private final String password;
  private final String version;
  private final String build;
  private final byte[] nodeId;
  /** Timeout TCP+SOCKS5 configuré (ms). */
  private final int connectTimeoutMs;

  private NetClient client;
  private NetSocket socket;
  private long keepaliveTimerId = -1;

  // Blocs de chiffrement directionnels
  private final CcCrypt encryptBlock = new CcCrypt();
  private final CcCrypt decryptBlock = new CcCrypt();

  // Handshake
  private boolean handshakeDone;
  private Buffer handshakeBuf = Buffer.buffer();

  // Login state (additif, ne change pas start())
  private Promise<LoginState> loginPromise = Promise.promise();
  private volatile LoginState loginState = LoginState.PENDING;

  // Flux handler
  private final StreamHandler streamHandler = new StreamHandler();

  // Cartes (par connexion)
  private final Map<Integer, CcCardCodec.CcCard> cards = new ConcurrentHashMap<>();

  // Données serveur (pour CMD_0B)
  private byte[] peerNodeId;
  private byte[] peerVersion;
  private byte[] cmd0bAesKey;

  // ECM en vol
  private final Object ecmLock = new Object();
  private boolean ecmBusy;
  private Promise<CwReply> pendingEcm;
  private EcmKey pendingKey;
  private int lastTargetCardId; // card_id ciblé par l'ECM en vol

  // Compteur ECM pour CMD_05 (déconnexion après ~50 ECM si mode inconnu)
  private int ecmCounter;
  private int maxEcms; // 0 = pas de limite

  // Cache-Exchange
  private final int cacheexMode;    // 0=off, 2=receive (nous recevons), 3=send (nous émettons)
  private final int cacheexMaxHop;  // hop maximum (défaut 10)
  private final String cacheexFilterCfg; // spec "caid:provid" annoncé au pair en mode 2 (gate de réception)
  private final int cacheexMaxSendQueue;  // borne de file d'émission push (nb de messages, défaut 2000 — D3)
  private final com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry statsRegistry; // nullable (stats par pair)
  /** Pont vers le vrai cache sans CacheVerticle (worker C1) — nullable. */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExBridge cacheExBridge;
  private final java.util.concurrent.atomic.AtomicInteger pendingPushQueue = new AtomicInteger();
  /** Gate anti-flood RX par pair (R3) — distincte de la file TX bornée. */
  private final com.bowman.cspng.connectors.cacheex.RxFloodGate rxFloodGate =
      new com.bowman.cspng.connectors.cacheex.RxFloodGate();
  /** CW-cycle check (V15) — nullable ; gate enabled/caids appliquée avant check. */
  private volatile com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;
  private volatile com.bowman.cspng.config.CwCycleConfig cwCycleConfig;
  /**
   * Porte d'ingress qualité/quarantaine/quorum (lots 1/2/4/5) — nullable.
   * Absente (tests directs) : garde historique {@code DcwValidator} inchangée.
   */
  private volatile com.bowman.cspng.connectors.cacheex.CacheExIngressGate ingressGate;

  /** Injection de la porte d'ingress (nullable — aucun effet tant que null). */
  public void setIngressGate(com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate) {
    this.ingressGate = gate;
  }
  private java.util.List<com.bowman.cspng.codec.cccam.CcCacheExCodec.FilterSpec> ownFilterSpecs = java.util.List.of();
  private List<byte[]> cacheexFilter; // FILTER reçu du pair (mode 3 send gate)
  // Chemins amont appris par hash (forwarding C2) + dédup hash:peer — LRU bornées
  private final com.bowman.cspng.connectors.cacheex.UpstreamPathStore upstreamPaths =
      new com.bowman.cspng.connectors.cacheex.UpstreamPathStore();
  private final Map<String, Boolean> pushedHashesLru = Collections.synchronizedMap(
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > 1000;
        }
      });

  public CcCamConnector(Vertx vertx, CacheVerticle cacheVerticle,
                          String name, String host, int port,
                          String username, String password, String profile) {
    this(vertx, cacheVerticle, name, host, port, username, password, profile,
        CcConstants.DEFAULT_VERSION, CcConstants.DEFAULT_BUILD,
        CcConstants.CACHEEX_MODE_OFF, CcConstants.CACHEEX_DEFAULT_MAXHOP);
  }

  public CcCamConnector(Vertx vertx, CacheVerticle cacheVerticle,
                          String name, String host, int port,
                          String username, String password, String profile,
                          String version, String build) {
    this(vertx, cacheVerticle, name, host, port, username, password, profile,
        version, build, CcConstants.CACHEEX_MODE_OFF, CcConstants.CACHEEX_DEFAULT_MAXHOP);
  }

  public CcCamConnector(Vertx vertx, CacheVerticle cacheVerticle,
                          String name, String host, int port,
                          String username, String password, String profile,
                          String version, String build,
                          int cacheexMode, int cacheexMaxHop) {
    this(vertx, cacheVerticle, name, host, port, username, password, profile,
        version, build, cacheexMode, cacheexMaxHop, 5000, null);
  }

  /** Constructeur complet avec connectTimeout et proxy SOCKS5 optionnel (pré-V15, défauts cacheex). */
  public CcCamConnector(Vertx vertx, CacheVerticle cacheVerticle,
                          String name, String host, int port,
                          String username, String password, String profile,
                          String version, String build,
                          int cacheexMode, int cacheexMaxHop,
                          int connectTimeoutMs, NetworkProxyOptions proxyOptions) {
    this(vertx, cacheVerticle, name, host, port, username, password, profile,
        version, build, cacheexMode, cacheexMaxHop, connectTimeoutMs, proxyOptions,
        null, DEFAULT_MAX_SEND_QUEUE, null);
  }

  /**
   * Constructeur complet V15 : filtre annoncé (mode 2), borne de file push (mode 3,
   * nombre de messages — D3), registry de stats par pair (nullable : sans stats).
   */
  public CcCamConnector(Vertx vertx, CacheVerticle cacheVerticle,
                          String name, String host, int port,
                          String username, String password, String profile,
                          String version, String build,
                          int cacheexMode, int cacheexMaxHop,
                          int connectTimeoutMs, NetworkProxyOptions proxyOptions,
                          String cacheexFilterCfg, int cacheexMaxSendQueue,
                          com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry statsRegistry) {
    super(vertx, cacheVerticle, name, host, port, profile, proxyOptions);
    this.username = username;
    this.password = password;
    this.version = version;
    this.build = build;
    this.nodeId = generateNodeId();
    this.cacheexMode = cacheexMode;
    this.cacheexMaxHop = cacheexMaxHop;
    this.cacheexFilterCfg = cacheexFilterCfg;
    this.cacheexMaxSendQueue = cacheexMaxSendQueue > 0 ? cacheexMaxSendQueue : DEFAULT_MAX_SEND_QUEUE;
    this.statsRegistry = statsRegistry;
    this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 5000;
    // Gate de réception mode 2 : spec strict, fail-closed à la construction (IAE)
    this.ownFilterSpecs = com.bowman.cspng.codec.cccam.CcCacheExCodec.parseFilterSpec(cacheexFilterCfg);
  }

  /** Borne de file push par défaut (contrat v2 §6.1 — nombre de messages, pas un débit). */
  public static final int DEFAULT_MAX_SEND_QUEUE = 2000;

  @Override
  public Future<LoginState> loginFuture() {
    return loginPromise.future();
  }

  private void completeLogin(LoginState state) {
    if (loginPromise.future().isComplete()) return;
    loginState = state;
    loginPromise.complete(state);
  }

  private void failLoginOnClose() {
    if (!loginPromise.future().isComplete()) {
      loginState = LoginState.FAILED;
      loginPromise.complete(LoginState.FAILED);
    }
  }

  private static byte[] generateNodeId() {
    byte[] id = new byte[8];
    new SecureRandom().nextBytes(id);
    return id;
  }

  // ============================================================
  // Cycle de vie
  // ============================================================

  @Override
  public Future<Void> start() {
    stopped = false;
    // Enregistrement de la paire cacheex (un seul endroit — CLM/worker/tests
    // n'ont plus à le faire ; upsert idempotent).
    if (statsRegistry != null && cacheexMode != CcConstants.CACHEEX_MODE_OFF) {
      statsRegistry.registerOutbound(name, "cccam", cacheexMode, profile,
          cacheexMaxHop, cacheexMaxSendQueue);
    }
    nextGeneration(); // nouveau cycle de vie — invalide les callbacks du cycle précédent
    Promise<Void> promise = Promise.promise();
    client = createNetClient(buildNetClientOptions(connectTimeoutMs));
    doConnect(promise);
    return promise.future();
  }

  private void doConnect(Promise<Void> promise) {
    if (loginPromise.future().isComplete()) {
      loginPromise = Promise.promise();
      loginState = LoginState.PENDING;
    }
    final int gen = currentGeneration();
    client.connect(port, host, ar -> {
      // Kill switch étanche : résultat obsolète (succès/échec TARDIF après stop,
      // ou d'un cycle antérieur) ignoré — socket fermée, AUCUNE réassignation.
      if (isStale(gen)) {
        if (ar.succeeded()) { try { ar.result().close(); } catch (Exception ignore) {} }
        if (!promise.future().isComplete()) promise.fail("Connector stopped");
        if (!loginPromise.future().isComplete()) completeLogin(LoginState.FAILED);
        return;
      }
      if (ar.succeeded()) {
        socket = ar.result();
        socket.exceptionHandler(t ->
            logger.log(Level.WARNING, "CCcam connection error: " + t.getMessage()));
        socket.closeHandler(v -> {
          if (isStale(gen)) return; // close handler d'une génération obsolète — ignoré
          connected = false;
          handshakeDone = false;
          stopKeepalive();
          failPendingEcm("Disconnected");
          logger.info("CCcam disconnected from " + host + ":" + port);
          if (statsRegistry != null && cacheexMode != CcConstants.CACHEEX_MODE_OFF) {
            statsRegistry.updateState(name, false, "disconnected", false);
          }
          failLoginOnClose();
          scheduleReconnect();
        });

        resetState();
        connected = true;

        // Enregistrer ce connecteur comme transport de cache (worker : pas de cache local)
        if (cacheVerticle != null) {
          cacheVerticle.addTransport(this);
        }

        if (statsRegistry != null && cacheexMode != CcConstants.CACHEEX_MODE_OFF) {
          statsRegistry.updateState(name, false, "connecting", false);
        }

        // Étape 1: attendre les 16 octets de seed du serveur (EN CLAIR)
        socket.handler(this::handleHandshake);
        logger.info("CCcam connected to " + host + ":" + port + ", waiting for seed...");
        promise.complete();
      } else {
        logger.warning("CCcam failed to connect to " + host + ":" + port + ": "
            + ar.cause().getMessage());
        scheduleReconnect();
        promise.fail(ar.cause());
        failLoginOnClose();
      }
    });
  }

  private void resetState() {
    cards.clear();
    ecmBusy = false;
    pendingEcm = null;
    pendingKey = null;
    lastTargetCardId = 0;
    ecmCounter = 0;
    maxEcms = 0;
    peerNodeId = null;
    peerVersion = null;
    cmd0bAesKey = null;
    handshakeBuf = Buffer.buffer();
    handshakeDone = false;
    streamHandler.reset();
    cacheexFilter = null;
    upstreamPaths.clear();
    pushedHashesLru.clear();
  }

  @Override
  public void stop() {
    stopped = true;
    nextGeneration(); // invalide tous les callbacks de connexion en vol
    stopKeepalive();
    // failPendingEcm AVANT close() — cohérent avec le fix Newcamd/Radegast.
    failPendingEcm("Connector stopped");
    connected = false;
    if (statsRegistry != null && cacheexMode != CcConstants.CACHEEX_MODE_OFF) {
      statsRegistry.updateState(name, false, "disabled", false);
      statsRegistry.unregister(name);
    }
    if (socket != null) socket.close();
    if (client != null) client.close();
  }

  /**
   * Pour les tests : vérifie si des cartes ont été reçues du serveur.
   */
  public boolean hasCards() {
    return !cards.isEmpty();
  }

  /**
   * Pour les tests : retourne le nombre de cartes connues.
   */
  public int cardCount() {
    return cards.size();
  }

  private void scheduleReconnect() {
    vertx.setTimer(RECONNECT_DELAY, id -> {
      if (!connected && !stopped) {
        logger.info("CCcam reconnecting to " + host + ":" + port + "...");
        // Fermer l'ANCIEN client avant remplacement (pas de fuite de NetClient)
        if (client != null) { try { client.close(); } catch (Exception ignore) {} }
        client = createNetClient(buildNetClientOptions(connectTimeoutMs));
        doConnect(Promise.promise());
      }
    });
  }

  // ============================================================
  // Handshake (§3 de la spec)
  // ============================================================

  private void handleHandshake(Buffer buf) {
    if (handshakeDone) return;
    handshakeBuf.appendBuffer(buf);

    if (handshakeBuf.length() >= CcConstants.SEED_SIZE) {
      processHandshake();
    }
  }

  private void processHandshake() {
    byte[] seed = handshakeBuf.getBytes(0, CcConstants.SEED_SIZE);
    logger.fine("CCcam received seed: " + bytesToHex(seed));

    // 1. cc_xor(seed)
    CcCrypt.xor(seed);
    logger.fine("CCcam after xor: " + bytesToHex(seed));

    // 2. SHA-1(seed)
    byte[] hash = sha1(seed);
    logger.fine("CCcam SHA-1 hash: " + bytesToHex(hash));

    // 3. Dérivation crypto miroir serveur
    // block[DECRYPT] = init_crypt(hash, 20) puis crypt(seed, 16, DECRYPT)
    decryptBlock.initCrypt(hash, CcConstants.SHA1_SIZE);
    decryptBlock.crypt(seed, CcConstants.SEED_SIZE, CcConstants.DECRYPT);

    // block[ENCRYPT] = init_crypt(seed, 16) puis crypt(hash, 20, DECRYPT)
    encryptBlock.initCrypt(seed, CcConstants.SEED_SIZE);
    encryptBlock.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

    // 4. Envoyer le hash transformé (20 octets, chiffré par encryptBlock, sans header)
    byte[] hashCopy = hash.clone();
    encryptBlock.crypt(hashCopy, hashCopy.length, CcConstants.ENCRYPT);
    sendRaw(hashCopy);

    // 5. Envoyer username (20 octets, padding '\0', chiffré sans header)
    byte[] userBuf = new byte[CcConstants.USERNAME_SIZE];
    byte[] userBytes = username.getBytes();
    System.arraycopy(userBytes, 0, userBuf, 0, Math.min(userBytes.length, CcConstants.USERNAME_SIZE));
    encryptBlock.crypt(userBuf, userBuf.length, CcConstants.ENCRYPT);
    sendRaw(userBuf);

    // 6. Avancer encryptBlock avec le mot de passe (mode ENCRYPT)
    byte[] pwdBytes = password.getBytes();
    encryptBlock.crypt(pwdBytes, pwdBytes.length, CcConstants.ENCRYPT);

    // 7. Envoyer "CCcam\0" (6 octets EXACTS — le \0 terminal est INCLUS
    //    dans les 6 octets transmis, cf cc_cli_connect ligne ~4738:
    //    cc_cmd_send(cl, buf, 6, MSG_NO_HEADER) avec buf = "CCcam\0")
    byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
    encryptBlock.crypt(magic, magic.length, CcConstants.ENCRYPT);
    sendRaw(magic);

    logger.fine("CCcam sent login credentials");

    // 8. Lire 20 octets de réponse, déchiffrer, vérifier "CCcam" en tête
    handshakeDone = true;
    handshakeBuf = Buffer.buffer();

    // Basculer le handler pour lire la réponse de login (20 octets déchiffrés)
    socket.handler(new LoginResponseHandler());
  }

  /**
   * Handler temporaire pour lire les 20 octets de réponse de login.
   * Complète le Promise LoginState interne (OK si "CCcam" en tête, FAILED sinon).
   */
  private class LoginResponseHandler implements Handler<Buffer> {
    private final Buffer buf = Buffer.buffer();

    @Override
    public void handle(Buffer data) {
      buf.appendBuffer(data);
      logger.fine("CCcam LoginResponse: received " + data.length()
          + " bytes, total=" + buf.length());
      if (buf.length() >= 20) {
        byte[] loginResp = buf.getBytes(0, 20);
        decryptBlock.crypt(loginResp, 20, CcConstants.DECRYPT);
        logger.fine("CCcam login response decrypted: " + bytesToHex(loginResp));

        if (loginResp.length >= 5
            && loginResp[0] == 'C' && loginResp[1] == 'C'
            && loginResp[2] == 'c' && loginResp[3] == 'a'
            && loginResp[4] == 'm') {
          logger.info("CCcam login OK");
          completeLogin(LoginState.OK);
          // Conserver les octets restants (>20) pour le parsing normal
          Buffer remaining = null;
          if (buf.length() > 20) {
            remaining = buf.slice(20, buf.length());
          }
          startNormalMode(remaining);
        } else {
          logger.warning("CCcam login FAILED — invalid server response: "
              + bytesToHex(loginResp));
          completeLogin(LoginState.FAILED);
          socket.close();
        }
      }
    }
  }

  private void startNormalMode(Buffer remaining) {
    if (cardDiscoveryService != null) {
      cardDiscoveryService.onConnectorConnected(name, currentGeneration());
    }

    // Envoyer MSG_CLI_DATA
    byte[] cliData = CcCardCodec.buildCliData(username, nodeId, version, build);
    sendFrame(CcConstants.MSG_CLI_DATA, cliData);

    // Cache-Exchange mode 2 (receive) : envoyer NOTRE FILTER (config v2 §6.1)
    // après login — spec vide/null = accept-all (sémantique OSCam cevnum=0).
    if (cacheexMode == CcConstants.CACHEEX_MODE_RECEIVE) {
      sendFilter();
    }

    if (statsRegistry != null && cacheexMode != CcConstants.CACHEEX_MODE_OFF) {
      statsRegistry.updateState(name, true, "connected",
          cacheexMode == CcConstants.CACHEEX_MODE_SEND && cacheexFilter != null);
    }

    // Configurer le handler de parsing normal (flux)
    socket.handler(streamHandler);

    // Nourrir les octets restants du login
    if (remaining != null && remaining.length() > 0) {
      streamHandler.handle(remaining);
    }

    // Démarrer le keepalive
    startKeepalive();
  }

  // ============================================================
  // Parsing de flux (déchiffrement progressif)
  // ============================================================

  /**
   * Handler de flux pour le parsing CCcam avec déchiffrement progressif.
   *
   * <p>Le flux CCcam est un chiffrement de flux : chaque octet du buffer
   * réseau est déchiffré séquentiellement dans l'ordre de réception.
   * On accumule le buffer, déchiffre les 4 premiers octets pour connaître
   * la longueur du payload, PUIS déchiffre le payload quand il est complet.
   *
   * <p>L'état crypto (block[DECRYPT]) est mutable et progresse entre
   * l'en-tête et le payload — c'est crucial pour le chiffrement de flux.
   */
  private class StreamHandler implements Handler<Buffer> {
    private Buffer accumulator = Buffer.buffer();
    private boolean headerPhase = true;
    private int payloadLen;
    private int currentCmd; // stocké entre l'en-tête et le payload

    void reset() {
      accumulator = Buffer.buffer();
      headerPhase = true;
      payloadLen = 0;
      currentCmd = 0;
    }

    @Override
    public void handle(Buffer data) {
      accumulator.appendBuffer(data);

      while (true) {
        if (headerPhase) {
          if (accumulator.length() < 4) return;

          // Déchiffrer les 4 octets d'en-tête
          byte[] header = accumulator.getBytes(0, 4);
          decryptBlock.crypt(header, 4, CcConstants.DECRYPT);
          int[] decoded = CcFrameCodec.decodeHeader(header);
          currentCmd = decoded[1];
          payloadLen = decoded[2];

          if (payloadLen > CcConstants.CC_MAXMSGSIZE) {
            // ⚠️ Chiffrement de flux continu : impossible de se resynchroniser
            // en "sautant" des octets — fermer la connexion immédiatement.
            logger.severe("CCcam payload too large: " + payloadLen
                + " — stream desynchronized, closing connection");
            socket.close();
            return;
          }

          if (payloadLen == 0) {
            // Pas de payload → traiter immédiatement
            processMessage(currentCmd, new byte[0]);
            discardHeader();
          } else {
            headerPhase = false;
          }
        } else {
          if (accumulator.length() < 4 + payloadLen) return;

          // Déchiffrer le payload (dans la CONTINUITÉ de l'état crypto)
          byte[] payload = accumulator.getBytes(4, 4 + payloadLen);
          decryptBlock.crypt(payload, payloadLen, CcConstants.DECRYPT);

          processMessage(currentCmd, payload);

          // Enlever en-tête (4) + payload du buffer
          accumulator = Buffer.buffer(accumulator.getBytes(4 + payloadLen,
              accumulator.length()));
          headerPhase = true;
        }
      }
    }

    private void discardHeader() {
      accumulator = Buffer.buffer(accumulator.getBytes(4, accumulator.length()));
      headerPhase = true;
    }
  }

  // ============================================================
  // Émission
  // ============================================================

  private void sendRaw(byte[] data) {
    if (socket != null) {
      socket.write(Buffer.buffer(data));
    }
  }

  private void sendFrame(int cmd, byte[] payload) {
    byte[] frame = CcFrameCodec.encode(cmd, payload);
    encryptBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
    sendRaw(frame);
  }

  // ============================================================
  // Traitement des messages
  // ============================================================

  private void processMessage(int cmd, byte[] payload) {
    logger.finer("CCcam received: " + CcFrameCodec.cmdName(cmd)
        + " (payload=" + payload.length + ")");

    switch (cmd) {
      case CcConstants.MSG_SRV_DATA:
        handleSrvData(payload);
        break;
      case CcConstants.MSG_NEW_CARD:
        handleNewCard(payload);
        break;
      case CcConstants.MSG_CARD_REMOVED:
        handleCardRemoved(payload);
        break;
      case CcConstants.MSG_CW_ECM:
        handleCwReply(payload);
        break;
      case CcConstants.MSG_CW_NOK1:
      case CcConstants.MSG_CW_NOK2:
        handleCwNok(cmd);
        break;
      case CcConstants.MSG_KEEPALIVE:
        logger.finer("CCcam received keepalive");
        break;
      case CcConstants.MSG_CMD_05:
        handleCmd05(payload);
        break;
      case CcConstants.MSG_CMD_0B:
        handleCmd0b(payload);
        break;
      case CcConstants.MSG_CMD_0C:
      case CcConstants.MSG_CMD_0D:
      case CcConstants.MSG_CMD_0E:
        logger.fine("CCcam ignoring " + CcFrameCodec.cmdName(cmd)
            + " (couche 2.2.x, non implémenté MVP)");
        break;
      case CcConstants.MSG_CACHE_PUSH:
        handleCachePush(payload);
        break;
      case CcConstants.MSG_CACHE_FILTER:
        handleCacheFilter(payload);
        break;
      case CcConstants.MSG_CACHE_FEATURE_EXCHANGE:
        handleFeatureExchange();
        break;
      case CcConstants.MSG_CACHE_FEATURE_EXCHANGE_REPLY:
      case CcConstants.MSG_CACHE_FEATURE_TRIGGER:
        logger.fine("CCcam ignoring " + CcFrameCodec.cmdName(cmd)
            + " (AIO features not implemented)");
        break;
      default:
        logger.fine("CCcam unknown command: 0x"
            + Integer.toHexString(cmd) + ", ignoring");
    }
  }

  // ============================================================
  // MSG_SRV_DATA
  // ============================================================

  private void handleSrvData(byte[] payload) {
    if (payload.length == CcConstants.MSG_SRV_DATA_SIZE) {
      CcCardCodec.SrvData srv = CcCardCodec.parseSrvData(payload);
      peerNodeId = srv.nodeId();
      peerVersion = srv.peerVersion();

      // Construire la clé AES pour CMD_0B : node_id(8) + version(8)
      cmd0bAesKey = new byte[16];
      System.arraycopy(peerNodeId, 0, cmd0bAesKey, 0, 8);
      int vLen = 0;
      while (vLen < peerVersion.length && peerVersion[vLen] != 0) vLen++;
      System.arraycopy(peerVersion, 0, cmd0bAesKey, 8, Math.min(vLen, 8));

      String verStr = new String(peerVersion, 0, Math.min(vLen, 6)).trim();
      logger.info("CCcam server: version=" + verStr
          + " nodeid=" + bytesToHex(peerNodeId));

      cards.clear();
    } else {
      logger.fine("CCcam SRV_DATA non-standard (len=" + payload.length
          + "), CMD_05 mode changed");
    }
  }

  // ============================================================
  // MSG_NEW_CARD / MSG_CARD_REMOVED
  // ============================================================

  private void handleNewCard(byte[] payload) {
    try {
      CcCardCodec.CcCard card = CcCardCodec.parseNewCard(payload);
      cards.put(card.cardId, card);
      if (cardDiscoveryService != null) {
        if (card.providers.isEmpty()) {
          cardDiscoveryService.onCardDiscovered(name, profile, card.caid, 0, card.cardId, card.hop, card.reshare,
              com.bowman.cspng.connectors.catalog.CardSource.Trust.DISCOVERED, reshareCap, null, currentGeneration());
        } else {
          for (CcCardCodec.CcProvider p : card.providers) {
            cardDiscoveryService.onCardDiscovered(name, profile, card.caid, p.provider(), card.cardId, card.hop, card.reshare,
                com.bowman.cspng.connectors.catalog.CardSource.Trust.DISCOVERED, reshareCap, null, currentGeneration());
          }
        }
      }
      logger.fine("CCcam card added: " + card);
    } catch (Exception e) {
      logger.warning("CCcam failed to parse NEW_CARD: " + e.getMessage());
    }
  }

  private void handleCardRemoved(byte[] payload) {
    try {
      int cardId = CcCardCodec.parseCardRemoved(payload);
      if (cards.remove(cardId) != null) {
        logger.fine("CCcam card removed: " + Integer.toHexString(cardId));
      }
    } catch (Exception e) {
      logger.warning("CCcam failed to parse CARD_REMOVED: " + e.getMessage());
    }
  }

  // ============================================================
  // Réponse CW (MSG_CW_ECM serveur → client)
  // ============================================================

  private void handleCwReply(byte[] payload) {
    if (payload.length < CcConstants.CW_SIZE) {
      logger.warning("CCcam CW reply too short: " + payload.length);
      failPendingEcm("CW reply too short");
      return;
    }

    int targetCardId;
    EcmKey key;
    Promise<CwReply> p;
    synchronized (ecmLock) {
      if (!ecmBusy || pendingEcm == null) {
        logger.warning("CCcam received CW but no ECM pending");
        return;
      }
      targetCardId = lastTargetCardId;
      key = pendingKey;
      p = pendingEcm;
      ecmBusy = false;
      pendingEcm = null;
      pendingKey = null;
    }

    // 1. cc_cw_crypt inverse (déchiffrement du scrambling)
    // On utilise notre PROPRE node_id (comportement client OSCam : cl->typ == 'r')
    byte[] cw = Arrays.copyOf(payload, CcConstants.CW_SIZE);
    CcCwCrypt.cwCrypt(cw, nodeId, targetCardId);

    // 2. ⚠️ ÉTAPE DE SYNCHRONISATION OBLIGATOIRE
    // Avancer block[DECRYPT] de 16 octets en mode ENCRYPT
    // Utiliser une COPIE pour ne pas modifier le CW retourné à l'appelant
    byte[] syncBuf = Arrays.copyOf(cw, CcConstants.CW_SIZE);
    decryptBlock.crypt(syncBuf, syncBuf.length, CcConstants.ENCRYPT);

    logger.fine("CCcam CW decrypted (card=" + Integer.toHexString(targetCardId)
        + "): cwLen=" + cw.length);

    // 3. Résoudre la promesse ECM
    CwReply reply = buildCwReply(cw, key);
    p.tryComplete(reply);
  }

  private void handleCwNok(int cmd) {
    String reason = (cmd == CcConstants.MSG_CW_NOK1)
        ? "card no longer available" : "decode failed";
    logger.fine("CCcam CW_NOK: " + reason);
    failPendingEcm(reason);
  }

  CwReply buildCwReply(byte[] cw) {
    return buildCwReply(cw, null);
  }

  CwReply buildCwReply(byte[] cw, EcmKey key) {
    EcmKey effectiveKey = (key != null) ? key : new EcmKey(0x80, 0, 0, 0, 0);
    int tag = (effectiveKey.commandTag() != 0) ? effectiveKey.commandTag() : 0x80;
    String origin = (socket != null && socket.remoteAddress() != null) ? socket.remoteAddress().host() : null;
    return new CwReply(
        effectiveKey,
        tag,
        cw,
        name,
        origin,
        System.currentTimeMillis());
  }

  private void failPendingEcm(String reason) {
    synchronized (ecmLock) {
      ecmBusy = false;
      pendingKey = null;
      if (pendingEcm != null) {
        Promise<CwReply> p = pendingEcm;
        pendingEcm = null;
        p.fail(reason);
      }
    }
  }

  // ============================================================
  // Envoi ECM
  // ============================================================

  @Override
  public Future<CwReply> sendEcm(EcmRequest request) {
    Promise<CwReply> promise = Promise.promise();

    if (stopped) {
      promise.fail("Connector stopped");
      return promise.future();
    }

    if (!connected || socket == null) {
      promise.fail("Not connected");
      return promise.future();
    }

    synchronized (ecmLock) {
      if (ecmBusy) {
        promise.fail(new ConnectorBusyException());
        return promise.future();
      }
      ecmBusy = true;
      pendingEcm = promise;
      pendingKey = request.key();
    }

    if (maxEcms > 0 && ecmCounter >= maxEcms) {
      logger.warning("CCcam ECM limit reached (" + maxEcms + "), reconnecting...");
      failPendingEcm("ECM limit reached");
      socket.close();
      return promise.future();
    }

    try {
      CcCardCodec.CcCard bestCard = selectCard(request.key().caId(), 0);

      if (bestCard == null) {
        logger.fine("CCcam no card available for caid="
            + Integer.toHexString(request.key().caId()));
        failPendingEcm("No card available");
        return promise.future();
      }

      synchronized (ecmLock) {
        lastTargetCardId = bestCard.cardId;
      }

      // CONTRAT P0 (wire OSCam) : le domaine transporte un ECM AMPUTÉ de ses 3 octets
      // de section DVB (invariant EcmKey : hash sur ecm[3..], cf. NewcamdSession
      // customData). Le wire CCcam attend la section ENTIÈRE (OSCam module-cccam.c
      // l.1873 memcpy(ecmbuf+13, cur_er->ecm, ecmlen) ; validation SCT_LEN
      // oscam-ecm.c l.3030). On reconstruit [tag][0x70|len_hi][len_lo]+body à
      // l'émission uniquement — le hash domaine n'est pas altéré.
      // Preuve RED/GREEN contre OSCam réel : docs/INBOUND-CAMS-PLAN.md §0.1
      // (amputé → NOK2 « decode failed » ; section reconstruite → CW 15/15).
      byte[] ecmSection = CcCardCodec.reconstructSection(
          request.key().commandTag(), request.body());

      byte[] ecmFrame = CcCardCodec.buildEcmRequest(
          bestCard.caid, 0, bestCard.cardId,
          request.key().serviceId(), ecmSection);

      // Envoyer sur l'event-loop Vert.x pour éviter la concurrence
      // entre le thread appelant sendEcm() (externe) et le timer keepalive
      vertx.runOnContext(v -> {
        sendFrame(CcConstants.MSG_CW_ECM, ecmFrame);
        ecmCounter++;
        logger.finer("CCcam ECM sent to card=" + Integer.toHexString(bestCard.cardId)
            + " caid=" + Integer.toHexString(bestCard.caid));
      });
    } catch (Exception e) {
      failPendingEcm("ECM send error: " + e.getMessage());
    }

    return promise.future();
  }

  public boolean hasCardForCaid(int caId) {
    if (cards.isEmpty()) return true;
    for (CcCardCodec.CcCard card : cards.values()) {
      if (card.caid == caId) return true;
    }
    return false;
  }

  public boolean hasCardForCaidAndProvider(int caId, int provider) {
    if (cards.isEmpty()) return true;
    for (CcCardCodec.CcCard card : cards.values()) {
      if (card.caid == caId && (provider == 0 || card.matches(caId, provider))) {
        return true;
      }
    }
    return false;
  }

  private CcCardCodec.CcCard selectCard(int caId, int provider) {
    CcCardCodec.CcCard best = null;
    int bestHop = Integer.MAX_VALUE;

    for (CcCardCodec.CcCard card : cards.values()) {
      if (card.caid == caId && (provider == 0 || card.matches(caId, provider))) {
        if (card.hop < bestHop) {
          best = card;
          bestHop = card.hop;
        }
      }
    }

    return best;
  }

  // ============================================================
  // Fake-checks : CMD_05 et CMD_0B
  // ============================================================

  private void handleCmd05(byte[] payload) {
    logger.fine("CCcam CMD_05 received (payload=" + payload.length + ")");

    if (payload.length == 0) {
      sendFrame(CcConstants.MSG_CMD_05, new byte[0]);
    } else if (payload.length == CcConstants.CMD_05_PAYLOAD_SIZE) {
      // Mode PLAIN (défaut) : écho pur
      sendFrame(CcConstants.MSG_CMD_05, payload);
    } else {
      // Mode inconnu : répondre vide, limiter à 50 ECM
      sendFrame(CcConstants.MSG_CMD_05, new byte[0]);
      if (maxEcms == 0) {
        maxEcms = 50;
        ecmCounter = 0;
        logger.info("CCcam CMD_05 unknown mode — limiting to 50 ECMs");
      }
    }
  }

  private void handleCmd0b(byte[] payload) {
    if (payload.length < CcConstants.CMD_0B_CHALLENGE_SIZE) {
      logger.warning("CCcam CMD_0B payload too short: " + payload.length);
      return;
    }

    byte[] challenge = Arrays.copyOf(payload, CcConstants.CMD_0B_CHALLENGE_SIZE);

    if (cmd0bAesKey == null) {
      logger.warning("CCcam CMD_0B no AES key yet, echoing challenge");
      sendFrame(CcConstants.MSG_CMD_0B, challenge);
      return;
    }

    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
      SecretKeySpec keySpec = new SecretKeySpec(cmd0bAesKey, "AES");
      cipher.init(Cipher.ENCRYPT_MODE, keySpec);
      byte[] response = cipher.doFinal(challenge);
      sendFrame(CcConstants.MSG_CMD_0B, response);
      logger.fine("CCcam CMD_0B AES-128-ECB response sent");
    } catch (Exception e) {
      logger.log(Level.WARNING, "CCcam CMD_0B AES failed: " + e.getMessage(), e);
      sendFrame(CcConstants.MSG_CMD_0B, challenge);
    }
  }

  // ============================================================
  // Cache-Exchange handlers
  // ============================================================

  /**
   * Traite un PUSH reçu (MSG_CACHE_PUSH, mode 2 receive).
   *
   * <p>Valide le PUSH (rc, size, anti-boucle), injecte la CW dans le cache local
   * via {@code cacheVerticle.handleReply}, et stocke les nodes par hash pour re-push futur.
   */
  private void handleCachePush(byte[] payload) {
    // FIX 5: n'injecter les PUSH que si le connecteur est en mode receive (2)
    if (cacheexMode != CcConstants.CACHEEX_MODE_RECEIVE) {
      logger.fine("CCcam CacheEx PUSH ignored — not in receive mode (current=" + cacheexMode + ")");
      return;
    }

    // R3 anti-flood RX par pair (borne TX maxSendQueue non concernée) : au-delà de
    // RxFloodGate.DEFAULT_MAX_PER_MINUTE push/min, drop compté 'flood' (fine-log,
    // jamais de spam de logs sous flood).
    if (!rxFloodGate.tryAcquire(
        com.bowman.cspng.connectors.cacheex.RxFloodGate.DEFAULT_MAX_PER_MINUTE)) {
      countDrop(CacheExStatsRegistry.REASON_FLOOD);
      logger.fine("CCcam CacheEx PUSH dropped — RX flood from " + name);
      return;
    }

    com.bowman.cspng.codec.cccam.CcCacheExCodec.PushRecord push;
    try {
      push = CcCacheExCodec.parsePush(payload);
    } catch (Exception e) {
      countDrop(CacheExStatsRegistry.REASON_INVALID);
      logger.log(Level.WARNING,
          "CCcam CacheEx PUSH parse error: " + e.getMessage(), e);
      return;
    }

    try {
      logger.fine("CCcam CacheEx PUSH: caid=0x" + Integer.toHexString(push.caid())
          + " srvid=0x" + Integer.toHexString(push.srvid())
          + " rc=" + push.rc() + " hash=0x" + Integer.toHexString(push.dataHash())
          + " nodes=" + push.nodeIds().size()
          + " oddEven=0x" + Integer.toHexString(push.oddEven()));

      // Validation
      if (!CcCacheExCodec.isPushValid(push)) {
        countDrop(CacheExStatsRegistry.REASON_INVALID);
        logger.fine("CCcam CacheEx PUSH not valid (rc=" + push.rc() + ")");
        return;
      }

      // Anti-boucle : vérifier que notre propre node_id n'est pas dans la liste
      if (CcCacheExCodec.containsNode(push.nodeIds(), nodeId)) {
        countDrop(CacheExStatsRegistry.REASON_LOOP);
        logger.fine("CCcam CacheEx PUSH skipped — own node in path");
        return;
      }

      // Max-hop : le nombre de nodes dans le chemin
      if (push.nodeIds().size() > cacheexMaxHop) {
        countDrop(CacheExStatsRegistry.REASON_HOP);
        logger.fine("CCcam CacheEx PUSH skipped — hop count " + push.nodeIds().size()
            + " > max " + cacheexMaxHop);
        return;
      }

      // Gate de réception : NOTRE filtre configuré (mode 2) — miroir check_cacheex_filter OSCam
      if (!ownFilterSpecs.isEmpty()) {
        boolean matches = false;
        for (com.bowman.cspng.codec.cccam.CcCacheExCodec.FilterSpec spec : ownFilterSpecs) {
          if (spec.matches(push.caid(), push.prid())) {
            matches = true;
            break;
          }
        }
        if (!matches) {
          countDrop(CacheExStatsRegistry.REASON_FILTERED);
          logger.fine("CCcam CacheEx PUSH dropped — caid/provid not in configured filter");
          return;
        }
      }

      // R2: Validation parité (0, 0x80, 0x81) AVANT toute allocation ou suivi quorum
      // Compat OSCam : 0 = parité INCONNUE acceptée (jamais convertie en 0x80) ;
      // autres valeurs => rejet fail-closed (corruption wire, pas de sens défini).
      // L'inconnue contourne le check cycle (bypass compté IGNORED) sans contaminer
      // les moitiés odd/even, mais reste servable (lookup par hash).
      if (push.oddEven() != 0x80 && push.oddEven() != 0x81 && push.oddEven() != 0) {
        countDrop(CacheExStatsRegistry.REASON_INVALID);
        logger.fine("CCcam CacheEx PUSH dropped — invalid oddEven 0x"
            + Integer.toHexString(push.oddEven()));
        return;
      }
      int oe = push.oddEven();

      // CW-cycle check (V15) : rejet des mauvais cycles AVANT injection au cache.
      // Métadonnée pair [18] : cycletime + flag next-cw-cycle (usePeerCycleMetadata).
      if (cwCycleChecker != null && cwCycleConfig != null && cwCycleConfig.enabled()
          && cwCycleConfig.appliesToCaid(push.caid())) {
        com.bowman.cspng.filters.CwCycleChecker.Verdict cycleVerdict = cwCycleChecker.check(
            profile, push.caid(), push.prid(), push.srvid(), oe, push.cw(),
            push.cycleTimeSec(), push.nextCwCycle(), System.currentTimeMillis());
        if (cwCycleChecker.shouldReject(cycleVerdict)) {
          countDrop(CacheExStatsRegistry.REASON_INVALID);
          logger.fine("CCcam CacheEx PUSH dropped — cw-cycle " + cycleVerdict);
          return;
        }
      }

      // Validation qualité contextuelle (lots 1/2/4/5) : quarantaine enforce,
      // policy DCW avec le contexte réel (profil/CAID/provider/SID, sourceId =
      // nom du connecteur = identité de transport vérifiée TCP), puis quorum
      // sur CW déjà validée avec origine réelle dédupliquée depuis push.nodeIds() (B1).
      byte[] pushCw = push.cw();
      com.bowman.cspng.connectors.cacheex.CacheExIngressGate gate = ingressGate;
      String originAddress = host + ":" + port;
      String realOrigin = com.bowman.cspng.connectors.cacheex.CacheExIngressGate.extractOrigin(
          push.nodeIds(), originAddress);
      if (gate != null) {
        com.bowman.cspng.connectors.cacheex.CacheExIngressGate.Verdict verdict = gate.preInject(
            profile, push.caid(), push.prid(), push.srvid(), oe, push.dataHash(),
            pushCw, name, realOrigin,
            com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED);
        if (!verdict.accept()) {
          countDrop(verdict.dropReason());
          logger.fine("CCcam CacheEx PUSH dropped — " + verdict.detail() + " from " + name);
          return;
        }
      } else {
        if (!DcwValidator.checksumDcw(pushCw)) {
          countDrop(CacheExStatsRegistry.REASON_CHECKSUM);
          logger.fine("CCcam CacheEx PUSH dropped — CW checksum invalid");
          return;
        }
        if (DcwValidator.hasFiveZeroes(pushCw)) {
          countDrop(CacheExStatsRegistry.REASON_INVALID);
          logger.fine("CCcam CacheEx PUSH dropped — CW has 5-7 zeroes");
          return;
        }
        if (DcwValidator.hasZeroDcw(pushCw)) {
          countDrop(CacheExStatsRegistry.REASON_INVALID);
          logger.fine("CCcam CacheEx PUSH dropped — CW has zero half");
          return;
        }
      }

      // Stocker le chemin reçu + métadonnée de cycle pour re-push futur
      // (forwarding multi-noeuds, C2) : QUEL QUE SOIT le mode — inoffensif en
      // mode 2 (onReply réservé au mode 3).
      noteUpstreamPath(push.dataHash(), push.nodeIds(),
          push.cycleTimeSec(), push.nextCwCycle());

      // Construire EcmKey et CwReply
      EcmKey key = new EcmKey(oe, push.srvid(), 0, push.caid(), push.dataHash());

      CwReply reply = new CwReply(key, oe,
          pushCw, name, originAddress, System.currentTimeMillis());

      // Injecter dans le vrai cache : CacheVerticle (central) ou pont (worker C1)
      if (cacheVerticle != null) {
        // Mode async, single-writer
        EcmRequest syntheticRequest = new EcmRequest(key, new byte[0],
            profile, originAddress, name,
            System.currentTimeMillis(), null, 5000);
        cacheVerticle.handleReply(syntheticRequest, reply);
        if (statsRegistry != null) statsRegistry.countReceived(name);
        if (gate != null) gate.postInjected(name);
      } else if (cacheExBridge != null && cacheExBridge.feedReceived(
          profile, -1, push.caid(), push.prid(), push.srvid(), oe, pushCw,
          push.dataHash(), push.cycleTimeSec(), push.nextCwCycle(),
          push.nodeIds(), originAddress, name)) {
        if (statsRegistry != null) statsRegistry.countReceived(name);
        if (gate != null) gate.postInjected(name);
      } else {
        // Aucun cache routable (worker sans pont / profil inconnu) — rejet explicite
        countDrop(CacheExStatsRegistry.REASON_INVALID);
        logger.fine("CCcam CacheEx PUSH dropped — no routable cache (worker bridge absent?)");
      }

    } catch (Exception e) {
      countDrop(CacheExStatsRegistry.REASON_INVALID);
      logger.log(Level.WARNING,
          "CCcam CacheEx PUSH handling error: " + e.getMessage(), e);
    }
  }

  /** Injection du contrôle CW-cycle (nullable — aucun effet tant que config absente/off). */
  /** Injection du pont cacheex worker (nullable — central = CacheVerticle). */
  public void setCacheExBridge(com.bowman.cspng.connectors.cacheex.CacheExBridge bridge) {
    this.cacheExBridge = bridge;
  }

  public void setCwCycle(com.bowman.cspng.filters.CwCycleChecker checker,
                          com.bowman.cspng.config.CwCycleConfig config) {
    this.cwCycleChecker = checker;
    this.cwCycleConfig = config;
  }

  /**
   * Mémorise le chemin de nodes amont d'un hash (forwarding C2) : le re-push
   * émettra {@code [ownNode, ...upstreamPath]} (sans doublon de notre node),
   * ce qui préserve l'anti-boucle et le max-hop de bout en bout.
   * LRU bornée (~1000) partagée avec les chemins appris des push reçus.
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
    if (statsRegistry != null) statsRegistry.countDropped(name, reason);
  }

  /**
   * Traite un FILTER reçu (MSG_CACHE_FILTER, mode 3 send).
   * Stocke le filtre pour gate push-out.
   */
  private void handleCacheFilter(byte[] payload) {
    try {
      CcCacheExCodec.FilterRecord filter = CcCacheExCodec.parseFilter(payload);
      logger.fine("CCcam CacheEx FILTER: cevnum=" + filter.cevnum()
          + " acceptAll=" + filter.isAcceptAll());

      // Stocker pour gate push-out (mode 3 send)
      if (cacheexMode == CcConstants.CACHEEX_MODE_SEND) {
        // Extraire les caid valides du filtre
        List<byte[]> filterList = new java.util.ArrayList<>();
        if (!filter.isAcceptAll()) {
          int count = Math.min(filter.cevnum(), CcConstants.CACHEEX_MAX_FILTERS);
          for (int i = 0; i < count; i++) {
            if (filter.caids()[i] != 0) {
              // Stocker le caid (4 octets BE) pour comparaison rapide
              byte[] caidBytes = new byte[4];
              caidBytes[2] = (byte) ((filter.caids()[i] >>> 8) & 0xFF);
              caidBytes[3] = (byte) (filter.caids()[i] & 0xFF);
              filterList.add(caidBytes);
            }
          }
        }
        cacheexFilter = filterList.isEmpty() ? null : filterList;
        if (statsRegistry != null) {
          statsRegistry.updateState(name, connected, connected ? "connected" : "disconnected",
              cacheexFilter != null);
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING,
          "CCcam CacheEx FILTER parse error: " + e.getMessage(), e);
    }
  }

  /**
   * Répond au feature exchange (0x83) par un reply avec bitfield 0 (mode non-AIO).
   */
  private void handleFeatureExchange() {
    byte[] replyPayload = CcCacheExCodec.encodeFeatureExchange(0);
    vertx.runOnContext(v -> {
      sendFrame(CcConstants.MSG_CACHE_FEATURE_EXCHANGE_REPLY, replyPayload);
    });
    logger.fine("CCcam CacheEx feature exchange replied with bitfield=0");
  }

  /**
   * Envoie NOTRE FILTER vers le pair (mode 2 receive après login).
   * Contrat v2 §6.1 : le filtre CONFIGURÉ ({@code cacheexFilterCfg}, "caid:provid")
   * est annoncé — spec vide/null = accept-all (sémantique OSCam cevnum=0).
   */
  private void sendFilter() {
    byte[] filterPayload = CcCacheExCodec.encodeFilterFromSpec(cacheexFilterCfg);
    sendFrame(CcConstants.MSG_CACHE_FILTER, filterPayload);
    logger.fine("CCcam CacheEx FILTER sent ("
        + (ownFilterSpecs.isEmpty() ? "accept-all" : ownFilterSpecs.size() + " entries") + ")");
  }

  // ============================================================
  // CacheTransport — onReply (mode 3 send : push CW sortante)
  // ============================================================

  /**
   * Implémentation de {@link CacheTransport#onReply(CwReply)}.
   *
   * <p>En mode 3 (send) : quand une CW est ajoutée au cache local (par un ECM reçu
   * d'un autre connecteur ou par un PUSH entrant), on la pousse vers le pair CCcam
   * si le filtre le permet et que le hop est OK.
   */
  @Override
  public void onReply(CwReply reply) {
    if (cacheexMode != CcConstants.CACHEEX_MODE_SEND) return;
    if (!connected || socket == null) return;
    if (reply == null || reply.isEmpty()) return;

    // Anti-boucle : ne pas re-pusher si la CW vient de ce même peer
    if (reply.originAddress() != null && reply.originAddress().equals(host + ":" + port)) {
      return;
    }

    // Dédup : éviter de re-pusher le même hash au même peer
    String dedupKey = reply.key().dataHash() + ":" + host + ":" + port;
    if (pushedHashesLru.containsKey(dedupKey)) {
      return;
    }

    // Filtre : si un FILTER spécifique a été reçu, vérifier le caid
    if (cacheexFilter != null && !cacheexFilter.isEmpty()) {
      boolean caidMatch = false;
      for (byte[] filterCaid : cacheexFilter) {
        int filterCaidInt = ((filterCaid[2] & 0xFF) << 8) | (filterCaid[3] & 0xFF);
        if (filterCaidInt == reply.key().caId()) {
          caidMatch = true;
          break;
        }
      }
      if (!caidMatch) {
        countDrop(CacheExStatsRegistry.REASON_FILTERED);
        return; // Filtre ne match pas
      }
    }

    // Récupérer le chemin amont connu pour ce hash (forwarding C2)
    com.bowman.cspng.connectors.cacheex.UpstreamPathStore.PathEntry stored =
        upstreamPaths.get(reply.key().dataHash());
    List<byte[]> knownNodes = stored != null ? stored.nodes() : Collections.emptyList();

    // Construire la liste des nodes : own node FIRST + known nodes
    List<byte[]> allNodes = new java.util.ArrayList<>();
    allNodes.add(nodeId);
    for (byte[] n : knownNodes) {
      if (!Arrays.equals(n, nodeId)) {
        allNodes.add(n);
      }
    }

    // Vérifier max-hop — FIX 3: aligné RX (count > maxhop rejeté par OSCam)
    if (allNodes.size() > cacheexMaxHop) {
      countDrop(CacheExStatsRegistry.REASON_HOP);
      logger.fine("CCcam CacheEx PUSH(OUT) skipped — hop count "
          + allNodes.size() + " > max " + cacheexMaxHop);
      return;
    }

    // Borne de file d'émission (D3 : NOMBRE de messages push en attente, pas un débit).
    // File pleine => drop compté 'queue' — jamais de croissance non bornée.
    for (;;) {
      int pending = pendingPushQueue.get();
      if (pending >= cacheexMaxSendQueue) {
        countDrop(CacheExStatsRegistry.REASON_QUEUE);
        logger.fine("CCcam CacheEx PUSH(OUT) dropped — send queue full ("
            + pending + " >= " + cacheexMaxSendQueue + ")");
        return;
      }
      if (pendingPushQueue.compareAndSet(pending, pending + 1)) break;
    }

    // Métadonnée de cycle (wire [18]) : chemin stocké d'abord (valeur wire exacte
    // de l'amont), sinon lookup checker si le check est actif.
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

    // Encode PUSH
    byte[] pushPayload = CcCacheExCodec.encodePush(
        reply.key().caId(), 0, reply.key().serviceId(),
        CcConstants.E_FOUND, reply.key().commandTag(),
        new byte[16], // ecmd5 zéro (MVP sans MD5)
        reply.key().dataHash(), reply.cw(), allNodes, cycleTimeSec, nextCwCycle);

    // Marquer comme pushed
    pushedHashesLru.put(dedupKey, Boolean.TRUE);

    // Envoyer sur l'event-loop Vert.x
    vertx.runOnContext(v -> {
      try {
        sendFrame(CcConstants.MSG_CACHE_PUSH, pushPayload);
      } finally {
        pendingPushQueue.decrementAndGet();
      }
    });
    if (statsRegistry != null) statsRegistry.countPushed(name);

    logger.fine("CCcam CacheEx PUSH(OUT): hash=0x"
        + Integer.toHexString(reply.key().dataHash())
        + " caid=0x" + Integer.toHexString(reply.key().caId())
        + " nodes=" + allNodes.size());
  }

  /**
   * Implémentation de {@link CacheTransport#onRequest(EcmRequest)} — no-op.
   */
  @Override
  public void onRequest(EcmRequest request) {
    // No-op : on ne propage pas les requêtes ECM vers le pair CCcam
  }

  // ============================================================
  // Keepalive
  // ============================================================

  private void startKeepalive() {
    if (keepaliveTimerId >= 0) return;
    keepaliveTimerId = vertx.setPeriodic(KEEPALIVE_INTERVAL, id -> {
      if (connected && handshakeDone) {
        sendFrame(CcConstants.MSG_KEEPALIVE, new byte[0]);
        logger.finest("CCcam keepalive sent");
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
  // Utilitaires
  // ============================================================

  private static byte[] sha1(byte[] data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      return md.digest(data);
    } catch (Exception e) {
      throw new RuntimeException("SHA-1 not available", e);
    }
  }

  static String bytesToHex(byte[] bytes) {
    if (bytes == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }
}
