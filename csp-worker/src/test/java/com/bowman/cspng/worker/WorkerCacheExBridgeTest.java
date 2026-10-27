package com.bowman.cspng.worker;

import com.bowman.cspng.connectors.cacheex.CacheExBridge;
import com.bowman.cspng.connectors.cacheex.CacheExEmitter;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pont CacheEx worker (C1) — sans socket : réception → store hash, lookup,
 * forwarding avec chemin complet, fan-out onLocalFeed, bornes.
 */
@DisplayName("WorkerCacheExBridge — store, lookup, forwarding")
class WorkerCacheExBridgeTest {

  static byte[] validCw(int seed) {
    byte[] cw = new byte[16];
    for (int g = 0; g < 4; g++) {
      cw[g * 4] = (byte) (seed + g * 3 + 1);
      cw[g * 4 + 1] = (byte) (seed + g * 3 + 2);
      cw[g * 4 + 2] = (byte) (seed + g * 3 + 3);
      cw[g * 4 + 3] = (byte) ((cw[g * 4] + cw[g * 4 + 1] + cw[g * 4 + 2]) & 0xFF);
    }
    return cw;
  }

  static final byte[] NODE_X = {0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38};

  /** Émetteur enregistreur (simule connecteur/session mode émission). */
  static class RecordingEmitter implements CacheExEmitter {
    final List<CwReply> pushed = new ArrayList<>();
    int notedCycle = -1;
    List<byte[]> notedPath;

    @Override
    public void noteUpstreamPath(int dataHash, List<byte[]> upstreamPath,
                                 int cycleTimeSec, boolean nextCwCycle) {
      notedPath = List.copyOf(upstreamPath);
      notedCycle = cycleTimeSec;
    }

    @Override
    public void onReply(CwReply reply) {
      pushed.add(reply);
    }

    @Override
    public void onRequest(com.bowman.cspng.domain.EcmRequest request) {
    }
  }

  private static io.vertx.core.Vertx vertx;

  private static synchronized StrictEcmRouter dummyRouter() {
    if (vertx == null) vertx = io.vertx.core.Vertx.vertx();
    return new StrictEcmRouter(
        new com.bowman.cspng.connectors.CwsConnectorManager(vertx, null));
  }

  @org.junit.jupiter.api.AfterAll
  static void closeVertx() throws Exception {
    if (vertx != null) {
      java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(5, java.util.concurrent.TimeUnit.SECONDS);
      vertx = null;
    }
  }

