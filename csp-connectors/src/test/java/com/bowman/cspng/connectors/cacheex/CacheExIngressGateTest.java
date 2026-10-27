package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.PeerQuarantineConfig;
import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwRepairMode;
import com.bowman.cspng.domain.dcw.LegacyGuard;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Porte d'ingress : ordre quarantaine → policy contextuelle → quorum,
 * exceptions policy légitimes préservées, population éligible exacte.
 */
@DisplayName("CacheExIngressGate — quarantaine/policy/quorum")
class CacheExIngressGateTest {

  static final class ManualClock extends Clock {
    final AtomicLong now = new AtomicLong(1_000_000L);
    @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    @Override public long millis() { return now.get(); }
  }

  private static byte[] validCw() {
    byte[] b = new byte[16];
    for (int i = 0; i < 16; i++) b[i] = (byte) (1 + i);
    for (int i = 0; i < 16; i += 4) b[i + 3] = (byte) ((b[i] + b[i + 1] + b[i + 2]) & 0xFF);
    return b;
  }

  private static byte[] badCw() {
    byte[] b = validCw();
    b[3] = (byte) (b[3] + 1);
    return b;
  }

  private static CacheExIngressGate wired(ManualClock clock, DcwQualityPolicy policy,
                                          PeerQuarantineConfig qcfg, CacheExQuorumConfig qmcfg,
                                          PeerQualityTracker[] trackerOut) {
    PeerQualityTracker tracker = new PeerQualityTracker(clock, 3_600_000L);
    PeerQuarantine quarantine = new PeerQuarantine(clock);
    CacheExQuorumTracker quorum = new CacheExQuorumTracker(clock);
    CacheExIngressGate g = new CacheExIngressGate();
    g.setDcwPolicySupplier(() -> policy);
    g.setQuality(tracker, quarantine, () -> qcfg);
    g.setQuorum(quorum, () -> qmcfg);
    if (trackerOut != null && trackerOut.length > 0) trackerOut[0] = tracker;
    return g;
  }

  @Test
  @DisplayName("CW valide acceptée, accepted enregistré après injection")
  void validAcceptedAndRecorded() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker[] t = new PeerQualityTracker[1];
    var g = wired(clock, DcwQualityPolicy.DEFAULT, PeerQuarantineConfig.DEFAULT,
        CacheExQuorumConfig.DEFAULT, t);
    var v = g.preInject("prof", 0x100, 0, 10, 0x80, 42, validCw(),
        "connA", "10.0.0.1:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v.accept());
    g.postInjected("connA");
    var q = t[0].quality("connA").orElseThrow();
    assertEquals(1, q.accepted());
    assertEquals(0, q.invalid());
  }

  @Test
  @DisplayName("CW checksum invalide rejetée, invalid enregistré (checksum)")
  void invalidRejectedAndRecorded() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker[] t = new PeerQualityTracker[1];
    var g = wired(clock, DcwQualityPolicy.DEFAULT, PeerQuarantineConfig.DEFAULT,
        CacheExQuorumConfig.DEFAULT, t);
    var v = g.preInject("prof", 0x100, 0, 10, 0x80, 42, badCw(),
        "connA", "10.0.0.1:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertFalse(v.accept());
    assertEquals(CacheExStatsRegistry.REASON_CHECKSUM, v.dropReason());
    var q = t[0].quality("connA").orElseThrow();
    assertEquals(1, q.invalid());
  }

