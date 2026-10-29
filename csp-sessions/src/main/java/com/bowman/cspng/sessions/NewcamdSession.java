package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheResult;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;

import java.util.Arrays;
import java.util.logging.Level;

/**
 * Session Newcamd — côté SERVEUR (accepte des clients Newcamd comme mgcamd/OSCam).
 *
 * <h3>Handshake serveur (corrigé, correction de review #3)</h3>
 * <ol>
 *   <li>Le SERVEUR génère 14 octets aléatoires et les ENVOIE au client.</li>
 *   <li>Le client répond avec un message de login chiffré.</li>
 *   <li>Le serveur déchiffre, valide username/password.</li>
 *   <li>Après validation, le serveur calcule la nouvelle clé de session :
 *       {@code desKeySpread(xorUserPass(configKey14, cryptPw))}
 *       et envoie l'ACK de login AVEC la nouvelle clé.</li>
 * </ol>
 *
 * <p>Authentification SIMPLE pour L4 (username/password en clair dans le message).
 */
public class NewcamdSession extends AbstractSession {

  protected final byte[] configKey14;
  protected byte[] desKey16;
  protected NetSocket socket;
  protected CwsConnectorManager connectorManager;
  protected boolean noEncrypt;
  protected volatile boolean authPending = false;
  protected int maxMsgSize = NewcamdConstants.CWS_NETMSGSIZE;
  protected Context sessionContext;
  protected int profileNetworkId = -1;
  protected int profileCaId = 0;
  protected int[] profileProviders = new int[0];
  protected volatile boolean profileCacheOnly = false;
  protected volatile long profileMaxCwWaitMs = 5000L;

  public NewcamdSession(Vertx vertx, CacheVerticle cacheVerticle,
                         SessionRegistry registry, byte[] configKey14) {
    super(vertx, cacheVerticle, registry);
    this.configKey14 = Arrays.copyOf(configKey14, configKey14.length);
    this.noEncrypt = false;
  }

  public void setNoEncrypt(boolean noEncrypt) { this.noEncrypt = noEncrypt; }
  public void setConnectorManager(CwsConnectorManager cm) { this.connectorManager = cm; }
  public void setMaxMsgSize(int maxMsgSize) { this.maxMsgSize = maxMsgSize; }
  public int getMaxMsgSize() { return maxMsgSize; }
  public void setProfileNetworkId(int networkId) { this.profileNetworkId = networkId; }
  public int getProfileNetworkId() { return profileNetworkId; }
  public void setProfileCaId(int caId) { this.profileCaId = caId; }
  public int getProfileCaId() { return profileCaId; }
  public void setProfileProviders(int[] providers) {
    this.profileProviders = providers != null ? providers.clone() : new int[0];
  }
  public int[] getProfileProviders() { return profileProviders.clone(); }
  public void setProfileCacheOnly(boolean cacheOnly) { this.profileCacheOnly = cacheOnly; }
  public boolean isProfileCacheOnly() { return profileCacheOnly; }
  public void setProfileMaxCwWaitMs(long maxCwWaitMs) {
    this.profileMaxCwWaitMs = maxCwWaitMs > 0 ? maxCwWaitMs : 5000L;
  }
  public long getProfileMaxCwWaitMs() { return profileMaxCwWaitMs; }
  protected NetSocket getSocket() { return socket; }
  protected byte[] getDesKey16() { return desKey16; }
  protected Context getSessionContext() { return sessionContext; }
  protected static record ReplyHeader(int sequence, int serviceId, int caId) {}

