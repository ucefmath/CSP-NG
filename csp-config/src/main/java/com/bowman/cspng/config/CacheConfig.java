package com.bowman.cspng.config;

/**
 * Configuration du cache local.
 *
 * @param cwMaxAgeMs          duree de vie d'une CW dans le cache local
 * @param maximumSize         taille maximale du cache local
 * @param maxCacheWaitMs      attente cache maximale absolue (ms), ou -1 pour mode pourcentage
 * @param maxCacheWaitPercent pourcentage de maxCwWait (1-100), ou -1 pour mode absolu
 */
public record CacheConfig(
    long cwMaxAgeMs,
    long maximumSize,
    long maxCacheWaitMs,
    int maxCacheWaitPercent,
    long version
) {
    public static final CacheConfig DEFAULT = new CacheConfig(30_000L, 100_000L, 500L, -1, 1);

    public CacheConfig {
        if (cwMaxAgeMs < 0) throw new IllegalArgumentException("cwMaxAgeMs must be >= 0");
        if (maximumSize < 0) throw new IllegalArgumentException("maximumSize must be >= 0");
        if (maxCacheWaitMs < -1) throw new IllegalArgumentException("maxCacheWaitMs must be >= -1");
        if (maxCacheWaitPercent < -1 || maxCacheWaitPercent > 100) {
            throw new IllegalArgumentException("maxCacheWaitPercent must be between -1 and 100");
        }
        if (maxCacheWaitMs == -1 && maxCacheWaitPercent == -1) {
            throw new IllegalArgumentException("maxCacheWaitMs and maxCacheWaitPercent cannot both be -1");
        }
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
