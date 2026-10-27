package com.bowman.cspng.config;

/** Pair UDP assigne a un cache (allowlist ; decouverte dynamique interdite cote worker). */
public record RelayUdpPeerConfig(
    long id,
    String cacheName,
    String host,
    int port,
    boolean enabled,
    long version
) {
  public RelayUdpPeerConfig {
    if (id < 1) throw new IllegalArgumentException("id must be >= 1");
    if (cacheName == null || cacheName.isBlank())
      throw new IllegalArgumentException("cacheName required");
    if (host == null || host.isBlank() || host.length() > 253)
      throw new IllegalArgumentException("host required (<=253)");
    if (port < 1 || port > 65535) throw new IllegalArgumentException("port 1..65535");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
  }
}