  /**
   * Accepte une connexion entrante. Le SERVEUR génère et envoie random14 au client.
   * (Corrigé : correction review #3)
   */
  public void accept(NetSocket socket) {
    this.socket = socket;
    this.remoteAddress = socket.remoteAddress().host();
    this.alive = true;
    // Capture exact Vert.x context of the socket (for context-confinement)
    this.sessionContext = Vertx.currentContext();
    socket.exceptionHandler(t ->
        logger.log(Level.WARNING, "Session error: " + t.getMessage()));
    socket.closeHandler(v -> cleanup());

    // === Handshake serveur : générer et ENVOYER random14 ===
    byte[] random14 = NewcamdCrypto.getRandomBytes(14);
    byte[] xored = NewcamdCrypto.xorKey(configKey14, random14);
    desKey16 = NewcamdCrypto.desKeySpread(xored);

    // Send random14 in clear — never log random/des key

    // Send random14 in clear
    socket.write(Buffer.buffer(random14));

    // Setup framed reader for subsequent messages
    setupFramedReader();
  }

  private void setupFramedReader() {
    RecordParser parser = RecordParser.newFixed(2, socket);
    socket.handler(parser);

    parser.setOutput(new Handler<Buffer>() {
      boolean readingLength = true;
      int messageLength;

      @Override
      public void handle(Buffer buffer) {
        if (!alive) return; // guard: ne pas traiter après close
        if (readingLength) {
          messageLength = ((buffer.getByte(0) & 0xFF) << 8) | (buffer.getByte(1) & 0xFF);
          if (messageLength > 0 && messageLength <= maxMsgSize + 16) {
            parser.fixedSizeMode(messageLength);
            readingLength = false;
          } else {
            logger.warning("Bad message length: " + messageLength);
            if (!authenticated) {
              AuthAudit.authFail("newcamd", "?", remoteAddress, "bad-length");
            }
            close();
          }
        } else {
          byte[] data = buffer.getBytes();
          byte[] decrypted = noEncrypt ? data :
              NewcamdCrypto.desDecrypt(data, messageLength, desKey16);

          if (decrypted == null) {
            logger.warning("Decryption failed, closing session");
            if (!authenticated) {
              AuthAudit.authFail("newcamd", "?", remoteAddress, "decrypt-fail");
            }
            close();
            return;
          }
          handleMessage(decrypted);
          parser.fixedSizeMode(2);
          readingLength = true;
        }
      }
    });
  }

  protected void handleMessage(byte[] decrypted) {
    if (decrypted.length < 11) {
      logger.warning("Truncated message: " + decrypted.length);
      if (!authenticated) {
        AuthAudit.authFail("newcamd", username != null ? username : "?", remoteAddress, "truncated");
      }
      close();
      return;
    }

    int commandTag = decrypted[10] & 0xFF;
    logger.finer("Received message, commandTag=0x" + Integer.toHexString(commandTag));

    if (!authenticated) {
      handleLogin(decrypted, commandTag);
      return;
    }

    if (decrypted.length < 13) {
      logger.warning("Truncated authenticated message: " + decrypted.length);
      close();
      return;
    }

    // Extract ECM data — fail-close if declared length > available
    int customDataLen = ((decrypted[11] & 0x0F) << 8) | (decrypted[12] & 0xFF);
    if (customDataLen > decrypted.length - 13) {
      logger.warning("Truncated ECM declaredLen " + customDataLen + " > available " + (decrypted.length - 13));
      close();
      return;
    }
    byte[] customData = new byte[customDataLen];
    if (customDataLen > 0) System.arraycopy(decrypted, 13, customData, 0, customDataLen);

    int sequence = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
    int serviceId = ((decrypted[2] & 0xFF) << 8) | (decrypted[3] & 0xFF);
    int wireCaId = ((decrypted[4] & 0xFF) << 8) | (decrypted[5] & 0xFF);
    int caId = wireCaId > 0 ? wireCaId : Math.max(0, profileCaId);
    int provider = ((decrypted[6] & 0xFF) << 16) | ((decrypted[7] & 0xFF) << 8) | (decrypted[8] & 0xFF);
    if (provider == 0 && profileProviders != null && profileProviders.length == 1) {
      provider = profileProviders[0];
    }
    int networkId = profileNetworkId; // fallback -1 (legacy tests sans config)
    int dataHash = computeHash(customData);
    ReplyHeader header = new ReplyHeader(sequence, serviceId, caId);

    switch (commandTag) {
      case NewcamdConstants.MSG_CARD_DATA_REQ:
        sendCardData(header);
        break;

      case NewcamdConstants.MSG_KEEPALIVE:
        sendKeepAlive(header);
        break;

      case 0x80: case 0x81: case 0x82:
        EcmKey key = new EcmKey(commandTag, serviceId, networkId, caId, dataHash);
        EcmRequest request = buildEcmRequest(key, customData, profileMaxCwWaitMs).withProvider(provider);
        processEcm(request, header);
        break;

      default:
        logger.fine("Unknown command tag: 0x" + Integer.toHexString(commandTag));
    }
  }

