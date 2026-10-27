package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolation par listener : 2 routeurs (listeners 7/8) partageant les stores,
 * chacun borne a son cache autorise. Le hit de l'un ne sert jamais l'autre.
 */
@DisplayName("RelayListenerScope — isolation 2 listeners x 2 caches autorises")
class RelayListenerScopeTest {

  private Vertx vertx;
  private static final byte[] CW = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};

  @BeforeEach void setUp() { vertx = Vertx.vertx(); }
  @AfterEach void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
  }

  private static ProtocolAuthResolver.AuthResult auth() {
    return new ProtocolAuthResolver.AuthResult("u1", "file:/run/secrets/u", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), null);
  }

  private static class Dummy extends AbstractConnector {
    private final java.util.function.Function<EcmRequest, Future<CwReply>> h;
    Dummy(String n, java.util.function.Function<EcmRequest, Future<CwReply>> h) {
      super(null, null, n, "127.0.0.1", 1000, "default");
      this.h = h;
    }
    @Override public boolean isConnected() { return true; }
    @Override public Future<CwReply> sendEcm(EcmRequest r) { return h.apply(r); }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  private static RelayCachePolicy policy() {
    return new RelayCachePolicy("local1",
        List.of(
            new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L),
            new RelayCacheConfig("c2", "local", null, true, 8000L, 10000, 512, 1L)),
        List.of(
            new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L),
            new ListenerCacheLinkConfig(8L, "c2", true, true, false, true, 1L)),
        List.of());
  }

  @Test
  @DisplayName("Feed via listener 7 visible en c1, invisible en c2 et au listener 8")
  void scopedFeedAndConsult() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger calls = new AtomicInteger();
    cm.addTestConnector(new Dummy("conn1", req -> {
      calls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW, "conn1", "127.0.0.1", System.currentTimeMillis()));
    }));
    RelayCacheStores stores = new RelayCacheStores();
    AtomicReference<RelayCachePolicy> live = new AtomicReference<>(policy());
    StrictEcmRouter r7 = new StrictEcmRouter(cm);
    r7.setRelayPolicySupplier(live::get, 7L);
    r7.setRelayStores(stores);
    StrictEcmRouter r8 = new StrictEcmRouter(cm);
    r8.setRelayPolicySupplier(live::get, 8L);
    r8.setRelayStores(stores);

    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 300), new byte[]{7,7}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch l1 = new CountDownLatch(1);
    r7.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l1.countDown(); });
    assertTrue(l1.await(2, TimeUnit.SECONDS));
    assertEquals(1, calls.get());

    StrictCacheKey key = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, new byte[]{7,7}, 1L, Set.of("conn1"));
    assertNotNull(stores.peek("c1", key), "c1 alimentee par listener 7");
    assertNull(stores.peek("c2", key), "c2 non alimentee par listener 7");

    // Listener 8 : pas de hit c1 (non autorise), interroge l'amont puis feed c2.
    CountDownLatch l2 = new CountDownLatch(1);
    r8.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l2.countDown(); });
    assertTrue(l2.await(2, TimeUnit.SECONDS));
    assertEquals(2, calls.get(), "listener 8 ne doit pas beneficier du cache de 7");
    assertNotNull(stores.peek("c2", key));
  }
}
