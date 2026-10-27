package com.bowman.cspng.infinispan;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryModifiedEvent;

/**
 * Listener Infinispan — observe les réplications distantes et re-dispatch vers CacheVerticle.
 * Cache typé Integer -> byte[], ignore isOriginLocal(), décodage strict.
 */
@Listener(observation = org.infinispan.notifications.Listener.Observation.POST)
public class InfinispanCacheListener {

  private static final Logger LOG = Logger.getLogger(InfinispanCacheListener.class.getName());

  private final CacheVerticle cacheVerticle;

  public InfinispanCacheListener(CacheVerticle cacheVerticle) {
    this.cacheVerticle = cacheVerticle;
  }

  @CacheEntryCreated
  public void onCreated(CacheEntryCreatedEvent<Integer, byte[]> event) {
    if (event.isOriginLocal()) return;
    if (event.isPre()) return;
    handle(event.getKey(), event.getValue());
  }

  @CacheEntryModified
  public void onModified(CacheEntryModifiedEvent<Integer, byte[]> event) {
    if (event.isOriginLocal()) return;
    if (event.isPre()) return;
    handle(event.getKey(), event.getValue());
  }

  private void handle(Integer keyHash, byte[] data) {
    if (keyHash == null || data == null) return;
    try {
      // décodage strict unique, ne jamais logger payload
      CwReply decoded = CwCacheCodec.decode(data);
      String source = decoded.originAddress();
      String marked = InfinispanCacheTransport.INFINISPAN_PREFIX + (source != null ? source : "remote");
      // reconstruire reply avec origin marqué sans second décodage
      CwReply remoteReply = new CwReply(decoded.key(), decoded.commandTag(), decoded.cw(),
          decoded.connectorName(), marked, decoded.timestamp());
      EcmKey ecmKey = remoteReply.key();
      // profil synthétique explicite "infinispan" pour ne pas polluer le profil default
      EcmRequest synthetic = new EcmRequest(ecmKey, new byte[0], "infinispan", marked, marked,
          remoteReply.timestamp(), null, 500L);
      cacheVerticle.runOnContext(() -> {
        try {
          cacheVerticle.handleReply(synthetic, remoteReply);
        } catch (Exception e) {
          LOG.log(Level.WARNING, "handleReply failed for hash " + keyHash);
        }
      });
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to handle Infinispan remote entry hash=" + keyHash + " error=" + e.getClass().getSimpleName());
    }
  }
}
