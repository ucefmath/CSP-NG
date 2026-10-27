package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.vertx.core.Vertx;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.jboss.resteasy.reactive.NoCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AdminEventResourceTest {

    Vertx vertx;

    @AfterEach
    void tearDown() throws Exception {
        if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void classHasRequiredAnnotations() throws Exception {
        var cls = AdminEventResource.class;
        assertTrue(cls.isAnnotationPresent(jakarta.ws.rs.Path.class));
        assertEquals("/api/admin/events", cls.getAnnotation(jakarta.ws.rs.Path.class).value());
        assertTrue(cls.isAnnotationPresent(jakarta.annotation.security.RolesAllowed.class));
        assertTrue(java.util.List.of(cls.getAnnotation(jakarta.annotation.security.RolesAllowed.class).value()).contains("admin"));
        assertTrue(cls.isAnnotationPresent(NoCache.class) || hasNoCacheMethod(cls));
        // Produces at class or method
        boolean hasProduces = cls.isAnnotationPresent(Produces.class);
        Method m = cls.getDeclaredMethod("events", String.class);
        hasProduces = hasProduces || m.isAnnotationPresent(Produces.class);
        assertTrue(hasProduces);
        // check media type is SERVER_SENT_EVENTS
        Produces p = m.getAnnotation(Produces.class);
        if (p != null) {
            boolean found = false;
            for (String v: p.value()) if (v.equals(MediaType.SERVER_SENT_EVENTS)) found=true;
            assertTrue(found, "Should produce text/event-stream");
        }
    }

    private boolean hasNoCacheMethod(Class<?> cls){
        for (Method m: cls.getDeclaredMethods()) if (m.isAnnotationPresent(NoCache.class)) return true;
        return false;
    }

    @Test
    void methodAcceptsOnlyHeaderParamNoQueryParam() throws Exception {
        Method m = AdminEventResource.class.getDeclaredMethod("events", String.class);
        Parameter param = m.getParameters()[0];
        assertTrue(param.isAnnotationPresent(jakarta.ws.rs.HeaderParam.class));
        assertEquals("Last-Event-ID", param.getAnnotation(jakarta.ws.rs.HeaderParam.class).value());
        assertFalse(param.isAnnotationPresent(jakarta.ws.rs.QueryParam.class));
        // ensure no other query param in method
        for (Parameter p: m.getParameters()) {
            assertFalse(p.isAnnotationPresent(jakarta.ws.rs.QueryParam.class), "No QueryParam allowed");
        }
        // ensure method signature has exactly 1 param
        assertEquals(1, m.getParameterCount());
    }

    @Test
    void disabledThrowsNotFoundBeforeStream() {
        vertx = Vertx.vertx();
        AdminEventBroker broker = new AdminEventBroker(vertx, "inst-test", 500, 64, 50, 15000, false);
        RuntimeSnapshotService snap = new RuntimeSnapshotService("inst-test", java.time.Instant.now(), System.nanoTime(), null, null, null, null, null, null, null);
        FakeSse sse = new FakeSse();
        AdminEventResource res = new AdminEventResource(broker, snap, sse);
        assertThrows(NotFoundException.class, () -> res.events(null));
        assertThrows(NotFoundException.class, () -> res.events("1"));
        vertx.close();
        vertx = null;
    }

    @Test
    void mapsToOutboundSseEventWithIdNameData() throws Exception {
        vertx = Vertx.vertx();
        AdminEventBroker broker = new AdminEventBroker(vertx, "inst-map", 500, 64, 50, 15000, true);
        RuntimeSnapshotService snap = new RuntimeSnapshotService("inst-map", java.time.Instant.now(), System.nanoTime(), null, null, null, null, null, null, null);
        FakeSse sse = new FakeSse();
        AdminEventResource res = new AdminEventResource(broker, snap, sse);

        // publish one event then subscribe via resource
        broker.publishStatusChanged(1,1,1,false);
        // await context
        var p1 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p1.complete());
        p1.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        AssertSubscriber<OutboundSseEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        res.events(null).subscribe(sub);
        // Need an event live after subscription
        broker.publishConfigReloaded("global");
        var p2 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p2.complete());
        p2.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        sub.awaitItems(1, java.time.Duration.ofSeconds(2));
        OutboundSseEvent ev = sub.getItems().get(0);
        assertNotNull(ev.getMediaType());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, ev.getMediaType());
        assertTrue(ev instanceof FakeOutboundSseEvent);
        FakeOutboundSseEvent fake = (FakeOutboundSseEvent) ev;
        assertNotNull(fake.id);
        assertTrue(fake.id.matches("\\d+"));
        assertNotNull(fake.name);
        assertTrue(java.util.Set.of("STATUS_CHANGED","CONFIG_RELOADED","CONNECTOR_CHANGED","SESSION_CHANGED","CACHE_CHANGED","DCW_CHANGED","SNAPSHOT_REQUIRED","HEARTBEAT").contains(fake.name));
        assertNotNull(fake.data);
        assertTrue(fake.data instanceof RuntimeSnapshotService.Envelope);
        RuntimeSnapshotService.Envelope<?> env = (RuntimeSnapshotService.Envelope<?>) fake.data;
        assertEquals("inst-map", env.instanceId());
        assertEquals("1.0", env.schemaVersion());
        assertNotNull(env.timestamp());
        assertDoesNotThrow(() -> java.time.Instant.parse(env.timestamp()));
        sub.cancel();
        vertx.close();
        vertx = null;
    }

    @Test
    void envelopeContainsPayloadAndInstanceStable() throws Exception {
        vertx = Vertx.vertx();
        AdminEventBroker broker = new AdminEventBroker(vertx, "fixed-id-123", 500, 64, 50, 15000, true);
        RuntimeSnapshotService snap = new RuntimeSnapshotService("fixed-id-123", java.time.Instant.now(), System.nanoTime(), null, null, null, null, null, null, null);
        FakeSse sse = new FakeSse();
        AdminEventResource res = new AdminEventResource(broker, snap, sse);
        AssertSubscriber<OutboundSseEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        res.events(null).subscribe(sub);
        broker.publishStatusChanged(2,1,5,true);
        var p3 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p3.complete());
        p3.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        sub.awaitItems(1, java.time.Duration.ofSeconds(2));
        FakeOutboundSseEvent fake = (FakeOutboundSseEvent) sub.getItems().get(0);
        RuntimeSnapshotService.Envelope<?> env = (RuntimeSnapshotService.Envelope<?>) fake.data;
        assertEquals("fixed-id-123", env.instanceId());
        assertTrue(env.payload() instanceof AdminEventBroker.StatusChangedPayload);
        sub.cancel();
        vertx.close();
        vertx=null;
    }

    @Test
    void lastEventIdReplayViaResource() throws Exception {
        vertx = Vertx.vertx();
        AdminEventBroker broker = new AdminEventBroker(vertx, "inst-replay", 500, 64, 50, 15000, true);
        RuntimeSnapshotService snap = new RuntimeSnapshotService("inst-replay", java.time.Instant.now(), System.nanoTime(), null, null, null, null, null, null, null);
        FakeSse sse = new FakeSse();
        AdminEventResource res = new AdminEventResource(broker, snap, sse);
        for (int i=0;i<3;i++) broker.publishStatusChanged(0,0,0,false);
        var p4 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p4.complete());
        p4.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        // ids 1,2,3 . request with last=1 should get 2,3
        AssertSubscriber<OutboundSseEvent> sub = AssertSubscriber.create(Long.MAX_VALUE);
        res.events("1").subscribe(sub);
        var p5 = io.vertx.core.Promise.<Void>promise();
        broker.brokerContext.runOnContext(v->p5.complete());
        p5.future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        sub.awaitItems(2, java.time.Duration.ofSeconds(2));
        assertEquals(2, sub.getItems().size());
        FakeOutboundSseEvent f1 = (FakeOutboundSseEvent) sub.getItems().get(0);
        FakeOutboundSseEvent f2 = (FakeOutboundSseEvent) sub.getItems().get(1);
        assertEquals("2", f1.id);
        assertEquals("3", f2.id);
        sub.cancel();
        vertx.close(); vertx=null;
    }

    // ---- Fake Sse ----
    static class FakeSse implements Sse {
        @Override public OutboundSseEvent.Builder newEventBuilder() { return new FakeBuilder(); }
        @Override public jakarta.ws.rs.sse.SseBroadcaster newBroadcaster() { return null; }
    }
    static class FakeBuilder implements OutboundSseEvent.Builder {
        String id; String name; MediaType mediaType; Class<?> type; Object data;
        @Override public OutboundSseEvent.Builder id(String id){ this.id=id; return this;}
        @Override public OutboundSseEvent.Builder name(String name){ this.name=name; return this;}
        @Override public OutboundSseEvent.Builder reconnectDelay(long d){ return this;}
        @Override public OutboundSseEvent.Builder mediaType(MediaType mt){ this.mediaType=mt; return this;}
        @Override public OutboundSseEvent.Builder comment(String c){ return this;}
        @Override public OutboundSseEvent.Builder data(Class t, Object d){ this.type=t; this.data=d; return this;}
        @Override public OutboundSseEvent.Builder data(jakarta.ws.rs.core.GenericType t, Object d){ this.data=d; return this;}
        @Override public OutboundSseEvent.Builder data(Object d){ this.data=d; return this;}
        @Override public OutboundSseEvent build(){ return new FakeOutboundSseEvent(id,name,mediaType,type,data);}
    }
    static class FakeOutboundSseEvent implements OutboundSseEvent {
        final String id; final String name; final MediaType mediaType; final Class<?> type; final Object data;
        FakeOutboundSseEvent(String id, String name, MediaType mt, Class<?> t, Object d){ this.id=id; this.name=name; this.mediaType=mt; this.type=t; this.data=d;}
        @Override public String getId(){ return id; }
        @Override public String getName(){ return name; }
        @Override public long getReconnectDelay(){ return -1; }
        @Override public boolean isReconnectDelaySet(){ return false; }
        @Override public String getComment(){ return null; }
        @Override public Class<?> getType(){ return type; }
        @Override public java.lang.reflect.Type getGenericType(){ return type; }
        @Override public MediaType getMediaType(){ return mediaType; }
        @Override public Object getData(){ return data; }
        @Override public String toString(){ return "FakeEvent{id="+id+",name="+name+"}";}
    }
}
