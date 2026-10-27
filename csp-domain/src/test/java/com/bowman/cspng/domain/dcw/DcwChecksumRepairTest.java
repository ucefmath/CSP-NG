package com.bowman.cspng.domain.dcw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DcwQualityPolicy — Réparation conditionnelle checksum DVB (corroboration, invariants, modes)")
class DcwChecksumRepairTest {

  // Référence valide : 12 octets de données + 4 octets checksum DVB conformes
  private static final byte[] VALID_REFERENCE_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06, // 1+2+3 = 6
      0x04, 0x05, 0x06, 0x0F, // 4+5+6 = 15
      0x07, 0x08, 0x09, 0x18, // 7+8+9 = 24
      0x0A, 0x0B, 0x0C, 0x21  // 10+11+12 = 33
  };

  // Candidat altéré : mêmes 12 octets de données, mais checksums corrompus (0x00)
  private static final byte[] CORRUPTED_CHECKSUM_CW = new byte[]{
      0x01, 0x02, 0x03, 0x00, // corrupt
      0x04, 0x05, 0x06, 0x00, // corrupt
      0x07, 0x08, 0x09, 0x00, // corrupt
      0x0A, 0x0B, 0x0C, 0x00  // corrupt
  };

  // Candidat avec 1 octet de données différent (0x99 au lieu de 0x01)
  private static final byte[] DIFFERENT_DATA_CW = new byte[]{
      (byte) 0x99, 0x02, 0x03, 0x00,
      0x04, 0x05, 0x06, 0x00,
      0x07, 0x08, 0x09, 0x00,
      0x0A, 0x0B, 0x0C, 0x00
  };

  // Candidat avec une demi-CW de 8 octets nulle
  private static final byte[] HALF_ZERO_CORRUPTED_CW = new byte[]{
      0x01, 0x02, 0x03, 0x00,
      0x04, 0x05, 0x06, 0x00,
      0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00
  };

  @Test
  @DisplayName("Mode OFF par défaut : aucune réparation n'est effectuée")
  void testRepairModeOffByDefault() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT; // repairMode = OFF
    DcwRepairResult result = policy.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "c1", "c2", "profile1", 0x1884, 0x000030, 0x2938
    );
    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.DISABLED, result.status());
  }

  @Test
  @DisplayName("Mode ENFORCE + REQUIRE_VALID + corroboration valide d'une autre origine : réparation réussie")
  void testRepairModeEnforceWithValidCorroboration() {
    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of()
    );

    DcwRepairResult result = policy.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "connector1", "connector2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertTrue(result.isRepaired());
    assertEquals(DcwRepairResult.Status.REPAIRED, result.status());
    assertNotNull(result.repairedCw());
    assertArrayEquals(VALID_REFERENCE_CW, result.repairedCw());

    // Vérifier que le tableau original n'a pas été muté
    assertEquals(0x00, CORRUPTED_CHECKSUM_CW[3]);
  }

  @Test
  @DisplayName("Mode OBSERVE : détecte et logue l'opportunité de réparation mais ne modifie pas le retour (non-enforced)")
  void testRepairModeObserve() {
    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.OBSERVE, true, List.of()
    );

    DcwRepairResult result = policy.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "connector1", "connector2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.OBSERVED, result.status());
  }

  @Test
  @DisplayName("Interdiction de réparer si une demi-CW de 8 octets est nulle (même avec référence)")
  void testNeverRepairHalfZero() {
    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of()
    );

    DcwRepairResult result = policy.tryRepair(
        HALF_ZERO_CORRUPTED_CW, VALID_REFERENCE_CW, "connector1", "connector2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.INELIGIBLE_HALF_ZERO, result.status());
  }

  @Test
  @DisplayName("Interdiction de réparer si les 12 octets de données diffèrent d'au moins 1 octet")
  void testRejectIfDataBytesDiffer() {
    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of()
    );

    DcwRepairResult result = policy.tryRepair(
        DIFFERENT_DATA_CW, VALID_REFERENCE_CW, "connector1", "connector2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.DATA_MISMATCH, result.status());
  }

  @Test
  @DisplayName("Interdiction de corroboration depuis la même origine (même connecteur / source)")
  void testRejectSameOriginCorroboration() {
    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of()
    );

    DcwRepairResult result = policy.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "connector1", "connector1", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.SAME_ORIGIN, result.status());
  }

  @Test
  @DisplayName("Interdiction de réparer si le mode effectif n'est pas REQUIRE_VALID (ex: IGNORE ou REJECT_VALID)")
  void testRejectIfNotRequireValidMode() {
    DcwQualityPolicy policyIgnore = new DcwQualityPolicy(
        true, false, false, ChecksumMode.IGNORE, DcwRepairMode.ENFORCE, true, List.of()
    );

    DcwRepairResult result = policyIgnore.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "c1", "c2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.INELIGIBLE_CHECKSUM_MODE, result.status());
  }

  @Test
  @DisplayName("Interdiction de réparer si le candidat ou le résultat réparé est blacklisté")
  void testRejectIfRepairedCwIsBlacklisted() {
    String repairedDigest = DcwDigest.digestSha256Hex(VALID_REFERENCE_CW);
    DcwRule blockRule = new DcwRule(
        1L, DcwRuleScope.WILDCARD, repairedDigest, DcwRuleAction.BLOCK, ChecksumMode.INHERIT, "Blacklist", true, 0L, 0L
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(
        true, false, false, ChecksumMode.REQUIRE_VALID, DcwRepairMode.ENFORCE, true, List.of(blockRule)
    );

    DcwRepairResult result = policy.tryRepair(
        CORRUPTED_CHECKSUM_CW, VALID_REFERENCE_CW, "c1", "c2", "profile1", 0x1884, 0x000030, 0x2938
    );

    assertFalse(result.isRepaired());
    assertEquals(DcwRepairResult.Status.RULE_BLOCKED, result.status());
  }
}
