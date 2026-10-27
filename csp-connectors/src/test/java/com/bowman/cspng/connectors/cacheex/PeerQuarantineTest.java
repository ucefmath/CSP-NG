package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.PeerQuarantineConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Lot 4 : opt-in off défaut, observe défaut, minimum échantillons, expiration, levée. */
@DisplayName("PeerQuarantine — opt-in, observe/enforce, expiration, levée")
class PeerQuarantineTest {

  static final class ManualClock extends Clock {
    final AtomicLong now = new AtomicLong(1_000_000L);
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    @Override public long millis() { return now.get(); }
  }

  private static PeerQuarantineConfig cfg(String mode) {
    return new PeerQuarantineConfig(true, mode, 10, 5_000, 300_000L, 60_000L, 256, 1L);
  }

  @Test
  @DisplayName("désactivé par défaut : aucune décision, aucun skip")
  void disabledByDefault() {
    ManualClock clock = new ManualClock();
    PeerQuarantine q = new PeerQuarantine(clock);
    assertFalse(q.evaluate("p1", 100, 9_000, PeerQuarantineConfig.DEFAULT));
    assertFalse(q.shouldSkip("p1", PeerQuarantineConfig.DEFAULT));
    assertTrue(q.snapshot().isEmpty());
  }

  @Test
  @DisplayName("minimum échantillons exigé")
  void minSamplesRequired() {
    ManualClock clock = new ManualClock();
    PeerQuarantine q = new PeerQuarantine(clock);
    var c = cfg("enforce");
    assertFalse(q.evaluate("p1", 9, 10_000, c));
    assertTrue(q.snapshot().isEmpty());
  }

  @Test
  @DisplayName("observe : compté mais jamais exclu")
  void observeNeverSkips() {
    ManualClock clock = new ManualClock();
    PeerQuarantine q = new PeerQuarantine(clock);
    var c = cfg("observe");
    assertTrue(q.evaluate("p1", 50, 9_000, c));
    assertFalse(q.shouldSkip("p1", c));
    assertEquals(1, q.observedCount());
    assertEquals(0, q.enforcedCount());
  }

  @Test
  @DisplayName("enforce : exclu puis expiré puis levée admin")
  void enforceExpireRelease() {
    ManualClock clock = new ManualClock();
    PeerQuarantine q = new PeerQuarantine(clock);
    var c = cfg("enforce");
    assertTrue(q.evaluate("p1", 50, 9_000, c));
    assertTrue(q.shouldSkip("p1", c));
    assertTrue(q.release("p1"));
    assertFalse(q.shouldSkip("p1", c));
    assertTrue(q.evaluate("p1", 50, 9_000, c));
    clock.now.addAndGet(61_000L);
    assertFalse(q.shouldSkip("p1", c));
  }

  @Test
  @DisplayName("régression : cfg.maxPeers() réellement appliqué (pas ignoré au profit d'une "
      + "constante fixe) — éviction dès que la borne CONFIGURÉE est atteinte")
  void configuredMaxPeersHonored() {
    ManualClock clock = new ManualClock();
    PeerQuarantine q = new PeerQuarantine(clock);
    // maxPeers=16 (bien en-deçà de l'ancien MAX_ENTRIES=256) : si la config
    // était ignorée, les 17 pairs coexisteraient tous ; ici le plus ancien
    // (par expiresAtMs) doit être évincé pour respecter la borne LIVE.
    var c = new PeerQuarantineConfig(true, "observe", 2, 0, 300_000L, 60_000L, 16, 1L);
    for (int i = 0; i < 17; i++) {
      assertTrue(q.evaluate("p" + i, 10, 10_000, c));
      clock.now.addAndGet(1L);
    }
    assertTrue(q.snapshot().size() <= 16,
        "cfg.maxPeers()=16 doit borner réellement le nombre d'entrées, eu : " + q.snapshot().size());
  }
}
