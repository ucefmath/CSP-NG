package com.bowman.cspng.cluster.tcp;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Settings immuable d'un peer CSPv2, normalisé.
 * Validation fail-fast stricte (DNS, nodeId, port, profiles).
 * enabled=false reste validé pour éviter surprise à l'activation.
 */
public record CspV2PeerSettings(
    boolean enabled,
    String nodeId,
    String host,
    int port,
    List<String> profiles,
    boolean dialEnabled
) {

  private static final Pattern NODE_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");
  private static final Pattern PROFILE_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
  // hostname label: 1..63 chars, alphanum start/end, hyphen interior
  private static final Pattern HOSTNAME_LABEL = Pattern.compile("[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?");
  private static final Pattern IPV4_DOTTED = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

  public CspV2PeerSettings(boolean enabled, String nodeId, String host, int port, List<String> profiles) {
    this(enabled, nodeId, host, port, profiles, true);
  }

  public CspV2PeerSettings {
    // nodeId
    if (nodeId == null || nodeId.isBlank()) {
      throw new IllegalArgumentException("peer nodeId must not be blank");
    }
    String nid = nodeId.trim();
    if (!NODE_ID_PATTERN.matcher(nid).matches()) {
      throw new IllegalArgumentException("peer nodeId invalid");
    }
    nodeId = nid;

    // host — DNS strict
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("peer host must not be blank");
    }
    String h = host.trim();
    validateDnsHostname(h);
    host = h;

    // port
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("peer port must be 1..65535");
    }

    // profiles
    if (profiles == null) {
      throw new IllegalArgumentException("peer profiles must not be null");
    }
    // defensive copy + validate each
    List<String> copy = List.copyOf(profiles.stream().map(p -> {
      if (p == null || p.isBlank()) {
        throw new IllegalArgumentException("peer profile must not be blank");
      }
      String t = p.trim();
      if (t.length() < 1 || t.length() > 64) {
        throw new IllegalArgumentException("peer profile length 1..64");
      }
      if (!PROFILE_PATTERN.matcher(t).matches()) {
        throw new IllegalArgumentException("peer profile invalid");
      }
      return t;
    }).toList());
    profiles = copy;
    // Note: empty profiles => deny-all is allowed, no exception.
  }

  static void validateDnsHostname(String host) {
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    String h = host.trim();
    if (h.length() > 253) {
      throw new IllegalArgumentException("host too long");
    }
    // Reject URI/userinfo/path/colon/query
    if (h.contains("://") || h.contains("@") || h.contains("/") || h.contains("\\")
        || h.contains("?") || h.contains("#") || h.contains(" ")) {
      throw new IllegalArgumentException("host must be plain DNS hostname");
    }
    // Reject colon => IP literal or port, IPv6
    if (h.contains(":")) {
      throw new IllegalArgumentException("host must not be IP literal");
    }
    // Reject IPv4 literal
    if (IPV4_DOTTED.matcher(h).matches()) {
      String[] parts = h.split("\\.", -1);
      boolean isIpv4 = true;
      for (String p : parts) {
        try {
          int v = Integer.parseInt(p);
          if (v < 0 || v > 255) { isIpv4 = false; break; }
        } catch (NumberFormatException e) { isIpv4 = false; break; }
      }
      if (isIpv4) {
        throw new IllegalArgumentException("host must not be IP literal");
      }
    }
    // Validate labels
    String[] labels = h.split("\\.", -1);
    if (labels.length == 0) {
      throw new IllegalArgumentException("host invalid");
    }
    for (String label : labels) {
      if (label.isEmpty() || label.length() > 63) {
        throw new IllegalArgumentException("host label invalid");
      }
      if (!HOSTNAME_LABEL.matcher(label).matches()) {
        throw new IllegalArgumentException("host label invalid");
      }
    }
    // Reject leading/trailing dot or hyphen already covered but ensure overall not start/end hyphen
    if (h.startsWith("-") || h.endsWith("-") || h.startsWith(".") || h.endsWith(".")) {
      throw new IllegalArgumentException("host invalid");
    }
    // Reject underscore
    if (h.contains("_")) {
      throw new IllegalArgumentException("host must not contain underscore");
    }
  }
}