  // Exposé pour tests
  boolean isAuthPending() { return authPending; }

  private void handleLogin(byte[] decrypted, int commandTag) {
    if (authPending) {
      logger.warning("Login already pending — ignoring concurrent login");
      return;
    }
    if (commandTag != NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN) {
      logger.warning("Expected login message, got 0x" + Integer.toHexString(commandTag));
      AuthAudit.authFail("newcamd", "?", remoteAddress, "unexpected-command");
      close();
      return;
    }
    if (decrypted.length < 14) {
      logger.warning("Login message truncated: " + decrypted.length);
      AuthAudit.authFail("newcamd", "?", remoteAddress, "malformed-login");
      sendLoginNak();
      close();
      return;
    }

    // Parse username/password from login message
    byte[] customData = new byte[decrypted.length - 13];
    System.arraycopy(decrypted, 13, customData, 0, customData.length);

    int null1 = -1, null2 = -1;
    for (int i = 0; i < customData.length; i++) {
      if (customData[i] == 0) {
        if (null1 == -1) null1 = i;
        else { null2 = i; break; }
      }
    }

    if (null1 <= 0 || null2 <= null1) {
      logger.warning("Invalid login message format");
      AuthAudit.authFail("newcamd", "?", remoteAddress, "malformed-login");
      sendLoginNak();
      close();
      return;
    }

    int loginSeq = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
    String user = new String(customData, 0, null1);
    String password = new String(customData, null1 + 1, null2 - null1 - 1);

    logger.info("Login attempt: " + user + " from " + remoteAddress);
    authPending = true;

    registry.authenticate(user, password).onComplete(ar -> {
      // Sérialiser sur l'event-loop de la session pour ordonner avec closeHandler
      vertx.runOnContext(v -> completeLogin(ar, user, password, loginSeq));
    });
  }

