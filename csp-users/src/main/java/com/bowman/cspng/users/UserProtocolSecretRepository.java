package com.bowman.cspng.users;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Repository P3 — secrets protocoles entrants CCcam/CS378x (table
 * {@code csp_user_protocol_secret}, migration V6).
 *
 * <p>Contrats (docs/INBOUND-CAMS-PLAN.md §P3) :
 * <ul>
 *   <li><b>Jamais de valeur</b> : le secret est référencé ({@code env:}/
 *       {@code file:} only) et résolu par {@code RuntimeSecretResolver}
 *       (allowlist {@code csp.secrets.allowed-file-roots}) à l'activation
 *       protocole uniquement — ce repository ne voit QUE la référence, aucun
 *       secret ne transite ici, ni en String ni en byte[].</li>
 *   <li><b>Login protocole = csp_user.name EXACT</b> (P4 : usernames exacts,
 *       PAS d'alias supplémentaire — retour reviewer passage 4). L'ucrc est
 *       dérivé de cette identité canonique et porté par le secret (proto-
 *       cole-dépendant), appliqué dans la même transaction : contrainte
 *       UNIQUE + unicité de csp_user.name = txn-lock correct sans nouvelle
 *       identité.</li>
 *   <li><b>ucrc</b> : CRC32(MD5(csp_user.name)) uint32 (module-camd35.c
 *       l.142/866) — appliqué à l'écriture pour CS378x uniquement (nullable
 *       pour CCcam), contrainte SQL UNIQUE ⇒ collision inter-comptes =
 *       {@link SecretCollisionException} (API : 409), JAMAIS de fusion.</li>
 *   <li><b>Version optimiste</b> : chaque écriture incrémente {@code version} ;
 *       écriture avec version stale = {@code UserOptimisticLockException}.</li>
 *   <li><b>Révocation</b> : {@code revoke()} positionne {@code enabled=false}
 *       + version++ — la snapshot ACL (P4/P5) refuse immédiatement ; la
 *       rotation de secret = nouvelle {@code secret_version} ⇒ les sessions
 *       avec génération stale sont rejetées.</li>
 *   <li><b>valid_until</b> : expiration portée par la snapshot (fail-closed).</li>
 * </ul>
 *
 * <p>Les protocoles legacy (newcamd/radegast/csp/ghttp) et la table
 * {@code csp_user} (bcrypt HTTP) restent INCHANGÉS. Aucune activation de
 * listener ni aucune session n'est branchée ici : les auth gates (P4/P5)
 * consomment {@link ProtocolAuthSnapshot} via un
 * {@link ProtocolAuthResolver}.
 */
public class UserProtocolSecretRepository {

  /** Protocoles couverts par la table (CHECK V6) — legacy interdit ici. */
  public enum Protocol { CCAM("cccam", 20), CS378X("cs378x", 63);

    private final String dbValue;
    private final int maxNameLength;

    Protocol(String dbValue, int maxNameLength) {
      this.dbValue = dbValue;
      this.maxNameLength = maxNameLength;
    }

    public String dbValue() { return dbValue; }
    public int maxNameLength() { return maxNameLength; }

    public static Protocol fromDb(String s) {
      for (Protocol p : values()) if (p.dbValue.equals(s)) return p;
      throw new IllegalArgumentException("unknown protocol: " + s);
    }
  }

  /** Collision d'identité protocole (ucrc ou login) — API : 409, pas de fusion. */
  public static class SecretCollisionException extends RuntimeException {
    public SecretCollisionException(String message) { super(message); }
  }

  /**
   * Snapshot persistée — RÉFÉRENCE seulement : aucune valeur de secret ne
   * peut être exposée depuis ce repository (le champ n'existe pas).
   */
  public record ProtocolSecretRecord(
      String userName, Protocol protocol,
      String secretRef, long secretVersion, Long ucrc, boolean enabled,
      Instant validUntil, long version, String updatedBy, Instant updatedAt,
      CacheExPolicy cacheex) {

    /** Compat sans politique cacheex (pre-V15 — off). */
    public ProtocolSecretRecord(
        String userName, Protocol protocol,
        String secretRef, long secretVersion, Long ucrc, boolean enabled,
        Instant validUntil, long version, String updatedBy, Instant updatedAt) {
      this(userName, protocol, secretRef, secretVersion, ucrc, enabled,
          validUntil, version, updatedBy, updatedAt, CacheExPolicy.OFF);
    }
  }

