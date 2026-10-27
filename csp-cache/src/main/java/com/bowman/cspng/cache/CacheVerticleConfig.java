package com.bowman.cspng.cache;

import java.util.Objects;

/**
 * Configuration du cache local et du rendez-vous de cache.
 *
 * <p>Reproduit les deux modes de {@code max-cache-wait} du legacy :
 * <ul>
 *   <li>Mode pourcentage : {@code maxCacheWaitPercent} &gt; 0, la valeur effective est
 *       {@code maxCwWait * maxCacheWaitPercent / 100}.</li>
 *   <li>Mode absolu : {@code maxCacheWaitMs} &gt; 0, la valeur effective est
 *       {@code min(maxCwWait, maxCacheWaitMs)}.</li>
 * </ul>
 *
 * @param cwMaxAgeMs          durée de vie d'une CW dans le cache local (Caffeine expireAfterWrite)
 * @param maximumSize         taille maximale du cache local
 * @param maxCacheWaitMs      attente cache maximale absolue (ms), ou -1 pour mode pourcentage
 * @param maxCacheWaitPercent pourcentage de maxCwWait (1-100), ou -1 pour mode absolu
 */
public record CacheVerticleConfig(
    long cwMaxAgeMs,
    long maximumSize,
    long maxCacheWaitMs,
    int maxCacheWaitPercent) {

  public CacheVerticleConfig {
    if (cwMaxAgeMs < 0) throw new IllegalArgumentException("cwMaxAgeMs must be >= 0");
    if (maximumSize < 0) throw new IllegalArgumentException("maximumSize must be >= 0");
    if (maxCacheWaitMs < -1) throw new IllegalArgumentException("maxCacheWaitMs must be >= -1");
    if (maxCacheWaitPercent < -1 || maxCacheWaitPercent > 100) {
      throw new IllegalArgumentException("maxCacheWaitPercent must be between -1 and 100");
    }
    if (maxCacheWaitMs == -1 && maxCacheWaitPercent == -1) {
      throw new IllegalArgumentException("maxCacheWaitMs and maxCacheWaitPercent cannot both be -1");
    }
  }

  /**
   * Calcule la durée d'attente effective à partir de la durée maximale de réponse CW.
   *
   * <p>Legacy : {@code DefaultCache.getMaxCacheWait(long maxCwWait)} l.53-56.
   */
  public long getMaxCacheWait(long maxCwWait) {
    if (maxCacheWaitPercent > 0) {
      return (long) ((maxCacheWaitPercent / 100.0) * maxCwWait);
    }
    return Math.min(maxCwWait, maxCacheWaitMs);
  }

  public static CacheVerticleConfig defaults() {
    return new CacheVerticleConfig(30_000L, 100_000L, 500L, -1);
  }
}
