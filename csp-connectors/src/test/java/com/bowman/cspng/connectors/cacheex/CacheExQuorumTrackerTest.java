package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.CacheExQuorumConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lot 5 : comparaison exacte de CW valides, double exigence pairs + origines B1,
 * quota 1 vote par pair et anti-starvation R1, OFF/OBSERVE/ENFORCE, expiration.
 */
@DisplayName("CacheExQuorumTracker — quorum exact, double exigence pairs et origines")
class CacheExQuorumTrackerTest {

  static final class ManualClock extends Clock {
    final AtomicLong now = new AtomicLong(1_000_000L);
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    @Override public long millis() { return now.get(); }
  }

  private static byte[] cw(int fill) {
    byte[] b = new byte[16];
    for (int i = 0; i < 16; i++) b[i] = (byte) (fill + i);
    // checksum DVB valide sur les 4 triplets
    for (int i = 0; i < 16; i += 4) {
      b[i + 3] = (byte) ((b[i] + b[i + 1] + b[i + 2]) & 0xFF);
    }
    return b;
  }

  private static CacheExQuorumConfig cfg(String mode) {
    return new CacheExQuorumConfig(mode, 2, 30_000L, 1_024, List.of(), List.of(), 1L);
  }

  private static CacheExQuorumTracker.QuorumKey key(int parity) {
    return new CacheExQuorumTracker.QuorumKey("prof", 0x100, 0, 100, parity, 0xdead);
  }

  @Test
  @DisplayName("off = bypass total")
  void offBypass() {
    CacheExQuorumTracker t = new CacheExQuorumTracker(new ManualClock());
    assertEquals(CacheExQuorumTracker.Decision.BYPASSED,
        t.observe(key(0x80), cw(1), "peerA", "10.0.0.1:12000", cfg("off")));
    assertEquals(0, t.size());
  }

  @Test
  @DisplayName("enforce : pending puis quorum à 2 pairs authentifiés ET 2 origines distinctes")
  void enforcePendingThenQuorum() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    byte[] valid = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "peerA", "node:0101010101010101", c));
    // même pair répété : toujours pending (1 pair = 1 vote)
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "peerA", "node:0101010101010101", c));
    assertEquals(CacheExQuorumTracker.Decision.QUORUM, t.observe(key(0x80), valid, "peerB", "node:0202020202020202", c));
  }

  @Test
  @DisplayName("sécurité B1 : un seul peerId forgeant 2 node_ids distincts ne fait PAS quorum")
  void singlePeerForgingMultipleNodeIdsFails() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    byte[] valid = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "attacker", "node:forged11111111", c));
    // Le même attaquant renvoie la même CW avec un autre node_id forgé -> distinctPeers reste 1 -> PAS DE QUORUM!
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "attacker", "node:forged22222222", c));
    assertEquals(0, t.quorumCount());
  }

  @Test
  @DisplayName("sécurité B1 : un seul peerId alternant node_id présent et absent ne fait PAS quorum")
  void singlePeerAlternatingPresentAbsentNodeIdFails() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    byte[] valid = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "attacker", "node:someNodeId", c));
    // 2e push sans node_id (fallback transport) depuis le même attaquant -> distinctPeers reste 1
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "attacker", "192.168.1.100:12000", c));
    assertEquals(0, t.quorumCount());
  }

  @Test
  @DisplayName("provenance réelle (B1) : deux pairs distincts relayant la MÊME origine ne font PAS quorum")
  void twoPeersSameOriginDoesNotQuorum() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    byte[] valid = cw(1);
    String sameOrigin = "node:originAlpha";
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "relay1", sameOrigin, c));
    // 2e pair distinct relayant la même source originAlpha -> distinctOrigins=1 < 2 -> reste PENDING!
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "relay2", sameOrigin, c));
    assertEquals(0, t.quorumCount());

    // 3e pair avec une vraie autre source -> QUORUM!
    assertEquals(CacheExQuorumTracker.Decision.QUORUM, t.observe(key(0x80), valid, "relay3", "node:originBeta", c));
    assertEquals(1, t.quorumCount());
  }

  @Test
  @DisplayName("enforce : CW divergente = mismatch, jamais injectée")
  void mismatchNeverInjects() {
    CacheExQuorumTracker t = new CacheExQuorumTracker(new ManualClock());
    var c = cfg("enforce");
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x81), cw(1), "peerA", "node:1111", c));
    assertEquals(CacheExQuorumTracker.Decision.MISMATCH, t.observe(key(0x81), cw(40), "peerB", "node:2222", c));
    assertEquals(1, t.mismatchCount());
  }

  @Test
  @DisplayName("parité exacte : 0 distinct de 0x80 (pas de contamination OSCam)")
  void parityExact() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    byte[] valid = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0), valid, "peerA", "node:1111", c));
    // même hash mais parité 0x80 = autre clé : pending indépendant
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), valid, "peerB", "node:2222", c));
    assertEquals(2, t.size());
  }

  @Test
  @DisplayName("expiration : aucune sortie après la fenêtre")
  void expiredAfterWindow() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), cw(1), "peerA", "node:1111", c));
    clock.now.addAndGet(31_000L);
    assertEquals(CacheExQuorumTracker.Decision.EXPIRED, t.observe(key(0x80), cw(1), "peerB", "node:2222", c));
  }

  @Test
  @DisplayName("régression : evictExpired() libère activement une clé PENDING jamais retouchée")
  void evictExpiredReleasesStalePendingKey() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");
    assertEquals(CacheExQuorumTracker.Decision.PENDING, t.observe(key(0x80), cw(1), "peerA", "node:1111", c));
    assertEquals(1, t.size());
    clock.now.addAndGet(c.windowMs() + 1_000L);
    t.evictExpired(c.windowMs());
    assertEquals(0, t.size());
    assertTrue(t.keySnapshot(10).isEmpty());
  }

  @Test
  @DisplayName("observe : opportunité comptée sans bloquer")
  void observeCounts() {
    CacheExQuorumTracker t = new CacheExQuorumTracker(new ManualClock());
    var c = cfg("observe");
    byte[] valid = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.OBSERVED, t.observe(key(0x80), valid, "peerA", "node:1111", c));
    assertEquals(CacheExQuorumTracker.Decision.QUORUM, t.observe(key(0x80), valid, "peerB", "node:2222", c));
    assertEquals(1, t.observedCount());
    assertEquals(1, t.quorumCount());
  }

  @Test
  @DisplayName("anti-starvation (R1) : 4 mauvais candidats de pairs distincts n'empêchent pas la bonne CW de 2 pairs honnêtes")
  void fourBadCandidatesBeforeGoodQuorum() {
    ManualClock clock = new ManualClock();
    CacheExQuorumTracker t = new CacheExQuorumTracker(clock);
    var c = cfg("enforce");

    // 4 distinct bad peers submit 4 distinct bad CWs
    for (int i = 0; i < 4; i++) {
      byte[] bad = cw(10 + i * 5);
      t.observe(key(0x80), bad, "badPeer" + i, "node:bad" + i, c);
    }

    // Now 2 honest peers arrive with good CW
    byte[] good = cw(1);
    assertEquals(CacheExQuorumTracker.Decision.MISMATCH,
        t.observe(key(0x80), good, "honestPeer1", "node:honest1", c));
    assertEquals(CacheExQuorumTracker.Decision.QUORUM,
        t.observe(key(0x80), good, "honestPeer2", "node:honest2", c));
  }
}
