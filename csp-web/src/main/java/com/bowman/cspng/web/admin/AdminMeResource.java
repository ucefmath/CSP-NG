package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.MeResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;

import java.util.Set;

/**
 * Endpoint /api/admin/me — retourne le nom et les rôles de l'utilisateur authentifié.
 * Ne renvoie jamais le hash du mot de passe.
 */
@Path("/api/admin/me")
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminMeResource {

    @GET
    public MeResponse me(@Context SecurityContext ctx) {
        String name = ctx.getUserPrincipal() != null ? ctx.getUserPrincipal().getName() : "unknown";
        java.util.HashSet<String> roles = new java.util.HashSet<>();
        if (ctx.isUserInRole("admin")) roles.add("admin");
        if (ctx.isUserInRole("user")) roles.add("user");
        return new MeResponse(name, Set.copyOf(roles));
    }
}
