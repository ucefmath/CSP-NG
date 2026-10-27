package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.DcwConfigRules;
import com.bowman.cspng.config.DcwPolicyConfig;
import com.bowman.cspng.config.DcwRuleConfig;
import com.bowman.cspng.config.DcwServiceMappingConfig;
import com.bowman.cspng.domain.dcw.DcwDigest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.web.admin.dto.*;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * API admin qualité DCW : policy, règles scopées, mappings de service approuvés, observations.
 * Mêmes gardes que /api/admin (rôle admin, HTTPS/CSRF via filtres globaux). Toutes les entrées
 * sont validées AVANT tout accès DB (400, sans refléter de CW ni de digest). Les mutations sont
 * transactionnelles côté repository (validation policy complète, audit, bump desired workers).
 */
@Path("/api/admin/dcw")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminDcwResource {

  private static final HexFormat HF = HexFormat.of();
  static final int MAX_LIST = 1000;

  @Inject
  ConfigRepository configRepository;

  @Inject
  com.bowman.cspng.filters.DcwObservationTracker observationTracker;

  @Inject
  com.bowman.cspng.filters.InterProviderLinkObserver linkObserver;

  @Inject
  com.bowman.cspng.connectors.routing.AdaptiveRoutingEngine adaptiveRoutingEngine;

  @Inject
  com.bowman.cspng.filters.CwCycleChecker cwCycleChecker;

  @Inject
  com.fasterxml.jackson.databind.ObjectMapper mapper;

  @Context
  SecurityContext securityContext;

  private String currentUsername() {
    if (securityContext != null && securityContext.getUserPrincipal() != null) {
      return securityContext.getUserPrincipal().getName();
    }
    return "admin";
  }

  private static Uni<Response> bad(String code, String msg) {
    return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of(code, msg)).build());
  }

  private static String lower(String v) {
    return v == null ? null : v.trim().toLowerCase(Locale.ROOT);
  }

  // ==================== Policy ====================

  @GET
  @Path("/policy")
  public Uni<Response> getPolicy() {
    return Uni.createFrom().completionStage(configRepository.getDcwPolicy().toCompletionStage())
        .map(p -> Response.ok(toResponse(p)).build());
  }

  @PUT
  @Path("/policy")
  public Uni<Response> updatePolicy(UpdateDcwPolicyRequest req) {
    if (req == null || req.version() == null || req.version() < 1) return bad("INVALID_INPUT", "version is required and must be >= 1");
    if (req.enabled() == null || req.adaptiveRoutingEnabled() == null || req.autoObserveEnabled() == null
        || req.zeroCountingEnabled() == null || req.allowZeroHalf() == null
        || req.defaultChecksumMode() == null || req.repairMode() == null) {
      return bad("INVALID_INPUT", "all policy fields are required (full PUT)");
    }
    String dcm = lower(req.defaultChecksumMode());
    String rm = lower(req.repairMode());
    List<String> errors = DcwConfigRules.validatePolicyHeader(dcm, rm);
    if (!errors.isEmpty()) return bad("INVALID_INPUT", String.join("; ", errors));
    return Uni.createFrom().completionStage(configRepository.saveDcwPolicy(req.enabled(), req.adaptiveRoutingEnabled(),
            req.autoObserveEnabled(), req.zeroCountingEnabled(), dcm, rm, req.allowZeroHalf(), req.version(),
            currentUsername()).toCompletionStage())
        .map(v -> Response.ok().build());
  }

  // ==================== Rules ====================

  @GET
  @Path("/rules")
  public Uni<Response> listRules() {
    return Uni.createFrom().completionStage(configRepository.listDcwRules().toCompletionStage())
        .map(list -> Response.ok(list.stream().limit(MAX_LIST).map(AdminDcwResource::toRuleResponse).toList()).build());
  }

  /** Construit + valide une règle depuis la requête ; IAE (message sûr) si invalide. */
  static DcwRuleConfig buildRule(long id, String profile, Integer caid, Integer provider, Integer sid, String sourceId,
                                 String digestSha256, String badDcwHex, String action, String checksumMode,
                                 String notes, Boolean enabled) {
    String digest = resolveDigest(digestSha256, badDcwHex);
    DcwRuleConfig r = new DcwRuleConfig(id, profile, caid != null ? caid : 0, provider, sid != null ? sid : 0, sourceId,
        digest, lower(action), lower(checksumMode), notes, enabled == null || enabled, 1L, 0L);
    List<String> errors = DcwConfigRules.validateRule(r);
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    return r;
  }

  @POST
  @Path("/rules")
  public Uni<Response> createRule(CreateDcwRuleRequest req) {
    if (req == null) return bad("INVALID_INPUT", "Request body is required");
    DcwRuleConfig rule;
    try {
      rule = buildRule(0L, req.profile(), req.caid(), req.provider(), req.sid(), req.sourceId(), req.digestSha256(),
          req.badDcwHex(), req.action(), req.checksumMode(), req.notes(), req.enabled());
    } catch (IllegalArgumentException e) {
      return bad("INVALID_INPUT", e.getMessage());
    }
    return Uni.createFrom().completionStage(configRepository.createDcwRule(rule, currentUsername()).toCompletionStage())
        .map(id -> Response.status(201).entity(toRuleResponse(new DcwRuleConfig(id, rule.profile(), rule.caid(),
            rule.provider(), rule.sid(), rule.sourceId(), rule.digestSha256(), rule.action(), rule.checksumMode(),
            rule.notes(), rule.enabled(), 1L, System.currentTimeMillis()))).build());
  }

  @PUT
  @Path("/rules/{id}")
  public Uni<Response> updateRule(@PathParam("id") long id, UpdateDcwRuleRequest req) {
    if (req == null || req.version() == null || req.version() < 1) return bad("INVALID_INPUT", "version is required and must be >= 1");
    DcwRuleConfig rule;
    try {
      rule = buildRule(id, req.profile(), req.caid(), req.provider(), req.sid(), req.sourceId(), req.digestSha256(),
          req.badDcwHex(), req.action(), req.checksumMode(), req.notes(), req.enabled());
    } catch (IllegalArgumentException e) {
      return bad("INVALID_INPUT", e.getMessage());
    }
    return Uni.createFrom().completionStage(configRepository.updateDcwRule(id, rule, req.version(), currentUsername()).toCompletionStage())
        .map(v -> Response.ok().build());
  }

  @DELETE
  @Path("/rules/{id}")
  public Uni<Response> deleteRule(@PathParam("id") long id) {
    return Uni.createFrom().completionStage(configRepository.deleteDcwRule(id, currentUsername()).toCompletionStage())
        .map(v -> Response.noContent().build());
  }

  // ==================== Service mappings (même profil/CAID/provider) ====================

  @GET
  @Path("/mappings")
  public Uni<Response> listMappings() {
    return Uni.createFrom().completionStage(configRepository.listDcwServiceMappings().toCompletionStage())
        .map(list -> Response.ok(list.stream().limit(MAX_LIST).map(AdminDcwResource::toMappingResponse).toList()).build());
  }

  static DcwServiceMappingConfig buildMapping(long id, String profile, Integer caid, Integer provider, Integer sourceSid,
                                             Integer targetSid, Boolean approved, Boolean enabled, Long expiresAtMs) {
    if (caid == null || provider == null || sourceSid == null || targetSid == null || approved == null) {
      throw new IllegalArgumentException("profile, caid, provider, sourceSid, targetSid, approved are required");
    }
    DcwServiceMappingConfig m = new DcwServiceMappingConfig(id, profile, caid, provider, sourceSid, targetSid, approved,
        enabled == null || enabled, expiresAtMs != null ? expiresAtMs : 0L, 1L, 0L);
    List<String> errors = DcwConfigRules.validateMapping(m);
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
    return m;
  }

  @POST
  @Path("/mappings")
  public Uni<Response> createMapping(CreateDcwServiceMappingRequest req) {
    if (req == null) return bad("INVALID_INPUT", "Request body is required");
    DcwServiceMappingConfig m;
    try {
      m = buildMapping(0L, req.profile(), req.caid(), req.provider(), req.sourceSid(), req.targetSid(), req.approved(),
          req.enabled(), req.expiresAtMs());
    } catch (IllegalArgumentException e) {
      return bad("INVALID_INPUT", e.getMessage());
    }
    return Uni.createFrom().completionStage(configRepository.createDcwServiceMapping(m, currentUsername()).toCompletionStage())
        .map(id -> Response.status(201).entity(toMappingResponse(new DcwServiceMappingConfig(id, m.profile(), m.caid(),
            m.provider(), m.sourceSid(), m.targetSid(), m.approved(), m.enabled(), m.expiresAtMs(), 1L,
            System.currentTimeMillis()))).build());
  }

  @PUT
  @Path("/mappings/{id}")
  public Uni<Response> updateMapping(@PathParam("id") long id, UpdateDcwServiceMappingRequest req) {
    if (req == null || req.version() == null || req.version() < 1) return bad("INVALID_INPUT", "version is required and must be >= 1");
    DcwServiceMappingConfig m;
    try {
      m = buildMapping(id, req.profile(), req.caid(), req.provider(), req.sourceSid(), req.targetSid(), req.approved(),
          req.enabled(), req.expiresAtMs());
    } catch (IllegalArgumentException e) {
      return bad("INVALID_INPUT", e.getMessage());
    }
    return Uni.createFrom().completionStage(configRepository.updateDcwServiceMapping(id, m, req.version(), currentUsername()).toCompletionStage())
        .map(v -> Response.ok().build());
  }

  @DELETE
  @Path("/mappings/{id}")
  public Uni<Response> deleteMapping(@PathParam("id") long id) {
    return Uni.createFrom().completionStage(configRepository.deleteDcwServiceMapping(id, currentUsername()).toCompletionStage())
        .map(v -> Response.noContent().build());
  }

  // ==================== Observations (lecture seule, bornées, sans CW) ====================

  /**
   * Compteurs réels des observateurs centraux. Les scores de routage adaptatif ne sont PAS
   * exposés ici : l'état adaptatif est encore local à chaque routeur/session (checkpoint suivant).
   */
  @GET
  @Path("/stats")
  public Uni<Response> getStats() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("anomalies", observationTracker != null ? observationTracker.recentAnomalies().size() : 0);
    m.put("candidateLinks", linkObserver != null ? linkObserver.candidates().size() : 0);
    m.put("maxRules", DcwQualityPolicy.MAX_RULES);
    m.put("maxMappings", DcwQualityPolicy.MAX_MAPPINGS);
    m.put("adaptiveScoresExposed", adaptiveRoutingEngine != null);
    if (adaptiveRoutingEngine != null) m.put("routing", adaptiveRoutingEngine.getStatsView());
    // CW-cycle (contrat v2 §5) — compteurs réels du checker (bean singleton).
    if (cwCycleChecker != null) {
      Map<String, Object> cw = new LinkedHashMap<>();
      cw.put("enabled", cwCycleChecker.enabled());
      cw.put("trackedEntries", cwCycleChecker.trackedEntries());
      cw.put("checks", cwCycleChecker.checks());
      cw.put("badCycles", cwCycleChecker.badCycles());
      cw.put("dropped", cwCycleChecker.droppedCount());
      cw.put("logged", cwCycleChecker.loggedCount());
      cw.put("bypassed", cwCycleChecker.ignoredCount());
      m.put("cwCycle", cw);
    } else {
      m.put("cwCycle", null);
    }
    // CW-cycle workers (B1) : compteurs réels remontés, par node ; absent/illisible
    // => entrée absente (jamais de zéros fictifs). Même pattern que le merge cacheex.
    // Chaîné en non-bloquant (jamais de .get() sur l'event-loop) et tolérant aux
    // repos factices (pool null en tests unitaires => objet vide, pas de NPE synchrone).
    Uni<java.util.Map<String, String>> statsUni;
    try {
      if (configRepository == null || mapper == null) throw new IllegalStateException("no stats backend");
      statsUni = Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage());
    } catch (RuntimeException e) {
      statsUni = Uni.createFrom().failure(e);
    }
    return statsUni
        .onItem().transform(nodeStats -> {
          Map<String, Object> cwWorkers = new LinkedHashMap<>();
          if (nodeStats != null) {
            for (var e : nodeStats.entrySet()) {
              try {
                var root = mapper.readTree(e.getValue());
                var cw = root.path("cwCycle");
                if (cw.isMissingNode() || cw.isNull()) continue;
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("enabled", cw.path("enabled").asBoolean(false));
                one.put("trackedEntries", cw.path("trackedEntries").asInt(0));
                one.put("checks", cw.path("checks").asLong(0));
                one.put("badCycles", cw.path("badCycles").asLong(0));
                one.put("dropped", cw.path("dropped").asLong(0));
                one.put("logged", cw.path("logged").asLong(0));
                one.put("bypassed", cw.path("bypassed").asLong(0));
                cwWorkers.put(e.getKey(), one);
              } catch (Exception ignored) {
                // payload illisible => pas d'entrée (honnête)
              }
            }
          }
          m.put("cwCycleWorkers", cwWorkers);
          return Response.ok(m).build();
        })
        .onFailure().recoverWithItem(t -> {
          m.put("cwCycleWorkers", Map.of());
          return Response.ok(m).build();
        });
  }

  @GET
  @Path("/anomalies")
  public Uni<Response> getAnomalies() {
    var list = observationTracker != null ? observationTracker.recentAnomalies() : List.<com.bowman.cspng.filters.DcwObservationTracker.AnomalyEvent>of();
    return Uni.createFrom().item(Response.ok(list.stream().limit(MAX_LIST).toList()).build());
  }

  @GET
  @Path("/candidates")
  public Uni<Response> getCandidates() {
    List<DcwCandidateLinkResponse> out = new ArrayList<>();
    if (linkObserver != null) {
      for (var c : linkObserver.candidates()) {
        if (out.size() >= MAX_LIST) break;
        out.add(new DcwCandidateLinkResponse(c.scopeA().toString(), c.scopeB().toString(), c.distinctEvidenceCount(),
            c.firstObservedMs(), c.lastObservedMs(), c.state()));
      }
    }
    return Uni.createFrom().item(Response.ok(out).build());
  }

  // ==================== Helpers ====================

  /** Digest explicite ou CW 16 octets hex convertie en SHA-256 (jamais conservée ni reflétée). */
  static String resolveDigest(String digestSha256, String badDcwHex) {
    boolean hasDigest = digestSha256 != null && !digestSha256.isBlank();
    boolean hasHex = badDcwHex != null && !badDcwHex.isBlank();
    if (hasDigest && hasHex) throw new IllegalArgumentException("provide either digestSha256 or badDcwHex, not both");
    if (hasDigest) return DcwDigest.normalizeSha256Hex(digestSha256.trim());
    if (hasHex) {
      String clean = badDcwHex.replaceAll("[\\s:]", "");
      if (clean.length() != 32 || !clean.matches("[0-9a-fA-F]{32}")) {
        throw new IllegalArgumentException("badDcwHex must be 16 bytes hex (32 characters)");
      }
      byte[] cw = HF.parseHex(clean);
      try {
        return DcwDigest.digestSha256Hex(cw);
      } finally {
        java.util.Arrays.fill(cw, (byte) 0);
      }
    }
    return null;
  }

  private static DcwPolicyResponse toResponse(DcwPolicyConfig p) {
    var rules = p.rules().stream().limit(MAX_LIST).map(AdminDcwResource::toRuleResponse).toList();
    var maps = p.mappings().stream().limit(MAX_LIST).map(AdminDcwResource::toMappingResponse).toList();
    return new DcwPolicyResponse(p.enabled(), p.adaptiveRoutingEnabled(), p.autoObserveEnabled(), p.zeroCountingEnabled(),
        p.defaultChecksumMode(), p.repairMode(), p.allowZeroHalf(), p.rules().size(), rules, p.mappings().size(), maps,
        p.version(), p.updatedAtMs());
  }

  private static DcwServiceMappingResponse toMappingResponse(DcwServiceMappingConfig m) {
    return new DcwServiceMappingResponse(m.id(), m.profile(), m.caid(), m.provider(), m.sourceSid(), m.targetSid(),
        m.approved(), m.enabled(), m.expiresAtMs(), m.version(), m.updatedAtMs());
  }

  private static DcwRuleResponse toRuleResponse(DcwRuleConfig r) {
    return new DcwRuleResponse(r.id(), r.profile(), r.caid(), r.provider(), r.sid(), r.sourceId(), r.digestSha256(),
        r.action(), r.checksumMode(), r.notes(), r.enabled(), r.version(), r.updatedAtMs());
  }
}
