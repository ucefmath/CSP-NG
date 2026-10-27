package com.bowman.cspng.domain.dcw;

/**
 * Mode de validation du checksum DVB pour les Control Words (DCW).
 */
public enum ChecksumMode {
  /** Checksum DVB obligatoire (4 triplets valides). */
  REQUIRE_VALID("require_valid"),
  /** Checksum ignoré (ni validité ni invalidité ne rejette). */
  IGNORE("ignore"),
  /** Checksum valide interdit / rejeté (pour providers sans checksum où checksum valide = faux CW). */
  REJECT_VALID("reject_valid"),
  /** Hérite du mode par défaut de la politique. */
  INHERIT("inherit");

  private final String value;

  ChecksumMode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static ChecksumMode fromString(String val) {
    if (val == null || val.isBlank()) return INHERIT;
    for (ChecksumMode m : values()) {
      if (m.value.equalsIgnoreCase(val) || m.name().equalsIgnoreCase(val)) {
        return m;
      }
    }
    throw new IllegalArgumentException("Unknown ChecksumMode: " + val);
  }
}
