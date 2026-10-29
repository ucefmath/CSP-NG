package com.bowman.cspng.web.admin;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Fallback HTML de la SPA admin (React Router, basename {@code /admin}).
 *
 * <p>Ne sert que les routes HTML connues de la console ({@code /admin/overview}, …) en
 * renvoyant le {@code index.html} du bundle frontend ({@code META-INF/resources/admin}).
 * Tout le reste (assets absents, API, routes inconnues) reste en 404 : ce fallback ne
 * masque jamais une ressource manquante ni une erreur backend.
 *
 * <p>Ne touche à aucune API backend existante (ajout seul, refonte UI).
 */
@Path("/admin")
public class AdminSpaFallbackResource {

    /** Routes HTML SPA (miroir de ADMIN_ROUTES côté frontend/src/router.tsx). */
    static final Set<String> SPA_ROUTES = Set.of(
            "overview", "general", "cache", "cache-etat", "cacheex", "cluster",
            "profiles", "listeners", "catalog", "connectors", "proxys", "relais",
            "dcw", "cw-cycle", "users", "logs", "login");

    static final String INDEX_CLASSPATH = "/META-INF/resources/admin/index.html";

    @GET
    @Path("{route: [a-z0-9-]+}")
    @Produces(MediaType.TEXT_HTML)
    public Response fallback(@PathParam("route") String route) {
        if (route == null || !SPA_ROUTES.contains(route)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        byte[] index = readIndex();
        if (index == null) {
            // Bundle frontend non construit (ex : -Dskip.frontend=true).
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        CacheControl noStore = new CacheControl();
        noStore.setNoStore(true);
        return Response.ok(index, MediaType.TEXT_HTML_TYPE)
                .cacheControl(noStore)
                .header("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
                                + "connect-src 'self'; object-src 'none'; base-uri 'none'; "
                                + "frame-ancestors 'none'; form-action 'self'")
                .build();
    }

    private static byte[] readIndex() {
        try (InputStream in = AdminSpaFallbackResource.class.getResourceAsStream(INDEX_CLASSPATH)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
