package com.bowman.cspng.users;

import com.bowman.cspng.users.crypto.PasswordHasher;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Repository reactif pour la gestion CRUD des utilisateurs dans PostgreSQL.
 *
 * <p>Chaque opération d'écriture est transactionnelle et inclut :
 * <ul>
 *   <li>Optimistic locking ({@code WHERE version = ?})</li>
 *   <li>Protection du dernier admin actif via {@code pg_advisory_xact_lock} transaction-scoped</li>
 *   <li>Audit atomique ({@code csp_audit_log})</li>
 *   <li>Trigger NOTIFY PostgreSQL existant</li>
 *   <li>Bcrypt offload hors event-loop (Vert.x executeBlocking)</li>
 * </ul>
 *
 * <p>Les lectures ne retournent jamais {@code password_hash}.
 */
public class UserRepository {

    private static final Logger LOG = Logger.getLogger(UserRepository.class.getName());
    private static final String NOTIFY_USER_CHANGED = "NOTIFY csp_user_changed";
    // Advisory xact lock id for serializing admin mutations (last-admin protection) and bootstrap
    private static final long ADMIN_XACT_LOCK_ID = 0x4353505F55534552L; // "CSP_USER"
    static final long BOOTSTRAP_XACT_LOCK_ID = 0x4353505F41444D49L; // "CSP_ADMI" reuse for bootstrap

    private final Pool pool;
    private final PasswordHasher hasher;
    private final Vertx vertx;

    public UserRepository(Pool pool) {
        this(pool, new PasswordHasher(), null);
    }

    public UserRepository(Pool pool, PasswordHasher hasher) {
        this(pool, hasher, null);
    }

    public UserRepository(Pool pool, PasswordHasher hasher, Vertx vertx) {
        this.pool = pool;
        this.hasher = hasher;
        this.vertx = vertx;
    }

    // -- READ operations --

    public Future<List<UserRecord>> list() {
        return pool.withTransaction(client ->
            client.preparedQuery(
                "SELECT name, display_name, email, enabled, admin, debug, map_excluded, max_connections,"
                    + " ip_mask, allowed_ecm_rate, version, updated_at, valid_from, valid_until"
                    + " FROM csp_user ORDER BY lower(name)")
                .execute()
                .compose(rows -> {
                    if (rows.size() == 0) return Future.succeededFuture(Collections.emptyList());
                    List<UserRecord> users = new ArrayList<>();
                    List<Future<Void>> futures = new ArrayList<>();
                    for (Row r : rows) {
                        String name = r.getString("name");
                        futures.add(loadUserRelations(client, name, row -> mapUserWithoutHash(row, r)).map(u -> {
                            users.add(u);
                            return null;
                        }));
                    }
                    return Future.join(futures).map(users);
                }));
    }

    public Future<Optional<UserRecord>> get(String name) {
        if (name == null) return Future.succeededFuture(Optional.empty());
        return pool.withTransaction(client ->
            client.preparedQuery(
                "SELECT name, display_name, email, enabled, admin, debug, map_excluded, max_connections,"
                    + " ip_mask, allowed_ecm_rate, version, updated_at, valid_from, valid_until"
                    + " FROM csp_user WHERE lower(name)=lower($1)")
                .execute(Tuple.of(name))
                .compose(rows -> {
                    Row row = firstOrNull(rows);
                    if (row == null) return Future.succeededFuture(Optional.<UserRecord>empty());
                    String canonicalName = row.getString("name");
                    return loadUserRelations(client, canonicalName, rs -> mapUserWithoutHash(rs, row))
                        .map(Optional::of);
                }));
    }

    // -- bootstrap transactional --

