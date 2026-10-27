package com.bowman.cspng.config.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parser pur pour fichiers {@code cccam.cfg} (lignes C: et N:).
 *
 * <p>Testable en isolation, sans dépendance Vert.x/CDI.
 *
 * <p>Robustesse : gère CRLF, trim trailing, multiple spaces.
 */
public final class CccamCfgParser {

  private CccamCfgParser() {}

  public enum Type { C, N }

  public record CaidProviderDecl(int caId, int providerIdent) {}

  public record ParsedLine(Type type, int lineNumber, String host, int port,
                           String username, String password, byte[] desKey14,
                           List<CaidProviderDecl> caidProviderDecls) {
    public ParsedLine {
      if (desKey14 != null) desKey14 = desKey14.clone();
      caidProviderDecls = caidProviderDecls == null ? List.of() : List.copyOf(caidProviderDecls);
    }
    // Compatibility overload for existing call sites (no braces => empty decls)
    public ParsedLine(Type type, int lineNumber, String host, int port,
                      String username, String password, byte[] desKey14) {
      this(type, lineNumber, host, port, username, password, desKey14, List.of());
    }
    @Override
    public byte[] desKey14() {
      return desKey14 == null ? null : desKey14.clone();
    }
    @Override
    public String toString() {
      return "ParsedLine[type=" + type + ", lineNumber=" + lineNumber + ", host=" + host
          + ", port=" + port + ", username=" + username + ", password=***"
          + ", desKey14=" + (desKey14 == null ? "null" : "[REDACTED]")
          + ", caidProviderDecls=" + caidProviderDecls + "]";
    }
  }

  public record ParseError(int lineNumber, String rawLine, String reason) {}

  private static String redactedC() { return "C: [REDACTED]"; }
  private static String redactedN() { return "N: [REDACTED]"; }

  public record ParseResult(List<ParsedLine> parsedLines,
                            List<String> ignoredLines,
                            List<ParseError> errors) {}

  private static final Pattern HEX2 = Pattern.compile("^[0-9A-Fa-f]{2}$");

  public static ParseResult parse(String content) {
    if (content == null) content = "";
    List<ParsedLine> parsed = new ArrayList<>();
    List<String> ignored = new ArrayList<>();
    List<ParseError> errors = new ArrayList<>();

    // Split preserving line numbers. Handle both LF and CRLF.
    // Use \n as delimiter, then strip \r from each line.
    String[] lines = content.split("\n", -1);
    // If content was empty, split gives [""] -> we handle as 0 parsed
    boolean isEmptyContent = content.isEmpty();

    int lineNumber = 0;
    for (String raw : lines) {
      lineNumber++;
      // In case of empty content, don't count phantom line
      if (isEmptyContent && lines.length == 1 && raw.isEmpty()) {
        break;
      }
      // Remove trailing \r from CRLF, but keep original raw for errors
      String line = raw;
      if (line.endsWith("\r")) {
        line = line.substring(0, line.length() - 1);
      }
      String trimmed = line.trim();
      // Trim trailing spaces BEFORE splitting (already trimmed), and tolerate multiple spaces
      if (trimmed.isEmpty()) {
        ignored.add(line);
        continue;
      }
      if (trimmed.startsWith("#")) {
        ignored.add(line);
        continue;
      }
      // Check prefix C: or N: after trim (case-sensitive)
      if (trimmed.startsWith("C:")) {
        parseCLine(line, trimmed, lineNumber, parsed, errors);
      } else if (trimmed.startsWith("N:")) {
        parseNLine(line, trimmed, lineNumber, parsed, errors);
      } else {
        // F:, R:, L:, G:, B:, SERVER LISTEN PORT :, DEBUG :, etc.
        ignored.add(line);
      }
    }

    return new ParseResult(Collections.unmodifiableList(parsed),
        Collections.unmodifiableList(ignored),
        Collections.unmodifiableList(errors));
  }

