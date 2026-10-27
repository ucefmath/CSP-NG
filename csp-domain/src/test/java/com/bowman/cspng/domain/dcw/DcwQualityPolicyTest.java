package com.bowman.cspng.domain.dcw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DcwQualityPolicy — Invariants, ChecksumMode (4 modes), scopes et règles")
class DcwQualityPolicyTest {

  private static final byte[] VALID_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06,
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  private static final byte[] HALF_ZERO_VALID_ACTIVE_CW = new byte[]{
      0x01, 0x02, 0x03, 0x06,
      0x04, 0x05, 0x06, 0x0F,
      0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00
  };

  private static final byte[] HALF_ZERO_NON_CHECKSUM_ACTIVE_CW = new byte[]{
      0x01, 0x02, 0x03, (byte) 0x99, // active half non-checksum (0x99 != 0x06)
      0x04, 0x05, 0x06, (byte) 0x88, // 0x88 != 0x0F
      0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00
  };

  private static final byte[] ALL_ZERO_CW = new byte[16];

  private static final byte[] BAD_CHECKSUM_CW = new byte[]{
      0x01, 0x02, 0x03, 0x00, // checksum 0x00 != 0x06
      0x04, 0x05, 0x06, 0x0F,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  private static final byte[] FIVE_ZEROES_CW = new byte[]{
      0x00, 0x00, 0x00, 0x00,
      0x00, 0x01, 0x02, 0x03,
      0x07, 0x08, 0x09, 0x18,
      0x0A, 0x0B, 0x0C, 0x21
  };

  @Test
  @DisplayName("Invariant longueur 16 inviolable (trop court, trop long, null)")
  void testLengthInvariant() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT;

    assertFalse(policy.validate(null, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE).isValid());
    assertFalse(policy.validate(new byte[15], "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE).isValid());
    assertFalse(policy.validate(new byte[17], "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE).isValid());
    assertFalse(policy.validate(new byte[0], "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE).isValid());
  }

  @Test
  @DisplayName("Tout-zero est invalide")
  void testAllZeroInvalid() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT;
    var res = policy.validate(ALL_ZERO_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(res.isValid());
    assertEquals(DcwValidationResult.Reason.ALL_ZERO, res.reason());
  }

  @Test
  @DisplayName("Demi-zero (half-zero) est accepté par défaut")
  void testHalfZeroAcceptedByDefault() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT;
    var res = policy.validate(HALF_ZERO_VALID_ACTIVE_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res.isValid());
  }

  @Test
  @DisplayName("ChecksumMode.REQUIRE_VALID rejette les checksums invalides et accepte les valides")
  void testChecksumModeRequireValid() {
    DcwRule rule = new DcwRule(
        1L, new DcwRuleScope("profile1", 0x1884, null, 0, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "Require valid checksum",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );
    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(rule));

    var resBad = policy.validate(BAD_CHECKSUM_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(resBad.isValid());
    assertEquals(DcwValidationResult.Reason.CHECKSUM_FAIL, resBad.reason());

    var resGood = policy.validate(VALID_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resGood.isValid());
  }

  @Test
  @DisplayName("Provider 0 est un provider légitime distinct du wildcard (null) et distinct de UNKNOWN (-1)")
  void testProviderZeroIsDistinctFromWildcardAndUnknown() {
    String digest = DcwDigest.digestSha256Hex(VALID_CW);
    DcwRule providerZeroRule = new DcwRule(
        1L,
        new DcwRuleScope("profile1", 0x0100, 0, 0x2938, null), // provider 0 exact
        digest,
        DcwRuleAction.BLOCK,
        ChecksumMode.INHERIT,
        "Block provider 0 only",
        true,
        System.currentTimeMillis(),
        System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(providerZeroRule));

    // Provider 0 exact -> match et bloque
    var res0 = policy.validate(VALID_CW, "profile1", 0x0100, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(res0.isValid());
    assertEquals(DcwValidationResult.Reason.RULE_BLOCKED, res0.reason());

    // Provider 0x000030 -> ne matche pas
    var res30 = policy.validate(VALID_CW, "profile1", 0x0100, 0x000030, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res30.isValid());

    // UNKNOWN_PROVIDER (-1) -> ne matche pas le provider 0
    var resUnknown = policy.validate(VALID_CW, "profile1", 0x0100, DcwRuleScope.UNKNOWN_PROVIDER, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resUnknown.isValid());
  }

  @Test
  @DisplayName("ChecksumMode.IGNORE accepte tous les checksums (valides ou invalides)")
  void testChecksumModeIgnore() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT; // default is IGNORE
    var resBad = policy.validate(BAD_CHECKSUM_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resBad.isValid());

    var resGood = policy.validate(VALID_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resGood.isValid());
  }

  @Test
  @DisplayName("ChecksumMode.REJECT_VALID rejette les CW avec checksum valide et accepte les sans checksum")
  void testChecksumModeRejectValid() {
    DcwRule rule = new DcwRule(
        1L, new DcwRuleScope("profile1", 0x0500, 0x032830, 0, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.REJECT_VALID, "Reject valid checksum provider",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );
    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(rule));

    // CW avec checksum valide -> REJETÉ
    var resValid = policy.validate(VALID_CW, "profile1", 0x0500, 0x032830, 0x1234, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(resValid.isValid());
    assertEquals(DcwValidationResult.Reason.CHECKSUM_VALID_REJECTED, resValid.reason());

    // CW sans checksum (BAD_CHECKSUM_CW) -> ACCEPTÉ pour ce provider
    var resNoChecksum = policy.validate(BAD_CHECKSUM_CW, "profile1", 0x0500, 0x032830, 0x1234, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resNoChecksum.isValid());
  }

  @Test
  @DisplayName("ChecksumMode.REJECT_VALID avec half-zero : la demi-CW nulle ne cause pas de faux rejet")
  void testChecksumModeRejectValidHalfZeroHandling() {
    DcwRule rule = new DcwRule(
        1L, new DcwRuleScope("profile1", 0x0500, 0x032830, 0, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.REJECT_VALID, "Reject valid checksum",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );
    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(rule));

    // Demi-CW non-checksum (la moitié active n'a pas de checksum valide) -> ACCEPTÉ
    var res = policy.validate(HALF_ZERO_NON_CHECKSUM_ACTIVE_CW, "profile1", 0x0500, 0x032830, 0x1234, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res.isValid(), "Half-zero with non-checksum active half should be accepted in REJECT_VALID mode");
  }

  @Test
  @DisplayName("Priorité déterministe de scope : le scope le plus précis l'emporte")
  void testScopePriority() {
    // Règle large sur le provider: IGNORE
    DcwRule providerRule = new DcwRule(
        1L, new DcwRuleScope("profile1", 0x1884, 0x000030, 0, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.IGNORE, "Provider wide",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );
    // Règle spécifique sur un SID: REQUIRE_VALID
    DcwRule sidRule = new DcwRule(
        2L, new DcwRuleScope("profile1", 0x1884, 0x000030, 0x2938, null),
        null, DcwRuleAction.NO_ACTION, ChecksumMode.REQUIRE_VALID, "SID specific",
        true, System.currentTimeMillis(), System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(providerRule, sidRule));

    // Sur le SID spécifique 0x2938 -> REQUIRE_VALID l'emporte -> BAD_CHECKSUM_CW rejeté
    var resSid = policy.validate(BAD_CHECKSUM_CW, "profile1", 0x1884, 0x000030, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(resSid.isValid());
    assertEquals(DcwValidationResult.Reason.CHECKSUM_FAIL, resSid.reason());

    // Sur un autre SID 0x9999 -> Provider wide (IGNORE) s'applique -> BAD_CHECKSUM_CW accepté
    var resOtherSid = policy.validate(BAD_CHECKSUM_CW, "profile1", 0x1884, 0x000030, 0x9999, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resOtherSid.isValid());
  }

  @Test
  @DisplayName("5 à 7 zéros rejeté quand zeroCountingEnabled=true")
  void testFiveZeroesRejected() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT.withZeroCounting(true);
    var res = policy.validate(FIVE_ZEROES_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(res.isValid());
    assertEquals(DcwValidationResult.Reason.FIVE_ZEROES, res.reason());
  }

  @Test
  @DisplayName("CW valide acceptée avec digest SHA-256 correct")
  void testValidCwAccepted() {
    DcwQualityPolicy policy = DcwQualityPolicy.DEFAULT;
    var res = policy.validate(VALID_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res.isValid());
    assertNotNull(res.digestSha256());
    assertEquals(64, res.digestSha256().length());
    assertEquals(DcwDigest.digestSha256Hex(VALID_CW), res.digestSha256());
  }

  @Test
  @DisplayName("Règle manuelle BLOCK par digest SHA-256 avec scope précis")
  void testManualBlockRuleExactScope() {
    String digest = DcwDigest.digestSha256Hex(VALID_CW);
    DcwRule rule = new DcwRule(
        1L,
        new DcwRuleScope("profile1", 0x1884, 0x000030, 0x2938, null),
        digest,
        DcwRuleAction.BLOCK,
        ChecksumMode.INHERIT,
        "Bad DCW test",
        true,
        System.currentTimeMillis(),
        System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(rule));

    var res1 = policy.validate(VALID_CW, "profile1", 0x1884, 0x000030, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(res1.isValid());
    assertEquals(DcwValidationResult.Reason.RULE_BLOCKED, res1.reason());

    var res2 = policy.validate(VALID_CW, "profile1", 0x1884, 0x000030, 0x9999, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res2.isValid());
  }

  @Test
  @DisplayName("Règle OBSERVE ne bloque pas la CW valide mais est signalée dans le résultat")
  void testObserveRuleDoesNotBlock() {
    String digest = DcwDigest.digestSha256Hex(VALID_CW);
    DcwRule rule = new DcwRule(
        1L,
        DcwRuleScope.WILDCARD,
        digest,
        DcwRuleAction.OBSERVE,
        ChecksumMode.INHERIT,
        "Suspicious DCW",
        true,
        System.currentTimeMillis(),
        System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, true, ChecksumMode.IGNORE, true, List.of(rule));

    var res = policy.validate(VALID_CW, "profile1", 0x1884, 0, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(res.isValid());
    assertTrue(res.isObserved());
  }

  @Test
  @DisplayName("Provider inconnu (UNKNOWN_PROVIDER = -1) ne matche pas un provider spécifique mais matche wildcard")
  void testUnknownProviderScopeMatching() {
    String digest = DcwDigest.digestSha256Hex(VALID_CW);
    DcwRule specificProviderRule = new DcwRule(
        1L,
        new DcwRuleScope("profile1", 0x1884, 0x000030, 0, null),
        digest,
        DcwRuleAction.BLOCK,
        ChecksumMode.INHERIT,
        "Specific provider",
        true,
        System.currentTimeMillis(),
        System.currentTimeMillis()
    );

    DcwQualityPolicy policy = new DcwQualityPolicy(true, false, false, ChecksumMode.IGNORE, true, List.of(specificProviderRule));

    var resUnknown = policy.validate(VALID_CW, "profile1", 0x1884, DcwRuleScope.UNKNOWN_PROVIDER, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertTrue(resUnknown.isValid());

    var resKnown = policy.validate(VALID_CW, "profile1", 0x1884, 0x000030, 0x2938, "conn1", LegacyGuard.PERMISSIVE);
    assertFalse(resKnown.isValid());
  }
}
