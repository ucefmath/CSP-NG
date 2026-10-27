package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.catalog.CardSource;
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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StrictEcmRouter — Routage strict, Cache unifié partitionné, Isolation ACL et Anti-Poisoning (P7)")
class StrictEcmRouterTest {

  private Vertx vertx;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(v -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  private static final byte[] CW_CORRECT = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };

  private static ProtocolAuthResolver.AuthResult auth(String user, Set<String> profiles,
                                                      Map<String, Set<Integer>> allowedSrv,
                                                      Map<String, Set<Integer>> blockedSrv,
                                                      Set<String> connectors, long epoch) {
    return new ProtocolAuthResolver.AuthResult(user, "file:/run/secrets/u", 1L, 1L,
        Instant.now().plusSeconds(3600), epoch, profiles, allowedSrv, blockedSrv, connectors, null);
  }

  private static class DummyConnector extends AbstractConnector {
    private final String name;
    private final String profile;
    private final boolean connected;
    private java.util.function.Function<EcmRequest, Future<CwReply>> handler;

    DummyConnector(String name, String profile, boolean connected,
                   java.util.function.Function<EcmRequest, Future<CwReply>> handler) {
      super(null, null, name, "127.0.0.1", 1000, profile);
      this.name = name;
      this.profile = profile;
      this.connected = connected;
      this.handler = handler;
    }

    @Override public boolean isConnected() { return connected; }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      return handler != null ? handler.apply(request) : Future.succeededFuture(CwReply.empty(request.key()));
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() {}
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
  }