  private static void parseCLine(String raw, String trimmed, int lineNumber,
                                 List<ParsedLine> parsed, List<ParseError> errors) {
    String after = trimmed.substring(2).trim();
    // Detect brace block
    int braceStart = after.indexOf('{');
    int braceEnd = braceStart != -1 ? after.indexOf('}', braceStart) : -1;
    String prefixPart;
    String braceContent = null;
    boolean hasBrace = false;
    if (braceStart != -1) {
      hasBrace = true;
      if (braceEnd == -1) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: brace '{' without closing '}'"));
        return;
      }
      // Reject any non-whitespace, non-comment content after closing brace
      // (commentaire inline '#' après '}' accepté — hors accolades, après champs obligatoires)
      String trailing = after.substring(braceEnd + 1).trim();
      if (!trailing.isEmpty() && !trailing.startsWith("#")) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: unexpected content after '}' (PARSE_ERROR)"));
        return;
      }
      prefixPart = after.substring(0, braceStart).trim();
      braceContent = after.substring(braceStart + 1, braceEnd);
    } else {
      prefixPart = after;
    }

    // Split prefixPart by whitespace (multiple spaces tolerated)
    String[] tokens = prefixPart.isEmpty() ? new String[0] : prefixPart.split("\\s+");
    // Commentaire inline APRÈS les champs obligatoires (host port user pass) :
    // le premier token d'index >= 4 commençant par '#' démarre un commentaire — tout le
    // reste est ignoré. Les 4 premiers tokens sont INTACTS : les mots de passe '#foo' et
    // 'pa#ss' sont préservés (aucun strip naïf du '#').
    int commentIdx = -1;
    for (int i = 4; i < tokens.length; i++) {
      if (tokens[i].startsWith("#")) { commentIdx = i; break; }
    }
    if (commentIdx != -1) {
      tokens = java.util.Arrays.copyOf(tokens, commentIdx);
    }
    // Format canonique C: <host> <port> <user> <pass> [wantemus] [{...}]
    // Le port est désormais TOUJOURS obligatoire et explicite (1-65535)
    if (tokens.length < 4) {
      errors.add(new ParseError(lineNumber, redactedC(), "C: port manquant ou invalide, format attendu: C: host port user pass [...]"));
      return;
    }

    String host = tokens[0];
    String portStr = tokens[1];
    int port;
    try {
      port = Integer.parseInt(portStr);
      if (port < 1 || port > 65535) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      errors.add(new ParseError(lineNumber, redactedC(), "C: port manquant ou invalide, format attendu: C: host port user pass [...]"));
      return;
    }
    String user = tokens[2];
    String pass = tokens[3];
    String wantemus = tokens.length >= 5 ? tokens[4] : null;

    if (host.isBlank() || user.isBlank() || pass.isBlank()) {
      errors.add(new ParseError(lineNumber, redactedC(), "C: blank host/user/pass"));
      return;
    }

    if (hasBrace) {
      if (wantemus == null) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: '{' present without wantemus yes/no before it (PARSE_ERROR)"));
        return;
      }
      if (!isWantemus(wantemus)) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: wantemus must be yes/no before '{'"));
        return;
      }
    } else {
      if (wantemus != null && !isWantemus(wantemus)) {
        // If 5th token not yes/no and no brace, treat as extra ignored? But strict: if extra token not yes/no, ignore?
        // For MVP, if extra token not yes/no, it's considered invalid wantemus → error
        // However champs additionnels ignorés after pass? Spec says wantemus is yes|no, so if present must be yes/no
        errors.add(new ParseError(lineNumber, redactedC(), "C: wantemus must be yes/no"));
        return;
      }
    }

    List<CaidProviderDecl> decls = List.of();
    if (hasBrace) {
      List<CaidProviderDecl> parsedDecls = parseBraceContent(braceContent, lineNumber, raw, errors);
      if (parsedDecls == null) return; // error already added
      decls = parsedDecls;
    }

    parsed.add(new ParsedLine(Type.C, lineNumber, host, port, user, pass, null, decls));
  }

  private static void parseNLine(String raw, String trimmed, int lineNumber,
                                 List<ParsedLine> parsed, List<ParseError> errors) {
    String after = trimmed.substring(2).trim();
    String[] tokens = after.isEmpty() ? new String[0] : after.split("\\s+");

    // Need at least host port user pass + 14 hex tokens = 18
    if (tokens.length < 18) {
      // Check if failure is due to missing DES key length
      if (tokens.length >= 4) {
        // Count hex tokens after first 4
        int hexCount = 0;
        for (int i = 4; i < tokens.length; i++) {
          if (HEX2.matcher(tokens[i]).matches()) hexCount++;
          else break;
        }
        if (hexCount != 14) {
          errors.add(new ParseError(lineNumber, redactedN(), "N: DES key must be exactly 14 hex bytes, got " + hexCount + " (PARSE_ERROR)"));
          return;
        }
      }
      errors.add(new ParseError(lineNumber, redactedN(), "N: missing fields (need host port user pass +14 DES bytes)"));
      return;
    }

    String host = tokens[0];
    String portStr = tokens[1];
    String user = tokens[2];
    String pass = tokens[3];

    int port;
    try {
      port = Integer.parseInt(portStr);
      if (port < 1 || port > 65535) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      errors.add(new ParseError(lineNumber, redactedN(), "N: invalid port"));
      return;
    }

    // Next 14 tokens must be hex bytes
    byte[] desKey = new byte[14];
    for (int i = 0; i < 14; i++) {
      String tok = tokens[4 + i];
      if (!HEX2.matcher(tok).matches()) {
        errors.add(new ParseError(lineNumber, redactedN(), "N: DES key token invalid hex: " + tok + " (PARSE_ERROR)"));
        return;
      }
      desKey[i] = (byte) Integer.parseInt(tok, 16);
    }

    // Remaining tokens [hop offset] [stealth] are IGNORED (documented MVP)
    if (host.isBlank() || user.isBlank() || pass.isBlank()) {
      errors.add(new ParseError(lineNumber, redactedN(), "N: blank host/user/pass"));
      return;
    }

    parsed.add(new ParsedLine(Type.N, lineNumber, host, port, user, pass, desKey));
  }

  private static List<CaidProviderDecl> parseBraceContent(String braceContent, int lineNumber, String raw, List<ParseError> errors) {
    String trimmed = braceContent == null ? "" : braceContent.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    String[] parts = trimmed.split(",");
    List<CaidProviderDecl> result = new ArrayList<>();
    for (String part : parts) {
      String decl = part.trim();
      if (decl.isEmpty()) continue;
      String[] fields = decl.split(":");
      if (fields.length < 2 || fields.length > 3) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: malformed CAID/provider declaration '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      String caidStr = fields[0].trim();
      String provStr = fields[1].trim();
      // hop ignored if present fields[2]
      if (caidStr.isEmpty() || provStr.isEmpty()) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: malformed CAID/provider declaration '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      // Hex validation: must be 1-4 hex for caId, 1-6 for provider, only hex chars
      if (!isHex(caidStr) || !isHex(provStr)) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: invalid hex in CAID/provider '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      if (caidStr.length() > 4 || provStr.length() > 6) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: CAID/provider out of range '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      int caId;
      int prov;
      try {
        caId = Integer.parseInt(caidStr, 16);
        prov = Integer.parseInt(provStr, 16);
      } catch (NumberFormatException e) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: invalid hex in CAID/provider '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      if (caId < 0 || caId > 0xFFFF) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: CAID out of range 0x0000..0xFFFF '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      if (prov < 0 || prov > 0xFFFFFF) {
        errors.add(new ParseError(lineNumber, redactedC(), "C: provider out of range 0x000000..0xFFFFFF '" + sanitizeBraceToken(decl) + "' (PARSE_ERROR)"));
        return null;
      }
      result.add(new CaidProviderDecl(caId, prov));
    }
    return List.copyOf(result);
  }

  private static boolean isHex(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
    }
    return true;
  }

  private static String sanitizeBraceToken(String token) {
    // token is from brace content, never contains password, but sanitize anyway truncated
    String t = token.replaceAll("[\\r\\n]+", " ").trim();
    if (t.length() > 40) t = t.substring(0, 40);
    return t;
  }

  private static boolean isWantemus(String s) {
    return "yes".equalsIgnoreCase(s) || "no".equalsIgnoreCase(s);
  }
}
