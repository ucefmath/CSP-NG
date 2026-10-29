package com.bowman.cspng.worker;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.connectors.cacheex.CacheExBridge;
import com.bowman.cspng.connectors.cacheex.CacheExEmitter;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.connectors.routing.StrictCacheKey;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pont CacheEx du worker (C1) : relie les paires cacheex (connecteurs sortants
 * mode 2/3, sessions entrantes policy 2/3) aux VRAIS caches du worker —
 * SANS CacheVerticle, SANS double cache fantôme.
 *
 * <p>Réception : les CW poussées sont indexées par
 * {@code (profil, caid, provider, sid, parité, dataHash)} dans un store borné
 * TTL (le cache strict exigeant le corps ECM exact ne peut pas les servir) et
 * servies via le hook {@code CacheExLookup} des routeurs listeners. Le
 * forwarding vers les paires d'émission préserve le chemin complet
 * (anti-boucle + max-hop de bout en bout, C2).
 *
 * <p>Émission : sur chaque feed dispatch local ({@code onCacheFeed}) et sur
 * chaque push reçu (forwarding), les cibles d'émission sont évaluées via
 * leur propre {@code onReply} (gate filtre, hop, file bornée, dédup,
 * anti-reflet — logique partagée avec le central). Aucun nouveau bus.
 *
 * <p>Le pont ne re-contrôle JAMAIS les CW (check unique côté réception, C5).
 * Compteurs réels par pair dans le {@link CacheExStatsRegistry} partagé,
 * remontés au central via {@code WorkerStatsView.cacheex}.
 */
public class WorkerCacheExBridge implements CacheExBridge {

  private static final Logger LOG = Logger.getLogger(WorkerCacheExBridge.class.getName());

  /** TTL des entrées du store hash (fraîcheur CW). */
  public static final long ENTRY_TTL_MS = 30_000L;
  /** Borne du store hash (entrées). */
  public static final int MAX_ENTRIES = 2048;

  /** Clé de lookup hash (push sans corps ECM). */
  public record LookupKey(String profile, int caid, int prid, int srvid, int oddEven, int dataHash) {}
  /** Clé de lookup par service (pour résolution de mapping Simulcrypt où dataHash diffère). */
  public record ServiceLookupKey(int caid, int prid, int srvid, int oddEven) {}
  public static final long SERVICE_ENTRY_TTL_MS = 8_500L;

  private static final class StoredCw {
    final byte[] cw;
    final long expiresAtMs;
    final List<byte[]> nodePath;
    final int cycleTimeSec;
    final boolean nextCwCycle;
    final String originAddr;
    final String sourceName;

    StoredCw(byte[] cw, long expiresAtMs, List<byte[]> nodePath,
          int cycleTimeSec, boolean nextCwCycle, String originAddr, String sourceName) {
      this.cw = cw;
      this.expiresAtMs = expiresAtMs;
      this.nodePath = nodePath;
      this.cycleTimeSec = cycleTimeSec;
      this.nextCwCycle = nextCwCycle;
      this.originAddr = originAddr;
      this.sourceName = sourceName;
    }
  }

