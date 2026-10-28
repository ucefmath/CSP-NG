package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.domain.dcw.*;
import com.bowman.cspng.filters.DcwObservationTracker;
import com.bowman.cspng.filters.InterProviderLinkObserver;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StrictEcmRouter — Intégration DcwQualityPolicy (rejet bad DCW, modes checksum, failover, protection cache)")
class StrictEcmRouterDcwQualityTest {

  private Vertx vertx;
  private CwsConnectorManager connectorManager;
  private StrictPartitionedCache cache;

  private static final byte[] VALID_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06,
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  private static final byte[] BAD_CHECKSUM_CW = new byte[]{
      0x01, 0x02, 0x03, 0x00, // checksum fail
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    connectorManager = new CwsConnectorManager(vertx, null);
    cache = new StrictPartitionedCache();
  }

  @AfterEach
  void tearDown() {
    vertx.close();
  }

  private static class TestConnector extends AbstractConnector {
    private final CwReply replyToReturn;

    TestConnector(Vertx vertx, String name, String profile, CwReply replyToReturn) {
      this(vertx, name, "10.1.0." + Math.floorMod(name.hashCode(), 200) + 1, 10000 + Math.floorMod(name.hashCode(), 1000), profile, replyToReturn);
    }

    TestConnector(Vertx vertx, String name, String host, int port, String profile, CwReply replyToReturn) {
      super(vertx, null, name, host, port, profile);
      this.replyToReturn = replyToReturn;
      this.connected = true;
    }

    @Override
    public Future<Void> start() {
      return Future.succeededFuture();
    }

    @Override
    public void stop() {
      this.connected = false;
    }

    @Override
    public Future<LoginState> loginFuture() {
      return Future.succeededFuture(LoginState.OK);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public Future<CwReply> sendEcm(EcmRequest request) {
      return Future.succeededFuture(replyToReturn);
    }
  }

  @Test
  @DisplayName("Rejet de bad checksum par connecteur 1 et failover immédiat vers connecteur 2 avec CW valide")
  void testFailoverOnBadChecksum() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);

    CwReply badReply = new CwReply(key, 0x80, BAD_CHECKSUM_CW, "c1", "127.0.0.1", System.currentTimeMillis());
    CwReply goodReply = new CwReply(key, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis());

    TestConnector c1 = new TestConnector(vertx, "c1", "profile1", badReply);
    c1.setPriority(10);
    TestConnector c2 = new TestConnector(vertx, "c2", "profile1", goodReply);
    c2.setPriority(5);

