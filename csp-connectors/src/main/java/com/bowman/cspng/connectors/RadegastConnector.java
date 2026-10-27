package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.radegast.RadegastConstants;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Connecteur Radegast — côté CLIENT. Lock-free FIFO bornée.
 */
public class RadegastConnector extends AbstractConnector {

  private static final int CONNECT_TIMEOUT = 10_000;
  static final int RECONNECT_DELAY = 5_000;
  private static final int ECM_REPLY_TIMEOUT_MS = 5_000;
  private static final int MAX_PENDING_REQUESTS = 1_000;

  private NetClient client;
  private NetSocket socket;
  private Promise<LoginState> loginPromise = Promise.promise();

  private final ConcurrentLinkedQueue<PendingEntry> pendingQueue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger pendingCount = new AtomicInteger(0);
  private Buffer recvBuf = Buffer.buffer();
  /** Timeout TCP+SOCKS5 configuré (ms). */
  private final int connectTimeoutMs;

  record PendingEntry(Promise<CwReply> promise, EcmKey key, Long timerId) {}

  /** Constructeur legacy (connexion directe). */
  public RadegastConnector(Vertx vertx, CacheVerticle cacheVerticle,
                             String name, String host, int port, String profile) {
    this(vertx, cacheVerticle, name, host, port, profile, CONNECT_TIMEOUT, null);
  }

  /** Constructeur avec connectTimeout et proxy SOCKS5 optionnel. */
  public RadegastConnector(Vertx vertx, CacheVerticle cacheVerticle,
                             String name, String host, int port, String profile,
                             int connectTimeoutMs, NetworkProxyOptions proxyOptions) {
    super(vertx, cacheVerticle, name, host, port, profile, proxyOptions);
    this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : CONNECT_TIMEOUT;
  }

  int pendingCount() { return pendingCount.get(); }

  @Override public Future<LoginState> loginFuture() { return loginPromise.future(); }

  private void completeLogin(LoginState state) {
    if (!loginPromise.future().isComplete()) loginPromise.complete(state);
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
    if (loginPromise.future().isComplete()) loginPromise = Promise.promise();
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
            logger.log(Level.WARNING, "Connection error: " + t.getMessage()));
        socket.closeHandler(v -> {
          if (isStale(gen)) return; // close handler d'une génération obsolète — ignoré
          connected = false;
          logger.info("Disconnected from " + host + ":" + port);
          if (!loginPromise.future().isComplete()) completeLogin(LoginState.FAILED);
          recvBuf = Buffer.buffer();
          failAllPending("Connection closed");
          scheduleReconnect();
        });
        socket.handler(this::handleIncoming);
        connected = true;
        logger.info("Connected (Radegast) to " + host + ":" + port);
        completeLogin(LoginState.OK);
        promise.complete();
      } else {
        logger.warning("Failed to connect to " + host + ":" + port + ": " + ar.cause().getMessage());
        scheduleReconnect();
        promise.fail(ar.cause());
        if (!loginPromise.future().isComplete()) completeLogin(LoginState.FAILED);
      }
    });
  }

  private void scheduleReconnect() {
    vertx.setTimer(RECONNECT_DELAY, id -> {
      if (!connected && !stopped) {
        // Fermer l'ANCIEN client avant remplacement (pas de fuite de NetClient)
        if (client != null) { try { client.close(); } catch (Exception ignore) {} }
        client = createNetClient(buildNetClientOptions(connectTimeoutMs));
        doConnect(Promise.promise());
      }
    });
  }

  private void handleIncoming(Buffer buffer) { recvBuf.appendBuffer(buffer); while (tryParseReply()) {} }

  private boolean tryParseReply() {
    int len = recvBuf.length();
    if (len < 2) return false;
    int type = recvBuf.getByte(0) & 0xFF;
    if (type != RadegastConstants.CMD_ECM_KEY_RESPONSE) {
      logger.warning("Unexpected Radegast frame type: 0x" + Integer.toHexString(type));
      recvBuf = Buffer.buffer();
      return false;
    }
    int payloadLen = recvBuf.getByte(1) & 0xFF;
    if (len < 2 + payloadLen) return false;
    byte[] frame = recvBuf.getBytes(0, 2 + payloadLen);
    int consumed = 2 + payloadLen;
    recvBuf = consumed < len ? recvBuf.getBuffer(consumed, len) : Buffer.buffer();

    byte[] cw = RadegastConstants.parseReplyCw(frame);
    if (cw.length > 0) logger.finer("Received CW reply: cwLen=" + cw.length);
    else logger.finer("Received empty reply (cannot decode)");

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
    return true;
  }

  @Override public void stop() {
    stopped = true;
    nextGeneration(); // invalide tous les callbacks de connexion en vol
    // failAllPending AVANT close() — même raisonnement que NewcamdConnector.
    failAllPending("Connector stopped");
    connected = false;
    if (socket != null) socket.close();
    if (client != null) client.close();
  }

  private void failAllPending(String reason) {
    PendingEntry entry;
    while ((entry = pendingQueue.poll()) != null) {
      pendingCount.decrementAndGet();
      vertx.cancelTimer(entry.timerId());
      entry.promise().tryFail(reason);
    }
  }

  @Override public Future<CwReply> sendEcm(EcmRequest request) {
    Promise<CwReply> promise = Promise.promise();
    if (stopped) { promise.fail("Connector stopped"); return promise.future(); }
    if (!connected || socket == null) { promise.fail("Not connected"); return promise.future(); }

    int current;
    do {
      current = pendingCount.get();
      if (current >= MAX_PENDING_REQUESTS) {
        promise.fail("Too many pending ECM requests (max=" + MAX_PENDING_REQUESTS + ")");
        return promise.future();
      }
    } while (!pendingCount.compareAndSet(current, current + 1));

    byte[] frame;
    try {
      frame = RadegastConstants.buildRequestFrame(
          request.key().caId(), 0, request.key().serviceId(), request.key().commandTag(), request.body());
    } catch (Exception e) {
      pendingCount.decrementAndGet();
      promise.fail(e);
      return promise.future();
    }

    EcmKey key = request.key();
    Long timerId = vertx.setTimer(ECM_REPLY_TIMEOUT_MS, this::onTimeout);
    PendingEntry entry = new PendingEntry(promise, key, timerId);
    pendingQueue.add(entry);

    socket.write(Buffer.buffer(frame), ar -> {
      if (!ar.succeeded()) {
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
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
    return sb.toString();
  }
}
