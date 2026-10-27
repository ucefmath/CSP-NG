package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserNotFoundException;
import com.bowman.cspng.users.UserProtocolSecretRepository;
import com.bowman.cspng.users.UserProtocolSecretRepository.Protocol;
import com.bowman.cspng.users.UserProtocolSecretRepository.ProtocolSecretRecord;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.bowman.cspng.web.admin.dto.ProtocolSecretResponse;
import com.bowman.cspng.web.admin.dto.UpsertProtocolSecretRequest;
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
import java.util.stream.Collectors;

/**
 * REST endpoints pour la gestion des secrets de protocoles entrants (CCcam / CS378x).
 * <p>
 * REFS ONLY : aucune valeur de secret ne transite jamais par cette API.
 */
@Path("/api/admin/users/{name}/secrets")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminUserSecretResource {

    @Inject
    UserRepository userRepository;

    @Inject
    UserProtocolSecretRepository secretRepository;

    @Context
    SecurityContext securityContext;

    @GET
    public Uni<Response> list(@PathParam("name") String name) {
        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).flatMap(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build());
            }
            return Uni.createFrom().completionStage(
                secretRepository.listByUser(name).toCompletionStage()
            ).map(list -> {
                List<ProtocolSecretResponse> resps = list.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
                return Response.ok(resps).build();
            });
        });
    }

    @GET
    @Path("/{protocol}")
    public Uni<Response> get(@PathParam("name") String name, @PathParam("protocol") String protocolStr) {
        Protocol proto = parseProtocol(protocolStr);
        if (proto == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_PROTOCOL", "Unknown protocol: " + protocolStr + ". Supported: cccam, cs378x"))
                .build());
        }
        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).flatMap(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build());
            }
            return Uni.createFrom().completionStage(
                secretRepository.get(name, proto).toCompletionStage()
            ).map(opt -> {
                if (opt.isEmpty()) {
                    return Response.status(404)
                        .entity(ErrorResponse.of("NOT_FOUND", "Protocol secret not found for " + proto.dbValue()))
                        .build();
                }
                return Response.ok(toResponse(opt.get())).build();
            });
        });
    }

    @PUT
    @Path("/{protocol}")
    public Uni<Response> upsert(@PathParam("name") String name,
                                @PathParam("protocol") String protocolStr,
                                @Valid UpsertProtocolSecretRequest req,
                                @QueryParam("version") @DefaultValue("0") long version) {
        Protocol proto = parseProtocol(protocolStr);
        if (proto == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_PROTOCOL", "Unknown protocol: " + protocolStr + ". Supported: cccam, cs378x"))
                .build());
        }
        if (name.length() > proto.maxNameLength()) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", proto.dbValue() + " login (user name) must be <= " + proto.maxNameLength() + " chars"))
                .build());
        }

        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).flatMap(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build());
            }

            UserProtocolSecretRepository.UpsertProtocolSecretRequest repoReq =
                new UserProtocolSecretRepository.UpsertProtocolSecretRequest(
                    req.secretRef(),
                    req.validUntil(),
                    req.enabled() != null ? req.enabled() : true,
                    toPolicy(req.cacheex()));

            return Uni.createFrom().completionStage(
                secretRepository.upsert(name, proto, repoReq, version, actor()).toCompletionStage()
            ).map(rec -> {
                int status = (version == 0) ? 201 : 200;
                return Response.status(status).entity(toResponse(rec)).build();
            });
        }).onFailure(IllegalArgumentException.class).recoverWithItem(e ->
            Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", e.getMessage())).build());
    }

    @POST
    @Path("/{protocol}/revoke")
    public Uni<Response> revoke(@PathParam("name") String name,
                                @PathParam("protocol") String protocolStr,
                                @QueryParam("version") long version) {
        Protocol proto = parseProtocol(protocolStr);
        if (proto == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_PROTOCOL", "Unknown protocol: " + protocolStr + ". Supported: cccam, cs378x"))
                .build());
        }
        if (version <= 0) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter required for revoke"))
                .build());
        }

        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).flatMap(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build());
            }

            return Uni.createFrom().completionStage(
                secretRepository.revoke(name, proto, version, actor()).toCompletionStage()
            ).map(rec -> Response.ok(toResponse(rec)).build());
        });
    }

    @DELETE
    @Path("/{protocol}")
    public Uni<Response> delete(@PathParam("name") String name,
                                @PathParam("protocol") String protocolStr,
                                @QueryParam("version") long version) {
        Protocol proto = parseProtocol(protocolStr);
        if (proto == null) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_PROTOCOL", "Unknown protocol: " + protocolStr + ". Supported: cccam, cs378x"))
                .build());
        }
        if (version <= 0) {
            return Uni.createFrom().item(Response.status(400)
                .entity(ErrorResponse.of("INVALID_INPUT", "version query parameter required for delete"))
                .build());
        }

        return Uni.createFrom().completionStage(
            userRepository.get(name).toCompletionStage()
        ).flatMap(userOpt -> {
            if (userOpt.isEmpty()) {
                return Uni.createFrom().item(Response.status(404)
                    .entity(ErrorResponse.of("NOT_FOUND", "User not found"))
                    .build());
            }

            return Uni.createFrom().completionStage(
                secretRepository.delete(name, proto, version).toCompletionStage()
            ).map(v -> Response.noContent().build());
        });
    }

    private Protocol parseProtocol(String s) {
        if (s == null) return null;
        try {
            return Protocol.fromDb(s.toLowerCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ProtocolSecretResponse toResponse(ProtocolSecretRecord rec) {
        var policy = rec.cacheex() != null ? rec.cacheex() : com.bowman.cspng.users.CacheExPolicy.OFF;
        return new ProtocolSecretResponse(
            rec.userName(),
            rec.protocol().dbValue(),
            rec.secretRef(),
            rec.secretVersion(),
            rec.ucrc(),
            rec.enabled(),
            rec.validUntil(),
            rec.version(),
            rec.updatedBy(),
            rec.updatedAt(),
            new ProtocolSecretResponse.CacheExPolicyDto(
                policy.mode(), policy.maxHop(), policy.filter())
        );
    }

    /** DTO cacheex → domaine (null/mode absent = off ; validation stricte du domaine). */
    private static com.bowman.cspng.users.CacheExPolicy toPolicy(
            com.bowman.cspng.web.admin.dto.UpsertProtocolSecretRequest.CacheExPolicyDto dto) {
        if (dto == null || dto.mode() == null || dto.mode() == 0) {
            return com.bowman.cspng.users.CacheExPolicy.OFF;
        }
        return new com.bowman.cspng.users.CacheExPolicy(
            dto.mode(),
            dto.maxHop() != null ? dto.maxHop() : 10,
            dto.filter());
    }

    private String actor() {
        Principal p = securityContext.getUserPrincipal();
        return p != null ? p.getName() : "unknown";
    }
}
