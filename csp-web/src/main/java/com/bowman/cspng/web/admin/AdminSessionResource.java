package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.bowman.cspng.web.admin.dto.LoginRequest;
import com.bowman.cspng.web.admin.dto.SessionResponse;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Endpoints d'authentification et gestion de session UI Admin (/api/admin/session).
 */
@Path("/api/admin/session")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class AdminSessionResource {

    private static final Logger LOG = Logger.getLogger(AdminSessionResource.class.getName());
    public static final String COOKIE_NAME = "CSPADMINSESSION";

    private final AdminSessionManager sessionManager;
    private final UserDirectory userDirectory;
    private final LoginRateLimiter rateLimiter;

    @Inject
    public AdminSessionResource(AdminSessionManager sessionManager,
                                UserDirectory userDirectory,
                                LoginRateLimiter rateLimiter) {
        this.sessionManager = sessionManager;
        this.userDirectory = userDirectory;
        this.rateLimiter = rateLimiter;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @jakarta.annotation.security.PermitAll
    public Uni<Response> login(LoginRequest req, @Context HttpServerRequest request) {
        String ip = extractIp(request);
        String corrId = UUID.randomUUID().toString().substring(0, 8);

        // 1. Rate-limiting per-IP
        if (!rateLimiter.tryAcquire(ip)) {
            AdminAuthAudit.preauthReject(ip, "rate-limit");
            Response r429 = Response.status(429)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .entity(new ErrorResponse("RATE_LIMIT_EXCEEDED", "Too many login attempts", null, corrId))
                .build();
            return Uni.createFrom().item(r429);
        }

        if (req == null || req.username() == null || req.username().isBlank() || req.password() == null || req.password().isBlank()) {
            String u = req != null && req.username() != null ? req.username() : "?";
            AdminAuthAudit.authFail("webadmin", u, ip, "unknown-user");
            return Uni.createFrom().item(unauthorized(corrId));
        }

        String username = req.username().trim();
        String password = req.password();

        Future<Optional<UserRecord>> lookupFuture = userDirectory.lookup(username);
        return Uni.createFrom().<Optional<UserRecord>>emitter(emitter -> {
            lookupFuture.onSuccess(emitter::complete).onFailure(emitter::fail);
        }).onFailure().recoverWithItem(Optional.empty())
        .chain(opt -> {
            if (opt.isEmpty()) {
                AdminAuthAudit.authFail("webadmin", username, ip, "unknown-user");
                return Uni.createFrom().item(unauthorized(corrId));
            }
            UserRecord rec = opt.get();
            if (!rec.enabled() || !rec.isEnabledEffective(Instant.now())) {
                AdminAuthAudit.authFail("webadmin", username, ip, "bad-password");
                return Uni.createFrom().item(unauthorized(corrId));
            }
            if (!rec.admin()) {
                AdminAuthAudit.authFail("webadmin", username, ip, "bad-password");
                return Uni.createFrom().item(unauthorized(corrId));
            }

            Future<Boolean> authFuture = userDirectory.authenticate(username, password);
            return Uni.createFrom().<Boolean>emitter(emitter -> {
                authFuture.onSuccess(emitter::complete).onFailure(err -> emitter.complete(false));
            }).chain(authenticated -> {
                if (!Boolean.TRUE.equals(authenticated)) {
                    AdminAuthAudit.authFail("webadmin", username, ip, "bad-password");
                    return Uni.createFrom().item(unauthorized(corrId));
                }

                // Authentification réussie
                AdminSession session = sessionManager.createSession(rec);
                AdminAuthAudit.authOk("webadmin", rec.name(), ip);

                // Max-Age correspond à max-lifetime (24h), le serveur applique le timeout idle glissant en mémoire
                long maxLifetimeSeconds = sessionManager.getMaxLifetimeSeconds();
                String cookieHeader = buildSetCookie(session.token(), maxLifetimeSeconds);

                Response resp = Response.ok(new SessionResponse(rec.name(), session.expiresIdle()))
                    .header("Set-Cookie", cookieHeader)
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .build();
                return Uni.createFrom().item(resp);
            });
        });
    }

    @DELETE
    @jakarta.annotation.security.PermitAll
    public Response logout(@Context HttpServerRequest request) {
        String token = extractSessionCookie(request);
        if (token != null) {
            sessionManager.revoke(token);
        }

        String clearCookie = buildClearCookie();
        return Response.ok(Map.of("status", "ok"))
            .header("Set-Cookie", clearCookie)
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .build();
    }

    private static Response unauthorized(String corrId) {
        return Response.status(Response.Status.UNAUTHORIZED)
            .type(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            .entity(new ErrorResponse("INVALID_CREDENTIALS", "Invalid credentials", null, corrId))
            .build();
    }

    public static String buildSetCookie(String token, long maxAgeSeconds) {
        return COOKIE_NAME + "=" + token + "; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAgeSeconds;
    }

    public static String buildClearCookie() {
        return COOKIE_NAME + "=; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT";
    }

    private String extractSessionCookie(HttpServerRequest request) {
        if (request == null) return null;
        Cookie c = request.getCookie(COOKIE_NAME);
        if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
            return c.getValue().trim();
        }
        String cookieHeader = request.getHeader("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE_NAME + "=")) {
                    return trimmed.substring(COOKIE_NAME.length() + 1).trim();
                }
            }
        }
        return null;
    }

    private String extractIp(HttpServerRequest request) {
        if (request == null) return "127.0.0.1";
        if (request.remoteAddress() != null && request.remoteAddress().host() != null) {
            return request.remoteAddress().host();
        }
        return "127.0.0.1";
    }
}
