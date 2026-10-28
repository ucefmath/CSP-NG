package com.bowman.cspng.domain.dcw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint 1 (reprise Opus) : fondations policy — defaults historiques par appelant,
 * enabled=false, provider inconnu, actions, conflits, bornes, demi-CW, repair durci.
 * CW de test synthétiques (aucune CW réelle).
 */
@DisplayName("DcwQualityPolicy — fondations checkpoint 1")
class DcwPolicyFoundationsTest {

  static final byte[] VALID = {1, 2, 3, 6, 4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33};
  static final byte[] BAD_CS = {1, 2, 3, 0, 4, 5, 6, 0, 7, 8, 9, 0, 10, 11, 12, 0};
  static final byte[] HALF_ZERO = {1, 2, 3, 6, 4, 5, 6, 15, 0, 0, 0, 0, 0, 0, 0, 0};

  static DcwRule checksumRule(long id, DcwRuleScope scope, ChecksumMode mode) {
    return new DcwRule(id, scope, null, DcwRuleAction.NO_ACTION, mode, "", true, 0L, 0L);
  }

  static DcwQualityPolicy policy(boolean enabled, ChecksumMode def, boolean allowZeroHalf, List<DcwRule> rules) {
    return new DcwQualityPolicy(enabled, false, false, false, def, DcwRepairMode.OFF, allowZeroHalf, rules);
  }

  @Test
  @DisplayName("DEFAULT : checksum INHERIT (legacy appelant), repair OFF, autoObserve OFF")
  void defaultIsInherit() {
    assertEquals(ChecksumMode.INHERIT, DcwQualityPolicy.DEFAULT.defaultChecksumMode());
    assertEquals(DcwRepairMode.OFF, DcwQualityPolicy.DEFAULT.repairMode());
    assertFalse(DcwQualityPolicy.DEFAULT.autoObserveEnabled());
  }

  @Test
  @DisplayName("INHERIT : legacy CHECKSUM_REQUIRED rejette bad checksum, PERMISSIVE accepte")
  void inheritUsesCallerLegacy() {
    var p = DcwQualityPolicy.DEFAULT;
    assertEquals(DcwValidationResult.Reason.CHECKSUM_FAIL,
        p.validate(BAD_CS, "p", 0x1884, 0, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).reason());
    assertTrue(p.validate(BAD_CS, "p", 0x1884, 0, 1, "c1", LegacyGuard.PERMISSIVE).isValid());
    assertTrue(p.validate(VALID, "p", 0x1884, 0, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).isValid());
  }

  @Test
  @DisplayName("enabled=false : règles custom ignorées, gardes legacy (longueur, tout-zéro, checksum appelant) préservées")
  void disabledKeepsLegacyGuards() {
    var block = new DcwRule(1L, DcwRuleScope.WILDCARD, DcwDigest.digestSha256Hex(VALID),
        DcwRuleAction.BLOCK, ChecksumMode.INHERIT, "", true, 0L, 0L);
    var ignore = checksumRule(2L, new DcwRuleScope(null, 0x1884, null, 0, null), ChecksumMode.IGNORE);
    var p = policy(false, ChecksumMode.IGNORE, true, List.of(block, ignore));
    assertTrue(p.validate(VALID, "p", 0x1884, 0, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).isValid(), "BLOCK ignoré");
    assertEquals(DcwValidationResult.Reason.CHECKSUM_FAIL,
        p.validate(BAD_CS, "p", 0x1884, 0, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).reason(),
        "IGNORE custom ignoré, legacy checksum conservé");
    assertTrue(p.validate(BAD_CS, "p", 0x1884, 0, 1, "c1", LegacyGuard.PERMISSIVE).isValid());
    assertFalse(p.validate(new byte[16], "p", 0x1884, 0, 1, "c1", LegacyGuard.PERMISSIVE).isValid());
    assertFalse(p.validate(new byte[15], "p", 0x1884, 0, 1, "c1", LegacyGuard.PERMISSIVE).isValid());
  }

