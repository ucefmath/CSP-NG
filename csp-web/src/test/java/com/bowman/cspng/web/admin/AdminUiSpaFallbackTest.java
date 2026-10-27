package com.bowman.cspng.web.admin;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fallback SPA {@code /admin/*} : routes HTML connues servies, tout le reste en 404
 * (assets absents et API jamais masqués). Test unitaire sans conteneur.
 */
@DisplayName("AdminUiSpaFallbackTest — Fallback /admin/* (SPA)")
class AdminUiSpaFallbackTest {

  private static final Set<String> LEGACY_ROUTES = Set.of(
      "overview", "general", "cache", "cluster", "profiles", "listeners",
      "catalog", "connectors", "proxys", "relais", "dcw", "users");

  @Test
  @DisplayName("1. Les 12 routes legacy + nouveaux écrans sont des routes fallback connues")
  void knownRoutesCoverLegacyAndNewScreens() {
    assertTrue(AdminSpaFallbackResource.SPA_ROUTES.containsAll(LEGACY_ROUTES),
        "all 12 legacy routes must be known SPA routes");
    assertTrue(AdminSpaFallbackResource.SPA_ROUTES.contains("cache-etat"), "cache-etat missing");
    assertTrue(AdminSpaFallbackResource.SPA_ROUTES.contains("cacheex"), "cacheex missing");
    assertTrue(AdminSpaFallbackResource.SPA_ROUTES.contains("cw-cycle"), "cw-cycle missing");
  }

  @Test
  @DisplayName("2. Route inconnue, asset ou API => 404 (jamais masqué)")
  void unknownRoutesReturn404() {
    AdminSpaFallbackResource r = new AdminSpaFallbackResource();
    assertEquals(404, r.fallback("nope").getStatus(), "unknown route must 404");
    assertEquals(404, r.fallback("assets").getStatus(), "assets segment must 404");
    assertEquals(404, r.fallback("api").getStatus(), "api segment must 404");
    assertEquals(404, r.fallback(null).getStatus(), "null must 404");
  }

  @Test
  @DisplayName("3. Route connue => index.html du bundle (200) ou 404 si bundle non construit")
  void knownRouteServesBundleOr404() {
    boolean bundleBuilt = bundleIndexExists();
    AdminSpaFallbackResource r = new AdminSpaFallbackResource();
    Response res = r.fallback("overview");
    if (bundleBuilt) {
      assertEquals(200, res.getStatus(), "known route must serve index.html when bundle built");
      assertNotNull(res.getEntity(), "index.html entity missing");
      String html = new String((byte[]) res.getEntity(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(html.contains("<div id=\"root\">"), "SPA root div missing in index.html");
      assertTrue(html.contains("/admin/assets/app.js"), "bundle script missing in index.html");
    } else {
      assertEquals(404, res.getStatus(), "known route must 404 when bundle absent (skip.frontend)");
    }
  }

  private static boolean bundleIndexExists() {
    for (String base : new String[]{"target/frontend-dist", "csp-web/target/frontend-dist",
        "target/classes/META-INF/resources/admin", "csp-web/target/classes/META-INF/resources/admin"}) {
      if (Files.exists(Path.of(base, "index.html"))) {
        return true;
      }
    }
    // Classpath (exécution depuis csp-web avec bundle copié)
    return AdminSpaFallbackResource.class.getResource(
        AdminSpaFallbackResource.INDEX_CLASSPATH) != null;
  }
}
