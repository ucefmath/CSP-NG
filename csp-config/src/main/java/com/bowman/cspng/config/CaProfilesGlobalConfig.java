package com.bowman.cspng.config;

/**
 * Configuration globale des CA Profiles (defaults herites par chaque profile).
 * Tous les secrets sont des references (env/file/vault), jamais en clair.
 */
public record CaProfilesGlobalConfig(
    String defaultDesKeyRef,
    String defaultConnectorDesKeyRef,
    String defaultClientIdRef,
    int defaultConnectorKeepalive,
    int defaultConnectorMinDelay,
    int defaultConnectorMaxQueue,
    Boolean blockCaidMismatch,
    long version
) {
    public static final CaProfilesGlobalConfig DEFAULT = new CaProfilesGlobalConfig(
        null, null, null, 15, 10, 200, null, 1
    );

    public CaProfilesGlobalConfig {
        if (defaultConnectorKeepalive < 0) throw new IllegalArgumentException("defaultConnectorKeepalive must be >= 0");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
