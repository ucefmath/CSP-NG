package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Couverture UI « Qualité DCW » — refonte SPA React (remplace les assertions sur
 * l'ancien app.js vanilla et les suites .mjs legacy, supprimés).
 *
 * <p>Couverture réelle : pages Dcw/CwCycle (sources frontend) + tests vitest exécutés
 * par le build Maven ({@code npm run verify}).
 */
@DisplayName("AdminUiDcwTest — Qualité DCW et cycle CW (SPA)")
class AdminUiDcwTest {

  private static final Path FRONTEND = Path.of("csp-web/frontend/src");

  private static Path frontend() {
    return Files.exists(FRONTEND) ? FRONTEND : Path.of("frontend/src");
  }

  private static String read(String rel) {
    try {
      return Files.readString(frontend().resolve(rel), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read frontend/src/" + rel + ": " + e.getMessage(), e);
    }
  }

  @Test
  @DisplayName("1. Routes dcw et cw-cycle déclarées dans le routeur (basename /admin)")
  void routerDeclaresDcwAndCwCycle() {
    String router = read("router.tsx");
    assertTrue(Pattern.compile("\"dcw\"").matcher(router).find(), "router must declare dcw");
    assertTrue(Pattern.compile("\"cw-cycle\"").matcher(router).find(), "router must declare cw-cycle");
    assertTrue(Pattern.compile("basename:\\s*\"/admin\"").matcher(router).find(),
        "router must use basename /admin");
  }

  @Test
  @DisplayName("2. Page DCW : politique + règles CRUD + mappings CRUD + observabilité")
  void dcwPageCoversPolicyRulesMappingsObservability() {
    String dcw = read("pages/Dcw.tsx");
    assertTrue(dcw.contains("/api/admin/dcw/policy"), "DCW policy endpoint missing");
    assertTrue(dcw.contains("/api/admin/dcw/rules"), "DCW rules endpoint missing");
    assertTrue(dcw.contains("/api/admin/dcw/mappings"), "DCW mappings endpoint missing");
    assertTrue(dcw.contains("/api/admin/dcw/stats"), "DCW stats endpoint missing");
    assertTrue(dcw.contains("/api/admin/dcw/anomalies"), "DCW anomalies endpoint missing");
    assertTrue(dcw.contains("/api/admin/dcw/candidates"), "DCW candidates endpoint missing");
    assertTrue(Pattern.compile("role=\"tablist\"|role=\"tab\"").matcher(dcw).find(),
        "DCW tabs must be accessible (tablist/tab)");
  }

  @Test
  @DisplayName("3. Page cycle CW : CRUD contrat + bornes + observabilité cwCycle (jamais de faux zéros)")
  void cwCyclePageCoversContractAndObservability() {
    String cw = read("pages/CwCycle.tsx");
    assertTrue(cw.contains("/api/admin/config/cw-cycle"), "cw-cycle endpoint missing");
    assertTrue(Pattern.compile("maxList.*16.*4000|16-4000").matcher(cw).find(), "maxList bounds missing");
    assertTrue(Pattern.compile("keepTimeSec.*5.*240|5-240").matcher(cw).find(), "keepTimeSec bounds missing");
    assertTrue(cw.contains("\"log\"") && cw.contains("\"drop\""), "onBad log|drop missing");
    assertTrue(cw.contains("usePeerCycleMetadata"), "usePeerCycleMetadata missing");
    assertTrue(cw.contains(".cwCycle"), "cwCycle observability from /dcw/stats missing");
    assertTrue(Pattern.compile("Pas de clé cwCycle|inconnu").matcher(cw).find(),
        "missing cwCycle stats must render unknown, never fake zeros");
  }

  @Test
  @DisplayName("4. Suites vitest réelles présentes (exécutées par npm run verify dans le build Maven)")
  void vitestSuitesExist() {
    Path base = frontend().resolve("test");
    assertTrue(Files.exists(base.resolve("api.test.ts")), "api.test.ts missing");
    assertTrue(Files.exists(base.resolve("routes.test.tsx")), "routes.test.tsx missing");
    assertTrue(Files.exists(base.resolve("components.test.tsx")), "components.test.tsx missing");
  }
}
