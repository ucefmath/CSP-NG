package com.bowman.cspng.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DcwPolicyConfig & DcwConfigValidator — Tests de validation et snapshot")
class DcwConfigValidatorTest {

  static final ProfileConfig PROF1 = new ProfileConfig("profile1", true, false, false, 0x1884, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);

  @Test
  @DisplayName("Validation d'une règle DCW valide")
  void testValidDcwRule() {
    DcwRuleConfig rule = new DcwRuleConfig(
        1L,
        "profile1",
        0x1884,
        0x000030,
        0x2938,
        "conn1",
        "a".repeat(64),
        "block",
        "Test bad DCW",
        true,
        1L,
        System.currentTimeMillis()
    );

    DcwPolicyConfig policy = new DcwPolicyConfig(
        true,
        false,
        false,
        true,
        true,
        List.of(rule),
        1L,
        System.currentTimeMillis()
    );

    ConfigSnapshot snap = ConfigSnapshot.empty().withProfiles(List.of(PROF1)).withDcwPolicy(policy);
    List<String> errors = ConfigValidator.validate(snap);
    assertTrue(errors.isEmpty(), "Expected 0 errors but got: " + errors);
  }

  @Test
  @DisplayName("Validation rejette un digest SHA-256 invalide (longueur != 64 ou non hex)")
  void testInvalidDigestSha256() {
    DcwRuleConfig invalidDigestRule = new DcwRuleConfig(
        1L,
        "profile1",
        0x1884,
        0,
        0x2938,
        null,
        "invalid-short-digest",
        "block",
        "Invalid",
        true,
        1L,
        System.currentTimeMillis()
    );

    DcwPolicyConfig policy = new DcwPolicyConfig(
        true,
        false,
        false,
        true,
        true,
        List.of(invalidDigestRule),
        1L,
        System.currentTimeMillis()
    );

    ConfigSnapshot snap = ConfigSnapshot.empty().withProfiles(List.of(PROF1)).withDcwPolicy(policy);
    List<String> errors = ConfigValidator.validate(snap);
    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(e -> e.contains("digest_sha256")));
  }

  @Test
  @DisplayName("Validation rejette une action non autorisée (autre que block/observe/allow)")
  void testInvalidAction() {
    DcwRuleConfig invalidActionRule = new DcwRuleConfig(
        1L,
        "profile1",
        0x1884,
        0,
        0x2938,
        null,
        "f".repeat(64),
        "unknown_action",
        "Invalid action",
        true,
        1L,
        System.currentTimeMillis()
    );

    DcwPolicyConfig policy = new DcwPolicyConfig(
        true,
        false,
        false,
        true,
        true,
        List.of(invalidActionRule),
        1L,
        System.currentTimeMillis()
    );

    ConfigSnapshot snap = ConfigSnapshot.empty().withProfiles(List.of(PROF1)).withDcwPolicy(policy);
    List<String> errors = ConfigValidator.validate(snap);
    assertFalse(errors.isEmpty());
    assertTrue(errors.stream().anyMatch(e -> e.contains("action")));
  }
}
