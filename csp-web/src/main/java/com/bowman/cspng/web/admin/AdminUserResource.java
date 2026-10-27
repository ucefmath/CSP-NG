package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.web.admin.dto.*;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST endpoints pour la gestion des utilisateurs (admin uniquement).
 */
@Path("/api/admin/users")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUserResource {

    @Inject
    UserRepository userRepository;

    @Context
    SecurityContext securityContext;

    @GET
    public Uni<List<UserResponse>> list() {
        return Uni.createFrom().completionStage(
            userRepository.list().toCompletionStage()
        ).map(users -> users.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    @GET
    @Path("/{name}")
    public Uni<Response> get(@PathParam("name") String name) {
        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).map(opt -> {
            if (opt.isEmpty()) {
                return Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build();
            }
            return Response.ok(toResponse(opt.get())).build();
        });
    }

    @POST
    public Uni<Response> create(@Valid CreateUserRequest req) {
        UserRepository.CreateUserRequest repoReq = new UserRepository.CreateUserRequest(
            req.name(), req.displayName(), req.password(), req.email(),
            req.enabled(), req.admin(), req.debug(), req.mapExcluded(),
            req.maxConnections(), req.ipMask(), req.allowedEcmRate(),
            req.profiles(), req.allowedServices(), req.blockedServices(), req.allowedConnectors(), null, null
        );
        return Uni.createFrom().completionStage(
            userRepository.create(repoReq, actor()).toCompletionStage()
        ).map(rec -> (Response) Response.status(201).entity(toResponse(rec)).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @PUT
    @Path("/{name}")
    public Uni<Response> update(@PathParam("name") String name, @Valid UpdateUserRequest req,
                                 @QueryParam("version") long version) {
        if (version <= 0) {
            return Uni.createFrom().item(
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version query parameter required")).build());
        }
        UserRepository.UpdateUserRequest repoReq = new UserRepository.UpdateUserRequest(
            req.displayName(), req.password(), req.email(),
            req.enabled(), req.admin(), req.debug(), req.mapExcluded(),
            req.maxConnections(), req.ipMask(), req.allowedEcmRate(),
            req.profiles(), req.allowedServices(), req.blockedServices(), req.allowedConnectors(), null, null
        );
        return Uni.createFrom().completionStage(
            userRepository.update(name, repoReq, version, actor()).toCompletionStage()
        ).flatMap(rec ->
            // Reload complet pour éviter réponse partielle {} sur ACL non fournies (repository reconstruit partiel)
            Uni.createFrom().completionStage(userRepository.get(rec.name()).toCompletionStage())
                .map(opt -> opt.map(this::toResponse).orElse(toResponse(rec)))
        ).map(full -> (Response) Response.ok(full).build())
        .onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @DELETE
    @Path("/{name}")
    public Uni<Response> delete(@PathParam("name") String name, @QueryParam("version") long version) {
        if (version <= 0) {
            return Uni.createFrom().item(
                Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "version query parameter required")).build());
        }
        return Uni.createFrom().completionStage(
            userRepository.delete(name, version, actor()).toCompletionStage()
        ).map(v -> Response.noContent().build());
    }

    private UserResponse toResponse(UserRecord rec) {
        return new UserResponse(
            rec.name(), rec.displayName(), rec.email(),
            rec.enabled(), rec.admin(), rec.debug(), rec.mapExcluded(),
            rec.maxConnections(), rec.ipMask(), rec.allowedEcmRate(),
            rec.version(), rec.profiles(), rec.allowedServices(), rec.blockedServices(), rec.allowedConnectors()
        );
    }

    private String actor() {
        Principal p = securityContext.getUserPrincipal();
        return p != null ? p.getName() : "unknown";
    }
}