  @Test
  @DisplayName("exception policy légitime préservée : IGNORE scopé laisse passer")
  void scopedIgnorePreserved() {
    ManualClock clock = new ManualClock();
    var policy = new DcwQualityPolicy(true, false, false, false, ChecksumMode.IGNORE,
        DcwRepairMode.OFF, true, List.of(), List.of());
    var g = wired(clock, policy, PeerQuarantineConfig.DEFAULT, CacheExQuorumConfig.DEFAULT, null);
    var v = g.preInject("prof", 0x100, 0, 10, 0x80, 42, badCw(),
        "connA", "10.0.0.1:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v.accept(), "IGNORE explicite (provider connu) doit laisser passer");
  }

  @Test
  @DisplayName("provider inconnu + garde REQUIRED : aucune relaxation (fail-closed)")
  void unknownProviderNoRelaxation() {
    ManualClock clock = new ManualClock();
    var policy = new DcwQualityPolicy(true, false, false, false, ChecksumMode.IGNORE,
        DcwRepairMode.OFF, true, List.of(), List.of());
    var g = wired(clock, policy, PeerQuarantineConfig.DEFAULT, CacheExQuorumConfig.DEFAULT, null);
    var v = g.preInject("prof", 0x100,
        com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER, 10, 0x80, 42, badCw(),
        "connA", "10.0.0.1:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertFalse(v.accept(), "provider inconnu : REQUIRE_VALID forcé");
  }

  @Test
  @DisplayName("quarantaine enforce : skip de routage, hors population éligible")
  void quarantineEnforceSkips() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker[] t = new PeerQualityTracker[1];
    var qcfg = new PeerQuarantineConfig(true, "enforce", 2, 5_000, 300_000L, 600_000L, 256, 1L);
    var g = wired(clock, DcwQualityPolicy.DEFAULT, qcfg, CacheExQuorumConfig.DEFAULT, t);
    // 2 invalides => ratio 10000 >= 5000 avec 2 échantillons => quarantiné
    g.preInject("p", 1, 0, 1, 0x80, 1, badCw(), "connQ", "10.0.0.9:12000", LegacyGuard.CHECKSUM_REQUIRED);
    g.preInject("p", 1, 0, 1, 0x80, 1, badCw(), "connQ", "10.0.0.9:12000", LegacyGuard.CHECKSUM_REQUIRED);
    var v = g.preInject("p", 1, 0, 1, 0x80, 1, validCw(), "connQ", "10.0.0.9:12000",
        LegacyGuard.CHECKSUM_REQUIRED);
    assertFalse(v.accept());
    assertEquals(CacheExStatsRegistry.REASON_QUARANTINE, v.dropReason());
    // le skip n'est pas un verdict contenu : population inchangée (2 invalid)
    var q = t[0].quality("connQ").orElseThrow();
    assertEquals(2, q.total());
  }

  @Test
  @DisplayName("quorum enforce : pending puis quorum à 2 origines, mismatch rejeté")
  void quorumEnforceFlow() {
    ManualClock clock = new ManualClock();
    var qmcfg = new CacheExQuorumConfig("enforce", 2, 30_000L, 1_024, List.of(), List.of(), 1L);
    var g = wired(clock, DcwQualityPolicy.DEFAULT, PeerQuarantineConfig.DEFAULT, qmcfg, null);
    byte[] valid = validCw();
    var v1 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, valid,
        "connA", "10.0.0.1:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertFalse(v1.accept(), "pas de sortie avant quorum en enforce");
    assertTrue(v1.detail().contains("pending"));
    var v2 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, valid,
        "connB", "10.0.0.2:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v2.accept(), "quorum à 2 origines distinctes");
    byte[] other = validCw();
    other[0] = (byte) (other[0] + 5);
    other[3] = (byte) ((other[0] + other[1] + other[2]) & 0xFF);
    var v3 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, other,
        "connC", "10.0.0.3:12000", LegacyGuard.CHECKSUM_REQUIRED);
    assertFalse(v3.accept(), "divergence : jamais injectée");
  }

