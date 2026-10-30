package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stockage web : aucun secret, jeton ou donnée de session dans le navigateur. Seules des
 * préférences d'affichage non sensibles, en liste blanche (fichier + clé), peuvent utiliser
 * localStorage ; sessionStorage et document.cookie sont interdits côté application.
 */
@DisplayName("UI — stockage web restreint à des préférences non sensibles")
class AdminUiWebStorageContractTest {

  /** fichier (relatif à frontend/src) → clés autorisées. */
  private static final Map<String, Set<String>> ALLOWED = Map.of(
      "i18n/core.ts", Set.of("csp.lang"),
      "pages/Users.tsx", Set.of("csp.users.liveStatus"));

  private static final Pattern KEY_LITERAL = Pattern.compile("[\"'](csp\\.[a-zA-Z0-9.]+)[\"']");

  @Test
  void localStorageOnlyForAllowlistedPreferences() throws Exception {
    Path root = FrontendSource.root();
    Map<String, String> offenders = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> s = Files.walk(root)) {
      files = s.filter(p -> p.toString().matches(".*\\.(ts|tsx)$"))
          .filter(p -> !root.relativize(p).toString().replace('\\', '/').startsWith("test/"))
          .toList();
    }
    for (Path p : files) {
      String rel = root.relativize(p).toString().replace('\\', '/');
      String src = Files.readString(p);
      assertFalse(src.contains("sessionStorage"), rel + " must not use sessionStorage");
      assertFalse(src.contains("document.cookie"), rel + " must not touch document.cookie");
      if (!src.contains("localStorage")) continue;
      Set<String> allowed = ALLOWED.get(rel);
      if (allowed == null) {
        offenders.put(rel, "localStorage not allowed in this file");
        continue;
      }
      Matcher m = KEY_LITERAL.matcher(src);
      while (m.find()) {
        if (!allowed.contains(m.group(1))) offenders.put(rel, "unexpected storage key " + m.group(1));
      }
      for (String k : allowed) {
        assertTrue(src.contains(k), rel + " must declare its allowlisted key " + k);
      }
    }
    assertTrue(offenders.isEmpty(), "web storage outside allowlist: " + offenders);
  }

  @Test
  void secretComponentsNeverUseWebStorage() {
    for (String rel : new String[]{"components/SecretField.tsx", "api.ts"}) {
      String src = FrontendSource.raw(rel);
      assertFalse(Pattern.compile("localStorage|sessionStorage").matcher(src).find(), rel + " must not use web storage");
    }
  }
}
