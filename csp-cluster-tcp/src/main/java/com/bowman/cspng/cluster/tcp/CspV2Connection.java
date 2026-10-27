package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cspv2.CspV2Codec;
import com.bowman.cspng.codec.cspv2.CspV2Constants;
import com.bowman.cspng.codec.cspv2.CspV2ErrorCode;
import com.bowman.cspng.codec.cspv2.CspV2Header;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.codec.cspv2.CspV2ProtocolException;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Moteur de connexion CSPv2 — Lot3B recovery fix.
 * Single writer event-loop, borné, aucun blocking/sleep/lock.
 * Utilise RecordParser.newFixed(20) avec bascule header/payload.
 */
public final class CspV2Connection {

  static final int WRITE_QUEUE_MAX_SIZE = 64 * 1024;
  private static final long ECM_ROUTING_CAP = 0x00000001L;

  // ---------------------------------------------------------------------------
  // Socket abstraction
  // ---------------------------------------------------------------------------
  interface SocketDelegate {
    List<Certificate> peerCertificates();
    void setWriteQueueMaxSize(int size);
    boolean writeQueueFull();
    Future<Void> write(Buffer data);
    void drainHandler(Handler<Void> h);
    void closeHandler(Handler<Void> h);
    void exceptionHandler(Handler<Throwable> h);
    void handler(Handler<Buffer> h);
    void pause();
    void resume();
    void close();
  }

  static final class NetSocketDelegate implements SocketDelegate {
    private final NetSocket socket;
    NetSocketDelegate(NetSocket socket) { this.socket = socket; }
    @Override public List<Certificate> peerCertificates() {
      try { return socket.peerCertificates(); } catch (Exception e) { return List.of(); }
    }
    @Override public void setWriteQueueMaxSize(int size) { socket.setWriteQueueMaxSize(size); }
    @Override public boolean writeQueueFull() { return socket.writeQueueFull(); }
    @Override public Future<Void> write(Buffer data) { return socket.write(data); }
    @Override public void drainHandler(Handler<Void> h) { socket.drainHandler(h); }
    @Override public void closeHandler(Handler<Void> h) { socket.closeHandler(h); }
    @Override public void exceptionHandler(Handler<Throwable> h) { socket.exceptionHandler(h); }
    @Override public void handler(Handler<Buffer> h) { socket.handler(h); }
    @Override public void pause() { socket.pause(); }
    @Override public void resume() { socket.resume(); }
    @Override public void close() { socket.close(); }
    NetSocket unwrap() { return socket; }
  }

  // ---------------------------------------------------------------------------
  // Fields
  // ---------------------------------------------------------------------------
  private final Vertx vertx;
  private final Context context;
  private final SocketDelegate socket;
  private final CspV2Settings settings;
  private final CspV2PeerSettings peerSettings;
  private final String peerNodeId;
  private final String localNodeId;
  private final CspV2ConnectionRole role;
  private final CspV2ConnectionListener listener;

  private volatile CspV2ConnectionState state;
  private final long localConnectionId;
  private long remoteConnectionId = -1;

  private long negotiatedCaps;
  private long negotiatedMaxPayload;
  private long negotiatedMaxInflight;

  /** CAP_CHECKSUM_POLICY n'autorise le wire étendu qu'après HELLO/ACK mutuels. */
  public boolean supportsChecksumPolicy() {
    return isHandshakeComplete() && (negotiatedCaps & CspV2Constants.CAP_CHECKSUM_POLICY) != 0;
  }

  private long nextOutboundStreamId;
  private long lastSeenInboundStreamId = 0;

  private final Map<Long, PendingOutbound> pendingOutbound = new HashMap<>();
  private final Map<Long, IncomingPending> incomingPending = new HashMap<>();
  private int incomingInflight = 0;

  private final Deque<Long> unknownDroppedTimestamps = new ArrayDeque<>();
  private long unknownDroppedTotal = 0;
  private final Deque<Long> rateTimestamps = new ArrayDeque<>();

  // dedup ADR §9.1 : (peerNodeId, connectionId, streamId)
  private final LinkedHashMap<DedupKey, Long> dedupCache;
  private long duplicateDropped = 0;

  private Buffer pendingWriteBuffer = null;
  private boolean pendingWriteIsCritical = false;
  private int writesInFlight = 0;
  private boolean pausedForBackpressure = false;
  private boolean pausedForInflight = false;

  // timers
  private long helloTimerId = -1;
  private long idleTimerId = -1;
  private long heartbeatTimerId = -1;
  private long writeDrainTimerId = -1;
  private long gracefulDrainTimerId = -1;
  private long errorCloseTimerId = -1;
  private long backpressurePauseTimerId = -1;
  private final Map<Long, Long> perRequestTimeoutIds = new HashMap<>();

  private CspV2Header pendingHeader = null;
  private boolean readingHeader = true;
  private RecordParser parser;
  private Buffer accumulate = Buffer.buffer();
  private boolean helloReceived = false;
  private boolean closed = false;
  private boolean closing = false; // error-close pending only (NOT draining)
  private boolean listenerClosedOnce = false;

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private boolean onConnectionContext() {
    return this.context != null && Vertx.currentContext() == this.context;
  }
  private void executeOnContext(Runnable action) {
    Objects.requireNonNull(action);
    if (onConnectionContext()) action.run();
    else context.runOnContext(v -> action.run());
  }

  // ---------------------------------------------------------------------------
  // Pending structures
  // ---------------------------------------------------------------------------
  private static final class PendingOutbound {
    final CspV2Payload.EcmRequest requestPayload;
    final Promise<CspV2Payload.DcwReply> promise;
    final long streamId;
    PendingOutbound(CspV2Payload.EcmRequest rp, Promise<CspV2Payload.DcwReply> p, long sid) {
      this.requestPayload = rp; this.promise = p; this.streamId = sid;
    }
  }
  private static final class IncomingPending {
    boolean done = false;
    long timerId = -1;
    final CspV2Payload.EcmRequest request;
    final long streamId;
    IncomingPending(CspV2Payload.EcmRequest req, long sid) { this.request = req; this.streamId = sid; }
  }
  private record DedupKey(String peerNodeId, long connectionId, long streamId) {}

  private enum WriteResult { WRITTEN, QUEUED, REJECTED }

