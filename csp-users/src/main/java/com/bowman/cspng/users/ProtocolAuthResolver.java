package com.bowman.cspng.users;

import io.vertx.core.Future;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Auth gates P4/P5 — snapshots d'auth des protocoles entrants (CCcam/CS378x).
 *
 * <p>Contrat (docs/INBOUND-CAMS-PLAN.md §P3) :
 * <ul>
 *   <li><b>Résolution du secret</b> : la valeur est résolue via
 *       {@code RuntimeSecretResolver} sur worker thread (jamais event-loop),
 *       {@code csp:csp} 0600, allowlist racines — CE RESOLVER NE FAIT PAS la
 *       résolution lui-même : il expose la référence + version ; la session
 *       (P4/P5) résout. Aucune valeur ne transite par cette API.</li>
 *   <li><b>Lease ≤ 2s / poll ≤ 1s</b> : l'implémentation cache les snapshots ;
 *       toute écriture (trigger NOTIFY {@code csp_user_changed} V2/V6) doit
 *       être visible en ≤ ~2s. Impl PG : poll de la génération
 *       {@code MAX(version)} ≤ 1s ; à chaque bump, les snapshots sont
 *       rechargées. Un login dont la version est stale par rapport au cache
 *       est rejeté (stale reject).</li>
 *   <li><b>Fail-closed</b> : reload DB échoué / lease expirée ⇒
 *       {@code Future.failedFuture} — les sessions STRICTES refusent
 *       (jamais de fail-open).</li>
 *   <li><b>Révocation/expiration</b> : {@code enabled=false} ou
 *       {@code valid_until} passé ⇒ snapshot absente pour l'auth ⇒ rejet
 *       immédiat (la révocation porte l'ACL au niveau snapshot).</li>
 *   <li>UC : les sessions appelent {@link #authenticate} à la connexion d'un
 *       client, puis re-valident la génération périodiquement
 *       ({@link #currentGeneration()}) — une génération changée ⇒
 *       re-lookup OU rejet (stale generation reject, pas de retry loop).</li>
 * </ul>
 *
 * <p>Les protocoles legacy (newcamd/radegast) et l'auth HTTP (bcrypt) restent
 * INCHANGÉS — ce resolver ne couvre QUE les nouveaux protocoles P4/P5, qui ne
 * sont PAS branchés tant que les auth gates ne sont pas validés.
 */
public interface ProtocolAuthResolver {

  /**
   * Résultat d'auth : snapshot ÉLIGIBLE — permissions user FRESH cohérentes
   * avec l'epoch lu (retour reviewer : l'auth doit porter l'ACL, pas seulement
   * la ref secret). Jamais la valeur du secret (résolution session P4/P5).
   */
  record AuthResult(String userName, String secretRef,
                    long secretVersion, long userVersion, Instant userValidUntil,
                    long epoch,
                    java.util.Set<String> profiles,
                    java.util.Map<String, java.util.Set<Integer>> allowedServices,
                    java.util.Map<String, java.util.Set<Integer>> blockedServices,
                    java.util.Set<String> allowedConnectors,
                    Long ucrc,
                    int allowedEcmRate,
                    CacheExPolicy cacheex) {

    public AuthResult(String userName, String secretRef,
                      long secretVersion, long userVersion, Instant userValidUntil,
                      long epoch,
                      java.util.Set<String> profiles,
                      java.util.Map<String, java.util.Set<Integer>> allowedServices,
                      java.util.Map<String, java.util.Set<Integer>> blockedServices,
                      java.util.Set<String> allowedConnectors,
                      Long ucrc,
                      int allowedEcmRate) {
      this(userName, secretRef, secretVersion, userVersion, userValidUntil, epoch,
          profiles, allowedServices, blockedServices, allowedConnectors, ucrc,
          allowedEcmRate, CacheExPolicy.OFF);
    }

    public AuthResult(String userName, String secretRef,
                      long secretVersion, long userVersion, Instant userValidUntil,
                      long epoch,
                      java.util.Set<String> profiles,
                      java.util.Map<String, java.util.Set<Integer>> allowedServices,
                      java.util.Map<String, java.util.Set<Integer>> blockedServices,
                      java.util.Set<String> allowedConnectors,
                      Long ucrc) {
      this(userName, secretRef, secretVersion, userVersion, userValidUntil, epoch,
          profiles, allowedServices, blockedServices, allowedConnectors, ucrc, -1);
    }

    public AuthResult {
      Objects.requireNonNull(userName, "userName");
      Objects.requireNonNull(secretRef, "secretRef");
      cacheex = cacheex != null ? cacheex : CacheExPolicy.OFF;
    }
  }

  /**
   * Authentifie un login protocole (case-exact) pour un protocole donné.
   *
   * @return failed si : compte désactivé/révoqué, expiré (valid_until),
   *         absent (inconnu), ou cache stale non rafraichissable (fail-closed).
   *         Le secret N'EST PAS résolu ici — la session résout
   *         {@code AuthResult.secretRef} pour comparer les preuves.
   */
  Future<AuthResult> authenticate(String protocol, String protocolName);

  /**
   * Authentifie un client par son ucrc (camd35 / CS378x : 4 octets clairs en début de flux).
   */
  Future<AuthResult> authenticateByUcrc(String protocol, long ucrc);

  /** Génération courante (poll ≤ 1s) — bump ⇒ toutes les sessions re-valident. */
  Future<Long> currentGeneration();

  // ============================================================
  // FAKE déterministe pour tests (aucune dépendance PG)
  // ============================================================

  /** Fake : lease/poll immédiats, rotation/révocation simulables. */
  final class Fake implements ProtocolAuthResolver {
    public record Entry(boolean enabled, Instant validUntil, String secretRef,
                        long secretVersion, java.util.Set<String> profiles,
                        java.util.Map<String, java.util.Set<Integer>> allowedServices,
                        java.util.Map<String, java.util.Set<Integer>> blockedServices,
                        java.util.Set<String> allowedConnectors, Long ucrc,
                        int allowedEcmRate, CacheExPolicy cacheex) {
      public Entry(boolean enabled, Instant validUntil, String secretRef,
                   long secretVersion, java.util.Set<String> profiles,
                   java.util.Map<String, java.util.Set<Integer>> allowedServices,
                   java.util.Map<String, java.util.Set<Integer>> blockedServices,
                   java.util.Set<String> allowedConnectors, Long ucrc,
                   int allowedEcmRate) {
        this(enabled, validUntil, secretRef, secretVersion, profiles,
            allowedServices, blockedServices, allowedConnectors, ucrc, allowedEcmRate,
            CacheExPolicy.OFF);
      }
      public Entry(boolean enabled, Instant validUntil, String secretRef,
                   long secretVersion, java.util.Set<String> profiles,
                   java.util.Map<String, java.util.Set<Integer>> allowedServices,
                   java.util.Map<String, java.util.Set<Integer>> blockedServices,
                   java.util.Set<String> allowedConnectors, Long ucrc) {
        this(enabled, validUntil, secretRef, secretVersion, profiles,
            allowedServices, blockedServices, allowedConnectors, ucrc, -1);
      }
      public Entry(boolean enabled, Instant validUntil, String secretRef,
                   long secretVersion) {
        this(enabled, validUntil, secretRef, secretVersion, java.util.Set.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Set.of(), null, -1);
      }
    }

    private final Map<String, Map<String, Entry>> byProtocol = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong(0);
    private boolean failClosed;

    public Fake add(String protocol, String protocolName, Entry entry) {
      byProtocol.computeIfAbsent(protocol, k -> new ConcurrentHashMap<>())
          .put(protocolName, entry);
      generation.incrementAndGet();
      return this;
    }

    public Fake revoke(String protocol, String protocolName) {
      Map<String, Entry> m = byProtocol.get(protocol);
      if (m != null) {
        Entry e = m.get(protocolName);
        if (e != null) {
          m.put(protocolName, new Entry(false, e.validUntil(), e.secretRef(),
              e.secretVersion(), e.profiles(), e.allowedServices(),
              e.blockedServices(), e.allowedConnectors(), e.ucrc(), e.allowedEcmRate(),
              e.cacheex() != null ? e.cacheex() : CacheExPolicy.OFF));
          generation.incrementAndGet();
        }
      }
      return this;
    }

    /** Rotation de secret : nouvelle version ⇒ génération stale côté sessions. */
    public Fake rotate(String protocol, String protocolName, String newRef) {
      Map<String, Entry> m = byProtocol.get(protocol);
      if (m != null) {
        Entry e = m.get(protocolName);
        if (e != null) {
          m.put(protocolName, new Entry(e.enabled(), e.validUntil(), newRef,
              e.secretVersion() + 1, e.profiles(), e.allowedServices(),
              e.blockedServices(), e.allowedConnectors(), e.ucrc(), e.allowedEcmRate(),
              e.cacheex() != null ? e.cacheex() : CacheExPolicy.OFF));
          generation.incrementAndGet();
        }
      }
      return this;
    }

    public Fake expire(String protocol, String protocolName) {
      Map<String, Entry> m = byProtocol.get(protocol);
      if (m != null) {
        Entry e = m.get(protocolName);
        if (e != null) {
          m.put(protocolName, new Entry(e.enabled(), Instant.now().minusSeconds(60),
              e.secretRef(), e.secretVersion(), e.profiles(), e.allowedServices(),
              e.blockedServices(), e.allowedConnectors(), e.ucrc(), e.allowedEcmRate(),
              e.cacheex() != null ? e.cacheex() : CacheExPolicy.OFF));
          generation.incrementAndGet();
        }
      }
      return this;
    }

    /** Simule un reload DB en échec — les lookups doivent FAIL-CLOSED. */
    public Fake failClosed(boolean fail) {
      this.failClosed = fail;
      generation.incrementAndGet();
      return this;
    }

    @Override
    public Future<AuthResult> authenticate(String protocol, String protocolName) {
      if (failClosed) {
        return Future.failedFuture(new IllegalStateException(
            "auth snapshot store unavailable (fail-closed)"));
      }
      Map<String, Entry> m = byProtocol.get(protocol);
      Entry e = m != null ? m.get(protocolName) : null;
      if (e == null) {
        return Future.failedFuture(new UserNotFoundException(
            "no protocol credential for " + protocol + "/" + protocolName));
      }
      if (!e.enabled()) {
        return Future.failedFuture(new IllegalStateException(
            "protocol credential revoked for " + protocolName));
      }
      if (e.validUntil() != null && e.validUntil().isBefore(Instant.now())) {
        return Future.failedFuture(new IllegalStateException(
            "protocol credential expired for " + protocolName));
      }
      return Future.succeededFuture(new AuthResult(protocolName,
          e.secretRef(), e.secretVersion(), 0L, e.validUntil(),
          generation.get(), e.profiles(), e.allowedServices(), e.blockedServices(),
          e.allowedConnectors(), e.ucrc(), e.allowedEcmRate(),
          e.cacheex() != null ? e.cacheex() : CacheExPolicy.OFF));
    }

    @Override
    public Future<AuthResult> authenticateByUcrc(String protocol, long ucrc) {
      if (failClosed) {
        return Future.failedFuture(new IllegalStateException(
            "auth snapshot store unavailable (fail-closed)"));
      }
      Map<String, Entry> m = byProtocol.get(protocol);
      if (m != null) {
        for (var entry : m.entrySet()) {
          Entry e = entry.getValue();
          if (e.ucrc() != null && e.ucrc() == ucrc) {
            if (!e.enabled()) {
              return Future.failedFuture(new IllegalStateException(
                  "protocol credential revoked for " + entry.getKey()));
            }
            if (e.validUntil() != null && e.validUntil().isBefore(Instant.now())) {
              return Future.failedFuture(new IllegalStateException(
                  "protocol credential expired for " + entry.getKey()));
            }
            return Future.succeededFuture(new AuthResult(entry.getKey(),
                e.secretRef(), e.secretVersion(), 0L, e.validUntil(),
                generation.get(), e.profiles(), e.allowedServices(), e.blockedServices(),
                e.allowedConnectors(), e.ucrc(), e.allowedEcmRate(),
                e.cacheex() != null ? e.cacheex() : CacheExPolicy.OFF));
          }
        }
      }
      return Future.failedFuture(new UserNotFoundException(
          "no protocol credential for " + protocol + " ucrc " + ucrc));
    }

    @Override
    public Future<Long> currentGeneration() {
      if (failClosed) {
        return Future.failedFuture(new IllegalStateException(
            "auth snapshot store unavailable (fail-closed)"));
      }
      return Future.succeededFuture(generation.get());
    }
  }
}
