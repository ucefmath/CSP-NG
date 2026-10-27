package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.CacheExQuorumConfig;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.PeerQuarantineConfig;
import com.bowman.cspng.connectors.cacheex.CacheExQuorumTracker;
import com.bowman.cspng.connectors.cacheex.CacheHitAttribution;
import com.bowman.cspng.connectors.cacheex.PeerQualityTracker;
import com.bowman.cspng.connectors.cacheex.PeerQuarantine;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.bowman.cspng.web.admin.dto.ReleaseQuarantineRequest;
import com.bowman.cspng.web.admin.dto.UpdateQuarantineRequest;
import com.bowman.cspng.web.admin.dto.UpdateQuorumRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.resteasy.reactive.NoCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Qualité des pairs CacheEx/UDP (lots 2-5, révisions B1-B5, R1-R6) :
 * qualité fenêtrée, hits servis, quarantaine temporaire, quorum ciblé.
 *
 * <p>Mêmes gardes que /api/admin (rôle admin, HTTPS/CSRF via filtres globaux).
 * Compteurs réels uniquement (jamais de zéros fabriqués pour les pairs sans
 * trafic). Aucune CW ni secret n'est journalisé ou renvoyé.
 *
 * <p>Fusion centrale + workers (B5) : les métriques remontées par les workers
 * portent un {@code nodeId} explicite pour identifier clairement la provenance
 * sans confondre les identités ni deviner par parsing de slash.
 *
 * <p>Levée de quarantaine (R6 / B2) : levée centrale immédiate + audit
 * enregistré en base (actor, peerId, sans secret). La levée sur worker n'est
 * pas supportée (statut 422 documenté) : l'état mémoire des workers expire
 * naturellement.
 */
@Path("/api/admin/peer-quality")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@NoCache
public class AdminPeerQualityResource {

  private static final Logger LOG = Logger.getLogger(AdminPeerQualityResource.class.getName());

  @Inject
  PeerQualityTracker qualityTracker;

  @Inject
  CacheHitAttribution hitAttribution;

  @Inject
  PeerQuarantine quarantine;

  @Inject
  CacheExQuorumTracker quorumTracker;

  @Inject
  ConfigRepository configRepository;

  @Inject
  ConfigHolder configHolder;

  @Inject
  ObjectMapper mapper;

  @Context
  SecurityContext securityContext;

  private String actor() {
    if (securityContext != null && securityContext.getUserPrincipal() != null) {
      return securityContext.getUserPrincipal().getName();
    }
    return "admin";
  }

  // ==================== Qualité fenêtrée (lot 2 / B5) ====================

  @GET
  @Path("/quality")
  public Uni<Response> quality() {
    if (!configHolder.isInitialized()) {
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    }
    PeerQuarantineConfig cfg = currentQuarantine();
    List<QualityRow> rows = new ArrayList<>();
    for (var q : qualityTracker.snapshot()) {
      boolean quarantined = cfg.enabled() && "enforce".equalsIgnoreCase(cfg.mode())
          && quarantine.shouldSkip(q.peerId(), cfg);
      rows.add(new QualityRow("central", q.peerId(), q.accepted(), q.invalid(), q.total(),
          q.invalidRatioBps(), q.windowMs(), quarantined));
    }
    if (configRepository == null) {
      rows.sort(Comparator.comparing(QualityRow::peerId));
      return Uni.createFrom().item(Response.ok(new QualityResponse(
          System.currentTimeMillis(), qualityTracker.windowMs(), rows)).build());
    }
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(nodeStats -> {
          if (nodeStats != null) {
            for (var e : nodeStats.entrySet()) {
              rows.addAll(parseWorkerQuality(e.getKey(), e.getValue()));
            }
          }
          rows.sort(Comparator.comparing(QualityRow::peerId));
          return Response.ok(new QualityResponse(System.currentTimeMillis(), qualityTracker.windowMs(), rows)).build();
        })
        .onFailure().recoverWithItem(t -> {
          LOG.log(Level.FINE, "Worker nodeStats unavailable for quality merge", t);
          rows.sort(Comparator.comparing(QualityRow::peerId));
          return Response.ok(new QualityResponse(System.currentTimeMillis(), qualityTracker.windowMs(), rows)).build();
        });
  }

