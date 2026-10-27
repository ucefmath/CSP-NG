package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.List;

/**
 * Administration centrale relais (ADR 0002, W0-W3).
 * Meme conventions que {@code AdminConfigResource} : {@code @RolesAllowed("admin")},
 * CSRF {@code X-CSP-Requested-With} via filtre global, HTTPS via filtre global,
 * optimistic-locking (409), audit transactionnel cote repository, actor.
 */
@Path("/api/admin/config/relay")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminRelayResource {

  @Inject
  ConfigRepository configRepository;

  @Inject
  ConfigHolder configHolder;

  @Context
  SecurityContext securityContext;

  // -- Nodes --

  @GET
  @Path("/nodes")
  public Uni<Response> listNodes() {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    List<RelayNodeConfig> list = configHolder.get().relayNodes();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/nodes/{name}")
  public Uni<Response> upsertNode(@PathParam("name") String name, NodeDto dto) {
    if (dto == null || dto.version() < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
    if (!name.equals(dto.nodeName()))
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "path name must match body nodeName")).build());
    RelayNodeConfig cfg;
    try {
      // Gemini FIX : desired_version READONLY serveur (valeur cliente ignoree).
      cfg = new RelayNodeConfig(dto.nodeName(), dto.displayName(), dto.enabled(),
          dto.authorized(), dto.revoked(), 1L, null, dto.version(), null,
          dto.cspHost(), dto.cspPort());
    } catch (IllegalArgumentException e) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }
    if (cfg.enabled() && !cfg.authorized()) {
      // Activation sans autorisation explicite interdite (pas de pair inconnu auto-actif).
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "enabled node must be authorized explicitly")).build());
    }
    return Uni.createFrom().completionStage(
        configRepository.upsertRelayNode(cfg, actor()).toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  // -- Caches --

  @GET
  @Path("/caches")
  public Uni<Response> listCaches() {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    List<RelayCacheConfig> list = configHolder.get().relayCaches();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/caches/{name}")
  public Uni<Response> upsertCache(@PathParam("name") String name, CacheDto dto) {
    if (dto == null || dto.version() < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
    if (!name.equals(dto.name()))
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "path name must match body name")).build());
    RelayCacheConfig cfg;
    try {
      cfg = new RelayCacheConfig(dto.name(), dto.kind(), dto.nodeName(), dto.enabled(),
          dto.maxAgeMs(), dto.maxEntries(), dto.maxInflight(), dto.version());
    } catch (IllegalArgumentException e) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }
    return Uni.createFrom().completionStage(
        configRepository.upsertRelayCache(cfg, actor()).toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  // -- Links --

  @GET
  @Path("/links")
  public Uni<Response> listLinks() {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    List<ListenerCacheLinkConfig> list = configHolder.get().listenerCacheLinks();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/links/{listenerId}/{cacheName}")
  public Uni<Response> upsertLink(@PathParam("listenerId") long listenerId,
                                  @PathParam("cacheName") String cacheName, LinkDto dto) {
    if (dto == null || dto.version() < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
    if (dto.listenerId() != listenerId || !dto.cacheName().equals(cacheName))
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "path must match body")).build());
    ListenerCacheLinkConfig cfg;
    try {
      cfg = new ListenerCacheLinkConfig(dto.listenerId(), dto.cacheName(), dto.canConsult(),
          dto.canFeed(), dto.canExport(), dto.enabled(), dto.version());
    } catch (IllegalArgumentException e) {
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }
    // Consultation UDP : implementee cote worker (WorkerUdpExchange.consult,
    // pending par EcmKey, allowlist stricte, cache scoped). Le lien canConsult
    // sur cache kind=udp est donc accepte ; sans pair allowliste, la
    // consultation echoue fermee au worker (pas d'erreur de config ici).
    return Uni.createFrom().completionStage(
        configRepository.upsertListenerCacheLink(cfg, actor()).toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  @DELETE
  @Path("/links/{listenerId}/{cacheName}")
  public Uni<Response> deleteLink(@PathParam("listenerId") long listenerId,
                                  @PathParam("cacheName") String cacheName,
                                  @QueryParam("version") Long version) {
    if (version == null || version < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
    return Uni.createFrom().completionStage(
        configRepository.deleteListenerCacheLink(listenerId, cacheName, version, actor()).toCompletionStage())
        .map(v -> Response.noContent().build());
  }

  // -- Pairs UDP allowlist par cache (echange cache inter-noeuds) --

  @GET
  @Path("/caches/{name}/peers")
  public Uni<Response> listUdpPeers(@PathParam("name") String name) {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    var list = configHolder.get().udpPeers().stream()
        .filter(p -> p.cacheName().equals(name)).toList();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/caches/{name}/peers")
  public Uni<Response> upsertUdpPeer(@PathParam("name") String name, PeerDto dto) {
    if (dto == null)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "peer body required")).build());
    return Uni.createFrom().completionStage(
        configRepository.upsertUdpPeer(name, dto.host(), dto.port(), dto.enabled(), actor())
            .toCompletionStage())
        .map(id -> Response.ok(new PeerIdResponse(id)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  @DELETE
  @Path("/peers/{id}")
  public Uni<Response> deleteUdpPeer(@PathParam("id") long id) {
    return Uni.createFrom().completionStage(
        configRepository.deleteUdpPeer(id, actor()).toCompletionStage())
        .map(v -> Response.noContent().build())
        .onFailure(com.bowman.cspng.config.ConfigNotFoundException.class).recoverWithItem(e ->
            Response.status(404).entity(ErrorResponse.of("NOT_FOUND", e.getMessage())).build());
  }

  public record PeerIdResponse(long id) {}

  // -- Affectations explicites par worker (Gemini FIX) --

  @GET
  @Path("/nodes/{name}/listeners")
  public Uni<Response> listNodeListeners(@PathParam("name") String name) {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    var list = configHolder.get().nodeListeners().stream()
        .filter(a -> a.nodeName().equals(name)).toList();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/nodes/{name}/listeners/{listenerId}")
  public Uni<Response> upsertNodeListener(@PathParam("name") String name,
                                          @PathParam("listenerId") long listenerId,
                                          AssignmentDto dto) {
    if (dto == null || dto.version() < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
    return Uni.createFrom().completionStage(
        configRepository.upsertNodeListener(name, listenerId, dto.enabled(), dto.version(), actor())
            .toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  @DELETE
  @Path("/nodes/{name}/listeners/{listenerId}")
  public Uni<Response> deleteNodeListener(@PathParam("name") String name,
                                          @PathParam("listenerId") long listenerId,
                                          @QueryParam("version") Long version) {
    if (version == null || version < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
    return Uni.createFrom().completionStage(
        configRepository.deleteNodeListener(name, listenerId, version, actor()).toCompletionStage())
        .map(v -> Response.noContent().build());
  }

  @GET
  @Path("/nodes/{name}/connectors")
  public Uni<Response> listNodeConnectors(@PathParam("name") String name) {
    if (!configHolder.isInitialized())
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    var list = configHolder.get().nodeConnectors().stream()
        .filter(a -> a.nodeName().equals(name)).toList();
    return Uni.createFrom().item(Response.ok(list).build());
  }

  @PUT
  @Path("/nodes/{name}/connectors/{connector}")
  public Uni<Response> upsertNodeConnector(@PathParam("name") String name,
                                           @PathParam("connector") String connector,
                                           AssignmentDto dto) {
    if (dto == null || dto.version() < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version must be >= 1")).build());
    return Uni.createFrom().completionStage(
        configRepository.upsertNodeConnector(name, connector, dto.enabled(), dto.version(), actor())
            .toCompletionStage())
        .map(nv -> Response.ok(new VersionResponse(nv)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
  }

  @DELETE
  @Path("/nodes/{name}/connectors/{connector}")
  public Uni<Response> deleteNodeConnector(@PathParam("name") String name,
                                           @PathParam("connector") String connector,
                                           @QueryParam("version") Long version) {
    if (version == null || version < 1)
      return Uni.createFrom().item(Response.status(400)
          .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
    return Uni.createFrom().completionStage(
        configRepository.deleteNodeConnector(name, connector, version, actor()).toCompletionStage())
        .map(v -> Response.noContent().build());
  }

  private String actor() {
    Principal p = securityContext != null ? securityContext.getUserPrincipal() : null;
    return p != null ? p.getName() : "unknown";
  }

  public record NodeDto(String nodeName, String displayName, boolean enabled, boolean authorized,
                        boolean revoked, long version, String cspHost, Integer cspPort) {
    /** Compat sans endpoint CSPv2. */
    public NodeDto(String nodeName, String displayName, boolean enabled, boolean authorized,
                   boolean revoked, long version) {
      this(nodeName, displayName, enabled, authorized, revoked, version, null, null);
    }
  }

  public record PeerDto(String host, int port, boolean enabled) {}
  public record AssignmentDto(boolean enabled, long version) {}
  public record CacheDto(String name, String kind, String nodeName, boolean enabled,
                         long maxAgeMs, int maxEntries, int maxInflight, long version) {}
  public record LinkDto(long listenerId, String cacheName, boolean canConsult, boolean canFeed,
                        boolean canExport, boolean enabled, long version) {}
  public record VersionResponse(long version) {}
}
