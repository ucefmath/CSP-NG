package com.bowman.cspng.connectors.cacheex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 3 : hits servis exactement une fois, unknown explicite, pas de probes. */
@DisplayName("CacheHitAttribution — hits servis, unknown explicite")
class CacheHitAttributionTest {

  @Test
  @DisplayName("origine connue comptée, unknown explicite sinon")
  void knownAndUnknown() {
    CacheHitAttribution a = new CacheHitAttribution();
    a.recordServedHit("10.0.0.1:12000", "connA");
    a.recordServedHit(null, null);
    a.recordServedHit("  ", "");
    var snap = a.snapshot();
    assertEquals(2, snap.size());
    assertEquals("10.0.0.1:12000", snap.get(0).origin());
    assertEquals(1, snap.get(0).hits());
    assertEquals(CacheHitAttribution.UNKNOWN, snap.get(1).origin());
    assertEquals(2, snap.get(1).hits());
  }

  @Test
  @DisplayName("connectorName seul sert d'origine quand pas d'adresse")
  void connectorFallback() {
    CacheHitAttribution a = new CacheHitAttribution();
    a.recordServedHit(null, "connB");
    assertEquals("connB", a.snapshot().get(0).origin());
  }

  @Test
  @DisplayName("vide au départ, jamais de zéros fictifs")
  void emptyInitially() {
    assertTrue(new CacheHitAttribution().snapshot().isEmpty());
  }

  @Test
  @DisplayName("hit servi relayé au registry CacheEx avec le SID (compteurs par service)")
  void relaysServedHitToCacheExStatsRegistryWithSid() {
    CacheExStatsRegistry reg = new CacheExStatsRegistry();
    reg.registerInbound("L3:@STB@", "cs378x", 3, "default", 10, 2000);

    CacheHitAttribution attr = new CacheHitAttribution();
    attr.setCacheExStatsRegistry(reg);

    attr.recordServedHit("77.90.60.222", "L3:@STB@", 0x1884, 0x000000, 0x32DC);

    var served = attr.snapshot();
    assertEquals(1, served.size(), "le hit servi reste compté dans l'attribution d'origine");
    assertEquals(1L, served.get(0).hits());

    CacheExStatsRegistry.PairStats peer = reg.snapshot().get(0);
    assertEquals(1L, peer.hits(), "le hit servi est aussi compté sur la paire CacheEx");
    assertEquals(1, peer.sids().size());
    var sid = peer.sids().get(0);
    assertEquals("1884@000000:32DC", sid.serviceKey());
    assertEquals(1L, sid.hits());
  }

  @Test
  @DisplayName("concurrence et saturation (B3) : éviction hors computeIfAbsent")
  void concurrentSaturationNoRecursiveUpdate() throws Exception {
    CacheHitAttribution a = new CacheHitAttribution();
    int threads = 8;
    int itemsPerThread = 200;
    var latch = new java.util.concurrent.CountDownLatch(threads);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          for (int j = 0; j < itemsPerThread; j++) {
            a.recordServedHit("10.0." + threadId + "." + j + ":12000", "conn-" + j);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
    executor.shutdown();
    assertTrue(a.size() <= CacheHitAttribution.MAX_ORIGINS);
  }
}
