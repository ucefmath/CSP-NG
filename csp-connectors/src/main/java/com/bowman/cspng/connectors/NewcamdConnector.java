package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.newcamd.Md5Crypt;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
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
import io.vertx.core.parsetools.RecordParser;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Connecteur Newcamd — côté CLIENT (se connecte à un cardserver upstream).
 *
 * <h3>Pending ECM (lock-free)</h3>
 * <p>FIFO {@link ConcurrentLinkedQueue} + {@link AtomicInteger} CAS.
 * Aucune synchronisation bloquante. Timeout/erreur écriture = close + fail-all.
 * Races reply/timeout/close gérées par remove atomique et tryComplete/tryFail.
 */
public class NewcamdConnector extends AbstractConnector {

  static final int RECONNECT_DELAY = 5_000;
  private static final int ECM_REPLY_TIMEOUT_MS = 5_000;
  private static final int MAX_PENDING_REQUESTS = 1_000;

  private final byte[] configKey14;
  private final String username;
  private final String password;
  private byte[] desKey16;
  /** Timeout TCP configuré par ConnectorConfig (ms). */
  private final int connectTimeoutMs;

  private NetClient client;
  private NetSocket socket;

  private Buffer handshakeBuf = Buffer.buffer();
  private boolean handshakeDone;

  private Promise<LoginState> loginPromise;
  private volatile LoginState loginState = LoginState.PENDING;

  protected boolean cardDiscovery = false;
  protected Integer reshareGrant = null;
  protected String newcamdDialect = "legacy";
  protected String newcamdPasswordEncoding = "plaintext";

  public void setCardDiscovery(boolean discovery) { this.cardDiscovery = discovery; }
  public boolean isCardDiscovery() { return cardDiscovery; }
  public void setReshareGrant(Integer grant) { this.reshareGrant = grant != null ? Math.max(0, Math.min(10, grant)) : null; }
  public Integer getReshareGrant() { return reshareGrant; }
  public void setNewcamdDialect(String dialect) { this.newcamdDialect = dialect != null ? dialect : "legacy"; }
  public String getNewcamdDialect() { return newcamdDialect; }
  public void setNewcamdPasswordEncoding(String encoding) { this.newcamdPasswordEncoding = encoding != null ? encoding : "plaintext"; }
  public String getNewcamdPasswordEncoding() { return newcamdPasswordEncoding; }

  private String effectivePassword() {
    if ("md5crypt".equalsIgnoreCase(newcamdPasswordEncoding)) {
      return Md5Crypt.md5Crypt(password, Md5Crypt.DEFAULT_SALT);
    }
    return password != null ? password : "";
  }

  private final ConcurrentLinkedQueue<PendingEntry> pendingQueue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger pendingCount = new AtomicInteger(0);

  record PendingEntry(Promise<CwReply> promise, EcmKey key, Long timerId) {}

  /** Constructeur legacy (connexion directe). */
  public NewcamdConnector(Vertx vertx, CacheVerticle cacheVerticle,
                            String name, String host, int port,
                            byte[] configKey14, String username, String password, String profile) {
    this(vertx, cacheVerticle, name, host, port, configKey14, username, password, profile, 5000, null);
  }

  /** Constructeur avec connectTimeout configurable et proxy optionnel. */
  public NewcamdConnector(Vertx vertx, CacheVerticle cacheVerticle,
                            String name, String host, int port,
                            byte[] configKey14, String username, String password, String profile,
                            int connectTimeoutMs, NetworkProxyOptions proxyOptions) {
    super(vertx, cacheVerticle, name, host, port, profile, proxyOptions);
    this.configKey14 = Arrays.copyOf(configKey14, configKey14.length);
    this.username = username;
    this.password = password;
    this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 5000;
    this.loginPromise = Promise.promise();
  }

  int pendingCount() { return pendingCount.get(); }

  @Override public Future<LoginState> loginFuture() { return loginPromise.future(); }

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

