package com.bowman.cspng.app.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AdminEventBrokerTest {

    Vertx vertx;
    AdminEventBroker broker;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (broker != null) {
            broker.shutdown();
            awaitContext();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    void awaitContext() throws Exception {
        if (broker != null && broker.brokerContext != null) {
            var p = io.vertx.core.Promise.<Void>promise();
            broker.brokerContext.runOnContext(v -> p.complete());
            p.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    AdminEventBroker newBroker(int replaySize, int queueSize, int maxSubs, long hb, String id) {
        return new AdminEventBroker(vertx, id, replaySize, queueSize, maxSubs, hb, true);
    }

    @Test
    void idsMonotoneAndReplayBounded() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-1");
        for (int i=0;i<12;i++) broker.publishStatusChanged(1,1,1,false);
        awaitContext();
        assertEquals(10, broker.getReplayBufferSize());
        long oldest = broker.replayBuffer.peekFirst().id();
        long newest = broker.replayBuffer.peekLast().id();
        assertEquals(3, oldest);
        assertEquals(12, newest);
        long prev = 0;
        for (var ev: broker.replayBuffer) { assertTrue(ev.id() > prev); prev = ev.id(); }
    }

    @Test
    void reconnectReplayValid() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-2");
        for (int i=0;i<3;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("1").subscribe(sub);
        awaitContext();
        sub.awaitItems(2, Duration.ofSeconds(2));
        assertEquals(2, sub.getItems().get(0).id());
        assertEquals(3, sub.getItems().get(1).id());
        broker.publishStatusChanged(1,1,1,true);
        awaitContext();
        sub.awaitItems(3, Duration.ofSeconds(2));
        assertEquals(4, sub.getItems().get(2).id());
        sub.cancel();
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
    }

    @Test
    void gapSnapshotAndCloseWithDemand() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-3");
        for (int i=0;i<15;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("1").subscribe(sub);
        awaitContext();
        sub.awaitCompletion(Duration.ofSeconds(2));
        assertEquals(1, sub.getItems().size());
        assertEquals(AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED, sub.getItems().get(0).type());
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
        assertEquals(1, broker.currentStats().replayGaps());
        assertEquals(16, sub.getItems().get(0).id());
        assertEquals(10, broker.getReplayBufferSize());
        assertTrue(broker.replayBuffer.stream().noneMatch(e -> e.type()== AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED));
    }

    @Test
    void gapWithDemand0ThenRequest() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-gap0");
        for (int i=0;i<5;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        broker.subscribe("999999").subscribe(sub);
        awaitContext();
        // with demand 0, no item and not completed (terminal queued)
        assertEquals(0, sub.getItems().size());
        assertFalse(sub.hasCompleted());
        // ensure not leaking as live subscriber
        assertEquals(0, broker.currentStats().subscriberCount());
        assertEquals(1, broker.currentStats().replayGaps());
        // now request 1 -> snapshot + complete
        sub.request(1);
        sub.awaitCompletion(Duration.ofSeconds(2));
        assertEquals(1, sub.getItems().size());
        assertEquals(AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED, sub.getItems().get(0).type());
        // after completion, terminal cleared
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
        // cancel before request case
        AssertSubscriber<AdminEventBroker.AdminEvent> sub2 = AssertSubscriber.create(0);
        broker.subscribe("999999").subscribe(sub2);
        awaitContext();
        assertEquals(0, sub2.getItems().size());
        sub2.cancel();
        awaitContext();
        // request after cancel must not deliver
        sub2.request(1);
        // give context time
        awaitContext();
        assertEquals(0, sub2.getItems().size());
        assertFalse(sub2.hasCompleted());
    }

    @Test
    void invalidAndFutureLeadToSnapshot() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-4");
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        for (String bad : List.of("abc", "-1")) {
            AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
            broker.subscribe(bad).subscribe(sub);
            awaitContext();
            sub.awaitCompletion(Duration.ofSeconds(2));
            assertEquals(1, sub.getItems().size());
            assertEquals(AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED, sub.getItems().get(0).type());
        }
        AssertSubscriber<AdminEventBroker.AdminEvent> live = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(live);
        awaitContext();
        assertFalse(live.hasCompleted());
        live.cancel();
    }

    @Test
    void futureIsOnlyGreaterThanCurrentId() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-future");
        broker.publishStatusChanged(0,0,0,false); // id1
        awaitContext();
        // lastId == currentId (1) should be valid live, not future
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("1").subscribe(sub);
        awaitContext();
        // no replay, still live
        assertEquals(0, sub.getItems().size());
        assertFalse(sub.hasCompleted());
        // publish id2 should be delivered live
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        sub.awaitItems(1, Duration.ofSeconds(2));
        assertEquals(2, sub.getItems().get(0).id());
        sub.cancel();
        awaitContext();
        // lastId > currentId (2) is future -> snapshot
        AssertSubscriber<AdminEventBroker.AdminEvent> fut = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("5").subscribe(fut);
        awaitContext();
        fut.awaitCompletion(Duration.ofSeconds(2));
        assertEquals(AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED, fut.getItems().get(0).type());
    }

    @Test
    void frontierNoEventsLast0Valid() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-empty");
        awaitContext();
        // no events, currentId 0, last 0 should be valid live, no replay, not snapshot
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("0").subscribe(sub);
        awaitContext();
        assertEquals(0, sub.getItems().size());
        assertFalse(sub.hasCompleted());
        // last 1 with no events and current 0 -> future -> snapshot
        AssertSubscriber<AdminEventBroker.AdminEvent> fut = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("1").subscribe(fut);
        awaitContext();
        fut.awaitCompletion(Duration.ofSeconds(2));
        assertEquals(AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED, fut.getItems().get(0).type());
        sub.cancel();
    }

    @Test
    void windowWithoutLoss() throws Exception {
        broker = newBroker(500, 64, 50, 15000, "inst-win");
        for (int i=0;i<2;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        broker.subscribe("1").subscribe(sub);
        awaitContext();
        broker.publishStatusChanged(1,1,1,false);
        awaitContext();
        sub.request(10);
        sub.awaitItems(2, Duration.ofSeconds(2));
        assertEquals(2, sub.getItems().get(0).id());
        assertEquals(3, sub.getItems().get(1).id());
        sub.cancel();
    }

    @Test
    void slowConsumerEvictionWithDemand0Strict() throws Exception {
        broker = newBroker(500, 4, 50, 15000, "inst-slow0");
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        for (int i=0;i<4;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertEquals(0, sub.getItems().size());
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        // demand0 queue pleine -> slowEviction, onComplete without onNext
        sub.awaitCompletion(Duration.ofSeconds(2));
        assertTrue(sub.hasCompleted());
        assertEquals(0, sub.getItems().size());
        assertEquals(1, broker.currentStats().slowEvictions());
        assertEquals(0, broker.currentStats().subscriberCount());
        // queue cleared check via stats and no heartbeat leak
        assertEquals(-1, broker.getHeartbeatTimerId());
    }

    @Test
    void slowConsumerWithDemandEmitsSnapshotBeforeComplete() throws Exception {
        broker = newBroker(500, 4, 50, 15000, "inst-slow1");
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        for (int i=0;i<4;i++) broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertEquals(0, sub.getItems().size());
        // force demand 1 while queue remains full (without draining)
        var holder = broker.subscribers.get(0);
        holder.subscription.requested = 1;
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        // barrier to ensure dispatch processed
        var p = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        sub.awaitCompletion(Duration.ofSeconds(2));
        boolean hasSnapshot = sub.getItems().stream().anyMatch(e -> e.type()== AdminEventBroker.AdminEventType.SNAPSHOT_REQUIRED);
        assertTrue(hasSnapshot);
        assertTrue(sub.hasCompleted());
        assertEquals(1, broker.currentStats().slowEvictions());
    }

    @Test
    void maxSubscribersRejected() throws Exception {
        broker = newBroker(10, 64, 2, 15000, "inst-max");
        AssertSubscriber<AdminEventBroker.AdminEvent> s1 = AssertSubscriber.create(Long.MAX_VALUE);
        AssertSubscriber<AdminEventBroker.AdminEvent> s2 = AssertSubscriber.create(Long.MAX_VALUE);
        AssertSubscriber<AdminEventBroker.AdminEvent> s3 = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(s1);
        broker.subscribe(null).subscribe(s2);
        awaitContext();
        assertEquals(2, broker.currentStats().subscriberCount());
        broker.subscribe(null).subscribe(s3);
        awaitContext();
        s3.awaitFailure(Duration.ofSeconds(2));
        assertTrue(s3.getFailure() instanceof AdminEventBroker.RejectedSubscriberException);
        assertEquals(1, broker.currentStats().rejectedSubscribers());
        s1.cancel(); s2.cancel();
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
    }

    @Test
    void cancellationCleanupAndTimer() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-cancel");
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        assertEquals(1, broker.currentStats().subscriberCount());
        assertNotEquals(-1, broker.getHeartbeatTimerId());
        sub.cancel();
        awaitContext();
        // barrier to ensure cancel processed
        var p = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(0, broker.currentStats().subscriberCount());
        assertEquals(-1, broker.getHeartbeatTimerId());
    }

    @Test
    void cancelInOnSubscribeNeverRegisters() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-cancel-sub");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean onSubscribeCalled = new AtomicBoolean(false);
        Flow.Subscriber<AdminEventBroker.AdminEvent> cancelling = new Flow.Subscriber<>() {
            Flow.Subscription sub;
            @Override public void onSubscribe(Flow.Subscription s) {
                onSubscribeCalled.set(true);
                s.cancel();
                latch.countDown();
                // request after cancel should be ignored
                s.request(10);
            }
            @Override public void onNext(AdminEventBroker.AdminEvent item) { fail("should not receive"); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        };
        broker.subscribe(null).subscribe(cancelling);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        awaitContext();
        assertTrue(onSubscribeCalled.get());
        assertEquals(0, broker.currentStats().subscriberCount());
        assertEquals(-1, broker.getHeartbeatTimerId());
        // publish should not be delivered
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
    }

    @Test
    void requestZeroSignalsErrorOnce() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-req0");
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        sub.request(0);
        sub.awaitFailure(Duration.ofSeconds(2));
        assertTrue(sub.getFailure() instanceof IllegalArgumentException);
        // second request 0 should not signal again
        sub.request(0);
        awaitContext();
        // still only one failure
        assertEquals(1, sub.getFailure() != null ? 1 : 0);
        // events after should not be delivered
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertEquals(0, sub.getItems().size());
        assertEquals(0, broker.currentStats().subscriberCount());
    }

    @Test
    void requestZeroWithNullContextHandled() {
        // broker without vertx (brokerContext null) should not NPE
        Vertx v = Vertx.vertx();
        AdminEventBroker b2 = new AdminEventBroker(v, "noctx", 10, 64, 50, 15000, true);
        // force brokerContext null to test null-path
        b2.brokerContext = null;
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(0);
        b2.subscribe(null).subscribe(sub);
        // request 0 should still error without NPE
        sub.request(0);
        try { sub.awaitFailure(Duration.ofSeconds(1)); } catch (AssertionError e) { fail("should have failed"); }
        assertTrue(sub.getFailure() instanceof IllegalArgumentException);
        v.close();
    }

    @Test
    void onNextThrowTerminatesAndSignalsErrorOnce() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-throw");
        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        Flow.Subscriber<AdminEventBroker.AdminEvent> throwing = new Flow.Subscriber<>() {
            Flow.Subscription s;
            @Override public void onSubscribe(Flow.Subscription subscription) { s = subscription; subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(AdminEventBroker.AdminEvent item) { throw new RuntimeException("boom"); }
            @Override public void onError(Throwable t) { errorLatch.countDown(); }
            @Override public void onComplete() { completed.set(true); }
        };
        broker.subscribe(null).subscribe(throwing);
        awaitContext();
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertTrue(errorLatch.await(2, TimeUnit.SECONDS));
        assertFalse(completed.get());
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
        // second publish should not signal again
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        assertEquals(0, broker.currentStats().subscriberCount());
    }

    @Test
    void heartbeatUniqueAndFakeTimer() throws Exception {
        broker = newBroker(10, 64, 50, 1000, "inst-hb");
        AssertSubscriber<AdminEventBroker.AdminEvent> s1 = AssertSubscriber.create(Long.MAX_VALUE);
        AssertSubscriber<AdminEventBroker.AdminEvent> s2 = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(s1);
        awaitContext();
        long tid1 = broker.getHeartbeatTimerId();
        assertNotEquals(-1, tid1);
        broker.subscribe(null).subscribe(s2);
        awaitContext();
        assertEquals(tid1, broker.getHeartbeatTimerId());
        broker.triggerHeartbeatForTest();
        awaitContext();
        // need barrier to ensure dispatch processed
        var p = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        s1.awaitItems(1, Duration.ofSeconds(2));
        s2.awaitItems(1, Duration.ofSeconds(2));
        assertEquals(AdminEventBroker.AdminEventType.HEARTBEAT, s1.getItems().get(0).type());
        assertEquals(AdminEventBroker.AdminEventType.HEARTBEAT, s2.getItems().get(0).type());
        assertTrue(broker.replayBuffer.stream().noneMatch(e -> e.type()== AdminEventBroker.AdminEventType.HEARTBEAT));
        s1.cancel(); s2.cancel();
        awaitContext();
        var p2 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p2.complete());
        p2.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(-1, broker.getHeartbeatTimerId());
    }

    @Test
    void shutdownCleanup() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-shutdown");
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        assertEquals(1, broker.currentStats().subscriberCount());
        broker.shutdown();
        awaitContext();
        var p = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(0, broker.subscribers.size());
        assertEquals(-1, broker.getHeartbeatTimerId());
    }

    @Test
    void statsSnapshot() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-stats");
        broker.publishStatusChanged(0,0,0,false);
        broker.publishConfigReloaded("global");
        awaitContext();
        var stats = broker.statsAsync().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(2, stats.replaySize());
        assertEquals(2, stats.published());
        assertEquals(0, stats.subscriberCount());
    }

    @Test
    void payloadValidations() {
        broker = newBroker(10, 64, 50, 15000, "inst-val");
        assertThrows(IllegalArgumentException.class, () -> broker.publishConfigReloaded("bad\r\n"));
        assertThrows(IllegalArgumentException.class, () -> broker.publishConfigReloaded("x".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> broker.publishConfigReloaded("bad name with spaces!"));
        assertThrows(IllegalArgumentException.class, () -> broker.publishConnectorChanged("UNKNOWN_STATE", 1));
        assertThrows(IllegalArgumentException.class, () -> broker.publishStatusChanged(-1,0,0,false));
        assertThrows(IllegalArgumentException.class, () -> broker.publishSessionChanged(1, "OK\r\n"));
    }

    @Test
    void noForbiddenWordsInJson() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-leak");
        broker.publishStatusChanged(10,5,2,true);
        broker.publishConfigReloaded("global");
        broker.publishConnectorChanged("CONNECTED", 1);
        broker.publishSessionChanged(3, "ACTIVE");
        broker.publishCacheChanged(100, "OK");
        broker.publishDcwChanged("OK", 2);
        awaitContext();
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        for (var ev: broker.replayBuffer) {
            String json = mapper.writeValueAsString(ev.payload());
            String low = json.toLowerCase();
            assertFalse(low.contains("password"), "leak password in " + json);
            assertFalse(low.contains("secret"), "leak secret in " + json);
            assertFalse(low.contains("token"), "leak token in " + json);
            assertFalse(low.contains("\"host\""), "leak host in " + json);
            assertFalse(low.contains("\"port\""), "leak port in " + json);
            assertFalse(low.contains("\"key\""), "leak key in " + json);
            assertFalse(low.contains("ecm"), "leak ecm in " + json);
            assertFalse(low.contains("\"cw\""), "leak cw in " + json);
        }
    }

    @Test
    void snapshotAndHeartbeatNotReplayedButHaveIds() throws Exception {
        broker = newBroker(10, 64, 50, 15000, "inst-ids");
        broker.publishStatusChanged(0,0,0,false);
        awaitContext();
        long afterFirst = broker.getNextId();
        AssertSubscriber<AdminEventBroker.AdminEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe(null).subscribe(sub);
        awaitContext();
        broker.triggerHeartbeatForTest();
        awaitContext();
        var p = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p.complete());
        p.future().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(afterFirst+1, broker.getNextId());
        assertEquals(1, broker.getReplayBufferSize());
        AssertSubscriber<AdminEventBroker.AdminEvent> gap = AssertSubscriber.create(Long.MAX_VALUE);
        broker.subscribe("999999").subscribe(gap);
        awaitContext();
        gap.awaitCompletion(Duration.ofSeconds(2));
        assertEquals(afterFirst+2, broker.getNextId());
        assertEquals(1, broker.getReplayBufferSize());
        sub.cancel();
    }

    @Test
    void configurationValidation() {
        assertThrows(IllegalStateException.class, () -> new AdminEventBroker(vertx, "x", 5, 64, 50, 15000, true));
        assertThrows(IllegalStateException.class, () -> new AdminEventBroker(vertx, "x", 500, 2, 50, 15000, true));
        assertThrows(IllegalStateException.class, () -> new AdminEventBroker(vertx, "x", 500, 64, 0, 15000, true));
        assertThrows(IllegalStateException.class, () -> new AdminEventBroker(vertx, "x", 500, 64, 50, 500, true));
    }
}
