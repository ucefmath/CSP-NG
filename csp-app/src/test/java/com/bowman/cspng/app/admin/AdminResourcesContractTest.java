package com.bowman.cspng.app.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contrats structurels des nouveaux endpoints admin (v2) : chemins stables pour
 * le frontend, garde {@code admin} partout (auth session + CSRF via filtres
 * globaux {@code /api/admin/**}), mappers d'erreurs enregistrés.
 */
@DisplayName("Contrats endpoints v2 — paths, rôles, mappers")
class AdminResourcesContractTest {

  private static void assertAdminResource(Class<?> resource, String basePath) {
    Path path = resource.getAnnotation(Path.class);
    assertNotNull(path, resource.getSimpleName() + " must have @Path");
    assertEquals(basePath, path.value());
    RolesAllowed roles = resource.getAnnotation(RolesAllowed.class);
    assertNotNull(roles, resource.getSimpleName() + " must have @RolesAllowed");
    assertArrayEquals(new String[]{"admin"}, roles.value());
  }

  private static Method findMethod(Class<?> resource,
                                   Class<? extends java.lang.annotation.Annotation> httpVerb,
                                   String subPath) {
    for (Method m : resource.getDeclaredMethods()) {
      if (m.getAnnotation(httpVerb) == null) continue;
      Path p = m.getAnnotation(Path.class);
      String v = p != null ? p.value() : "";
      if (v.equals(subPath)) return m;
    }
    fail(resource.getSimpleName() + " must expose " + httpVerb.getSimpleName() + " " + subPath);
    return null;
  }

  @Test
  @DisplayName("Bulk : PUT littéraux /connectors|profiles|listeners|users/bulk (jamais de template éclipsé)")
  void bulkContract() {
    // NOTE routage : les chemins sont VOLONTAIREMENT littéraux — un template
    // /{resource}/bulk serait éclipsé par /connectors/{name} etc. en JAX-RS
    // (prouvé par AdminCacheexContractsHttpTest). URLs inchangées pour le frontend.
    findMethod(com.bowman.cspng.web.admin.AdminConfigResource.class, PUT.class, "/connectors/bulk");
    findMethod(com.bowman.cspng.web.admin.AdminConfigResource.class, PUT.class, "/profiles/bulk");
    findMethod(com.bowman.cspng.web.admin.AdminConfigResource.class, PUT.class, "/listeners/bulk");
    findMethod(com.bowman.cspng.web.admin.AdminBulkResource.class, PUT.class, "/users/bulk");
  }

  @Test
  @DisplayName("Cache status : GET /api/admin/cache/status, rôle admin")
  void cacheStatusContract() {
    assertAdminResource(com.bowman.cspng.web.admin.AdminCacheResource.class, "/api/admin/cache");
    findMethod(com.bowman.cspng.web.admin.AdminCacheResource.class, GET.class, "/status");
  }

  @Test
  @DisplayName("CacheEx stats : GET+POST /api/admin/cacheex/stats[/reset], rôle admin")
  void cacheexStatsContract() {
    assertAdminResource(com.bowman.cspng.web.admin.AdminCacheexResource.class, "/api/admin/cacheex");
    findMethod(com.bowman.cspng.web.admin.AdminCacheexResource.class, GET.class, "/stats");
    findMethod(com.bowman.cspng.web.admin.AdminCacheexResource.class, POST.class, "/stats/reset");
  }

  @Test
  @DisplayName("Secrets materialize : POST uniquement, rôle admin")
  void secretsMaterializeContract() {
    assertAdminResource(AdminSecretResource.class, "/api/admin/secrets/materialize");
    findMethod(AdminSecretResource.class, POST.class, "");
    // POST ONLY : aucun GET/PUT/DELETE sur la ressource (arbitrage sécurité)
    for (Method m : AdminSecretResource.class.getDeclaredMethods()) {
      assertNull(m.getAnnotation(GET.class), "materialize must be POST-only (no GET)");
      assertNull(m.getAnnotation(PUT.class), "materialize must be POST-only (no PUT)");
      assertNull(m.getAnnotation(DELETE.class), "materialize must be POST-only (no DELETE)");
    }
  }

  @Test
  @DisplayName("CW-cycle : GET+PUT /api/admin/config/cw-cycle")
  void cwCycleContract() throws Exception {
    Class<?> resource = com.bowman.cspng.web.admin.AdminConfigResource.class;
    findMethod(resource, GET.class, "/cw-cycle");
    findMethod(resource, PUT.class, "/cw-cycle");
  }

  @Test
  @DisplayName("Mappers bulk enregistrés (@Provider)")
  void bulkMappersRegistered() {
    assertNotNull(
        com.bowman.cspng.web.admin.BulkUnknownItemsExceptionMapper.class.getAnnotation(Provider.class));
    assertNotNull(
        com.bowman.cspng.web.admin.UnknownUsersExceptionMapper.class.getAnnotation(Provider.class));
  }
}
