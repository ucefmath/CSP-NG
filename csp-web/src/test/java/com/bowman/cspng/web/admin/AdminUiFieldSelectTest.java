package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sélection accessible, tables filtrables et champs secrets — refonte SPA React
 * (remplace les assertions sur fieldSelect/app.js vanilla et les .mjs legacy, supprimés).
 */
@DisplayName("AdminUiFieldSelectTest — Sélection, tables et secrets (SPA)")
class AdminUiFieldSelectTest {

  private static final Path FRONTEND = Path.of("csp-web/frontend/src");

  private static Path frontend() {
    return Files.exists(FRONTEND) ? FRONTEND : Path.of("frontend/src");
  }

  /** Page + namespaces i18n (les libellés vivent dans i18n/ns/*.ts depuis v1.0.2). */
  private static String read(String rel) {
    return FrontendSource.withI18n(rel);
  }

  @Test
  @DisplayName("1. Sélection avec labels : cases à cocher labellisées sur chaque table + bulk")
  void selectionUsesAccessibleLabels() {
    String bulk = read("components/BulkBar.tsx");
    assertTrue(Pattern.compile("aria-label").matcher(bulk).find(),
        "SelectBox must expose an accessible label");
    for (String page : new String[]{"pages/Profiles.tsx", "pages/Listeners.tsx",
        "pages/Connectors.tsx", "pages/Users.tsx"}) {
      String src = read(page);
      assertTrue(Pattern.compile("Sélectionner ").matcher(src).find(),
          page + " must label each selection checkbox");
      assertTrue(src.contains("BulkBar"), page + " must offer grouped bulk edit");
    }
  }

  @Test
  @DisplayName("2. Tables filtrables : recherche sur profils/connecteurs/proxys/users")
  void tablesAreFilterable() {
    assertTrue(read("pages/Profiles.tsx").contains("Rechercher un profil"), "profiles filter missing");
    assertTrue(read("pages/Connectors.tsx").contains("Rechercher un connecteur"), "connectors filter missing");
    assertTrue(read("pages/Proxys.tsx").contains("Rechercher un proxy"), "proxys filter missing");
    assertTrue(read("pages/Users.tsx").contains("Rechercher un utilisateur"), "users filter missing");
  }

  @Test
  @DisplayName("3. Secrets : valeur directe ou file:, révélation volontaire, jamais de GET valeur")
  void secretsNeverEchoedAndRevealIsExplicit() {
    String secret = read("components/SecretField.tsx");
    assertTrue(secret.contains("/api/admin/secrets/materialize"), "materialize endpoint missing");
    assertTrue(Pattern.compile("Révéler|revealed").matcher(secret).find(),
        "secret reveal must be explicit (opt-in)");
    assertFalse(Pattern.compile("localStorage|sessionStorage").matcher(secret).find(),
        "no secret in web storage");
    // Refs existantes env:/file: conservées (mode référence par défaut)
    assertTrue(secret.contains("env:") && secret.contains("file:"), "env:/file: refs must be supported");
  }

  @Test
  @DisplayName("4. Bulk : inchangé/valeur/réinitialisation, résumé, versions par ligne")
  void bulkSupportsUnchangedModeAndConfirmSummary() {
    String bulk = read("components/BulkBar.tsx");
    assertTrue(bulk.contains("Inchangé"), "bulk must support unchanged mode");
    assertTrue(bulk.contains("Réinitialiser"), "bulk must offer explicit reset on NULLABLE columns");
    assertTrue(bulk.contains("Résumé de confirmation"), "bulk must show a confirmation summary");
    assertTrue(Pattern.compile("version").matcher(bulk).find(), "bulk items must carry per-row version");
    assertTrue(Pattern.compile("/bulk").matcher(bulk).find(), "bulk must call PUT …/bulk");
    // Sémantique backend (appendBulkSet, contrat v2 §2) : absent = inchangé,
    // présent + null = reset UNIQUEMENT si resettable (colonne NULLABLE),
    // ensembles = remplacement complet. Aucune sentinelle texte magique.
    assertTrue(bulk.contains("resettable"), "reset must be gated per-field (NULLABLE only)");
    assertTrue(bulk.contains("multiselect"), "user sets must use accessible multiselect replace");
  }

  @Test
  @DisplayName("5. ACL users : chips labellisées + services par profil (hex/décimal)")
  void userAclChipsAndServiceMaps() {
    String users = read("pages/Users.tsx");
    assertTrue(users.contains("allowedConnectors"), "allowedConnectors chips missing");
    assertTrue(Pattern.compile("<fieldset|<legend|legend").matcher(users).find(),
        "ACL groups must use fieldset/legend");
    assertTrue(users.contains("parseSidList") || users.contains("0x"), "SID hex/decimal parsing missing");
  }
}
