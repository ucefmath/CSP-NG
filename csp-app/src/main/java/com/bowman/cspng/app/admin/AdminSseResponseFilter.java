package com.bowman.cspng.app.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;

@ApplicationScoped
@Provider
public class AdminSseResponseFilter {

    @Inject AdminEventBroker broker;

    @ServerRequestFilter
    public Optional<Response> preFilter(ContainerRequestContext requestContext) {
        String path = extractPath(requestContext);
        if (!"api/admin/events".equals(path)) return Optional.empty();
        if (broker != null && !broker.isEnabled()) {
            return Optional.of(Response.status(Response.Status.NOT_FOUND).entity("{\"code\":\"NOT_FOUND\",\"message\":\"SSE disabled\"}").type("application/json").build());
        }
        return Optional.empty();
    }

    private String extractPath(ContainerRequestContext ctx) {
        try {
            String p = ctx.getUriInfo().getPath();
            if (p != null) {
                String n = p.startsWith("/") ? p.substring(1) : p;
                if ("api/admin/events".equals(n)) return n;
            }
            String uriPath = ctx.getUriInfo().getRequestUri().getPath();
            if (uriPath != null) {
                String n2 = uriPath.startsWith("/") ? uriPath.substring(1) : uriPath;
                if (n2.equals("api/admin/events") || n2.endsWith("/api/admin/events")) return "api/admin/events";
            }
        } catch (Exception ignored) {}
        return "";
    }
}
