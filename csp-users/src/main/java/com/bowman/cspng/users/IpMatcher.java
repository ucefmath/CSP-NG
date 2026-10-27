package com.bowman.cspng.users;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Matcher IP pour ipMask user : support IPv4/IPv6 exact, wildcard legacy, CIDR fail-closed.
 * Aucun DNS : parsing littéral uniquement.
 * - "*" => tous
 * - exact "10.0.0.1"
 * - wildcard "192.168.*" ou "192.168.1.*" uniquement terminal après '.'
 * - CIDR "10.0.0.0/8", "2001:db8::/32"
 * mask vide => false (appelant gère blank comme absence de restriction).
 * Toute syntaxe invalide => false (fail-closed).
 */
public final class IpMatcher {

  private static final Logger LOG = Logger.getLogger(IpMatcher.class.getName());

  private IpMatcher() {}

  /**
   * Validates an IP mask string without DNS resolution.
   * Accepted formats: "*", exact IP literal (IPv4 or IPv6), CIDR, wildcard (e.g., "192.168.*").
   * Returns true if the mask is syntactically valid.
   */
  public static boolean isValidMask(String mask) {
    if (mask == null) return false;
    mask = mask.trim();
    if (mask.isEmpty()) return true; // empty = no restriction, valid
    if ("*".equals(mask)) return true;

    // CIDR
    if (mask.contains("/")) {
      try {
        String[] parts = mask.split("/", 2);
        if (parts.length != 2) return false;
        String netStr = parts[0].trim();
        String prefixStr = parts[1].trim();
        if (netStr.isEmpty() || prefixStr.isEmpty()) return false;
        int prefixLen = Integer.parseInt(prefixStr);
        byte[] netBytes = parseIpLiteral(netStr);
        if (netBytes == null) return false;
        int maxPrefix = netBytes.length * 8;
        if (prefixLen < 0 || prefixLen > maxPrefix) return false;
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }

    // wildcard
    if (mask.contains("*")) {
      return isStrictWildcard(mask);
    }

    // exact IP literal
    return parseIpLiteral(mask) != null;
  }

  public static boolean isAllowed(String remoteIp, String mask) {
    if (remoteIp == null || mask == null) return false;
    mask = mask.trim();
    remoteIp = remoteIp.trim();
    if (mask.isEmpty()) return false;
    if ("*".equals(mask)) return true;
    if (remoteIp.isEmpty()) return false;

    byte[] remoteBytes = parseIpLiteral(remoteIp);
    if (remoteBytes == null) return false; // hostname / forme abrégée -> deny

    // exact (littéral)
    if (mask.equals(remoteIp)) return true;

    // CIDR
    if (mask.contains("/")) {
      try {
        String[] parts = mask.split("/", 2);
        if (parts.length != 2) return false;
        String netStr = parts[0].trim();
        String prefixStr = parts[1].trim();
        if (netStr.isEmpty() || prefixStr.isEmpty()) return false;
        int prefixLen = Integer.parseInt(prefixStr);
        byte[] netBytes = parseIpLiteral(netStr);
        if (netBytes == null) return false;
        if (remoteBytes.length != netBytes.length) return false;
        int maxPrefix = remoteBytes.length * 8;
        if (prefixLen < 0 || prefixLen > maxPrefix) return false;
        return matchesPrefix(remoteBytes, netBytes, prefixLen);
      } catch (NumberFormatException e) {
        LOG.log(Level.FINE, "Invalid CIDR prefix — deny");
        return false;
      } catch (Exception e) {
        LOG.log(Level.FINE, "Invalid CIDR syntax — deny");
        return false;
      }
    }
    // wildcard : uniquement terminal après '.'
    if (mask.contains("*")) {
      if (!isStrictWildcard(mask)) {
        LOG.log(Level.FINE, "Invalid wildcard syntax — deny");
        return false;
      }
      String prefix = mask.substring(0, mask.indexOf('*'));
      // remote déjà validé littéral IPv4, vérif préfixe
      return remoteIp.startsWith(prefix);
    }
    return false;
  }

  private static boolean isStrictWildcard(String mask) {
    int firstStar = mask.indexOf('*');
    int lastStar = mask.lastIndexOf('*');
    if (firstStar != lastStar) return false; // multiples étoiles
    if (firstStar != mask.length() - 1) return false; // pas terminal
    if (firstStar == 0) return false; // "*" déjà traité, mais "*x" interdit
    if (mask.charAt(firstStar - 1) != '.') return false; // doit être après '.'
    String prefix = mask.substring(0, firstStar);
    // prefix doit être 1-3 octets valides terminés par '.'
    if (!prefix.endsWith(".")) return false;
    String withoutDot = prefix.substring(0, prefix.length() - 1);
    if (withoutDot.isEmpty()) return false;
    String[] parts = withoutDot.split("\\.", -1);
    if (parts.length < 1 || parts.length > 3) return false;
    for (String p : parts) {
      if (p.isEmpty()) return false;
      if (!p.matches("\\d{1,3}")) return false;
      try {
        int v = Integer.parseInt(p);
        if (v < 0 || v > 255) return false;
      } catch (NumberFormatException e) { return false; }
    }
    // pas de caractères après * déjà vérifié, et prefix uniquement digits et '.'
    return true;
  }

  static byte[] parseIpLiteral(String s) {
    if (s == null) return null;
    s = s.trim();
    if (s.isEmpty() || s.contains("%")) return null; // scope id interdit
    if (s.contains(":")) {
      // IPv6 littéral — validation stricte hex/:/. uniquement
      if (!s.matches("[0-9a-fA-F:.]+")) return null;
      if (s.contains(":::")) return null;
      // au plus un "::"
      int firstDouble = s.indexOf("::");
      if (firstDouble != -1 && s.indexOf("::", firstDouble + 2) != -1) return null;
      // hostname avec lettres g-z déjà exclu par regex
      return parseIPv6Strict(s);
    } else if (s.contains(".")) {
      return parseIPv4Strict(s);
    } else {
      return null; // hostname
    }
  }

  private static byte[] parseIPv4Strict(String s) {
    if (s == null) return null;
    String[] parts = s.split("\\.", -1);
    if (parts.length != 4) return null;
    byte[] out = new byte[4];
    for (int i = 0; i < 4; i++) {
      String p = parts[i];
      if (p.isEmpty() || p.length() > 3) return null;
      if (!p.matches("\\d+")) return null;
      // rejet forme abrégée non : déjà 4 parts, mais vérifier octal/hex non autorisé
      // autoriser 0-255, y compris leading zero strict (ex "001" ok si valeur <256)
      try {
        int v = Integer.parseInt(p);
        if (v < 0 || v > 255) return null;
        out[i] = (byte) v;
      } catch (NumberFormatException e) { return null; }
    }
    return out;
  }

  private static byte[] parseIPv6Strict(String s) {
    if (s.contains(".")) {
      // Forme avec suffixe IPv4 (IPv4-mapped / IPv4-compatible)
      int lastColon = s.lastIndexOf(':');
      if (lastColon < 0) return null;
      // seul le dernier token (après le dernier ':') peut contenir des '.'
      if (s.lastIndexOf('.', lastColon - 1) >= 0) return null;
      String dotted = s.substring(lastColon + 1);
      byte[] v4 = parseIPv4Strict(dotted);
      if (v4 == null) return null;
      String hexPart = s.substring(0, lastColon);
      String w1 = String.format("%02x%02x", v4[0] & 0xff, v4[1] & 0xff);
      String w2 = String.format("%02x%02x", v4[2] & 0xff, v4[3] & 0xff);
      String combined = hexPart.isEmpty() ? (w1 + ":" + w2) : (hexPart + ":" + w1 + ":" + w2);
      return parsePureIPv6(combined);
    }
    return parsePureIPv6(s);
  }

  private static byte[] parsePureIPv6(String s) {
    if (s.isEmpty()) return null;
    int doubleColon = s.indexOf("::");
    boolean hasCompression = doubleColon >= 0;
    if (hasCompression && s.indexOf("::", doubleColon + 2) >= 0) return null;

    String head = hasCompression ? s.substring(0, doubleColon) : s;
    String tail = hasCompression ? s.substring(doubleColon + 2) : "";
    String[] headParts = head.isEmpty() ? new String[0] : head.split(":", -1);
    String[] tailParts = tail.isEmpty() ? new String[0] : tail.split(":", -1);
    for (String p : headParts) if (p.isEmpty()) return null;
    for (String p : tailParts) if (p.isEmpty()) return null;

    int explicit = headParts.length + tailParts.length;
    int missing = 8 - explicit;
    if (hasCompression) {
      if (missing <= 0) return null;
    } else {
      if (missing != 0) return null;
    }

    byte[] out = new byte[16];
    int pos = 0;
    for (String p : headParts) {
      int v = parseHexGroup(p);
      if (v < 0) return null;
      out[pos++] = (byte) (v >>> 8);
      out[pos++] = (byte) v;
    }
    for (int z = 0; z < missing; z++) {
      out[pos++] = 0;
      out[pos++] = 0;
    }
    for (String p : tailParts) {
      int v = parseHexGroup(p);
      if (v < 0) return null;
      out[pos++] = (byte) (v >>> 8);
      out[pos++] = (byte) v;
    }
    return pos == 16 ? out : null;
  }

  private static int parseHexGroup(String g) {
    if (g == null || g.isEmpty() || g.length() > 4) return -1;
    try {
      return Integer.parseInt(g, 16);
    } catch (NumberFormatException e) { return -1; }
  }

  private static boolean matchesPrefix(byte[] remote, byte[] net, int prefixLen) {
    int fullBytes = prefixLen / 8;
    int remainingBits = prefixLen % 8;
    for (int i = 0; i < fullBytes; i++) {
      if (remote[i] != net[i]) return false;
    }
    if (remainingBits > 0) {
      int mask = (0xFF << (8 - remainingBits)) & 0xFF;
      if ((remote[fullBytes] & mask) != (net[fullBytes] & mask)) return false;
    }
    return true;
  }
}