  @Test
  @DisplayName("Anti-Poisoning & Hash Collision : 2 corps d'ECM avec même hash 32-bit ont des clés de cache distinctes")
  void sameHashDifferentBodyPartitionedDistinctly() {
    StrictPartitionedCache cache = new StrictPartitionedCache();

    byte[] body1 = new byte[]{ 1, 2, 3, 4, 5 };
    byte[] body2 = new byte[]{ 1, 2, 3, 4, 99 }; // Corps différent

    // Même hash simulé (par exemple hash 12345)
    StrictCacheKey key1 = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body1, 1L, Set.of("conn1"));
    StrictCacheKey key2 = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body2, 1L, Set.of("conn1"));

    assertNotEquals(key1, key2, "Deux clés avec corps différents ne doivent PAS être égales");

    CwReply reply1 = new CwReply(new EcmKey(0x80, 1, 0, 0x0100, 12345), 0x80, CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis());
    cache.put(key1, reply1, 8000);

    assertNotNull(cache.peek(key1), "Key1 doit être en cache");
    assertNull(cache.peek(key2), "Key2 NE DOIT PAS être trouvée (pas d'empoisonnement par hash collision)");
  }

  @Test
  @DisplayName("Isolation ACL Inter-utilisateurs : 2 utilisateurs avec connecteurs éligibles distincts sont partitionnés")
  void crossUserAclIsolation() {
    StrictPartitionedCache cache = new StrictPartitionedCache();

    byte[] body = new byte[]{ 1, 2, 3 };
    StrictCacheKey keyUserA = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body, 1L, Set.of("conn1"));
    StrictCacheKey keyUserB = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body, 1L, Set.of("conn2"));

    assertNotEquals(keyUserA, keyUserB, "Les clés doivent différer par ensemble de connecteurs éligibles");

    CwReply replyA = new CwReply(new EcmKey(0x80, 1, 0, 0x0100, 100), 0x80, CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis());
    cache.put(keyUserA, replyA, 8000);

    assertNotNull(cache.peek(keyUserA));
    assertNull(cache.peek(keyUserB), "User B ne doit pas toucher le cache de User A");
  }

  @Test
  @DisplayName("Invalidation par Epoch : un bump d'epoch invalide immédiatement le cache")
  void epochBumpInvalidatesCache() {
    StrictPartitionedCache cache = new StrictPartitionedCache();
    byte[] body = new byte[]{ 1, 2, 3 };

    StrictCacheKey keyEpoch1 = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body, 1L, Set.of("conn1"));
    StrictCacheKey keyEpoch2 = new StrictCacheKey("default", 0x0100, 0, 1, 0x80, body, 2L, Set.of("conn1"));

    cache.put(keyEpoch1, new CwReply(new EcmKey(0x80, 1, 0, 0x0100, 100), 0x80, CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis()), 8000);

    assertNotNull(cache.peek(keyEpoch1));
    assertNull(cache.peek(keyEpoch2), "Le cache à epoch=2 doit être vide (stale cache ignoré)");
  }

  @Test
  @DisplayName("Routage strict : fail closed si aucun connecteur éligible connecté")
  void failClosedWhenNoEligibleConnector() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    StrictEcmRouter router = new StrictEcmRouter(cm);

    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Doit retourner une réponse vide (FAIL CLOSED)");
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("Routage strict : sélection connecteur connecté + mise en cache + vérification provenance")
  void successfulRouteAndCacheHit() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger callCount = new AtomicInteger(0);

    DummyConnector conn = new DummyConnector("conn1", "default", true, req -> {
      callCount.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis()));
    });
    cm.addTestConnector(conn);

    StrictEcmRouter router = new StrictEcmRouter(cm);

    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);

    // Premier appel -> connecteur interrogé
    CountDownLatch latch1 = new CountDownLatch(1);
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertArrayEquals(CW_CORRECT, ar.result().cw());
      assertEquals("conn1", ar.result().connectorName());
      latch1.countDown();
    });
    assertTrue(latch1.await(2, TimeUnit.SECONDS));
    assertEquals(1, callCount.get());

    // Deuxième appel -> Cache HIT (connecteur non rappelé)
    CountDownLatch latch2 = new CountDownLatch(1);
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertArrayEquals(CW_CORRECT, ar.result().cw());
      latch2.countDown();
    });
    assertTrue(latch2.await(2, TimeUnit.SECONDS));
    assertEquals(1, callCount.get(), "Le deuxième appel doit être servi par le cache");
  }

  @Test
  @DisplayName("Vérification provenance : rejet si le connecteur retourne un nom non éligible")
  void forgedConnectorProvenanceRejected() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);

    // Le connecteur "conn1" retourne une réponse frauduleuse prétendant venir de "unauthorized_conn"
    DummyConnector conn = new DummyConnector("conn1", "default", true, req ->
        Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "unauthorized_conn", "127.0.0.1", System.currentTimeMillis())));
    cm.addTestConnector(conn);

    StrictEcmRouter router = new StrictEcmRouter(cm);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Provenance invalide doit être rejetée (fail closed)");
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("In-flight timeout : un connecteur muet est interrompu à maxWaitMs sans bloquer les joiners")
  void silentConnectorTimesOutAndCompletesInFlight() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);

    // Connecteur qui ne complète jamais sa Promise
    DummyConnector silentConn = new DummyConnector("conn1", "default", true, req -> Promise.<CwReply>promise().future());
    cm.addTestConnector(silentConn);

    StrictEcmRouter router = new StrictEcmRouter(cm);
    // Requête avec maxWaitMs court (200ms)
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1, 2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 200L);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);

    CountDownLatch latch1 = new CountDownLatch(1);
    CountDownLatch latch2 = new CountDownLatch(1);

    // Leader
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Le leader doit timeout et retourner empty");
      latch1.countDown();
    });

    // Joiner
    router.routeEcm(req, auth).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Le joiner doit recevoir empty suite au timeout");
      latch2.countDown();
    });

    assertTrue(latch1.await(3, TimeUnit.SECONDS), "Leader doit terminer avant 3s");
    assertTrue(latch2.await(3, TimeUnit.SECONDS), "Joiner doit terminer avant 3s");
  }

  @Test
  @DisplayName("2 connecteurs avec CAID et provider distincts -> SEUL le connecteur correspondant exactement est interrogé")
  void twoConnectorsDifferentCaidProviderRoutesOnlyToMatchingSource() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger connACalls = new AtomicInteger(0);
    AtomicInteger connBCalls = new AtomicInteger(0);

    DummyConnector connA = new DummyConnector("connA", "default", true, req -> {
      connACalls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "connA", "127.0.0.1", System.currentTimeMillis()));
    });
    DummyConnector connB = new DummyConnector("connB", "default", true, req -> {
      connBCalls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "connB", "127.0.0.1", System.currentTimeMillis()));
    });

    cm.addTestConnector(connA);
    cm.addTestConnector(connB);

    CardDiscoveryService discovery = new CardDiscoveryService(vertx);
    discovery.onConnectorConnected("connA", 1L);
    discovery.onConnectorConnected("connB", 1L);

    // connA offre 0x0100 / 0x000068
    discovery.onCardDiscovered("connA", "default", 0x0100, 0x000068, 101L, 1, 2, com.bowman.cspng.connectors.catalog.CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.updateCapabilityState("default", 0x0100, 0x000068, com.bowman.cspng.connectors.catalog.CardCapability.State.PUBLISHED);

    // connB offre 0x0500 / 0x032830
    discovery.onCardDiscovered("connB", "default", 0x0500, 0x032830, 201L, 1, 2, com.bowman.cspng.connectors.catalog.CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.updateCapabilityState("default", 0x0500, 0x032830, com.bowman.cspng.connectors.catalog.CardCapability.State.PUBLISHED);

    StrictEcmRouter router = new StrictEcmRouter(cm, new StrictPartitionedCache(), null, discovery);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("connA", "connB"), 1L);

    // Requête pour connA (0x0100 / 0x000068)
    EcmRequest reqA = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch latchA = new CountDownLatch(1);
    router.routeEcm(reqA, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertEquals("connA", ar.result().connectorName());
      latchA.countDown();
    });
    assertTrue(latchA.await(2, TimeUnit.SECONDS));
    assertEquals(1, connACalls.get(), "connA doit être appelé exactement 1 fois");
    assertEquals(0, connBCalls.get(), "connB NE DOIT PAS être appelé");

    // Requête pour connB (0x0500 / 0x032830)
    EcmRequest reqB = new EcmRequest(new EcmKey(0x80, 2, 0, 0x0500, 200), new byte[]{2}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);
    CountDownLatch latchB = new CountDownLatch(1);
    router.routeEcm(reqB, auth, 0x032830).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertEquals("connB", ar.result().connectorName());
      latchB.countDown();
    });
    assertTrue(latchB.await(2, TimeUnit.SECONDS));
    assertEquals(1, connACalls.get(), "connA ne doit plus être appelé");
    assertEquals(1, connBCalls.get(), "connB doit être appelé exactement 1 fois");
  }

  @Test
  @DisplayName("Capacité inexistante, BLOCKED, ou manual non PUBLISHED -> FAIL CLOSED (NO send)")
  void capabilityStateEnforcesFailClosed() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger connCalls = new AtomicInteger(0);

    DummyConnector conn = new DummyConnector("conn1", "default", true, req -> {
      connCalls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis()));
    });
    cm.addTestConnector(conn);

    CardDiscoveryService discovery = new CardDiscoveryService(vertx);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0x000068, 101L, 1, 2, com.bowman.cspng.connectors.catalog.CardSource.Trust.DISCOVERED, 5, null, 1L);

    StrictEcmRouter router = new StrictEcmRouter(cm, new StrictPartitionedCache(), null, discovery);
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);

    // 1. État par défaut = DISCOVERED (non PUBLISHED en mode manual) -> FAIL CLOSED
    CountDownLatch latch1 = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Non PUBLISHED -> empty");
      latch1.countDown();
    });
    assertTrue(latch1.await(2, TimeUnit.SECONDS));
    assertEquals(0, connCalls.get(), "Le connecteur ne doit PAS être interrogé");

    // 2. État BLOCKED -> FAIL CLOSED
    discovery.updateCapabilityState("default", 0x0100, 0x000068, com.bowman.cspng.connectors.catalog.CardCapability.State.BLOCKED);
    CountDownLatch latch2 = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "BLOCKED -> empty");
      latch2.countDown();
    });
    assertTrue(latch2.await(2, TimeUnit.SECONDS));
    assertEquals(0, connCalls.get(), "Le connecteur ne doit PAS être interrogé");

    // 3. Capacité inexistante (provider inconnu 0x999999) -> FAIL CLOSED
    CountDownLatch latch3 = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x999999).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Inexistant -> empty");
      latch3.countDown();
    });
    assertTrue(latch3.await(2, TimeUnit.SECONDS));
    assertEquals(0, connCalls.get(), "Le connecteur ne doit PAS être interrogé");
  }

  @Test
  @DisplayName("Profils utilisateur vide -> FAIL CLOSED immédiat")
  void emptyUserProfilesFailsClosed() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    StrictEcmRouter router = new StrictEcmRouter(cm);

    // Profils = Set.of() (vide)
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of(), Map.of(), Map.of(), Set.of("conn1"), 1L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth, 0).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertTrue(ar.result().isEmpty(), "Profils vide doit FAIL CLOSED");
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("R1: Sources avec reshare=0 (client-only, CCcam amont 1, Newcamd grant 0) sont ROUTABLES avec succès")
  void reshareZeroAndCcAmont1AndGrant0AreRoutable() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger connCalls = new AtomicInteger(0);

    DummyConnector conn = new DummyConnector("conn1", "default", true, req -> {
      connCalls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis()));
    });
    cm.addTestConnector(conn);

    CardDiscoveryService discovery = new CardDiscoveryService(vertx);
    discovery.onConnectorConnected("conn1", 1L);

    // CCcam amont reshare = 1 -> effectiveReshare = 0 (client-only)
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0x000068, 101L, 1, 1, CardSource.Trust.DISCOVERED, 0, null, 1L);
    discovery.updateCapabilityState("default", 0x0100, 0x000068, com.bowman.cspng.connectors.catalog.CardCapability.State.PUBLISHED);

    StrictEcmRouter router = new StrictEcmRouter(cm, new StrictPartitionedCache(), null, discovery, "manual");
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertFalse(ar.result().isEmpty(), "Reshare 0 client-only DOIT être routé");
      assertArrayEquals(CW_CORRECT, ar.result().cw());
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertEquals(1, connCalls.get(), "Le connecteur doit être interrogé avec succès");
  }

  @Test
  @DisplayName("R2: Mode wildcard autorise le routage des capacités à l'état DISCOVERED (non bloquées)")
  void wildcardModeAllowsDiscoveredState() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    AtomicInteger connCalls = new AtomicInteger(0);

    DummyConnector conn = new DummyConnector("conn1", "default", true, req -> {
      connCalls.incrementAndGet();
      return Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis()));
    });
    cm.addTestConnector(conn);

    CardDiscoveryService discovery = new CardDiscoveryService(vertx);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0x000068, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);

    // Routeur en mode wildcard
    StrictEcmRouter router = new StrictEcmRouter(cm, new StrictPartitionedCache(), null, discovery, "wildcard");
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertFalse(ar.result().isEmpty(), "En mode wildcard, état DISCOVERED est routable");
      assertArrayEquals(CW_CORRECT, ar.result().cw());
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    assertEquals(1, connCalls.get());
  }

  @Test
  @DisplayName("Provider wire 3-byte valide (0x000068) matche exactement et transmet la bonne clé")
  void providerWire3ByteMatchesCorrectly() throws Exception {
    CwsConnectorManager cm = new CwsConnectorManager(vertx, null);
    DummyConnector conn = new DummyConnector("conn1", "default", true, req ->
        Future.succeededFuture(new CwReply(req.key(), req.key().commandTag(), CW_CORRECT, "conn1", "127.0.0.1", System.currentTimeMillis())));
    cm.addTestConnector(conn);

    CardDiscoveryService discovery = new CardDiscoveryService(vertx);
    discovery.onConnectorConnected("conn1", 1L);
    discovery.onCardDiscovered("conn1", "default", 0x0100, 0x000068, 101L, 1, 2, CardSource.Trust.DISCOVERED, 5, null, 1L);
    discovery.updateCapabilityState("default", 0x0100, 0x000068, com.bowman.cspng.connectors.catalog.CardCapability.State.PUBLISHED);

    StrictEcmRouter router = new StrictEcmRouter(cm, new StrictPartitionedCache(), null, discovery, "manual");
    ProtocolAuthResolver.AuthResult auth = auth("u1", Set.of("default"), Map.of(), Map.of(), Set.of("conn1"), 1L);
    EcmRequest req = new EcmRequest(new EcmKey(0x80, 1, 0, 0x0100, 100), new byte[]{1}, "default", "127.0.0.1", "u1", System.currentTimeMillis(), null, 5000);

    CountDownLatch latch = new CountDownLatch(1);
    router.routeEcm(req, auth, 0x000068).onComplete(ar -> {
      assertTrue(ar.succeeded());
      assertFalse(ar.result().isEmpty());
      latch.countDown();
    });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }
}
