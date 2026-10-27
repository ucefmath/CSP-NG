package com.bowman.cspng.connectors.cacheex;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 2 : fenêtre temporelle glissante, population éligible, horloge injectable. */
@DisplayName("PeerQualityTracker — fenêtre temporelle, éligibilité, bornes")
class PeerQualityTrackerTest {

  static final class ManualClock extends Clock {
    final AtomicLong now = new AtomicLong(1_000_000L);
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    @Override public long millis() { return now.get(); }
  }

  @Test
  @DisplayName("accepted+invalid alimentent la fenêtre, ratio en points de base")
  void eligiblePopulationAndRatio() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker t = new PeerQualityTracker(clock, 60_000L);
    t.recordAccepted("peerA");
    t.recordAccepted("peerA");
    t.recordInvalid("peerA");
    var q = t.quality("peerA").orElseThrow();
    assertEquals(2, q.accepted());
    assertEquals(1, q.invalid());
    assertEquals(3, q.total());
    assertEquals(3_333, q.invalidRatioBps());
  }

  @Test
  @DisplayName("la fenêtre temporelle expire les vieux événements")
  void temporalWindowExpiry() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker t = new PeerQualityTracker(clock, 10_000L);
    t.recordAccepted("peerA");
    t.recordInvalid("peerA");
    clock.now.addAndGet(20_000L);
    t.recordAccepted("peerA");
    var q = t.quality("peerA").orElseThrow();
    assertEquals(1, q.total());
    assertEquals(1, q.accepted());
    assertEquals(0, q.invalid());
  }

  @Test
  @DisplayName("pair inconnu = vide, jamais de zéros fabriqués")
  void unknownPeerEmpty() {
    PeerQualityTracker t = new PeerQualityTracker(new ManualClock(), 60_000L);
    assertTrue(t.quality("ghost").isEmpty());
    assertTrue(t.snapshot().isEmpty());
  }

  @Test
  @DisplayName("peerId null/vide ignoré, pas de NPE")
  void nullPeerIgnored() {
    PeerQualityTracker t = new PeerQualityTracker(new ManualClock(), 60_000L);
    assertDoesNotThrow(() -> { t.recordAccepted(null); t.recordInvalid("  "); });
    assertTrue(t.snapshot().isEmpty());
  }

  @Test
  @DisplayName("régression : minSamples=5000 (borne PeerQuarantineConfig max=10000) atteignable, "
      + "pas plafonné par MAX_EVENTS_PER_PEER")
  void highMinSamplesReachable() {
    ManualClock clock = new ManualClock();
    // Fenêtre large (1h) : tous les événements restent dans la fenêtre temporelle,
    // seule la borne de comptage MAX_EVENTS_PER_PEER peut plafonner le total.
    PeerQualityTracker t = new PeerQualityTracker(clock, 3_600_000L);
    for (int i = 0; i < 5_000; i++) t.recordAccepted("peerA");
    var q = t.quality("peerA").orElseThrow();
    assertEquals(5_000, q.total(), "5000 échantillons doivent être comptés sans perte");
  }

  @Test
  @DisplayName("concurrence et saturation (B3) : éviction hors computeIfAbsent sans ConcurrentModificationException")
  void concurrentSaturationNoRecursiveUpdate() throws Exception {
    ManualClock clock = new ManualClock();
    PeerQualityTracker t = new PeerQualityTracker(clock, 300_000L);
    int threads = 8;
    int itemsPerThread = 200;
    var latch = new java.util.concurrent.CountDownLatch(threads);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
    for (int i = 0; i < threads; i++) {
      final int threadId = i;
      executor.submit(() -> {
        try {
          for (int j = 0; j < itemsPerThread; j++) {
            String peer = "peer-" + (threadId * itemsPerThread + j);
            t.recordAccepted(peer);
            t.recordInvalid(peer);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
    executor.shutdown();
    assertTrue(t.size() <= PeerQualityTracker.MAX_PEERS);
  }
}
