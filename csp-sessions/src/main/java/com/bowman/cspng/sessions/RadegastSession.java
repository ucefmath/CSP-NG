package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheResult;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.radegast.RadegastConstants;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;

import java.util.logging.Level;

/**
 * Session Radegast — côté SERVEUR (accepte des clients Radegast).
 *
 * <p>Protocole simple TLV sans chiffrement ni authentification.
 * Le "username" est dérivé de l'adresse IP (comportement legacy).
 */
public class RadegastSession extends AbstractSession {

  private NetSocket socket;
  private CwsConnectorManager connectorManager;
  private volatile boolean profileCacheOnly = false;
  private volatile long profileMaxCwWaitMs = 5000L;

  public RadegastSession(Vertx vertx, CacheVerticle cacheVerticle,
                          SessionRegistry registry) {
    super(vertx, cacheVerticle, registry);
  }

  public void setConnectorManager(CwsConnectorManager cm) { this.connectorManager = cm; }
  public void setProfileCacheOnly(boolean cacheOnly) { this.profileCacheOnly = cacheOnly; }
  public boolean isProfileCacheOnly() { return profileCacheOnly; }
  public void setProfileMaxCwWaitMs(long maxCwWaitMs) {
    this.profileMaxCwWaitMs = maxCwWaitMs > 0 ? maxCwWaitMs : 5000L;
  }
  public long getProfileMaxCwWaitMs() { return profileMaxCwWaitMs; }

  /**
   * Accepte une connexion entrante Radegast.
   */
  public void accept(NetSocket socket) {
    this.socket = socket;
    this.remoteAddress = socket.remoteAddress().host();
    this.username = "rdg@" + remoteAddress;

    socket.exceptionHandler(t ->
        logger.log(Level.WARNING, "Radegast session error: " + t.getMessage()));
    socket.closeHandler(v -> cleanup());

    // Radegast has no authentication — register immediately
    releasePreauthLease();
    authenticated = true;
    registry.register(this);
    alive = true;

    // Setup handler for incoming TLV frames
    socket.handler(this::handleIncoming);

    logger.info("Radegast session from " + remoteAddress);
  }

  private void handleIncoming(Buffer buffer) {
    byte[] data = buffer.getBytes();
    if (data.length < 2) return;

    int type = data[0] & 0xFF;
    if (type != RadegastConstants.CMD_ECM_KEY_ASK) {
      logger.fine("Unexpected Radegast message type: " + type);
      return;
    }

    RadegastConstants.RadegastRequest req = RadegastConstants.parseRequest(data);
    if (req == null || !req.isValid()) {
      logger.warning("Invalid Radegast request from " + remoteAddress);
      return;
    }

    // Compute hash
    int dataHash = computeHash(req.customData());
    EcmKey key = new EcmKey(req.commandTag(), req.serviceId(), 0, req.caId(), dataHash);
    EcmRequest request = buildEcmRequest(key, req.customData(), profileMaxCwWaitMs);

    processEcm(request);
  }

  private void processEcm(EcmRequest request) {
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
            sendCwReply(reply);
          } else if (!profileCacheOnly && connectorManager != null) {
            cacheVerticle.handleRemoteRequest(request);
            connectorManager.sendEcmRequest(request).onComplete(car -> {
              if (car.succeeded() && car.result() != null && !car.result().isEmpty()) {
                CwReply reply = car.result();
                cacheVerticle.handleReply(request, reply);
                sendCwReply(reply);
              } else {
                cacheVerticle.handleReply(request, null);
                sendCwReply(new CwReply(request.key(),
                    request.key().commandTag(), new byte[0],
                    null, null, System.currentTimeMillis()));
              }
            });
          } else {
            sendCwReply(new CwReply(request.key(),
                request.key().commandTag(), new byte[0],
                null, null, System.currentTimeMillis()));
          }
        });
  }

  private void sendCwReply(CwReply reply) {
    if (socket == null) return;
    byte[] frame = RadegastConstants.buildReplyFrame(reply.isEmpty() ? new byte[0] : reply.cw());
    socket.write(Buffer.buffer(frame));
  }

  private void cleanup() {
    alive = false;
    fireCloseHooks();
    registry.unregister(this);
  }

  @Override
  public void close() {
    alive = false;
    if (socket != null) socket.close();
    cleanup();
  }

  private static int computeHash(byte[] data) {
    int h = 0;
    for (byte b : data) {
      h = 31 * h + (b & 0xFF);
    }
    return h;
  }
}