  @Test
  @DisplayName("Provider inconnu + legacy CHECKSUM_REQUIRED : aucune relaxation (IGNORE/REJECT_VALID) appliquée")
  void unknownProviderNeverRelaxesLegacyChecksum() {
    var ignoreCaid = checksumRule(1L, new DcwRuleScope(null, 0x1884, null, 0, null), ChecksumMode.IGNORE);
    var p = policy(true, ChecksumMode.INHERIT, true, List.of(ignoreCaid));
    assertEquals(DcwValidationResult.Reason.CHECKSUM_FAIL,
        p.validate(BAD_CS, "p", 0x1884, DcwRuleScope.UNKNOWN_PROVIDER, 1, "udp:x", LegacyGuard.CHECKSUM_REQUIRED).reason());
    // contexte connu : la règle explicite s'applique
    assertTrue(p.validate(BAD_CS, "p", 0x1884, 0x30, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).isValid());
    var rejectValid = checksumRule(2L, new DcwRuleScope(null, 0x0500, null, 0, null), ChecksumMode.REJECT_VALID);
    var p2 = policy(true, ChecksumMode.INHERIT, true, List.of(rejectValid));
    assertTrue(p2.validate(VALID, "p", 0x0500, DcwRuleScope.UNKNOWN_PROVIDER, 1, "udp:x", LegacyGuard.CHECKSUM_REQUIRED).isValid(),
        "unknown : REJECT_VALID non appliqué, legacy require");
    assertFalse(p2.validate(VALID, "p", 0x0500, 0x032830, 1, "c1", LegacyGuard.CHECKSUM_REQUIRED).isValid());
  }

  @Test
  @DisplayName("BLOCK/OBSERVE exigent un digest ; action absente = NO_ACTION ; 'allow' refusé")
  void actionsContract() {
    assertThrows(IllegalArgumentException.class, () -> new DcwRule(1L, DcwRuleScope.WILDCARD, null,
        DcwRuleAction.BLOCK, ChecksumMode.INHERIT, "", true, 0L, 0L));
    assertThrows(IllegalArgumentException.class, () -> new DcwRule(1L, DcwRuleScope.WILDCARD, null,
        DcwRuleAction.OBSERVE, ChecksumMode.INHERIT, "", true, 0L, 0L));
    var r = new DcwRule(1L, DcwRuleScope.WILDCARD, null, null, ChecksumMode.IGNORE, "", true, 0L, 0L);
    assertEquals(DcwRuleAction.NO_ACTION, r.action());
    assertThrows(IllegalArgumentException.class, () -> DcwRuleAction.fromString("allow"));
    assertEquals(DcwRuleAction.NO_ACTION, DcwRuleAction.fromString("none"));
    // règle checksum-only sur scope wildcard ne bloque rien
    var p = policy(true, ChecksumMode.INHERIT, true, List.of(r));
    assertTrue(p.validate(VALID, "p", 1, 0, 1, "c", LegacyGuard.PERMISSIVE).isValid());
  }

  @Test
  @DisplayName("Conflit de checksumMode sur scope identique refusé (déterminisme)")
  void conflictingScopesRejected() {
    var s = new DcwRuleScope("p", 0x1884, 0x30, 0, null);
    var a = checksumRule(1L, s, ChecksumMode.IGNORE);
    var b = checksumRule(2L, s, ChecksumMode.REQUIRE_VALID);
    assertFalse(DcwQualityPolicy.findConflicts(List.of(a, b)).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> policy(true, ChecksumMode.INHERIT, true, List.of(a, b)));
    var same = checksumRule(3L, s, ChecksumMode.IGNORE);
    assertTrue(DcwQualityPolicy.findConflicts(List.of(a, same)).isEmpty());
  }

  @Test
  @DisplayName("Borne 1000 règles uniforme")
  void rulesCapped() {
    List<DcwRule> rules = new ArrayList<>();
    for (int i = 0; i < DcwQualityPolicy.MAX_RULES + 1; i++) {
      rules.add(checksumRule(i, new DcwRuleScope(null, 1, null, (i % 65535) + 1, "s" + i), ChecksumMode.IGNORE));
    }
    assertThrows(IllegalArgumentException.class, () -> policy(true, ChecksumMode.INHERIT, true, rules));
    assertEquals(1000, DcwQualityPolicy.MAX_RULES);
  }

  @Test
  @DisplayName("allowZeroHalf=false rejette demi-CW nulle ; true l'accepte")
  void allowZeroHalfEffective() {
    assertTrue(policy(true, ChecksumMode.INHERIT, true, List.of())
        .validate(HALF_ZERO, "p", 1, 0, 1, "c", LegacyGuard.CHECKSUM_REQUIRED).isValid());
    assertEquals(DcwValidationResult.Reason.HALF_ZERO, policy(true, ChecksumMode.INHERIT, false, List.of())
        .validate(HALF_ZERO, "p", 1, 0, 1, "c", LegacyGuard.CHECKSUM_REQUIRED).reason());
  }

