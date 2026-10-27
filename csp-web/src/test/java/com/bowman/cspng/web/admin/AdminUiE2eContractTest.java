package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrat des tests E2E Playwright (bundle packagé + API mockée alignée DTO) :
 * existence des specs, alignement des fixtures sur les vrais endpoints/DTO,
 * et garde-fou XSS (aucun rendu HTML brut côté sources).
 *
 * <p>Les suites elles-mêmes sont exécutées par le build Maven
 * ({@code npm run e2e} dans {@code generate-resources}) ; ce test verrouille
 * le contrat sans les ré-exécuter.
 */
@DisplayName("AdminUiE2eContractTest — Contrat couverture E2E Playwright")
class AdminUiE2eContractTest {

  private static final Path FRONTEND = Path.of("csp-web/frontend");

  private static Path frontend() {
    return Files.exists(FRONTEND) ? FRONTEND : Path.of("frontend");
  }

  private static String read(String rel) {
    try {
      return Files.readString(frontend().resolve(rel), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read frontend/" + rel + ": " + e.getMessage(), e);
    }
  }

  @Test
  @DisplayName("1. Specs E2E présents : auth/nav/CRUD/bulk/secrets/relais/DCW/a11y")
  void e2eSpecsExist() {
    for (String spec : new String[]{"auth.spec.ts", "navigation.spec.ts", "crud.spec.ts",
        "connectors.spec.ts", "secrets.spec.ts", "relay.spec.ts", "dcw.spec.ts", "a11y.spec.ts"}) {
      assertTrue(Files.exists(frontend().resolve("e2e/" + spec)), "missing e2e spec " + spec);
    }
    assertTrue(Files.exists(frontend().resolve("e2e/fixtures.ts")), "missing e2e fixtures");
    assertTrue(Files.exists(frontend().resolve("e2e/server.mjs")), "missing e2e static server");
    assertTrue(Files.exists(frontend().resolve("playwright.config.ts")), "missing playwright config");
  }

  @Test
  @DisplayName("2. Fixtures alignées sur les vrais endpoints et DTO backend")
  void fixturesCoverRealEndpoints() {
    String fixtures = read("e2e/fixtures.ts");
    for (String endpoint : new String[]{
        "/api/admin/session", "/api/admin/me", "/api/admin/config",
        "/api/admin/users", "/api/admin/config/relay/nodes", "/api/admin/config/relay/caches",
        "/api/admin/config/relay/links", "/api/admin/relay/status",
        "/api/admin/dcw/policy", "/api/admin/dcw/rules", "/api/admin/dcw/mappings",
        "/api/admin/dcw/stats", "/api/admin/dcw/anomalies", "/api/admin/dcw/candidates",
        "/api/admin/cache/status", "/api/admin/cacheex/stats", "/api/admin/config/cw-cycle",
        "/api/admin/catalog"}) {
      assertTrue(fixtures.contains(endpoint), "fixtures must mock " + endpoint);
    }
    // Champs DTO réels (noms exacts backend ; cacheexMaxHop = contrat v2 non
    // encore émis par ce backend, couvert côté specs/pages).
    for (String field : new String[]{"canConsult", "canFeed", "canExport", "connectorName",
        "digestSha256", "sourceSid", "targetSid", "expiresAtMs", "cacheexMode",
        "newcamdPasswordEncoding", "allowedConnectors"}) {
      assertTrue(fixtures.contains(field), "fixtures must use real DTO field " + field);
    }
  }

  @Test
  @DisplayName("3. Specs : assertions de corps (compat API), CSRF, versions, XSS/dialog")
  void specsAssertBodiesCsrfVersionsAndDialogs() {
    String all = Stream.of("auth.spec.ts", "navigation.spec.ts", "crud.spec.ts",
        "connectors.spec.ts", "secrets.spec.ts", "relay.spec.ts", "dcw.spec.ts", "a11y.spec.ts")
        .map(s -> read("e2e/" + s))
        .reduce("", (a, b) -> a + "\n" + b);
    assertTrue(Pattern.compile("X-CSP-Requested-With|x-csp-requested-with").matcher(all).find(),
        "specs must assert CSRF header");
    assertTrue(Pattern.compile("version=\\d|version: \\d").matcher(all).find(),
        "specs must assert optimistic versions");
    assertTrue(all.contains("409"), "specs must cover 409 rollback");
    assertTrue(all.contains("onerror=alert"), "specs must cover XSS payload");
    assertTrue(all.contains("aria-selected"), "specs must cover tab keyboard semantics");
    assertTrue(all.contains("no-store") || all.contains("unsafe-inline"),
        "specs must cover CSP constraints");
  }

  @Test
  @DisplayName("4. Aucun rendu HTML brut (XSS) dans les sources frontend")
  void noRawHtmlRendering() throws Exception {
    Path src = frontend().resolve("src");
    try (Stream<Path> files = Files.walk(src)) {
      for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".tsx"))::iterator) {
        String content = Files.readString(p, StandardCharsets.UTF_8);
        assertFalse(content.contains("dangerouslySetInnerHTML"),
            "raw HTML rendering forbidden in " + src.relativize(p));
      }
    }
  }
}