  // ---------------------------------------------------------------------------
  // Factories
  // ---------------------------------------------------------------------------
  public static CspV2Connection open(Vertx vertx, NetSocket netSocket, CspV2Settings settings,
                                     CspV2ConnectionListener listener, CspV2ConnectionRole role) {
    if (vertx == null || netSocket == null || settings == null || listener == null || role == null) {
      throw new IllegalArgumentException("args must not be null");
    }
    if (!settings.enabled()) {
      try { netSocket.close(); } catch (Exception ignore) {}
      throw new CspV2ConnectionException("csp disabled");
    }
    Context ctx = Vertx.currentContext();
    if (ctx == null || !ctx.isEventLoopContext()) {
      try { netSocket.close(); } catch (Exception ignore) {}
      throw new CspV2ConnectionException("open must be called on event-loop context");
    }
    NetSocketDelegate delegate = new NetSocketDelegate(netSocket);
    List<Certificate> certs;
    try { certs = netSocket.peerCertificates(); } catch (Exception e) { certs = List.of(); }
    String extracted;
    try {
      extracted = CspV2Identity.extractNodeId(certs);
    } catch (Exception e) {
      try { netSocket.close(); } catch (Exception ignore) {}
      throw new CspV2ConnectionException("peer identity verification failed", e);
    }
    CspV2PeerSettings peer = settings.peers().stream()
        .filter(p -> p.enabled() && p.nodeId().equals(extracted))
        .findFirst().orElse(null);
    if (peer == null) {
      try { netSocket.close(); } catch (Exception ignore) {}
      throw new CspV2ConnectionException("peer not enabled");
    }
    return new CspV2Connection(vertx, ctx, delegate, settings, peer, extracted, role, listener);
  }

  /**
   * Package-private factory for tests. Caller MUST be on the Vert.x event-loop
   * context already (throw otherwise). No blocking/CompletableFuture.get.
   */
  static CspV2Connection openForTest(Vertx vertx, SocketDelegate delegate, CspV2Settings settings,
                                     CspV2PeerSettings peer, String peerNodeId,
                                     CspV2ConnectionRole role, CspV2ConnectionListener listener) {
    Context ctx = Vertx.currentContext();
    if (ctx == null || !ctx.isEventLoopContext()) {
      throw new CspV2ConnectionException("openForTest must be called on event-loop context");
    }
    return new CspV2Connection(vertx, ctx, delegate, settings, peer, peerNodeId, role, listener);
  }

  // ---------------------------------------------------------------------------
  // Constructor
  // ---------------------------------------------------------------------------
  private CspV2Connection(Vertx vertx, Context context, SocketDelegate socket, CspV2Settings settings,
                          CspV2PeerSettings peerSettings, String peerNodeId,
                          CspV2ConnectionRole role, CspV2ConnectionListener listener) {
    this.vertx = vertx;
    this.context = context;
    this.socket = socket;
    this.settings = settings;
    this.peerSettings = peerSettings;
    this.peerNodeId = peerNodeId;
    this.localNodeId = settings.nodeId();
    this.role = role;
    this.listener = listener;
    this.state = CspV2ConnectionState.PRE_HANDSHAKE;
    if (vertx == null || socket == null || settings == null || peerSettings == null || peerNodeId == null || role == null || listener == null) {
      throw new IllegalArgumentException("args must not be null");
    }
    if (context == null || !context.isEventLoopContext()) {
      throw new IllegalArgumentException("context must be event-loop");
    }
    if (!settings.enabled()) {
      throw new IllegalArgumentException("settings must be enabled");
    }
    if (!peerSettings.enabled()) {
      throw new IllegalArgumentException("peer must be enabled");
    }
    if (!peerNodeId.equals(peerSettings.nodeId())) {
      throw new IllegalArgumentException("peer id mismatch");
    }
    this.localConnectionId = generateConnectionId();
    this.nextOutboundStreamId = (role == CspV2ConnectionRole.INITIATOR) ? 1L : 2L;
    this.negotiatedMaxPayload = settings.maxPayloadSize();
    this.negotiatedMaxInflight = settings.maxInflight();
    this.negotiatedCaps = ECM_ROUTING_CAP;
    this.dedupCache = new LinkedHashMap<>(16, 0.75f, false) {
      @Override protected boolean removeEldestEntry(Map.Entry<DedupKey, Long> eldest) {
        return size() > settings.dedupMaxEntries();
      }
    };
    socket.setWriteQueueMaxSize(WRITE_QUEUE_MAX_SIZE);
    installFraming();
    socket.closeHandler(v -> {
      if (closed) return;
      executeOnContext(this::closeSilently);
    });
    socket.exceptionHandler(t -> {
      if (closed) return;
      executeOnContext(this::closeSilently);
    });
    scheduleHelloTimeout();
    if (role == CspV2ConnectionRole.INITIATOR) {
      sendHello();
    }
  }

  private static long generateConnectionId() {
    long v;
    do { v = SECURE_RANDOM.nextLong() & Long.MAX_VALUE; } while (v == 0);
    return v;
  }

  // ---------------------------------------------------------------------------
  // Framing
  // ---------------------------------------------------------------------------
  private void installFraming() {
    if (socket instanceof NetSocketDelegate nsd) {
      NetSocket ns = nsd.unwrap();
      parser = RecordParser.newFixed(CspV2Constants.HEADER_SIZE, ns);
      parser.exceptionHandler(t -> {
        if (closed) return;
        if (closing) { executeOnContext(this::closeSilently); return; }
        executeOnContext(this::closeSilently);
      });
      parser.handler(buf -> {
        if (closed) return;
        if (onConnectionContext()) onBuffer(buf);
        else executeOnContext(() -> onBuffer(buf));
      });
    } else {
      socket.handler(chunk -> {
        if (closed) return;
        if (onConnectionContext()) onBufferFakePath(chunk);
        else executeOnContext(() -> onBufferFakePath(chunk));
      });
      socket.exceptionHandler(t -> {
        if (closed) return;
        executeOnContext(this::closeSilently);
      });
    }
  }

  private void onBufferFakePath(Buffer chunk) {
    if (closing || closed) return;
    if (state == CspV2ConnectionState.DRAINING) {
      // DRAINING is not closing; continue reading
    }
    accumulate.appendBuffer(chunk);
    while (true) {
      if (closing || closed) return;
      if (readingHeader) {
        if (accumulate.length() < CspV2Constants.HEADER_SIZE) return;
        byte[] headerBytes = accumulate.getBytes(0, CspV2Constants.HEADER_SIZE);
        Buffer headerBuf = Buffer.buffer(headerBytes);
        int remLen = accumulate.length() - CspV2Constants.HEADER_SIZE;
        if (remLen == 0) accumulate = Buffer.buffer();
        else {
          byte[] remaining = accumulate.getBytes(CspV2Constants.HEADER_SIZE, accumulate.length());
          accumulate = Buffer.buffer(remaining);
        }
        handleHeaderBuffer(headerBuf);
        if (closed || closing) return;
        if (pendingHeader != null && pendingHeader.payloadLength() == 0) {
          CspV2Header h = pendingHeader;
          pendingHeader = null;
          handlePayloadBuffer(h, Buffer.buffer());
          if (closed || closing) return;
          continue;
        }
        if (pendingHeader != null) readingHeader = false;
        else continue;
      } else {
        long need = pendingHeader.payloadLength();
        if (accumulate.length() < need) return;
        Buffer payloadBuf;
        if (need == 0) payloadBuf = Buffer.buffer();
        else {
          byte[] plBytes = accumulate.getBytes(0, (int) need);
          payloadBuf = Buffer.buffer(plBytes);
          int remLen2 = accumulate.length() - (int) need;
          if (remLen2 == 0) accumulate = Buffer.buffer();
          else {
            byte[] rem = accumulate.getBytes((int) need, accumulate.length());
            accumulate = Buffer.buffer(rem);
          }
        }
        CspV2Header h = pendingHeader;
        pendingHeader = null;
        readingHeader = true;
        handlePayloadBuffer(h, payloadBuf);
        if (closed || closing) return;
      }
    }
  }

