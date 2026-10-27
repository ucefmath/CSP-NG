package com.bowman.cspng.domain.srvid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Parseur pur de fichiers oscam.provid (zéro I/O bloquante, thread-safe).
 *
 * <p>Format standard OSCam : {@code <caid>:<provid>|<provider>|<satellite>|<language>}
 * <br>Exemple : {@code 0100:000068|Cyfra+ (13E)||}
 */
public final class OscamProvidParser {

  public static final int MAX_LINES = 200_000;

  private OscamProvidParser() {}

  /**
   * Parse un flux de lignes oscam.provid et retourne une Map immuable (caid, provid) -> ProviderInfo.
   */
  public static Map<ProviderKey, ProviderInfo> parse(Reader reader) throws IOException {
    Objects.requireNonNull(reader, "reader");
    Map<ProviderKey, ProviderInfo> map = new HashMap<>();
    try (BufferedReader br = new BufferedReader(reader)) {
      String line;
      int lineCount = 0;
      while ((line = br.readLine()) != null) {
        if (++lineCount > MAX_LINES) {
          break;
        }
        parseLine(line, map);
      }
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Parse depuis un InputStream (UTF-8).
   */
  public static Map<ProviderKey, ProviderInfo> parse(InputStream is) throws IOException {
    Objects.requireNonNull(is, "inputStream");
    return parse(new InputStreamReader(is, StandardCharsets.UTF_8));
  }

  /**
   * Parse depuis une chaîne complète.
   */
  public static Map<ProviderKey, ProviderInfo> parse(String content) {
    if (content == null || content.isBlank()) {
      return Map.of();
    }
    try {
      return parse(new StringReader(content));
    } catch (IOException e) {
      return Map.of();
    }
  }

  static void parseLine(String rawLine, Map<ProviderKey, ProviderInfo> out) {
    if (rawLine == null) return;
    String line = rawLine.trim();
    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) {
      return;
    }

    int pipeIdx = line.indexOf('|');
    if (pipeIdx <= 0) {
      return;
    }

    String header = line.substring(0, pipeIdx).trim();
    String body = line.substring(pipeIdx + 1);

    int colonIdx = header.indexOf(':');
    if (colonIdx <= 0 || colonIdx >= header.length() - 1) {
      return;
    }

    Integer caid = parseHex(header.substring(0, colonIdx), 4);
    Integer provid = parseHex(header.substring(colonIdx + 1), 6);
    if (caid == null || provid == null) {
      return;
    }

    String[] meta = body.split("\\|", -1);
    String provider = meta.length > 0 ? nullIfBlank(meta[0]) : null;
    String satellite = meta.length > 1 ? nullIfBlank(meta[1]) : null;
    String language = meta.length > 2 ? nullIfBlank(meta[2]) : null;

    if (provider != null || satellite != null || language != null) {
      out.put(new ProviderKey(caid, provid), new ProviderInfo(provider, satellite, language));
    }
  }

  private static Integer parseHex(String hexStr, int maxHexLen) {
    if (hexStr == null) return null;
    String clean = hexStr.trim();
    if (clean.startsWith("0x") || clean.startsWith("0X")) {
      clean = clean.substring(2);
    }
    if (clean.isEmpty() || clean.length() > maxHexLen) return null;
    try {
      return Integer.parseInt(clean, 16);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String nullIfBlank(String s) {
    if (s == null) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
