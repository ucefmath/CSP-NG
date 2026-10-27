package com.bowman.cspng.cache;

import com.bowman.cspng.domain.EcmKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * Cache local L1 des CW, single-writer, hébergé dans le {@link CacheVerticle}.
 *
 * <p>Remplace {@code DefaultCache.ecmMap} (legacy : {@code MessageCacheMap}). La clé est
 * {@link EcmKey}, l'identité étant le {@code dataHash} (hash de l'ECM). La valeur est un
 * {@link CachedReply} (CW + profileName hérité). Caffeine fournit l'expiration TTL et
 * la limite de taille.
 *
 * <p>Le cache est manipulé uniquement depuis le contexte Vert.x du verticle : il n'y a
 * pas de contention réelle malgré la thread-safety native de Caffeine.
 */
public class LocalCwStore {

  private final Cache<EcmKey, CachedReply> cache;

  public LocalCwStore(CacheVerticleConfig config) {
    Caffeine<Object, Object> builder = Caffeine.newBuilder();
    if (config.cwMaxAgeMs() > 0) {
      builder.expireAfterWrite(config.cwMaxAgeMs(), TimeUnit.MILLISECONDS);
    }
    if (config.maximumSize() > 0) {
      builder.maximumSize(config.maximumSize());
    }
    this.cache = builder.build();
  }

  public CachedReply get(EcmKey key) {
    return cache.getIfPresent(key);
  }

  public CachedReply put(EcmKey key, CachedReply reply) {
    return cache.asMap().put(key, reply);
  }

  public void remove(EcmKey key) {
    cache.invalidate(key);
  }

  public long size() {
    return cache.estimatedSize();
  }

  public void clear() {
    cache.invalidateAll();
  }
}