  private final Map<LookupKey, StoredCw> store = Collections.synchronizedMap(
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LookupKey, StoredCw> eldest) {
          return size() > MAX_ENTRIES;
        }
      });

  private final Map<ServiceLookupKey, StoredCw> serviceStore = Collections.synchronizedMap(
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ServiceLookupKey, StoredCw> eldest) {
          return size() > MAX_ENTRIES;
        }
      });

  private final CacheExStatsRegistry stats;
  private final Map<Long, StrictEcmRouter> routersByListener = new ConcurrentHashMap<>();
  private final Map<String, Long> listenerByProfile = new ConcurrentHashMap<>();
  /** Cibles d'émission : connecteurs mode 3 (clé = nom). */
  private final Map<String, CacheExEmitter> emitConnectors = new ConcurrentHashMap<>();
  /** Cibles d'émission : sessions mode 2 (clé = peerId "L<id>:<user>"). */
  private final Map<String, CacheExEmitter> emitSessions = new ConcurrentHashMap<>();

  public WorkerCacheExBridge(CacheExStatsRegistry stats) {
    this.stats = stats;
  }

  /** Attache le routeur d'un listener (appelé à l'ouverture du listener). */
  public void attachRouter(long listenerId, String profile, StrictEcmRouter router) {
    if (router == null) return;
    routersByListener.put(listenerId, router);
    if (profile != null && !profile.isBlank()) {
      listenerByProfile.putIfAbsent(profile, listenerId);
    }
    router.setCacheExLookup(new StrictEcmRouter.CacheExLookup() {
      @Override
      public CwReply lookup(String p, int c, int pr, int s, int oe, int h) {
        return WorkerCacheExBridge.this.lookup(p, c, pr, s, oe, h);
      }

      @Override
      public CwReply lookupMapped(String p, int c, int pr, int s, int oe, int h) {
        return WorkerCacheExBridge.this.lookupMapped(p, c, pr, s, oe, h);
      }
    });
  }

  /** Détache un listener fermé (routeur + sessions émettrices). */
  public void detachRouter(long listenerId) {
    StrictEcmRouter router = routersByListener.remove(listenerId);
    if (router != null) {
      try {
        router.setCacheExLookup(null);
      } catch (Exception ignored) {
      }
    }
    listenerByProfile.entrySet().removeIf(e -> e.getValue() == listenerId);
    emitSessions.keySet().removeIf(peerId -> peerId.startsWith("L" + listenerId + ":"));
  }

  @Override
  public void registerEmitter(String peerId, CacheExEmitter emitter) {
    if (peerId == null || emitter == null) return;
    // Connecteurs (nom simple) vs sessions ("L<id>:<user>") : même traitement,
    // registres séparés pour un unregister ciblé.
    if (peerId.startsWith("L")) emitSessions.put(peerId, emitter);
    else emitConnectors.put(peerId, emitter);
  }

  @Override
  public void unregisterEmitter(String peerId) {
    if (peerId == null) return;
    emitSessions.remove(peerId);
    emitConnectors.remove(peerId);
  }

  // ============================================================
  // CacheExBridge — réception
  // ============================================================

  @Override
  public boolean feedReceived(String profile, long listenerId,
                              int caid, int prid, int srvid, int oddEven,
                              byte[] cw, int dataHash,
                              int cycleTimeSec, boolean nextCwCycle,
                              List<byte[]> nodePath,
                              String originAddr, String sourceName) {
    if (profile == null || profile.isBlank()) return false;
    if (cw == null || cw.length != 16) return false;
    // Double-garde défensive (l'appelant a déjà validé) : jamais de CW
    // invalide dans le vrai cache.
    if (!DcwValidator.checksumDcw(cw) || DcwValidator.hasFiveZeroes(cw)
        || DcwValidator.hasZeroDcw(cw)) {
      return false;
    }
    StrictEcmRouter router = routersByListener.get(listenerId);
    if (router == null) {
      Long lid = listenerByProfile.get(profile);
      router = lid != null ? routersByListener.get(lid) : null;
    }
    if (router == null) {
      LOG.fine("WorkerCacheExBridge: no router for profile " + profile + " — push dropped");
      return false;
    }
    List<byte[]> path = new ArrayList<>();
    if (nodePath != null) {
      for (byte[] n : nodePath) {
        if (n != null && n.length == 8) path.add(n.clone());
      }
    }
    long nowMs = System.currentTimeMillis();
    LookupKey key = new LookupKey(profile, caid, prid, srvid, oddEven, dataHash);
    store.put(key, new StoredCw(cw.clone(), nowMs + ENTRY_TTL_MS,
        List.copyOf(path), Math.max(0, Math.min(127, cycleTimeSec)), nextCwCycle,
        originAddr, sourceName));
    StoredCw svcEntry = new StoredCw(cw.clone(), nowMs + SERVICE_ENTRY_TTL_MS,
        List.copyOf(path), Math.max(0, Math.min(127, cycleTimeSec)), nextCwCycle,
        originAddr, sourceName);
    serviceStore.put(new ServiceLookupKey(caid, prid, srvid, oddEven), svcEntry);
    if (prid != 0) {
      serviceStore.put(new ServiceLookupKey(caid, 0, srvid, oddEven), svcEntry);
    }

    // Forwarding vers les paires d'émission avec le chemin COMPLET (C2) :
    // chaque cible applique sa gate (filtre/hop/file/dédup/anti-reflet) via onReply.
    CwReply reply = new CwReply(
        new EcmKey(oddEven, srvid, 0, caid, dataHash), oddEven, cw.clone(),
        sourceName, originAddr, System.currentTimeMillis());
    evaluateEmit(reply, path, cycleTimeSec, nextCwCycle);
    return true;
  }

  // ============================================================
  // Émission (feeds dispatch + forwarding)
  // ============================================================

  /**
   * Évalue les cibles d'émission pour une CW fraîche du dispatch local
   * (appelé depuis {@code WorkerRuntime.onCacheFeed}).
   */
  public void onLocalFeed(CwReply reply) {
    if (reply == null || reply.isEmpty() || reply.cw() == null || reply.cw().length != 16) return;
    evaluateEmit(reply, List.of(), 0, false);
  }

  private void evaluateEmit(CwReply reply, List<byte[]> path, int cycleTimeSec, boolean nextCwCycle) {
    if (reply == null || reply.isEmpty()) return;
    int hash = reply.key().dataHash();
    for (CacheExEmitter target : emitConnectors.values()) {
      try {
        if (path != null && !path.isEmpty()) {
          target.noteUpstreamPath(hash, path, cycleTimeSec, nextCwCycle);
        }
        target.onReply(reply);
      } catch (Exception e) {
        LOG.log(Level.FINE, "WorkerCacheExBridge emit to connector failed", e);
      }
    }
    for (CacheExEmitter target : emitSessions.values()) {
      try {
        if (path != null && !path.isEmpty()) {
          target.noteUpstreamPath(hash, path, cycleTimeSec, nextCwCycle);
        }
        target.onReply(reply);
      } catch (Exception e) {
        LOG.log(Level.FINE, "WorkerCacheExBridge emit to session failed", e);
      }
    }
  }

  // ============================================================
  // Lookup hash (hook StrictEcmRouter.CacheExLookup)
  // ============================================================

  /**
   * Sert les CW poussées (sans corps ECM) : hit direct, sans feed (donc sans
   * re-export et sans boucle). L'appelant (routeur) applique la politique DCW.
   */
  public CwReply lookup(String profile, int caid, int provider, int sid, int oddEven, int dataHash) {
    if (profile == null) return null;
    // Exact d'abord (profil/caid/provider/sid/parité/hash — aucun wildcard
    // multi-service), puis repli INCONNU (0) seulement si miss : une CW poussée
    // sans parité reste servable sans contaminer 0x80/0x81 (clé de réponse
    // reconstruite avec la parité demandée).
    StoredCw e = store.get(new LookupKey(profile, caid, provider, sid, oddEven, dataHash));
    if (e == null && oddEven != 0) {
      e = store.get(new LookupKey(profile, caid, provider, sid, 0, dataHash));
    }
    if (e == null) return null;
    if (System.currentTimeMillis() > e.expiresAtMs) {
      store.remove(new LookupKey(profile, caid, provider, sid, oddEven, dataHash));
      return null;
    }
    EcmKey key = new EcmKey(oddEven, sid, 0, caid, dataHash);
    return new CwReply(key, oddEven, e.cw.clone(), e.sourceName, e.originAddr,
        System.currentTimeMillis());
  }

  /**
   * Lookup pour une cible résolue par {@code DcwServiceMapping} : tente d'abord par {@code dataHash},
   * puis par identité de service {@code (caid, provider, sid, oddEven)} lorsque les octets d'ECM
   * diffèrent entre les deux flux liés (Simulcrypt).
   */
  public CwReply lookupMapped(String profile, int caid, int provider, int sid, int oddEven, int dataHash) {
    CwReply direct = lookup(profile, caid, provider, sid, oddEven, dataHash);
    if (direct != null) return direct;
    long now = System.currentTimeMillis();
    StoredCw e = getFreshServiceEntry(new ServiceLookupKey(caid, provider, sid, oddEven), now);
    if (e == null && oddEven != 0) {
      e = getFreshServiceEntry(new ServiceLookupKey(caid, provider, sid, 0), now);
    }
    if (e == null && provider != 0) {
      e = getFreshServiceEntry(new ServiceLookupKey(caid, 0, sid, oddEven), now);
      if (e == null && oddEven != 0) {
        e = getFreshServiceEntry(new ServiceLookupKey(caid, 0, sid, 0), now);
      }
    }
    if (e == null) return null;
    EcmKey key = new EcmKey(oddEven, sid, 0, caid, dataHash);
    return new CwReply(key, oddEven, e.cw.clone(), e.sourceName, e.originAddr, now);
  }

  private StoredCw getFreshServiceEntry(ServiceLookupKey key, long now) {
    StoredCw e = serviceStore.get(key);
    if (e == null) return null;
    if (now > e.expiresAtMs) {
      serviceStore.remove(key);
      return null;
    }
    return e;
  }

  /** Accès test : taille du store. */
  int storeSize() {
    return store.size();
  }
}
