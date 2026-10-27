package com.bowman.cspng.config;

/**
 * Lien SID cache (SidCacheLinker).
 */
public record SidCacheLinkConfig(
    long id,
    String profileName,
    int sidA,
    int sidB
) {
    public SidCacheLinkConfig {
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
    }
}
