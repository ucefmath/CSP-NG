package com.bowman.cspng.infinispan;

import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.infinispan.Cache;

/**
 * Bridge Infinispan optionnel — transport secondaire L3.
 * Publie les CW replies vers le cache répliqué via putAsync (jamais sync sur event-loop).
 * Anti-boucle : ignore les replies dont originAddress commence par "infinispan:".
 * Cache typé Integer (dataHash) -> byte[] (codec binaire versionné).
 */
public class InfinispanCacheTransport implements CacheTransport {

  private static final Logger LOG = Logger.getLogger(InfinispanCacheTransport.class.getName());
  static final String INFINISPAN_PREFIX = "infinispan:";
  static final String UDP_PREFIX = "udp:";

  private final Cache<Integer, byte[]> cache;

  public InfinispanCacheTransport(Cache<Integer, byte[]> cache) {
    this.cache = cache;
  }

  /** Visible for tests : underlying cache. */
  Cache<Integer, byte[]> cache() {
    return cache;
  }

  @Override
  public void onRequest(EcmRequest request) {
    // no-op en V1 — le bridge réplique uniquement les CW replies
  }

  @Override
  public void onReply(CwReply reply) {
    if (reply == null || reply.isEmpty()) return;
    String origin = reply.originAddress();
    if (origin != null && (origin.startsWith(INFINISPAN_PREFIX) || origin.startsWith(UDP_PREFIX))) {
      return;
    }
    if (cache == null) return;
    Integer key = reply.key().dataHash();
    byte[] value;
    try {
      value = CwCacheCodec.encode(reply);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Infinispan encode failed for hash " + key + ": " + e.getMessage());
      return;
    }
    CompletableFuture<?> future = cache.putAsync(key, value);
    future.whenComplete((old, ex) -> {
      if (ex != null) {
        LOG.log(Level.WARNING, "Infinispan putAsync failed for hash " + key + ": " + ex.getClass().getSimpleName());
      }
    });
  }
}
