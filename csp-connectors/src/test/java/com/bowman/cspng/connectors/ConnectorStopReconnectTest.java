package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorStopReconnectTest {

  Vertx vertx;

  @AfterEach
  void close() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); } catch (Exception e) { return 33000 + (int)(Math.random()*2000); }
  }

  @Test
  @Timeout(20)
  void newcamdStopPreventsReconnect() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    AtomicInteger connectCount = new AtomicInteger(0);
    NetServer server = vertx.createNetServer();
    CountDownLatch serverStarted = new CountDownLatch(1);
    server.connectHandler(sock -> {
      connectCount.incrementAndGet();
      // Send 14 random bytes to allow handshake to proceed a bit, then keep connection
      byte[] rnd = new byte[14];
      new java.security.SecureRandom().nextBytes(rnd);
      sock.write(io.vertx.core.buffer.Buffer.buffer(rnd));
    });
    server.listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    byte[] key14 = new byte[14];
    java.util.Arrays.fill(key14, (byte)0x01);
    NewcamdConnector conn = new NewcamdConnector(vertx, cv, "test-newcamd", "127.0.0.1", port, key14, "user", "pass", "profile");
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    // Wait for initial connect
    Thread.sleep(800);
    assertEquals(1, connectCount.get(), "initial connect should have happened");
    // Capture server-side socket to force closeHandler -> reconnect attempt would happen
    conn.stop();
    // Give time for closeHandler to fire and scheduleReconnect to be evaluated
    Thread.sleep(500);
    // Force close all server-side sockets by closing server then restarting listening? Instead just wait RECONNECT_DELAY+1s
    // connector's closeHandler already called scheduleReconnect; if bug, it will reconnect
    Thread.sleep(6500);
    assertEquals(1, connectCount.get(), "after stop(), no reconnect should occur");
    assertTrue(conn.stopped, "stopped flag should be true");
    assertFalse(conn.isConnected());
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(20)
  void cccamStopPreventsReconnect() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    AtomicInteger connectCount = new AtomicInteger(0);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      connectCount.incrementAndGet();
      // Send 16 seed bytes for CCcam handshake
      byte[] seed = new byte[16];
      new java.security.SecureRandom().nextBytes(seed);
      sock.write(io.vertx.core.buffer.Buffer.buffer(seed));
    });
    server.listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CcCamConnector conn = new CcCamConnector(vertx, cv, "test-cccam", "127.0.0.1", port, "user", "pass", "profile");
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    Thread.sleep(800);
    assertEquals(1, connectCount.get());
    conn.stop();
    Thread.sleep(500);
    Thread.sleep(6500);
    assertEquals(1, connectCount.get(), "after stop(), CCcam should not reconnect");
    assertTrue(conn.stopped);
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(20)
  void radegastStopPreventsReconnect() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    AtomicInteger connectCount = new AtomicInteger(0);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> connectCount.incrementAndGet());
    server.listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    RadegastConnector conn = new RadegastConnector(vertx, cv, "test-radegast", "127.0.0.1", port, "profile");
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    Thread.sleep(800);
    assertEquals(1, connectCount.get());
    conn.stop();
    Thread.sleep(6500);
    assertEquals(1, connectCount.get(), "after stop(), radegast should not reconnect");
    assertTrue(conn.stopped);
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(10)
  void startResetsStoppedAllowsReconnectAfterRestart() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    AtomicInteger connectCount = new AtomicInteger(0);
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> connectCount.incrementAndGet());
    server.listen(port, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    RadegastConnector conn = new RadegastConnector(vertx, cv, "test-restart", "127.0.0.1", port, "profile");
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    Thread.sleep(500);
    assertEquals(1, connectCount.get());
    conn.stop();
    assertTrue(conn.stopped);
    Thread.sleep(500);
    // restart should reset stopped and reconnect
    conn.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    Thread.sleep(500);
    assertFalse(conn.stopped, "start() should reset stopped");
    assertEquals(2, connectCount.get(), "restart should reconnect");
    conn.stop();
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }
}