  /** Requête d'écriture (create/update = upsert par (user, protocol)). */
  public record UpsertProtocolSecretRequest(
      String secretRef, Instant validUntil, boolean enabled,
      CacheExPolicy cacheex) {

    /** Compat sans politique cacheex (off). */
    public UpsertProtocolSecretRequest(String secretRef, Instant validUntil, boolean enabled) {
      this(secretRef, validUntil, enabled, null);
    }
  }

  private final Pool pool;

  public UserProtocolSecretRepository(Pool pool) {
    this.pool = pool;
  }

  // ============================================================
  // Écritures (CRUD admin, verrou optimiste + collisions)
  // ============================================================

  /**
   * Upsert du secret protocole d'un user (create or update, PK (user, protocol)).
   *
   * <p>Collision (ucrc ou login protocole pris par un AUTRE compte) →
   * {@link SecretCollisionException} ; version stale sur update →
   * {@link UserOptimisticLockException}. Le secret référencé n'est PAS résolu
   * ici (résolution à l'activation protocole, hors du CRUD — la validation de
   * référence est syntaxique {@code env:}/{@code file:} + borne d'usage).
   *
   * @param expectedVersion version attendue pour un update (0 = création)
   */
  public Future<ProtocolSecretRecord> upsert(String userName, Protocol protocol,
                                             UpsertProtocolSecretRequest req,
                                             long expectedVersion, String actor) {
    validateSecretRef(req.secretRef());
    // Bornes du login = csp_user.name, à l'ACTIVATION seulement (upsert) :
    // CCcam <= 20 (OSCam strncmp(usr, account->usr, 20)), CS378x <= 63.
    if (userName.length() > protocol.maxNameLength()) {
      throw new IllegalArgumentException(protocol.dbValue()
          + " login (csp_user.name) must be <= " + protocol.maxNameLength()
          + " chars: " + userName.length());
    }
    // ucrc dérivé de l'identité canonique csp_user.name (jamais d'alias) —
    // collision inter-comptes IMPOSSIBLE par construction (name unique) ;
    // UNIQUE(ucrc) reste une garde de cohérence transactionnelle.
    Long ucrc = protocol == Protocol.CS378X ? ucrcOf(userName) : null;

    return pool.withTransaction(conn -> upsertTx(conn, userName, protocol, req,
        ucrc, expectedVersion, actor));
  }

  private Future<ProtocolSecretRecord> upsertTx(SqlConnection conn, String userName,
                                                 Protocol protocol,
                                                 UpsertProtocolSecretRequest req,
                                                 Long ucrc, long expectedVersion,
                                                 String actor) {
    return conn.preparedQuery(
            "SELECT id, version, secret_ref, secret_version, ucrc, "
                + "enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter "
                + "FROM csp_user_protocol_secret WHERE user_name = $1 AND protocol = $2")
        .execute(Tuple.of(userName, protocol.dbValue()))
        .compose(rows -> {
          if (rows.size() == 0) {
            if (expectedVersion > 0) {
              return Future.failedFuture(new UserOptimisticLockException(
                  userName + "#protocol-secret", expectedVersion));
            }
            return insert(conn, userName, protocol, req, ucrc, actor);
          }
          Row existing = rows.iterator().next();
          long currentVersion = existing.getLong("version");
          if (expectedVersion != currentVersion) {
            return Future.failedFuture(new UserOptimisticLockException(
                userName + "#protocol-secret", expectedVersion));
          }
          return update(conn, existing, userName, protocol, req, ucrc, actor);
        });
  }

  private Future<ProtocolSecretRecord> insert(SqlConnection conn, String userName,
                                              Protocol protocol,
                                              UpsertProtocolSecretRequest req,
                                              Long ucrc, String actor) {
    return conn.preparedQuery(
            "INSERT INTO csp_user_protocol_secret "
                + "(user_name, protocol, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, version, created_by, updated_by, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter) "
                + "VALUES ($1, $2, $3, 1, $4, $5, $6, 1, $7, $7, $8, $9, $10) "
                + "RETURNING id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter")
        .execute(Tuple.of(userName, protocol.dbValue(),
            req.secretRef(), ucrc, req.enabled(),
            toOffsetDateTime(req.validUntil()), actor,
            req.cacheex() != null && req.cacheex().enabled() ? req.cacheex().mode() : null,
            req.cacheex() != null ? req.cacheex().maxHop() : null,
            req.cacheex() != null ? req.cacheex().filter() : null))
        .map(rows -> mapRecord(rows.iterator().next(), userName, protocol))
        .recover(e -> Future.failedFuture(translateDbError(e, userName)));
  }

