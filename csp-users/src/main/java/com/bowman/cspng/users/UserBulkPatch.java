package com.bowman.cspng.users;

import java.util.Map;
import java.util.Set;

/**
 * Patch bulk users (contrat v2 §2.1) — options communes uniquement.
 *
 * <p>Sémantique : champ {@code null} = INCHANGÉ (jamais de reset implicite).
 * Interdits structurellement (sécurité) : {@code name}, {@code admin}
 * (escalade de privilèges en masse), {@code password} / {@code passwordHash},
 * secrets protocoles (endpoint dédié), {@code validFrom}/{@code validUntil}
 * (mutations de validité = PUT unitaire).
 *
 * <p>Validation fail-closed identique au PUT unitaire ({@link UserRepository#update}).
 */
public record UserBulkPatch(
    String displayName, String email, Boolean enabled, Boolean debug, Boolean mapExcluded,
    Integer maxConnections, String ipMask, Integer allowedEcmRate,
    Set<String> profiles, Set<String> allowedConnectors,
    Map<String, Set<Integer>> allowedServices, Map<String, Set<Integer>> blockedServices) {

    public UserBulkPatch {
        if (profiles != null) profiles = Set.copyOf(profiles);
        if (allowedConnectors != null) allowedConnectors = Set.copyOf(allowedConnectors);
        if (allowedServices != null) allowedServices = Map.copyOf(allowedServices);
        if (blockedServices != null) blockedServices = Map.copyOf(blockedServices);
        if (maxConnections != null && (maxConnections < 0 || maxConnections > 1000)) {
            throw new IllegalArgumentException("maxConnections must be 0-1000");
        }
        if (allowedEcmRate != null && (allowedEcmRate < -1 || allowedEcmRate > 10000)) {
            throw new IllegalArgumentException("allowedEcmRate must be -1..10000");
        }
    }

    public boolean isEmpty() {
        return displayName == null && email == null && enabled == null && debug == null
            && mapExcluded == null && maxConnections == null && ipMask == null
            && allowedEcmRate == null && profiles == null && allowedConnectors == null
            && allowedServices == null && blockedServices == null;
    }

    /** Résultat par item : nouvelle version de la ligne. */
    public record BulkResult(String id, long version) {}
}
