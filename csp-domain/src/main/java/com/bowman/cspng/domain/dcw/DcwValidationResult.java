package com.bowman.cspng.domain.dcw;

/**
 * Résultat immuable de validation de qualité d'un Control Word (DCW).
 */
public record DcwValidationResult(
    boolean isValid,
    Reason reason,
    String digestSha256,
    boolean isObserved,
    String message
) {

  public enum Reason {
    OK,
    NULL_OR_INVALID_LENGTH,
    ALL_ZERO,
    CHECKSUM_FAIL,
    CHECKSUM_VALID_REJECTED,
    FIVE_ZEROES,
    HALF_ZERO,
    RULE_BLOCKED,
    RULE_OBSERVED
  }

  public static DcwValidationResult valid(String digestSha256, boolean observed) {
    return new DcwValidationResult(true, Reason.OK, digestSha256, observed, "OK");
  }

  public static DcwValidationResult invalid(Reason reason, String digestSha256, String message) {
    return new DcwValidationResult(false, reason, digestSha256, false, message);
  }
}
