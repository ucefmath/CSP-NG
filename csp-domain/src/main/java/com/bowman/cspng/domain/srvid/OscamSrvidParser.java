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
 * Parseur pur de fichiers oscam.srvid (zéro I/O bloquante, thread-safe).
 *
 * <p>Supporte trois formats :
 * <ol>
 *   <li><b>Canonique</b> : {@code CAID@PROVID[,CAID2@PROVID...]:SID|provider|name|type|desc}</li>
 *   <li><b>Ancien</b> : {@code CAID:PROVID:SID:nom} (4 champs séparés par des deux-points)</li>
 *   <li><b>srvid2</b> : {@code SID:CAID@PROVID[,CAID@PROVID...]|name|type|desc|provider}</li>
 * </ol>
 *
 * Les lignes commençant par {@code #}, {@code ;} ou vides sont ignorées.
 * Les lignes malformées ou excédant la longueur hexadécimale légale sont ignorées sans lever d'exception.
 */
public final class OscamSrvidParser {

  public static final int MAX_LINES = 200_000;

  private OscamSrvidParser() {}

  /**
   * Parse un flux de lignes oscam.srvid et retourne une Map immuable (caid, sid) -> ServiceInfo.
   */
  public static Map<ServiceKey, ServiceInfo> parse(Reader reader) throws IOException {
    Objects.requireNonNull(reader, "reader");
    Map<ServiceKey, ServiceInfo> map = new HashMap<>();
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
  public static Map<ServiceKey, ServiceInfo> parse(InputStream is) throws IOException {
    Objects.requireNonNull(is, "inputStream");
    return parse(new InputStreamReader(is, StandardCharsets.UTF_8));
  }

  /**
   * Parse depuis une chaîne complète.
   */
  public static Map<ServiceKey, ServiceInfo> parse(String content) {
    if (content == null || content.isBlank()) {
      return Map.of();
    }
    try {
      return parse(new StringReader(content));
    } catch (IOException e) {
      return Map.of();
    }
  }

  static void parseLine(String rawLine, Map<ServiceKey, ServiceInfo> out) {
    if (rawLine == null) return;
    String line = rawLine.trim();
    if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || line.startsWith("//")) {
      return;
    }

    int pipeIdx = line.indexOf('|');
    if (pipeIdx >= 0) {
      // Formats avec pipe : Canonique ou srvid2
      String header = line.substring(0, pipeIdx).trim();
      String body = line.substring(pipeIdx + 1);

      int colonIdx = header.indexOf(':');
      if (colonIdx <= 0 || colonIdx >= header.length() - 1) {
        return;
      }

      String partA = header.substring(0, colonIdx).trim();
      String partB = header.substring(colonIdx + 1).trim();

      boolean isSrvid2 = false;
      if (partB.contains("@") || partB.contains(",")) {
        isSrvid2 = true;
      } else if (partA.contains("@") || partA.contains(",")) {
        isSrvid2 = false;
      }

      if (isSrvid2) {
        // srvid2: SID:CAID@PROVID[,CAID...]|name|type|desc|provider
        Integer sid = parseHex(partA, 4);
        if (sid == null) return;

        String[] meta = body.split("\\|", -1);
        String name = meta.length > 0 ? nullIfBlank(meta[0]) : null;
        String type = meta.length > 1 ? nullIfBlank(meta[1]) : null;
        String desc = meta.length > 2 ? nullIfBlank(meta[2]) : null;
        String provider = meta.length > 3 ? nullIfBlank(meta[3]) : null;
        ServiceInfo info = new ServiceInfo(name, provider, type, desc);

        String[] caidSpecs = partB.split(",");
        for (String spec : caidSpecs) {
          Integer caid = parseCaidSpec(spec);
          if (caid != null) {
            out.put(new ServiceKey(caid, sid), info);
          }
        }
      } else {
        // Canonique: CAID@PROVID[,CAID...]:SID|provider|name|type|desc
        Integer sid = parseHex(partB, 4);
        if (sid == null) return;

        String[] meta = body.split("\\|", -1);
        String provider = meta.length > 0 ? nullIfBlank(meta[0]) : null;
        String name = meta.length > 1 ? nullIfBlank(meta[1]) : null;
        String type = meta.length > 2 ? nullIfBlank(meta[2]) : null;
        String desc = meta.length > 3 ? nullIfBlank(meta[3]) : null;
        ServiceInfo info = new ServiceInfo(name, provider, type, desc);

        String[] caidSpecs = partA.split(",");
        for (String spec : caidSpecs) {
          Integer caid = parseCaidSpec(spec);
          if (caid != null) {
            out.put(new ServiceKey(caid, sid), info);
          }
        }
      }
    } else {
      // Ancien format: CAID:PROVID:SID:nom
      String[] parts = line.split(":", 4);
      if (parts.length >= 4) {
        Integer caid = parseHex(parts[0], 4);
        Integer provid = parseHex(parts[1], 6);
        Integer sid = parseHex(parts[2], 4);
        String name = nullIfBlank(parts[3]);
        if (caid != null && provid != null && sid != null && name != null) {
          out.put(new ServiceKey(caid, sid), new ServiceInfo(name, null, null, null));
        }
      }
    }
  }

  private static Integer parseCaidSpec(String spec) {
    if (spec == null) return null;
    String s = spec.trim();
    if (s.isEmpty()) return null;
    int atIdx = s.indexOf('@');
    if (atIdx >= 0) {
      String caidStr = s.substring(0, atIdx).trim();
      String provStr = s.substring(atIdx + 1).trim();
      Integer provid = parseHex(provStr, 6);
      if (provid == null) return null;
      return parseHex(caidStr, 4);
    } else {
      return parseHex(s, 4);
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