  @Test
  @DisplayName("extraction provenance réelle (B1) : dernier node_id = créateur initial, fallback transport si vide")
  void extractOriginOrder() {
    byte[] relayHop = new byte[]{ 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
    byte[] initialOrigin = new byte[]{ 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, (byte) 0x80 };
    // Multi-hop path: [relayHop, initialOrigin]
    String extracted = CacheExIngressGate.extractOrigin(List.of(relayHop, initialOrigin), "10.0.0.1:12000");
    assertEquals("node:1020304050607080", extracted, "le créateur initial est le dernier élément du chemin");

    // Single hop: [initialOrigin]
    String single = CacheExIngressGate.extractOrigin(List.of(initialOrigin), "10.0.0.1:12000");
    assertEquals("node:1020304050607080", single);

    // Empty list: fallback
    String fallback = CacheExIngressGate.extractOrigin(List.of(), "10.0.0.1:12000");
    assertEquals("10.0.0.1:12000", fallback);

    // Null list: fallback
    String nullFallback = CacheExIngressGate.extractOrigin(null, "10.0.0.1:12000");
    assertEquals("10.0.0.1:12000", nullFallback);
  }

  @Test
  @DisplayName("mode OBSERVE (B2) : toute CW valide acceptée même sur mismatch ou expiration, métriques comptées")
  void observeModeAcceptsAllValid() {
    ManualClock clock = new ManualClock();
    var qmcfg = new CacheExQuorumConfig("observe", 2, 30_000L, 1_024, List.of(), List.of(), 1L);
    PeerQualityTracker[] t = new PeerQualityTracker[1];
    var g = wired(clock, DcwQualityPolicy.DEFAULT, PeerQuarantineConfig.DEFAULT, qmcfg, t);

    byte[] validA = validCw();
    byte[] validB = validCw();
    validB[0] = (byte) (validB[0] + 3);
    validB[3] = (byte) ((validB[0] + validB[1] + validB[2]) & 0xFF);

    // 1st observation: OBSERVED -> accepted
    var v1 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, validA, "connA", "node:1111111111111111", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v1.accept(), "en OBSERVE, première CW doit être acceptée");

    // 2nd observation divergent CW: MISMATCH interne -> still accepted!
    var v2 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, validB, "connB", "node:2222222222222222", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v2.accept(), "en OBSERVE, CW divergente doit rester acceptée");

    // 3rd observation after window expiration: EXPIRED interne -> still accepted!
    clock.now.addAndGet(35_000L);
    var v3 = g.preInject("prof", 0x100, 0, 10, 0x80, 42, validA, "connC", "node:3333333333333333", LegacyGuard.CHECKSUM_REQUIRED);
    assertTrue(v3.accept(), "en OBSERVE, CW après expiration doit rester acceptée");
  }

  @Test
  @DisplayName("restauration : identité UDP jamais évaluée pour quarantaine (source usurpable)")
  void udpIdentityNeverQuarantined() {
    ManualClock clock = new ManualClock();
    PeerQualityTracker tracker = new PeerQualityTracker(clock, 3_600_000L);
    PeerQuarantine quarantine = new PeerQuarantine(clock);
    CacheExIngressGate g = new CacheExIngressGate();
    g.setDcwPolicySupplier(() -> DcwQualityPolicy.DEFAULT);
    // enforce, seuil minimal (2 échantillons, 0% requis) : le pire cas pour
    // vérifier qu'aucune évaluation de quarantaine n'est déclenchée côté UDP.
    var qcfg = new PeerQuarantineConfig(true, "enforce", 2, 0, 300_000L, 600_000L, 256, 1L);
    g.setQuality(tracker, quarantine, () -> qcfg);
    String udpPeer = "udp:203.0.113.9:12000";
    for (int i = 0; i < 10; i++) g.recordContentInvalidUdp(udpPeer);
    g.postInjectedUdp(udpPeer);
    // La qualité fenêtrée DOIT quand même refléter ces événements (observabilité UDP).
    var q = tracker.quality(udpPeer).orElseThrow();
    assertEquals(11, q.total());
    assertEquals(10, q.invalid());
    // Mais la quarantaine ne doit JAMAIS avoir été alimentée pour cette identité.
    assertTrue(quarantine.snapshot().isEmpty(),
        "aucune entrée de quarantaine ne doit exister pour une identité udp:");
    assertFalse(quarantine.shouldSkip(udpPeer, qcfg));
    assertEquals(0, quarantine.observedCount());
    assertEquals(0, quarantine.enforcedCount());
  }
}