    connectorManager.addTestConnector(c1);
    connectorManager.addTestConnector(c2);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, ChecksumMode.REQUIRE_VALID, true, List.of()));

    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty());
    assertEquals("c2", result.connectorName());
    assertArrayEquals(VALID_CW, result.cw());

    // Vérifier que seul c2 (bon) est dans le cache, pas c1
    StrictCacheKey cacheKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, req.body(), 1L, Set.of("c1", "c2"));
    CwReply cached = cache.peek(cacheKey);
    assertNotNull(cached);
    assertEquals("c2", cached.connectorName());
  }

  @Test
  @DisplayName("Mode REJECT_VALID scopé sur provider : rejet de CW avec checksum valide et sélection de CW sans checksum")
  void testRejectValidChecksumModeScoped() {
    EcmKey key = new EcmKey(0x80, 0x1234, 0, 0x0500, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);

    DcwRule rejectValidRule = new DcwRule(
        1L, new DcwRuleScope("profile1", 0x0500, 0x032830, 0, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.REJECT_VALID, "Provider rejects checksum",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );
    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(rejectValidRule));

    CwReply validChecksumFake = new CwReply(key, 0x80, VALID_CW, "c1", "127.0.0.1", System.currentTimeMillis());
    CwReply noChecksumGood = new CwReply(key, 0x80, BAD_CHECKSUM_CW, "c2", "127.0.0.1", System.currentTimeMillis());

    TestConnector c1 = new TestConnector(vertx, "c1", "profile1", validChecksumFake);
    c1.setPriority(10);
    TestConnector c2 = new TestConnector(vertx, "c2", "profile1", noChecksumGood);
    c2.setPriority(5);

    connectorManager.addTestConnector(c1);
    connectorManager.addTestConnector(c2);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(policy);

    CwReply result = router.routeEcm(req, auth, 0x032830).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty());
    assertEquals("c2", result.connectorName());
    assertArrayEquals(BAD_CHECKSUM_CW, result.cw());
  }

  @Test
  @DisplayName("Rejet par règle manuelle BLOCK appliquée par digest SHA-256 avec failover vers connecteur suivant")
  void testManualBlockRuleInStrictRouter() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);

    String badDigest = DcwDigest.digestSha256Hex(VALID_CW);
    DcwRule blockRule = new DcwRule(
        1L,
        new DcwRuleScope("profile1", 0x1884, 0, 0x2938, "c1"),
        badDigest,
        DcwRuleAction.BLOCK,
        ChecksumMode.INHERIT,
        "Block c1 bad cw",
        true,
        System.currentTimeMillis(),
        System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(blockRule));

    CwReply reply1 = new CwReply(key, 0x80, VALID_CW, "c1", "127.0.0.1", System.currentTimeMillis());
    byte[] validCw2 = VALID_CW.clone();
    validCw2[0] = 0x02;
    validCw2[3] = (byte) ((validCw2[0] + validCw2[1] + validCw2[2]) & 0xFF);
    CwReply reply2 = new CwReply(key, 0x80, validCw2, "c2", "127.0.0.1", System.currentTimeMillis());

    TestConnector c1 = new TestConnector(vertx, "c1", "profile1", reply1);
    c1.setPriority(10);
    TestConnector c2 = new TestConnector(vertx, "c2", "profile1", reply2);
    c2.setPriority(5);

    connectorManager.addTestConnector(c1);
    connectorManager.addTestConnector(c2);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(policy);

    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty());
    assertEquals("c2", result.connectorName());
    assertArrayEquals(validCw2, result.cw());
  }

  @Test
  @DisplayName("Observation et tracking des anomalies et des liens candidats lors du routage")
  void testObservationTrackerAndLinkObserverIntegration() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);

    DcwObservationTracker tracker = new DcwObservationTracker(100);
    InterProviderLinkObserver linkObserver = new InterProviderLinkObserver(100);

    CwReply badReply = new CwReply(key, 0x80, BAD_CHECKSUM_CW, "c1", "127.0.0.1", System.currentTimeMillis());
    CwReply goodReply = new CwReply(key, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis());

    TestConnector c1 = new TestConnector(vertx, "c1", "profile1", badReply);
    c1.setPriority(10);
    TestConnector c2 = new TestConnector(vertx, "c2", "profile1", goodReply);
    c2.setPriority(5);

    connectorManager.addTestConnector(c1);
    connectorManager.addTestConnector(c2);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, true, ChecksumMode.REQUIRE_VALID, true, List.of()));
    router.setObservationTracker(tracker);
    router.setLinkObserver(linkObserver);

    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty());
    assertEquals("c2", result.connectorName());

    // Vérifier que l'anomalie de c1 a bien été enregistrée dans l'observation tracker
    List<DcwObservationTracker.AnomalyEvent> anomalies = tracker.recentAnomalies();
    assertEquals(1, anomalies.size());
    assertEquals("c1", anomalies.get(0).sourceId());
    assertEquals(DcwObservationTracker.AnomalyType.CHECKSUM_FAIL, anomalies.get(0).type());
  }

  @Test
  @DisplayName("Référence cache valide pré-existante : servie telle quelle (cache hit, provenance d'origine), aucun dispatch")
  void validCachedReferenceIsServedUnchanged() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    StrictCacheKey cacheKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, req.body(), 1L, Set.of("c1", "c2"));
    cache.put(cacheKey, new CwReply(key, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis()), 60000);
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0; corrupted[7] = 0; corrupted[11] = 0; corrupted[15] = 0;
    connectorManager.addTestConnector(new TestConnector(vertx, "c1", "profile1", new CwReply(key, 0x80, corrupted, "c1", "127.0.0.1", 0L)));
    connectorManager.addTestConnector(new TestConnector(vertx, "c2", "profile1", CwReply.empty(key)));
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of()));
    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertArrayEquals(VALID_CW, result.cw());
    assertEquals("c2", result.connectorName(), "provenance d'origine conservée");
  }

  /** Connecteur qui insère une référence valide dans le cache pendant la requête (course réaliste). */
  private final class RacingConnector extends AbstractConnector {
    private final CwReply bad; private final StrictCacheKey k; private final CwReply ref;
    RacingConnector(Vertx vx, CwReply bad, StrictCacheKey k, CwReply ref) {
      super(vx, null, "c1", "10.2.0.1", 11001, "profile1");
      this.bad = bad; this.k = k; this.ref = ref; this.connected = true;
    }
    @Override public Future<Void> start() { return Future.succeededFuture(); }
    @Override public void stop() { }
    @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
    @Override public boolean isConnected() { return true; }
    @Override public Future<CwReply> sendEcm(EcmRequest request) {
      cache.put(k, ref, 60000);
      return Future.succeededFuture(bad);
    }
  }

  private List<DcwObservationTracker.AnomalyEvent> runRepairScenario(DcwRepairMode mode) {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    StrictCacheKey cacheKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, req.body(), 1L, Set.of("c1", "c2"));
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0; corrupted[7] = 0; corrupted[11] = 0; corrupted[15] = 0;
    connectorManager.addTestConnector(new RacingConnector(vertx, new CwReply(key, 0x80, corrupted, "c1", "127.0.0.1", 0L), cacheKey,
        new CwReply(key, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis())));
    var c2 = new TestConnector(vertx, "c2", "profile1", CwReply.empty(key));
    c2.setPriority(-1);
    connectorManager.addTestConnector(c2);
    DcwObservationTracker tracker = new DcwObservationTracker(100);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    var explicit = new DcwRule(1L, new DcwRuleScope("profile1", 0x1884, 0, 0, null), null, DcwRuleAction.NO_ACTION,
        ChecksumMode.REQUIRE_VALID, "", true, 0L, 0L);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT, mode, true, List.of(explicit)));
    router.setObservationTracker(tracker);
    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    if (mode == DcwRepairMode.ENFORCE) {
      assertFalse(result.isEmpty(), "ENFORCE sert la référence fraîche indépendante");
      assertArrayEquals(VALID_CW, result.cw());
      assertEquals("c2", result.connectorName(), "provenance de la référence, jamais du candidat altéré");
      assertNotEquals("repaired:" + "c1", result.connectorName());
    } else {
      assertTrue(result.isEmpty() || !java.util.Arrays.equals(VALID_CW, result.cw()),
          "OBSERVE/OFF ne sert aucune CW corroborée");
    }
    StrictCacheKey servedKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, req.body(), 1L, Set.of("c1", "c2"));
    CwReply stored = cache.peek(servedKey);
    if (stored != null) {
      assertFalse(stored.connectorName() != null && stored.connectorName().startsWith("repaired:"),
          "CW réparée/substituée non apprise comme preuve de cache");
      if (mode != DcwRepairMode.ENFORCE) {
        assertNotEquals("c1", stored.connectorName());
      }
    }
    return tracker.recentAnomalies();
  }

  @Test
  @DisplayName("ENFORCE : référence cache fraîche d'un autre connecteur servie sous son identité, pas sous c1")
  void enforceServesIndependentFreshReference() {
    var enforce = runRepairScenario(DcwRepairMode.ENFORCE);
    assertTrue(enforce.stream().anyMatch(a -> a.type() == DcwObservationTracker.AnomalyType.REPAIR_OPPORTUNITY), enforce.toString());
  }

  @Test
  @DisplayName("ENFORCE : store local nommé corroboré ; node et udp exclus")
  void namedLocalStoreCorroboratesNodeAndUdpDoNot() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    byte[] body = new byte[10];
    EcmRequest req = new EcmRequest(key, body, "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0;
    StrictCacheKey cacheKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, body, 1L, Set.of("c1", "ref"));
    connectorManager.addTestConnector(new TestConnector(vertx, "c1", "10.9.0.1", 19001, "profile1",
        new CwReply(key, 0x80, corrupted, "c1", "10.9.0.1", 0L)) {
      @Override public Future<CwReply> sendEcm(EcmRequest request) {
        return super.sendEcm(request);
      }
    });
    connectorManager.addTestConnector(new TestConnector(vertx, "ref", "10.9.0.2", 19002, "profile1", CwReply.empty(key)));
    var stores = new RelayCacheStores();
    var local = new RelayCacheConfig("loc", "local", null, true, 8000L, 100, 8, 1L);
    stores.ensure("loc", local);
    stores.put("loc", cacheKey, new CwReply(key, 0x80, VALID_CW, "ref", "10.9.0.2", System.currentTimeMillis()), 60000);
    var node = new RelayCacheConfig("nd", "node", "peer-x", true, 8000L, 100, 8, 1L);
    stores.ensure("nd", node);
    stores.put("nd", cacheKey, new CwReply(key, 0x80, VALID_CW, "node:peer-x", "cspv2", System.currentTimeMillis()), 60000);
    var explicit = new DcwRule(1L, new DcwRuleScope("profile1", 0x1884, 0, 0, null), null,
        DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "", true, 0L, 0L);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setRelayPolicy(new RelayCachePolicy("self", List.of(local, node),
        List.of(new ListenerCacheLinkConfig(7L, "loc", true, false, false, true, 1L)), List.of()), 7L);
    router.setRelayStores(stores);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.ENFORCE, true, List.of(explicit)));
    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertEquals("ref", result.connectorName());
    assertArrayEquals(VALID_CW, result.cw());
    stores.clear("loc");
    CwReply onlyRemote = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertTrue(onlyRemote.isEmpty(), "node/udp ne corroborent pas, got " + onlyRemote.connectorName());
  }

  @Test
  @DisplayName("ENFORCE : deux alias du même host:port ne se corroborent pas")
  void sameEndpointAliasesAreNotIndependent() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0; corrupted[7] = 0; corrupted[11] = 0; corrupted[15] = 0;
    StrictCacheKey cacheKey = new StrictCacheKey("profile1", 0x1884, 0, 0x2938, 0x80, req.body(), 1L, Set.of("alias-a", "alias-b"));
    var aliasA = new TestConnector(vertx, "alias-a", "10.0.0.8", 12000, "profile1",
        new CwReply(key, 0x80, corrupted, "alias-a", "10.0.0.8", 0L)) {
      @Override public Future<CwReply> sendEcm(EcmRequest request) {
        cache.put(cacheKey, new CwReply(key, 0x80, VALID_CW, "alias-b", "10.0.0.8", System.currentTimeMillis()), 60000);
        return super.sendEcm(request);
      }
    };
    connectorManager.addTestConnector(aliasA);
    connectorManager.addTestConnector(new TestConnector(vertx, "alias-b", "10.0.0.8", 12000, "profile1", CwReply.empty(key)));
    var explicit = new DcwRule(1L, new DcwRuleScope("profile1", 0x1884, 0, 0, null), null,
        DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "", true, 0L, 0L);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.ENFORCE, true, List.of(explicit)));
    CwReply result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertTrue(result.isEmpty(), "même endpoint : pas de corroboration, got " + result.connectorName());
  }

  @Test
  @DisplayName("ENFORCE : mapping approuvé même provider autorise le peek du SID lié ; autre provider refusé")
  void approvedSameProviderMappingLooksUpLinkedSid() {
    int sidA = 0x2938;
    int sidB = 0x2940;
    int provider = 0x30;
    EcmKey keyA = new EcmKey(0x80, sidA, 0, 0x1884, 0x12345678);
    EcmKey keyB = new EcmKey(0x80, sidB, 0, 0x1884, 0x12345678);
    byte[] body = new byte[10];
    EcmRequest req = new EcmRequest(keyA, body, "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0; corrupted[7] = 0; corrupted[11] = 0; corrupted[15] = 0;
    connectorManager.addTestConnector(new TestConnector(vertx, "c1", "profile1",
        new CwReply(keyA, 0x80, corrupted, "c1", "127.0.0.1", 0L)));
    connectorManager.addTestConnector(new TestConnector(vertx, "c2", "profile1", CwReply.empty(keyA)));
    StrictCacheKey linked = new StrictCacheKey("profile1", 0x1884, provider, sidB, 0x80, body, 1L, Set.of("c1", "c2"));
    cache.put(linked, new CwReply(keyB, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis()), 60000);
    var mapping = new DcwServiceMapping(1L, "profile1", 0x1884, provider, sidA, sidB, true, true, 0L, 1L, 0L);
    var explicit = new DcwRule(1L, new DcwRuleScope("profile1", 0x1884, provider, 0, null), null,
        DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "", true, 0L, 0L);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.ENFORCE, true, List.of(explicit), List.of(mapping)));
    CwReply result = router.routeEcm(req, auth, provider).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty());
    assertEquals(keyA, result.key(), "réponse servie sous la clé demandée");
    assertEquals(sidA, result.key().serviceId());
    assertArrayEquals(VALID_CW, result.cw());
    assertEquals("c2", result.connectorName());
    StrictCacheKey requestKey = new StrictCacheKey("profile1", 0x1884, provider, sidA, 0x80, body, 1L, Set.of("c1", "c2"));
    assertNull(cache.peek(requestKey), "référence liée non recopiée sur le SID demandeur");
  }

  @Test
  @DisplayName("OBSERVE : opportunité enregistrée, aucune CW corroborée servie")
  void observeDoesNotServeReference() {
    var observed = runRepairScenario(DcwRepairMode.OBSERVE);
    assertTrue(observed.stream().anyMatch(a -> a.type() == DcwObservationTracker.AnomalyType.REPAIR_OPPORTUNITY), observed.toString());
  }

  @Test
  @DisplayName("Repair OFF : aucun diagnostic de réparation")
  void repairOffNoDiagnostic() {
    var off = runRepairScenario(DcwRepairMode.OFF);
    assertTrue(off.stream().noneMatch(a -> a.type() == DcwObservationTracker.AnomalyType.REPAIR_OPPORTUNITY));
  }

  @Test
  @DisplayName("autoObserveEnabled=false : aucune auto-observation (anomalies checksum, liens candidats, cycles)")
  void autoObserveFlagGatesObservation() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    var c1 = new TestConnector(vertx, "c1", "profile1", new CwReply(key, 0x80, BAD_CHECKSUM_CW, "c1", "127.0.0.1", 0L));
    c1.setPriority(10);
    var c2 = new TestConnector(vertx, "c2", "profile1", new CwReply(key, 0x80, VALID_CW, "c2", "127.0.0.1", 0L));
    c2.setPriority(5);
    connectorManager.addTestConnector(c1);
    connectorManager.addTestConnector(c2);
    DcwObservationTracker tracker = new DcwObservationTracker(100);
    InterProviderLinkObserver links = new InterProviderLinkObserver(100);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, ChecksumMode.REQUIRE_VALID, true, List.of()));
    router.setObservationTracker(tracker);
    router.setLinkObserver(links);
    CwReply r = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertEquals("c2", r.connectorName());
    assertTrue(tracker.recentAnomalies().isEmpty(), tracker.recentAnomalies().toString());
  }

  @Test
  @DisplayName("BUSY single-flight : compteur charge incrementé, pas compté en échec de qualité")
  void busyIsNotQualityFailure() {
    EcmKey key = new EcmKey(0x80, 0x2938, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1", System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L, Instant.now().plusSeconds(3600), 1L,
        Set.of("profile1"), null, null, null, null);
    connectorManager.addTestConnector(new AbstractConnector(vertx, null, "busy", "127.0.0.1", 10000, "profile1") {
      { connected = true; }
      @Override public Future<Void> start() { return Future.succeededFuture(); }
      @Override public void stop() {}
      @Override public Future<LoginState> loginFuture() { return Future.succeededFuture(LoginState.OK); }
      @Override public boolean isConnected() { return true; }
      @Override public Future<CwReply> sendEcm(EcmRequest request) {
        return Future.failedFuture(new com.bowman.cspng.connectors.ConnectorBusyException());
      }
    });
    var router = new StrictEcmRouter(connectorManager, cache, null);
    var result = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertTrue(result.isEmpty());
    var view = router.adaptiveRoutingEngine().getStatsView();
    var scores = (java.util.List<?>) view.get("scores");
    assertEquals(1, scores.size());
    var score = (java.util.Map<?, ?>) scores.get(0);
    assertEquals(1L, score.get("busy"));
    assertEquals(0L, score.get("fail"));
    assertEquals(0L, score.get("samples"));
  }

  // ==================================================================
  // T3 — résolution de mapping DANS le lookup CacheEx :
  //   * un mapping CAID:Provider global (SID wildcard) résout le lookup
  //     sur le couple cible, à SID demandé identique ;
  //   * un mapping par SID (même CAID:Provider) résout le lookup sur le
  //     SID lié ;
  //   * la résolution est MONO-SAut (jamais A -> B -> C) ;
  //   * l'ACL service du client porte aussi sur le SID cible : un mapping
  //     ne contourne JAMAIS un service bloqué (lookup CacheEx ET
  //     corroboration de réparation).
  // ==================================================================

  @Test
  @DisplayName("CacheEx : un mapping global CAID:Provider approuvé résout le lookup sur le couple cible (même SID)")
  void cacheExLookupResolvesGlobalCaidProviderMapping() {
    int sid = 0x32DC;
    int reqCaid = 0x1884;
    int reqProvider = 0x000000;
    EcmKey key = new EcmKey(0x80, sid, 0, reqCaid, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1",
        System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("profile1"), null, null, null, null);

    // Mapping global : le couple 0x1884:0x000000 et le couple 0x1813:0x000068 sont liés, SID inchangé.
    var globalMapping = new DcwServiceMapping(1L, "profile1", 0x1884, 0x000000, 0x1813, 0x000068,
        0, 0, true, true, 0L, 1L, 0L);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.OFF, true, List.of(), List.of(globalMapping)));
    // Le push CacheEx n'existe que sous le couple CIBLE (0x1813:0x000068).
    router.setCacheExLookup((prof, c, p, s, oe, h) -> (c == 0x1813 && p == 0x000068 && s == 0x32DC)
        ? new CwReply(new EcmKey(oe, s, 0, c, h), oe, VALID_CW, "L3:@STB@", "77.90.60.222", System.currentTimeMillis())
        : null);

    CwReply result = router.routeEcm(req, auth, reqProvider).toCompletionStage().toCompletableFuture().join();
    assertFalse(result.isEmpty(), "le mapping CAID:Provider global doit alimenter le lookup CacheEx");
    assertArrayEquals(VALID_CW, result.cw());
    assertEquals("L3:@STB@", result.connectorName(), "provenance du push CacheEx conservée");
    assertEquals(0x1884, result.key().caId());
    assertEquals(0x32DC, result.key().serviceId());
  }

  @Test
  @DisplayName("CacheEx : le SID cible d'un mapping bloqué par l'ACL du client n'est jamais servi")
  void cacheExLookupMappedTargetSidBlockedByAclIsRejected() {
    int sidA = 0x0010;
    int sidB = 0x0020;
    EcmKey key = new EcmKey(0x80, sidA, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1",
        System.currentTimeMillis(), null, 2000L);
    var blockedAuth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("profile1"), null,
        Map.of("profile1", Set.of(sidB)), null, null);
    var allowedAuth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("profile1"), null, null, null, null);

    var sidMapping = new DcwServiceMapping(1L, "profile1", 0x1884, 0x000000, 0x1884, 0x000000,
        sidA, sidB, true, true, 0L, 1L, 0L);

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.OFF, true, List.of(), List.of(sidMapping)));
    // Le push CacheEx n'existe que sous le SID LIÉ (0x0020).
    router.setCacheExLookup((prof, c, p, s, oe, h) -> s == 0x0020
        ? new CwReply(new EcmKey(oe, s, 0, c, h), oe, VALID_CW, "L3:@STB@", "77.90.60.222", System.currentTimeMillis())
        : null);

    CwReply blocked = router.routeEcm(req, blockedAuth, 0).toCompletionStage().toCompletableFuture().join();
    assertTrue(blocked.isEmpty(),
        "le SID cible 0x0020 est bloqué pour ce client : le mapping ne doit pas contourner l'ACL, got "
            + blocked.connectorName());

    // Contrôle de cohérence : sans blocage ACL, le SID lié est bien consulté — l'assertion
    // ci-dessus n'est donc pas satisfaite par simple absence de résolution du mapping.
    CwReply allowed = router.routeEcm(req, allowedAuth, 0).toCompletionStage().toCompletableFuture().join();
    assertFalse(allowed.isEmpty(), "sans blocage ACL, le lookup sur le SID lié 0x0020 doit servir la CW");
    assertArrayEquals(VALID_CW, allowed.cw());
  }

  @Test
  @DisplayName("CacheEx : la résolution de mapping est mono-saut (A->B) et jamais transitive (A->B->C)")
  void cacheExLookupMappingIsSingleHopNotTransitive() {
    int sid = 0x32DC;
    EcmKey key = new EcmKey(0x80, sid, 0, 0x1884, 0x12345678);
    EcmRequest req = new EcmRequest(key, new byte[10], "profile1", "127.0.0.1", "client1",
        System.currentTimeMillis(), null, 2000L);
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("profile1"), null, null, null, null);

    // A (0x1884:0x000000) <-> B (0x1813:0x000068) <-> C (0x0100:0x000068)
    var ab = new DcwServiceMapping(1L, "profile1", 0x1884, 0x000000, 0x1813, 0x000068,
        0, 0, true, true, 0L, 1L, 0L);
    var bc = new DcwServiceMapping(2L, "profile1", 0x1813, 0x000068, 0x0100, 0x000068,
        0, 0, true, true, 0L, 1L, 0L);

    // Identité sous laquelle le push CacheEx est disponible (contrôlée par le test).
    AtomicInteger seedCaid = new AtomicInteger(0x0100);
    AtomicInteger seedProvider = new AtomicInteger(0x000068);
    List<String> consulted = new ArrayList<>();

    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.OFF, true, List.of(), List.of(ab, bc)));
    router.setCacheExLookup((prof, c, p, s, oe, h) -> {
      consulted.add(String.format("0x%04X:0x%06X:0x%04X", c, p, s));
      if (c == seedCaid.get() && p == seedProvider.get() && s == sid) {
        return new CwReply(new EcmKey(oe, s, 0, c, h), oe, VALID_CW, "L3:@STB@", "77.90.60.222", System.currentTimeMillis());
      }
      return null;
    });

    // 1) La CW n'est disponible que sous C : aucune transitivité A -> B -> C.
    CwReply transitive = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertTrue(transitive.isEmpty(),
        "A->B->C interdit (un seul saut autorisé), got " + transitive.connectorName());
    assertFalse(consulted.stream().anyMatch(id -> id.equals("0x0100:0x000068:0x32DC")),
        "l'identité de C ne doit jamais être consultée : " + consulted);

    // 2) Contrôle : la CW est disponible sous B -> servie en un seul saut.
    seedCaid.set(0x1813);
    seedProvider.set(0x000068);
    CwReply singleHop = router.routeEcm(req, auth, 0).toCompletionStage().toCompletableFuture().join();
    assertFalse(singleHop.isEmpty(), "un seul saut A->B doit servir la CW, consultés : " + consulted);
    assertArrayEquals(VALID_CW, singleHop.cw());
  }

  @Test
  @DisplayName("ENFORCE : corroboration sur le SID cible d'un mapping bloqué par l'ACL du client refusée")
  void peekCorroborationMappedTargetSidBlockedByAclIsIgnored() {
    int sidA = 0x2938;
    int sidB = 0x2940;
    int provider = 0x30;
    EcmKey keyA = new EcmKey(0x80, sidA, 0, 0x1884, 0x12345678);
    EcmKey keyB = new EcmKey(0x80, sidB, 0, 0x1884, 0x12345678);
    byte[] body = new byte[10];
    EcmRequest req = new EcmRequest(keyA, body, "profile1", "127.0.0.1", "client1",
        System.currentTimeMillis(), null, 2000L);
    // Le client demande sidA (autorisé) mais sidB est bloqué pour lui.
    var auth = new ProtocolAuthResolver.AuthResult("user1", "group1", 1L, 1L,
        Instant.now().plusSeconds(3600), 1L, Set.of("profile1"), null,
        Map.of("profile1", Set.of(sidB)), null, null);
    byte[] corrupted = VALID_CW.clone();
    corrupted[3] = 0;
    corrupted[7] = 0;
    corrupted[11] = 0;
    corrupted[15] = 0;
    connectorManager.addTestConnector(new TestConnector(vertx, "c1", "profile1",
        new CwReply(keyA, 0x80, corrupted, "c1", "127.0.0.1", 0L)));
    connectorManager.addTestConnector(new TestConnector(vertx, "c2", "profile1", CwReply.empty(keyA)));
    StrictCacheKey linked = new StrictCacheKey("profile1", 0x1884, provider, sidB, 0x80, body, 1L, Set.of("c1", "c2"));
    cache.put(linked, new CwReply(keyB, 0x80, VALID_CW, "c2", "127.0.0.1", System.currentTimeMillis()), 60000);
    var mapping = new DcwServiceMapping(1L, "profile1", 0x1884, provider, sidA, sidB, true, true, 0L, 1L, 0L);
    var explicit = new DcwRule(1L, new DcwRuleScope("profile1", 0x1884, provider, 0, null), null,
        DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "", true, 0L, 0L);
    StrictEcmRouter router = new StrictEcmRouter(connectorManager, cache, null);
    router.setDcwPolicy(new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT,
        DcwRepairMode.ENFORCE, true, List.of(explicit), List.of(mapping)));
    CwReply result = router.routeEcm(req, auth, provider).toCompletionStage().toCompletableFuture().join();
    assertTrue(result.isEmpty(),
        "le SID cible 0x2940 est bloqué : aucune corroboration servie, got " + result.connectorName());
  }
}
