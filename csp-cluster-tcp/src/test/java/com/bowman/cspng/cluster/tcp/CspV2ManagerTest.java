package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class CspV2ManagerTest {

  private Vertx vertx;
  private Context sharedCtx;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    CompletableFuture<Context> cf = new CompletableFuture<>();
    vertx.getOrCreateContext().runOnContext(v -> cf.complete(Vertx.currentContext()));
    sharedCtx = cf.get(5, TimeUnit.SECONDS);
  }

  @AfterEach
  void tearDown(VertxTestContext ctx) {
    if (vertx != null) vertx.close().onComplete(ctx.succeedingThenComplete());
  }

  // -----------------------------------------------------------------------
  // FakeHandle — pour tests sans CspV2Connection réelle
  // -----------------------------------------------------------------------
  static class FakeHandle implements ConnectionHandle {
    final String peerNodeId;
    final CspV2ConnectionRole role;
    volatile CspV2ConnectionState state = CspV2ConnectionState.PRE_HANDSHAKE;
    volatile boolean closed;
    final AtomicInteger drainCalls = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    final AtomicInteger requestCalls = new AtomicInteger();
    final List<CspV2Payload.EcmRequest> requests = new ArrayList<>();

    FakeHandle(String peerNodeId, CspV2ConnectionRole role) {
      this.peerNodeId = peerNodeId; this.role = role;
    }
    @Override public CspV2ConnectionState state() { return state; }
    @Override public boolean isClosed() { return closed; }
    @Override public String peerNodeId() { return peerNodeId; }
    @Override public CspV2ConnectionRole role() { return role; }
    @Override
    public Future<CspV2Payload.DcwReply> request(CspV2Payload.EcmRequest req) {
      requestCalls.incrementAndGet(); requests.add(req);
      return Future.succeededFuture(new CspV2Payload.DcwReply(
          req.commandTag(), req.serviceId(), req.networkId(), req.caId(), req.dataHash(),
          0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
    }
    @Override public void drain() { drainCalls.incrementAndGet(); }
    @Override public void close() { closeCalls.incrementAndGet(); closed = true; state = CspV2ConnectionState.CLOSED; }
    void setEstablished() { state = CspV2ConnectionState.ESTABLISHED; }
    void setClosed() { closed = true; state = CspV2ConnectionState.CLOSED; }
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------
  private static CspV2Settings testSettings(String local, List<CspV2PeerSettings> peers,
                                            int maxConn, int maxPerNode) {
    return new CspV2Settings(true, local, "0.0.0.0", 9090, 1048576, 1000, maxConn, maxPerNode,
        5000, 100, 5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000,
        100, 30000, 10000, 60000,
        false, "file:cert", "file:key", "file:trust", false, peers);
  }

  private static CspV2PeerSettings peer(String id, String host, int port, List<String> profiles) {
    return new CspV2PeerSettings(true, id, host, port, profiles);
  }

  private static CspV2ConnectionListener noopListener() {
    return new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String p, CspV2Payload.EcmRequest r) {
        return Future.succeededFuture(new CspV2Payload.DcwReply(
            r.commandTag(), r.serviceId(), r.networkId(), r.caId(), r.dataHash(),
            0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
      }
    };
  }

  private CspV2Manager createManager(CspV2Settings s) throws Exception {
    CompletableFuture<CspV2Manager> cf = new CompletableFuture<>();
    sharedCtx.runOnContext(v -> cf.complete(new CspV2Manager(vertx, Vertx.currentContext(), s, noopListener())));
    return cf.get(5, TimeUnit.SECONDS);
  }

  private <T> T onContext(java.util.function.Supplier<T> fn) throws Exception {
    CompletableFuture<T> cf = new CompletableFuture<>();
    sharedCtx.runOnContext(v -> { try { cf.complete(fn.get()); } catch (Exception e) { cf.completeExceptionally(e); } });
    return cf.get(5, TimeUnit.SECONDS);
  }

  private static CspV2Payload.EcmRequest makeEcmRequest(String profile, long maxWaitMs) {
    byte[] ecm = new byte[]{0x01, 0x00, 0x03, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66};
    int hash = ClusterUdpCodec.ecmHash(ecm, 3, ecm.length - 3);
    return new CspV2Payload.EcmRequest(0x80, 100, 200, 0x0B00, hash, profile, ecm, maxWaitMs);
  }

  private static java.util.random.RandomGenerator rngReturning(long v) {
    return new java.util.random.RandomGenerator() {
      @Override public long nextLong() { return v; }
      @Override public long nextLong(long bound) { return Math.min(v, bound - 1); }
    };
  }

  // =======================================================================
  // 1. Limits
  // =======================================================================
  @Test void reserveWithinLimits() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> { assertTrue(m.reserve("b")); assertTrue(m.reserve("b")); assertEquals(2,m.globalCount()); return null; });
  }

  @Test void reserveRejectsGlobalLimit() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 2, 10));
    onContext(() -> { assertTrue(m.reserve("b")); assertTrue(m.reserve("b")); assertFalse(m.reserve("b")); return null; });
  }

  @Test void reserveRejectsPerNodeLimit() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 100, 2));
    onContext(() -> { assertTrue(m.reserve("b")); assertTrue(m.reserve("b")); assertFalse(m.reserve("b")); return null; });
  }

  @Test void releaseDecrementsUnderflowSafe() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b"); m.reserve("b"); assertEquals(2,m.globalCount());
      m.release("b"); assertEquals(1,m.globalCount());
      m.release("b"); assertEquals(0,m.globalCount());
      m.release("b"); assertEquals(0,m.globalCount());
      return null;
    });
  }

  // =======================================================================
  // 2. releaseTracker
  // =======================================================================
  @Test void releaseTrackerIdempotent() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b");
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      m.trackers.put(h,t);
      m.releaseTracker(t, h);
      assertEquals(0, m.globalCount()); assertTrue(t.released); assertFalse(m.trackers.containsKey(h));
      m.releaseTracker(t, h); // idempotent
      assertEquals(0, m.globalCount());
      return null;
    });
  }

  // =======================================================================
  // 3. PeerListener bind-safe
  // =======================================================================
  @Test void bindThenEstablished() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b");
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.PeerListener pl = new CspV2Manager.PeerListener("b", t, m);
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); h.setEstablished();
      pl.bind(h); pl.onEstablished("b");
      assertEquals(h, m.establishedByPeer.get("b"));
      assertTrue(t.established); assertTrue(m.trackers.containsKey(h));
      return null;
    });
  }

  @Test void closeBeforeBindNoLeak() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b"); assertEquals(1,m.globalCount());
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.PeerListener pl = new CspV2Manager.PeerListener("b", t, m);
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR);
      pl.onClosed("b","early"); // before bind
      pl.bind(h);
      assertEquals(0, m.globalCount()); assertTrue(t.released); assertFalse(m.trackers.containsKey(h));
      return null;
    });
  }

  @Test void establishedBeforeBindFinalized() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b");
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.PeerListener pl = new CspV2Manager.PeerListener("b", t, m);
      pl.onEstablished("b"); // before bind
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); h.setEstablished();
      pl.bind(h);
      assertEquals(h, m.establishedByPeer.get("b")); assertTrue(t.established);
      return null;
    });
  }

  // =======================================================================
  // 4. Duplicate → drain new, no reconnect, distinct handles
  // =======================================================================
  @Test void duplicateDrainsNewDistinctHandles() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.reserve("b"); m.reserve("b");
      FakeHandle h1 = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); h1.setEstablished();
      CspV2Manager.ConnectionTracker t1 = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.PeerListener pl1 = new CspV2Manager.PeerListener("b", t1, m);
      pl1.bind(h1); pl1.onEstablished("b");
      assertSame(h1, m.establishedByPeer.get("b"));

      FakeHandle h2 = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); h2.setEstablished();
      CspV2Manager.ConnectionTracker t2 = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.PeerListener pl2 = new CspV2Manager.PeerListener("b", t2, m);
      pl2.bind(h2); pl2.onEstablished("b");
      assertTrue(t2.duplicate);
      assertEquals(1, h2.drainCalls.get());
      assertSame(h1, m.establishedByPeer.get("b")); // original preserved

      m.running = true;
      pl2.onClosed("b","closed");
      // Duplicate → no reconnect
      assertEquals(0, m.reconnectTimers.size());
      return null;
    });
  }

  // =======================================================================
  // 5. dialing / guards
  // =======================================================================
  @Test void dialingGuardAddIfAbsent() throws Exception {
    CspV2PeerSettings p = peer("b","b.ex",9090,List.of("p1"));
    CspV2Settings s = testSettings("a", List.of(p), 10, 5);
    CspV2Manager m = createManager(s);
    onContext(() -> {
      m.running = true;
      m.dialingPeers.add("b");
      m.scheduleConnect(p); // skipped
      m.scheduleReconnect(p); // skipped
      assertEquals(1, m.dialingPeers.size());
      assertEquals(0, m.reconnectScheduled());
      return null;
    });
  }

  @Test void activeEstablishedGuard() throws Exception {
    CspV2PeerSettings p = peer("b","b.ex",9090,List.of("p1"));
    CspV2Manager m = createManager(testSettings("a", List.of(p), 10, 5));
    onContext(() -> {
      m.running = true;
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); h.setEstablished();
      m.establishedByPeer.put("b",h);
      m.scheduleConnect(p);
      assertEquals(0, m.reconnectScheduled());
      return null;
    });
  }

  @Test void timerExistsPreventsConsumingAttempt() throws Exception {
    CspV2PeerSettings p = peer("b","b.ex",9090,List.of("p1"));
    CspV2Manager m = createManager(testSettings("a", List.of(p), 10, 5));
    onContext(() -> {
      m.running = true;
      CspV2ReconnectPolicy pol = CspV2ReconnectPolicy.create(100, 30000, rngReturning(0));
      m.reconnectPolicies.put("b", pol);
      m.reconnectTimers.put("b", 999L);
      int before = pol.attempt();
      m.scheduleReconnect(p); // skipped
      assertEquals(before, pol.attempt()); // attempt not consumed
      return null;
    });
  }

  // =======================================================================
  // 6. Stop → force fallback
  // =======================================================================
  @Test void stopWithHandlesDrainsAndForceCloses(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    sharedCtx.runOnContext(v -> {
      m.running = true;
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      m.trackers.put(h,t);
      m.reserve("b");
      m.establishedByPeer.put("b", h); // simulate established
      m.stop().onComplete(ar -> {
        ctx.verify(() -> {
          assertTrue(ar.succeeded());
          assertTrue(h.drainCalls.get() > 0, "handle drained");
          assertFalse(m.isRunning());
          assertFalse(m.trackers.containsKey(h));
          assertTrue(t.released);
          assertEquals(0, m.globalCount());
        });
        ctx.completeNow();
      });
    });
  }

  @Test void stopForceFallbackClearsEstablished() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    onContext(() -> {
      m.running = true;
      FakeHandle h = new FakeHandle("b", CspV2ConnectionRole.INITIATOR);
      CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
      m.trackers.put(h,t);
      m.reserve("b"); assertEquals(1, m.globalCount());
      m.establishedByPeer.put("b", h);

      // Simuler force release (comme dans shutdown timer)
      m.forceReleaseAll();
      assertTrue(t.released);
      assertEquals(0, m.globalCount());
      assertFalse(m.establishedByPeer.containsKey("b"));
      assertTrue(m.trackers.isEmpty());
      return null;
    });
  }

  // =======================================================================
  // 7. Round-robin
  // =======================================================================
  @Test void requestRoundRobin(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2PeerSettings p1 = peer("b","b.ex",9090,List.of("p1"));
    CspV2PeerSettings p2 = peer("c","c.ex",9090,List.of("p1"));
    CspV2Manager m = createManager(testSettings("a", List.of(p1,p2), 10, 5));
    sharedCtx.runOnContext(v -> {
      FakeHandle hB = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); hB.setEstablished();
      FakeHandle hC = new FakeHandle("c", CspV2ConnectionRole.INITIATOR); hC.setEstablished();
      m.establishedByPeer.put("b",hB); m.establishedByPeer.put("c",hC);
      m.trackers.put(hB, new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR));
      m.trackers.put(hC, new CspV2Manager.ConnectionTracker("c", CspV2ConnectionRole.INITIATOR));

      CspV2Payload.EcmRequest req = makeEcmRequest("p1", 5000);
      m.doRequest(req).onComplete(ar1 -> ctx.verify(() -> {
        assertTrue(ar1.succeeded()); assertEquals(1,hB.requestCalls.get()); assertEquals(0,hC.requestCalls.get());
      }));
      m.doRequest(req).onComplete(ar2 -> ctx.verify(() -> {
        assertTrue(ar2.succeeded()); assertEquals(1,hB.requestCalls.get()); assertEquals(1,hC.requestCalls.get());
      }));
      m.doRequest(req).onComplete(ar3 -> ctx.verify(() -> {
        assertTrue(ar3.succeeded()); assertEquals(2,hB.requestCalls.get()); assertEquals(1,hC.requestCalls.get());
        ctx.completeNow();
      }));
    });
  }

  @Test void requestWithPeerRoundRobin(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2PeerSettings p1 = peer("b","b.ex",9090,List.of("p1"));
    CspV2PeerSettings p2 = peer("c","c.ex",9090,List.of("p1"));
    CspV2Manager m = createManager(testSettings("a", List.of(p1,p2), 10, 5));
    sharedCtx.runOnContext(v -> {
      FakeHandle hB = new FakeHandle("b", CspV2ConnectionRole.INITIATOR); hB.setEstablished();
      FakeHandle hC = new FakeHandle("c", CspV2ConnectionRole.INITIATOR); hC.setEstablished();
      m.establishedByPeer.put("b",hB); m.establishedByPeer.put("c",hC);
      m.trackers.put(hB, new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR));
      m.trackers.put(hC, new CspV2Manager.ConnectionTracker("c", CspV2ConnectionRole.INITIATOR));

      CspV2Payload.EcmRequest req = makeEcmRequest("p1", 5000);
      m.requestWithPeer(req).onComplete(ar1 -> ctx.verify(() -> {
        assertTrue(ar1.succeeded());
        assertEquals("b", ar1.result().peerNodeId());
        assertNotNull(ar1.result().reply());
        assertEquals(1,hB.requestCalls.get());
      }));
      m.requestWithPeer(req).onComplete(ar2 -> ctx.verify(() -> {
        assertTrue(ar2.succeeded());
        assertEquals("c", ar2.result().peerNodeId());
        assertEquals(1,hC.requestCalls.get());
      }));
      m.requestWithPeer(req).onComplete(ar3 -> ctx.verify(() -> {
        assertTrue(ar3.succeeded());
        // Back to b (round-robin: b,c,b)
        assertEquals("b", ar3.result().peerNodeId());
        assertEquals(2,hB.requestCalls.get()); assertEquals(1,hC.requestCalls.get());
        ctx.completeNow();
      }));
    });
  }

  @Test void requestFailsNoPeer(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(), 10, 5));
    sharedCtx.runOnContext(v ->
      m.doRequest(makeEcmRequest("p1",5000)).onComplete(ar ->
        ctx.verify(() -> { assertTrue(ar.failed()); assertTrue(ar.cause().getMessage().contains("no peer")); ctx.completeNow(); })));
  }

  // =======================================================================
  // 8. onEcmRequest delegateEcm catch
  // =======================================================================
  @Test void onEcmRequestDelegatesToAppListener(Vertx vertx, VertxTestContext ctx) throws Exception {
    AtomicInteger called = new AtomicInteger();
    CspV2ConnectionListener countingListener = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String p, CspV2Payload.EcmRequest r) {
        called.incrementAndGet();
        return Future.succeededFuture(new CspV2Payload.DcwReply(
            r.commandTag(), r.serviceId(), r.networkId(), r.caId(), r.dataHash(),
            0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], ""));
      }
    };
    CompletableFuture<CspV2Manager> cf = new CompletableFuture<>();
    sharedCtx.runOnContext(v -> cf.complete(new CspV2Manager(vertx, Vertx.currentContext(),
        testSettings("a", List.of(), 10, 5), countingListener)));
    CspV2Manager m = cf.get(5, TimeUnit.SECONDS);

    CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
    CspV2Manager.PeerListener pl = new CspV2Manager.PeerListener("b", t, m);
    CspV2Payload.EcmRequest req = makeEcmRequest("p1", 5000);

    pl.onEcmRequest("b", req).onComplete(ar -> {
      ctx.verify(() -> { assertTrue(ar.succeeded()); assertEquals(1, called.get()); });
      ctx.completeNow();
    });
  }

  @Test void onEcmRequestCatchesThrow(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2ConnectionListener throwingListener = new CspV2ConnectionListener() {
      @Override public Future<CspV2Payload.DcwReply> onEcmRequest(String p, CspV2Payload.EcmRequest r) {
        throw new RuntimeException("boom");
      }
    };
    CompletableFuture<CspV2Manager> cf = new CompletableFuture<>();
    sharedCtx.runOnContext(v -> cf.complete(new CspV2Manager(vertx, Vertx.currentContext(),
        testSettings("a", List.of(), 10, 5), throwingListener)));
    CspV2Manager m = cf.get(5, TimeUnit.SECONDS);

    CspV2Manager.ConnectionTracker t = new CspV2Manager.ConnectionTracker("b", CspV2ConnectionRole.INITIATOR);
    CspV2Manager.PeerListener pl = new CspV2Manager.PeerListener("b", t, m);

    pl.onEcmRequest("b", makeEcmRequest("p1", 5000)).onComplete(ar -> {
      ctx.verify(() -> assertTrue(ar.failed()));
      ctx.completeNow();
    });
  }

  // =======================================================================
  // 9. Foreign context marshal
  // =======================================================================
  @Test void foreignContextViaSecondVertx(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(), 10, 5));
    // VertxExtension provides a separate vertx instance in test method parameter
    // Call from the extension's vertx context → should marshal correctly
    Vertx foreign = Vertx.vertx();
    try {
      foreign.getOrCreateContext().runOnContext(v -> {
        assertNotSame(m.context, Vertx.currentContext());
        m.request(makeEcmRequest("p1", 5000)).onComplete(ar -> {
          ctx.verify(() -> assertTrue(ar.failed())); // no peer → failed, but marshal worked
          ctx.completeNow();
        });
      });
    } finally {
      foreign.close();
    }
  }

  // =======================================================================
  // 10. Context detection + snapshot
  // =======================================================================
  @Test void onManagerContextDetection(Vertx vertx, VertxTestContext ctx) throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(), 10, 5));
    assertFalse(m.onManagerContext()); // from test thread
    sharedCtx.runOnContext(v -> {
      ctx.verify(() -> assertTrue(m.onManagerContext()));
      ctx.completeNow();
    });
  }

  @Test void snapshotReflectsState() throws Exception {
    CspV2Manager m = createManager(testSettings("a", List.of(peer("b","b.ex",9090,List.of("p1"))), 10, 5));
    CspV2Manager.CspV2ManagerSnapshot s = m.snapshot();
    assertNotNull(s); assertFalse(s.running()); assertEquals(0, s.activeConnections());
    onContext(() -> { m.reserve("b"); m.updateSnapshot(); assertEquals(1, m.snapshot().activeConnections()); return null; });
  }
}
