package com.bowman.cspng.connectors.catalog;

import java.time.Instant;
import java.util.Objects;

/**
 * Relation n:n entre une capacité et une source amont (connecteur + origin card id).
 */
public record CardSource(
    long id,
    long capabilityId,
    String connectorName,
    Long originCardId,
    Trust trust,
    int hop,
    int reshare,
    Instant updatedAt
) {
  public enum Trust {
    DISCOVERED("discovered"),
    DECLARED_UNVERIFIED("declared_unverified"),
    MANUAL("manual");

    private final String dbValue;

    Trust(String dbValue) {
      this.dbValue = dbValue;
    }

    public String dbValue() {
      return dbValue;
    }

    public static Trust fromDb(String s) {
      for (Trust t : values()) {
        if (t.dbValue.equalsIgnoreCase(s)) return t;
      }
      throw new IllegalArgumentException("Unknown trust: " + s);
    }
  }

  public CardSource {
    Objects.requireNonNull(connectorName, "connectorName");
    Objects.requireNonNull(trust, "trust");
    if (hop < 1 || hop > 15) throw new IllegalArgumentException("hop must be 1..15: " + hop);
    if (reshare < 0 || reshare > 15) throw new IllegalArgumentException("reshare must be 0..15: " + reshare);
  }
}
