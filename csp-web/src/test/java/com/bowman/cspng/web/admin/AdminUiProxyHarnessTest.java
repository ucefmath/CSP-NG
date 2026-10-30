package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness proxys + connecteurs — refonte SPA React (remplace les assertions sur
 * l'ancien app.js vanilla, supprimé). Vérifie le WIRING complet côté sources :
 * navigation groupée, routes, dialogues, endpoints REST et garde-fous secrets.
 */
@DisplayName("AdminUiProxyHarnessTest — Proxys et connecteurs (SPA)")
class AdminUiProxyHarnessTest {

  private static final Path FRONTEND = Path.of("csp-web/frontend/src");

  private static Path frontend() {
    return Files.exists(FRONTEND) ? FRONTEND : Path.of("frontend/src");
  }

  /** Page + namespaces i18n (les libellés vivent dans i18n/ns/*.ts depuis v1.0.2). */
  private static String read(String rel) {
    return FrontendSource.withI18n(rel);
  }

  @Test
  @DisplayName("1. Navigation groupée : proxys + 12 routes legacy + nouveaux écrans")
  void navGroupsContainAllRoutes() {
    String ui = read("components/ui.tsx");
    String router = read("router.tsx");
    for (String r : new String[]{"overview", "general", "cache", "cluster", "profiles",
        "listeners", "catalog", "connectors", "proxys", "relais", "dcw", "users"}) {
      assertTrue(router.contains("\"" + r + "\""), "router must declare " + r);
    }
    for (String r : new String[]{"cache-etat", "cacheex", "cw-cycle"}) {
      assertTrue(router.contains("\"" + r + "\""), "router must declare new screen " + r);
    }
    assertTrue(ui.contains("Proxys"), "grouped nav must contain Proxys");
    assertTrue(Pattern.compile("<nav[^>]*aria-label=\\{t\\(\"shell\\.mainNav\"\\)\\}").matcher(ui).find()
        || ui.contains("aria-label=\"Navigation principale\""), "nav must be labelled");
    assertTrue(ui.contains("Navigation principale") && ui.contains("Main navigation"),
        "nav label must be translated (FR/EN)");
  }

  @Test
  @DisplayName("2. Dialogue proxy : auth appariée, refs seules, nom immuable, kill switch")
  void proxyDialogValidatesAuthPairingAndSecretRefsOnly() {
    String proxys = read("pages/Proxys.tsx");
    assertTrue(proxys.contains("username et passwordRef"), "auth pairing validation missing");
    assertTrue(proxys.contains("env: ou file: requis"), "passwordRef ref-type validation missing");
    assertTrue(Pattern.compile("disabled=\\{isEdit\\}").matcher(proxys).find(),
        "proxy name must be disabled in edit mode (immutable)");
    assertTrue(proxys.contains("AUCUN repli en connexion directe"), "kill-switch warning missing");
    assertTrue(proxys.contains("PROXY_IN_USE"), "409 PROXY_IN_USE handling missing");
    assertTrue(proxys.contains("connectorCount"), "connectorCount usage missing");
    // Aucun champ mot de passe en clair : SecretField (matérialisation serveur) uniquement
    assertTrue(proxys.contains("SecretField"), "proxy dialog must use SecretField");
    assertFalse(Pattern.compile("type=\"password\"").matcher(proxys).find(),
        "proxy dialog must NOT have a cleartext password field");
  }

  @Test
  @DisplayName("3. Dialogue connecteur : proxy direct/SOCKS5, secrets refs, CacheEx asymétrique, cs378x")
  void connectorDialogHasProxySelectorSecretsAndCacheex() {
    String conn = read("pages/Connectors.tsx");
    assertTrue(conn.contains("Connexion directe"), "direct connection option missing");
    assertTrue(conn.contains("cacheexMode"), "cacheexMode missing");
    assertTrue(Pattern.compile("\"2.*r[ée]ception|mode 2").matcher(conn).find(), "mode 2 = reception missing");
    assertTrue(Pattern.compile("mode 3.*[ée]mission|3.*émission").matcher(conn).find(), "mode 3 = emission missing");
    assertTrue(conn.contains("cacheexMaxSend"), "cacheexMaxSend (file bound) missing");
    assertTrue(conn.contains("cacheexMaxHop"), "cacheexMaxHop missing");
    assertTrue(conn.contains("cs378x"), "cs378x protocol missing");
    assertTrue(conn.contains("SecretField"), "connector secrets must use SecretField");
    assertTrue(conn.contains("C-line") || conn.contains("C: "), "C-line import missing");
    assertTrue(Pattern.compile("env:|file:").matcher(conn).find(), "secret ref validation missing");
  }

  @Test
  @DisplayName("4. Endpoints REST appelés (proxys + connecteurs + bulk + materialize)")
  void uiCallsMatchingRestEndpoints() {
    String proxys = read("pages/Proxys.tsx");
    assertTrue(proxys.contains("/api/admin/config/proxies"), "proxies endpoint missing");
    String conn = read("pages/Connectors.tsx");
    assertTrue(conn.contains("/api/admin/config/connectors/"), "connector upsert endpoint missing");
    assertTrue(conn.contains("/api/admin/secrets/materialize"), "C-line import must materialize password server-side");
    String bulk = read("components/BulkBar.tsx");
    assertTrue(Pattern.compile("/api/admin/config/\\$\\{resource\\}/bulk").matcher(bulk).find()
        || bulk.contains("/bulk"), "bulk endpoint missing");
  }

  @Test
  @DisplayName("5. Client API : cookie même origine + CSRF sur mutations, erreurs 409/426/403")
  void apiClientPreservesCookieAndCsrf() {
    String api = read("api.ts");
    assertTrue(api.contains("same-origin"), "same-origin credentials missing");
    assertTrue(api.contains("X-CSP-Requested-With"), "CSRF header missing");
    assertTrue(api.contains("CSP-WebUI"), "CSRF value missing");
    assertTrue(api.contains("409"), "409 conflict mapping missing");
    assertTrue(api.contains("426"), "426 TLS mapping missing");
    assertTrue(api.contains("403"), "403 mapping missing");
    assertFalse(Pattern.compile("localStorage|sessionStorage").matcher(api).find(),
        "no secret in web storage");
  }
}
