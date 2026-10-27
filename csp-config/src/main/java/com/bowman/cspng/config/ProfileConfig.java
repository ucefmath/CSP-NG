package com.bowman.cspng.config;

/**
 * Configuration d'un profile CA (operateur TV).
 */
public record ProfileConfig(
    String name,
    boolean enabled,
    boolean debug,
    boolean cacheOnly,
    int caId,
    int networkId,
    Boolean requireProviderMatch,
    Boolean checkEcmLength,
    long maxCwWaitMs,
    Long congestionLimitMs,
    boolean mismatchedCards,
    int serviceConflicts,
    String servicesFile,
    String providerFilter,
    String servicesFileFormat,
    Boolean blockCaidMismatch,
    long version
) {
    public ProfileConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (maxCwWaitMs <= 100) throw new IllegalArgumentException("maxCwWaitMs must be > 100");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }

    /** Effective congestion limit, falling back to maxCwWaitMs. */
    public long effectiveCongestionLimit() {
        return congestionLimitMs != null ? congestionLimitMs : maxCwWaitMs;
    }
}
