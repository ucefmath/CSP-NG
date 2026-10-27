package com.bowman.cspng.connectors.routing;

import com.bowman.cspng.domain.CwReply;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache strict partitionné en mémoire, borné avec TTL et coalescence in-flight (P7).
 */
public class StrictPartitionedCache {

  public static final long DEFAULT_TTL_MS = 8_000L;
  public static final int DEFAULT_MAX_CAPACITY = 10_000;
  public static final int DEFAULT_MAX_INFLIGHT = 2_048;

  private record Entry(CwReply reply, long expiresAt) {}

  private final Map<StrictCacheKey, Entry> store = new ConcurrentHashMap<>();
  private final Map<StrictCacheKey, Promise<CwReply>> inFlight = new ConcurrentHashMap<>();
  private final int maxCapacity;
  private final int maxInFlight;

  public StrictPartitionedCache() {
    this(DEFAULT_MAX_CAPACITY, DEFAULT_MAX_INFLIGHT);
  }

  public StrictPartitionedCache(int maxCapacity) {
    this(maxCapacity, DEFAULT_MAX_INFLIGHT);
  }

  public StrictPartitionedCache(int maxCapacity, int maxInFlight) {
    this.maxCapacity = Math.max(1, maxCapacity);
    this.maxInFlight = Math.max(1, maxInFlight);
  }

  /**
   * Retourne la réponse en cache si présente et non expirée.
   */
  public CwReply peek(StrictCacheKey key) {
    long now = System.currentTimeMillis();
    Entry e = store.get(key);
    if (e != null) {
      if (e.expiresAt() > now && !e.reply().isEmpty()) {
        return e.reply();
      } else {
        store.remove(key, e);
      }
    }
    return null;
  }

  /**
   * Enregistre une réponse dans le cache avec un TTL borné.
   * Éviction stricte : ne dépasse JAMAIS maxCapacity.
   */
  public void put(StrictCacheKey key, CwReply reply, long ttlMs) {
    if (reply == null || reply.isEmpty()) return;
    long now = System.currentTimeMillis();
    if (store.size() >= maxCapacity) {
      // 1. Nettoyage paresseux des expirés
      store.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
      // 2. Si toujours plein, éviction stricte de l'entrée expirant le plus tôt
      while (store.size() >= maxCapacity && !store.isEmpty()) {
        StrictCacheKey evictKey = null;
        long minExp = Long.MAX_VALUE;
        for (Map.Entry<StrictCacheKey, Entry> entry : store.entrySet()) {
          if (entry.getValue().expiresAt() < minExp) {
            minExp = entry.getValue().expiresAt();
            evictKey = entry.getKey();
          }
        }
        if (evictKey != null) {
          store.remove(evictKey);
        } else {
          break;
        }
      }
    }
    long exp = now + Math.max(500, ttlMs);
    store.put(key, new Entry(reply, exp));
  }

  /**
   * Coalescing in-flight : si une requête identique est déjà en cours, retourne son Future.
   * Sinon, crée et enregistre la Promise pour les requêtes suivantes.
   */
  public InFlightResult joinOrCreateInFlight(StrictCacheKey key) {
    Promise<CwReply> p = inFlight.get(key);
    if (p != null) {
      return new InFlightResult(p.future(), false, null);
    }
    if (inFlight.size() >= maxInFlight) {
      // Limite in-flight atteinte : ne pas créer de Promise coalescée non bornée
      return new InFlightResult(Future.failedFuture("in-flight capacity exceeded"), false, null);
    }
    Promise<CwReply> newPromise = Promise.promise();
    Promise<CwReply> existing = inFlight.putIfAbsent(key, newPromise);
    if (existing != null) {
      return new InFlightResult(existing.future(), false, null);
    }
    return new InFlightResult(newPromise.future(), true, newPromise);
  }

  public void completeInFlight(StrictCacheKey key, CwReply reply) {
    Promise<CwReply> p = inFlight.remove(key);
    if (p != null) {
      p.tryComplete(reply);
    }
  }

  public void clear() {
    store.clear();
    inFlight.clear();
  }

  public int size() {
    return store.size();
  }

  public record InFlightResult(Future<CwReply> future, boolean isLeader, Promise<CwReply> leaderPromise) {}
}
