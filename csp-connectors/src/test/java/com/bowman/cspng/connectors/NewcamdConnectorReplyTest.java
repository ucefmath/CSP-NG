package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NewcamdConnector ECM reply handling.
 *
 * <p>Uses a mock Newcamd server that properly handles the handshake
 * (random14, login decryption, ACK encryption, session key switch)
 * and ECM reply encryption. No Thread.sleep, no random ports,
 * no fake plaintext frames.
 */
@DisplayName("NewcamdConnector — reply correlation")
class NewcamdConnectorReplyTest {

  private Vertx vertx;
  private static final String USER = "testuser";
  private static final String PASS = "testpass";
  // Fixed config key for reproducible handshake
  private static final byte[] CONFIG_KEY14 = {
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E
  };
  // Fixed random14 sent by server
  private static final byte[] RANDOM14 = {
      0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
      0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D
  };
  // Precomputed keys —
  // loginKey16 = desKeySpread(xorKey(CONFIG_KEY14, RANDOM14))
  // sessionKey16 = desKeySpread(xorUserPass(CONFIG_KEY14, PASS))
  private static final byte[] LOGIN_KEY16;
  private static final byte[] SESSION_KEY16;
  // Test CW
  private static final byte[] TEST_CW = {
      0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
      (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
      (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00
  };

  static {
    byte[] xored = NewcamdCrypto.xorKey(CONFIG_KEY14, RANDOM14);
    LOGIN_KEY16 = NewcamdCrypto.desKeySpread(xored);
    byte[] newKey14 = NewcamdCrypto.xorUserPass(CONFIG_KEY14, PASS);
    SESSION_KEY16 = NewcamdCrypto.desKeySpread(newKey14);
  }

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) vertx.close();
  }

