package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stats dispatch reelles : ok/echec/timeout + latence mesuree au dispatch,
 * jamais de fictif. Timeouts attribues a la tentative active exacte.
 */
@DisplayName("DispatchStats — compteurs et latences reels au dispatch")
class DispatchStatsTest {

  private static final byte[] CW = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};

  private Vertx vertx;

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

  private static EcmRequest req(int dataHash, long maxWaitMs) {
    return new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, dataHash), new byte[]{9},
        "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, maxWaitMs);
  }

  private static DispatchStats.Snapshot one(StrictEcmRouter router, String name) {
    return router.dispatchStats().snapshot().stream()
        .filter(s -> s.name().equals(name)).findFirst()
        .orElseThrow(() -> new AssertionError("no stats for " + name));
  }

  @Test
  @DisplayName("Unitaire : bornes, moyennes, absence = pas d'entree")
  void unitBounds() {
    DispatchStats st = new DispatchStats();
    assertTrue(st.snapshot().isEmpty(), "aucun trafic => aucune entree (pas de zero fictif)");
    st.recordOk("c1", 10L);
    st.recordOk("c1", 30L);
    st.recordFail("c1", 5L);
    st.recordTimeout("c1");
    var s = st.snapshot().stream().filter(x -> x.name().equals("c1")).findFirst().orElseThrow();
    assertEquals(2L, s.ok());
    assertEquals(1L, s.fail());
    assertEquals(1L, s.timeout());
    assertEquals(15L, s.avgLatencyMs());
    for (int i = 0; i < 200; i++) st.recordOk("overflow-" + i, 1L);
    assertTrue(st.snapshot().size() <= DispatchStats.MAX_CONNECTORS + 1);
  }

  @Test
  @DisplayName("Routeur : succes mesure ok=1 + latence ; echec mesure fail=1")
  void routerOkAndFail() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    cm.addTestConnector(new Dummy("conn1",
        r -> Future.succeededFuture(new CwReply(r.key(), r.key().commandTag(), CW.clone(),
            "conn1", "127.0.0.1", System.currentTimeMillis()))));
    StrictEcmRouter router = new StrictEcmRouter(cm);
    CountDownLatch l1 = new CountDownLatch(1);
    router.routeEcm(req(501, 2000L), auth()).onComplete(ar -> l1.countDown());
    assertTrue(l1.await(3, TimeUnit.SECONDS));
    var ok = one(router, "conn1");
    assertEquals(1L, ok.ok());
    assertEquals(0L, ok.fail());
    assertNotNull(ok.avgLatencyMs(), "latence mesuree reelle");

    CwsConnectorManager cm2 = new CwsConnectorManager(vertx, null);
    cm2.addTestConnector(new Dummy("conn1", r -> Future.failedFuture("boom")));
    StrictEcmRouter router2 = new StrictEcmRouter(cm2);
    CountDownLatch l2 = new CountDownLatch(1);
    router2.routeEcm(req(502, 2000L), auth()).onComplete(ar -> l2.countDown());
    assertTrue(l2.await(3, TimeUnit.SECONDS));
    var fail = one(router2, "conn1");
    assertEquals(0L, fail.ok());
    assertEquals(1L, fail.fail());
  }

  @Test
  @DisplayName("Routeur : timeout attribue au connecteur actif exact")
  void routerTimeoutAttributed() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    Promise<CwReply> never = Promise.promise();
    cm.addTestConnector(new Dummy("conn1", r -> never.future()));
    StrictEcmRouter router = new StrictEcmRouter(cm);
    CountDownLatch l = new CountDownLatch(1);
    final CwReply[] out = new CwReply[1];
    router.routeEcm(req(503, 150L), auth()).onComplete(ar -> {
      out[0] = ar.result();
      l.countDown();
    });
    assertTrue(l.await(5, TimeUnit.SECONDS));
    assertTrue(out[0] == null || out[0].isEmpty(), "timeout => vide");
    var s = one(router, "conn1");
    assertEquals(1L, s.timeout(), "timeout attribue au connecteur actif");
    assertEquals(0L, s.ok());
  }
}
