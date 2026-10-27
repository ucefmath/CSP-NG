package com.bowman.cspng.users;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Impl PG du {@link ProtocolAuthResolver} — snapshots COMPLÈTES (user fresh +
 * secret + ACL), epoch monotone V7, lease ≤ 2s, poll ≤ 1s.
 *
 * <p>Corrections du retour reviewer (passage 4, documentées) :
 * <ul>
 *   <li><b>Epoch monotone</b> : la génération est l'{@code csp_auth_epoch} V7
 *       (bump in-transaction par trigger sur TOUTES les tables de droits :
 *       csp_user, csp_user_profile, csp_user_service, csp_user_connector,
 *       csp_user_protocol_secret) — plus de MAX(version) infalsifiable
 *       (une écriture à version basse ou un delete laissait MAX inchangé).
 *       Le NOTIFY reste un accélérateur, l'epoch est la garantie.</li>
 *   <li><b>Flags/dates user ≠ flags secret</b> : la snapshot porte l'user
 *       FRESH (enabled, valid_from/valid_until, version) ET le secret
 *       (ref, secret_version, enabled, valid_until) — les deux doivent être
 *       éligibles ; les flags secret sont des SUPPLÉMENTS protocole, jamais
 *       un substitut des flags/dates ACL de csp_user.</li>
 *   <li><b>Permissions cohérentes</b> : la requête de rechargement lit dans
 *       la MÊME transaction implicite (single SELECT avec agrégats) l'user,
 *       son secret et ses ACL (profiles, services, connectors) — version
 *       cohérente au moment du rechargement.</li>
 *   <li><b>Login = csp_user.name exact</b> : plus d'alias protocol_name.</li>
 * </ul>
 *
 * <p>Mécanique cache : poll epoch ≤ 1s (horloge injectable pour les tests) ;
 * bump ⇒ rechargement complet ; lease ≤ 2s : poll en échec après expiration
 * de lease ⇒ fail-closed (jamais fail-open). Le premier appel AVANT tout poll
 * réussi est toujours fail-closed (le piège des impls qui servent un cache
 * vide à froid : {@code lastSuccessfulPoll = 0} ⇒ grâce impossible).
 */
public class PgProtocolAuthResolver implements ProtocolAuthResolver {

  private static final long POLL_INTERVAL_MS = 1_000;   // poll ≤ 1s
  private static final long LEASE_MS = 2_000;           // lease ≤ 2s

  private final Pool pool;
  private final LongSupplier clock;

  private volatile long lastSuccessfulPoll = 0; // epoch ms, 0 = jamais poll réussi
  private final AtomicLong cachedEpoch = new AtomicLong(-1);
  private final AtomicReference<Map<String, Snapshot>> cache =
      new AtomicReference<>(Map.of());

  public PgProtocolAuthResolver(Pool pool) {
    this(pool, System::currentTimeMillis);
  }

  /** Constructeur test : horloge injectable (lease/poll déterministes). */
  public PgProtocolAuthResolver(Pool pool, LongSupplier clock) {
    this.pool = Objects.requireNonNull(pool, "pool");
    this.clock = clock != null ? clock : System::currentTimeMillis;
  }

  /**
   * Snapshot d'auth : user fresh + secret + ACL, version cohérentes.
   * Aucune valeur de secret (référence seulement — résolution session P4/P5).
   */
  public record Snapshot(
      String userName, boolean userEnabled, Instant userValidFrom,
      Instant userValidUntil, long userVersion, int allowedEcmRate,
      String secretRef, long secretVersion, boolean secretEnabled,
      Instant secretValidUntil, Long ucrc,
      Set<String> profiles,
      Map<String, Set<Integer>> allowedServices,
      Map<String, Set<Integer>> blockedServices,
      Set<String> allowedConnectors,
      long epoch,
      CacheExPolicy cacheex) {

    /** Compat sans politique cacheex (pre-V15). */
    public Snapshot(
        String userName, boolean userEnabled, Instant userValidFrom,
        Instant userValidUntil, long userVersion, int allowedEcmRate,
        String secretRef, long secretVersion, boolean secretEnabled,
        Instant secretValidUntil, Long ucrc,
        Set<String> profiles,
        Map<String, Set<Integer>> allowedServices,
        Map<String, Set<Integer>> blockedServices,
        Set<String> allowedConnectors,
        long epoch) {
      this(userName, userEnabled, userValidFrom, userValidUntil, userVersion,
          allowedEcmRate, secretRef, secretVersion, secretEnabled, secretValidUntil,
          ucrc, profiles, allowedServices, blockedServices, allowedConnectors,
          epoch, CacheExPolicy.OFF);
    }

    /** Éligibilité COMBINÉE : user ET secret (supplément) valides. */
    public boolean eligible(Instant now) {
      if (!userEnabled || !secretEnabled) return false;
      if (userValidFrom != null && userValidFrom.isAfter(now)) return false;
      if (userValidUntil != null && userValidUntil.isBefore(now)) return false;
      if (secretValidUntil != null && secretValidUntil.isBefore(now)) return false;
      return true;
    }
  }