  @Test
  @DisplayName("tryRepair durci : origine nulle, provider inconnu, INHERIT, déjà valide, référence bloquée => refus")
  void repairHardened() {
    var explicit = checksumRule(1L, new DcwRuleScope(null, 0x1884, 0x30, 0, null), ChecksumMode.REQUIRE_VALID);
    var p = new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT, DcwRepairMode.ENFORCE, true, List.of(explicit));
    assertEquals(DcwRepairResult.Status.UNTRUSTED_ORIGIN,
        p.tryRepair(BAD_CS, VALID, null, "c2", "p", 0x1884, 0x30, 1).status());
    assertEquals(DcwRepairResult.Status.UNTRUSTED_ORIGIN,
        p.tryRepair(BAD_CS, VALID, "c1", " ", "p", 0x1884, 0x30, 1).status());
    assertEquals(DcwRepairResult.Status.INELIGIBLE_CHECKSUM_MODE,
        p.tryRepair(BAD_CS, VALID, "c1", "c2", "p", 0x1884, DcwRuleScope.UNKNOWN_PROVIDER, 1).status());
    var inherit = new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT, DcwRepairMode.ENFORCE, true, List.of());
    assertEquals(DcwRepairResult.Status.INELIGIBLE_CHECKSUM_MODE,
        inherit.tryRepair(BAD_CS, VALID, "c1", "c2", "p", 0x1884, 0x30, 1).status(), "INHERIT/legacy non résolu = refus");
    assertEquals(DcwRepairResult.Status.NOT_NEEDED,
        p.tryRepair(VALID, VALID, "c1", "c2", "p", 0x1884, 0x30, 1).status());
    var blockRef = new DcwRule(2L, DcwRuleScope.WILDCARD, DcwDigest.digestSha256Hex(VALID),
        DcwRuleAction.BLOCK, ChecksumMode.INHERIT, "", true, 0L, 0L);
    var pb = new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT, DcwRepairMode.ENFORCE, true, List.of(explicit, blockRef));
    assertEquals(DcwRepairResult.Status.RULE_BLOCKED,
        pb.tryRepair(BAD_CS, VALID, "c1", "c2", "p", 0x1884, 0x30, 1).status());
    var enabledOff = new DcwQualityPolicy(false, false, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of());
    assertEquals(DcwRepairResult.Status.DISABLED,
        enabledOff.tryRepair(BAD_CS, VALID, "c1", "c2", "p", 0x1884, 0x30, 1).status());
    var ok = p.tryRepair(BAD_CS, VALID, "c1", "c2", "p", 0x1884, 0x30, 1);
    assertEquals(DcwRepairResult.Status.REPAIRED, ok.status());
    assertArrayEquals(VALID, ok.repairedCw());
  }

  @Test
  @DisplayName("Mappings typés dans la policy : sourceSid != targetSid, expiration respectée, borne 1000")
  void mappingsTyped() {
    assertThrows(IllegalArgumentException.class,
        () -> new DcwServiceMapping(1L, "p", 0x1884, 0x30, 0x1884, 0x30, 5, 5, true, true, 0L, 1L, 0L));
    var m = new DcwServiceMapping(1L, "p", 0x1884, 0x30, 0x1884, 0x30, 5, 6, true, true, 10_000L, 1L, 0L);
    assertTrue(m.matches("p", 0x1884, 0x30, 6, 5, 9_999L));
    assertFalse(m.matches("p", 0x1884, 0x30, 6, 5, 10_001L), "expiré");
    assertFalse(m.matches("p", 0x1884, 0x31, 6, 5, 1L), "cross-provider");
    var notApproved = new DcwServiceMapping(2L, "p", 0x1884, 0x30, 0x1884, 0x30, 5, 6, false, true, 0L, 1L, 0L);
    assertFalse(notApproved.matches("p", 0x1884, 0x30, 5, 6, 1L));
    var p = new DcwQualityPolicy(true, false, false, false, ChecksumMode.INHERIT, DcwRepairMode.OFF, true, List.of(), List.of(m));
    assertEquals(1, p.mappings().size());
  }

  // ---------------------------------------------------------------------------------------------
  // Contrat CAID:Provider : un mapping global (sourceSid = targetSid = 0) peut lier deux couples
  // CAID/provider distincts et résoudre un SID dans les deux sens en le conservant.
  // ---------------------------------------------------------------------------------------------

  static final int MAP_CAID_A = 0x1884;
  static final int MAP_PROVIDER_A = 0x000000;
  static final int MAP_CAID_B = 0x1813;
  static final int MAP_PROVIDER_B = 0x000068;
  static final int MAP_SID = 0x32DC;

  /** Mapping global p: 0x1884/0x000000 <-> 0x1813/0x000068, sans restriction de SID (0, 0). */
  static DcwServiceMapping globalCaidProviderMapping() {
    return new DcwServiceMapping(10L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
        0, 0, true, true, 0L, 1L, 0L);
  }

  @Test
  @DisplayName("Mapping CAID:Provider global : change de couple CAID/provider et résout dans les deux sens en conservant le SID")
  void globalCaidProviderMappingResolvesBothWays() {
    var m = globalCaidProviderMapping();
    assertEquals(Optional.of(new DcwServiceMapping.MappedTarget(MAP_CAID_B, MAP_PROVIDER_B, MAP_SID)),
        m.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 1000L));
    assertEquals(Optional.of(new DcwServiceMapping.MappedTarget(MAP_CAID_A, MAP_PROVIDER_A, MAP_SID)),
        m.resolveTarget("p", MAP_CAID_B, MAP_PROVIDER_B, MAP_SID, 1000L));

    // autres SID : le mapping global n'en privilégie aucun (sourceSid = targetSid = 0)
    assertEquals(Optional.of(new DcwServiceMapping.MappedTarget(MAP_CAID_B, MAP_PROVIDER_B, 0x0001)),
        m.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, 0x0001, 1000L));
  }

  @Test
  @DisplayName("Mapping CAID:Provider global : provider inconnu, profil ou CAID/provider non couverts ne résolvent rien")
  void globalCaidProviderMappingIgnoresNonCoveredContext() {
    var m = globalCaidProviderMapping();
    assertEquals(Optional.empty(),
        m.resolveTarget("p", MAP_CAID_A, DcwRuleScope.UNKNOWN_PROVIDER, MAP_SID, 1000L),
        "provider inconnu : jamais de mapping");
    assertEquals(Optional.empty(), m.resolveTarget("other", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 1000L));
    assertEquals(Optional.empty(), m.resolveTarget("p", 0x1885, MAP_PROVIDER_A, MAP_SID, 1000L));
    assertEquals(Optional.empty(), m.resolveTarget("p", MAP_CAID_A, 0x000001, MAP_SID, 1000L));
  }

  @Test
  @DisplayName("Mapping CAID:Provider global : expiration et inactive (désactivé / non approuvé) ne résolvent rien")
  void globalCaidProviderMappingRequiresActive() {
    var expiring = new DcwServiceMapping(10L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
        0, 0, true, true, 10_000L, 1L, 0L);
    assertTrue(expiring.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 9_999L).isPresent());
    assertEquals(Optional.empty(), expiring.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 10_001L));
    var disabled = new DcwServiceMapping(11L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
        0, 0, true, false, 0L, 1L, 0L);
    assertEquals(Optional.empty(), disabled.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 1000L));
    var notApproved = new DcwServiceMapping(12L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
        0, 0, false, true, 0L, 1L, 0L);
    assertEquals(Optional.empty(), notApproved.resolveTarget("p", MAP_CAID_A, MAP_PROVIDER_A, MAP_SID, 1000L));
  }

  @Test
  @DisplayName("Mapping mixte (un seul SID à 0) refusé ; auto-mapping complet refusé ; même CAID/provider avec SIDs distincts accepté")
  void caidProviderMappingShapeEnforced() {
    // SID 0 d'un seul côté : ambigu (global d'un côté, ciblé de l'autre)
    assertThrows(IllegalArgumentException.class,
        () -> new DcwServiceMapping(11L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
            0, 5, true, true, 0L, 1L, 0L));
    assertThrows(IllegalArgumentException.class,
        () -> new DcwServiceMapping(12L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
            5, 0, true, true, 0L, 1L, 0L));
    // auto-mapping : même CAID/provider et même SID (y compris le cas global 0/0)
    assertThrows(IllegalArgumentException.class,
        () -> new DcwServiceMapping(13L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_A, MAP_PROVIDER_A,
            0, 0, true, true, 0L, 1L, 0L));
    assertThrows(IllegalArgumentException.class,
        () -> new DcwServiceMapping(14L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_A, MAP_PROVIDER_A,
            5, 5, true, true, 0L, 1L, 0L));
    // mapping intra CAID/provider existant : toujours légitime
    assertDoesNotThrow(() -> new DcwServiceMapping(15L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_A, MAP_PROVIDER_A,
        5, 6, true, true, 0L, 1L, 0L));
    assertDoesNotThrow(() -> new DcwServiceMapping(16L, "p", MAP_CAID_A, MAP_PROVIDER_A, MAP_CAID_B, MAP_PROVIDER_B,
        5, 6, true, true, 0L, 1L, 0L));
  }
}