    /**
     * Transaction bootstrap: aucun pre-check avant advisory lock.
     * Transaction 1: pg_advisory_xact_lock + count admin; si >0 -> empty même sans credentials.
     * Si count==0 et credentials absentes -> BootstrapCredentialsMissingException après lock.
     * Si count==0 et creds présentes -> hash bcrypt hors transaction/event-loop, puis transaction 2 lock+re-count+insert.
     */
    public Future<Optional<UserRecord>> bootstrapAdminIfNeeded(String bootstrapUser, String bootstrapPassword, String actor) {
        // Aucun check avant lock: on lock d'abord
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT pg_advisory_xact_lock($1)")
                .execute(Tuple.of(BOOTSTRAP_XACT_LOCK_ID))
                .compose(v -> client.preparedQuery(
                    "SELECT COUNT(*) as cnt FROM csp_user WHERE enabled = true AND admin = true").execute())
                .map(cntRows -> {
                    Row cntRow = firstOrNull(cntRows);
                    long cnt = cntRow != null ? cntRow.getLong("cnt") : 0;
                    return cnt;
                })
        ).compose(cnt -> {
            if (cnt > 0) {
                return Future.succeededFuture(Optional.<UserRecord>empty());
            }
            // count==0 : besoin credentials
            if (bootstrapUser == null || bootstrapUser.isBlank() || bootstrapPassword == null || bootstrapPassword.isBlank()) {
                return Future.failedFuture(new BootstrapCredentialsMissingException(
                    "No active admin and bootstrap credentials missing"));
            }
            validateName(bootstrapUser);
            validatePassword(bootstrapPassword);
            // Hash hors transaction/event-loop
            Future<String> hashFuture;
            if (vertx != null) {
                if (Context.isOnEventLoopThread()) {
                    hashFuture = vertx.executeBlocking(() -> hasher.hash(bootstrapPassword), false);
                } else {
                    hashFuture = hasher.hashAsync(bootstrapPassword, vertx);
                }
            } else {
                try {
                    hashFuture = Future.succeededFuture(hasher.hash(bootstrapPassword));
                } catch (Exception e) {
                    hashFuture = Future.failedFuture(e);
                }
            }
            return hashFuture.compose(hash ->
                pool.withTransaction(client2 ->
                    client2.preparedQuery("SELECT pg_advisory_xact_lock($1)")
                        .execute(Tuple.of(BOOTSTRAP_XACT_LOCK_ID))
                        .compose(v2 -> client2.preparedQuery(
                            "SELECT COUNT(*) as cnt FROM csp_user WHERE enabled = true AND admin = true").execute())
                        .compose(cntRows2 -> {
                            Row cntRow2 = firstOrNull(cntRows2);
                            long cnt2 = cntRow2 != null ? cntRow2.getLong("cnt") : 0;
                            if (cnt2 > 0) {
                                return Future.succeededFuture(Optional.<UserRecord>empty());
                            }
                            long nowEpoch = Instant.now().getEpochSecond();
                            return client2.preparedQuery(
                                "INSERT INTO csp_user (name, display_name, password_hash, email, enabled, admin, debug,"
                                    + " map_excluded, max_connections, ip_mask, allowed_ecm_rate, version, updated_at, valid_from, valid_until)"
                                    + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,1,to_timestamp($12),$13,$14)")
                                .execute(Tuple.of(
                                    bootstrapUser, "Administrator", hash, null, true, true, false, false, 0, null, -1, nowEpoch, null, null))
                                .compose(w -> insertAudit(client2, "USER_CREATED", bootstrapUser, null, 1L, actor != null ? actor : "bootstrap", "bootstrap admin created"))
                                .compose(w -> doNotify(client2))
                                .compose(w -> client2.preparedQuery(
                                    "SELECT name FROM csp_user WHERE lower(name)=lower($1)").execute(Tuple.of(bootstrapUser))
                                    .compose(rs -> {
                                        Row rr = firstOrNull(rs);
                                        String canonical = rr != null ? rr.getString("name") : bootstrapUser;
                                        return loadUserRelations(client2, canonical, rels ->
                                            new UserRecord(canonical, "Administrator", "", null, true, true, false, false, 0, null, -1,
                                                rels.profiles, rels.allowed, rels.blocked, rels.connectors, 1L, null, null))
                                            .map(Optional::of);
                                    }));
                        })
                )
            );
        });
    }

    // -- WRITE operations --

    public Future<UserRecord> create(CreateUserRequest req, String actor) {
        validateCreate(req);
        validateName(req.name());
        validatePassword(req.password());
        validateEmail(req.email());
        validateIpMask(req.ipMask());
        validateMaxConnections(req.maxConnections());
        validateAllowedEcmRate(req.allowedEcmRate());
        validateProfileSets(req.profiles(), req.allowedServices(), req.blockedServices(), req.allowedConnectors());

        // Offload bcrypt hors event-loop
        Future<String> hashFuture = offloadHash(req.password());

        return hashFuture.compose(hash -> {
            long nowEpoch = Instant.now().getEpochSecond();
            // clear plaintext reference asap after hash
            // req is record, cannot nullify, but we do not retain req.password beyond this scope
            return pool.withTransaction(client ->
                client.preparedQuery(
                    "INSERT INTO csp_user (name, display_name, password_hash, email, enabled, admin, debug,"
                        + " map_excluded, max_connections, ip_mask, allowed_ecm_rate, version, updated_at, valid_from, valid_until)"
                        + " VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,1,to_timestamp($12),$13,$14)")
                    .execute(Tuple.of(
                        req.name(),
                        req.displayName() != null && !req.displayName().isEmpty() ? req.displayName() : req.name(),
                        hash,
                        req.email(),
                        req.enabled(),
                        req.admin(),
                        req.debug(),
                        req.mapExcluded(),
                        req.maxConnections(),
                        req.ipMask(),
                        req.allowedEcmRate(),
                        nowEpoch,
                        req.validFrom() != null ? req.validFrom().getEpochSecond() : null,
                        req.validUntil() != null ? req.validUntil().getEpochSecond() : null
                    ))
                    .compose(v -> insertProfiles(client, req.name(), req.profiles()))
                    .compose(v -> insertServices(client, req.name(), req.allowedServices(), req.blockedServices()))
                    .compose(v -> insertConnectors(client, req.name(), req.allowedConnectors()))
                    .compose(v -> insertAudit(client, "USER_CREATED", req.name(), null, 1L, actor, "user created"))
                    .compose(v -> doNotify(client))
                    .compose(v -> getCanonical(client, req.name())
                        .compose(canonical -> loadUserRelations(client, canonical, rs ->
                            mapUserWithoutHash(rs,
                                new UserRecord(canonical, req.displayName() != null && !req.displayName().isEmpty() ? req.displayName() : canonical,
                                    hash, req.email(), req.enabled(), req.admin(), req.debug(), req.mapExcluded(),
                                    req.maxConnections(), req.ipMask(), req.allowedEcmRate(),
                                    req.profiles(), req.allowedServices(), req.blockedServices(), req.allowedConnectors(),
                                    1L, req.validFrom(), req.validUntil()
                                ))
                        ))
                    ));
        });
    }

    public Future<UserRecord> update(String name, UpdateUserRequest req, long expectedVersion, String actor) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("User name is required");
        validateEmail(req.email());
        if (req.password() != null) validatePassword(req.password());
        if (req.ipMask() != null) validateIpMask(req.ipMask());
        if (req.maxConnections() != null) validateMaxConnections(req.maxConnections());
        if (req.allowedEcmRate() != null) validateAllowedEcmRate(req.allowedEcmRate());
        if (req.profiles() != null || req.allowedServices() != null || req.blockedServices() != null || req.allowedConnectors() != null) {
            validateProfileSets(
                req.profiles() != null ? req.profiles() : Set.of(),
                req.allowedServices() != null ? req.allowedServices() : Map.of(),
                req.blockedServices() != null ? req.blockedServices() : Map.of(),
                req.allowedConnectors() != null ? req.allowedConnectors() : Set.of());
        }
        Future<String> hashFuture;
        if (req.password() != null) {
            hashFuture = offloadHash(req.password());
        } else {
            hashFuture = Future.succeededFuture(null);
        }
        return hashFuture.compose(hash ->
            pool.withTransaction(client ->
                // Sérialiser toutes mutations admin potentiellement dangereuses
                client.preparedQuery("SELECT pg_advisory_xact_lock($1)").execute(Tuple.of(ADMIN_XACT_LOCK_ID))
                    .compose(v -> client.preparedQuery(
                        "SELECT name, admin, enabled FROM csp_user WHERE lower(name)=lower($1) FOR UPDATE")
                        .execute(Tuple.of(name)))
                    .compose(rows -> {
                        Row row = firstOrNull(rows);
                        if (row == null) throw new UserNotFoundException(name);
                        String canonical = row.getString("name");
                        boolean currentlyAdmin = Boolean.TRUE.equals(row.getBoolean("admin"));
                        boolean currentlyEnabled = Boolean.TRUE.equals(row.getBoolean("enabled"));

                        boolean targetAdmin = req.admin() != null ? req.admin() : currentlyAdmin;
                        boolean targetEnabled = req.enabled() != null ? req.enabled() : currentlyEnabled;

                        // Last admin check: seulement si l'utilisateur était admin actif et devient non-admin/inactif
                        if (currentlyAdmin && currentlyEnabled && (!targetAdmin || !targetEnabled)) {
                            return client.preparedQuery(
                                "SELECT COUNT(*) as cnt FROM csp_user WHERE enabled = true AND admin = true AND lower(name) != lower($1)")
                                .execute(Tuple.of(canonical))
                                .compose(cntRows -> {
                                    Row cntRow = firstOrNull(cntRows);
                                    if (cntRow != null) {
                                        long otherAdmins = cntRow.getLong("cnt");
                                        if (otherAdmins == 0) {
                                            throw new LastAdminException(canonical);
                                        }
                                    }
                                    return doUpdateWithHash(client, canonical, req, hash, expectedVersion, actor, currentlyAdmin, currentlyEnabled);
                                });
                        }
                        return doUpdateWithHash(client, canonical, req, hash, expectedVersion, actor, currentlyAdmin, currentlyEnabled);
                    })
            )
        );
    }

    private Future<UserRecord> doUpdateWithHash(io.vertx.sqlclient.SqlConnection client, String canonical,
                                         UpdateUserRequest req, String hash, long expectedVersion, String actor,
                                         boolean currentlyAdmin, boolean currentlyEnabled) {
        StringBuilder setClause = new StringBuilder("SET version = version + 1, updated_at = now()");
        List<Object> params = new ArrayList<>();

        if (req.displayName() != null) { setClause.append(", display_name = $"); params.add(req.displayName()); setClause.append(params.size()); }
        if (hash != null) { setClause.append(", password_hash = $"); params.add(hash); setClause.append(params.size()); }
        if (req.email() != null) { setClause.append(", email = $"); params.add(req.email()); setClause.append(params.size()); }
        if (req.enabled() != null) { setClause.append(", enabled = $"); params.add(req.enabled()); setClause.append(params.size()); }
        if (req.admin() != null) { setClause.append(", admin = $"); params.add(req.admin()); setClause.append(params.size()); }
        if (req.debug() != null) { setClause.append(", debug = $"); params.add(req.debug()); setClause.append(params.size()); }
        if (req.mapExcluded() != null) { setClause.append(", map_excluded = $"); params.add(req.mapExcluded()); setClause.append(params.size()); }
        if (req.maxConnections() != null) { setClause.append(", max_connections = $"); params.add(req.maxConnections()); setClause.append(params.size()); }
        if (req.ipMask() != null) { setClause.append(", ip_mask = $"); params.add(req.ipMask()); setClause.append(params.size()); }
        if (req.allowedEcmRate() != null) { setClause.append(", allowed_ecm_rate = $"); params.add(req.allowedEcmRate()); setClause.append(params.size()); }
        // NOTE (fix passage 4) : les parenthèses fermantes de to_timestamp($N)
        // manquaient — SQL invalide « to_timestamp($13 WHERE ... » dès qu'un
        // valid_from/valid_until était fourni à l'update (jamais couvert avant).
        if (req.validFrom() != null) { setClause.append(", valid_from = to_timestamp($"); params.add(req.validFrom().getEpochSecond()); setClause.append(params.size()).append(")"); }
        if (req.validUntil() != null) { setClause.append(", valid_until = to_timestamp($"); params.add(req.validUntil().getEpochSecond()); setClause.append(params.size()).append(")"); }

        setClause.append(" WHERE lower(name)=lower($");
        params.add(canonical);
        int nameIdx = params.size();
        setClause.append(nameIdx).append(") AND version = $");
        params.add(expectedVersion);
        int verIdx = params.size();
        setClause.append(verIdx);

        String sql = "UPDATE csp_user " + setClause;
        Tuple tuple = Tuple.tuple();
        for (Object p : params) tuple.addValue(p);

        return client.preparedQuery(sql)
            .execute(tuple)
            .compose(rows -> {
                if (rows.rowCount() == 0) {
                    throw new UserOptimisticLockException(canonical, expectedVersion);
                }
                long newVersion = expectedVersion + 1;
                Future<Void> profilesFuture = Future.succeededFuture();
                Future<Void> servicesFuture = Future.succeededFuture();
                Future<Void> connectorsFuture = Future.succeededFuture();
                if (req.profiles() != null) {
                    profilesFuture = client.preparedQuery("DELETE FROM csp_user_profile WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v -> insertProfiles(client, canonical, req.profiles()));
                }
                if (req.allowedServices() != null && req.blockedServices() != null) {
                    servicesFuture = client.preparedQuery("DELETE FROM csp_user_service WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v -> insertServices(client, canonical, req.allowedServices(), req.blockedServices()));
                } else if (req.allowedServices() != null) {
                    servicesFuture = client.preparedQuery("DELETE FROM csp_user_service WHERE lower(user_name)=lower($1) AND mode=0")
                        .execute(Tuple.of(canonical))
                        .compose(v -> insertServices(client, canonical, req.allowedServices(), Map.of()));
                } else if (req.blockedServices() != null) {
                    servicesFuture = client.preparedQuery("DELETE FROM csp_user_service WHERE lower(user_name)=lower($1) AND mode=1")
                        .execute(Tuple.of(canonical))
                        .compose(v -> insertServices(client, canonical, Map.of(), req.blockedServices()));
                }
                if (req.allowedConnectors() != null) {
                    connectorsFuture = client.preparedQuery("DELETE FROM csp_user_connector WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v -> insertConnectors(client, canonical, req.allowedConnectors()));
                }
                return Future.join(List.of(profilesFuture, servicesFuture, connectorsFuture))
                    .compose(v -> insertAudit(client, "USER_UPDATED", canonical, expectedVersion, newVersion, actor, "user updated"))
                    .compose(v -> doNotify(client))
                    .compose(v -> loadUserRelations(client, canonical, rs -> mapUserWithoutHash(rs,
                        new UserRecord(canonical,
                            req.displayName() != null ? req.displayName() : canonical,
                            "", // never return hash
                            req.email(), req.enabled() != null ? req.enabled() : currentlyEnabled,
                            req.admin() != null ? req.admin() : currentlyAdmin,
                            req.debug() != null ? req.debug() : false,
                            req.mapExcluded() != null ? req.mapExcluded() : false,
                            req.maxConnections() != null ? req.maxConnections() : 1,
                            req.ipMask(), req.allowedEcmRate() != null ? req.allowedEcmRate() : -1,
                            req.profiles() != null ? req.profiles() : Set.of(),
                            req.allowedServices() != null ? req.allowedServices() : Map.of(),
                            req.blockedServices() != null ? req.blockedServices() : Map.of(),
                            req.allowedConnectors() != null ? req.allowedConnectors() : Set.of(),
                            newVersion, req.validFrom(), req.validUntil()
                        ))
                    ));
            });
    }

    // ============================================================
    // Bulk users (contrat v2 §2) — versions PAR LIGNE, ordre croissant name,
    // UNE transaction (verrou advisory), rollback total, garde last-admin par user.
    // ============================================================

    /**
     * Patch bulk d'options communes de plusieurs users. Tout-ou-rien : un user inconnu
     * ({@link UnknownUsersException}), une version en désaccord
     * ({@link UserOptimisticLockException}) ou une valeur invalide roule back TOUT.
     * Les sets ({@code profiles}, {@code allowedConnectors}, services) fournis remplacent
     * intégralement les valeurs existantes ; null = inchangé.
     */
    public Future<List<UserBulkPatch.BulkResult>> bulkUpdateUsers(
        List<java.util.Map.Entry<String, Long>> items, UserBulkPatch patch,
        java.util.Set<String> present, String actor) {
        if (items == null || items.isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException("items must contain at least 1 element"));
        }
        if (items.size() > 100) {
            return Future.failedFuture(new IllegalArgumentException("items limit 100"));
        }
        if (patch == null || present == null || present.isEmpty()) {
            return Future.failedFuture(new IllegalArgumentException("patch must contain at least 1 field"));
        }
        // null explicite sur un set = ambigu => 400 (omettre pour inchangé, [] pour vider)
        for (String setKey : new String[]{"profiles", "allowedConnectors", "allowedServices", "blockedServices"}) {
            if (present.contains(setKey)) {
                Object v = switch (setKey) {
                    case "profiles" -> patch.profiles();
                    case "allowedConnectors" -> patch.allowedConnectors();
                    case "allowedServices" -> patch.allowedServices();
                    default -> patch.blockedServices();
                };
                if (v == null) {
                    return Future.failedFuture(new IllegalArgumentException(
                        "bulk patch field '" + setKey + "' does not accept null (omit it, or [] to clear)"));
                }
            }
        }
        // Validation des valeurs (fail-closed, mêmes règles que le PUT unitaire)
        if (patch.email() != null) validateEmail(patch.email());
        if (patch.ipMask() != null) validateIpMask(patch.ipMask());
        if (patch.maxConnections() != null) validateMaxConnections(patch.maxConnections());
        if (patch.allowedEcmRate() != null) validateAllowedEcmRate(patch.allowedEcmRate());
        if (patch.profiles() != null || patch.allowedServices() != null
            || patch.blockedServices() != null || patch.allowedConnectors() != null) {
            validateProfileSets(
                patch.profiles() != null ? patch.profiles() : Set.of(),
                patch.allowedServices() != null ? patch.allowedServices() : Map.of(),
                patch.blockedServices() != null ? patch.blockedServices() : Map.of(),
                patch.allowedConnectors() != null ? patch.allowedConnectors() : Set.of());
        }
        // Ordre déterministe : name croissant, doublons interdits
        List<java.util.Map.Entry<String, Long>> sorted = new java.util.ArrayList<>(items);
        sorted.sort(java.util.Comparator.comparing(Map.Entry::getKey));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var it : sorted) {
            if (!seen.add(it.getKey())) {
                return Future.failedFuture(new IllegalArgumentException("duplicate item id: " + it.getKey()));
            }
        }

        return pool.withTransaction(client ->
            client.preparedQuery("SELECT pg_advisory_xact_lock($1)").execute(Tuple.of(ADMIN_XACT_LOCK_ID))
                .compose(v -> {
                    Future<List<UserBulkPatch.BulkResult>> chain =
                        Future.succeededFuture(new java.util.ArrayList<UserBulkPatch.BulkResult>());
                    for (var it : sorted) {
                        chain = chain.compose(results -> bulkUpdateOneUser(
                            client, it.getKey(), it.getValue(), patch, present, actor)
                            .map(r -> {
                                results.add(r);
                                return results;
                            }));
                    }
                    return chain;
                }));
    }

    private Future<UserBulkPatch.BulkResult> bulkUpdateOneUser(
        io.vertx.sqlclient.SqlConnection client, String name, long expectedVersion,
        UserBulkPatch patch, java.util.Set<String> present, String actor) {
        return client.preparedQuery(
                "SELECT name, admin, enabled, version FROM csp_user WHERE lower(name)=lower($1) FOR UPDATE")
            .execute(Tuple.of(name))
            .compose(rows -> {
                Row row = firstOrNull(rows);
                if (row == null) throw new UnknownUsersException(List.of(name));
                String canonical = row.getString("name");
                long currentVersion = row.getLong("version");
                if (currentVersion != expectedVersion) {
                    throw new UserOptimisticLockException(canonical, expectedVersion);
                }
                boolean currentlyAdmin = Boolean.TRUE.equals(row.getBoolean("admin"));
                boolean currentlyEnabled = Boolean.TRUE.equals(row.getBoolean("enabled"));
                boolean targetEnabled = patch.enabled() != null ? patch.enabled() : currentlyEnabled;

                // Garde last-admin : un admin actif ne peut pas devenir inactif en masse
                if (currentlyAdmin && currentlyEnabled && !targetEnabled) {
                    return client.preparedQuery(
                        "SELECT COUNT(*) as cnt FROM csp_user WHERE enabled = true AND admin = true AND lower(name) != lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(cntRows -> {
                            Row cntRow = firstOrNull(cntRows);
                            if (cntRow != null && cntRow.getLong("cnt") == 0) {
                                throw new LastAdminException(canonical);
                            }
                            return doBulkUserUpdate(client, canonical, expectedVersion, patch, present, actor);
                        });
                }
                return doBulkUserUpdate(client, canonical, expectedVersion, patch, present, actor);
            });
    }

    private Future<UserBulkPatch.BulkResult> doBulkUserUpdate(
        io.vertx.sqlclient.SqlConnection client, String canonical, long expectedVersion,
        UserBulkPatch patch, java.util.Set<String> present, String actor) {
        StringBuilder setClause = new StringBuilder("SET ");
        List<Object> params = new ArrayList<>();
        boolean first = true;
        // admin/password/validity JAMAIS patchables en bulk (contrat v2 §2.1).
        // Sémantique présence (v2 §2) : absent=inchangé ; présent null + NULLABLE=reset
        // NULL ; présent null + NOT NULL=400 (jamais de succès silencieux).
        first = appendUserBulkCol(setClause, params, first, "display_name", "displayName",
            patch.displayName(), present, true);
        first = appendUserBulkCol(setClause, params, first, "email", "email",
            patch.email(), present, true);
        first = appendUserBulkCol(setClause, params, first, "enabled", "enabled",
            patch.enabled(), present, false);
        first = appendUserBulkCol(setClause, params, first, "debug", "debug",
            patch.debug(), present, false);
        first = appendUserBulkCol(setClause, params, first, "map_excluded", "mapExcluded",
            patch.mapExcluded(), present, false);
        first = appendUserBulkCol(setClause, params, first, "max_connections", "maxConnections",
            patch.maxConnections(), present, false);
        first = appendUserBulkCol(setClause, params, first, "ip_mask", "ipMask",
            patch.ipMask(), present, true);
        first = appendUserBulkCol(setClause, params, first, "allowed_ecm_rate", "allowedEcmRate",
            patch.allowedEcmRate(), present, false);
        setClause.append(", version = version + 1, updated_at = now(), updated_by = $")
                 .append(params.size() + 1);
        params.add(actor);
        setClause.append(" WHERE lower(name)=lower($").append(params.size() + 1)
                 .append(") AND version = $").append(params.size() + 2);
        params.add(canonical);
        params.add(expectedVersion);

        Tuple tuple = Tuple.tuple();
        for (Object p : params) tuple.addValue(p);

        return client.preparedQuery("UPDATE csp_user " + setClause + " RETURNING version")
            .execute(tuple)
            .compose(rows -> {
                if (rows.rowCount() == 0) {
                    throw new UserOptimisticLockException(canonical, expectedVersion);
                }
                long newVersion = firstOrNull(rows).getLong("version");
                Future<Void> chain = Future.succeededFuture();
                if (patch.profiles() != null) {
                    chain = chain.compose(v -> client.preparedQuery(
                            "DELETE FROM csp_user_profile WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v2 -> insertProfiles(client, canonical, patch.profiles())));
                }
                if (patch.allowedServices() != null && patch.blockedServices() != null) {
                    chain = chain.compose(v -> client.preparedQuery(
                            "DELETE FROM csp_user_service WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v2 -> insertServices(client, canonical,
                            patch.allowedServices(), patch.blockedServices())));
                } else if (patch.allowedServices() != null) {
                    chain = chain.compose(v -> client.preparedQuery(
                            "DELETE FROM csp_user_service WHERE lower(user_name)=lower($1) AND mode=0")
                        .execute(Tuple.of(canonical))
                        .compose(v2 -> insertServices(client, canonical,
                            patch.allowedServices(), Map.of())));
                } else if (patch.blockedServices() != null) {
                    chain = chain.compose(v -> client.preparedQuery(
                            "DELETE FROM csp_user_service WHERE lower(user_name)=lower($1) AND mode=1")
                        .execute(Tuple.of(canonical))
                        .compose(v2 -> insertServices(client, canonical,
                            Map.of(), patch.blockedServices())));
                }
                if (patch.allowedConnectors() != null) {
                    chain = chain.compose(v -> client.preparedQuery(
                            "DELETE FROM csp_user_connector WHERE lower(user_name)=lower($1)")
                        .execute(Tuple.of(canonical))
                        .compose(v2 -> insertConnectors(client, canonical, patch.allowedConnectors())));
                }
                return chain
                    .compose(v -> insertAudit(client, "USER_BULK_UPDATED", canonical,
                        expectedVersion, newVersion, actor, "bulk user patch"))
                    .compose(v -> doNotify(client))
                    .map(v -> new UserBulkPatch.BulkResult(canonical, newVersion));
            });
    }

    private static boolean appendUserBulkCol(StringBuilder setClause, List<Object> params,
                                             boolean first, String col, String patchKey,
                                             Object value, java.util.Set<String> present,
                                             boolean nullable) {
        if (!first) setClause.append(", ");
        if (!present.contains(patchKey)) {
            setClause.append(col).append(" = ").append(col);
        } else if (value != null) {
            setClause.append(col).append(" = $").append(params.size() + 1);
            params.add(value);
        } else if (!nullable) {
            throw new IllegalArgumentException(
                "bulk patch field '" + patchKey + "' does not accept null (omit it to leave unchanged)");
        } else {
            setClause.append(col).append(" = NULL");
        }
        return false;
    }

    public Future<Void> delete(String name, long expectedVersion, String actor) {
        return pool.withTransaction(client ->
            client.preparedQuery("SELECT pg_advisory_xact_lock($1)").execute(Tuple.of(ADMIN_XACT_LOCK_ID))
                .compose(v -> client.preparedQuery(
                    "SELECT name, admin, enabled FROM csp_user WHERE lower(name)=lower($1) FOR UPDATE")
                    .execute(Tuple.of(name)))
                .compose(rows -> {
                    Row row = firstOrNull(rows);
                    if (row == null) throw new UserNotFoundException(name);
                    String canonical = row.getString("name");
                    if (Boolean.TRUE.equals(row.getBoolean("admin")) && Boolean.TRUE.equals(row.getBoolean("enabled"))) {
                        return client.preparedQuery(
                            "SELECT COUNT(*) as cnt FROM csp_user WHERE enabled = true AND admin = true AND lower(name) != lower($1)")
                            .execute(Tuple.of(canonical))
                            .compose(cntRows -> {
                                Row cntRow = firstOrNull(cntRows);
                                if (cntRow != null && cntRow.getLong("cnt") == 0) {
                                    throw new LastAdminException(canonical);
                                }
                                return doDelete(client, canonical, expectedVersion, actor);
                            });
                    }
                    return doDelete(client, canonical, expectedVersion, actor);
                }));
    }

    private Future<Void> doDelete(io.vertx.sqlclient.SqlConnection client, String canonical, long expectedVersion, String actor) {
        return client.preparedQuery(
            "DELETE FROM csp_user_profile WHERE lower(user_name)=lower($1)")
            .execute(Tuple.of(canonical))
            .compose(v -> client.preparedQuery(
                "DELETE FROM csp_user_service WHERE lower(user_name)=lower($1)")
                .execute(Tuple.of(canonical)))
            .compose(v -> client.preparedQuery(
                "DELETE FROM csp_user_connector WHERE lower(user_name)=lower($1)")
                .execute(Tuple.of(canonical)))
            .compose(v -> client.preparedQuery(
                "DELETE FROM csp_user WHERE lower(name)=lower($1) AND version = $2")
                .execute(Tuple.of(canonical, expectedVersion)))
            .compose(rows -> {
                if (rows.rowCount() == 0) {
                    throw new UserOptimisticLockException(canonical, expectedVersion);
                }
                return insertAudit(client, "USER_DELETED", canonical, expectedVersion, null, actor, "user deleted")
                    .compose(v -> doNotify(client));
            });
    }

    // -- helpers --

    private Future<String> offloadHash(String plain) {
        if (vertx != null) {
            try {
                if (Context.isOnEventLoopThread()) {
                    return vertx.executeBlocking(() -> hasher.hash(plain), false);
                }
            } catch (Throwable ignored) {}
            return hasher.hashAsync(plain, vertx);
        } else {
            try {
                return Future.succeededFuture(hasher.hash(plain));
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        }
    }

    private Future<String> getCanonical(io.vertx.sqlclient.SqlConnection client, String name) {
        return client.preparedQuery("SELECT name FROM csp_user WHERE lower(name)=lower($1)")
            .execute(Tuple.of(name))
            .map(rows -> {
                Row r = firstOrNull(rows);
                return r != null ? r.getString("name") : name;
            });
    }

    private Future<UserRecord> loadUserRelations(io.vertx.sqlclient.SqlConnection client, String canonicalName,
                                                  java.util.function.Function<UserRelations, UserRecord> mapper) {
        return client.preparedQuery("SELECT profile FROM csp_user_profile WHERE lower(user_name)=lower($1)")
            .execute(Tuple.of(canonicalName))
            .compose(profileRows -> {
                Set<String> profiles = new HashSet<>();
                for (Row r : profileRows) profiles.add(r.getString("profile"));
                return client.preparedQuery("SELECT profile, sid, mode FROM csp_user_service WHERE lower(user_name)=lower($1)")
                    .execute(Tuple.of(canonicalName))
                    .compose(svcRows -> {
                        Map<String, Set<Integer>> allowed = new HashMap<>();
                        Map<String, Set<Integer>> blocked = new HashMap<>();
                        for (Row r : svcRows) {
                            String prof = r.getString("profile");
                            int sid = r.getInteger("sid");
                            int mode = r.getInteger("mode");
                            if (mode == 0) allowed.computeIfAbsent(prof, k -> new HashSet<>()).add(sid);
                            else blocked.computeIfAbsent(prof, k -> new HashSet<>()).add(sid);
                        }
                        return client.preparedQuery("SELECT connector FROM csp_user_connector WHERE lower(user_name)=lower($1)")
                            .execute(Tuple.of(canonicalName))
                            .map(connRows -> {
                                Set<String> connectors = new HashSet<>();
                                for (Row r : connRows) connectors.add(r.getString("connector"));
                                return mapper.apply(new UserRelations(profiles, allowed, blocked, connectors));
                            });
                    });
            });
    }

    private Future<Void> insertProfiles(io.vertx.sqlclient.SqlConnection client, String name, Set<String> profiles) {
        if (profiles == null || profiles.isEmpty()) return Future.succeededFuture();
        List<Future<Void>> futures = new ArrayList<>();
        for (String prof : profiles) {
            futures.add(client.preparedQuery(
                "INSERT INTO csp_user_profile (user_name, profile) VALUES ($1,$2) ON CONFLICT DO NOTHING")
                .execute(Tuple.of(name, prof)).mapEmpty());
        }
        return Future.join(futures).mapEmpty();
    }

    private Future<Void> insertServices(io.vertx.sqlclient.SqlConnection client, String name,
                                         Map<String, Set<Integer>> allowed, Map<String, Set<Integer>> blocked) {
        List<Future<Void>> futures = new ArrayList<>();
        if (allowed != null) {
            for (var e : allowed.entrySet()) {
                if (e.getValue() != null) {
                    for (int sid : e.getValue()) {
                        futures.add(client.preparedQuery(
                            "INSERT INTO csp_user_service (user_name, profile, sid, mode) VALUES ($1,$2,$3,0) ON CONFLICT DO NOTHING")
                            .execute(Tuple.of(name, e.getKey(), sid)).mapEmpty());
                    }
                }
            }
        }
        if (blocked != null) {
            for (var e : blocked.entrySet()) {
                if (e.getValue() != null) {
                    for (int sid : e.getValue()) {
                        futures.add(client.preparedQuery(
                            "INSERT INTO csp_user_service (user_name, profile, sid, mode) VALUES ($1,$2,$3,1) ON CONFLICT DO NOTHING")
                            .execute(Tuple.of(name, e.getKey(), sid)).mapEmpty());
                    }
                }
            }
        }
        if (futures.isEmpty()) return Future.succeededFuture();
        return Future.join(futures).mapEmpty();
    }

    private Future<Void> insertConnectors(io.vertx.sqlclient.SqlConnection client, String name, Set<String> connectors) {
        if (connectors == null || connectors.isEmpty()) return Future.succeededFuture();
        List<Future<Void>> futures = new ArrayList<>();
        for (String c : connectors) {
            futures.add(client.preparedQuery(
                "INSERT INTO csp_user_connector (user_name, connector) VALUES ($1,$2) ON CONFLICT DO NOTHING")
                .execute(Tuple.of(name, c)).mapEmpty());
        }
        return Future.join(futures).mapEmpty();
    }

    private Future<Void> insertAudit(io.vertx.sqlclient.SqlConnection client,
                                      String eventType, String entityName,
                                      Long oldVersion, Long newVersion,
                                      String actor, String summary) {
        return client.preparedQuery(
            "INSERT INTO csp_audit_log (event_type, entity_type, entity_name, old_version, new_version, changed_by, summary)"
                + " VALUES ($1,$2,$3,$4,$5,$6,$7)")
            .execute(Tuple.of(eventType, "csp_user", entityName, oldVersion, newVersion, actor, summary))
            .mapEmpty();
    }

    private Future<Void> doNotify(io.vertx.sqlclient.SqlConnection client) {
        return client.query(NOTIFY_USER_CHANGED).execute().mapEmpty();
    }

    private UserRecord mapUserWithoutHash(UserRelations relations, Row r) {
        String name = r.getString("name");
        String displayName = r.getString("display_name");
        if (displayName == null) displayName = name;
        String email = r.getString("email");
        boolean enabled = Boolean.TRUE.equals(r.getBoolean("enabled"));
        boolean admin = Boolean.TRUE.equals(r.getBoolean("admin"));
        boolean debug = Boolean.TRUE.equals(r.getBoolean("debug"));
        boolean mapExcluded = Boolean.TRUE.equals(r.getBoolean("map_excluded"));
        Integer maxConn = r.getInteger("max_connections");
        int maxConnections = maxConn != null ? maxConn : 1;
        String ipMask = r.getString("ip_mask");
        Integer ecmRate = r.getInteger("allowed_ecm_rate");
        int allowedEcmRate = ecmRate != null ? ecmRate : -1;
        long version = r.getLong("version") != null ? r.getLong("version") : 1L;
        Instant validFrom = r.getOffsetDateTime("valid_from") != null ? r.getOffsetDateTime("valid_from").toInstant() : null;
        Instant validUntil = r.getOffsetDateTime("valid_until") != null ? r.getOffsetDateTime("valid_until").toInstant() : null;
        return new UserRecord(name, displayName, "", email, enabled, admin, debug, mapExcluded,
            maxConnections, ipMask, allowedEcmRate,
            relations.profiles, relations.allowed, relations.blocked, relations.connectors,
            version, validFrom, validUntil);
    }

    private UserRecord mapUserWithoutHash(UserRelations relations, UserRecord base) {
        return new UserRecord(base.name(), base.displayName(), "", base.email(),
            base.enabled(), base.admin(), base.debug(), base.mapExcluded(),
            base.maxConnections(), base.ipMask(), base.allowedEcmRate(),
            relations.profiles, relations.allowed, relations.blocked, relations.connectors,
            base.version(), base.validFrom(), base.validUntil());
    }

    // -- Validation --

    private void validateCreate(CreateUserRequest req) {
        if (req.maxConnections() < -1) throw new IllegalArgumentException("maxConnections must be >= -1");
        if (req.allowedEcmRate() < -1) throw new IllegalArgumentException("allowedEcmRate must be >= -1");
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("User name is required");
        if (name.length() < 1 || name.length() > 128) throw new IllegalArgumentException("User name length must be 1-128");
        if (!name.matches("[a-zA-Z0-9_\\-.@]+")) throw new IllegalArgumentException("User name contains invalid characters");
    }

    private void validateEmail(String email) {
        if (email != null) {
            if (email.length() > 255) throw new IllegalArgumentException("Email too long (max 255)");
            if (!email.contains("@") || email.length() < 5) throw new IllegalArgumentException("Invalid email format");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Password is required");
        if (password.length() < 4 || password.length() > 128)
            throw new IllegalArgumentException("Password must be 4-128 characters");
    }

    private void validateIpMask(String ipMask) {
        if (ipMask != null && !ipMask.isBlank()) {
            if (!IpMatcher.isValidMask(ipMask))
                throw new IllegalArgumentException("Invalid IP mask: " + ipMask);
        }
    }

    private void validateMaxConnections(int maxConnections) {
        if (maxConnections < -1 || maxConnections > 100000)
            throw new IllegalArgumentException("maxConnections must be -1..100000");
    }

    private void validateAllowedEcmRate(int ecmRate) {
        if (ecmRate < -1 || ecmRate > 100000)
            throw new IllegalArgumentException("allowedEcmRate must be -1..100000");
    }

    private void validateProfileSets(Set<String> profiles, Map<String, Set<Integer>> allowed,
                                      Map<String, Set<Integer>> blocked, Set<String> connectors) {
        if (profiles.size() > 500) throw new IllegalArgumentException("Too many profiles (max 500)");
        if (connectors.size() > 500) throw new IllegalArgumentException("Too many connectors (max 500)");
        int svcCount = 0;
        for (var e : allowed.entrySet()) {
            if (e.getValue() != null) for (int sid : e.getValue()) {
                if (sid < 0 || sid > 65535) throw new IllegalArgumentException("SID must be 0..65535");
                svcCount++;
            }
        }
        for (var e : blocked.entrySet()) {
            if (e.getValue() != null) for (int sid : e.getValue()) {
                if (sid < 0 || sid > 65535) throw new IllegalArgumentException("SID must be 0..65535");
                svcCount++;
            }
        }
        if (svcCount > 2000) throw new IllegalArgumentException("Too many service entries (max 2000)");
    }

    private static Row firstOrNull(RowSet<Row> rows) {
        if (rows == null) return null;
        var it = rows.iterator();
        return it.hasNext() ? it.next() : null;
    }

    private record UserRelations(Set<String> profiles, Map<String, Set<Integer>> allowed,
                                  Map<String, Set<Integer>> blocked, Set<String> connectors) {}

    public record CreateUserRequest(
        String name, String displayName, String password, String email,
        boolean enabled, boolean admin, boolean debug, boolean mapExcluded,
        int maxConnections, String ipMask, int allowedEcmRate,
        Set<String> profiles,
        Map<String, Set<Integer>> allowedServices,
        Map<String, Set<Integer>> blockedServices,
        Set<String> allowedConnectors,
        Instant validFrom, Instant validUntil
    ) {
        public CreateUserRequest {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (password == null || password.isEmpty()) throw new IllegalArgumentException("password is required");
            if (profiles == null) profiles = Set.of();
            if (allowedServices == null) allowedServices = Map.of();
            if (blockedServices == null) blockedServices = Map.of();
            if (allowedConnectors == null) allowedConnectors = Set.of();
        }
    }

    public record UpdateUserRequest(
        String displayName, String password, String email,
        Boolean enabled, Boolean admin, Boolean debug, Boolean mapExcluded,
        Integer maxConnections, String ipMask, Integer allowedEcmRate,
        Set<String> profiles,
        Map<String, Set<Integer>> allowedServices,
        Map<String, Set<Integer>> blockedServices,
        Set<String> allowedConnectors,
        Instant validFrom, Instant validUntil
    ) {}
}