  @Override
  public Future<AuthResult> authenticate(String protocol, String protocolName) {
    return refreshIfNeeded()
        .map(v -> {
          Snapshot s = cache.get().get(protocol + '\u0000' + protocolName);
          if (s == null || !s.eligible(Instant.now())) {
            // Inconnu, désactivé, révoqué ou expiré : ABSENT de la snapshot
            // éligible — l'auth refuse, sans distinguer (pas d'oracle).
            throw new UserNotFoundException(
                "no eligible protocol credential for " + protocol);
          }
          return new AuthResult(s.userName(), s.secretRef(), s.secretVersion(),
              s.userVersion(), s.userValidUntil(), s.epoch(),
              s.profiles(), s.allowedServices(), s.blockedServices(),
              s.allowedConnectors(), s.ucrc(), s.allowedEcmRate(),
              s.cacheex() != null ? s.cacheex() : CacheExPolicy.OFF);
        });
  }

  @Override
  public Future<AuthResult> authenticateByUcrc(String protocol, long ucrc) {
    return refreshIfNeeded()
        .map(v -> {
          Snapshot s = cache.get().get(protocol + ":ucrc:" + ucrc);
          if (s == null || !s.eligible(Instant.now())) {
            throw new UserNotFoundException(
                "no eligible protocol credential for " + protocol + " ucrc 0x" + Long.toHexString(ucrc));
          }
          return new AuthResult(s.userName(), s.secretRef(), s.secretVersion(),
              s.userVersion(), s.userValidUntil(), s.epoch(),
              s.profiles(), s.allowedServices(), s.blockedServices(),
              s.allowedConnectors(), s.ucrc(), s.allowedEcmRate(),
              s.cacheex() != null ? s.cacheex() : CacheExPolicy.OFF);
        });
  }

  @Override
  public Future<Long> currentGeneration() {
    return refreshIfNeeded().map(v -> cachedEpoch.get());
  }

  /** Poll si nécessaire ; fail-closed si lease expirée sans poll réussi. */
  private Future<Void> refreshIfNeeded() {
    long now = clock.getAsLong();
    if (now - lastSuccessfulPoll <= POLL_INTERVAL_MS) {
      return Future.succeededFuture();
    }
    return poll().recover(e -> {
      if (lastSuccessfulPoll == 0
          || clock.getAsLong() - lastSuccessfulPoll > LEASE_MS) {
        return Future.failedFuture(new IllegalStateException(
            "auth snapshot lease expired (fail-closed)", e));
      }
      return Future.succeededFuture(); // grâce : lease encore valide
    });
  }

