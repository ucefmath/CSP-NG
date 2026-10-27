package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigValidationException;
import com.bowman.cspng.config.NetworkProxyConfig;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
import java.util.Map;

/**
 * REST CRUD pour les proxys SOCKS5 ({@code csp_proxy}) — Plan P2.
 *
 * <p>Conventions :
 * <ul>
 *   <li>{@code @RolesAllowed("admin")} — mêmes filtres HTTPS/CSRF que le reste de /api/admin.</li>
 *   <li>Upsert : {@code version=1} = création (absent requis), sinon mise à jour
 *       optimistic-lockée. 404 absent, 409 mismatch — via mappers existants.</li>
 *   <li>DELETE : 409 {@code PROXY_IN_USE} si le proxy est référencé par un connecteur
 *       (le FK ON DELETE RESTRICT reste le filet de sécurité SQL).</li>
 *   <li>{@code GET /usage/{name}} : compteur de connecteurs attachés (pour l'UI).</li>
 *   <li>Secrets : seule la <em>référence</em> ({@code passwordRef} {@code env:} ou
 *       {@code file:}) transite en JSON — jamais de mot de passe en clair.</li>
 * </ul>
 */
@Path("/api/admin/config/proxies")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminProxyResource {

    @Inject
    ConfigRepository configRepository;

    @Inject
    ConfigHolder configHolder;

    @Context
    SecurityContext securityContext;

    /** Liste des proxys avec compteur d'usage par connecteur. */
    @GET
    public Uni<Response> listProxies() {
        return Uni.createFrom().completionStage(configRepository.loadAllProxies().toCompletionStage())
            .flatMap(proxies -> {
                if (proxies.isEmpty()) {
                    return Uni.createFrom().item(Response.ok(List.of()).build());
                }
                // gather usage counts
                List<Uni<Long>> unis = proxies.stream()
                    .map(p -> Uni.createFrom().completionStage(
                        configRepository.countProxyUsage(p.name()).toCompletionStage()))
                    .toList();
                return Uni.combine().all().unis(unis).combinedWith(results -> {
                    @SuppressWarnings("unchecked")
                    List<Long> counts = (List<Long>) results;
                    var out = new java.util.ArrayList<Map<String, Object>>(proxies.size());
                    for (int i = 0; i < proxies.size(); i++) {
                        NetworkProxyConfig p = proxies.get(i);
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("name", p.name());
                        m.put("enabled", p.enabled());
                        m.put("type", p.type());
                        m.put("host", p.host());
                        m.put("port", p.port());
                        m.put("username", p.username() != null ? p.username() : "");
                        m.put("passwordRef", p.passwordRef() != null ? p.passwordRef() : "");
                        m.put("description", p.description() != null ? p.description() : "");
                        m.put("version", p.version());
                        m.put("connectorCount", counts.get(i));
                        out.add(m);
                    }
                    return Response.ok(out).build();
                });
            })
            .onFailure().recoverWithItem(e ->
                // Message FIXE — ne JAMAIS exposer de détail d'exception (potentiellement secrets/SQL)
                Response.status(503).entity(ErrorResponse.of("NOT_READY",
                    "configuration store temporarily unavailable")).build());
    }

    /** Détail d'un proxy. */
    @GET
    @Path("/{name}")
    public Uni<Response> getProxy(@PathParam("name") String name) {
        if (!configHolder.isInitialized()) {
            return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
        }
        NetworkProxyConfig p = configHolder.get().proxyByName().get(name);
        if (p == null) {
            return Uni.createFrom().item(Response.status(404).entity(ErrorResponse.of("NOT_FOUND", "Proxy not found")).build());
        }
        return Uni.createFrom().completionStage(configRepository.countProxyUsage(name).toCompletionStage())
            .map(cnt -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("name", p.name());
                m.put("enabled", p.enabled());
                m.put("type", p.type());
                m.put("host", p.host());
                m.put("port", p.port());
                m.put("username", p.username() != null ? p.username() : "");
                m.put("passwordRef", p.passwordRef() != null ? p.passwordRef() : "");
                m.put("description", p.description() != null ? p.description() : "");
                m.put("version", p.version());
                m.put("connectorCount", cnt);
                return Response.ok(m).build();
            });
    }

    /** Création OU mise à jour (upsert optimistic-locké). version=1 ⇒ création. */
    @PUT
    @Path("/{name}")
    public Uni<Response> upsertProxy(@PathParam("name") @Pattern(regexp = "[A-Za-z0-9_.\\-]{1,64}") String name,
                                     @Valid ProxyDto dto) {
        if (!name.equals(dto.name())) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "Path name and body name must match (proxy name is immutable)")).build());
        }
        if (dto.version() < 1) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "version must be >=1 (1 = create)")).build());
        }
        // Auth appariée : username+passwordRef ensemble ou aucun
        boolean hasUser = dto.username() != null && !dto.username().isBlank();
        boolean hasPassRef = dto.passwordRef() != null && !dto.passwordRef().isBlank();
        if (hasUser != hasPassRef) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT",
                    "username and passwordRef must be provided together (both null = no auth)")).build());
        }
        // passwordRef : validation déléguée au record NetworkProxyConfig
        // (mécanisme ConfigValidator.isValidSecretRef — env:/file: only, file:../ rejeté) ;
        // toute IllegalArgumentException → 400 INVALID_INPUT ci-dessous.
        NetworkProxyConfig cfg;
        try {
            // PAS de timeout sur le proxy (Plan P2) : le budget T est porté par
            // le connecteur (csp_connector.connect_timeout_ms).
            cfg = new NetworkProxyConfig(
                dto.name(), dto.enabled(), "socks5", dto.host(), dto.port(),
                hasUser ? dto.username() : null,
                hasPassRef ? dto.passwordRef() : null,
                dto.description(), dto.version());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }
        return Uni.createFrom().completionStage(configRepository.upsertProxy(cfg, actor()).toCompletionStage())
            .map(newVersion -> Response.ok(new VersionResponse(newVersion)).build())
            .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    /** Suppression — 409 PROXY_IN_USE si référencé, 404 absent, 409 version mismatch. */
    @DELETE
    @Path("/{name}")
    public Uni<Response> deleteProxy(@PathParam("name") String name,
                                     @QueryParam("version") Long version) {
        if (version == null || version < 1) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter must be >=1")).build());
        }
        return Uni.createFrom().completionStage(configRepository.deleteProxy(name, version, actor()).toCompletionStage())
            .map(v -> Response.noContent().build())
            .onFailure(ConfigValidationException.class).recoverWithItem(e -> {
                if (e.getMessage() != null && e.getMessage().startsWith("PROXY_IN_USE")) {
                    return Response.status(409).entity(ErrorResponse.of("PROXY_IN_USE", e.getMessage())).build();
                }
                return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build();
            });
    }

    /** Compteur d'usage : nombre de connecteurs référençant ce proxy (404 si absent). */
    @GET
    @Path("/usage/{name}")
    public Uni<Response> proxyUsage(@PathParam("name") String name) {
        if (!configHolder.isInitialized()) {
            return Uni.createFrom().item(Response.status(503).entity(ErrorResponse.of("NOT_READY",
                "configuration not yet loaded")).build());
        }
        NetworkProxyConfig p = configHolder.get().proxyByName().get(name);
        if (p == null) {
            return Uni.createFrom().item(Response.status(404)
                .entity(ErrorResponse.of("NOT_FOUND", "Proxy not found")).build());
        }
        return Uni.createFrom().completionStage(configRepository.countProxyUsage(name).toCompletionStage())
            .map(cnt -> Response.ok(Map.of("proxyName", name, "connectorCount", cnt)).build());
    }

    private String actor() {
        Principal p = securityContext.getUserPrincipal();
        return p != null ? p.getName() : "unknown";
    }

    // -- DTOs --

    public record VersionResponse(long version) {}

    /**
     * DTO proxy — nom immutable (regex PK), type socks5 seul, host validé,
     * port 1..65535, auth appariée (env:/file: refs seulement, jamais de mot de passe clair).
     */
    public record ProxyDto(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_.\\-]{1,64}") String name,
        boolean enabled,
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @Size(max = 64) String username,
        @Size(max = 512) String passwordRef,
        @Size(max = 512) String description,
        @Min(1) long version
    ) {}
}
