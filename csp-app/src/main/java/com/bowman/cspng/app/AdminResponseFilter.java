package com.bowman.cspng.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.ext.Provider;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

@ApplicationScoped
@Provider
public class AdminResponseFilter {

    @ServerResponseFilter
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) return;
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (!(normalized.equals("api/admin") || normalized.startsWith("api/admin/"))) return;

        responseContext.getHeaders().putSingle("Cache-Control", "no-store");
        responseContext.getHeaders().putSingle("Pragma", "no-cache");
        responseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().putSingle("Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'");
    }
}