  private void completeLogin(io.vertx.core.AsyncResult<Boolean> ar, String user, String password, int loginSeq) {
    if (!alive || socket == null) {
      authPending = false;
      return;
    }

    var rec = registry.userDirectory().lookupCached(user);
    if (rec == null) {
      logger.warning("Login failed (no record) for user: " + user);
      AuthAudit.authFail("newcamd", user, remoteAddress, "unknown-user");
      sendLoginNak();
      close();
      authPending = false;
      return;
    }
    String canonical = rec.name();
    if (!rec.isEnabledEffective()) {
      logger.warning("Account disabled/expired for user: " + canonical);
      AuthAudit.authFail("newcamd", canonical, remoteAddress, "expired");
      sendLoginNak();
      close();
      authPending = false;
      return;
    }
    if (ar.failed() || !Boolean.TRUE.equals(ar.result())) {
      logger.warning("Login failed for user: " + user);
      AuthAudit.authFail("newcamd", user, remoteAddress, "bad-password");
      sendLoginNak();
      close();
      authPending = false;
      return;
    }
    if (rec.ipMask() != null && !rec.ipMask().isBlank() && !"*".equals(rec.ipMask())) {
      if (!com.bowman.cspng.users.IpMatcher.isAllowed(remoteAddress, rec.ipMask())) {
        logger.warning("IP not allowed for user: " + canonical);
        logger.fine("IP check denied for remote=" + remoteAddress);
        AuthAudit.authFail("newcamd", canonical, remoteAddress, "ip-denied");
        sendLoginNak();
        close();
        authPending = false;
        return;
      }
    }

    username = canonical;
    int maxConnections = rec.maxConnections();
    if (!registry.tryRegister(NewcamdSession.this, maxConnections)) {
      logger.warning("User " + canonical + " has max connections (" + maxConnections + ")");
      AuthAudit.authFail("newcamd", canonical, remoteAddress, "quota-connections");
      username = null;
      sendLoginNak();
      close();
      authPending = false;
      return;
    }
    // Revérifier alive/socket avant ACK : si fermeture entre tryRegister et ACK, annuler
    if (!alive || socket == null) {
      registry.unregister(this);
      username = null;
      authPending = false;
      return;
    }
    releasePreauthLease();
    authenticated = true;
    AuthAudit.authOk("newcamd", canonical, remoteAddress);

    // Envoyer LOGIN_ACK chiffré avec la clé de handshake, puis basculer sur la clé de session
    sendLoginAck(loginSeq);
    byte[] newKey14 = NewcamdCrypto.xorUserPass(configKey14, password);
    byte[] newDesKey16 = NewcamdCrypto.desKeySpread(newKey14);
    desKey16 = newDesKey16;
    // wipe temp key
    java.util.Arrays.fill(newKey14, (byte)0);
    logger.info("User " + canonical + " logged in from " + remoteAddress);
    authPending = false;
  }

  protected void sendLoginAck() {
    sendLoginAck(0);
  }
  protected void sendLoginAck(int sequence) {
    byte[] ackMsg = buildSimpleMessage(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_ACK, new byte[0], sequence);
    sendEncrypted(ackMsg);
  }

  protected void sendLoginNak() {
    byte[] nakMsg = buildSimpleMessage(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_NAK, new byte[0]);
    sendEncrypted(nakMsg);
  }

  protected void sendCardData(ReplyHeader header) {
    int[] provs = (profileProviders != null && profileProviders.length > 0) ? profileProviders : new int[]{0};
    byte[] cardData = new byte[12 + 11 * provs.length];
    cardData[0] = 0x02; // userId (anonymous)
    int ca = profileCaId > 0 ? profileCaId : 0;
    cardData[1] = (byte) ((ca >> 8) & 0xFF);
    cardData[2] = (byte) (ca & 0xFF);
    cardData[11] = (byte) (provs.length & 0xFF);
    for (int i = 0; i < provs.length; i++) {
      int prov = provs[i];
      int off = 12 + i * 11;
      cardData[off] = (byte) ((prov >> 16) & 0xFF);
      cardData[off + 1] = (byte) ((prov >> 8) & 0xFF);
      cardData[off + 2] = (byte) (prov & 0xFF);
    }
    byte[] cardMsg = buildSimpleMessage(NewcamdConstants.MSG_CARD_DATA, cardData, header.sequence());
    sendEncrypted(cardMsg);
  }

  protected void sendKeepAlive() {
    sendKeepAlive(new ReplyHeader(0, 0, 0));
  }
  protected void sendKeepAlive(ReplyHeader header) {
    byte[] kaMsg = buildSimpleMessage(NewcamdConstants.MSG_KEEPALIVE, new byte[0], header.sequence());
    sendEncrypted(kaMsg);
  }

  protected byte[] buildSimpleMessage(int commandTag, byte[] customData) {
    return buildSimpleMessage(commandTag, customData, 0);
  }
  protected byte[] buildSimpleMessage(int commandTag, byte[] customData, int sequence) {
    byte[] buffer = new byte[13 + customData.length];
    buffer[0] = (byte) ((sequence >> 8) & 0xFF);
    buffer[1] = (byte) (sequence & 0xFF);
    buffer[10] = (byte) commandTag;
    buffer[11] = (byte) ((customData.length >> 8) & 0xFF);
    buffer[12] = (byte) (customData.length & 0xFF);
    if (customData.length > 0) {
      System.arraycopy(customData, 0, buffer, 13, customData.length);
    }
    return buffer;
  }

