package com.bowman.cspng.domain.dcw;

import java.util.Arrays;

/**
 * Résultat d'une tentative de réparation de checksum DVB.
 */
public record DcwRepairResult(
    Status status,
    byte[] repairedCw,
    String message
) {

  public enum Status {
    DISABLED,
    REPAIRED,
    OBSERVED,
    INELIGIBLE_HALF_ZERO,
    INELIGIBLE_CHECKSUM_MODE,
    DATA_MISMATCH,
    SAME_ORIGIN,
    INVALID_REFERENCE,
    RULE_BLOCKED,
    UNTRUSTED_ORIGIN,
    NOT_NEEDED
  }

  public DcwRepairResult {
    repairedCw = repairedCw != null ? repairedCw.clone() : null;
  }

  public boolean isRepaired() {
    return status == Status.REPAIRED;
  }

  public byte[] repairedCw() {
    return repairedCw != null ? repairedCw.clone() : null;
  }

  public static DcwRepairResult of(Status status, byte[] repairedCw, String message) {
    return new DcwRepairResult(status, repairedCw, message);
  }

  public static DcwRepairResult disabled() {
    return new DcwRepairResult(Status.DISABLED, null, "Repair is disabled");
  }
}
