package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
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

@DisplayName("StrictEcmRouter + RelayCachePolicy — detachement immediat, boucles, feed gating (ADR 0002)")
class StrictEcmRouterRelayTest {

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
    private final boolean connected;
    private final java.util.function.Function<EcmRequest, Future<CwReply>> h;
    Dummy(String n, String p, boolean c, java.util.function.Function<EcmRequest, Future<CwReply>> h) {
      super(null, null, n, "127.0.0.1", 1000, p);
      this.connected = c; this.h = h;
    }
    @Override public boolean isConnected() { return connected; }
    @Override public Future<CwReply> sendEcm(EcmRequest r) { return h.apply(r); }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  private static RelayCachePolicy policyWithLink(boolean enabled) {
    return new RelayCachePolicy("local1",
        List.of(new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L)),
        List.of(new ListenerCacheLinkConfig(7L, "c1", true, true, false, enabled, 1L)),
        List.of());
  }

  @Test
  @DisplayName("Sans politique => cache hit P7 inchange (regression)")
  void noPolicyKeepsLegacyHit() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger calls = new AtomicInteger();
    cm.addTestConnector(new Dummy("conn1", "default", true, req -> {
      calls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW, "conn1", "127.0.0.1", System.currentTimeMillis()));
    }));
    StrictEcmRouter router = new StrictEcmRouter(cm);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1,2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch l1 = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l1.countDown(); });
    assertTrue(l1.await(2, TimeUnit.SECONDS));
    CountDownLatch l2 = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l2.countDown(); });
    assertTrue(l2.await(2, TimeUnit.SECONDS));
    assertEquals(1, calls.get());
  }

  @Test
  @DisplayName("Detachement immediat : disable lien => 2e appel ne hit plus le cache")
  void detachDisablesCacheHitLive() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger calls = new AtomicInteger();
    cm.addTestConnector(new Dummy("conn1", "default", true, req -> {
      calls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW, "conn1", "127.0.0.1", System.currentTimeMillis()));
    }));
    StrictEcmRouter router = new StrictEcmRouter(cm);
    AtomicReference<RelayCachePolicy> live = new AtomicReference<>(policyWithLink(true));
    router.setRelayPolicySupplier(live::get, 7L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 101), new byte[]{3,4}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch l1 = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l1.countDown(); });
    assertTrue(l1.await(2, TimeUnit.SECONDS));
    assertEquals(1, calls.get());
    // Detachement : politique live remplacee (meme listener, lien disabled)
    live.set(policyWithLink(false));
    CountDownLatch l2 = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> { assertFalse(ar.result().isEmpty()); l2.countDown(); });
    assertTrue(l2.await(2, TimeUnit.SECONDS));
    assertEquals(2, calls.get(), "Cache desactive => le connecteur doit etre re-interroge (pas de hit)");
  }

  @Test
  @DisplayName("Boucle : origin chain contenant le local => refus fail-closed")
  void loopRejected() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    cm.addTestConnector(new Dummy("conn1", "default", true,
        req -> Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW, "conn1", "127.0.0.1", System.currentTimeMillis()))));
    StrictEcmRouter router = new StrictEcmRouter(cm);
    router.setRelayPolicy(policyWithLink(true), 7L);
    router.setRelayOriginChain(List.of("peer-a", "local1"));
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 102), new byte[]{5}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch l = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Boucle => empty fail-closed");
      l.countDown();
    });
    assertTrue(l.await(2, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Sans candidat local et sans externalConsult : retour empty IMMEDIAT sans attente 5s (Sonnet FIX #1)")
  void noCandidatesAndNoExtConsultReturnsImmediately() throws Exception {
    CwsConnectorManager emptyCm = new CwsConnectorManager(vertx, null);
    StrictEcmRouter router = new StrictEcmRouter(emptyCm);
    RelayCacheStores stores = new RelayCacheStores();
    router.setRelayStores(stores);
    router.setRelayPolicy(policyWithLink(true), 7L);
    // externalConsult est volontairement NULL (comme sur listeners centraux)

    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 103), new byte[]{7},
        "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000L);
    long start = System.currentTimeMillis();
    CountDownLatch l = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Doit echouer fail-closed sans blocage");
      l.countDown();
    });
    assertTrue(l.await(500, TimeUnit.MILLISECONDS), "Reponse immediate attendue (< 500ms, pas 5000ms)");
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed < 1000L, "Latence mesurée doit etre < 1s (got " + elapsed + "ms)");

    // Mais un hit de cache existant dans le store reste servi immediatement !
    var nodePolicy = new RelayCachePolicy("local1",
        List.of(new RelayCacheConfig("c1", "node", "peer-a", true, 8000L, 10000, 512, 1L)),
        List.of(new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L)),
        List.of(new RelayNodeConfig("peer-a", null, true, true, false, 1L, null, 1L)));
    router.setRelayPolicy(nodePolicy, 7L);
    StrictCacheKey key = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, new byte[]{7}, 1L, Set.of("node:peer-a"));
    stores.ensure("c1", new RelayCacheConfig("c1", "node", "peer-a", true, 8000L, 10000, 512, 1L));
    stores.put("c1", key, new CwReply(req.key(), 0x80, CW, "node:peer-a", "127.0.0.1", System.currentTimeMillis()), 8000L);
    CountDownLatch l2 = new CountDownLatch(1);
    router.routeEcm(req, auth()).onComplete(ar -> {
      assertFalse(ar.result().isEmpty(), "Cache hit existant doit etre servi");
      assertArrayEquals(CW, ar.result().cw());
      l2.countDown();
    });
    assertTrue(l2.await(500, TimeUnit.MILLISECONDS));
  }

  @Test
  @DisplayName("Anti-boucle : requete d'origine CSPv2 ne declenche JAMAIS d'externalConsult sortant (max hop = 1)")
  void inboundCspv2NeverTriggersExternalConsult() throws Exception {
    CwsConnectorManager emptyCm = new CwsConnectorManager(vertx, null);
    StrictEcmRouter router = new StrictEcmRouter(emptyCm);
    router.setRelayStores(new RelayCacheStores());
    router.setRelayPolicy(policyWithLink(true), 7L);

    AtomicInteger extConsultCalls = new AtomicInteger();
    router.setExternalConsult((lid, r, a, p, el, c) -> {
      extConsultCalls.incrementAndGet();
      return Future.succeededFuture(new StrictEcmRouter.ExternalReply("c1",
          new CwReply(r.key(), 0x80, CW, "node:peer-a", "127.0.0.1", System.currentTimeMillis())));
    });

    // Requete issue d'un client local (origin = 127.0.0.1) -> externalConsult declenche
    EcmRequest localReq = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 104), new byte[]{8},
        "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 2000L);
    CountDownLatch l1 = new CountDownLatch(1);
    router.routeEcm(localReq, auth()).onComplete(ar -> {
      assertFalse(ar.result().isEmpty());
      l1.countDown();
    });
    assertTrue(l1.await(2, TimeUnit.SECONDS));
    assertEquals(1, extConsultCalls.get());

    // Requete d'origine CSPv2 distante (origin = cspv2:node-b) -> externalConsult JAMAIS declenche (anti-loop)
    EcmRequest remoteReq = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 105), new byte[]{9},
        "default", "cspv2:node-b", "node-b", System.currentTimeMillis(), null, 2000L);
    CountDownLatch l2 = new CountDownLatch(1);
    router.routeEcm(remoteReq, auth()).onComplete(ar -> {
      assertTrue(ar.result().isEmpty(), "Sans connecteur local, requete distante echoue immediatement (pas de re-forward)");
      l2.countDown();
    });
    assertTrue(l2.await(500, TimeUnit.MILLISECONDS));
    assertEquals(1, extConsultCalls.get(), "externalConsult ne doit PAS avoir ete appele pour l'origine cspv2:");
  }
}