  protected void sendEncrypted(byte[] message) {
    if (socket == null || desKey16 == null) return;
    byte[] encrypted = noEncrypt ? message :
        NewcamdCrypto.desEncrypt(message, message.length, desKey16, maxMsgSize);
    if (encrypted == null) { logger.warning("Failed to encrypt (too large)"); return; }
    Buffer out = Buffer.buffer(2 + encrypted.length);
    out.appendByte((byte) (encrypted.length >> 8));
    out.appendByte((byte) (encrypted.length & 0xFF));
    out.appendBytes(encrypted);
    socket.write(out);
  }

  protected void processEcm(EcmRequest request) {
    processEcm(request, new ReplyHeader(0, request.key().serviceId(), request.key().caId()));
  }
  protected void processEcm(EcmRequest request, ReplyHeader header) {
    boolean hasConnectedConnector = !profileCacheOnly
        && connectorManager != null
        && request.profileName() != null
        && !connectorManager.getConnectedConnectorsForProfile(request.profileName()).isEmpty();
    boolean alwaysWait = profileCacheOnly || !hasConnectedConnector;
    long maxCwWait = request.maxWaitMs() > 0 ? request.maxWaitMs() : profileMaxCwWaitMs;
    cacheVerticle.processRequest(0, request, alwaysWait, maxCwWait)
        .onComplete(ar -> {
          if (ar.succeeded() && ar.result().isHit()) {
            CwReply reply = ar.result().reply();
            sendCwReply(request, reply, header);
          } else if (!profileCacheOnly && connectorManager != null) {
            cacheVerticle.handleRemoteRequest(request);
            connectorManager.sendEcmRequest(request).onComplete(car -> {
              if (car.succeeded() && car.result() != null && !car.result().isEmpty()) {
                CwReply reply = car.result();
                cacheVerticle.handleReply(request, reply);
                sendCwReply(request, reply, header);
              } else {
                cacheVerticle.handleReply(request, null);
                sendCwReply(request, new CwReply(request.key(),
                    request.key().commandTag(), new byte[0], null, null,
                    System.currentTimeMillis()), header);
              }
            });
          } else {
            sendCwReply(request, new CwReply(request.key(),
                request.key().commandTag(), new byte[0], null, null,
                System.currentTimeMillis()), header);
          }
        });
  }

  protected void sendCwReply(EcmRequest request, CwReply reply) {
    sendCwReply(request, reply, new ReplyHeader(0, request.key().serviceId(), request.key().caId()));
  }
  protected void sendCwReply(EcmRequest request, CwReply reply, ReplyHeader header) {
    byte[] replyData = reply.isEmpty() ? new byte[0] : reply.cw();
    int commandTag = (request != null && request.key() != null
        && (request.key().commandTag() == 0x80 || request.key().commandTag() == 0x81))
        ? request.key().commandTag()
        : reply.commandTag();
    byte[] message = buildSimpleMessage(commandTag, replyData, header.sequence());
    if (header != null && header.serviceId() != 0) {
      message[2] = (byte) ((header.serviceId() >> 8) & 0xFF);
      message[3] = (byte) (header.serviceId() & 0xFF);
    }
    sendEncrypted(message);
  }

  protected void cleanup() {
    alive = false;
    if (socket != null) {
      socket.handler(null); // detach handler to prevent further processing
    }
    fireCloseHooks();
    registry.unregister(this);
  }

  @Override
  public void close() {
    alive = false;
    if (socket != null) socket.close();
    cleanup();
  }

  protected static int computeHash(byte[] data) {
    int h = 0;
    for (byte b : data) h = 31 * h + (b & 0xFF);
    return h;
  }

  protected static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }
}