  @Test
  @DisplayName("feedReceived stocke + lookup sert (même clé), miss sinon")
  void feedThenLookupHit() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());

    byte[] cw = validCw(5);
    assertTrue(bridge.feedReceived("default", 7L, 0x0100, 0x6A, 0x1234, 0x80,
        cw, 0xABCDEF01, 10, true, List.of(NODE_X), "10.0.0.2:12000", "up-conn"));
    assertEquals(1, bridge.storeSize());

    CwReply hit = bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x80, 0xABCDEF01);
    assertNotNull(hit, "lookup doit servir la CW poussée");
    assertArrayEquals(cw, hit.cw());

    // Clés voisines => miss (pas de confusion)
    assertNull(bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x81, 0xABCDEF01), "parité");
    assertNull(bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x80, 0xABCDEF02), "hash");
    assertNull(bridge.lookup("default", 0x0500, 0x6A, 0x1234, 0x80, 0xABCDEF01), "caid");
    assertNull(bridge.lookup("other", 0x0100, 0x6A, 0x1234, 0x80, 0xABCDEF01), "profil");
  }

  @Test
  @DisplayName("Compat OSCam : stocké sous 0 servi pour requête 0x80/0x81 (fallback exact-puis-unknown)")
  void unknownParityFallbackServes() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());

    byte[] cw = validCw(15);
    // PUSH à parité inconnue (0)
    assertTrue(bridge.feedReceived("default", 7L, 0x0100, 0x6A, 0x1234, 0,
        cw, 0xDEF00001, 0, false, List.of(NODE_X), "10.0.0.2:12000", "up-conn"));

    // Requête 0x80 : miss exact, HIT fallback unknown — octets intacts
    CwReply hit80 = bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x80, 0xDEF00001);
    assertNotNull(hit80, "fallback unknown doit servir la requête 0x80");
    assertArrayEquals(cw, hit80.cw());
    // Requête 0x81 : idem, sans contamination croisée
    CwReply hit81 = bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x81, 0xDEF00001);
    assertNotNull(hit81, "fallback unknown doit servir la requête 0x81");
    assertArrayEquals(cw, hit81.cw());
    // Lookup exact 0 : hit direct
    assertNotNull(bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0, 0xDEF00001));
    // Autres dimensions : toujours miss (pas de wildcard)
    assertNull(bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x80, 0xDEF00002), "hash");
    assertNull(bridge.lookup("default", 0x0500, 0x6A, 0x1234, 0x80, 0xDEF00001), "caid");
    assertNull(bridge.lookup("default", 0x0100, 0x6B, 0x1234, 0x80, 0xDEF00001), "provider");
    assertNull(bridge.lookup("default", 0x0100, 0x6A, 0x1235, 0x80, 0xDEF00001), "sid");
  }

  @Test
  @DisplayName("Exact prime sur unknown : une entrée 0x80 connue gagne sur le fallback")
  void exactBeatsUnknownFallback() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());

    byte[] cwKnown = validCw(21);
    byte[] cwUnknown = validCw(22);
    assertTrue(bridge.feedReceived("default", 7L, 0x0100, 0x6A, 0x1234, 0,
        cwUnknown, 0xDEF00002, 0, false, List.of(NODE_X), "10.0.0.2:12000", "up-conn"));
    assertTrue(bridge.feedReceived("default", 7L, 0x0100, 0x6A, 0x1234, 0x80,
        cwKnown, 0xDEF00002, 0, false, List.of(NODE_X), "10.0.0.2:12000", "up-conn"));

    CwReply hit = bridge.lookup("default", 0x0100, 0x6A, 0x1234, 0x80, 0xDEF00002);
    assertNotNull(hit);
    assertArrayEquals(cwKnown, hit.cw(), "l'entrée exacte prime, pas le fallback");
  }

  @Test
  @DisplayName("Profil inconnu / CW invalide => refus explicite (false)")
  void unknownProfileOrInvalidCwRefused() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());

    assertFalse(bridge.feedReceived("nope", 99L, 0x0100, 0, 1, 0x80,
        validCw(1), 42, 0, false, List.of(), "1.2.3.4:5", "x"),
        "profil non servi => false");
    assertFalse(bridge.feedReceived("default", 7L, 0x0100, 0, 1, 0x80,
        new byte[16], 42, 0, false, List.of(), "1.2.3.4:5", "x"),
        "CW nulle => false");
    assertEquals(0, bridge.storeSize());
  }

  @Test
  @DisplayName("Forwarding : chemin complet + cycle propagés aux émetteurs, pas de reflet")
  void forwardingPreservesPathAndCycle() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());
    RecordingEmitter emitter = new RecordingEmitter();
    bridge.registerEmitter("up-emit", emitter);

    byte[] cw = validCw(9);
    assertTrue(bridge.feedReceived("default", 7L, 0x0100, 0x6A, 0x1234, 0x80,
        cw, 0x11111111, 12, true, List.of(NODE_X), "10.0.0.2:12000", "up-conn"));

    // L'émetteur a reçu le chemin COMPLET + cycle pour son re-push
    assertNotNull(emitter.notedPath, "chemin propagé à la cible");
    assertEquals(1, emitter.notedPath.size());
    assertArrayEquals(NODE_X, emitter.notedPath.get(0));
    assertEquals(12, emitter.notedCycle, "cycletime propagé");
    // ... et la CW à pousser (l'anti-reflet est appliqué par la cible via originAddr)
    assertEquals(1, emitter.pushed.size());
    assertArrayEquals(cw, emitter.pushed.get(0).cw());
    assertEquals("10.0.0.2:12000", emitter.pushed.get(0).originAddress(),
        "origin préservée pour l'anti-reflet côté cible");
  }

  @Test
  @DisplayName("onLocalFeed : fan-out vers les émetteurs (dispatch local)")
  void localFeedFansOut() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    RecordingEmitter emitter = new RecordingEmitter();
    bridge.registerEmitter("up-emit", emitter);

    EcmKey key = new EcmKey(0x80, 0x1234, 0, 0x0100, 0x22222222);
    CwReply reply = new CwReply(key, 0x80, validCw(13), "local-conn", "127.0.0.1:1000",
        System.currentTimeMillis());
    bridge.onLocalFeed(reply);

    assertEquals(1, emitter.pushed.size());
    assertArrayEquals(validCw(13), emitter.pushed.get(0).cw());
    // Pas de chemin amont pour une CW fraîche => rien noté
    assertNull(emitter.notedPath);
  }

  @Test
  @DisplayName("detachRouter : feed refusé, unregisterEmitter stoppe le fan-out")
  void detachStopsFeedAndEmit() {
    WorkerCacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    bridge.attachRouter(7L, "default", dummyRouter());
    RecordingEmitter emitter = new RecordingEmitter();
    bridge.registerEmitter("L7:alice", emitter);

    bridge.detachRouter(7L);
    assertFalse(bridge.feedReceived("default", 7L, 0x0100, 0, 1, 0x80,
        validCw(1), 42, 0, false, List.of(), "1.2.3.4:5", "x"));

    bridge.unregisterEmitter("L7:alice");
    EcmKey key = new EcmKey(0x80, 1, 0, 0x0100, 43);
    bridge.onLocalFeed(new CwReply(key, 0x80, validCw(2), "c", "h", System.currentTimeMillis()));
    assertTrue(emitter.pushed.isEmpty(), "cible désenregistrée => plus de fan-out");
  }

  @Test
  @DisplayName("Interface CacheExBridge respectée (feed + register/unregister)")
  void interfaceCompliance() {
    CacheExBridge bridge = new WorkerCacheExBridge(new CacheExStatsRegistry());
    RecordingEmitter emitter = new RecordingEmitter();
    bridge.registerEmitter("k", emitter);
    bridge.unregisterEmitter("k");
    // Pas d'exception sur chemins null-safety
    assertFalse(bridge.feedReceived(null, 1L, 0, 0, 0, 0x80, validCw(1), 1,
        0, false, null, null, null));
  }
}
