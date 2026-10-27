package com.bowman.cspng.connectors.catalog;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository PostgreSQL pour le catalogue de cartes persistantes (V8).
 *
 * <p>Contrats P6 (docs/INBOUND-CAMS-PLAN.md §P6) :
 * <ul>
 *   <li>Allocation atomique et persistante du {@code cardId} (séquence uint32 NO CYCLE).</li>
 *   <li>Relation n:n capacités/sources amont avec provenance exacte.</li>
 *   <li>Verrou optimiste sur le statut ({@code state}) pour l'audit et l'administration.</li>
 * </ul>
 */
public class CardCatalogRepository {

  private final Pool pool;

  public CardCatalogRepository(Pool pool) {
    this.pool = pool;
  }

  /**
   * Alloue ou récupère une capacité (profile, caid, provider).
   * L'allocation de card_id est atomique et unique pour toute la durée de vie du système.
   */
  public Future<CardCapability> allocateOrGetCapability(String profile, int caid, int provider) {
    Objects.requireNonNull(profile, "profile");
    return pool.preparedQuery(
        "INSERT INTO csp_card_capability (profile, caid, provider, card_id) "
            + "VALUES ($1, $2, $3, nextval('csp_card_id_seq')) "
            + "ON CONFLICT (profile, caid, provider) DO UPDATE "
            + "SET updated_at = now() "
            + "RETURNING id, profile, caid, provider, state, card_id, version, updated_at")
        .execute(Tuple.of(profile, caid, provider))
        .map(rows -> mapCapability(rows.iterator().next()));
  }

  public Future<Optional<CardCapability>> getCapability(String profile, int caid, int provider) {
    return pool.preparedQuery(
        "SELECT id, profile, caid, provider, state, card_id, version, updated_at "
            + "FROM csp_card_capability WHERE profile = $1 AND caid = $2 AND provider = $3")
        .execute(Tuple.of(profile, caid, provider))
        .map(rows -> rows.size() == 0 ? Optional.empty() : Optional.of(mapCapability(rows.iterator().next())));
  }

  public Future<CardCapability> setCapabilityState(long capabilityId, CardCapability.State newState, long expectedVersion) {
    return pool.preparedQuery(
        "UPDATE csp_card_capability SET state = $1, version = version + 1, updated_at = now() "
            + "WHERE id = $2 AND version = $3 "
            + "RETURNING id, profile, caid, provider, state, card_id, version, updated_at")
        .execute(Tuple.of(newState.dbValue(), capabilityId, expectedVersion))
        .map(rows -> {
          if (rows.size() == 0) throw new IllegalStateException("Stale version or capability not found: " + capabilityId);
          return mapCapability(rows.iterator().next());
        });
  }

  public Future<CardSource> upsertSource(long capabilityId, String connectorName, Long originCardId,
                                         CardSource.Trust trust, int hop, int reshare) {
    long origin = originCardId != null ? originCardId : 0L;
    return pool.preparedQuery(
        "INSERT INTO csp_card_source (capability_id, connector_name, origin_card_id, trust, hop, reshare) "
            + "VALUES ($1, $2, $3, $4, $5, $6) "
            + "ON CONFLICT (capability_id, connector_name, origin_card_id) DO UPDATE "
            + "SET trust = $4, hop = $5, reshare = $6, updated_at = now() "
            + "RETURNING id, capability_id, connector_name, origin_card_id, trust, hop, reshare, updated_at")
        .execute(Tuple.of(capabilityId, connectorName, origin, trust.dbValue(), hop, reshare))
        .map(rows -> mapSource(rows.iterator().next()));
  }

  public Future<Void> removeSource(long capabilityId, String connectorName, Long originCardId) {
    long origin = originCardId != null ? originCardId : 0L;
    return pool.preparedQuery(
        "DELETE FROM csp_card_source WHERE capability_id = $1 AND connector_name = $2 AND origin_card_id = $3")
        .execute(Tuple.of(capabilityId, connectorName, origin))
        .map(rows -> null);
  }

  public Future<List<CardCapability>> listCapabilities(String profile) {
    return pool.preparedQuery(
        "SELECT id, profile, caid, provider, state, card_id, version, updated_at "
            + "FROM csp_card_capability WHERE profile = $1 ORDER BY caid, provider")
        .execute(Tuple.of(profile))
        .map(rows -> {
          List<CardCapability> list = new ArrayList<>();
          for (Row r : rows) list.add(mapCapability(r));
          return list;
        });
  }

  public Future<List<CardSource>> listSources(long capabilityId) {
    return pool.preparedQuery(
        "SELECT id, capability_id, connector_name, origin_card_id, trust, hop, reshare, updated_at "
            + "FROM csp_card_source WHERE capability_id = $1 ORDER BY hop, connector_name")
        .execute(Tuple.of(capabilityId))
        .map(rows -> {
          List<CardSource> list = new ArrayList<>();
          for (Row r : rows) list.add(mapSource(r));
          return list;
        });
  }

  private static CardCapability mapCapability(Row r) {
    var v = r.getOffsetDateTime("updated_at");
    Instant updatedAt = v != null ? v.toInstant() : Instant.now();
    return new CardCapability(
        r.getLong("id"),
        r.getString("profile"),
        r.getInteger("caid"),
        r.getInteger("provider"),
        CardCapability.State.fromDb(r.getString("state")),
        r.getLong("card_id"),
        r.getLong("version"),
        updatedAt
    );
  }

  private static CardSource mapSource(Row r) {
    var v = r.getOffsetDateTime("updated_at");
    Instant updatedAt = v != null ? v.toInstant() : Instant.now();
    return new CardSource(
        r.getLong("id"),
        r.getLong("capability_id"),
        r.getString("connector_name"),
        r.getLong("origin_card_id"),
        CardSource.Trust.fromDb(r.getString("trust")),
        r.getInteger("hop"),
        r.getInteger("reshare"),
        updatedAt
    );
  }
}