  @Override public Future<Void> start() {
    stopped = false;
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
      // Kill switch étanche : un résultat obsolète (succès/échec TARDIF après
      // stop, ou d'un cycle de vie antérieur) est ignoré — socket fermée si
      // obtenue, AUCUNE réassignation socket/connected/handlers, AUCUNE relance.
      if (isStale(gen)) {
        if (ar.succeeded()) { try { ar.result().close(); } catch (Exception ignore) {} }
        if (!promise.future().isComplete()) promise.fail("Connector stopped");
        if (!loginPromise.future().isComplete()) completeLogin(LoginState.FAILED);
        return;
      }
      if (ar.succeeded()) {
        socket = ar.result();
        socket.exceptionHandler(t ->
            logger.log(Level.WARNING, "Connection error: " + t.getMessage()));
        socket.closeHandler(v -> {
          if (isStale(gen)) return; // close handler d'une génération obsolète — ignoré
          connected = false;
          handshakeDone = false;
          if (cardDiscoveryService != null) {
            cardDiscoveryService.onConnectorDisconnected(name, gen);
          }
          logger.info("Disconnected from " + host + ":" + port);
          failLoginOnClose();
          failAllPending("Connection closed");
          scheduleReconnect();
        });
        handshakeBuf = Buffer.buffer();
        handshakeDone = false;
        connected = true;
        socket.handler(this::handleHandshake);
        logger.info("Connected to " + host + ":" + port + ", waiting for server random14...");
        promise.complete();
      } else {
        logger.warning("Failed to connect to " + host + ":" + port + ": " + ar.cause().getMessage());
        scheduleReconnect();
        promise.fail(ar.cause());
        failLoginOnClose();
      }
    });
  }

  private void handleHandshake(Buffer buf) {
    if (handshakeDone) return;
    handshakeBuf.appendBuffer(buf);
    if (handshakeBuf.length() >= 14) {
      byte[] random14 = handshakeBuf.getBytes(0, 14);
      if ("oscam_ncd525".equalsIgnoreCase(newcamdDialect)) {
        desKey16 = NewcamdCrypto.loginKeyNcd525(random14, configKey14, 14);
      } else {
        byte[] xored = NewcamdCrypto.xorKey(configKey14, random14);
        desKey16 = NewcamdCrypto.desKeySpread(xored);
      }
      logger.fine("Client handshake: got random14=" + bytesToHex(random14)
          + " desKey16=" + bytesToHex(desKey16));
      sendLogin();
      handshakeDone = true;
      setupFramedReader(handshakeBuf);
    }
  }

  private void sendLogin() {
    String effPass = effectivePassword();
    String loginStr = username + "\0" + effPass + "\0";
    byte[] loginPayload = loginStr.getBytes(StandardCharsets.UTF_8);
    byte[] loginFrame = buildNewcamdFrame(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN,
        loginPayload, 0, 0, 0, 0, 0);
    byte[] encrypted = NewcamdCrypto.desEncrypt(loginFrame, loginFrame.length, desKey16,
        NewcamdConstants.CWS_NETMSGSIZE);
    if (encrypted == null) { logger.warning("Failed to encrypt login message"); return; }
    Buffer out = Buffer.buffer(2 + encrypted.length);
    out.appendByte((byte) (encrypted.length >> 8));
    out.appendByte((byte) (encrypted.length & 0xFF));
    out.appendBytes(encrypted);
    socket.write(out);
    logger.info("Sent login to upstream " + host + ":" + port);
  }

  private void sendCardDataReq() {
    byte[] frame = buildNewcamdFrame(NewcamdConstants.MSG_CARD_DATA_REQ, new byte[0], 0, 0, 0, 0, 0);
    byte[] encrypted = NewcamdCrypto.desEncrypt(frame, frame.length, desKey16, NewcamdConstants.CWS_NETMSGSIZE);
    if (encrypted == null) { logger.warning("Failed to encrypt CARD_DATA_REQ"); return; }
    Buffer out = Buffer.buffer(2 + encrypted.length);
    out.appendByte((byte) (encrypted.length >> 8));
    out.appendByte((byte) (encrypted.length & 0xFF));
    out.appendBytes(encrypted);
    socket.write(out);
    logger.fine("Sent MSG_CARD_DATA_REQ to " + name);
  }

  private void setupFramedReader(Buffer remaining) {
    RecordParser parser = RecordParser.newFixed(2, socket);
    socket.handler(parser);
    parser.setOutput(new Handler<Buffer>() {
      boolean readingLength = true; int messageLength;
      @Override public void handle(Buffer buffer) {
        if (readingLength) {
          messageLength = ((buffer.getByte(0) & 0xFF) << 8) | (buffer.getByte(1) & 0xFF);
          if (messageLength > 0 && messageLength <= NewcamdConstants.CWS_NETMSGSIZE + 16) {
            parser.fixedSizeMode(messageLength);
            readingLength = false;
          } else { logger.warning("Bad message length: " + messageLength); }
        } else {
          handleIncomingMessage(buffer.getBytes());
          parser.fixedSizeMode(2);
          readingLength = true;
        }
      }
    });
    if (remaining.length() > 14) {
      parser.handle(remaining.slice(14, remaining.length() - 14));
    }
  }

  private void handleIncomingMessage(byte[] data) {
    if (desKey16 == null) { logger.warning("Received message before handshake complete"); return; }
    byte[] decrypted = NewcamdCrypto.desDecrypt(data, data.length, desKey16);
    if (decrypted == null) { logger.warning("Decryption failed for incoming message"); return; }
    logger.finer("Received decrypted: " + bytesToHex(decrypted));
    if (decrypted.length < 11) { logger.warning("Message too short: " + decrypted.length); return; }
    int tag = decrypted[10] & 0xFF;

    if (tag == NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_ACK) {
      logger.info("Newcamd login ACK received");
      // CrUCIAL: switch to session key BEFORE notifying login completion.
      // Any waiter on loginFuture() may immediately call sendEcm() — which
      // must use the session key, not the login key.
      // Matches legacy NewcamdCwsConnector.java:156 (setDesKey16 before ready).
      String effPass = effectivePassword();
      if ("oscam_ncd525".equalsIgnoreCase(newcamdDialect)) {
        byte[] pwdBytes = effPass.getBytes(StandardCharsets.UTF_8);
        desKey16 = NewcamdCrypto.loginKeyNcd525(configKey14, pwdBytes, pwdBytes.length);
      } else {
        byte[] newKey14 = NewcamdCrypto.xorUserPass(configKey14, effPass);
        desKey16 = NewcamdCrypto.desKeySpread(newKey14);
      }
      if (cardDiscoveryService != null) {
        cardDiscoveryService.onConnectorConnected(name, currentGeneration());
      }
      if (cardDiscovery) {
        sendCardDataReq();
      }
      completeLogin(LoginState.OK);
    } else if (tag == NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_NAK) {
      logger.warning("Newcamd login NAK received");
      completeLogin(LoginState.FAILED);
    } else if (tag == NewcamdConstants.MSG_CARD_DATA) {
      int customDataLen = ((decrypted[11] & 0xFF) << 8) | (decrypted[12] & 0xFF);
      if (customDataLen >= 12 && decrypted.length >= 13 + customDataLen) {
        byte[] cardData = new byte[customDataLen];
        System.arraycopy(decrypted, 13, cardData, 0, customDataLen);
        int caid = ((cardData[1] & 0xFF) << 8) | (cardData[2] & 0xFF);
        int provCount = cardData[11] & 0xFF;
        if (cardDiscoveryService != null) {
          if (provCount == 0) {
            cardDiscoveryService.onCardDiscovered(name, profile, caid, 0, 0L, 1, 0,
                com.bowman.cspng.connectors.catalog.CardSource.Trust.DECLARED_UNVERIFIED,
                reshareCap, reshareGrant, currentGeneration());
          } else {
            for (int i = 0; i < provCount; i++) {
              int pOff = 12 + i * 11;
              if (pOff + 3 <= cardData.length) {
                int prov = ((cardData[pOff] & 0xFF) << 16) | ((cardData[pOff + 1] & 0xFF) << 8) | (cardData[pOff + 2] & 0xFF);
                cardDiscoveryService.onCardDiscovered(name, profile, caid, prov, 0L, 1, 0,
                    com.bowman.cspng.connectors.catalog.CardSource.Trust.DECLARED_UNVERIFIED,
                    reshareCap, reshareGrant, currentGeneration());
              }
            }
          }
        }
        logger.fine("Newcamd MSG_CARD_DATA received: caid=" + Integer.toHexString(caid) + " nprov=" + provCount);
      }
    } else if (tag == NewcamdConstants.ECM_COMMAND_TAG || tag == (NewcamdConstants.ECM_COMMAND_TAG + 1)) {
      int dataLen = ((decrypted[11] & 0xFF) << 8) | (decrypted[12] & 0xFF);
      byte[] cw;
      if (dataLen > 0 && decrypted.length >= 13 + dataLen) {
        cw = new byte[dataLen];
        System.arraycopy(decrypted, 13, cw, 0, dataLen);
      } else { cw = new byte[0]; }

      PendingEntry entry = pendingQueue.poll();
      if (entry != null) {
        pendingCount.decrementAndGet();
        vertx.cancelTimer(entry.timerId());
        EcmKey originalKey = entry.key();
        CwReply reply = new CwReply(originalKey, originalKey.commandTag(), cw, name,
            socket.remoteAddress().host(), System.currentTimeMillis());
        logger.fine("Completing ECM reply for " + originalKey + " cwLen=" + cw.length);
        entry.promise().tryComplete(reply);
      } else {
        logger.fine("Received ECM reply but no pending requests");
      }
    } else if (tag == NewcamdConstants.MSG_KEEPALIVE) {
      logger.fine("Keep-alive reply received");
    }
  }

  private void scheduleReconnect() {
    vertx.setTimer(RECONNECT_DELAY, id -> {
      if (!connected && !stopped) {
        logger.info("Reconnecting to " + host + ":" + port + "...");
        // Fermer l'ANCIEN client avant remplacement (pas de fuite de NetClient)
        if (client != null) { try { client.close(); } catch (Exception ignore) {} }
        client = createNetClient(buildNetClientOptions(connectTimeoutMs));
        doConnect(Promise.promise());
      }
    });
  }

  @Override public void stop() {
    stopped = true;
    int gen = currentGeneration();
    nextGeneration(); // invalide tous les callbacks de connexion en vol
    if (cardDiscoveryService != null) {
      cardDiscoveryService.onConnectorDisconnected(name, gen);
    }
    // failAllPending AVANT close() : évite la race où closeHandler() voit
    // la queue vide et croit qu'il n'y a rien à faire alors que stop() a
    // déjà fermé le socket — cause de NewcamdConnectorReplyTest.pendingFailOnStop.
    failAllPending("Connector stopped");
    connected = false;
    if (socket != null) socket.close();
    if (client != null) client.close();
  }

  /** Vide la queue de façon lock-free exclusivement. Chaque entrée est terminalisée exactement une fois. */
  private void failAllPending(String reason) {
    PendingEntry entry;
    while ((entry = pendingQueue.poll()) != null) {
      pendingCount.decrementAndGet();
      vertx.cancelTimer(entry.timerId());
      entry.promise().tryFail(reason);
    }
  }

  // ── sendEcm : CAS capacity + tryFail/tryComplete ──

  @Override public Future<CwReply> sendEcm(EcmRequest request) {
    Promise<CwReply> promise = Promise.promise();
    if (stopped) {
      promise.fail("Connector stopped");
      return promise.future();
    }
    if (!connected || socket == null || desKey16 == null) {
      promise.fail("Not connected");
      return promise.future();
    }
    // Atomic CAS capacity reservation
    int current;
    do {
      current = pendingCount.get();
      if (current >= MAX_PENDING_REQUESTS) {
        promise.fail("Too many pending ECM requests (max=" + MAX_PENDING_REQUESTS + ")");
        return promise.future();
      }
    } while (!pendingCount.compareAndSet(current, current + 1));

    // Build frame — release reservation on early failure
    byte[] frame, encrypted;
    try {
      frame = buildNewcamdFrame(request.key().commandTag(), request.body(),
          request.key().serviceId(), request.key().caId(), request.key().networkId(),
          0, request.key().dataHash());
    } catch (Exception e) {
      pendingCount.decrementAndGet();
      promise.fail(e);
      return promise.future();
    }
    encrypted = NewcamdCrypto.desEncrypt(frame, frame.length, desKey16, NewcamdConstants.CWS_NETMSGSIZE);
    if (encrypted == null) {
      pendingCount.decrementAndGet();
      promise.fail("Too large");
      return promise.future();
    }

    // Gather output buffer
    Buffer out = Buffer.buffer(2 + encrypted.length);
    out.appendByte((byte) (encrypted.length >> 8));
    out.appendByte((byte) (encrypted.length & 0xFF));
    out.appendBytes(encrypted);

    // Create entry and add to queue BEFORE write (so write callback sees it)
    EcmKey key = request.key();
    Long timerId = vertx.setTimer(ECM_REPLY_TIMEOUT_MS, this::onTimeout);
    PendingEntry entry = new PendingEntry(promise, key, timerId);
    pendingQueue.add(entry);

    socket.write(out, ar -> {
      if (!ar.succeeded()) {
        // Race-safe: only act if we win the remove race
        if (pendingQueue.remove(entry)) {
          pendingCount.decrementAndGet();
          vertx.cancelTimer(timerId);
          entry.promise().tryFail(ar.cause());
          if (socket != null) socket.close();
        }
      }
    });
    return promise.future();
  }

  // ── onTimeout: ferme la connexion SEULEMENT si l'entrée a été retirée ──

  void onTimeout(Long tid) {
    PendingEntry timedOut = null;
    for (PendingEntry e : pendingQueue) {
      if (e.timerId().equals(tid)) {
        if (pendingQueue.remove(e)) {
          timedOut = e;
          pendingCount.decrementAndGet();
        }
        break;
      }
    }
    if (timedOut != null) {
      timedOut.promise().tryFail("ECM reply timeout");
      if (socket != null) {
        logger.warning("ECM reply timeout — closing connection");
        socket.close();
      }
    }
    // else: entry already consumed by reply/close → no-op
  }

  private byte[] buildNewcamdFrame(int commandTag, byte[] customData,
                                    int sid, int caid, int onid, int seq, int hash) {
    byte[] buf = new byte[13 + customData.length];
    buf[0] = (byte) (sid >> 8);
    buf[1] = (byte) (sid & 0xFF);
    buf[2] = (byte) (onid >> 8);
    buf[3] = (byte) (onid & 0xFF);
    buf[4] = (byte) (caid >> 8);
    buf[5] = (byte) (caid & 0xFF);
    buf[6] = (byte) (hash >> 24);
    buf[7] = (byte) (hash >> 16);
    buf[8] = (byte) (hash >> 8);
    buf[9] = (byte) (hash & 0xFF);
    buf[10] = (byte) commandTag;
    buf[11] = (byte) ((customData.length >> 8) & 0xFF);
    buf[12] = (byte) (customData.length & 0xFF);
    System.arraycopy(customData, 0, buf, 13, customData.length);
    return buf;
  }

  private static String bytesToHex(byte[] bytes) {
    if (bytes == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }
}
