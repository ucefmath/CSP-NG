package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.BulkPatches;
import com.bowman.cspng.config.BulkUnknownItemsException;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigValidationException;
import com.bowman.cspng.users.UnknownUsersException;
import com.bowman.cspng.users.UserBulkPatch;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Bulk edit des options communes de plusieurs éléments (contrat v2 §2).
 *
 * <p>Garanties :
 * <ul>
 *   <li>versions PAR LIGNE {@code {items:[{id,version}], patch:{...}}} — pas de génération globale ;</li>
 *   <li>ordre d'application DÉTERMINISTE (ids croissants, côté repository) ;</li>
 *   <li>UNE transaction : conflit 409, invalide 400 ou item inconnu 400 UNKNOWN_ITEMS
 *       ⇒ rollback TOTAL (rien d'appliqué) ;</li>
 *   <li>fail-closed sur les clés de patch : champs identité, secrets, nodeId et
 *       admin/password STRUCTURELLEMENT interdits (400 INVALID_PATCH_FIELD) ;</li>
 *   <li>audit transactionnel par item + acteur.</li>
 * </ul>
 */
@Path("/api/admin/config")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminBulkResource {

    private static final int MAX_ITEMS = 100;

    /** Clés de patch autorisées par ressource (contrat v2 §2.1 — tout le reste est 400). */
    private static final Set<String> CONNECTOR_KEYS = Set.of(
        "enabled", "debug", "connectTimeoutMs", "reconnectDelayMs", "keepAliveSec", "minDelayMs",
        "maxQueue", "newcamdMaxMsgSize", "radegastResponseTimeoutMs", "cccamEnabled", "cccamVersion",
        "priority", "maxCapacity", "proxyName", "cardDiscovery", "reshareGrant", "newcamdDialect",
        "reshareCap", "newcamdPasswordEncoding", "cacheexMode", "cacheexFilter", "cacheexMaxSend",
        "cacheexMaxHop");
    private static final Set<String> PROFILE_KEYS = Set.of(
        "enabled", "debug", "cacheOnly", "requireProviderMatch", "checkEcmLength", "maxCwWaitMs",
        "congestionLimitMs", "mismatchedCards", "serviceConflicts", "blockCaidMismatch",
        "providerFilter", "servicesFile", "servicesFileFormat");
    private static final Set<String> LISTENER_KEYS = Set.of(
        "enabled", "cardMode", "strictPolicy", "reshareCap", "maxCards", "maxInflight",
        "maxPerIp", "idleTimeoutSec", "allowList", "denyList");
    private static final Set<String> USER_KEYS = Set.of(
        "displayName", "email", "enabled", "debug", "mapExcluded", "maxConnections", "ipMask",
        "allowedEcmRate", "profiles", "allowedConnectors", "allowedServices", "blockedServices");

    @Inject
    ConfigRepository configRepository;

    @Inject
    UserRepository userRepository;

    @Inject
    ObjectMapper mapper;

    @Context
    SecurityContext securityContext;

    /**
     * NOTE routage : chemins LITTÉRAUX uniquement (aucun template) car JAX-RS
     * préfère les littéraux — un template <code>/{resource}/bulk</code> serait
     * éclipsé par les routes existantes <code>/connectors/{name}</code>,
     * <code>/profiles/{name}</code> (plus de caractères littéraux).
     * Les URLs restent EXACTEMENT celles du contrat v2 consommé par le frontend.
     */
    @PUT
    @Path("/users/bulk")
    public Uni<Response> usersBulkEndpoint(@Valid BulkRequestDto req) {
        return bulkUsers(req);
    }

    /** Parse items commun ; lève BadRequest (400 déjà construit) si invalide. */
    private List<BulkPatches.BulkItem> parseItems(BulkRequestDto req) throws BadRequest {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BadRequest("items must contain at least 1 element");
        }
        if (req.items().size() > MAX_ITEMS) {
            throw new BadRequest("items limit " + MAX_ITEMS);
        }
        if (req.patch() == null || !req.patch().isObject() || req.patch().isEmpty()) {
            throw new BadRequest("patch must contain at least 1 field");
        }
        if (req.patch().size() > 24) {
            throw new BadRequest("patch limit 24 fields");
        }
        List<BulkPatches.BulkItem> items = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (BulkItemDto dto : req.items()) {
            if (dto.id() == null || dto.id().isBlank()) {
                throw new BadRequest("item id required");
            }
            if (!seen.add(dto.id())) {
                throw new BadRequest("duplicate item id: " + dto.id());
            }
            items.add(new BulkPatches.BulkItem(dto.id(), dto.version()));
        }
        return items;
    }

    /** Erreur 400 pré-construite pour les validations synchrones. */
    private static final class BadRequest extends RuntimeException {
        BadRequest(String msg) { super(msg); }
    }

    private Uni<Response> badOf(BadRequest e) {
        return bad("INVALID_INPUT", e.getMessage());
    }

    /** Bulk connecteurs — appelé par PUT /connectors/bulk (chemin littéral). */
    public Uni<Response> bulkConnectors(@Valid BulkRequestDto req) {
        List<BulkPatches.BulkItem> items;
        try {
            items = parseItems(req);
        } catch (BadRequest e) {
            return badOf(e);
        }
        Uni<Response> invalid = checkKeys(req.patch(), CONNECTOR_KEYS);
        if (invalid != null) return invalid;
        BulkPatches.ConnectorPatch patch;
        try {
            patch = mapper.convertValue(req.patch(), BulkPatches.ConnectorPatch.class);
        } catch (IllegalArgumentException e) {
            return bad("INVALID_INPUT", e.getMessage());
        }
        return Uni.createFrom().completionStage(
                configRepository.bulkUpdateConnectors(items, patch, presentFields(req.patch()), actor()).toCompletionStage())
            .map(res -> Response.ok(new BulkResponseDto(toDtos(res))).build())
            .onFailure(ConfigValidationException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    /** Bulk profils — appelé par PUT /profiles/bulk (chemin littéral). */
    public Uni<Response> bulkProfiles(@Valid BulkRequestDto req) {
        List<BulkPatches.BulkItem> items;
        try {
            items = parseItems(req);
        } catch (BadRequest e) {
            return badOf(e);
        }
        Uni<Response> invalid = checkKeys(req.patch(), PROFILE_KEYS);
        if (invalid != null) return invalid;
        BulkPatches.ProfilePatch patch;
        try {
            patch = mapper.convertValue(req.patch(), BulkPatches.ProfilePatch.class);
        } catch (IllegalArgumentException e) {
            return bad("INVALID_INPUT", e.getMessage());
        }
        return Uni.createFrom().completionStage(
                configRepository.bulkUpdateProfiles(items, patch, presentFields(req.patch()), actor()).toCompletionStage())
            .map(res -> Response.ok(new BulkResponseDto(toDtos(res))).build())
            .onFailure(ConfigValidationException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    /** Bulk listeners — appelé par PUT /listeners/bulk (chemin littéral). */
    public Uni<Response> bulkListeners(@Valid BulkRequestDto req) {
        List<BulkPatches.BulkItem> items;
        try {
            items = parseItems(req);
        } catch (BadRequest e) {
            return badOf(e);
        }
        Uni<Response> invalid = checkKeys(req.patch(), LISTENER_KEYS);
        if (invalid != null) return invalid;
        for (BulkPatches.BulkItem it : items) {
            try {
                Long.parseLong(it.id());
            } catch (NumberFormatException e) {
                return bad("INVALID_INPUT", "listener ids must be numeric: " + it.id());
            }
        }
        BulkPatches.ListenerPatch patch;
        try {
            patch = mapper.convertValue(req.patch(), BulkPatches.ListenerPatch.class);
        } catch (IllegalArgumentException e) {
            return bad("INVALID_INPUT", e.getMessage());
        }
        return Uni.createFrom().completionStage(
                configRepository.bulkUpdateListeners(items, patch, presentFields(req.patch()), actor()).toCompletionStage())
            .map(res -> Response.ok(new BulkResponseDto(toDtos(res))).build())
            .onFailure(ConfigValidationException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    /** Bulk users — endpoint PUT /users/bulk (chemin littéral, cette classe). */
    public Uni<Response> bulkUsers(@Valid BulkRequestDto req) {
        List<BulkPatches.BulkItem> items;
        try {
            items = parseItems(req);
        } catch (BadRequest e) {
            return badOf(e);
        }
        Uni<Response> invalid = checkKeys(req.patch(), USER_KEYS);
        if (invalid != null) return invalid;
        UserBulkPatch patch;
        try {
            patch = mapper.convertValue(req.patch(), UserBulkPatch.class);
        } catch (IllegalArgumentException e) {
            return bad("INVALID_INPUT", e.getMessage());
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (BulkPatches.BulkItem it : items) {
            entries.add(new AbstractMap.SimpleImmutableEntry<>(it.id(), it.version()));
        }
        return Uni.createFrom().completionStage(
                userRepository.bulkUpdateUsers(entries, patch, presentFields(req.patch()), actor()).toCompletionStage())
            .map(res -> {
                List<BulkItemResultDto> out = res.stream()
                    .map(r -> new BulkItemResultDto(r.id(), r.version()))
                    .toList();
                return Response.ok(new BulkResponseDto(out)).build();
            })
            .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    private static List<BulkItemResultDto> toDtos(List<BulkPatches.BulkItemResult> res) {
        return res.stream().map(r -> new BulkItemResultDto(r.id(), r.version())).toList();
    }

    /** Clés présentes dans le patch (absent != null explicite — contrat v2 §2). */
    private static Set<String> presentFields(JsonNode patch) {
        Set<String> out = new java.util.HashSet<>();
        for (Iterator<String> it = patch.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    /** Fail-closed : toute clé hors whitelist ⇒ 400 INVALID_PATCH_FIELD (rien d'appliqué). */
    private Uni<Response> checkKeys(JsonNode patch, Set<String> allowed) {
        Set<String> unknown = new TreeSet<>();
        for (Iterator<String> it = patch.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!allowed.contains(field)) unknown.add(field);
        }
        if (!unknown.isEmpty()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_PATCH_FIELD",
                    "forbidden or unknown patch field(s): " + String.join(", ", unknown)))
                .build());
        }
        return null;
    }

    private static Uni<Response> bad(String code, String msg) {
        return Uni.createFrom().item(
            Response.status(400).entity(ErrorResponse.of(code, msg)).build());
    }

    private String actor() {
        Principal p = securityContext != null ? securityContext.getUserPrincipal() : null;
        return p != null ? p.getName() : "unknown";
    }

    // -- DTOs --

    public record BulkItemDto(@NotBlank String id, @Min(1) long version) {}

    public record BulkRequestDto(@Size(min = 1, max = MAX_ITEMS) List<@Valid BulkItemDto> items,
                                  JsonNode patch) {}

    public record BulkItemResultDto(String id, long version) {}

    public record BulkResponseDto(List<BulkItemResultDto> updated) {}
}
