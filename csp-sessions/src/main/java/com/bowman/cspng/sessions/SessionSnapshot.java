package com.bowman.cspng.sessions;

import java.net.InetAddress;

/**
 * Snapshot immuable SANITISÉ d'une session.
 * Aucun mot de passe/token/CW/ECM/provider ACL n'est exposé.
 */
public record SessionSnapshot(
    String id,
    String username,
    String remoteAddress,
    String protocol,
    String profile,
    boolean authenticated,
    boolean alive) {

  /**
   * Anonymise une adresse IP sans DNS : gère IPv4:port et [IPv6]:port,
   * parse IPv4 manuellement, IPv6 via allowlist hex/colon/dot + colon requis
   * avant tout appel InetAddress (aucun hostname résolu).
   * Output /24 et /64, malformed/hostname -> "***".
   */
  public static String anonymizeAddress(String raw) {
    if (raw == null || raw.isBlank()) return "***";
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return "***";
    String host = extractHost(trimmed);
    if (host == null || host.isBlank()) return "***";
    if (host.contains("%")) return "***"; // zone id rejetée
    // Try IPv4 manual
    String ipv4Anon = tryAnonymizeIPv4(host);
    if (ipv4Anon != null) return ipv4Anon;
    // IPv6: must contain colon and only allow hex/colon/dot
    if (!host.contains(":")) return "***";
    if (!isStrictIPv6Chars(host)) return "***";
    try {
      // Safe after strict allowlist: IP literal only, no hostname DNS
      InetAddress addr = InetAddress.getByName(host);
      byte[] bytes = addr.getAddress();
      if (bytes.length != 16) return "***";
      for (int i = 8; i < 16; i++) bytes[i] = 0;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 4; i++) {
        int v = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
        if (i > 0) sb.append(':');
        sb.append(Integer.toHexString(v));
      }
      sb.append("::");
      return sb.toString();
    } catch (Exception e) {
      return "***";
    }
  }

  private static String extractHost(String raw) {
    raw = raw.trim();
    // [IPv6]:port
    if (raw.startsWith("[")) {
      int close = raw.indexOf(']');
      if (close > 1) {
        return raw.substring(1, close).trim();
      }
      return null;
    }
    // IPv4:port  e.g. 192.168.1.1:8080
    if (raw.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+$")) {
      int colon = raw.lastIndexOf(':');
      return raw.substring(0, colon);
    }
    // If contains : and not bracketed, could be bare IPv6 with port? But spec says [IPv6]:port,
    // so bare IPv6 should not have port. If single colon with numeric port but not IPv4 pattern -> hostname:port -> to be rejected later.
    // Detect hostname:port generic (contains : and port numeric) -> extract before colon for later hostname check
    // But we should not strip port for bare IPv6 which has multiple colons; so check if there is exactly one colon and after is digits -> treat as host:port
    // However IPv6 has >=2 colons, so safe.
    if (raw.indexOf(':') != raw.lastIndexOf(':')) {
      // multiple colons -> IPv6 literal without port, return as is (strip zone already)
      // ensure not having port suffix like :1234 at end that is still part of IPv6? IPv6 last hextet could be digits but ambiguous.
      // We assume no port without brackets, so return raw.
      return raw;
    }
    // single colon case: could be host:port or IPv6 single colon (like ::1 has 2 colons, so not here) or malformed
    if (raw.contains(":")) {
      int idx = raw.lastIndexOf(':');
      String after = raw.substring(idx + 1);
      if (after.matches("\\d+") && raw.substring(0, idx).matches(".*[a-zA-Z].*")) {
        // hostname:port -> return host part to be later rejected as hostname
        return raw.substring(0, idx);
      }
      // if before part looks like IPv4 without port? single colon shouldn't happen for IPv4, so treat as raw
    }
    return raw;
  }

  private static String tryAnonymizeIPv4(String host) {
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) return null;
    int[] vals = new int[4];
    for (int i = 0; i < 4; i++) {
      String p = parts[i];
      if (p.isEmpty() || p.length() > 3) return null;
      for (char c : p.toCharArray()) if (!Character.isDigit(c)) return null;
      try {
        int v = Integer.parseInt(p);
        if (v < 0 || v > 255) return null;
        vals[i] = v;
      } catch (NumberFormatException e) { return null; }
    }
    return vals[0] + "." + vals[1] + "." + vals[2] + ".0";
  }

  private static boolean isStrictIPv6Chars(String host) {
    for (char c : host.toCharArray()) {
      if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.') continue;
      return false;
    }
    return true;
  }
}
