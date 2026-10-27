package com.bowman.cspng.connectors.cacheex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chemins amont appris par hash ECM (forwarding multi-noeuds C2).
 *
 * <p>Chaque entrée mémorise, pour un {@code dataHash} : le chemin de node ids
 * reçu ({@code own node} du pair émetteur FIRST), le cycletime transporté
 * (wire [18], 0 = absent) et le flag next-cw-cycle. Le re-push émet
 * {@code [ownNode, ...upstreamPath]} (sans doublon), ce qui préserve
 * l'anti-boucle (notre id déjà présent ⇒ drop) et le max-hop de bout en bout.
 *
 * <p>LRU bornée ({@value #MAX_ENTRIES}) : pas de croissance non bornée.
 * Thread-safe (accès event-loops multiples + threads HTTP stats).
 */
public final class UpstreamPathStore {

  public static final int MAX_ENTRIES = 1000;

  /** Entrée : chemin amont + métadonnée de cycle wire. */
  public record PathEntry(List<byte[]> nodes, int cycleTimeSec, boolean nextCwCycle) {
    public PathEntry {
      nodes = nodes != null ? List.copyOf(nodes) : List.of();
      if (cycleTimeSec < 0 || cycleTimeSec > 127) {
        throw new IllegalArgumentException("cycleTimeSec must be 0..127");
      }
    }
  }

  private final Map<Integer, PathEntry> map = Collections.synchronizedMap(
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, PathEntry> eldest) {
          return size() > MAX_ENTRIES;
        }
      });

  /**
   * Mémorise le chemin amont d'un hash. Les nodes invalides (null / ≠ 8 octets)
   * sont ignorés ; un chemin vide après filtrage n'est pas stocké.
   */
  public void note(int dataHash, List<byte[]> upstreamPath, int cycleTimeSec, boolean nextCwCycle) {
    if (upstreamPath == null || upstreamPath.isEmpty()) return;
    List<byte[]> copy = new ArrayList<>(upstreamPath.size());
    for (byte[] n : upstreamPath) {
      if (n != null && n.length == 8) copy.add(n.clone());
    }
    if (copy.isEmpty()) return;
    int ct = Math.max(0, Math.min(127, cycleTimeSec));
    map.put(dataHash, new PathEntry(List.copyOf(copy), ct, nextCwCycle));
  }

  /** Compat sans métadonnée de cycle. */
  public void note(int dataHash, List<byte[]> upstreamPath) {
    note(dataHash, upstreamPath, 0, false);
  }

  public PathEntry get(int dataHash) {
    return map.get(dataHash);
  }

  /** Chemin seul (compat) — liste vide si inconnu. */
  public List<byte[]> pathFor(int dataHash) {
    PathEntry e = map.get(dataHash);
    return e != null ? e.nodes() : List.of();
  }

  public void clear() {
    map.clear();
  }

  public int size() {
    return map.size();
  }
}
