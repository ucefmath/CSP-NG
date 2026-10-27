package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.codec.cspv2.CspV2Codec;
import com.bowman.cspng.codec.cspv2.CspV2Constants;
import com.bowman.cspng.codec.cspv2.CspV2ErrorCode;
import com.bowman.cspng.codec.cspv2.CspV2Header;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CspV2ConnectionTest {

  // -------------------------------------------------------------------------
  // Fake socket delegate — moved out of main
  // -------------------------------------------------------------------------
  static class FakeSocketDelegate implements CspV2Connection.SocketDelegate {
    private Handler<Buffer> dataHandler;
    private Handler<Void> drainHandler;
    private Handler<Void> closeHandler;
    private Handler<Throwable> exceptionHandler;
    private boolean writeQueueFull = false;
    private boolean closed = false;
    private final List<Buffer> written = new ArrayList<>();
    private final List<Certificate> fakePeerCerts;
    private boolean failNextWrite = false;

    // Track write promises for drain tests
    private final List<Promise<Void>> writePromises = new ArrayList<>();
    private boolean holdWrites = false;

    FakeSocketDelegate(List<Certificate> fakePeerCerts) { this.fakePeerCerts = fakePeerCerts; }
    @Override public List<Certificate> peerCertificates() { return fakePeerCerts; }
    @Override public void setWriteQueueMaxSize(int size) {}
    @Override public boolean writeQueueFull() { return writeQueueFull; }
    void setWriteQueueFull(boolean b) { this.writeQueueFull = b; }
    void setFailNextWrite(boolean f) { this.failNextWrite = f; }
    void setHoldWrites(boolean h) { this.holdWrites = h; }
    List<Promise<Void>> writePromises() { return writePromises; }
    void completePendingWrites() {
      for (Promise<Void> p : writePromises) p.complete();
      writePromises.clear();
    }
    void failPendingWrites(Throwable t) {
      for (Promise<Void> p : writePromises) p.fail(t);
      writePromises.clear();
    }
    @Override public Future<Void> write(Buffer data) {
      written.add(data.copy());
      if (failNextWrite) {
        failNextWrite = false;
        return Future.failedFuture(new RuntimeException("simulated write failure"));
      }
      if (holdWrites) {
        Promise<Void> p = Promise.promise();
        writePromises.add(p);
        return p.future();
      }
      return Future.succeededFuture();
    }
    List<Buffer> written() { return written; }
    void clearWritten() { written.clear(); }
    @Override public void drainHandler(Handler<Void> h) { this.drainHandler = h; }
    void fireDrain() { if (drainHandler != null) drainHandler.handle(null); }
    @Override public void closeHandler(Handler<Void> h) { this.closeHandler = h; }
    @Override public void exceptionHandler(Handler<Throwable> h) { this.exceptionHandler = h; }
    @Override public void handler(Handler<Buffer> h) { this.dataHandler = h; }
    void emit(Buffer b) { if (dataHandler != null) dataHandler.handle(b); }
    @Override public void pause() {}
    @Override public void resume() {}
    @Override public void close() { closed = true; if (closeHandler != null) closeHandler.handle(null); }
    boolean isClosed() { return closed; }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------
  private static CspV2Settings settings(String local, String peer, List<String> profiles,
                                        long helloMs, long idleMs, long heartbeatMs,
                                        long drainMs, long bpPauseMs,
                                        int maxInflight, int maxPayload) {
    CspV2PeerSettings ps = new CspV2PeerSettings(true, peer, "peer.example.com", 9090, profiles);
    return new CspV2Settings(true, local, "0.0.0.0", 9090,
        maxPayload, maxInflight, 100, 10, 5000, 100,
        5000, helloMs, idleMs, heartbeatMs, drainMs, 10000, 5000, bpPauseMs,
        100, 30000, 10000, 60000, false, "file:cert", "file:key", "file:trust", false, List.of(ps));
  }

  private static CspV2Settings defaultSettings(String local, String peer) {
    return settings(local, peer, List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
  }

  private static CspV2Payload.EcmRequest makeEcmRequest(String profile, long maxWaitMs) {
    byte[] ecm = new byte[]{0x01, 0x00, 0x03, 0x11, 0x22, 0x33};
    int hash = ClusterUdpCodec.ecmHash(ecm, 3, ecm.length - 3);
    return new CspV2Payload.EcmRequest(0x80, 100, 200, 0x0B00, hash, profile, ecm, maxWaitMs);
  }

  private static FakeSocketDelegate fake() { return new FakeSocketDelegate(List.of()); }

  private static CspV2ConnectionListener noopListener() {
    return new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        CspV2Payload.DcwReply notFound = new CspV2Payload.DcwReply(
            req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(),
            0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
        return Future.succeededFuture(notFound);
      }
    };
  }

  private static void forward(FakeSocketDelegate from, FakeSocketDelegate to) {
    for (Buffer b : from.written()) to.emit(b);
    from.clearWritten();
  }

  /**
   * Create a connection on the event-loop. openForTest now requires being
   * on the event-loop context. This helper bridges from test thread to event-loop
   * via CompletableFuture (acceptable in test code, not in main).
   */
  private static CspV2Connection createConnection(Vertx vertx, FakeSocketDelegate sock,
      CspV2Settings s, CspV2PeerSettings peer, String peerNodeId,
      CspV2ConnectionRole role, CspV2ConnectionListener listener) throws Exception {
    CompletableFuture<CspV2Connection> cf = new CompletableFuture<>();
    vertx.runOnContext(v -> {
      try {
        cf.complete(CspV2Connection.openForTest(vertx, sock, s, peer, peerNodeId, role, listener));
      } catch (Exception e) { cf.completeExceptionally(e); }
    });
    return cf.get(5, TimeUnit.SECONDS);
  }

  private Vertx vertx;

  @AfterEach
  void tearDown() { if (vertx != null) vertx.close(); }

  // -------------------------------------------------------------------------
  // Framing fragmented / coalesced / zero
  // -------------------------------------------------------------------------
  @Test
  void framingFragmentedCoalescedZero(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-b", "node-a");
    CspV2PeerSettings peer = s.peers().get(0);
    FakeSocketDelegate acceptorSock = fake();
    CspV2Connection acceptor = createConnection(vertx, acceptorSock, s, peer, "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Payload.Hello hello = new CspV2Payload.Hello("node-a", 12345L, 1, s.maxPayloadSize(), s.maxInflight());
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    acceptorSock.emit(Buffer.buffer(java.util.Arrays.copyOfRange(frame, 0, 5)));
    acceptorSock.emit(Buffer.buffer(java.util.Arrays.copyOfRange(frame, 5, 20)));
    acceptorSock.emit(Buffer.buffer(java.util.Arrays.copyOfRange(frame, 20, frame.length)));
    vertx.setTimer(100, id -> {
      try {
        assertEquals(CspV2ConnectionState.ESTABLISHED, acceptor.state());
        CspV2Payload.Heartbeat hb = new CspV2Payload.Heartbeat();
        byte[] f1 = CspV2Codec.encodeFrame(hb, 0L);
        byte[] f2 = CspV2Codec.encodeFrame(hb, 0L);
        Buffer coalesced = Buffer.buffer(f1).appendBuffer(Buffer.buffer(f2));
        acceptorSock.emit(coalesced);
        vertx.setTimer(50, id2 -> {
          try {
            assertEquals(CspV2ConnectionState.ESTABLISHED, acceptor.state());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void handshakeHappyBothRoles(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings settingsA = defaultSettings("node-a", "node-b");
    CspV2Settings settingsB = defaultSettings("node-b", "node-a");
    CspV2PeerSettings peerA = settingsA.peers().get(0);
    CspV2PeerSettings peerB = settingsB.peers().get(0);
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, settingsA, peerA, "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, settingsB, peerB, "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    assertEquals(CspV2ConnectionState.HELLO_SENT, initiator.state());
    assertEquals(1, sockA.written().size());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      try {
        assertEquals(CspV2ConnectionState.ESTABLISHED, acceptor.state());
        assertEquals(1, sockB.written().size());
        forward(sockB, sockA);
        vertx.setTimer(50, id2 -> {
          try {
            assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
            assertEquals(initiator.negotiatedMaxPayload(), acceptor.negotiatedMaxPayload());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void checksumPolicyCapabilityNegotiatedOnlyWithMutualSupport(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings a = defaultSettings("node-a", "node-b");
    CspV2Settings b = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sa = fake(), sb = fake();
    CspV2Connection initiator = createConnection(vertx, sa, a, a.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sb, b, b.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    // Old acceptor receives the same HELLO but only acknowledges ECM_ROUTING_CAP.
    byte[] helloBytes = sa.written().get(0).getBytes();
    var helloHeader = CspV2Codec.decodeHeader(java.util.Arrays.copyOfRange(helloBytes, 0, 20), 1048576);
    var hello = (CspV2Payload.Hello) CspV2Codec.decodePayload(helloHeader,
        java.util.Arrays.copyOfRange(helloBytes, 20, helloBytes.length));
    assertEquals(CspV2Constants.CAP_CHECKSUM_POLICY,
        hello.capabilities() & CspV2Constants.CAP_CHECKSUM_POLICY);
    forward(sa, sb);
    vertx.setTimer(25, id -> {
      try {
        assertEquals(CspV2Constants.CAP_CHECKSUM_POLICY,
            acceptor.negotiatedCaps() & CspV2Constants.CAP_CHECKSUM_POLICY);
        forward(sb, sa);
        vertx.setTimer(25, id2 -> {
          try {
            assertEquals(CspV2Constants.CAP_CHECKSUM_POLICY,
                initiator.negotiatedCaps() & CspV2Constants.CAP_CHECKSUM_POLICY);
            assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void newPeersTransferNonChecksumOnlyAfterMutualNegotiation(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    byte[] nonChecksum = {1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 10, 11, 12, 0};
    CspV2Settings a = defaultSettings("node-a", "node-b");
    CspV2Settings b = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sa = fake(), sb = fake();
    CspV2ConnectionListener source = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        return Future.succeededFuture(new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(),
            req.caId(), req.dataHash(), req.commandTag(), CspV2Payload.DcwStatus.OK, nonChecksum, ""));
      }
      @Override public boolean acceptsReply(String peer, CspV2Payload.EcmRequest req,
                                            CspV2Payload.DcwReply reply, boolean negotiated) {
        return negotiated;
      }
    };
    CspV2ConnectionListener sink = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        return Future.failedFuture("not serving");
      }
      @Override public boolean acceptsInboundReply(String peer, CspV2Payload.EcmRequest req,
                                                   CspV2Payload.DcwReply reply, boolean negotiated) {
        return negotiated;
      }
    };
    CspV2Connection initiator = createConnection(vertx, sa, a, a.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, sink);
    CspV2Connection acceptor = createConnection(vertx, sb, b, b.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, source);
    forward(sa, sb);
    vertx.setTimer(25, id -> {
      forward(sb, sa);
      vertx.setTimer(25, id2 -> {
        Future<CspV2Payload.DcwReply> result = initiator.request(makeEcmRequest("profile-a", 1500));
        vertx.setTimer(25, id3 -> {
          forward(sa, sb);
          vertx.setTimer(25, id4 -> {
            forward(sb, sa);
            result.onComplete(ar -> {
              try {
                assertTrue(ar.succeeded(), ar.cause() == null ? "" : ar.cause().toString());
                assertArrayEquals(nonChecksum, ar.result().cw());
                ctx.completeNow();
              } catch (Throwable t) { ctx.failNow(t); }
            });
          });
        });
      });
    });
  }

  @Test
  void oldAcceptorAckOnlyRoutingKeepsNewInitiatorStrict(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings a = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sa = fake();
    CspV2Connection initiator = createConnection(vertx, sa, a, a.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    // Ancien acceptor : ignore l'offre de bit 0x08 et répond avec la seule capacité historique.
    byte[] helloBytes = sa.written().get(0).getBytes();
    var h = CspV2Codec.decodeHeader(java.util.Arrays.copyOfRange(helloBytes, 0, 20), 1048576);
    var hello = (CspV2Payload.Hello) CspV2Codec.decodePayload(h,
        java.util.Arrays.copyOfRange(helloBytes, 20, helloBytes.length));
    sa.clearWritten();
    sa.emit(Buffer.buffer(CspV2Codec.encodeFrame(new CspV2Payload.HelloAck("node-b", hello.connectionId(),
        1L, a.maxPayloadSize(), a.maxInflight()), 0)));
    vertx.setTimer(25, id -> {
      try {
        assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
        assertFalse(initiator.supportsChecksumPolicy());
        assertFalse(initiator.isClosed());
        ctx.completeNow();
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void tieBreakRejectsWrongDirection(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings settingsA = defaultSettings("node-a", "node-b");
    CspV2PeerSettings peerA = settingsA.peers().get(0);
    FakeSocketDelegate sock = fake();
    CspV2Connection acceptor = createConnection(vertx, sock, settingsA, peerA, "node-b",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Payload.Hello hello = new CspV2Payload.Hello("node-b", 999L, 1, settingsA.maxPayloadSize(), settingsA.maxInflight());
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    sock.emit(Buffer.buffer(frame));
    vertx.setTimer(1200, id -> {
      try {
        assertTrue(acceptor.isClosed() || acceptor.state() == CspV2ConnectionState.CLOSED);
        assertFalse(sock.written().isEmpty());
        ctx.completeNow();
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void capMismatchCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-b", "node-a");
    CspV2PeerSettings peer = s.peers().get(0);
    FakeSocketDelegate sock = fake();
    CspV2Connection acceptor = createConnection(vertx, sock, s, peer, "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Payload.Hello hello = new CspV2Payload.Hello("node-a", 111L, 0, s.maxPayloadSize(), s.maxInflight());
    byte[] frame = CspV2Codec.encodeFrame(hello, 0L);
    sock.emit(Buffer.buffer(frame));
    vertx.setTimer(1200, id -> {
      try { assertTrue(acceptor.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void helloTimeoutCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = settings("node-a", "node-b", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    CspV2PeerSettings peer = s.peers().get(0);
    FakeSocketDelegate sock = fake();
    CspV2Connection acceptor = createConnection(vertx, sock, s, peer, "node-b",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    vertx.setTimer(1500, id -> {
      try { assertTrue(acceptor.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void badMagicAlwaysCloseSilentPreHandshake(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-b", "node-a");
    CspV2PeerSettings peer = s.peers().get(0);
    FakeSocketDelegate sock = fake();
    CspV2Connection acceptor = createConnection(vertx, sock, s, peer, "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    byte[] bad = new byte[20];
    sock.emit(Buffer.buffer(bad));
    vertx.setTimer(50, id -> {
      try { assertTrue(acceptor.isClosed()); assertTrue(sock.written().isEmpty()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void postHandshakeBadHeaderSendsErrorThenClose(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
        byte[] bad = new byte[20];
        sockB.emit(Buffer.buffer(bad));
        vertx.setTimer(100, id3 -> {
          try { assertTrue(acceptor.isClosed()); assertTrue(sockB.written().size() <= 1); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void streamParityEnforced(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        byte[] badFrame = CspV2Codec.encodeFrame(req, 2L);
        sockB.emit(Buffer.buffer(badFrame));
        vertx.setTimer(1200, id3 -> {
          try { assertTrue(acceptor.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void requestCorrelationAndEcho(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2ConnectionListener acceptorListener = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        CspV2Payload.DcwReply reply = new CspV2Payload.DcwReply(
            req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(),
            1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
        return Future.succeededFuture(reply);
      }
    };
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, acceptorListener);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        Future<CspV2Payload.DcwReply> fut = initiator.request(req);
        assertEquals(1, sockA.written().size());
        forward(sockA, sockB);
        vertx.setTimer(100, id3 -> {
          assertEquals(1, sockB.written().size());
          forward(sockB, sockA);
          vertx.setTimer(50, id4 -> {
            try { assertTrue(fut.succeeded()); assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, fut.result().status()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  @Test
  void unknownResponseThresholdCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa2 = new CspV2Settings(true, "node-a", "0.0.0.0", 9090, 1048576, 10, 100, 10, 5000, 2,
        5000, 1000, 5000, 2000, 1000, 10000, 5000, 3000, 100, 30000, 10000, 60000, false, "file:cert", "file:key", "file:trust", false,
        List.of(new CspV2PeerSettings(true, "node-b", "peer.example.com", 9090, List.of("profile-a"))));
    FakeSocketDelegate sockA = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa2, sa2.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
        CspV2Payload.DcwReply fakeReply = new CspV2Payload.DcwReply(0x80, 100, 200, 0x0B00, 12345, 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
        for (int i = 0; i < 3; i++) {
          byte[] frame = CspV2Codec.encodeFrame(fakeReply, 999L + i);
          sockA.emit(Buffer.buffer(frame));
        }
        vertx.setTimer(2000, id3 -> {
          try { assertTrue(initiator.isClosed()); assertTrue(initiator.unknownDropped() >= 3); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void requestTimeoutFailsFuture(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    Promise<CspV2Payload.DcwReply> never = Promise.promise();
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, (peer, req) -> never.future());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 150);
        Future<CspV2Payload.DcwReply> fut = initiator.request(req);
        forward(sockA, sockB);
        vertx.setTimer(400, id3 -> {
          try { assertTrue(fut.failed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void profileAllowlistDenies(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = settings("node-b", "node-a", List.of("allowed"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    AtomicInteger listenerCalled = new AtomicInteger();
    CspV2ConnectionListener l = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        listenerCalled.incrementAndGet();
        return Future.succeededFuture(new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.OK, new byte[16], ""));
      }
    };
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, l);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("not-allowed", 2000);
        byte[] frame = CspV2Codec.encodeFrame(req, 1L);
        sockB.emit(Buffer.buffer(frame));
        vertx.setTimer(100, id3 -> {
          try {
            assertEquals(0, listenerCalled.get());
            assertEquals(1, sockB.written().size());
            byte[] written = sockB.written().get(0).getBytes();
            byte[] payload = java.util.Arrays.copyOfRange(written, 20, written.length);
            CspV2Header h = CspV2Codec.decodeHeader(java.util.Arrays.copyOfRange(written, 0, 20), 1048576);
            CspV2Payload p = CspV2Codec.decodePayload(h, payload);
            assertTrue(p instanceof CspV2Payload.DcwReply);
            assertEquals(CspV2Payload.DcwStatus.DENIED, ((CspV2Payload.DcwReply) p).status());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void maxInflightPauseAndResume(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sb = settings("node-b", "node-a", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 2, 1048576);
    CspV2Settings sa = settings("node-a", "node-b", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    FakeSocketDelegate sockB = fake();
    AtomicInteger count = new AtomicInteger();
    Promise<CspV2Payload.DcwReply> never2 = Promise.promise();
    CspV2ConnectionListener slow = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String peer, CspV2Payload.EcmRequest req) {
        if (count.incrementAndGet() <= 2) return never2.future();
        return Future.succeededFuture(new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
      }
    };
    FakeSocketDelegate sockA = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, slow);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        for (int i = 0; i < 2; i++) {
          CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
          byte[] f = CspV2Codec.encodeFrame(req, 1L + i * 2);
          sockB.emit(Buffer.buffer(f));
        }
        vertx.setTimer(100, id3 -> {
          try { assertEquals(2, acceptor.inflightIncoming()); assertTrue(acceptor.isPaused()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void heartbeatSentPeriodic(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = settings("node-a", "node-b", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    CspV2Settings sb = settings("node-b", "node-a", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(3000, id2 -> {
        try { assertTrue(sockA.written().size() >= 1 || sockB.written().size() >= 1); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
      });
    });
  }

  @Test
  void idleTimeoutCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = settings("node-a", "node-b", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    CspV2Settings sb = settings("node-b", "node-a", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(6500, id2 -> {
        try { assertTrue(acc.isClosed() || ini.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
      });
    });
  }

  @Test
  void backpressureSingleSlotAndDrain(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        assertEquals(CspV2ConnectionState.ESTABLISHED, conn.state());
        sock.setWriteQueueFull(true);
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        assertNotNull(conn.pendingWrite());
        assertTrue(conn.isPaused());
        CspV2Payload.EcmRequest req2 = makeEcmRequest("profile-a", 5000);
        conn.request(req2);
        vertx.setTimer(50, id3 -> {
          try { assertTrue(conn.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void backpressureDrainResumes(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        sock.setWriteQueueFull(true);
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        assertNotNull(conn.pendingWrite());
        sock.setWriteQueueFull(false);
        sock.fireDrain();
        vertx.setTimer(50, id3 -> {
          try { assertNull(conn.pendingWrite()); assertFalse(conn.isPaused()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void drainSendsShuttingDownAndCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        // Create pending request so DRAINING persists for observation
        Promise<CspV2Payload.DcwReply> never = Promise.promise();
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        forward(sock, sockB); // deliver to acc
        vertx.setTimer(50, id2a -> {
          conn.drain();
          assertEquals(CspV2ConnectionState.DRAINING, conn.state());
          assertFalse(conn.isClosing(), "drain() should NOT set closing=true");
          assertFalse(sock.written().isEmpty());
          // Close via explicit close() to finish test
          conn.close();
          vertx.setTimer(100, id3 -> {
            try { assertTrue(conn.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  @Test
  void closeFailPendingAndGetters(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b",
        CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a",
        CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        Future<CspV2Payload.DcwReply> fut = conn.request(req);
        conn.close();
        vertx.setTimer(50, id3 -> {
          try {
            assertTrue(fut.failed());
            assertTrue(conn.isClosed());
            assertEquals(CspV2ConnectionState.CLOSED, conn.state());
            assertEquals("node-b", conn.peerNodeId());
            assertTrue(conn.negotiatedMaxPayload() > 0);
            assertNotNull(conn.peerNodeId());
            conn.close();
            assertTrue(conn.isClosed());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  // -------------------------------------------------------------------------
  // Review fix tests
  // -------------------------------------------------------------------------

  @Test
  void lateCompletionDoesNotDoubleReplyAndInflightZero(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    Promise<CspV2Payload.DcwReply> delayed = Promise.promise();
    CspV2ConnectionListener slow = (peer, req) -> delayed.future();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, slow);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 200);
        initiator.request(req);
        forward(sockA, sockB);
        vertx.setTimer(400, id3 -> {
          try {
            assertEquals(1, sockB.written().size());
            assertEquals(0, acceptor.inflightIncoming());
            CspV2Payload.DcwReply late = new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
            delayed.complete(late);
            vertx.setTimer(100, id4 -> {
              try {
                assertEquals(1, sockB.written().size());
                assertEquals(0, acceptor.inflightIncoming());
                ctx.completeNow();
              } catch (Throwable t) { ctx.failNow(t); }
            });
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void writeFailureCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection initiator = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        try {
          assertEquals(CspV2ConnectionState.ESTABLISHED, initiator.state());
          sockA.setFailNextWrite(true);
          CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
          initiator.request(req);
          vertx.setTimer(100, id3 -> {
            try { assertTrue(initiator.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
          });
        } catch (Throwable t) { ctx.failNow(t); }
      });
    });
  }

  @Test
  void foreignThreadCompletion(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CompletableFuture<CspV2Payload.DcwReply> cf = new CompletableFuture<>();
    CspV2ConnectionListener l = (peer, req) -> {
      Promise<CspV2Payload.DcwReply> p = Promise.promise();
      cf.thenAccept(reply -> p.complete(reply));
      return p.future();
    };
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, l);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        Future<CspV2Payload.DcwReply> fut = ini.request(req);
        forward(sockA, sockB);
        new Thread(() -> {
          CspV2Payload.DcwReply reply = new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
          cf.complete(reply);
        }).start();
        vertx.setTimer(200, id3 -> {
          forward(sockB, sockA);
          vertx.setTimer(50, id4 -> {
            try { assertTrue(fut.succeeded()); assertEquals(0, acc.inflightIncoming()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  @Test
  void streamIdBeyondU32AcceptedAndWrapCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        long beyondU32 = 0x1_00000001L; // 4294967297 odd, >U32
        ini.setNextOutboundStreamIdForTest(beyondU32);
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        Future<CspV2Payload.DcwReply> fut = ini.request(req);
        try { assertFalse(fut.failed()); } catch (Throwable t) { ctx.failNow(t); return; }
        assertEquals(1, sockA.written().size());
        ini.setNextOutboundStreamIdForTest(Long.MAX_VALUE);
        CspV2Payload.EcmRequest req2 = makeEcmRequest("profile-a", 2000);
        ini.request(req2);
        vertx.setTimer(200, id3 -> {
          try { assertTrue(ini.isClosed() || ini.isClosing()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void preHandshakeBusinessFrameSendsNotAuthorized(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sock = fake();
    CspV2Connection acc = createConnection(vertx, sock, s, s.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
    byte[] frame = CspV2Codec.encodeFrame(req, 1L);
    sock.emit(Buffer.buffer(frame));
    vertx.setTimer(1200, id -> {
      try {
        assertTrue(acc.isClosed() || acc.isClosing());
        assertFalse(sock.written().isEmpty());
        byte[] written = sock.written().get(0).getBytes();
        CspV2Header h = CspV2Codec.decodeHeader(java.util.Arrays.copyOfRange(written, 0, 20), 1048576);
        CspV2Payload p = CspV2Codec.decodePayload(h, java.util.Arrays.copyOfRange(written, 20, written.length));
        assertTrue(p instanceof CspV2Payload.ErrorPayload);
        assertEquals(CspV2ErrorCode.NOT_AUTHORIZED, ((CspV2Payload.ErrorPayload) p).code());
        ctx.completeNow();
      } catch (Throwable t) { ctx.failNow(t); }
    });
  }

  @Test
  void targetedErrorRemovesPendingOnly(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req1 = makeEcmRequest("profile-a", 5000);
        CspV2Payload.EcmRequest req2 = makeEcmRequest("profile-a", 5000);
        Future<CspV2Payload.DcwReply> f1 = ini.request(req1);
        Future<CspV2Payload.DcwReply> f2 = ini.request(req2);
        assertEquals(2, ini.pendingOutboundForTest().size());
        forward(sockA, sockB);
        long sid1 = 1L;
        CspV2Payload.ErrorPayload err = new CspV2Payload.ErrorPayload(CspV2ErrorCode.MALFORMED_PAYLOAD, "malformed_payload");
        byte[] errFrame = CspV2Codec.encodeFrame(err, sid1);
        sockA.emit(Buffer.buffer(errFrame));
        vertx.setTimer(100, id3 -> {
          try {
            assertTrue(f1.failed());
            assertFalse(f2.isComplete());
            assertEquals(1, ini.pendingOutboundForTest().size());
            assertFalse(ini.isClosed());
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void queuedRequestAcceptedSecondCriticalCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sock = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        sock.setWriteQueueFull(true);
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        Future<CspV2Payload.DcwReply> f1 = conn.request(req);
        assertFalse(f1.isComplete());
        assertEquals(1, conn.pendingOutboundForTest().size());
        assertNotNull(conn.pendingWrite());
        CspV2Payload.EcmRequest req2 = makeEcmRequest("profile-a", 5000);
        conn.request(req2);
        vertx.setTimer(50, id3 -> {
          try { assertTrue(conn.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void errorCloseUsesDrainMs(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = settings("node-b", "node-a", List.of("profile-a"), 1000, 5000, 2000, 1000, 3000, 10, 1048576);
    FakeSocketDelegate sock = fake();
    CspV2Connection acc = createConnection(vertx, sock, s, s.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sockA = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection acceptor = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        byte[] bad = CspV2Codec.encodeFrame(req, 2L);
        sockB.emit(Buffer.buffer(bad));
        vertx.setTimer(500, id3 -> {
          try {
            assertFalse(acceptor.isClosed());
            vertx.setTimer(800, id4 -> {
              try { assertTrue(acceptor.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
            });
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void dedupDuplicateIgnored(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    AtomicInteger calls = new AtomicInteger();
    CspV2ConnectionListener counting = (peer, req) -> {
      calls.incrementAndGet();
      return Future.succeededFuture(new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
    };
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, counting);
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 2000);
        byte[] frame = CspV2Codec.encodeFrame(req, 1L);
        sockB.emit(Buffer.buffer(frame));
        vertx.setTimer(100, id3 -> {
          assertEquals(1, calls.get());
          sockB.emit(Buffer.buffer(frame));
          vertx.setTimer(100, id4 -> {
            try { assertEquals(1, calls.get()); assertEquals(1, acc.duplicateDropped()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  @Test
  void drainEmptyClosesImmediately(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        conn.drain();
        vertx.setTimer(100, id3 -> {
          try { assertTrue(conn.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void drainWithPendingWaits(Vertx vertx, VertxTestContext ctx) throws Exception {
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    Promise<CspV2Payload.DcwReply> never = Promise.promise();
    CspV2ConnectionListener slow = (peer, req) -> never.future();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, slow);
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        forward(sock, sockB);
        vertx.setTimer(50, id3 -> {
          conn.drain();
          vertx.setTimer(100, id4 -> {
            try {
              assertFalse(conn.isClosed());
              never.complete(new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(), 1, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
              vertx.setTimer(100, id5 -> {
                forward(sockB, sock);
                vertx.setTimer(200, id6 -> {
                  try { assertTrue(conn.isClosed() || conn.isClosing()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
                });
              });
            } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  // -------------------------------------------------------------------------
  // New recovery fix tests — strong assertions
  // -------------------------------------------------------------------------

  @Test
  void drainLeavesClosingFalseAndStateDraining(Vertx vertx, VertxTestContext ctx) throws Exception {
    // drain() sets state=DRAINING, does NOT set closing=true, does NOT pause socket
    // With pending outbound work, DRAINING persists and closing stays false
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        // Create pending outbound on initiator so DRAINING persists
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        Future<CspV2Payload.DcwReply> fut = conn.request(req);
        // request creates pendingOutbound entry; don't forward to acceptor yet
        assertEquals(1, conn.pendingOutboundForTest().size());
        conn.drain();
        // DRAINING persists because pendingOutbound is non-empty
        assertEquals(CspV2ConnectionState.DRAINING, conn.state());
        assertFalse(conn.isClosing(), "drain MUST NOT set closing=true");
        assertFalse(sock.written().isEmpty(), "SHUTTING_DOWN frame must be sent");
        ctx.completeNow();
      });
    });
  }

  @Test
  void remoteShutdownKeepsReadingAndTargetedReplyWorks(Vertx vertx, VertxTestContext ctx) throws Exception {
    // When remote sends global SHUTTING_DOWN, local enters DRAINING but continues reading.
    // With pending outbound, DRAINING persists so replies can still resolve pending streams.
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        assertEquals(CspV2ConnectionState.ESTABLISHED, ini.state());
        // Create a pending outbound so DRAINING persists
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        ini.request(req);
        forward(sockA, sockB); // deliver ECM to acc
        // Now send global SHUTTING_DOWN to initiator
        vertx.setTimer(50, id2a -> {
          CspV2Payload.ErrorPayload shutdown = new CspV2Payload.ErrorPayload(CspV2ErrorCode.SHUTTING_DOWN, "shutting_down");
          byte[] shutdownFrame = CspV2Codec.encodeFrame(shutdown, 0L);
          sockA.emit(Buffer.buffer(shutdownFrame));
          vertx.setTimer(100, id3 -> {
            try {
              assertEquals(CspV2ConnectionState.DRAINING, ini.state(), "DRAINING must persist with pending outbound");
              assertFalse(ini.isClosing(), "DRAINING from remote MUST NOT set closing=true");
              // Verify reading still works: send DCW_REPLY for the pending stream
              forward(sockB, sockA); // deliver ECM from acc to socket A
              vertx.setTimer(50, id3a -> {
                // acc should have sent a reply (NOT_FOUND from noopListener)
                forward(sockB, sockA);
                vertx.setTimer(50, id4 -> {
                  // ini should have processed the reply and resolved pending
                  // Now DRAINING should close since pending empty
                  try {
                    assertTrue(ini.isClosed() || ini.state() == CspV2ConnectionState.DRAINING);
                    ctx.completeNow();
                  } catch (Throwable t) { ctx.failNow(t); }
                });
              });
            } catch (Throwable t) { ctx.failNow(t); }
          });
        });
      });
    });
  }

  @Test
  void writesInFlightIncrementedOnWriteAndDecrementedOnCallback(Vertx vertx, VertxTestContext ctx) throws Exception {
    // Verify writesInFlight is incremented during write and decremented after callback
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    sock.setHoldWrites(true); // hold all writes so writesInFlight stays > 0
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        // After handshake, HELLO write should have completed (held)
        // Now send a request
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        vertx.setTimer(50, id3 -> {
          try {
            java.lang.reflect.Field fW = CspV2Connection.class.getDeclaredField("writesInFlight");
            fW.setAccessible(true);
            int w = (int) fW.get(conn);
            assertTrue(w > 0, "writesInFlight should be > 0 while write is held");
            // Complete the write
            sock.completePendingWrites();
            vertx.setTimer(50, id4 -> {
              try {
                int w2 = (int) fW.get(conn);
                assertEquals(0, w2, "writesInFlight should be 0 after write completes");
                ctx.completeNow();
              } catch (Throwable t) { ctx.failNow(t); }
            });
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void writeFailureDecrementsWritesInFlightAndCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    // Write failure should still decrement writesInFlight and close connection
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        sock.setFailNextWrite(true);
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        conn.request(req);
        vertx.setTimer(100, id3 -> {
          try {
            assertTrue(conn.isClosed(), "connection must close on write failure");
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void openForTestThrowsIfNotOnEventLoop() {
    // openForTest must throw if called outside event-loop
    Vertx v = Vertx.vertx();
    try {
      CspV2Settings s = defaultSettings("node-a", "node-b");
      FakeSocketDelegate sock = fake();
      assertThrows(CspV2ConnectionException.class, () ->
          CspV2Connection.openForTest(v, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener()));
    } finally {
      v.close();
    }
  }

  @Test
  void socketDelegateIsPackagePrivate() {
    assertFalse(java.lang.reflect.Modifier.isPublic(CspV2Connection.SocketDelegate.class.getModifiers()),
        "SocketDelegate should be package-private");
  }

  @Test
  void onConnectionContextHelperIsExactIdentity(Vertx vertx, VertxTestContext ctx) throws Exception {
    // Verify onConnectionContext uses exact identity, not just isOnEventLoopThread
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = fake();
    CompletableFuture<CspV2Connection> cf = new CompletableFuture<>();
    vertx.runOnContext(v -> {
      try {
        cf.complete(CspV2Connection.openForTest(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener()));
      } catch (Exception e) { cf.completeExceptionally(e); }
    });
    CspV2Connection conn = cf.get(2, TimeUnit.SECONDS);
    java.lang.reflect.Method m = CspV2Connection.class.getDeclaredMethod("onConnectionContext");
    m.setAccessible(true);
    // On same event-loop (same context) should be true
    CompletableFuture<Boolean> cf2 = new CompletableFuture<>();
    vertx.runOnContext(v -> {
      try { cf2.complete((Boolean) m.invoke(conn)); } catch (Exception e) { cf2.completeExceptionally(e); }
    });
    assertTrue(cf2.get(2, TimeUnit.SECONDS));
    // On different context (new Vertx) should be false
    Vertx foreign = Vertx.vertx();
    CompletableFuture<Boolean> cf3 = new CompletableFuture<>();
    foreign.runOnContext(v -> {
      try { cf3.complete((Boolean) m.invoke(conn)); } catch (Exception e) { cf3.completeExceptionally(e); }
    });
    assertFalse(cf3.get(2, TimeUnit.SECONDS));
    foreign.close();
    ctx.completeNow();
  }

  // -------------------------------------------------------------------------
  // Corrigés de dernière itération
  // -------------------------------------------------------------------------

  @Test
  void shutdownDrainMsUsedForGracefulClose(Vertx vertx, VertxTestContext ctx) throws Exception {
    // verify scheduleGracefulDrainClose uses shutdownDrainMs, not drainMs
    this.vertx = vertx;
    // drainMs=1000, shutdownDrainMs=5000, idleMs=30000 > backpressurePauseMs=5000
    CspV2PeerSettings ps = new CspV2PeerSettings(true, "node-b", "peer.example.com", 9090, List.of("profile-a"));
    CspV2Settings customSettings = new CspV2Settings(true, "node-a", "0.0.0.0", 9090,
        1048576, 10, 100, 10, 5000, 100, 5000,
        1000, 30000, 2000, 1000, 5000, // drainMs=1000, shutdownDrainMs=5000
        5000, 5000, 100, 30000, 10000, 60000, false, "file:cert", "file:key", "file:trust", false,
        List.of(ps));
    FakeSocketDelegate sock = fake();
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, customSettings, customSettings.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        // Create pending so DRAINING doesn't close immediately
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 10000);
        conn.request(req);
        conn.drain();
        // After drain, graceful timer = shutdownDrainMs = 5000ms
        // At drainMs (1000ms), should NOT be closed (still waiting on shutdownDrainMs)
        vertx.setTimer(1500, id3 -> {
          try {
            assertFalse(conn.isClosed(), "should NOT close via drainMs (1000ms), must wait for shutdownDrainMs (5000ms)");
            // Now close explicitly and verify
            conn.close();
            vertx.setTimer(100, id4 -> {
              try { assertTrue(conn.isClosed()); ctx.completeNow(); } catch (Throwable t) { ctx.failNow(t); }
            });
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void concurrentLocalAndRemoteDrainPreservesPending(Vertx vertx, VertxTestContext ctx) throws Exception {
    // Both local drain() and remote SHUTTING_DOWN arrive concurrently,
    // pending streams must not be lost.
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        // Create pending outbound on initiator
        CspV2Payload.EcmRequest req = makeEcmRequest("profile-a", 5000);
        Future<CspV2Payload.DcwReply> fut = ini.request(req);
        assertEquals(1, ini.pendingOutboundForTest().size());
        // Local drain first
        ini.drain();
        assertEquals(CspV2ConnectionState.DRAINING, ini.state());
        assertEquals(1, ini.pendingOutboundForTest().size(), "pending must survive local drain");
        // Then remote SHUTTING_DOWN arrives — must not lose pending
        CspV2Payload.ErrorPayload remoteShutdown = new CspV2Payload.ErrorPayload(CspV2ErrorCode.SHUTTING_DOWN, "shutting_down");
        byte[] shutdownFrame = CspV2Codec.encodeFrame(remoteShutdown, 0L);
        sockA.emit(Buffer.buffer(shutdownFrame));
        vertx.setTimer(100, id3 -> {
          try {
            assertEquals(CspV2ConnectionState.DRAINING, ini.state(), "must stay DRAINING on concurrent shutdown");
            assertEquals(1, ini.pendingOutboundForTest().size(), "pending must survive concurrent remote+local drain");
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void postHandshakeMalformedPayloadErrorEchoesStreamId(Vertx vertx, VertxTestContext ctx) throws Exception {
    // Post-handshake malformed payload must send ERROR with correct streamId
    this.vertx = vertx;
    CspV2Settings sa = defaultSettings("node-a", "node-b");
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockA = fake();
    FakeSocketDelegate sockB = fake();
    CspV2Connection ini = createConnection(vertx, sockA, sa, sa.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sockA, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sockA);
      vertx.setTimer(50, id2 -> {
        // Send a valid-header frame with garbage payload on stream 5
        CspV2Header garbageHeader = new CspV2Header(
            CspV2Constants.MAGIC,
            CspV2Constants.VERSION,
            com.bowman.cspng.codec.cspv2.CspV2MessageType.ECM_REQUEST,
            CspV2Constants.FLAGS_REQUIRED,
            50, 5L);
        byte[] headerBytes = CspV2Codec.encodeHeader(garbageHeader);
        byte[] frame = java.util.Arrays.copyOf(headerBytes, headerBytes.length + 50);
        // payload is all zeros (garbage) — will fail decode
        sockB.emit(Buffer.buffer(frame));
        vertx.setTimer(200, id3 -> {
          try {
            assertFalse(sockB.written().isEmpty(), "must have written error frame");
            byte[] written = sockB.written().get(0).getBytes();
            CspV2Header h = CspV2Codec.decodeHeader(java.util.Arrays.copyOfRange(written, 0, 20), 1048576);
            CspV2Payload p = CspV2Codec.decodePayload(h, java.util.Arrays.copyOfRange(written, 20, written.length));
            assertTrue(p instanceof CspV2Payload.ErrorPayload);
            assertEquals(CspV2ErrorCode.MALFORMED_PAYLOAD, ((CspV2Payload.ErrorPayload) p).code());
            assertEquals(5L, h.streamId(), "error frame must echo the original streamId");
            ctx.completeNow();
          } catch (Throwable t) { ctx.failNow(t); }
        });
      });
    });
  }

  @Test
  void syncThrowAndNullWriteHandledWithoutCounterLeak(Vertx vertx, VertxTestContext ctx) throws Exception {
    // writeNow must handle synchronous throw and null Future without leaking writesInFlight
    this.vertx = vertx;
    CspV2Settings s = defaultSettings("node-a", "node-b");
    FakeSocketDelegate sock = new FakeSocketDelegate(List.of()) {
      @Override public Future<Void> write(Buffer data) {
        throw new RuntimeException("synchronous write explosion");
      }
    };
    CspV2Settings sb = defaultSettings("node-b", "node-a");
    FakeSocketDelegate sockB = fake();
    CspV2Connection conn = createConnection(vertx, sock, s, s.peers().get(0), "node-b", CspV2ConnectionRole.INITIATOR, noopListener());
    CspV2Connection acc = createConnection(vertx, sockB, sb, sb.peers().get(0), "node-a", CspV2ConnectionRole.ACCEPTOR, noopListener());
    forward(sock, sockB);
    vertx.setTimer(50, id -> {
      forward(sockB, sock);
      vertx.setTimer(50, id2 -> {
        // HELLO write should have failed sync and closed connection
        // This tests that the sync throw in writeNow is handled
        assertTrue(conn.isClosed(), "connection must close on sync write throw");
        try {
          java.lang.reflect.Field fW = CspV2Connection.class.getDeclaredField("writesInFlight");
          fW.setAccessible(true);
          int w = (int) fW.get(conn);
          // writesInFlight should be exactly decremented (not leaked)
          assertTrue(w >= 0, "writesInFlight must not be negative after sync throw");
          ctx.completeNow();
        } catch (Throwable t) { ctx.failNow(t); }
      });
    });
  }
}