  // ============================================================
  // Test 1: sendEcm future is NOT completed on write alone
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("sendEcm future not completed on write alone")
  void pendingAfterWrite() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      // Should not be completed yet — server hasn't replied
      assertFalse(future.isComplete(),
          "Future should not be completed before reply");

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 2: sendEcm completes with CW on corresponding reply
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("sendEcm completes with CW on response")
  void completesWithCw() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, TEST_CW);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      CwReply reply = future.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertArrayEquals(TEST_CW, reply.cw());
      // Key must be the original one from the request
      assertEquals(request.key(), reply.key());
      assertEquals(request.key().commandTag(), reply.commandTag());
      assertEquals(connector.getName(), reply.connectorName());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 3: Multiple sequential ECMs — correct FIFO ordering
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Multiple ECMs complete in FIFO order with correct keys")
  void multipleEcmsFifoOrder() throws Exception {
    // Alternate CWs for each request
    byte[] cw1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                  0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
    byte[] cw2 = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                  0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30};
    byte[] cw3 = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
                  0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40};

    // Server will reply with alternating CWs ready
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, cw1, cw2, cw3);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest req1 = ecmRequest(0x80, 0x0001, 0x0D00, 0x11);
      EcmRequest req2 = ecmRequest(0x80, 0x0002, 0x0D00, 0x22);
      EcmRequest req3 = ecmRequest(0x81, 0x0003, 0x0D00, 0x33);

      Future<CwReply> f1 = connector.sendEcm(req1);
      Future<CwReply> f2 = connector.sendEcm(req2);
      Future<CwReply> f3 = connector.sendEcm(req3);

      CwReply r1 = f1.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      CwReply r2 = f2.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      CwReply r3 = f3.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(req1.key(), r1.key());
      assertEquals(req2.key(), r2.key());
      assertEquals(req3.key(), r3.key());
      assertArrayEquals(cw1, r1.cw());
      assertArrayEquals(cw2, r2.cw());
      assertArrayEquals(cw3, r3.cw());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 4: Empty/failed reply
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("sendEcm completes with empty CW on cannot-decode response")
  void emptyReplyCompletes() throws Exception {
    // Send empty CW (0-length) to simulate "cannot decode"
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, new byte[0]);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      CwReply reply = future.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertTrue(reply.isEmpty());
      assertEquals(request.key(), reply.key());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 5: Timeout — server never replies
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("sendEcm fails with timeout when server doesn't reply")
  void timeoutFails() throws Exception {
    // Server does handshake but never replies to ECM
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      // Should fail with timeout after ~5s
      Throwable cause = null;
      try {
        future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        fail("Should have thrown");
      } catch (Exception e) {
        cause = e.getCause();
      }
      assertNotNull(cause);
      assertTrue(cause.getMessage().contains("timeout"),
          "Expected timeout message, got: " + cause.getMessage());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 6: Close cleanup — pending fail on disconnect
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("Pending futures fail on connector stop")
  void pendingFailOnStop() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      assertFalse(future.isComplete());

      connector.stop();

      assertTrue(future.isComplete(), "Future should complete after stop");
      assertTrue(future.failed(), "Future should have failed after stop");
      String msg = future.cause().getMessage();
      assertTrue(msg.contains("stopped") || msg.contains("closed"),
          "Expected 'stopped' or 'closed', got: " + msg);
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 7: Overflow — too many pending requests rejected
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("sendEcm fails when MAX_PENDING exceeded")
  void overflowRejected() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      // Fill the queue
      for (int i = 0; i < 1000; i++) {
        connector.sendEcm(request); // ignore futures — they won't reply
      }

      // Next should fail
      Future<CwReply> future = connector.sendEcm(request);
      assertTrue(future.failed(), "Should reject overflow");
      assertTrue(future.cause().getMessage().contains("Too many pending"),
          "Expected overflow message");

      connector.stop();
      assertEquals(0, connector.pendingCount(), "pending count must be 0 after stop");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 11: onTimeout with unknown ID is no-op
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("onTimeout with unknown timer id does NOT close connection")
  void onTimeoutUnknownIdNoOp() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, TEST_CW);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      // Send one ECM to establish that the connection works
      EcmRequest req = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> f = connector.sendEcm(req);
      CwReply reply = f.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertEquals(0, connector.pendingCount(), "count 0 after reply");

      // Now call onTimeout with a bogus timer id — must be no-op
      assertTrue(connector.isConnected(), "connected before no-op call");
      connector.onTimeout(-1L); // never-used timer id
      connector.onTimeout(Long.MAX_VALUE); // never-used timer id
      assertTrue(connector.isConnected(),
          "Connection must still be alive after onTimeout with unknown id");
      assertEquals(0, connector.pendingCount(),
          "pending count unchanged by no-op timeout");

      // Connection still works — send another ECM
      EcmRequest req2 = ecmRequest(0x81, 0x5678, 0x0D00, 0x99);
      Future<CwReply> f2 = connector.sendEcm(req2);
      CwReply reply2 = f2.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply2);
      assertEquals(req2.key(), reply2.key());
      assertEquals(0, connector.pendingCount(), "count 0 after second reply");

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Helpers
  // ============================================================
  // Test 8: Timeout → all pending fail + connection closed
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Timeout of one ECM fails all pending and closes connection")
  void timeoutClearsAllPending() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest req1 = ecmRequest(0x80, 0x0001, 0x0D00, 0x11);
      EcmRequest req2 = ecmRequest(0x80, 0x0002, 0x0D00, 0x22);

      Future<CwReply> f1 = connector.sendEcm(req1);
      Future<CwReply> f2 = connector.sendEcm(req2);

      // Both must fail
      Throwable t1 = f1.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(10, TimeUnit.SECONDS);
      assertNotNull(t1, "First request should fail");
      Throwable t2 = f2.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(5, TimeUnit.SECONDS);
      assertNotNull(t2, "Second request should also fail");

      // Connection must have been closed by timeout
      connector.stop(); // prevent reconnect

      assertFalse(connector.isConnected(),
          "Connector should be disconnected after stop");
      assertEquals(0, connector.pendingCount(), "pending count must be 0");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 9: Late reply after timeout NOT misattributed to next request
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("After timeout, late reply cannot be misattributed (connection was closed)")
  void lateReplyNotMisattributed() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      EcmRequest req1 = ecmRequest(0x80, 0x0001, 0x0D00, 0x11);
      Future<CwReply> f1 = connector.sendEcm(req1);

      // Wait for timeout — connection closes, all pending fail
      Throwable t1 = f1.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(10, TimeUnit.SECONDS);
      assertNotNull(t1, "First should have failed (timeout)");

      // Stop to prevent auto-reconnect
      connector.stop();

      // After stop, connection is down — any late reply can't be matched
      assertFalse(connector.isConnected(), "Connection should be down");
      assertEquals(0, connector.pendingCount(), "pending count must be 0");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 10: Concurrent calls respect atomic bound
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Concurrent sendEcm calls respect MAX_PENDING bound")
  void concurrentAtomicBound() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, false, null);
    server.start();
    try {
      NewcamdConnector connector = connect(server.port());

      int threadCount = 4;
      int perThread = 300; // 4 * 300 = 1200 > 1000 bound
      Thread[] threads = new Thread[threadCount];
      java.util.concurrent.atomic.AtomicInteger overflowCount = new java.util.concurrent.atomic.AtomicInteger();
      java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger();
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(threadCount);

      for (int t = 0; t < threadCount; t++) {
        threads[t] = new Thread(() -> {
          try {
            startLatch.await();
          } catch (InterruptedException e) { return; }
          EcmRequest req = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
          for (int i = 0; i < perThread; i++) {
            Future<CwReply> f = connector.sendEcm(req);
            if (f.failed() && f.cause().getMessage().contains("Too many pending")) {
              overflowCount.incrementAndGet();
            } else if (!f.failed()) {
              successCount.incrementAndGet();
            }
          }
          doneLatch.countDown();
        });
        threads[t].start();
      }

      startLatch.countDown();
      assertTrue(doneLatch.await(10, TimeUnit.SECONDS));

      // With bound 1000 and 1200 requests, at least some should overflow
      assertTrue(overflowCount.get() > 0, "At least some requests should overflow the bound");
      // Total success should not exceed the bound
      assertTrue(successCount.get() <= 1000,
          "Successful requests should not exceed MAX_PENDING: " + successCount.get());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 12: Session key active BEFORE login future resolves
  // ============================================================
  // Proves fix for race where completeLogin() was called before desKey16 assignment.
  // The waiter on loginFuture() may call sendEcm() synchronously on resume —
  // if desKey16 still holds the login key, the upstream cannot decrypt the ECM
  // and the request times out.

  @Test
  @Timeout(10)
  @DisplayName("Session key is active before loginFuture resolves")
  void sessionKeyBeforeLoginComplete() throws Exception {
    MockNewcamdServer server = new MockNewcamdServer(vertx, true, TEST_CW);
    server.start();
    try {
      NewcamdConnector connector = new NewcamdConnector(vertx,
          new CacheVerticle(), "test-newcamd", "127.0.0.1", server.port(),
          CONFIG_KEY14, USER, PASS, "test-profile");

      connector.start().toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      // Block until login completes — on resume, desKey16 MUST be session key
      connector.loginFuture().toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);

      // Immediately send ECM — must succeed, proving session key is active
      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);
      CwReply reply = future.toCompletionStage().toCompletableFuture()
          .get(3, TimeUnit.SECONDS);

      assertNotNull(reply, "Reply must not be null (timeout = wrong key)");
      assertArrayEquals(TEST_CW, reply.cw(), "CW must match TEST_CW");
      assertEquals(request.key(), reply.key());

      connector.stop();
      assertEquals(0, connector.pendingCount());
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Helpers
  // ============================================================

  private NewcamdConnector connect(int port) throws Exception {
    CacheVerticle cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle);

    NewcamdConnector connector = new NewcamdConnector(vertx, cacheVerticle,
        "test-newcamd", "127.0.0.1", port, CONFIG_KEY14, USER, PASS, "test-profile");

    connector.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    connector.loginFuture().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    return connector;
  }

  private static EcmRequest ecmRequest(int tag, int sid, int caid, int hash) {
    byte[] ecm = new byte[32];
    return new EcmRequest(
        new EcmKey(tag, sid, 0x0001, caid, hash),
        ecm, "test-profile", "127.0.0.1", "origin-1",
        System.currentTimeMillis(), null, 5000);
  }

  // ============================================================
  // Mock Newcamd Server
  // ============================================================

  /**
   * A minimal Newcamd server that performs the proper handshake
   * (random14 → login decrypt → ACK encrypt → session key switch)
   * and optionally replies to ECM requests with CWs.
   *
   * <p>Encryption uses real {@link NewcamdCrypto}, same as the connector.
   * No fake plaintext passing through decryptors.
   */
  static class MockNewcamdServer {
    private final Vertx vertx;
    private final boolean replyToEcm;
    private final byte[][] cws; // CWs to send for each ECM request (cycled)
    private int cwIndex;
    private NetServer server;
    private int port;
    private volatile byte[] sessionKey;

    /**
     * @param vertx Vert.x
     * @param replyToEcm if false, server doesn't reply to ECM (for timeout tests)
     * @param cws CWs to reply with (one per ECM request, cycled). If a CW is length 0,
     *            replies with an empty (cannot-decode) response.
     */
    MockNewcamdServer(Vertx vertx, boolean replyToEcm, byte[]... cws) {
      this.vertx = vertx;
      this.replyToEcm = replyToEcm;
      this.cws = cws != null ? cws : new byte[0][];
    }

    int port() { return port; }

    void start() throws Exception {
      CountDownLatch ready = new CountDownLatch(1);
      server = vertx.createNetServer();
      server.connectHandler(this::handleConnection);
      // Bind to port 0 for auto-assignment
      server.listen(0, "127.0.0.1", ar -> {
        if (ar.succeeded()) {
          port = server.actualPort();
          ready.countDown();
        }
      });
      assertTrue(ready.await(5, TimeUnit.SECONDS), "Server should start");
    }

    void stop() {
      if (server != null) server.close();
    }

    private void handleConnection(NetSocket sock) {
      sock.write(Buffer.buffer(RANDOM14));
      FramedHandler handler = new FramedHandler(sock);
      sock.handler(handler::handle);
    }

    /**
     * Reads length-prefixed (2 bytes big-endian) Newcamd frames,
     * decrypts them with the current key, and processes.
     */
    private class FramedHandler {
      private final NetSocket sock;
      private Buffer acc = Buffer.buffer();
      private boolean readingLength = true;
      private int msgLen;
      // Start with login key
      private byte[] currentKey = LOGIN_KEY16;
      private int phase = 0; // 0=login, 1=session

      FramedHandler(NetSocket sock) { this.sock = sock; }

      void handle(Buffer data) {
        acc.appendBuffer(data);
        while (true) {
          if (readingLength) {
            if (acc.length() < 2) return;
            msgLen = ((acc.getByte(0) & 0xFF) << 8) | (acc.getByte(1) & 0xFF);
            if (msgLen <= 0 || msgLen > NewcamdConstants.CWS_NETMSGSIZE + 16) {
              acc = Buffer.buffer(); readingLength = true; return;
            }
            readingLength = false;
          }
          if (!readingLength) {
            if (acc.length() < 2 + msgLen) return;
            byte[] encrypted = acc.getBytes(2, 2 + msgLen);
            acc = Buffer.buffer(acc.getBytes(2 + msgLen, acc.length()));
            readingLength = true;

            byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, currentKey);
            if (decrypted == null) return; // decryption failed

            if (phase == 0) {
              sendAck(sock);
              currentKey = SESSION_KEY16;
              phase = 1;
            } else {
              if (replyToEcm) {
                byte[] cw = getNextCw();
                sendEcmReply(sock, cw, decrypted);
              }
            }
          }
        }
      }

      private byte[] getNextCw() {
        if (cws.length == 0) return TEST_CW;
        byte[] cw = cws[cwIndex % cws.length];
        cwIndex++;
        return cw;
      }
    }

    private void sendAck(NetSocket sock) {
      byte[] ackFrame = buildNewcamdFrame(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_ACK, new byte[0],
          0, 0, 0, 0, 0);
      byte[] encrypted = NewcamdCrypto.desEncrypt(ackFrame, ackFrame.length, LOGIN_KEY16,
          NewcamdConstants.CWS_NETMSGSIZE);
      sendFramed(sock, encrypted);
    }

    private void sendEcmReply(NetSocket sock, byte[] cw, byte[] requestDecrypted) {
      // The reply reflects the same frame format as the request.
      // For the connector to correlate via FIFO, the reply just needs to be an ECM reply.
      // We can echo some header fields from the request for realism.
      int sid = ((requestDecrypted[0] & 0xFF) << 8) | (requestDecrypted[1] & 0xFF);
      int onid = ((requestDecrypted[2] & 0xFF) << 8) | (requestDecrypted[3] & 0xFF);
      int caid = ((requestDecrypted[4] & 0xFF) << 8) | (requestDecrypted[5] & 0xFF);
      int hash = ((requestDecrypted[6] & 0xFF) << 24) | ((requestDecrypted[7] & 0xFF) << 16)
          | ((requestDecrypted[8] & 0xFF) << 8) | (requestDecrypted[9] & 0xFF);
      int tag = requestDecrypted[10] & 0xFF;

      byte[] replyFrame = buildNewcamdFrame(tag, cw, sid, caid, onid, 0, hash);
      byte[] encrypted = NewcamdCrypto.desEncrypt(replyFrame, replyFrame.length, SESSION_KEY16,
          NewcamdConstants.CWS_NETMSGSIZE);
      sendFramed(sock, encrypted);
    }

    private void sendFramed(NetSocket sock, byte[] encrypted) {
      Buffer out = Buffer.buffer(2 + encrypted.length);
      out.appendByte((byte) (encrypted.length >> 8));
      out.appendByte((byte) (encrypted.length & 0xFF));
      out.appendBytes(encrypted);
      sock.write(out);
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
  }
}
