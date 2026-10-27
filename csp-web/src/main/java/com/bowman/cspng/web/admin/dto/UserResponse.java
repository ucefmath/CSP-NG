package com.bowman.cspng.web.admin.dto;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * DTO for user response (never includes password hash).
 * All collections are non-null, immutable.
 */
public record UserResponse(
    String name,
    String displayName,
    String email,
    boolean enabled,
    boolean admin,
    boolean debug,
    boolean mapExcluded,
    int maxConnections,
    String ipMask,
    int allowedEcmRate,
    long version,
    Set<String> profiles,
    Map<String, Set<Integer>> allowedServices,
    Map<String, Set<Integer>> blockedServices,
    Set<String> allowedConnectors
) {
    public UserResponse {
        profiles = profiles == null ? Set.of() : Set.copyOf(profiles);
        allowedConnectors = allowedConnectors == null ? Set.of() : Set.copyOf(allowedConnectors);
        allowedServices = allowedServices == null ? Map.of() : Collections.unmodifiableMap(deepCopyServices(allowedServices));
        blockedServices = blockedServices == null ? Map.of() : Collections.unmodifiableMap(deepCopyServices(blockedServices));
    }

    private static Map<String, Set<Integer>> deepCopyServices(Map<String, Set<Integer>> src) {
        if (src.isEmpty()) return Map.of();
        Map<String, Set<Integer>> copy = new HashMap<>();
        for (var e : src.entrySet()) {
            copy.put(e.getKey(), e.getValue() == null ? Set.of() : Set.copyOf(e.getValue()));
        }
        return copy;
    }
}
