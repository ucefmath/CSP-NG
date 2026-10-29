package com.bowman.cspng.web.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Lecture des sources frontend pour les tests de contrat UI. Depuis l'i18n (v1.0.2), les libellés
 * ne sont plus en dur dans les pages : chaque fichier est lu avec son ou ses namespaces de
 * traduction ({@code pages/Profiles.tsx} → {@code i18n/ns/profiles.ts}), afin que les contrats
 * portent sur le comportement et les libellés réellement affichés.
 */
final class FrontendSource {

  private static final Path FRONTEND = Path.of("csp-web/frontend/src");

  private FrontendSource() {}

  static Path root() {
    return Files.exists(FRONTEND) ? FRONTEND : Path.of("frontend/src");
  }

  /** Source brute, sans traductions. */
  static String raw(String rel) {
    try {
      return Files.readString(root().resolve(rel), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("cannot read frontend/src/" + rel + ": " + e.getMessage(), e);
    }
  }

  /** Source + namespaces i18n associés (concaténés). */
  static String withI18n(String rel) {
    StringBuilder sb = new StringBuilder(raw(rel));
    for (String ns : namespacesFor(rel)) {
      Path p = root().resolve("i18n/ns/" + ns + ".ts");
      if (Files.exists(p)) sb.append("\n/* i18n:").append(ns).append(" */\n").append(raw("i18n/ns/" + ns + ".ts"));
    }
    return sb.toString();
  }

  static Set<String> namespacesFor(String rel) {
    Set<String> out = new LinkedHashSet<>();
    String file = rel.substring(rel.lastIndexOf('/') + 1).replaceFirst("\\.tsx?$", "");
    if (rel.startsWith("pages/")) {
      out.add(Character.toLowerCase(file.charAt(0)) + file.substring(1));
    } else if (rel.equals("components/ui.tsx")) {
      out.add("shell");
      out.add("components");
    } else if (rel.startsWith("components/")) {
      out.add("components");
    }
    return out;
  }
}
