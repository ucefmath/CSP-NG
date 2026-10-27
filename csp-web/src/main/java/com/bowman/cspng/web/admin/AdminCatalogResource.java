package com.bowman.cspng.web.admin;

import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.connectors.catalog.CardSource;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

/**
 * REST Admin endpoints pour la gestion du catalogue de cartes persistantes (P6/P8).
 */
@Path("/api/admin/catalog")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminCatalogResource {

    @Inject
    CardCatalogRepository catalogRepository;

    @Inject
    CardDiscoveryService cardDiscoveryService;

    public record CapabilityResponse(
        long id, String profile, int caid, int provider, String state,
        long cardId, long version, Instant updatedAt
    ) {}

    public record SourceResponse(
        long id, long capabilityId, String connectorName, Long originCardId,
        String trust, int hop, int reshare, Instant updatedAt
    ) {}

    public record StateUpdateRequest(String state, long version) {}

    @GET
    @Path("/capabilities/{profile}")
    public Uni<Response> listCapabilities(@PathParam("profile") String profile) {
        if (catalogRepository == null) {
            return Uni.createFrom().item(Response.ok(List.of()).build());
        }
        return Uni.createFrom().completionStage(
            catalogRepository.listCapabilities(profile).toCompletionStage()
        ).map(list -> {
            List<CapabilityResponse> resps = list.stream()
                .map(c -> new CapabilityResponse(c.id(), c.profile(), c.caid(), c.provider(),
                    c.state().dbValue(), c.cardId(), c.version(), c.updatedAt()))
                .toList();
            return Response.ok(resps).build();
        });
    }

    @PUT
    @Path("/capabilities/{id}/state")
    public Uni<Response> setCapabilityState(@PathParam("id") long id, StateUpdateRequest req) {
        if (req == null || req.state() == null || req.version() < 1) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "state and version (>=1) required")).build());
        }
        CardCapability.State state;
        try {
            state = CardCapability.State.fromDb(req.state());
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
        }

        if (catalogRepository == null) {
            return Uni.createFrom().item(Response.status(503)
                .entity(ErrorResponse.of("NOT_READY", "Catalog repository not available")).build());
        }

        return Uni.createFrom().completionStage(
            catalogRepository.setCapabilityState(id, state, req.version()).toCompletionStage()
        ).map(c -> {
            if (cardDiscoveryService != null) {
                cardDiscoveryService.updateCapabilityState(c.profile(), c.caid(), c.provider(), c.state());
            }
            return Response.ok(new CapabilityResponse(c.id(), c.profile(), c.caid(), c.provider(),
                c.state().dbValue(), c.cardId(), c.version(), c.updatedAt())).build();
        })
        .onFailure(IllegalStateException.class).recoverWithItem(e ->
            Response.status(409).entity(ErrorResponse.of("CONFLICT", e.getMessage())).build());
    }

    @GET
    @Path("/capabilities/{id}/sources")
    public Uni<Response> listSources(@PathParam("id") long capabilityId) {
        if (catalogRepository == null) {
            return Uni.createFrom().item(Response.ok(List.of()).build());
        }
        return Uni.createFrom().completionStage(
            catalogRepository.listSources(capabilityId).toCompletionStage()
        ).map(list -> {
            List<SourceResponse> resps = list.stream()
                .map(s -> new SourceResponse(s.id(), s.capabilityId(), s.connectorName(),
                    s.originCardId(), s.trust().dbValue(), s.hop(), s.reshare(), s.updatedAt()))
                .toList();
            return Response.ok(resps).build();
        });
    }
}
