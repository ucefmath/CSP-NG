package com.bowman.cspng.connectors;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.filters.*;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class CwsConnectorManagerTest {

  /* ---- existing tests (preserved) ---- */

  @Test
  void emptyManagerHasZeroActive() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cacheVerticle = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cacheVerticle);
    assertEquals(0, mgr.activeCount());
    vertx.close();
  }

  @Test
  void addNewcamdConnector() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cacheVerticle = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cacheVerticle);

    byte[] desKey = new byte[14];
    NewcamdConnector c = mgr.addNewcamdConnector("test", "localhost", 12345, desKey,
        "testuser", "testpass", "test-profile");
    assertNotNull(c);
    assertEquals("test", c.getName());
    assertEquals("test-profile", c.getProfile());
    vertx.close();
  }

  @Test
  void removeConnectorRemovesFromMapAndStops() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cv);
    byte[] desKey = new byte[14];
    NewcamdConnector c = mgr.addNewcamdConnector("to-remove", "localhost", 12345, desKey, "u", "p", "prof");
    c.connected = true;
    assertNotNull(mgr.getConnector("to-remove"));
    assertEquals(1, mgr.getConnectors().size());
    AbstractConnector removed = mgr.removeConnector("to-remove");
    assertSame(c, removed);
    assertNull(mgr.getConnector("to-remove"));
    assertEquals(0, mgr.getConnectors().size());
    assertFalse(c.isConnected(), "stop() should clear connected");
    assertTrue(c.stopped, "stopped flag should be true");
    vertx.close();
  }

  @Test
  void removeConnectorUnknownReturnsNull() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cv);
    assertNull(mgr.removeConnector("nope"));
    vertx.close();
  }

  @Test
  void removeCcCamConnectorAlsoRemovesTransport() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CountDownLatch latch = new CountDownLatch(1);
    vertx.deployVerticle(cv).onComplete(ar -> latch.countDown());
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    CountDownLatch deploy2 = new CountDownLatch(1);
    cv.runOnContext(deploy2::countDown);
    assertTrue(deploy2.await(5, TimeUnit.SECONDS));

    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cv);
    CcCamConnector c = mgr.addCcCamConnector("cc-test", "localhost", 12345, "u", "p", "prof", "2.3.2", "4000");
    CountDownLatch addLatch = new CountDownLatch(1);
    cv.runOnContext(() -> { cv.addTransport(c); addLatch.countDown(); });
    assertTrue(addLatch.await(5, TimeUnit.SECONDS));
    assertEquals(1, cv.transportCount());
    mgr.removeConnector("cc-test");
    CountDownLatch check = new CountDownLatch(1);
    cv.runOnContext(check::countDown);
    assertTrue(check.await(5, TimeUnit.SECONDS));
    assertEquals(0, cv.transportCount(), "CCcam remove should decrement transportCount");
    vertx.close();
  }

  @Test
  void removeConnectorNotifiesListener() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cv);
    AtomicReference<String> removedName = new AtomicReference<>();
    AtomicReference<AbstractConnector> removedConn = new AtomicReference<>();
    mgr.addListener(new CwsConnectorManager.ConnectorListener() {
      @Override public void onConnectorAdded(String name, AbstractConnector connector) {}
      @Override public void onConnectorRemoved(String name, AbstractConnector connector) {
        removedName.set(name);
        removedConn.set(connector);
      }
    });
    byte[] desKey = new byte[14];
    NewcamdConnector c = mgr.addNewcamdConnector("listen-test", "localhost", 12345, desKey, "u", "p", "prof");
    mgr.removeConnector("listen-test");
    assertEquals("listen-test", removedName.get());
    assertSame(c, removedConn.get());
    vertx.close();
  }

  @Test
  void getConnectorsIsUnmodifiable() {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cv = new CacheVerticle();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, cv);
    byte[] desKey = new byte[14];
    mgr.addNewcamdConnector("a", "localhost", 12345, desKey, "u", "p", "prof");
    assertThrows(UnsupportedOperationException.class, () -> mgr.getConnectors().put("x", null));
    vertx.close();
  }

  /* ---- new tests: filter chain integration ---- */

  static CwReply testReply(String connector, byte[] cw) {
    return new CwReply(new EcmKey(0x80, 1, 2, 0x1810, 12345), 0x80,
        cw, connector, "127.0.0.1", System.currentTimeMillis());
  }

  static CwReply testReply(String connector) {
    return testReply(connector, new byte[]{4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33, 13, 14, 15, 42});
  }

  static EcmRequest testRequest(String profile) {
    return testRequest(profile, 5000);
  }

  static EcmRequest testRequest(String profile, long maxWaitMs) {
    return new EcmRequest(new EcmKey(0x80, 1, 2, 0x1810, 12345),
        new byte[]{1, 2, 3}, profile, "192.168.1.1", "origin-1",
        System.currentTimeMillis(), 0.5, maxWaitMs);
  }

  // --- filter chain setter/getter ---

  @Test
  void filterChainInitiallyNull() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    assertNull(mgr.getFilterChain());
    vertx.close();
  }

  @Test
  void setAndGetFilterChain() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    FilterChain chain = new FilterChain(List.of(new DcwFilter(new DcwFilterConfig(), null)));
    mgr.setFilterChain(chain);
    assertSame(chain, mgr.getFilterChain());
    mgr.setFilterChain(null);
    assertNull(mgr.getFilterChain());
    vertx.close();
  }

  // --- getConnectedConnectorsForProfile ---

  @Test
  void getConnectedConnectorsFiltersByProfileAndConnected() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    TestConnector ca = new TestConnector(vertx, "ca", "profile-a");
    ca.connected = true;
    TestConnector cb = new TestConnector(vertx, "cb", "profile-b");
    cb.connected = true;
    TestConnector ca2 = new TestConnector(vertx, "ca2", "profile-a");
    ca2.connected = false;
    mgr.addTestConnector(ca);
    mgr.addTestConnector(cb);
    mgr.addTestConnector(ca2);

    List<AbstractConnector> list = mgr.getConnectedConnectorsForProfile("profile-a");
    assertEquals(1, list.size());
    assertSame(ca, list.get(0));
    vertx.close();
  }

  // --- sendEcmRequest without filter chain (historical behavior preserved) ---

  @Test
  void sendEcmRequestNoConnectorsFails() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    assertTrue(f.failed());
    vertx.close();
  }

  @Test
  void sendEcmRequestNoFilterChainPreservesHistoricalBehavior() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    assertTrue(f.succeeded());
    assertSame(reply, f.result());
    vertx.close();
  }

  // --- single connector + filter chain: ACCEPT / REWRITE / RELEASE succeed ---

  @Test
  void sendEcmWithAcceptFilterSucceeds() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    TestConnector tc = addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter acceptAll = new TestFilter("accept", 10, (ctx, r) -> new FilterDecision.Accept(r));
    mgr.setFilterChain(new FilterChain(List.of(acceptAll)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(reply, f.result());
    assertEquals(1, tc.sendCount());
    vertx.close();
  }

  @Test
  void sendEcmWithRewriteFilterSucceeds() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply original = testReply("c1");
    byte[] newCw = {1, 1, 1, 3, 1, 1, 1, 3, 1, 1, 1, 3, 1, 1, 1, 3};
    CwReply rewritten = testReply("c1", newCw);
    TestConnector tc = addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(original));

    CwFilter rewriter = new TestFilter("rw", 10, (ctx, r) -> new FilterDecision.Rewrite(rewritten));
    mgr.setFilterChain(new FilterChain(List.of(rewriter)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(rewritten, f.result());
    assertEquals(1, tc.sendCount());
    vertx.close();
  }

  @Test
  void sendEcmWithReleaseFilterSucceeds() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    CwReply held = testReply("c2");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter releaser = new TestFilter("rel", 10,
        (ctx, r) -> new FilterDecision.ReleasePrevious(held, "c2"));
    mgr.setFilterChain(new FilterChain(List.of(releaser)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(held, f.result());
    vertx.close();
  }

  // --- DROP/HOLD do NOT fail immediately ---

  @Test
  void dropFilterDoesNotFailImmediately() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter dropper = new TestFilter("drop", 10, (ctx, r) -> FilterDecision.Drop.INSTANCE);
    mgr.setFilterChain(new FilterChain(List.of(dropper)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("dropped"), "error must be generic");
    assertFalse(f.cause().getMessage().contains("c1"), "must not leak connector name");
    vertx.close();
  }

  @Test
  void holdFilterDoesNotFailImmediately() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter holder = new TestFilter("hold", 10, (ctx, r) -> FilterDecision.Hold.INSTANCE);
    mgr.setFilterChain(new FilterChain(List.of(holder)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("held"), "error must be generic");
    vertx.close();
  }

  // --- timeout ---

  @Test
  void timeoutFailsPromise() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter dropper = new TestFilter("drop", 10, (ctx, r) -> FilterDecision.Drop.INSTANCE);
    mgr.setFilterChain(new FilterChain(List.of(dropper)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 150));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertTrue(f.cause().getMessage().contains("No ECM response"),
        "message should be generic");
    assertFalse(f.cause().getMessage().contains("profile-a"),
        "message must NOT contain profile name");
    assertFalse(f.cause().getMessage().matches(".*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*"),
        "must not leak IP addresses");
    vertx.close();
  }

  // --- async release after HOLD completes before timeout ---

  @Test
  void asyncReleaseAfterHoldSucceeds() throws Exception {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    CwReply held = testReply("held-conn");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    AtomicReference<FilterContext.ReplyRelease> captured = new AtomicReference<>();
    CwFilter holdThenRelease = new TestFilter("async", 10, (ctx, r) -> {
      captured.set(ctx.releaseChannel());
      return FilterDecision.Hold.INSTANCE;
    });
    mgr.setFilterChain(new FilterChain(List.of(holdThenRelease)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 5000));

    CountDownLatch released = new CountDownLatch(1);
    vertx.setTimer(100, id -> {
      FilterContext.ReplyRelease channel = captured.get();
      assertNotNull(channel);
      channel.release(held, "c1");
      released.countDown();
    });

    assertTrue(released.await(5, TimeUnit.SECONDS));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(held, f.result());
    vertx.close();
  }

  // --- late release ignored ---

  @Test
  void lateReleaseAfterTimeoutIgnored() throws Exception {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    AtomicReference<FilterContext.ReplyRelease> captured = new AtomicReference<>();
    CwFilter holder = new TestFilter("h", 10, (ctx, r) -> {
      captured.set(ctx.releaseChannel());
      return FilterDecision.Hold.INSTANCE;
    });
    mgr.setFilterChain(new FilterChain(List.of(holder)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 150));
    awaitCompletion(f);
    assertTrue(f.failed());

    CwReply late = testReply("late");
    FilterContext.ReplyRelease channel = captured.get();
    channel.release(late, "c1");
    assertTrue(f.failed()); // still failed
    vertx.close();
  }

  // --- never-completing connector bounded ---

  @Test
  void neverCompletingConnectorIsBounded() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    addTestConnector(mgr, vertx, "c1", "profile-a", Promise.<CwReply>promise().future());

    CwFilter acceptAll = new TestFilter("a", 10, (ctx, r) -> new FilterDecision.Accept(r));
    mgr.setFilterChain(new FilterChain(List.of(acceptAll)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("c1"), "must not leak connector name");
    vertx.close();
  }

  // --- scheduler: filter can HOLD then schedule release ---

  @Test
  void holdThenScheduledReleaseSucceeds() throws Exception {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    CwReply held = testReply("held");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwFilter holdAndSchedule = new TestFilter("h+s", 10, (ctx, r) -> {
      // schedule a release via the context's scheduler
      ctx.scheduler().schedule(80, () -> ctx.releaseChannel().release(held, "c1"));
      return FilterDecision.Hold.INSTANCE;
    });
    mgr.setFilterChain(new FilterChain(List.of(holdAndSchedule)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 5000));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(held, f.result());
    vertx.close();
  }

  // --- scheduler: can be cancelled ---

  @Test
  void scheduledTaskCanBeCancelled() throws Exception {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    AtomicInteger callCount = new AtomicInteger(0);
    CwFilter scheduleThenCancel = new TestFilter("sc", 10, (ctx, r) -> {
      var task = ctx.scheduler().schedule(50, callCount::incrementAndGet);
      task.cancel(); // immediately cancel
      return FilterDecision.Hold.INSTANCE;
    });
    mgr.setFilterChain(new FilterChain(List.of(scheduleThenCancel)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 300));
    awaitCompletion(f);
    assertTrue(f.failed());
    // wait a bit more to ensure cancelled task doesn't fire
    Thread.sleep(100);
    assertEquals(0, callCount.get(), "cancelled task should not have run");
    vertx.close();
  }

  // --- scheduler: late scheduled release ignored ---

  @Test
  void lateScheduledReleaseIgnored() throws Exception {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c1");
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply));

    CwReply lateHeld = testReply("late");
    CwFilter holdAndLateSchedule = new TestFilter("hls", 10, (ctx, r) -> {
      // schedule after timeout will have fired
      ctx.scheduler().schedule(500, () -> ctx.releaseChannel().release(lateHeld, "c1"));
      return FilterDecision.Hold.INSTANCE;
    });
    mgr.setFilterChain(new FilterChain(List.of(holdAndLateSchedule)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 100));
    awaitCompletion(f);
    assertTrue(f.failed());
    Thread.sleep(500); // let the late scheduler fire
    assertTrue(f.failed()); // still failed
    vertx.close();
  }

  // --- sendEcm throws synchronously → handled as failure path ---

  @Test
  void sendEcmSyncExceptionHandled() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    ThrowingConnector throwingConn = new ThrowingConnector(vertx, "thrower", "profile-a");
    throwingConn.connected = true;
    mgr.addTestConnector(throwingConn);

    CwFilter acceptAll = new TestFilter("a", 10, (ctx, r) -> new FilterDecision.Accept(r));
    mgr.setFilterChain(new FilterChain(List.of(acceptAll)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("thrower"), "must not leak connector name");
    vertx.close();
  }

  // --- clampDelay ---

  @Test
  void clampDelayBounds() {
    assertEquals(1, CwsConnectorManager.clampDelay(0));
    assertEquals(1, CwsConnectorManager.clampDelay(-5));
    assertEquals(100, CwsConnectorManager.clampDelay(100));
    assertEquals(60_000, CwsConnectorManager.clampDelay(120_000));
  }

  // --- fan-out: first accept wins ---

  @Test
  void fanOutFirstAcceptWinsSecondIgnored() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());

    CwReply reply1 = testReply("c1");
    Promise<CwReply> p2 = Promise.promise();
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(reply1));
    addTestConnector(mgr, vertx, "c2", "profile-a", p2.future());

    CwFilter multi = new TestFilter("multi", 10, (ctx, r) -> new FilterDecision.Accept(r), true);
    mgr.setFilterChain(new FilterChain(List.of(multi)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(reply1, f.result());

    CwReply reply2 = testReply("c2");
    p2.complete(reply2);
    assertSame(reply1, f.result());
    vertx.close();
  }

  // --- fan-out: all DROP waits for timeout ---

  @Test
  void fanOutAllDropWaitsForTimeout() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(testReply("c1")));
    addTestConnector(mgr, vertx, "c2", "profile-a", Future.succeededFuture(testReply("c2")));

    CwFilter dropper = new TestFilter("drop", 10, (ctx, r) -> FilterDecision.Drop.INSTANCE, true);
    mgr.setFilterChain(new FilterChain(List.of(dropper)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("c1"), "must not leak connector name");
    vertx.close();
  }

  // --- fan-out: all HOLD waits for timeout ---

  @Test
  void fanOutAllHoldWaitsForTimeout() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(testReply("c1")));
    addTestConnector(mgr, vertx, "c2", "profile-a", Future.succeededFuture(testReply("c2")));

    CwFilter holder = new TestFilter("hold", 10, (ctx, r) -> FilterDecision.Hold.INSTANCE, true);
    mgr.setFilterChain(new FilterChain(List.of(holder)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    vertx.close();
  }

  // --- fan-out: one error + one accept succeeds ---

  @Test
  void fanOutOneErrorOneAcceptSucceeds() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c2");

    addTestConnector(mgr, vertx, "c1", "profile-a",
        Future.failedFuture("connection refused"));
    addTestConnector(mgr, vertx, "c2", "profile-a", Future.succeededFuture(reply));

    CwFilter multi = new TestFilter("multi", 10, (ctx, r) -> new FilterDecision.Accept(r), true);
    mgr.setFilterChain(new FilterChain(List.of(multi)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(reply, f.result());
    vertx.close();
  }

  // --- fan-out: all errors waits for timeout ---

  @Test
  void fanOutAllErrorsWaitsForTimeout() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());

    addTestConnector(mgr, vertx, "c1", "profile-a",
        Future.failedFuture("error1"));
    addTestConnector(mgr, vertx, "c2", "profile-a",
        Future.failedFuture("error2"));

    CwFilter multi = new TestFilter("multi", 10, (ctx, r) -> new FilterDecision.Accept(r), true);
    mgr.setFilterChain(new FilterChain(List.of(multi)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 200));
    awaitCompletion(f);
    assertTrue(f.failed());
    assertFalse(f.cause().getMessage().contains("error1"), "must not leak internal error");
    vertx.close();
  }

  // --- fan-out: filter exception fail-closed ---

  @Test
  void fanOutFilterExceptionTreatedAsDrop() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    CwReply reply = testReply("c2");

    addTestConnector(mgr, vertx, "c1", "profile-a", Future.succeededFuture(testReply("c1")));
    addTestConnector(mgr, vertx, "c2", "profile-a", Future.succeededFuture(reply));

    CwFilter throwing = new TestFilter("throw", 10, (ctx, r) -> {
      if ("c1".equals(ctx.connectorName())) throw new RuntimeException("simulated crash");
      return new FilterDecision.Accept(r);
    }, true);

    mgr.setFilterChain(new FilterChain(List.of(throwing)));
    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    assertSame(reply, f.result());
    vertx.close();
  }

  // --- no connected connectors fails immediately ---

  @Test
  void fanOutNoConnectedConnectorsFails() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    TestConnector tc = new TestConnector(vertx, "c1", "profile-a");
    tc.connected = false;
    mgr.addTestConnector(tc);

    CwFilter multi = new TestFilter("multi", 10, (ctx, r) -> new FilterDecision.Accept(r), true);
    mgr.setFilterChain(new FilterChain(List.of(multi)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a"));
    assertTrue(f.failed());
    vertx.close();
  }

  // --- error message never leaks topology ---

  @Test
  void errorMessagesAreGenericAndBounded() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    addTestConnector(mgr, vertx, "secret-connector", "profile-a",
        Future.succeededFuture(testReply("secret-connector")));

    CwFilter dropper = new TestFilter("d", 10, (ctx, r) -> FilterDecision.Drop.INSTANCE);
    mgr.setFilterChain(new FilterChain(List.of(dropper)));

    Future<CwReply> f = mgr.sendEcmRequest(testRequest("profile-a", 100));
    awaitCompletion(f);
    String msg = f.cause().getMessage();
    assertNotNull(msg);
    assertFalse(msg.contains("secret-connector"), "must not leak connector name: " + msg);
    assertFalse(msg.contains("localhost"), "must not leak host: " + msg);
    assertFalse(msg.contains("9999"), "must not leak port: " + msg);
    assertFalse(msg.contains("127.0.0.1"), "must not leak IP: " + msg);
    assertFalse(msg.contains("profile-a"), "must not leak profile: " + msg);
    assertTrue(msg.length() < 200, "error message must be bounded: " + msg.length());
    vertx.close();
  }

  // --- helpers ---

  static TestConnector addTestConnector(CwsConnectorManager mgr, Vertx vertx,
                                         String name, String profile,
                                         Future<CwReply> replyFuture) {
    TestConnector tc = new TestConnector(vertx, name, profile, replyFuture);
    tc.connected = true;
    mgr.addTestConnector(tc);
    return tc;
  }

  static void awaitCompletion(Future<?> f) {
    try {
      long deadline = System.currentTimeMillis() + 5000;
      while (!f.isComplete() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static class TestConnector extends AbstractConnector {
    private final Future<CwReply> replyFuture;
    private final AtomicInteger sendCount = new AtomicInteger(0);

    TestConnector(Vertx vertx, String name, String profile) {
      this(vertx, name, profile, Future.failedFuture("not configured"));
    }

    TestConnector(Vertx vertx, String name, String profile, Future<CwReply> replyFuture) {
      super(vertx, new CacheVerticle(), name, "localhost", 9999, profile);
      this.replyFuture = replyFuture;
    }

    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() { connected = false; }
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      sendCount.incrementAndGet();
      return replyFuture;
    }
    int sendCount() { return sendCount.get(); }
  }

  /** Connector that throws synchronously from sendEcm. */
  static class ThrowingConnector extends AbstractConnector {
    ThrowingConnector(Vertx vertx, String name, String profile) {
      super(vertx, new CacheVerticle(), name, "localhost", 9999, profile);
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() { connected = false; }
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      throw new RuntimeException("synchronous failure");
    }
  }

  static class TestFilter implements CwFilter {
    private final String name;
    private final int order;
    private final DecisionFn fn;
    private final boolean multi;

    TestFilter(String name, int order, DecisionFn fn) { this(name, order, fn, false); }
    TestFilter(String name, int order, DecisionFn fn, boolean multi) {
      this.name = name; this.order = order; this.fn = fn; this.multi = multi;
    }
    @Override public String name() { return name; }
    @Override public int order() { return order; }
    @Override public FilterDecision evaluateReply(FilterContext ctx, CwReply reply) {
      return fn.apply(ctx, reply);
    }
    @Override public boolean requiresMultipleReplies() { return multi; }
  }

  @FunctionalInterface
  interface DecisionFn {
    FilterDecision apply(FilterContext ctx, CwReply reply);
  }

  // ---- snapshotConnectors tests ----

  @Test
  void snapshotConnectorsSanitizesAndSorted() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    TestConnector c2 = new TestConnector(vertx, "z-conn", "profile-a");
    c2.connected = true;
    TestConnector c1 = new TestConnector(vertx, "a-conn", "profile-a");
    c1.connected = false;
    mgr.addTestConnector(c2);
    mgr.addTestConnector(c1);
    var list = mgr.snapshotConnectors();
    assertEquals(2, list.size());
    assertEquals("a-conn", list.get(0).name());
    assertEquals("z-conn", list.get(1).name());
    // protocol sanitize allowlist
    for (var s : list) {
      assertTrue(java.util.Set.of("newcamd","radegast","cccam","unknown").contains(s.protocol()));
    }
    // no host/port/leak in snapshot
    for (var s : list) {
      String str = s.toString();
      assertFalse(str.contains("localhost"));
      assertFalse(str.contains("9999"));
      assertFalse(str.toLowerCase().contains("password"));
      assertFalse(str.toLowerCase().contains("deskey"));
    }
    // immutability
    assertThrows(UnsupportedOperationException.class, () -> list.add(list.get(0)));
    vertx.close();
  }

  @Test
  void snapshotConnectorsProtocolDerivation() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    TestConnector tc = new TestConnector(vertx, "tc", "prof");
    tc.connected = true;
    mgr.addTestConnector(tc);
    // Add real connectors to check protocol allowlist
    byte[] key = new byte[14];
    NewcamdConnector nc = mgr.addNewcamdConnector("nc1", "localhost", 1234, key, "u","p","prof");
    CcCamConnector cc = mgr.addCcCamConnector("cc1", "localhost", 1235, "u","p","prof","2.3.2","4000");
    RadegastConnector rg = mgr.addRadegastConnector("rg1", "localhost", 1236, "prof");
    var snaps = mgr.snapshotConnectors();
    java.util.Map<String, String> protoByName = new java.util.HashMap<>();
    for (var s: snaps) protoByName.put(s.name(), s.protocol());
    assertEquals("newcamd", protoByName.get("nc1"));
    assertEquals("cccam", protoByName.get("cc1"));
    assertEquals("radegast", protoByName.get("rg1"));
    assertEquals("unknown", protoByName.get("tc"));
    vertx.close();
  }

  @Test
  void snapshotConnectorsNoSecretsInJsonComponents() {
    var comps = ConnectorSnapshot.class.getRecordComponents();
    for (var rc: comps) {
      String n = rc.getName().toLowerCase();
      assertFalse(n.contains("password"));
      assertFalse(n.contains("secret"));
      assertFalse(n.contains("deskey"));
      assertFalse(n.contains("host"));
      assertFalse(n.contains("port"));
    }
  }

  // ---- ensureMatchingKey & defense in depth tests ----

  @Test
  void ensureMatchingKeyReturnsSameIfFieldsMatch() {
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0500, 0xABCD);
    CwReply reply = new CwReply(key, 0x80, new byte[16], "conn1", "127.0.0.1", 1000L);
    CwReply result = CwsConnectorManager.ensureMatchingKey(reply, key);
    assertSame(reply, result);
  }

  @Test
  void ensureMatchingKeyReassignsWhenDummyKeyProvided() {
    EcmKey reqKey = new EcmKey(0x80, 0x1234, 0x0001, 0x0500, 0xABCD);
    EcmKey dummyKey = new EcmKey(0x80, 0, 0, 0, 0);
    byte[] cw = new byte[]{1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};
    CwReply replyWithDummyKey = new CwReply(dummyKey, 0x80, cw, "conn1", "127.0.0.1", 1000L);

    CwReply result = CwsConnectorManager.ensureMatchingKey(replyWithDummyKey, reqKey);
    assertNotSame(replyWithDummyKey, result);
    assertEquals(reqKey, result.key());
    assertEquals(0x80, result.key().commandTag());
    assertEquals(0x1234, result.key().serviceId());
    assertEquals(0x0001, result.key().networkId());
    assertEquals(0x0500, result.key().caId());
    assertEquals(0xABCD, result.key().dataHash());
    assertEquals("conn1", result.connectorName());
    assertEquals("127.0.0.1", result.originAddress());
    assertArrayEquals(cw, result.cw());
  }

  @Test
  void dispatchToCandidatesReassignsRequestKeyWhenConnectorReturnsDummyKey() {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());

    EcmKey reqKey = new EcmKey(0x80, 0x2000, 0x0010, 0x0500, 0x12345678);
    EcmRequest req = new EcmRequest(reqKey, new byte[]{1, 2, 3}, "default",
        "192.168.1.1", "origin-1", System.currentTimeMillis(), null, 5000L);

    // Connector returning dummy key (0x80, 0, 0, 0, 0)
    byte[] cw = new byte[]{1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};
    CwReply dummyReply = new CwReply(new EcmKey(0x80, 0, 0, 0, 0), 0x80,
        cw, "dummy-conn", "127.0.0.1", 1000L);

    TestConnector tc = addTestConnector(mgr, vertx, "dummy-conn", "default",
        Future.succeededFuture(dummyReply));

    Future<CwReply> f = mgr.dispatchToCandidates(req, List.of(tc));
    awaitCompletion(f);
    assertTrue(f.succeeded());
    CwReply result = f.result();
    assertNotNull(result);
    assertEquals(reqKey, result.key(), "dispatchToCandidates must reassign original request key");
    assertEquals(0x2000, result.key().serviceId());
    assertEquals(0x0500, result.key().caId());
    assertEquals(0x12345678, result.key().dataHash());
    assertArrayEquals(cw, result.cw());
    vertx.close();
  }

  // ---- Checkpoint DCW : provider réel propagé + garde historique checksum du manager ----

  private Future<CwReply> sendWithPolicy(com.bowman.cspng.domain.dcw.DcwQualityPolicy policy, int provider, byte[] cw) {
    Vertx vertx = Vertx.vertx();
    CwsConnectorManager mgr = new CwsConnectorManager(vertx, new CacheVerticle());
    mgr.setDcwPolicy(policy);
    EcmKey key = new EcmKey(0x80, 0x2000, 0x0010, 0x0500, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[]{1, 2, 3}, "default", "192.168.1.1", "origin-1",
        System.currentTimeMillis(), null, 2000L);
    addTestConnector(mgr, vertx, "c-dcw", "default",
        Future.succeededFuture(new CwReply(key, 0x80, cw, "c-dcw", "127.0.0.1", 1000L)));
    Future<CwReply> f = mgr.sendEcmRequest(req, provider, null, "manual");
    awaitCompletion(f);
    vertx.close();
    return f;
  }

  static final byte[] DCW_BAD_CS = {1, 2, 3, 0, 5, 6, 7, 0, 9, 10, 11, 0, 13, 14, 15, 0};

  @Test
  void dcwDefaultPolicyKeepsLegacyChecksumRequirement() {
    assertTrue(sendWithPolicy(com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT, 0x032830, DCW_BAD_CS).failed());
  }

  @Test
  void dcwScopedIgnoreAppliesOnlyToRealPropagatedProvider() {
    var rule = new com.bowman.cspng.domain.dcw.DcwRule(1L,
        new com.bowman.cspng.domain.dcw.DcwRuleScope(null, 0x0500, 0x032830, 0, null), null,
        com.bowman.cspng.domain.dcw.DcwRuleAction.NO_ACTION, com.bowman.cspng.domain.dcw.ChecksumMode.IGNORE, "", true, 0L, 0L);
    var pol = new com.bowman.cspng.domain.dcw.DcwQualityPolicy(true, false, false,
        com.bowman.cspng.domain.dcw.ChecksumMode.INHERIT, true, List.of(rule));
    assertTrue(sendWithPolicy(pol, 0x032830, DCW_BAD_CS).succeeded(), "provider réel : exception IGNORE appliquée");
    assertTrue(sendWithPolicy(pol, 0x000030, DCW_BAD_CS).failed(), "autre provider : garde historique");
    assertTrue(sendWithPolicy(pol, 0, DCW_BAD_CS).failed(), "provider 0 n'est pas un wildcard");
  }
}