  private void onBuffer(Buffer chunk) {
    if (closing || closed) return;
    if (state == CspV2ConnectionState.DRAINING) {
      // DRAINING is not closing; continue reading
    }
    if (readingHeader) {
      handleHeaderBuffer(chunk);
      if (closed || closing) return;
      if (pendingHeader != null) {
        if (pendingHeader.payloadLength() == 0) {
          CspV2Header h = pendingHeader;
          pendingHeader = null;
          handlePayloadBuffer(h, Buffer.buffer());
        } else {
          readingHeader = false;
          if (parser != null) parser.fixedSizeMode((int) pendingHeader.payloadLength());
        }
      }
    } else {
      CspV2Header h = pendingHeader;
      pendingHeader = null;
      readingHeader = true;
      if (parser != null) parser.fixedSizeMode(CspV2Constants.HEADER_SIZE);
      handlePayloadBuffer(h, chunk);
    }
  }

  private void handleHeaderBuffer(Buffer buf) {
    if (buf.length() != CspV2Constants.HEADER_SIZE) {
      closeSilently();
      return;
    }
    byte[] headerBytes = buf.getBytes();
    long effectiveMax = isHandshakeComplete() ? negotiatedMaxPayload : settings.maxPayloadSize();
    try {
      CspV2Header header = CspV2Codec.decodeHeader(headerBytes, effectiveMax);
      pendingHeader = header;
    } catch (CspV2ProtocolException e) {
      if (e.isInvalidMagic()) { closeSilently(); return; }
      if (!isHandshakeComplete()) closeSilently();
      else sendErrorAndClose(mapToErrorCode(e), 0L);
    } catch (Exception e) {
      if (!isHandshakeComplete()) closeSilently(); else sendErrorAndClose(CspV2ErrorCode.MALFORMED_PAYLOAD, 0L);
    }
  }

  private void handlePayloadBuffer(CspV2Header header, Buffer payloadBuf) {
    resetIdle();
    byte[] payloadBytes = payloadBuf.getBytes();
    if (payloadBytes.length != header.payloadLength()) {
      if (!isHandshakeComplete()) closeSilently(); else sendErrorAndClose(CspV2ErrorCode.MALFORMED_PAYLOAD, header.streamId());
      return;
    }
    CspV2Payload payload;
    try {
      payload = CspV2Codec.decodePayload(header, payloadBytes, supportsChecksumPolicy());
    } catch (CspV2ProtocolException e) {
      if (!isHandshakeComplete()) closeSilently(); else sendErrorAndClose(CspV2ErrorCode.MALFORMED_PAYLOAD, header.streamId());
      return;
    } catch (Exception e) {
      if (!isHandshakeComplete()) closeSilently(); else sendErrorAndClose(CspV2ErrorCode.MALFORMED_PAYLOAD, header.streamId());
      return;
    }
    dispatchFrame(header, payload);
  }