  private Future<ProtocolSecretRecord> update(SqlConnection conn, Row existing,
                                               String userName, Protocol protocol,
                                               UpsertProtocolSecretRequest req,
                                               Long ucrc, String actor) {
    // Rotation de secret (référence changée) => nouvelle secret_version ;
    // sinon la version du secret est conservée (pas de bump fantôme).
    boolean refChanged = !req.secretRef().equals(existing.getString("secret_ref"));
    long newSecretVersion = existing.getLong("secret_version") + (refChanged ? 1 : 0);
    return conn.preparedQuery(
            "UPDATE csp_user_protocol_secret SET secret_ref = $1, "
                + "secret_version = $2, ucrc = $3, enabled = $4, valid_until = $5, "
                + "cacheex_mode = $6, cacheex_max_hop = $7, cacheex_filter = $8, "
                + "version = version + 1, updated_by = $9, updated_at = now() "
                + "WHERE user_name = $10 AND protocol = $11 AND version = $12 "
                + "RETURNING id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter")
        .execute(Tuple.of(req.secretRef(), newSecretVersion, ucrc,
            req.enabled(), toOffsetDateTime(req.validUntil()),
            req.cacheex() != null && req.cacheex().enabled() ? req.cacheex().mode() : null,
            req.cacheex() != null ? req.cacheex().maxHop() : null,
            req.cacheex() != null ? req.cacheex().filter() : null,
            actor, userName, protocol.dbValue(), existing.getLong("version")))
        .map(rows -> {
          if (rows.size() == 0) {
            throw new UserOptimisticLockException(userName + "#protocol-secret", 0);
          }
          return mapRecord(rows.iterator().next(), userName, protocol);
        })
        .recover(e -> Future.failedFuture(translateDbError(e, userName)));
  }

  /**
   * Révocation : {@code enabled = false} + version++ — la snapshot ACL refuse
   * immédiatement (generation stale => rejet), sans supprimer l'entrée
   * (audit conservé, ré-activation possible via upsert).
   */
  public Future<ProtocolSecretRecord> revoke(String userName, Protocol protocol,
                                             long expectedVersion, String actor) {
    return pool.preparedQuery(
            "UPDATE csp_user_protocol_secret SET enabled = false, "
                + "version = version + 1, updated_by = $3, updated_at = now() "
                + "WHERE user_name = $1 AND protocol = $2 AND version = $4 "
                + "RETURNING id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter")
        .execute(Tuple.of(userName, protocol.dbValue(), actor, expectedVersion))
        .map(rows -> {
          if (rows.size() == 0) {
            throw new UserOptimisticLockException(
                userName + "#protocol-secret", expectedVersion);
          }
          return mapRecord(rows.iterator().next(), userName, protocol);
        });
  }

  public Future<Void> delete(String userName, Protocol protocol, long expectedVersion) {
    return pool.preparedQuery(
            "DELETE FROM csp_user_protocol_secret "
                + "WHERE user_name = $1 AND protocol = $2 AND version = $3")
        .execute(Tuple.of(userName, protocol.dbValue(), expectedVersion))
        .map(rows -> {
          if (rows.rowCount() == 0) {
            throw new UserOptimisticLockException(
                userName + "#protocol-secret", expectedVersion);
          }
          return null;
        });
  }

  // ============================================================
  // Lectures (références uniquement — jamais de valeur)
  // ============================================================

  public Future<Optional<ProtocolSecretRecord>> get(String userName, Protocol protocol) {
    return pool.preparedQuery(
            "SELECT id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter "
                + "FROM csp_user_protocol_secret WHERE user_name = $1 AND protocol = $2")
        .execute(Tuple.of(userName, protocol.dbValue()))
        .map(rows -> rows.size() == 0
            ? Optional.empty()
            : Optional.of(mapRecord(rows.iterator().next(), userName, protocol)));
  }

  public Future<List<ProtocolSecretRecord>> listByUser(String userName) {
    return pool.preparedQuery(
            "SELECT protocol, id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter "
                + "FROM csp_user_protocol_secret WHERE user_name = $1 ORDER BY protocol")
        .execute(Tuple.of(userName))
        .map(rows -> {
          List<ProtocolSecretRecord> out = new ArrayList<>();
          for (Row r : rows) {
            Protocol p = Protocol.fromDb(r.getString("protocol"));
            out.add(mapRecord(r, userName, p));
          }
          return out;
        });
  }

