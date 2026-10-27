package com.bowman.cspng.domain.dcw;

/**
 * Mode de réparation conditionnelle de checksum DVB.
 */
public enum DcwRepairMode {
  /** Réparation désactivée (défaut). */
  OFF("off"),
  /** Détection et observation des opportunités sans altération de la réponse. */
  OBSERVE("observe"),
  /** Application de la réparation en présence de corroboration valide. */
  ENFORCE("enforce");

  private final String value;

  DcwRepairMode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static DcwRepairMode fromString(String val) {
    if (val == null || val.isBlank()) return OFF;
    for (DcwRepairMode m : values()) {
      if (m.value.equalsIgnoreCase(val) || m.name().equalsIgnoreCase(val)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown DcwRepairMode: " + val);
  }
}
