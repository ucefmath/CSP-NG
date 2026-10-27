package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.ConfigValidator;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.dns.DnsClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Résolution DNS asynchrone non-bloquante avec cache TTL.
 *
 * <p>Utilise le résolveur DNS natif de Vert.x ({@link DnsClient}) — jamais
 * {@code InetAddress.getByName} (bloquant). Résout A + AAAA et union les résultats.
 *
 * <p>Cache in-memory {@link ConcurrentHashMap} avec TTL configurable
 * {@code csp.import.dns-cache-ttl-seconds} (défaut 60s), éviction à la lecture,
 * taille bornée à 256 entrées : si le cache est plein, on évince d'abord les
 * entrées expirées, puis si toujours plein on supprime une entrée arbitraire
 * (plus ancien iterator) — documenté comme "éviction simple sans LRU strict".
 */
@ApplicationScoped
public class DnsResolverService {

  private static final Logger LOG = Logger.getLogger(DnsResolverService.class.getName());
  private static final int MAX_CACHE_SIZE = 256;

  private final Vertx vertx;
  private final long ttlMillis;
  private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

  private record CacheEntry(Set<String> ips, long expiresAt) {}

  @Inject
  public DnsResolverService(Vertx vertx,
                            @ConfigProperty(name = "csp.import.dns-cache-ttl-seconds", defaultValue = "60") long ttlSeconds) {
    this.vertx = vertx;
    this.ttlMillis = ttlSeconds * 1000L;
  }

  // Constructor for tests without CDI
  public DnsResolverService(Vertx vertx, long ttlSeconds, boolean test) {
    this.vertx = vertx;
    this.ttlMillis = ttlSeconds * 1000L;
  }

  /**
   * Résout un hostname ou retourne directement le littéral IP.
   *
   * @param hostOrIp hostname ou littéral IPv4/IPv6
   * @return Future avec l'ensemble des IPs résolues (jamais vide si succès)
   */
  public Future<Set<String>> resolve(String hostOrIp) {
    if (hostOrIp == null || hostOrIp.isBlank()) {
      return Future.failedFuture("host blank");
    }
    String host = hostOrIp.trim();

    // Littéral IP → retour direct sans requête réseau (réutilise ConfigValidator.isValidInet)
    if (ConfigValidator.isValidInet(host)) {
      return Future.succeededFuture(Set.of(host));
    }

    // Cache lookup with expiration
    CacheEntry entry = cache.get(host);
    if (entry != null) {
      if (System.currentTimeMillis() < entry.expiresAt()) {
        return Future.succeededFuture(entry.ips());
      } else {
        cache.remove(host, entry);
      }
    }

    if (vertx == null) {
      return Future.failedFuture("Vertx not available for DNS resolution of: " + host);
    }

    DnsClient dnsClient;
    try {
      dnsClient = vertx.createDnsClient();
    } catch (Exception e) {
      return Future.failedFuture("Failed to create DnsClient for " + host + ": " + e.getMessage());
    }

    Promise<Set<String>> promise = Promise.promise();

    Future<List<String>> aFuture = dnsClient.resolveA(host)
        .recover(err -> {
          LOG.fine("DNS resolveA failed for " + host + ": " + err.getMessage());
          return Future.succeededFuture(List.of());
        });
    Future<List<String>> aaaaFuture = dnsClient.resolveAAAA(host)
        .recover(err -> {
          LOG.fine("DNS resolveAAAA failed for " + host + ": " + err.getMessage());
          return Future.succeededFuture(List.of());
        });

    // Combine both results
    aFuture.onComplete(ar -> {
      List<String> aRecords = ar.succeeded() ? ar.result() : List.of();
      aaaaFuture.onComplete(aaaaAr -> {
        List<String> aaaaRecords = aaaaAr.succeeded() ? aaaaAr.result() : List.of();
        try {
          dnsClient.close();
        } catch (Exception ignored) {}

        Set<String> union = new HashSet<>();
        if (aRecords != null) union.addAll(aRecords);
        if (aaaaRecords != null) union.addAll(aaaaRecords);

        if (union.isEmpty()) {
          promise.fail("DNS resolution failed for host: " + host + " (no A/AAAA records)");
        } else {
          Set<String> immutable = Collections.unmodifiableSet(union);
          putCache(host, immutable);
          promise.complete(immutable);
        }
      });
    });

    return promise.future();
  }

  private void putCache(String host, Set<String> ips) {
    long expiresAt = System.currentTimeMillis() + ttlMillis;
    // Bounded size: evict expired first, then arbitrary
    if (cache.size() >= MAX_CACHE_SIZE && !cache.containsKey(host)) {
      // Evict expired entries
      Iterator<java.util.Map.Entry<String, CacheEntry>> it = cache.entrySet().iterator();
      long now = System.currentTimeMillis();
      while (it.hasNext()) {
        java.util.Map.Entry<String, CacheEntry> e = it.next();
        if (now >= e.getValue().expiresAt()) {
          it.remove();
          if (cache.size() < MAX_CACHE_SIZE) break;
        }
      }
      // Still full → remove one arbitrary entry (first iterator)
      if (cache.size() >= MAX_CACHE_SIZE) {
        Iterator<String> kit = cache.keySet().iterator();
        if (kit.hasNext()) {
          cache.remove(kit.next());
        }
      }
    }
    cache.put(host, new CacheEntry(ips, expiresAt));
  }

  // Visible for testing
  int cacheSize() {
    return cache.size();
  }

  void clearCache() {
    cache.clear();
  }
}