  List<QualityRow> parseWorkerQuality(String nodeName, String payloadJson) {
    List<QualityRow> out = new ArrayList<>();
    if (nodeName == null || payloadJson == null || payloadJson.isBlank()) return out;
    try {
      JsonNode root = mapper.readTree(payloadJson);
      JsonNode arr = root.path("peerQuality");
      if (!arr.isArray()) return out;
      int count = 0;
      for (JsonNode item : arr) {
        if (count++ >= 64) break;
        String peerId = item.path("peerId").asText(null);
        if (peerId == null || peerId.isBlank()) continue;
        out.add(new QualityRow(
            nodeName,
            peerId,
            Math.max(0, item.path("accepted").asLong(0)),
            Math.max(0, item.path("invalid").asLong(0)),
            Math.max(0, item.path("total").asLong(0)),
            Math.max(0, item.path("invalidRatioBps").asInt(0)),
            Math.max(0, item.path("windowMs").asLong(300_000L)),
            item.path("quarantined").asBoolean(false)));
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Unreadable worker peerQuality payload for " + nodeName, e);
    }
    return out;
  }

  // ==================== Hits servis (lot 3 / B5) ====================

  @GET
  @Path("/hits")
  public Uni<Response> hits() {
    List<HitEntryView> entries = new ArrayList<>();
    for (var h : hitAttribution.snapshot()) {
      entries.add(new HitEntryView("central", h.origin(), h.hits(), h.lastHitAtMs()));
    }
    if (configRepository == null) {
      entries.sort(Comparator.comparing(HitEntryView::origin));
      return Uni.createFrom().item(Response.ok(new HitsResponse(System.currentTimeMillis(), entries)).build());
    }
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(nodeStats -> {
          if (nodeStats != null) {
            for (var e : nodeStats.entrySet()) {
              entries.addAll(parseWorkerHits(e.getKey(), e.getValue()));
            }
          }
          entries.sort(Comparator.comparing(HitEntryView::origin));
          return Response.ok(new HitsResponse(System.currentTimeMillis(), entries)).build();
        })
        .onFailure().recoverWithItem(t -> {
          LOG.log(Level.FINE, "Worker nodeStats unavailable for hits merge", t);
          entries.sort(Comparator.comparing(HitEntryView::origin));
          return Response.ok(new HitsResponse(System.currentTimeMillis(), entries)).build();
        });
  }

  List<HitEntryView> parseWorkerHits(String nodeName, String payloadJson) {
    List<HitEntryView> out = new ArrayList<>();
    if (nodeName == null || payloadJson == null || payloadJson.isBlank()) return out;
    try {
      JsonNode root = mapper.readTree(payloadJson);
      JsonNode arr = root.path("cacheHits");
      if (!arr.isArray()) return out;
      int count = 0;
      for (JsonNode item : arr) {
        if (count++ >= 64) break;
        String origin = item.path("origin").asText(null);
        if (origin == null || origin.isBlank()) continue;
        Long lastHitAt = item.has("lastHitAtMs") && !item.path("lastHitAtMs").isNull()
            ? Math.max(0, item.path("lastHitAtMs").asLong()) : null;
        out.add(new HitEntryView(nodeName, origin, Math.max(0, item.path("hits").asLong(0)), lastHitAt));
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Unreadable worker cacheHits payload for " + nodeName, e);
    }
    return out;
  }

  // ==================== Quarantaine (lot 4 / B5 / R6) ====================

  @GET
  @Path("/quarantine")
  public Uni<Response> getQuarantine() {
    if (!configHolder.isInitialized()) {
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    }
    PeerQuarantineConfig cfg = currentQuarantine();
    List<QuarantineEntryView> entries = new ArrayList<>();
    for (var q : quarantine.snapshot()) {
      entries.add(new QuarantineEntryView("central", q.peerId(), q.reason(),
          q.quarantinedAtMs(), q.expiresAtMs(), q.observedOnly()));
    }
    long observed = quarantine.observedCount();
    long enforced = quarantine.enforcedCount();
    if (configRepository == null) {
      entries.sort(Comparator.comparing(QuarantineEntryView::peerId));
      return Uni.createFrom().item(Response.ok(new QuarantineResponse(cfg, entries, observed, enforced)).build());
    }
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(nodeStats -> {
          if (nodeStats != null) {
            for (var e : nodeStats.entrySet()) {
              entries.addAll(parseWorkerQuarantine(e.getKey(), e.getValue()));
            }
          }
          entries.sort(Comparator.comparing(QuarantineEntryView::peerId));
          return Response.ok(new QuarantineResponse(cfg, entries, observed, enforced)).build();
        })
        .onFailure().recoverWithItem(t -> {
          LOG.log(Level.FINE, "Worker nodeStats unavailable for quarantine merge", t);
          entries.sort(Comparator.comparing(QuarantineEntryView::peerId));
          return Response.ok(new QuarantineResponse(cfg, entries, observed, enforced)).build();
        });
  }

  List<QuarantineEntryView> parseWorkerQuarantine(String nodeName, String payloadJson) {
    List<QuarantineEntryView> out = new ArrayList<>();
    if (nodeName == null || payloadJson == null || payloadJson.isBlank()) return out;
    try {
      JsonNode root = mapper.readTree(payloadJson);
      JsonNode arr = root.path("quarantine");
      if (!arr.isArray()) return out;
      int count = 0;
      for (JsonNode item : arr) {
        if (count++ >= 64) break;
        String peerId = item.path("peerId").asText(null);
        if (peerId == null || peerId.isBlank()) continue;
        out.add(new QuarantineEntryView(
            nodeName,
            peerId,
            item.path("reason").asText(""),
            Math.max(0, item.path("quarantinedAtMs").asLong(0)),
            Math.max(0, item.path("expiresAtMs").asLong(0)),
            item.path("observedOnly").asBoolean(false)));
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Unreadable worker quarantine payload for " + nodeName, e);
    }
    return out;
  }

  @PUT
  @Path("/quarantine")
  public Uni<Response> updateQuarantine(@Valid UpdateQuarantineRequest req) {
    if (req.version() == null || req.version() < 1) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
    }
    PeerQuarantineConfig cfg;
    try {
      cfg = new PeerQuarantineConfig(
          req.enabled() != null && req.enabled(),
          req.mode() != null ? req.mode() : PeerQuarantineConfig.MODE_OBSERVE,
          req.minSamples() != null ? req.minSamples() : PeerQuarantineConfig.DEFAULT.minSamples(),
          req.invalidRatioBps() != null ? req.invalidRatioBps() : PeerQuarantineConfig.DEFAULT.invalidRatioBps(),
          req.windowMs() != null ? req.windowMs() : PeerQuarantineConfig.DEFAULT.windowMs(),
          req.durationMs() != null ? req.durationMs() : PeerQuarantineConfig.DEFAULT.durationMs(),
          req.maxPeers() != null ? req.maxPeers() : PeerQuarantineConfig.DEFAULT.maxPeers(),
          req.version());
    } catch (IllegalArgumentException e) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }
    return Uni.createFrom().completionStage(configRepository.updatePeerQuarantine(cfg, actor()).toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  @POST
  @Path("/quarantine/release")
  public Uni<Response> releaseQuarantine(@Valid ReleaseQuarantineRequest req) {
    if (req == null || req.peerId() == null || req.peerId().isBlank()) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "peerId required")).build());
    }
    String peerId = req.peerId().trim();
    if ((req.nodeId() != null && !req.nodeId().isBlank() && !"central".equalsIgnoreCase(req.nodeId().trim()))
        || peerId.contains("/")) {
      return Uni.createFrom().item(Response.status(422)
          .entity(ErrorResponse.of("WORKER_SCOPE_NOT_SUPPORTED",
              "La levée manuelle s'applique uniquement au nœud central ; la quarantaine des workers expire automatiquement en mémoire.")).build());
    }
    // Si le pair n'est pas dans la quarantaine centrale active, ne pas enregistrer d'audit mensonger (action RELEASED)
    if (!quarantine.hasLiveEntry(peerId)) {
      return Uni.createFrom().item(Response.ok(new ReleaseResponse(false, peerId,
          "Pair non trouvé dans la quarantaine centrale active.")).build());
    }
    String act = actor();
    // MF1 : L'audit persistant en BD doit SUCCÉDER AVANT que l'état mémoire ne soit modifié.
    // En cas d'échec BD, retourner 500 et conserver l'état de quarantaine intact (shouldSkip reste true).
    if (configRepository == null) {
      boolean released = quarantine.release(peerId);
      return Uni.createFrom().item(Response.ok(new ReleaseResponse(released, peerId,
          released ? "Levée centrale effectuée." : "Pair non trouvé dans la quarantaine centrale active.")).build());
    }
    return Uni.createFrom().completionStage(
        configRepository.recordQuarantineReleaseAudit(peerId, act).toCompletionStage()
    ).map(v -> {
        boolean released = quarantine.release(peerId);
        return Response.ok(new ReleaseResponse(released, peerId,
            released ? "Quarantaine centrale levée ; audit enregistré."
                     : "Pair non trouvé dans la quarantaine centrale active.")).build();
    }).onFailure().recoverWithItem(t -> {
        LOG.log(Level.SEVERE, "Échec d'enregistrement de l'audit de levée de quarantaine pour " + peerId, t);
        return Response.status(500).entity(ErrorResponse.of("AUDIT_FAILED",
            "Échec d'enregistrement de l'audit de levée : " + t.getMessage())).build();
    });
  }

  // ==================== Quorum (lot 5 / B5 / R4) ====================

  @GET
  @Path("/quorum")
  public Uni<Response> getQuorum() {
    if (!configHolder.isInitialized()) {
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    }
    CacheExQuorumConfig cfg = currentQuorum();
    long liveWindowMs = cfg != null ? cfg.windowMs() : CacheExQuorumConfig.DEFAULT.windowMs();
    var centralStats = quorumTracker.stats();
    var keys = quorumTracker.keySnapshot(64, liveWindowMs);

    if (configRepository == null) {
      return Uni.createFrom().item(Response.ok(new QuorumResponse(cfg, centralStats, keys)).build());
    }
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(nodeStats -> {
          long obs = centralStats.observed();
          long qrm = centralStats.quorum();
          long pnd = centralStats.pending();
          long mis = centralStats.mismatch();
          long exp = centralStats.expired();
          int kcount = centralStats.keys();
          if (nodeStats != null) {
            for (var entry : nodeStats.entrySet()) {
              var wq = parseWorkerQuorum(entry.getValue());
              if (wq != null) {
                obs += wq.observed();
                qrm += wq.quorum();
                pnd += wq.pending();
                mis += wq.mismatch();
                exp += wq.expired();
                kcount += wq.keys();
              }
            }
          }
          var aggregated = new CacheExQuorumTracker.QuorumStats(kcount, obs, qrm, pnd, mis, exp);
          return Response.ok(new QuorumResponse(cfg, aggregated, keys)).build();
        })
        .onFailure().recoverWithItem(t -> {
          LOG.log(Level.FINE, "Worker nodeStats unavailable for quorum merge", t);
          return Response.ok(new QuorumResponse(cfg, centralStats, keys)).build();
        });
  }

  CacheExQuorumTracker.QuorumStats parseWorkerQuorum(String payloadJson) {
    if (payloadJson == null || payloadJson.isBlank()) return null;
    try {
      JsonNode root = mapper.readTree(payloadJson);
      JsonNode q = root.path("quorum");
      if (q.isNull() || q.isMissingNode() || !q.isObject()) return null;
      return new CacheExQuorumTracker.QuorumStats(
          Math.max(0, q.path("keys").asInt(0)),
          Math.max(0, q.path("observed").asLong(0)),
          Math.max(0, q.path("quorum").asLong(0)),
          Math.max(0, q.path("pending").asLong(0)),
          Math.max(0, q.path("mismatch").asLong(0)),
          Math.max(0, q.path("expired").asLong(0)));
    } catch (Exception e) {
      return null;
    }
  }

  @PUT
  @Path("/quorum")
  public Uni<Response> updateQuorum(@Valid UpdateQuorumRequest req) {
    if (req.version() == null || req.version() < 1) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1")).build());
    }
    CacheExQuorumConfig cfg;
    try {
      cfg = new CacheExQuorumConfig(
          req.mode() != null ? req.mode() : CacheExQuorumConfig.MODE_OFF,
          req.minOrigins() != null ? req.minOrigins() : 2,
          req.windowMs() != null ? req.windowMs() : 30_000L,
          req.maxKeys() != null ? req.maxKeys() : 1_024,
          req.profiles() != null ? req.profiles() : List.of(),
          req.caids() != null ? req.caids() : List.of(),
          req.version());
    } catch (IllegalArgumentException e) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }
    return Uni.createFrom().completionStage(configRepository.updateCacheExQuorum(cfg, actor()).toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  private PeerQuarantineConfig currentQuarantine() {
    var snap = configHolder.get();
    return snap != null && snap.quarantine() != null ? snap.quarantine() : PeerQuarantineConfig.DEFAULT;
  }

  private CacheExQuorumConfig currentQuorum() {
    var snap = configHolder.get();
    return snap != null && snap.quorum() != null ? snap.quorum() : CacheExQuorumConfig.DEFAULT;
  }

  public record QualityRow(String nodeId, String peerId, long accepted, long invalid, long total,
                           int invalidRatioBps, long windowMs, boolean quarantined) {}
  public record QualityResponse(long generatedAtMs, long windowMs, List<QualityRow> peers) {}
  public record HitEntryView(String nodeId, String origin, long hits, Long lastHitAtMs) {}
  public record HitsResponse(long generatedAtMs, List<HitEntryView> origins) {}
  public record QuarantineEntryView(String nodeId, String peerId, String reason,
                                    long quarantinedAtMs, long expiresAtMs, boolean observedOnly) {}
  public record QuarantineResponse(PeerQuarantineConfig config,
                                   List<QuarantineEntryView> entries,
                                   long observed, long enforced) {}
  public record QuorumResponse(CacheExQuorumConfig config,
                               CacheExQuorumTracker.QuorumStats stats,
                               List<CacheExQuorumTracker.QuorumKeyView> keys) {}
  public record ReleaseResponse(boolean released, String peerId, String note) {}
  public record VersionResponse(long version) {}
}