  private Future<Void> poll() {
    return pool.preparedQuery("SELECT epoch FROM csp_auth_epoch WHERE id = 1")
        .execute()
        .compose(epochRows -> {
          long epoch = epochRows.iterator().next().getLong(0);
          if (epoch == cachedEpoch.get()) {
            lastSuccessfulPoll = clock.getAsLong();
            return Future.succeededFuture();
          }
          return pool.preparedQuery(
              "SELECT u.name, u.enabled, u.valid_from, u.valid_until, u.version AS u_version, u.allowed_ecm_rate, "
                  + "s.protocol, s.secret_ref, s.secret_version, s.enabled AS s_enabled, "
                  + "s.valid_until AS s_valid_until, s.ucrc, "
                  + "s.cacheex_mode, s.cacheex_max_hop, s.cacheex_filter, "
                  + "COALESCE((SELECT array_agg(profile ORDER BY profile) "
                  + "         FROM csp_user_profile p WHERE p.user_name = u.name), '{}') AS profiles, "
                  + "COALESCE((SELECT json_object_agg(profile, sids) FROM ( "
                  + "    SELECT p.profile, "
                  + "            (SELECT array_agg(sid ORDER BY sid) FROM csp_user_service us "
                  + "             WHERE us.user_name = u.name AND us.profile = p.profile "
                  + "               AND us.mode = 0) AS sids "
                  + "    FROM csp_user_profile p WHERE p.user_name = u.name) sub), '{}') AS allowed, "
                  + "COALESCE((SELECT json_object_agg(profile, sids) FROM ( "
                  + "    SELECT p.profile, "
                  + "            (SELECT array_agg(sid ORDER BY sid) FROM csp_user_service us "
                  + "             WHERE us.user_name = u.name AND us.profile = p.profile "
                  + "               AND us.mode = 1) AS sids "
                  + "    FROM csp_user_profile p WHERE p.user_name = u.name) sub), '{}') AS blocked, "
                  + "COALESCE((SELECT array_agg(connector ORDER BY connector) "
                  + "         FROM csp_user_connector c WHERE c.user_name = u.name), '{}') AS connectors "
                  + "FROM csp_user u "
                  + "JOIN csp_user_protocol_secret s ON s.user_name = u.name "
                  + "WHERE u.enabled = true "
                  + "  AND (u.valid_from IS NULL OR u.valid_from <= now()) "
                  + "  AND (u.valid_until IS NULL OR u.valid_until > now()) "
                  + "  AND s.enabled = true "
                  + "  AND (s.valid_until IS NULL OR s.valid_until > now())")
              .execute()
              .map(rows -> {
                Map<String, Snapshot> fresh = new ConcurrentHashMap<>();
                for (Row r : rows) {
                  String protocol = r.getString("protocol");
                  Integer rateVal = r.getInteger("allowed_ecm_rate");
                  int rate = rateVal != null ? rateVal : -1;
                  Snapshot snap = new Snapshot(
                      r.getString("name"), r.getBoolean("enabled"),
                      toInstant(r, "valid_from"), toInstant(r, "valid_until"),
                      r.getLong("u_version"), rate,
                      r.getString("secret_ref"), r.getLong("secret_version"),
                      r.getBoolean("s_enabled"), toInstant(r, "s_valid_until"),
                      r.getLong("ucrc"),
                      Set.copyOf(java.util.Arrays.asList(r.getArrayOfStrings("profiles"))),
                      parseServiceMap(asJsonObject(r.getJson("allowed"))),
                      parseServiceMap(asJsonObject(r.getJson("blocked"))),
                      Set.copyOf(java.util.Arrays.asList(r.getArrayOfStrings("connectors"))),
                      epoch,
                      snapshotCacheEx(r));
                  fresh.put(protocol + '\u0000' + snap.userName(), snap);
                  if (snap.ucrc() != null) {
                    fresh.put(protocol + ":ucrc:" + snap.ucrc(), snap);
                  }
                }
                cache.set(Map.copyOf(fresh));
                cachedEpoch.set(epoch);
                lastSuccessfulPoll = clock.getAsLong();
                return null;
              });
        });
  }

  /** Politique cacheex du secret — tolère un schéma pre-V15 (off). */
  private static CacheExPolicy snapshotCacheEx(Row r) {
    try {
      return CacheExPolicy.fromDb(
          r.getInteger("cacheex_mode"), r.getInteger("cacheex_max_hop"),
          r.getString("cacheex_filter"));
    } catch (Exception e) {
      return CacheExPolicy.OFF;
    }
  }

  private static JsonObject asJsonObject(Object o) {
    return o instanceof JsonObject j ? j : null;
  }

  private static Instant toInstant(Row r, String col) {
    var v = r.getOffsetDateTime(col);
    return v != null ? v.toInstant() : null;
  }

  private static Map<String, Set<Integer>> parseServiceMap(io.vertx.core.json.JsonObject o) {
    Map<String, Set<Integer>> out = new HashMap<>();
    if (o == null) return Map.of();
    for (var e : o.getMap().entrySet()) {
      Set<Integer> sids = new TreeSet<>();
      Object v = e.getValue();
      if (v instanceof List<?> l) {
        for (Object x : l) {
          if (x instanceof Number n) sids.add(n.intValue());
        }
      } else if (v instanceof Number n) {
        sids.add(n.intValue());
      }
      out.put(e.getKey(), Set.copyOf(sids));
    }
    return out.isEmpty() ? Map.of() : Map.copyOf(out);
  }
}