  private void dispatchFrame(CspV2Header header, CspV2Payload payload) {
    if (closed) return;
    // closing = error-close pending; allow only DCW_REPLY/ERROR to resolve pending promises
    if (closing) {
      if (payload instanceof CspV2Payload.DcwReply || payload instanceof CspV2Payload.ErrorPayload) {
        // allow replies/errors even when closing
      } else {
        return;
      }
    }
    // DRAINING state: continue reading, allow replies/errors, reject new ECM, drop heartbeat
    if (state == CspV2ConnectionState.DRAINING && !closing) {
      if (payload instanceof CspV2Payload.EcmRequest) {
        sendStreamError(CspV2ErrorCode.SHUTTING_DOWN, header.streamId());
        return;
      }
      if (payload instanceof CspV2Payload.Hello || payload instanceof CspV2Payload.HelloAck) {
        sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId());
        return;
      }
      if (payload instanceof CspV2Payload.Heartbeat) {
        return;
      }
      // DCW_REPLY and ERROR fall through to normal handling
    }
    // Pre-handshake business check ADR §4.1
    if (!isHandshakeComplete()) {
      boolean isHandshakeFrame = payload instanceof CspV2Payload.Hello || payload instanceof CspV2Payload.HelloAck;
      if (!isHandshakeFrame) {
        sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId());
        return;
      }
    }
    switch (payload) {
      case CspV2Payload.Hello h -> handleHello(header, h);
      case CspV2Payload.HelloAck ha -> handleHelloAck(header, ha);
      case CspV2Payload.EcmRequest er -> handleIncomingEcm(header, er);
      case CspV2Payload.DcwReply dr -> handleDcwReply(header, dr);
      case CspV2Payload.ErrorPayload ep -> handleError(header, ep);
      case CspV2Payload.Heartbeat hb -> handleHeartbeat(header);
    }
  }

  // ---------------------------------------------------------------------------
  // Handshake
  // ---------------------------------------------------------------------------
  private void sendHello() {
    state = CspV2ConnectionState.HELLO_SENT;
    CspV2Payload.Hello hello = new CspV2Payload.Hello(localNodeId, localConnectionId,
        ECM_ROUTING_CAP | CspV2Constants.CAP_CHECKSUM_POLICY, settings.maxPayloadSize(), settings.maxInflight());
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    writeFrame(Buffer.buffer(frame), true);
  }

  private void handleHello(CspV2Header header, CspV2Payload.Hello hello) {
    if (role != CspV2ConnectionRole.ACCEPTOR) { sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return; }
    if (helloReceived) { sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return; }
    if (header.streamId() != 0) { closeSilently(); return; }
    helloReceived = true;
    if (!hello.nodeId().equals(peerNodeId)) { sendErrorAndClose(CspV2ErrorCode.IDENTITY_MISMATCH, header.streamId()); return; }
    if (localNodeId.compareTo(peerNodeId) <= 0) {
      sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return;
    }
    if (hello.connectionId() <= 0) { closeSilently(); return; }
    remoteConnectionId = hello.connectionId();
    long remoteCaps = hello.capabilities();
    long remoteMaxPayload = hello.maxPayloadAccepted();
    long remoteMaxInflight = hello.maxInflightAccepted();
    if ((remoteCaps & ECM_ROUTING_CAP) == 0) { sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return; }
    if (remoteMaxPayload < CspV2Constants.MIN_MAX_PAYLOAD || remoteMaxPayload > CspV2Constants.MAX_MAX_PAYLOAD
        || remoteMaxInflight < CspV2Constants.MIN_MAX_INFLIGHT || remoteMaxInflight > CspV2Constants.MAX_MAX_INFLIGHT) {
      closeSilently(); return;
    }
    negotiatedCaps = ECM_ROUTING_CAP | (remoteCaps & CspV2Constants.CAP_CHECKSUM_POLICY);
    negotiatedMaxPayload = Math.min(settings.maxPayloadSize(), remoteMaxPayload);
    negotiatedMaxInflight = Math.min(settings.maxInflight(), remoteMaxInflight);
    CspV2Payload.HelloAck ack = new CspV2Payload.HelloAck(localNodeId, remoteConnectionId, negotiatedCaps, negotiatedMaxPayload, negotiatedMaxInflight);
    byte[] frame = CspV2Codec.encodeFrame(ack, 0L);
    writeFrame(Buffer.buffer(frame), true);
    transitionToEstablished();
  }

  private void handleHelloAck(CspV2Header header, CspV2Payload.HelloAck ack) {
    if (role != CspV2ConnectionRole.INITIATOR) { sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return; }
    if (state != CspV2ConnectionState.HELLO_SENT) { sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return; }
    if (header.streamId() != 0) { closeSilently(); return; }
    if (!ack.nodeId().equals(peerNodeId)) { closeSilently(); return; }
    if (ack.connectionId() != localConnectionId) { closeSilently(); return; }
    if ((ack.capabilities() & ECM_ROUTING_CAP) == 0) { closeSilently(); return; }
    if ((ack.capabilities() & ~(ECM_ROUTING_CAP | CspV2Constants.CAP_CHECKSUM_POLICY)) != 0) { closeSilently(); return; }
    long ackMaxPayload = ack.maxPayloadAccepted();
    long ackMaxInflight = ack.maxInflightAccepted();
    if (ackMaxPayload < CspV2Constants.MIN_MAX_PAYLOAD || ackMaxPayload > CspV2Constants.MAX_MAX_PAYLOAD
        || ackMaxInflight < CspV2Constants.MIN_MAX_INFLIGHT || ackMaxInflight > CspV2Constants.MAX_MAX_INFLIGHT) {
      closeSilently(); return;
    }
    if (ackMaxPayload > settings.maxPayloadSize() || ackMaxInflight > settings.maxInflight()) { closeSilently(); return; }
    if ((ack.capabilities() & ECM_ROUTING_CAP) == 0) { closeSilently(); return; }
    negotiatedCaps = ack.capabilities();
    negotiatedMaxPayload = ackMaxPayload;
    negotiatedMaxInflight = ackMaxInflight;
    remoteConnectionId = ack.connectionId();
    transitionToEstablished();
  }

  private void transitionToEstablished() {
    if (state == CspV2ConnectionState.ESTABLISHED) return;
    state = CspV2ConnectionState.ESTABLISHED;
    cancelHelloTimeout();
    scheduleIdle();
    scheduleHeartbeat();
    try { listener.onEstablished(peerNodeId); } catch (Exception ignore) {}
  }

  // ---------------------------------------------------------------------------
  // Stream allocation
  // ---------------------------------------------------------------------------
  private long allocateStreamId() {
    long id = nextOutboundStreamId;
    // validate id sanity (id is always positive from initialization)
    if (id <= 0) { closeSilently(); throw new CspV2ConnectionException("streamId invalid"); }
    if (role == CspV2ConnectionRole.INITIATOR && (id % 2 == 0)) throw new CspV2ConnectionException("parity");
    if (role == CspV2ConnectionRole.ACCEPTOR && (id % 2 != 0)) throw new CspV2ConnectionException("parity");
    // check if next allocation would overflow; if so, refuse new ECM
    long next = id + 2;
    if (next < id || next <= 0) {
      // overflow: next wrapped past Long.MAX_VALUE
      nextOutboundStreamId = Long.MAX_VALUE;
      sendErrorAndClose(CspV2ErrorCode.STREAM_ID_INVALID, 0L);
      throw new CspV2ConnectionException("streamId wrap");
    }
    nextOutboundStreamId = next;
    return id;
  }

  void setNextOutboundStreamIdForTest(long v) { this.nextOutboundStreamId = v; }

  // ---------------------------------------------------------------------------
  // Outbound request
  // ---------------------------------------------------------------------------
  public Future<CspV2Payload.DcwReply> request(CspV2Payload.EcmRequest req) {
    if (!onConnectionContext()) {
      Promise<CspV2Payload.DcwReply> p = Promise.promise();
      executeOnContext(() -> request(req).onComplete(ar -> {
        if (ar.succeeded()) p.complete(ar.result()); else p.fail(ar.cause());
      }));
      return p.future();
    }
    if (closed || closing || state != CspV2ConnectionState.ESTABLISHED) {
      return Future.failedFuture(new CspV2ConnectionException("not established"));
    }
    if (req == null) return Future.failedFuture(new IllegalArgumentException("req null"));
    if (pendingOutbound.size() >= negotiatedMaxInflight) {
      return Future.failedFuture(new CspV2ConnectionException("too many inflight"));
    }
    long sid;
    try { sid = allocateStreamId(); } catch (Exception e) { return Future.failedFuture(e); }
    Promise<CspV2Payload.DcwReply> promise = Promise.promise();
    PendingOutbound po = new PendingOutbound(req, promise, sid);
    pendingOutbound.put(sid, po);
    byte[] full;
    try { full = CspV2Codec.encodeFrame(req, sid); } catch (Exception e) { pendingOutbound.remove(sid); return Future.failedFuture(e); }
    WriteResult wr = writeFrame(Buffer.buffer(full), true);
    if (wr == WriteResult.REJECTED) {
      pendingOutbound.remove(sid);
      if (!promise.future().isComplete()) promise.tryFail(new CspV2ConnectionException("backpressure rejected"));
      return promise.future();
    }
    long timerId = vertx.setTimer(req.maxWaitMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(() -> handleOutboundTimeout(sid)); return; }
      handleOutboundTimeout(sid);
    });
    perRequestTimeoutIds.put(sid, timerId);
    return promise.future();
  }

  private void handleOutboundTimeout(long sid) {
    if (closed) return;
    PendingOutbound removed = pendingOutbound.remove(sid);
    if (removed != null) {
      perRequestTimeoutIds.remove(sid);
      removed.promise.tryFail(new CspV2ConnectionException("timeout"));
      maybeFinishDrain();
    }
  }

  // ---------------------------------------------------------------------------
  // Incoming ECM
  // ---------------------------------------------------------------------------
  private void handleIncomingEcm(CspV2Header header, CspV2Payload.EcmRequest req) {
    if (closed || closing || state != CspV2ConnectionState.ESTABLISHED) {
      sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return;
    }
    long sid = header.streamId();
    // dedup before monotonicity
    if (remoteConnectionId != -1) {
      purgeExpiredDedup();
      DedupKey key = new DedupKey(peerNodeId, remoteConnectionId, sid);
      Long existing = dedupCache.get(key);
      if (existing != null) {
        duplicateDropped++;
        return;
      }
    }
    boolean isOdd = (sid % 2 != 0);
    boolean expectedOdd = (role == CspV2ConnectionRole.ACCEPTOR);
    if (isOdd != expectedOdd) { sendErrorAndClose(CspV2ErrorCode.STREAM_ID_INVALID, sid); return; }
    if (sid <= lastSeenInboundStreamId) { sendErrorAndClose(CspV2ErrorCode.STREAM_ID_INVALID, sid); return; }
    lastSeenInboundStreamId = sid;

    if (remoteConnectionId != -1) {
      DedupKey key = new DedupKey(peerNodeId, remoteConnectionId, sid);
      dedupCache.put(key, System.nanoTime());
    }

    if (!peerSettings.profiles().contains(req.profile())) {
      CspV2Payload.DcwReply denied = new CspV2Payload.DcwReply(
          req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(),
          0, CspV2Payload.DcwStatus.DENIED, new byte[0], "");
      byte[] frame = CspV2Codec.encodeFrame(denied, sid);
      writeFrame(Buffer.buffer(frame), true);
      return;
    }

    if (closed || closing) return;
    long now = System.nanoTime();
    long oneSecNs = 1_000_000_000L;
    while (!rateTimestamps.isEmpty() && now - rateTimestamps.peekFirst() > oneSecNs) rateTimestamps.pollFirst();
    if (rateTimestamps.size() >= settings.requestsPerSecondPerNode()) {
      sendErrorAndClose(CspV2ErrorCode.RATE_LIMITED, sid); return;
    }
    rateTimestamps.addLast(now);

    if (incomingPending.size() >= negotiatedMaxInflight || incomingInflight >= negotiatedMaxInflight) {
      if (!pausedForInflight) { pausedForInflight = true; socket.pause(); suspendIdleForBackpressure(); }
      sendErrorAndClose(CspV2ErrorCode.TOO_MANY_INFLIGHT, sid); return;
    }
    if (incomingPending.size() + 1 >= negotiatedMaxInflight) {
      if (!pausedForInflight) { pausedForInflight = true; socket.pause(); suspendIdleForBackpressure(); }
    }
    incomingInflight++;

    IncomingPending pending = new IncomingPending(req, sid);
    incomingPending.put(sid, pending);
    long timerId = vertx.setTimer(req.maxWaitMs(), tid -> {
      if (!onConnectionContext()) { executeOnContext(() -> onIncomingTimeout(sid)); return; }
      onIncomingTimeout(sid);
    });
    pending.timerId = timerId;

    Future<CspV2Payload.DcwReply> tmpFut;
    try {
      tmpFut = listener.onEcmRequest(peerNodeId, req, supportsChecksumPolicy());
      if (tmpFut == null) tmpFut = Future.failedFuture(new NullPointerException("null future"));
    } catch (Exception e) {
      tmpFut = Future.failedFuture(e);
    }
    final Future<CspV2Payload.DcwReply> fut = tmpFut;
    fut.onComplete(ar -> {
      if (!onConnectionContext()) {
        executeOnContext(() -> handleListenerCompletion(sid, ar));
      } else {
        handleListenerCompletion(sid, ar);
      }
    });
  }

  private void onIncomingTimeout(long sid) {
    if (closed) return;
    IncomingPending p = incomingPending.get(sid);
    if (p == null || p.done) return;
    CspV2Payload.DcwReply notFound = new CspV2Payload.DcwReply(
        p.request.commandTag(), p.request.serviceId(), p.request.networkId(), p.request.caId(), p.request.dataHash(),
        0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    completeIncoming(sid, notFound, true);
  }

  private void handleListenerCompletion(long sid, io.vertx.core.AsyncResult<CspV2Payload.DcwReply> ar) {
    if (closed) return;
    IncomingPending p = incomingPending.get(sid);
    if (p == null || p.done) return;
    if (ar.failed() || ar.result() == null) {
      CspV2Payload.DcwReply notFound = new CspV2Payload.DcwReply(
          p.request.commandTag(), p.request.serviceId(), p.request.networkId(), p.request.caId(), p.request.dataHash(),
          0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
      completeIncoming(sid, notFound, false);
    } else {
      CspV2Payload.DcwReply reply = ar.result();
      CspV2Payload.DcwReply validated = validateDcwReplyForWire(p.request, reply);
      if (!listener.acceptsReply(peerNodeId, p.request, validated, supportsChecksumPolicy())) {
        validated = new CspV2Payload.DcwReply(p.request.commandTag(), p.request.serviceId(), p.request.networkId(),
            p.request.caId(), p.request.dataHash(), 0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
      }
      completeIncoming(sid, validated, false);
    }
  }

  private CspV2Payload.DcwReply validateDcwReplyForWire(CspV2Payload.EcmRequest orig, CspV2Payload.DcwReply reply) {
    boolean echoOk = reply.commandTag() == orig.commandTag()
        && reply.serviceId() == orig.serviceId()
        && reply.networkId() == orig.networkId()
        && reply.caId() == orig.caId()
        && reply.dataHash() == orig.dataHash();
    if (!echoOk) {
      return new CspV2Payload.DcwReply(
          orig.commandTag(), orig.serviceId(), orig.networkId(), orig.caId(), orig.dataHash(),
          0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    }
    String cn = reply.connectorName();
    if (!settings.exposeConnectorName()) cn = "";
    if (cn == null) cn = "";
    if (reply.status() == CspV2Payload.DcwStatus.OK) {
      if (reply.cw().length != 16) {
        return new CspV2Payload.DcwReply(orig.commandTag(), orig.serviceId(), orig.networkId(), orig.caId(), orig.dataHash(),
            reply.replyTag(), CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
      }
    } else {
      if (reply.cw().length != 0) {
        return new CspV2Payload.DcwReply(orig.commandTag(), orig.serviceId(), orig.networkId(), orig.caId(), orig.dataHash(),
            reply.replyTag(), reply.status(), new byte[0], cn);
      }
    }
    if (!settings.exposeConnectorName() && !cn.isEmpty()) {
      return new CspV2Payload.DcwReply(reply.commandTag(), reply.serviceId(), reply.networkId(), reply.caId(), reply.dataHash(),
          reply.replyTag(), reply.status(), reply.cw(), "");
    }
    if (!Objects.equals(cn, reply.connectorName()) || !echoOk) {
      return new CspV2Payload.DcwReply(reply.commandTag(), reply.serviceId(), reply.networkId(), reply.caId(), reply.dataHash(),
          reply.replyTag(), reply.status(), reply.cw(), cn);
    }
    return reply;
  }

  private void completeIncoming(long sid, CspV2Payload.DcwReply reply, boolean isTimeout) {
    IncomingPending p = incomingPending.get(sid);
    if (p == null || p.done) return;
    p.done = true;
    if (p.timerId != -1) { vertx.cancelTimer(p.timerId); p.timerId = -1; }
    incomingPending.remove(sid);
    incomingInflight--;
    if (incomingInflight < 0) incomingInflight = 0;
    if (pausedForInflight && incomingPending.size() < negotiatedMaxInflight && incomingInflight < negotiatedMaxInflight) {
      pausedForInflight = false;
      if (!pausedForBackpressure && !closed) {
        socket.resume();
        resumeIdleAfterBackpressure();
      }
    }
    if (reply != null) {
      byte[] frame = CspV2Codec.encodeFrame(reply, sid, supportsChecksumPolicy());
      writeFrame(Buffer.buffer(frame), true);
    }
    maybeFinishDrain();
  }

  private void purgeExpiredDedup() {
    long now = System.nanoTime();
    long ttlNs = settings.dedupTtlMs() * 1_000_000L;
    Iterator<Map.Entry<DedupKey, Long>> it = dedupCache.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<DedupKey, Long> e = it.next();
      if (now - e.getValue() > ttlNs) it.remove();
      else break;
    }
  }

  private void handleDcwReply(CspV2Header header, CspV2Payload.DcwReply reply) {
    if (!isHandshakeComplete() || (state != CspV2ConnectionState.ESTABLISHED && state != CspV2ConnectionState.DRAINING)) {
      sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return;
    }
    long sid = header.streamId();
    PendingOutbound po = pendingOutbound.remove(sid);
    if (po == null) { recordUnknownDropped(); return; }
    Long timerId = perRequestTimeoutIds.remove(sid);
    if (timerId != null) vertx.cancelTimer(timerId);
    CspV2Payload.EcmRequest orig = po.requestPayload;
    if (reply.commandTag() != orig.commandTag()
        || reply.serviceId() != orig.serviceId()
        || reply.networkId() != orig.networkId()
        || reply.caId() != orig.caId()
        || reply.dataHash() != orig.dataHash()) {
      recordUnknownDropped();
      po.promise.tryFail(new CspV2ConnectionException("echo mismatch"));
      maybeFinishDrain();
      return;
    }
    if (!listener.acceptsInboundReply(peerNodeId, orig, reply, supportsChecksumPolicy())) {
      po.promise.tryFail(new CspV2ConnectionException("invalid reply policy"));
    } else {
      po.promise.tryComplete(reply);
    }
    maybeFinishDrain();
  }

  private void recordUnknownDropped() {
    if (closed || closing) return;
    long now = System.nanoTime();
    long oneMinNs = 60_000_000_000L;
    while (!unknownDroppedTimestamps.isEmpty() && now - unknownDroppedTimestamps.peekFirst() > oneMinNs) unknownDroppedTimestamps.pollFirst();
    if (unknownDroppedTimestamps.size() >= settings.unknownStreamsPerMinute()) {
      unknownDroppedTotal++;
      sendErrorAndClose(CspV2ErrorCode.STREAM_ID_INVALID, 0L);
      return;
    }
    unknownDroppedTimestamps.addLast(now);
    unknownDroppedTotal++;
    if (unknownDroppedTimestamps.size() > settings.unknownStreamsPerMinute()) {
      sendErrorAndClose(CspV2ErrorCode.STREAM_ID_INVALID, 0L);
    }
  }

  private void handleHeartbeat(CspV2Header header) {
    if (!isHandshakeComplete() || state != CspV2ConnectionState.ESTABLISHED) {
      sendErrorAndClose(CspV2ErrorCode.NOT_AUTHORIZED, header.streamId()); return;
    }
  }

  private void handleError(CspV2Header header, CspV2Payload.ErrorPayload ep) {
    long sid = header.streamId();
    if (sid > 0) {
      // targeted error: resolve specific pending stream
      PendingOutbound po = pendingOutbound.remove(sid);
      if (po != null) {
        Long tid = perRequestTimeoutIds.remove(sid);
        if (tid != null) vertx.cancelTimer(tid);
        po.promise.tryFail(new CspV2ConnectionException("peer error " + ep.code().canonicalReason()));
        maybeFinishDrain();
        return;
      } else {
        recordUnknownDropped();
        return;
      }
    } else {
      // global error
      if (ep.code() == CspV2ErrorCode.SHUTTING_DOWN) {
        // peer is draining — enter DRAINING or stay DRAINING, never close abruptly
        if (state == CspV2ConnectionState.ESTABLISHED) {
          state = CspV2ConnectionState.DRAINING;
          cancelHeartbeat();
          cancelIdle();
          scheduleGracefulDrainClose();
          maybeFinishDrain();
        } else if (state == CspV2ConnectionState.DRAINING) {
          // already draining — keep pending, reschedule timer only if absent (no indefinite extension)
          if (gracefulDrainTimerId == -1) scheduleGracefulDrainClose();
          maybeFinishDrain();
        } else {
          closeSilently();
        }
      } else {
        closeSilently();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Timers
  // ---------------------------------------------------------------------------
  private void scheduleHelloTimeout() {
    helloTimerId = vertx.setTimer(settings.helloMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::scheduleHelloTimeoutCheck); return; }
      scheduleHelloTimeoutCheck();
    });
  }
  private void scheduleHelloTimeoutCheck() {
    if (state != CspV2ConnectionState.ESTABLISHED) closeSilently();
  }
  private void cancelHelloTimeout() { if (helloTimerId != -1) { vertx.cancelTimer(helloTimerId); helloTimerId = -1; } }
  private void scheduleIdle() {
    cancelIdle();
    idleTimerId = vertx.setTimer(settings.idleMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::closeSilently); return; }
      closeSilently();
    });
  }
  private void cancelIdle() { if (idleTimerId != -1) { vertx.cancelTimer(idleTimerId); idleTimerId = -1; } }
  private void resetIdle() {
    if (state == CspV2ConnectionState.ESTABLISHED && !pausedForBackpressure && !pausedForInflight && !closing && !closed) scheduleIdle();
  }
  private void scheduleHeartbeat() {
    cancelHeartbeat();
    heartbeatTimerId = vertx.setPeriodic(settings.heartbeatMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::doHeartbeat); return; }
      doHeartbeat();
    });
  }
  private void doHeartbeat() {
    if (closed || closing || state != CspV2ConnectionState.ESTABLISHED) return;
    if (socket.writeQueueFull()) return;
    CspV2Payload.Heartbeat hb = new CspV2Payload.Heartbeat();
    byte[] frame = CspV2Codec.encodeFrame(hb, 0L);
    writeFrame(Buffer.buffer(frame), false);
  }
  private void cancelHeartbeat() { if (heartbeatTimerId != -1) { vertx.cancelTimer(heartbeatTimerId); heartbeatTimerId = -1; } }
  private void suspendIdleForBackpressure() {
    cancelIdle();
    if (backpressurePauseTimerId != -1) vertx.cancelTimer(backpressurePauseTimerId);
    backpressurePauseTimerId = vertx.setTimer(settings.backpressurePauseMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::closeSilently); return; }
      closeSilently();
    });
  }
  private void resumeIdleAfterBackpressure() {
    if (backpressurePauseTimerId != -1) { vertx.cancelTimer(backpressurePauseTimerId); backpressurePauseTimerId = -1; }
    if (state == CspV2ConnectionState.ESTABLISHED && !pausedForBackpressure && !pausedForInflight && !closing && !closed) scheduleIdle();
  }

  // ---------------------------------------------------------------------------
  // Unified write — single point for all socket.write calls
  // ---------------------------------------------------------------------------

  /**
   * Execute a socket write with writesInFlight tracking.
   * Handles synchronous throw and null Future from socket.write.
   * @param data the buffer to write
   * @param onSuccess optional callback run after successful write
   */
  private void writeNow(Buffer data, Runnable onSuccess) {
    writesInFlight++;
    Future<Void> fut;
    try {
      fut = socket.write(data);
    } catch (Exception e) {
      // synchronous throw — decrement exactly once
      writesInFlight--;
      if (writesInFlight < 0) {
        // invariant violated — hard close without listener re-call
        closed = true;
        return;
      }
      closeSilently();
      return;
    }
    if (fut == null) {
      // null Future — decrement exactly once
      writesInFlight--;
      closeSilently();
      return;
    }
    fut.onComplete(ar -> {
      if (!onConnectionContext()) {
        executeOnContext(() -> writeNowCallback(ar, onSuccess));
        return;
      }
      writeNowCallback(ar, onSuccess);
    });
  }

  private void writeNow(Buffer data) {
    writeNow(data, null);
  }

  private void writeNowCallback(io.vertx.core.AsyncResult<Void> ar, Runnable onSuccess) {
    if (closed) {
      // after close: bookkeeping only — decrement, no listener, no closeSilently
      writesInFlight--;
      if (writesInFlight < 0) writesInFlight = 0;
      return;
    }
    writesInFlight--;
    if (writesInFlight < 0) {
      // invariant violated — must not happen in production, close hard
      closeSilently();
      return;
    }
    if (ar.failed()) {
      closeSilently();
    } else {
      if (onSuccess != null) onSuccess.run();
      maybeFinishDrain();
    }
  }

  /**
   * Queue-aware frame writer. Critical frames may be queued when write queue is full.
   * Non-critical frames (heartbeat) are silently dropped when queue is full.
   * Actual socket writes are performed exclusively via writeNow.
   */
  private WriteResult writeFrame(Buffer frame, boolean isCritical) {
    if (closed || closing) return WriteResult.REJECTED;
    if (isCritical) {
      if (socket.writeQueueFull()) {
        if (pendingWriteBuffer != null) {
          // second critical while slot occupied => close
          closeSilently();
          return WriteResult.REJECTED;
        }
        pendingWriteBuffer = frame;
        pendingWriteIsCritical = true;
        if (!pausedForBackpressure) {
          pausedForBackpressure = true;
          socket.pause();
          suspendIdleForBackpressure();
        }
        socket.drainHandler(v -> {
          if (!onConnectionContext()) { executeOnContext(this::handleDrain); return; }
          handleDrain();
        });
        if (writeDrainTimerId != -1) vertx.cancelTimer(writeDrainTimerId);
        writeDrainTimerId = vertx.setTimer(settings.drainMs(), id -> {
          if (!onConnectionContext()) { executeOnContext(() -> { if (pendingWriteBuffer != null) closeSilently(); }); return; }
          if (pendingWriteBuffer != null) closeSilently();
        });
        return WriteResult.QUEUED;
      }
      writeNow(frame);
      return WriteResult.WRITTEN;
    } else {
      // non-critical (heartbeat): drop if full, never occupy slot
      if (socket.writeQueueFull()) return WriteResult.REJECTED;
      if (pendingWriteBuffer != null) return WriteResult.REJECTED;
      writeNow(frame);
      return WriteResult.WRITTEN;
    }
  }

  private void handleDrain() {
    socket.drainHandler(null);
    if (writeDrainTimerId != -1) { vertx.cancelTimer(writeDrainTimerId); writeDrainTimerId = -1; }
    if (pendingWriteBuffer != null) {
      Buffer toWrite = pendingWriteBuffer;
      pendingWriteBuffer = null;
      pendingWriteIsCritical = false;
      writeNow(toWrite, this::afterDrainWrite);
      return;
    }
    afterDrainWrite();
  }

  private void afterDrainWrite() {
    pausedForBackpressure = false;
    if (!pausedForInflight && !closed) {
      socket.resume();
    }
    resumeIdleAfterBackpressure();
  }

  private void maybeFinishDrain() {
    if (state == CspV2ConnectionState.DRAINING && pendingOutbound.isEmpty() && incomingPending.isEmpty() && pendingWriteBuffer == null && writesInFlight == 0) {
      closeSilently();
    }
  }

  // ---------------------------------------------------------------------------
  // Error / close
  // ---------------------------------------------------------------------------
  private boolean isHandshakeComplete() { return state == CspV2ConnectionState.ESTABLISHED || state == CspV2ConnectionState.DRAINING; }

  private CspV2ErrorCode mapToErrorCode(CspV2ProtocolException e) {
    if (e.errorCode() != null) return e.errorCode();
    return switch (e.category()) {
      case UNSUPPORTED_VERSION -> CspV2ErrorCode.UNSUPPORTED_VERSION;
      case UNSUPPORTED_TYPE -> CspV2ErrorCode.UNSUPPORTED_TYPE;
      case UNSUPPORTED_FLAGS -> CspV2ErrorCode.UNSUPPORTED_FLAGS;
      case FRAME_TOO_LARGE -> CspV2ErrorCode.FRAME_TOO_LARGE;
      case STREAM_ID_INVALID -> CspV2ErrorCode.STREAM_ID_INVALID;
      default -> CspV2ErrorCode.MALFORMED_PAYLOAD;
    };
  }

  private void sendErrorAndClose(CspV2ErrorCode code, long sid) {
    if (closed || closing) return;
    // sid>0: targeted error directly on that stream (codec guarantees valid positive sid)
    long errorSid = (sid > 0) ? sid : 0L;
    // write error frame BEFORE setting closing flag so writeNow doesn't reject
    CspV2Payload.ErrorPayload ep = new CspV2Payload.ErrorPayload(code, code.canonicalReason());
    try {
      byte[] frame = CspV2Codec.encodeFrame(ep, errorSid);
      writeFrame(Buffer.buffer(frame), true);
    } catch (Exception ignore) {}
    closing = true;
    socket.pause();
    // schedule close: wait for writes in flight to complete, then close
    if (errorCloseTimerId != -1) vertx.cancelTimer(errorCloseTimerId);
    errorCloseTimerId = vertx.setTimer(settings.drainMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::closeSilently); return; }
      closeSilently();
    });
  }

  private void sendStreamError(CspV2ErrorCode code, long sid) {
    if (closed) return;
    CspV2Payload.ErrorPayload ep = new CspV2Payload.ErrorPayload(code, code.canonicalReason());
    try {
      byte[] frame = CspV2Codec.encodeFrame(ep, sid);
      writeFrame(Buffer.buffer(frame), true);
    } catch (Exception ignore) {}
  }

  private void scheduleGracefulDrainClose() {
    if (gracefulDrainTimerId != -1) vertx.cancelTimer(gracefulDrainTimerId);
    gracefulDrainTimerId = vertx.setTimer(settings.shutdownDrainMs(), id -> {
      if (!onConnectionContext()) { executeOnContext(this::closeSilently); return; }
      closeSilently();
    });
  }

  private void closeSilently() {
    if (closed) return;
    if (!onConnectionContext()) {
      executeOnContext(this::closeSilently);
      return;
    }
    if (closed) return;
    closed = true;
    closing = true;
    state = CspV2ConnectionState.CLOSED;
    cancelAllTimers();
    // fail outbound pending
    for (PendingOutbound po : pendingOutbound.values()) {
      Long tid = perRequestTimeoutIds.remove(po.streamId);
      if (tid != null) vertx.cancelTimer(tid);
      po.promise.tryFail(new CspV2ConnectionException("closed"));
    }
    pendingOutbound.clear();
    perRequestTimeoutIds.clear();
    // cancel incoming pending
    for (IncomingPending ip : incomingPending.values()) {
      if (ip.timerId != -1) vertx.cancelTimer(ip.timerId);
      ip.done = true;
    }
    incomingPending.clear();
    incomingInflight = 0;
    // clear pending write
    pendingWriteBuffer = null;
    pendingWriteIsCritical = false;
    // clear deques and dedup cache
    unknownDroppedTimestamps.clear();
    rateTimestamps.clear();
    dedupCache.clear();
    // clear drain handler
    socket.drainHandler(null);
    try { socket.close(); } catch (Exception ignore) {}
    if (!listenerClosedOnce) {
      listenerClosedOnce = true;
      try { listener.onClosed(peerNodeId, "closed"); } catch (Exception ignore) {}
    }
  }

  private void cancelAllTimers() {
    cancelHelloTimeout();
    cancelIdle();
    cancelHeartbeat();
    if (writeDrainTimerId != -1) { vertx.cancelTimer(writeDrainTimerId); writeDrainTimerId = -1; }
    if (gracefulDrainTimerId != -1) { vertx.cancelTimer(gracefulDrainTimerId); gracefulDrainTimerId = -1; }
    if (errorCloseTimerId != -1) { vertx.cancelTimer(errorCloseTimerId); errorCloseTimerId = -1; }
    if (backpressurePauseTimerId != -1) { vertx.cancelTimer(backpressurePauseTimerId); backpressurePauseTimerId = -1; }
    for (Long tid : perRequestTimeoutIds.values()) vertx.cancelTimer(tid);
    perRequestTimeoutIds.clear();
    for (IncomingPending ip : incomingPending.values()) {
      if (ip.timerId != -1) { vertx.cancelTimer(ip.timerId); ip.timerId = -1; }
    }
  }

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------
  public void drain() {
    if (!onConnectionContext()) { executeOnContext(this::drain); return; }
    if (closed || closing || state == CspV2ConnectionState.CLOSED || state == CspV2ConnectionState.DRAINING) return;
    state = CspV2ConnectionState.DRAINING;
    cancelHeartbeat();
    cancelIdle();
    CspV2Payload.ErrorPayload ep = new CspV2Payload.ErrorPayload(CspV2ErrorCode.SHUTTING_DOWN, CspV2ErrorCode.SHUTTING_DOWN.canonicalReason());
    byte[] frame = CspV2Codec.encodeFrame(ep, 0L);
    WriteResult wr = writeFrame(Buffer.buffer(frame), true);
    if (wr == WriteResult.REJECTED) {
      closeSilently();
      return;
    }
    // schedule max drain timer; maybeFinishDrain handles actual close when empty
    scheduleGracefulDrainClose();
  }

  public void close() {
    if (!onConnectionContext()) { executeOnContext(this::close); return; }
    closeSilently();
  }

  public CspV2ConnectionState state() { return state; }
  public String peerNodeId() { return peerNodeId; }
  public long negotiatedMaxPayload() { return negotiatedMaxPayload; }
  public long negotiatedMaxInflight() { return negotiatedMaxInflight; }
  public long negotiatedCaps() { return negotiatedCaps; }
  public int inflightOutbound() { return pendingOutbound.size(); }
  public int inflightIncoming() { return incomingInflight; }
  public long unknownDropped() { return unknownDroppedTotal; }
  public long duplicateDropped() { return duplicateDropped; }
  public CspV2ConnectionRole role() { return role; }
  public boolean isClosed() { return closed; }
  public boolean isClosing() { return closing; }

  // Package-private for tests
  SocketDelegate socketDelegate() { return socket; }
  long localConnectionId() { return localConnectionId; }
  Buffer pendingWrite() { return pendingWriteBuffer; }
  boolean isPaused() { return pausedForBackpressure || pausedForInflight; }
  Map<Long, PendingOutbound> pendingOutboundForTest() { return pendingOutbound; }
}
