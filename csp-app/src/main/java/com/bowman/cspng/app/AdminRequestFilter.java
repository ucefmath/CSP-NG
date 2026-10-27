package com.bowman.cspng.app;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;
import java.util.UUID;

/**
 * Filtre de requête admin : TLS + CSRF.
 * Utilise l'annotation Quarkus REST {@code @ServerRequestFilter} pour
 * être exécuté avant les ressources JAX-RS.
 * Retourne Optional<Response> pour abort (Quarkus REST n'autorise pas abortWith).
 */
@ApplicationScoped
@Provider
public class AdminRequestFilter {

    @Inject
    @ConfigProperty(name = "csp.admin.require-https", defaultValue = "true")
    boolean requireHttps;

    @ServerRequestFilter
    public Optional<Response> filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) return Optional.empty();
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!(normalized.equals("api/admin") || normalized.startsWith("api/admin/"))) return Optional.empty();

        if (requireHttps) {
            try {
                var uri = requestContext.getUriInfo().getRequestUri();
                if (uri == null || !"https".equals(uri.getScheme())) {
                    String corrId = UUID.randomUUID().toString().substring(0, 8);
                    Response abort = Response.status(426)
                        .type(MediaType.APPLICATION_JSON)
                        .header("Cache-Control", "no-store")
                        .header("Pragma", "no-cache")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                        .entity(new ErrorResponse("HTTPS_REQUIRED", "HTTPS is required for admin API", null, corrId))
                        .build();
                    return Optional.of(abort);
                }
            } catch (Exception e) {
                String corrId = UUID.randomUUID().toString().substring(0, 8);
                Response abort = Response.status(426)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                    .entity(new ErrorResponse("HTTPS_REQUIRED", "HTTPS is required for admin API", null, corrId))
                    .build();
                return Optional.of(abort);
            }
        }

        String method = requestContext.getMethod();
        if (!HttpMethod.GET.equals(method) && !HttpMethod.HEAD.equals(method)
            && !HttpMethod.OPTIONS.equals(method)) {
            String csrf = requestContext.getHeaderString("X-CSP-Requested-With");
            if (!"CSP-WebUI".equals(csrf)) {
                String corrId = UUID.randomUUID().toString().substring(0, 8);
                Response abort = Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                    .entity(new ErrorResponse("CSRF_INVALID", "Missing or invalid X-CSP-Requested-With header", null, corrId))
                    .build();
                return Optional.of(abort);
            }
        }
        return Optional.empty();
    }
}
