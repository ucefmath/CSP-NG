package com.bowman.cspng.domain.dcw;

/**
 * Action d'une règle DCW.
 *
 * <ul>
 *   <li>{@link #BLOCK} : rejette la CW dont le digest SHA-256 et le scope correspondent (digest obligatoire).</li>
 *   <li>{@link #OBSERVE} : signale sans rejeter (digest obligatoire).</li>
 *   <li>{@link #NO_ACTION} ("none") : règle purement déclarative (ex. checksumMode scopé), jamais bloquante,
 *       sans digest.</li>
 * </ul>
 * Aucune action "allow" : aucune règle ne peut contourner un invariant structurel ni une règle BLOCK.
 */
public enum DcwRuleAction {
  BLOCK("block"),
  OBSERVE("observe"),
  NO_ACTION("none");

  private final String value;

  DcwRuleAction(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** null/blank => NO_ACTION ; accepte "none" / "no_action" ; toute autre valeur inconnue => IAE. */
  public static DcwRuleAction fromString(String val) {
    if (val == null || val.isBlank()) return NO_ACTION;
    String v = val.trim();
    if ("no_action".equalsIgnoreCase(v)) return NO_ACTION;
    for (DcwRuleAction a : values()) {
      if (a.value.equalsIgnoreCase(v) || a.name().equalsIgnoreCase(v)) return a;
    }
    throw new IllegalArgumentException("Unknown DCW rule action (expected block|observe|none)");
  }
}
