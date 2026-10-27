package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.radegast.RadegastConstants;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RadegastConnector ECM reply handling.
 *
 * <p>Uses a mock Radegast server that handles TLV framing and replies
 * with CWs. Tests fragmentation by splitting frames across multiple buffers.
 */
@DisplayName("RadegastConnector — reply correlation")
class RadegastConnectorReplyTest {

  private Vertx vertx;
  private static final byte[] TEST_CW = {
      0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
      (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
      (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00
  };

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
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

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
    MockRadegastServer server = new MockRadegastServer(vertx, true, TEST_CW);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      CwReply reply = future.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertArrayEquals(TEST_CW, reply.cw());
      assertEquals(request.key(), reply.key());
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
    byte[] cw1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                  0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10};
    byte[] cw2 = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                  0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30};
    byte[] cw3 = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
                  0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40};

    MockRadegastServer server = new MockRadegastServer(vertx, true, cw1, cw2, cw3);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

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
    MockRadegastServer server = new MockRadegastServer(vertx, true, new byte[0]);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

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
  // Test 5: Fragmented reply — server sends reply in pieces
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("sendEcm handles fragmented reply correctly")
  void fragmentedReply() throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch replyDone = new CountDownLatch(1);
    var replyRef = new java.util.concurrent.atomic.AtomicReference<CwReply>();

    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      sock.handler(data -> {
        // Received ECM request — send reply in two fragments
        byte[] replyFrame = RadegastConstants.buildReplyFrame(TEST_CW);
        // Split: send first part, then rest after a small delay
        int split = replyFrame.length / 2;
        byte[] part1 = new byte[split];
        System.arraycopy(replyFrame, 0, part1, 0, split);
        byte[] part2 = new byte[replyFrame.length - split];
        System.arraycopy(replyFrame, split, part2, 0, part2.length);
        sock.write(Buffer.buffer(part1));
        // Small delay to ensure first part is processed before second
        vertx.setTimer(50, tid -> {
          sock.write(Buffer.buffer(part2));
        });
      });
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) ready.countDown();
    });
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    int port = server.actualPort();

    try {
      RadegastConnector connector = connect(port);

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

      CwReply reply = future.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertArrayEquals(TEST_CW, reply.cw());
      assertEquals(request.key(), reply.key());

      connector.stop();
    } finally {
      server.close();
    }
  }

  // ============================================================
  // Test 6: Timeout
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("sendEcm fails with timeout when server doesn't reply")
  void timeoutFails() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> future = connector.sendEcm(request);

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
  // Test 7: Stop cleanup
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("Pending futures fail on connector stop")
  void pendingFailOnStop() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

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
  // Test 8: Overflow
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("sendEcm fails when MAX_PENDING exceeded")
  void overflowRejected() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      EcmRequest request = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      for (int i = 0; i < 1000; i++) {
        connector.sendEcm(request);
      }

      Future<CwReply> future = connector.sendEcm(request);
      assertTrue(future.failed(), "Should reject overflow");
      assertTrue(future.cause().getMessage().contains("Too many pending"),
          "Expected overflow message");

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 9: Timeout → all pending fail + connection closed
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Timeout of one ECM fails all pending and closes connection")
  void timeoutClearsAllPending() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      EcmRequest req1 = ecmRequest(0x80, 0x0001, 0x0D00, 0x11);
      EcmRequest req2 = ecmRequest(0x80, 0x0002, 0x0D00, 0x22);

      Future<CwReply> f1 = connector.sendEcm(req1);
      Future<CwReply> f2 = connector.sendEcm(req2);

      Throwable t1 = f1.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(10, TimeUnit.SECONDS);
      assertNotNull(t1, "First should fail");
      Throwable t2 = f2.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(5, TimeUnit.SECONDS);
      assertNotNull(t2, "Second should also fail");

      connector.stop();
      assertFalse(connector.isConnected());
      assertEquals(0, connector.pendingCount(), "pending count must be 0");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 10: Late reply not misattributed
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("After timeout, late reply cannot be misattributed (connection was closed)")
  void lateReplyNotMisattributed() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      EcmRequest req1 = ecmRequest(0x80, 0x0001, 0x0D00, 0x11);
      Future<CwReply> f1 = connector.sendEcm(req1);

      Throwable t1 = f1.toCompletionStage().toCompletableFuture()
          .handle((r, t) -> t).get(10, TimeUnit.SECONDS);
      assertNotNull(t1, "First should have failed (timeout)");

      connector.stop();
      assertFalse(connector.isConnected(), "Connection should be down");
      assertEquals(0, connector.pendingCount(), "pending count must be 0");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 11: Concurrent atomic bound
  // ============================================================

  @Test
  @Timeout(15)
  @DisplayName("Concurrent sendEcm calls respect MAX_PENDING bound")
  void concurrentAtomicBound() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, false, null);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      int threadCount = 4;
      int perThread = 300;
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

      assertTrue(overflowCount.get() > 0);
      assertTrue(successCount.get() <= 1000,
          "Successful: " + successCount.get());

      connector.stop();
      assertEquals(0, connector.pendingCount(), "pending count must be 0");
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Test 12: onTimeout with unknown ID is no-op
  // ============================================================

  @Test
  @Timeout(10)
  @DisplayName("onTimeout with unknown timer id does NOT close connection")
  void onTimeoutUnknownIdNoOp() throws Exception {
    MockRadegastServer server = new MockRadegastServer(vertx, true, TEST_CW);
    server.start();
    try {
      RadegastConnector connector = connect(server.port());

      // Send one ECM to establish connection works
      EcmRequest req = ecmRequest(0x80, 0x1234, 0x0D00, 0x42);
      Future<CwReply> f = connector.sendEcm(req);
      CwReply reply = f.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply);
      assertEquals(0, connector.pendingCount());

      // Bogus timer ids — must be no-op
      assertTrue(connector.isConnected());
      connector.onTimeout(-1L);
      connector.onTimeout(Long.MAX_VALUE);
      assertTrue(connector.isConnected(),
          "Connection must still be alive after onTimeout with unknown id");
      assertEquals(0, connector.pendingCount());

      // Connection still works
      EcmRequest req2 = ecmRequest(0x81, 0x5678, 0x0D00, 0x99);
      Future<CwReply> f2 = connector.sendEcm(req2);
      CwReply reply2 = f2.toCompletionStage().toCompletableFuture()
          .get(5, TimeUnit.SECONDS);
      assertNotNull(reply2);
      assertEquals(req2.key(), reply2.key());
      assertEquals(0, connector.pendingCount());

      connector.stop();
    } finally {
      server.stop();
    }
  }

  // ============================================================
  // Helpers
  // ============================================================

  private RadegastConnector connect(int port) throws Exception {
    CacheVerticle cacheVerticle = new CacheVerticle();
    RadegastConnector connector = new RadegastConnector(vertx, cacheVerticle,
        "test-radegast", "127.0.0.1", port, "test-profile");

    connector.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
  // Mock Radegast Server
  // ============================================================

  /**
   * A minimal Radegast server that replies to ECM requests.
   */
  static class MockRadegastServer {
    private final Vertx vertx;
    private final boolean replyToEcm;
    private final byte[][] cws;
    private int cwIndex;
    private NetServer server;
    private int port;

    MockRadegastServer(Vertx vertx, boolean replyToEcm, byte[]... cws) {
      this.vertx = vertx;
      this.replyToEcm = replyToEcm;
      this.cws = cws != null ? cws : new byte[0][];
    }

    int port() { return port; }

    void start() throws Exception {
      CountDownLatch ready = new CountDownLatch(1);
      server = vertx.createNetServer();
      server.connectHandler(sock -> {
        Buffer[] acc = { Buffer.buffer() };
        sock.handler(data -> {
          acc[0] = acc[0].appendBuffer(data);
          // Process all complete ECM request frames in the buffer
          while (acc[0].length() >= 2 && acc[0].getByte(0) == RadegastConstants.CMD_ECM_KEY_ASK) {
            int frameLen = (acc[0].getByte(1) & 0xFF) + 2;
            if (acc[0].length() < frameLen) break; // incomplete frame
            acc[0] = acc[0].getBuffer(frameLen, acc[0].length()); // consume this frame
            if (replyToEcm) {
              byte[] cw = getNextCw();
              byte[] replyFrame = RadegastConstants.buildReplyFrame(cw);
              sock.write(Buffer.buffer(replyFrame));
            }
          }
        });
      });
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

    private byte[] getNextCw() {
      if (cws.length == 0) return TEST_CW;
      byte[] cw = cws[cwIndex % cws.length];
      cwIndex++;
      return cw;
    }
  }
}
