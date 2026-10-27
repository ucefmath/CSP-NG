package com.bowman.cspng.connectors.cacheex;

import com.bowman.cspng.cache.CacheTransport;

import java.util.List;

/**
 * Capacité d'émission CacheEx avec chemin amont (forwarding C2).
 *
 * <p>Les 4 émetteurs (connecteurs CCcam/CS378x sortants, sessions CCcam/CS378x
 * entrantes) implémentent {@code CacheTransport} (fan-out {@code onReply} avec
 * gates filtre/hop/file/dédup/anti-reflet) ET cette interface (mémorisation du
 * chemin amont pour re-push avec anti-boucle et max-hop de bout en bout).
 */
public interface CacheExEmitter extends CacheTransport {

  /**
   * Mémorise le chemin amont + métadonnée de cycle wire d'un hash : le re-push
   * émettra {@code [ownNode, ...upstreamPath]} (sans doublon).
   */
  void noteUpstreamPath(int dataHash, List<byte[]> upstreamPath,
                        int cycleTimeSec, boolean nextCwCycle);
}