  /**
   * Lookup par login protocole = csp_user.name EXACT (case-exact) — utilisé
   * par l'auth P4/P5. Pas de cache ici : la mise en cache lease/poll est le
   * rôle du {@link ProtocolAuthResolver}.
   */
  public Future<Optional<ProtocolSecretRecord>> findByUser(Protocol protocol,
                                                            String userName) {
    return pool.preparedQuery(
            "SELECT user_name, id, version, secret_ref, secret_version, "
                + "ucrc, enabled, valid_until, updated_by, updated_at, "
                + "cacheex_mode, cacheex_max_hop, cacheex_filter "
                + "FROM csp_user_protocol_secret "
                + "WHERE protocol = $1 AND user_name = $2")
        .execute(Tuple.of(protocol.dbValue(), userName))
        .map(rows -> rows.size() == 0
            ? Optional.empty()
            : Optional.of(mapRecord(rows.iterator().next(),
                rows.iterator().next().getString("user_name"), protocol)));
  }

  /**
   * Génération courante = AUTH EPOCH monotone (V7 {@code csp_auth_epoch}) —
   * MAX(version) était INFIRME (retour reviewer) : une écriture à version
   * basse ou un delete laissait MAX inchangé. L'epoch est bumpée par trigger
   * DANS la transaction de toute écriture de droits user (poll ≤ 1s, P4/P5).
   */
  public Future<Long> currentGeneration() {
    return pool.preparedQuery("SELECT epoch FROM csp_auth_epoch WHERE id = 1")
        .execute()
        .map(rows -> rows.iterator().next().getLong(0));
  }

  // ============================================================
  // Validation & mapping
  // ============================================================

  /** Le driver PG exige OffsetDateTime pour timestamptz ; null-safe. */
  private static OffsetDateTime toOffsetDateTime(Instant i) {
    return i != null ? i.atOffset(ZoneOffset.UTC) : null;
  }

  private static void validateSecretRef(String ref) {
    if (ref == null || (!ref.startsWith("env:") && !ref.startsWith("file:"))) {
      throw new IllegalArgumentException("secretRef must be env: or file: — value is never stored");
    }
    if (ref.length() <= 4) {
      throw new IllegalArgumentException("secretRef is empty after prefix");
    }
  }

  private static RuntimeException translateDbError(Throwable e, String userName) {
    String msg = e.getMessage() != null ? e.getMessage() : "";
    // unique_violation sur uq_csp_protocol_login ou ucrc => 409, pas de fusion
    if (msg.contains("uq_csp_protocol_login")) {
      return new SecretCollisionException("protocol login already taken (other account?)");
    }
    if (msg.contains("csp_user_protocol_secret_ucrc_key")
        || (msg.contains("ucrc") && msg.contains("duplicate key"))) {
      return new SecretCollisionException("ucrc collision with another account");
    }
    if (msg.contains("csp_user_protocol_secret_pkey")
        || msg.contains("csp_user_protocol_secret_user_name_protocol_key")) {
      return new UserOptimisticLockException(userName + "#protocol-secret", 0);
    }
    return e instanceof RuntimeException re ? re : new RuntimeException(e);
  }

  private static ProtocolSecretRecord mapRecord(Row r, String userName, Protocol protocol) {
    CacheExPolicy cacheex;
    try {
      cacheex = CacheExPolicy.fromDb(
          r.getInteger("cacheex_mode"), r.getInteger("cacheex_max_hop"),
          r.getString("cacheex_filter"));
    } catch (Exception e) {
      // Pré-V15 (colonnes absentes) — off
      cacheex = CacheExPolicy.OFF;
    }
    return new ProtocolSecretRecord(userName, protocol,
        r.getString("secret_ref"), r.getLong("secret_version"), r.getLong("ucrc"),
        r.getBoolean("enabled"),
        r.getOffsetDateTime("valid_until") != null
            ? r.getOffsetDateTime("valid_until").toInstant() : null,
        r.getLong("version"), r.getString("updated_by"),
        r.getOffsetDateTime("updated_at") != null
            ? r.getOffsetDateTime("updated_at").toInstant() : null,
        cacheex);
  }

  /**
   * ucrc camd35 = CRC32(MD5(name)) en uint32 — module-camd35.c l.142/866
   * (contrat identique à {@code Camd35Crypto.ucrc} ; dupliquée ici car le
   * module users ne dépend pas du codec — les deux implémentations sont
   * figées par le même golden python3 : ucrc("test") = 0xe807815d).
   */
  public static Long ucrcOf(String name) {
    try {
      byte[] md5 = MessageDigest.getInstance("MD5")
          .digest(name.getBytes(StandardCharsets.US_ASCII));
      CRC32 crc = new CRC32();
      crc.update(md5, 0, 16);
      return crc.getValue();
    } catch (Exception e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }
}
