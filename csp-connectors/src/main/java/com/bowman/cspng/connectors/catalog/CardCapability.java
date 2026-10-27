package com.bowman.cspng.connectors.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * Capacité de carte déclarée ou découverte pour un (profile, caid, provider).
 * Porte un {@code cardId} persistant (1..4294967295) alloué une seule fois.
 */
public record CardCapability(
    long id,
    String profile,
    int caid,
    int provider,
    State state,
    long cardId,
    long version,
    Instant updatedAt
) {
  public enum State {
    DISCOVERED("discovered"),
    PUBLISHED("published"),
    BLOCKED("blocked");

    private final String dbValue;

    State(String dbValue) {
      this.dbValue = dbValue;
    }

    public String dbValue() {
      return dbValue;
    }

    public static State fromDb(String s) {
      for (State st : values()) {
        if (st.dbValue.equalsIgnoreCase(s)) return st;
      }
      throw new IllegalArgumentException("Unknown state: " + s);
    }
  }

  public CardCapability {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(state, "state");
    if (caid < 1 || caid > 65535) throw new IllegalArgumentException("caid must be 1..65535: " + caid);
    if (provider < 0 || provider > 16777215) throw new IllegalArgumentException("provider must be 0..16777215: " + provider);
    if (cardId < 1 || cardId > 4294967295L) throw new IllegalArgumentException("cardId must be 1..4294967295: " + cardId);
  }
}
