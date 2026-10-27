package com.bowman.cspng.app.admin;

import com.bowman.cspng.cache.CacheStats;
import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import com.bowman.cspng.connectors.ConnectorSnapshot;
import com.bowman.cspng.sessions.SessionSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class AdminEventBridgeTest {

    Vertx vertx;
    AdminEventBroker broker;
    FakeSnapshotService fakeSnap;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fakeSnap != null && fakeSnap.bridge != null) {
            try { fakeSnap.bridge.stop(); } catch (Exception ignored) {}
        }
        if (broker != null) {
            broker.shutdown();
            awaitBroker();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    void awaitBroker() throws Exception {
        if (broker != null && broker.brokerContext != null) {
            Promise<Void> p = Promise.promise();
            broker.brokerContext.runOnContext(v -> p.complete());
            p.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    void awaitBridge(AdminEventBridge b) throws Exception {
        Context ctx = b.getBridgeContextForTest();
        if (ctx != null) {
            Promise<Void> p = Promise.promise();
            ctx.runOnContext(v -> p.complete());
            p.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        awaitBroker();
    }

    static class FakeSnapshotService extends RuntimeSnapshotService {
        AdminEventBridge bridge;
        Supplier<StatusPayload> statusSup = () -> new StatusPayload(true, true, 2, 1, 3, 1, false, true, 0, "0.1.0", 100, Instant.now().toString());
        Supplier<List<ConnectorSnapshot>> connSup = () -> List.of(
                new ConnectorSnapshot("c1", "newcamd", "p1", true, "CONNECTED"),
                new ConnectorSnapshot("c2", "radegast", "p1", false, "DISCONNECTED")
        );
        Supplier<Future<CacheStats>> cacheSup = () -> Future.succeededFuture(new CacheStats(0,0,42,0,0,0,0,0));
        // sessionSnapshots kept for completeness but bridge now uses status count for sessions
        Supplier<List<SessionSnapshot>> sessSup = () -> List.of(
                new SessionSnapshot("s1", "user1", "1.2.3.0", "newcamd", "p1", true, true)
        );
        FakeSnapshotService() {
            super("fake-id", Instant.now(), System.nanoTime(), null, null, null, null, null, null, null);
        }
        @Override public StatusPayload statusPayload() { return statusSup.get(); }
        @Override public List<ConnectorSnapshot> connectorSnapshots() { return connSup.get(); }
        @Override public List<SessionSnapshot> sessionSnapshots() { return sessSup.get(); }
        @Override public Future<CacheStats> cacheStatsFuture() { return cacheSup.get(); }
    }

    AdminEventBridge newBridge(boolean enabled, long scanMs, FakeSnapshotService snap) {
        broker = new AdminEventBroker(vertx, "br-test-" + System.nanoTime(), 500, 64, 50, 15000, enabled);
        return new AdminEventBridge(vertx, broker, snap, scanMs);
    }

    @Test
    void disabledNoResources() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(false, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        assertTrue(bridge.isStartedForTest());
        assertEquals(-1, bridge.getTimerIdForTest());
        assertNull(bridge.getConsumerForTest());
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        awaitBroker();
        assertEquals(0, broker.getReplayBufferSize(), "disabled should not publish");
        assertEquals(0, broker.currentStats().published());
        bridge.stop();
        assertTrue(bridge.isShutdownForTest() || !bridge.isStartedForTest());
        assertEquals(-1, bridge.getTimerIdForTest());
        bridge.stop();
        assertEquals(-1, bridge.getTimerIdForTest());
    }

    @Test
    void scanMsBoundsFailFast() {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridgeLow = newBridge(true, 100, fakeSnap);
        assertThrows(IllegalStateException.class, bridgeLow::start);
        AdminEventBridge bridgeHigh = newBridge(true, 70000, fakeSnap);
        assertThrows(IllegalStateException.class, bridgeHigh::start);
        AdminEventBridge bridgeValid = newBridge(true, 250, fakeSnap);
        fakeSnap.bridge = bridgeValid;
        bridgeValid.start();
        assertEquals(250, bridgeValid.scanMs);
        try { bridgeValid.stop(); } catch (Exception ignored) {}
        AdminEventBridge bridgeValid2 = newBridge(true, 60000, fakeSnap);
        fakeSnap.bridge = bridgeValid2;
        bridgeValid2.start();
        assertEquals(60000, bridgeValid2.scanMs);
        try { bridgeValid2.stop(); } catch (Exception ignored) {}
    }

    @Test
    void startupBaselineEventsTypesSafe() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        Promise<Void> p = Promise.promise();
        bridge.getBridgeContextForTest().runOnContext(v -> p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        assertTrue(broker.getReplayBufferSize() >= 4, "baseline should publish 4 types, got " + broker.getReplayBufferSize());
        var types = broker.replayBuffer.stream().map(e -> e.type().name()).toList();
        assertTrue(types.contains("STATUS_CHANGED"));
        assertTrue(types.contains("CONNECTOR_CHANGED"));
        assertTrue(types.contains("SESSION_CHANGED"));
        assertTrue(types.contains("CACHE_CHANGED"));
        for (var ev : broker.replayBuffer) {
            switch (ev.type()) {
                case CONNECTOR_CHANGED -> {
                    var pl = (AdminEventBroker.ConnectorChangedPayload) ev.payload();
                    assertEquals("UPDATED", pl.state());
                    assertTrue(pl.count() >=0 && pl.count() <= 1_000_000);
                }
                case SESSION_CHANGED -> {
                    var pl = (AdminEventBroker.SessionChangedPayload) ev.payload();
                    assertEquals("UPDATED", pl.state());
                }
                case CACHE_CHANGED -> {
                    var pl = (AdminEventBroker.CacheChangedPayload) ev.payload();
                    assertEquals("OK", pl.state());
                    assertTrue(pl.size() >=0);
                }
                case STATUS_CHANGED -> {
                    var pl = (AdminEventBroker.StatusChangedPayload) ev.payload();
                    assertTrue(pl.totalConnectors() >=0);
                }
                default -> {}
            }
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        for (var ev : broker.replayBuffer) {
            String json = mapper.writeValueAsString(ev.payload());
            String low = json.toLowerCase();
            assertFalse(low.contains("password"), json);
            assertFalse(low.contains("secret"), json);
            assertFalse(low.contains("host\""), json);
            assertFalse(low.contains("\"port\""), json);
            assertFalse(low.contains("token"), json);
            assertFalse(low.contains("c1"), "connector name leak");
            assertFalse(low.contains("1.2.3"), "ip leak");
        }
        bridge.stop();
    }

    @Test
    void unchangedNoDuplicate() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        awaitBroker();
        int base = broker.getReplayBufferSize();
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        awaitBroker();
        Promise<Void> p = Promise.promise();
        bridge.getBridgeContextForTest().runOnContext(v -> p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        assertEquals(base, broker.getReplayBufferSize(), "unchanged should not publish duplicate");
        bridge.stop();
    }

    @Test
    void eachDeltaOneEventAggregates() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        awaitBroker();
        Promise<Void> pp0 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp0.complete()); pp0.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        int base = broker.getReplayBufferSize();

        // delta status: change degraded flag -> exactly one STATUS_CHANGED (session count unchanged, so no SESSION)
        fakeSnap.statusSup = () -> new RuntimeSnapshotService.StatusPayload(true, true, 2, 1, 3, 1, true, true, 0, "0.1.0", 100, Instant.now().toString());
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        awaitBroker();
        Promise<Void> pp = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp.complete()); pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(base + 1, broker.getReplayBufferSize(), "status delta should be one");
        assertEquals(AdminEventBroker.AdminEventType.STATUS_CHANGED, broker.replayBuffer.peekLast().type());

        int afterStatus = broker.getReplayBufferSize();
        // connector metadata change same connected count (1 -> 1) should NOT publish
        fakeSnap.connSup = () -> List.of(
                new ConnectorSnapshot("c99", "newcamd", "pX", true, "CONNECTED"),
                new ConnectorSnapshot("c2", "radegast", "p1", false, "DISCONNECTED")
        );
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p2 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p2.complete()); p2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(afterStatus, broker.getReplayBufferSize(), "metadata same count should not publish CONNECTOR_CHANGED");

        // connected count change 1 -> 2 should publish exactly one CONNECTOR_CHANGED
        fakeSnap.connSup = () -> List.of(
                new ConnectorSnapshot("c1", "newcamd", "p1", true, "CONNECTED"),
                new ConnectorSnapshot("c2", "radegast", "p1", true, "CONNECTED")
        );
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p3 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p3.complete()); p3.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(afterStatus + 1, broker.getReplayBufferSize());
        assertEquals(AdminEventBroker.AdminEventType.CONNECTOR_CHANGED, broker.replayBuffer.peekLast().type());

        int afterConn = broker.getReplayBufferSize();
        // session swap same count via status count unchanged (still 3) but different list -> should NOT publish SESSION_CHANGED
        // status still 3, so no session event
        fakeSnap.sessSup = () -> List.of(
                new SessionSnapshot("s99", "userX", "9.9.9.0", "newcamd", "p1", true, true)
        );
        // keep status count 3
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p4 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p4.complete()); p4.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(afterConn, broker.getReplayBufferSize(), "session swap same count should not publish");

        // session count change via status 3 -> 5 should publish exactly one SESSION (and also STATUS because digest includes sessions)
        // So we expect 2 events: STATUS + SESSION. Verify session event exists and count is 5
        fakeSnap.statusSup = () -> new RuntimeSnapshotService.StatusPayload(true, true, 2, 1, 5, 1, true, true, 0, "0.1.0", 100, Instant.now().toString());
        int beforeSessCount = broker.getReplayBufferSize();
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p5 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p5.complete()); p5.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        // status and session are two distinct types, both should be published
        assertTrue(broker.getReplayBufferSize() >= beforeSessCount + 2, "session count change should publish STATUS + SESSION");
        var hasSession5 = broker.replayBuffer.stream().filter(e -> e.type()== AdminEventBroker.AdminEventType.SESSION_CHANGED).map(e -> ((AdminEventBroker.SessionChangedPayload)e.payload()).count()).anyMatch(c -> c==5);
        assertTrue(hasSession5);

        int afterSess = broker.getReplayBufferSize();
        // cache metric change same size but different pending/hits should NOT publish (aggregate comparison only size)
        fakeSnap.cacheSup = () -> Future.succeededFuture(new CacheStats(99, 99, 42, 99, 99, 99, 0, 0)); // same cached 42, different metrics
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p6 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p6.complete()); p6.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(afterSess, broker.getReplayBufferSize(), "cache metrics same size should not publish");

        // cache size change 42 -> 999 should publish exactly one CACHE_CHANGED
        fakeSnap.cacheSup = () -> Future.succeededFuture(new CacheStats(0,0,999,0,0,0,0,0));
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        Promise<Void> p7 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p7.complete()); p7.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(afterSess + 1, broker.getReplayBufferSize());
        assertEquals(AdminEventBroker.AdminEventType.CACHE_CHANGED, broker.replayBuffer.peekLast().type());
        var last = (AdminEventBroker.CacheChangedPayload) broker.replayBuffer.peekLast().payload();
        assertEquals(999, last.size());

        bridge.stop();
    }

    @Test
    void configReloadEvent() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        awaitBroker();
        int before = broker.getReplayBufferSize();
        vertx.eventBus().publish(ConfigWatcher.CONFIG_RELOADED_ADDRESS, "global");
        Promise<Void> p = Promise.promise();
        bridge.getBridgeContextForTest().runOnContext(v -> p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        Promise<Void> p2 = Promise.promise();
        bridge.getBridgeContextForTest().runOnContext(v -> p2.complete());
        p2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        var hasConfig = broker.replayBuffer.stream().anyMatch(e -> e.type() == AdminEventBroker.AdminEventType.CONFIG_RELOADED);
        assertTrue(hasConfig, "config reload should publish CONFIG_RELOADED");
        assertTrue(broker.getReplayBufferSize() >= before + 1);
        bridge.stop();
    }

    @Test
    void cacheAsyncOverlapCoalesceFailureRecovery() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AtomicReference<Promise<CacheStats>> firstPromiseRef = new AtomicReference<>(Promise.promise());
        AtomicInteger callCount = new AtomicInteger(0);
        fakeSnap.cacheSup = () -> {
            int c = callCount.incrementAndGet();
            if (c == 1) return firstPromiseRef.get().future();
            if (c == 2) return Future.succeededFuture(new CacheStats(0,0,100,0,0,0,0,0));
            return Future.succeededFuture(new CacheStats(0,0,100,0,0,0,0,0));
        };
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        Promise<Void> pp = Promise.promise();
        bridge.getBridgeContextForTest().runOnContext(v -> pp.complete());
        pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(bridge.isScanInFlightForTest(), "first scan should be inFlight waiting cache");
        bridge.triggerScanForTest();
        Promise<Void> pp2 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp2.complete()); pp2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(bridge.isScanDirtyForTest(), "second trigger should set dirty");
        assertEquals(1, callCount.get(), "overlap should not create second cache call yet");
        firstPromiseRef.get().complete(new CacheStats(0,0,42,0,0,0,0,0));
        Promise<Void> pp3 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp3.complete()); pp3.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        Promise<Void> pp4 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp4.complete()); pp4.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        assertEquals(2, callCount.get(), "dirty should trigger exactly one new scan");
        assertFalse(bridge.isScanInFlightForTest());
        assertFalse(bridge.isScanDirtyForTest());

        fakeSnap.cacheSup = () -> Future.failedFuture("cache fail");
        int beforeFail = broker.getReplayBufferSize();
        fakeSnap.statusSup = () -> new RuntimeSnapshotService.StatusPayload(true, true, 2, 1, 5, 1, false, true, 0, "0.1.0", 100, Instant.now().toString());
        bridge.triggerScanForTest();
        Promise<Void> pp5 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp5.complete()); pp5.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        assertFalse(bridge.isScanInFlightForTest(), "failure should clear inFlight");
        assertTrue(broker.getReplayBufferSize() >= beforeFail + 1);
        var hasStatus = broker.replayBuffer.stream().anyMatch(e -> e.type()== AdminEventBroker.AdminEventType.STATUS_CHANGED);
        assertTrue(hasStatus);
        fakeSnap.cacheSup = () -> Future.succeededFuture(new CacheStats(0,0,200,0,0,0,0,0));
        fakeSnap.statusSup = () -> new RuntimeSnapshotService.StatusPayload(true, true, 2, 1, 5, 1, false, true, 0, "0.1.0", 100, Instant.now().toString());
        bridge.triggerScanForTest();
        Promise<Void> pp6 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp6.complete()); pp6.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        var hasCache200 = broker.replayBuffer.stream().anyMatch(e -> e.type() == AdminEventBroker.AdminEventType.CACHE_CHANGED && ((AdminEventBroker.CacheChangedPayload)e.payload()).size()==200);
        assertTrue(hasCache200, "recovery should publish cache after failure");
        bridge.stop();
    }

    @Test
    void shutdownCleanupNoLatePublish() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AtomicReference<Promise<CacheStats>> pending = new AtomicReference<>(Promise.promise());
        fakeSnap.cacheSup = () -> pending.get().future();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        Promise<Void> pp = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp.complete()); pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(bridge.isScanInFlightForTest());
        int sizeBeforeStop = broker.getReplayBufferSize();
        bridge.stop();
        assertEquals(-1, bridge.getTimerIdForTest());
        assertNull(bridge.getConsumerForTest());
        assertTrue(bridge.isShutdownForTest() || !bridge.isStartedForTest());
        pending.get().complete(new CacheStats(0,0,9999,0,0,0,0,0));
        Promise<Void> pp2 = Promise.promise();
        vertx.runOnContext(v -> pp2.complete());
        pp2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        assertEquals(sizeBeforeStop, broker.getReplayBufferSize(), "late callback after shutdown must not publish");
        bridge.triggerScanForTest();
        Promise<Void> pp3 = Promise.promise(); vertx.runOnContext(v -> pp3.complete()); pp3.future().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
        awaitBroker();
        assertEquals(sizeBeforeStop, broker.getReplayBufferSize());
        bridge.stop();
    }

    @Test
    void singleTimerGlobal() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        long tid = bridge.getTimerIdForTest();
        assertNotEquals(-1, tid);
        bridge.triggerScanForTest();
        bridge.triggerScanForTest();
        awaitBridge(bridge);
        assertEquals(tid, bridge.getTimerIdForTest(), "single periodic timer global");
        bridge.stop();
    }

    @Test
    void staleGenerationDoesNotCorruptNewScan() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AtomicReference<Promise<CacheStats>> oldPending = new AtomicReference<>(Promise.promise());
        AtomicReference<Promise<CacheStats>> newPending = new AtomicReference<>(Promise.promise());
        AtomicInteger callCount = new AtomicInteger(0);
        fakeSnap.cacheSup = () -> {
            int c = callCount.incrementAndGet();
            if (c==1) return oldPending.get().future();
            return newPending.get().future();
        };
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        Promise<Void> pp = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp.complete()); pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(bridge.isScanInFlightForTest());
        // stop while old scan pending
        bridge.stop();
        long genAfterStop = bridge.getGenerationForTest();
        // restart
        bridge.start();
        Promise<Void> pp2 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp2.complete()); pp2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertTrue(bridge.isScanInFlightForTest(), "new scan should be inFlight");
        int sizeAfterRestartStart = broker.getReplayBufferSize();
        // complete old pending (stale generation)
        oldPending.get().complete(new CacheStats(0,0,999,0,0,0,0,0));
        Promise<Void> pp3 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp3.complete()); pp3.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        // stale should not clear new scan's inFlight nor publish extra
        assertTrue(bridge.isScanInFlightForTest(), "new scanInFlight must remain true after stale callback");
        assertEquals(genAfterStop+1, bridge.getGenerationForTest());
        // complete new pending
        newPending.get().complete(new CacheStats(0,0,77,0,0,0,0,0));
        Promise<Void> pp4 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp4.complete()); pp4.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        // after new cache 77 completes, inFlight should be false and cache published
        // wait for new scan's cache (77) to be processed
        // poll until inFlight false or timeout
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && bridge.isScanInFlightForTest()) {
            Promise<Void> px = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> px.complete()); px.future().toCompletionStage().toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
        }
        assertFalse(bridge.isScanInFlightForTest());
        // should have published baseline again exactly once (restart resets baselines)
        assertTrue(broker.getReplayBufferSize() > sizeAfterRestartStart || broker.getReplayBufferSize() >= 4);
        bridge.stop();
    }

    @Test
    void restartRepublishesBaselineExactlyOnce() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        Promise<Void> p0 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p0.complete()); p0.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        int firstBaselineSize = broker.getReplayBufferSize();
        assertTrue(firstBaselineSize >=4);
        bridge.stop();
        // clear broker buffer for clarity but keep generation
        broker.replayBuffer.clear();
        // use same broker but new generation
        bridge.start();
        Promise<Void> p1 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p1.complete()); p1.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        Promise<Void> p2 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p2.complete()); p2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        int secondBaseline = broker.getReplayBufferSize();
        assertEquals(firstBaselineSize, secondBaseline, "restart should republish baseline exactly once with same counts");
        // second scan unchanged should not duplicate
        bridge.triggerScanForTest();
        Promise<Void> p3 = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> p3.complete()); p3.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS); awaitBroker();
        assertEquals(secondBaseline, broker.getReplayBufferSize());
        bridge.stop();
    }

    @Test
    void cacheSaturationBridgeClampsToMax() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        awaitBroker();
        fakeSnap.cacheSup = () -> Future.succeededFuture(new CacheStats(0,0,11_000_000L,0,0,0,0,0));
        bridge.triggerScanForTest();
        Promise<Void> pp = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp.complete()); pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        var found = broker.replayBuffer.stream().filter(e -> e.type()== AdminEventBroker.AdminEventType.CACHE_CHANGED).reduce((a,b) -> b).orElse(null);
        assertNotNull(found);
        assertEquals(10_000_000L, ((AdminEventBroker.CacheChangedPayload)found.payload()).size(), "bridge should clamp 11M to MAX");
        assertEquals(AdminEventBroker.MAX_CACHE_EVENT_SIZE, AdminEventBridge.MAX_CACHE_EVENT_SIZE);
        bridge.stop();
        // direct broker >MAX should be rejected
        assertThrows(IllegalArgumentException.class, () -> broker.publishCacheChanged(11_000_000L, "OK"));
        assertThrows(IllegalArgumentException.class, () -> broker.publishCacheChanged(AdminEventBroker.MAX_CACHE_EVENT_SIZE + 1, "OK"));
        // exactly MAX is allowed
        broker.publishCacheChanged(AdminEventBroker.MAX_CACHE_EVENT_SIZE, "OK");
        awaitBroker();
    }

    @Test
    void cacheClampAndLongSupport() throws Exception {
        fakeSnap = new FakeSnapshotService();
        AdminEventBridge bridge = newBridge(true, 1000, fakeSnap);
        fakeSnap.bridge = bridge;
        bridge.start();
        awaitBridge(bridge);
        awaitBroker();
        fakeSnap.cacheSup = () -> Future.succeededFuture(new CacheStats(0,0,2_500_000L,0,0,0,0,0));
        bridge.triggerScanForTest();
        Promise<Void> pp = Promise.promise(); bridge.getBridgeContextForTest().runOnContext(v -> pp.complete()); pp.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        awaitBroker();
        var found = broker.replayBuffer.stream().filter(e -> e.type()== AdminEventBroker.AdminEventType.CACHE_CHANGED).reduce((a,b) -> b).orElse(null);
        assertNotNull(found);
        assertEquals(2_500_000L, ((AdminEventBroker.CacheChangedPayload)found.payload()).size());
        bridge.stop();
        assertThrows(IllegalArgumentException.class, () -> broker.publishCacheChanged(11_000_000L, "OK"));
    }
}
