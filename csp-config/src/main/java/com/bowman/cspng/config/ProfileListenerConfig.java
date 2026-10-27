package com.bowman.cspng.config;

/**
 * Listener de profile (port d'ecoute par protocole).
 */
public record ProfileListenerConfig(
    long id,
    String profileName,
    String protocol,
    int port,
    String bindAddress,
    boolean enabled,
    String desKeyRef,
    String allowList,
    String denyList,
    long version,
    String cardMode,
    boolean strictPolicy,
    Integer reshareCap,
    int maxCards,
    int maxInflight,
    int maxPerIp,
    String nodeId,
    int idleTimeoutSec
) {
    public ProfileListenerConfig {
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
        if (protocol == null || protocol.isBlank()) throw new IllegalArgumentException("protocol required");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1-65535");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (cardMode != null && !cardMode.equalsIgnoreCase("manual") && !cardMode.equalsIgnoreCase("wildcard")) {
            throw new IllegalArgumentException("cardMode must be manual or wildcard if set");
        }
        if (!strictPolicy && ("cccam".equalsIgnoreCase(protocol) || "cs378x".equalsIgnoreCase(protocol))) {
            throw new IllegalArgumentException("strictPolicy must be true for cccam and cs378x listeners");
        }
        if (reshareCap != null && (reshareCap < 0 || reshareCap > 10)) {
            throw new IllegalArgumentException("reshareCap must be 0-10");
        }
        if (maxCards < 1 || maxCards > 4096) throw new IllegalArgumentException("maxCards must be 1-4096");
        if (maxInflight < 1 || maxInflight > 64) throw new IllegalArgumentException("maxInflight must be 1-64");
        if (maxPerIp < 1 || maxPerIp > 100) throw new IllegalArgumentException("maxPerIp must be 1-100");
        if (idleTimeoutSec < 5 || idleTimeoutSec > 3600) throw new IllegalArgumentException("idleTimeoutSec must be 5-3600");
    }

    /** Constructeur de compatibilite legacy (sans champs V9). */
    public ProfileListenerConfig(
        long id,
        String profileName,
        String protocol,
        int port,
        String bindAddress,
        boolean enabled,
        String desKeyRef,
        String allowList,
        String denyList,
        long version
    ) {
        this(id, profileName, protocol, port, bindAddress, enabled, desKeyRef, allowList, denyList, version,
             "manual", true, 0, 512, 32, 5, null, 60);
    }
}
