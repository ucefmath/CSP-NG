package com.bowman.cspng.users;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enregistrement utilisateur complet (L5b).
 * Collections défensives immuables, recherches case-insensitive déléguées aux directories.
 */
public record UserRecord(
    String name,
    String displayName,
    String passwordHash,
    String email,
    boolean enabled,
    boolean admin,
    boolean debug,
    boolean mapExcluded,
    int maxConnections,
    String ipMask,
    int allowedEcmRate,
    Set<String> profiles,
    Map<String, Set<Integer>> allowedServices,
    Map<String, Set<Integer>> blockedServices,
    Set<String> allowedConnectors,
    long version,
    Instant validFrom,
    Instant validUntil) {

  public UserRecord {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(passwordHash, "passwordHash");
    // defensive copies – immutable
    profiles = profiles == null ? Set.of() : Set.copyOf(profiles);
    allowedServices = deepCopyServices(allowedServices);
    blockedServices = deepCopyServices(blockedServices);
    allowedConnectors = allowedConnectors == null ? Set.of() : Set.copyOf(allowedConnectors);
    // normalize maxConnections: -1 means unlimited, but store as given
  }

  // Convenience constructor for tests / legacy (6 args)
  public UserRecord(String name, String displayName, String passwordHash, boolean enabled, boolean admin, Set<String> profiles) {
    this(name, displayName, passwordHash, null, enabled, admin, false, false, 1, null, -1,
        profiles, Map.of(), Map.of(), Set.of(), 1L, null, null);
  }

  // Convenience with email and maxConnections
  public UserRecord(String name, String displayName, String passwordHash, boolean enabled, boolean admin,
                    Set<String> profiles, int maxConnections) {
    this(name, displayName, passwordHash, null, enabled, admin, false, false, maxConnections, null, -1,
        profiles, Map.of(), Map.of(), Set.of(), 1L, null, null);
  }

  public boolean isEnabledEffective(Instant now) {
    if (!enabled) return false;
    if (validFrom != null && now.isBefore(validFrom)) return false;
    if (validUntil != null && now.isAfter(validUntil)) return false;
    return true;
  }

  public boolean isEnabledEffective() {
    return isEnabledEffective(Instant.now());
  }

  private static Map<String, Set<Integer>> deepCopyServices(Map<String, Set<Integer>> src) {
    if (src == null || src.isEmpty()) return Map.of();
    Map<String, Set<Integer>> copy = new HashMap<>();
    for (var e : src.entrySet()) {
      copy.put(e.getKey(), e.getValue() == null ? Set.of() : Set.copyOf(e.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }
}
