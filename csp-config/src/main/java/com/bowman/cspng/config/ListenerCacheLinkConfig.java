package com.bowman.cspng.config;

/**
 * Lien listener x cache (ADR 0002). Detache par defaut ({@code enabled=false}).
 * Au moins un role doit etre vrai.
 */
public record ListenerCacheLinkConfig(
    long listenerId,
    String cacheName,
    boolean canConsult,
    boolean canFeed,
    boolean canExport,
    boolean enabled,
    long version
) {
  public ListenerCacheLinkConfig {
    if (listenerId < 1) throw new IllegalArgumentException("listenerId must be >= 1");
    if (cacheName == null || cacheName.isBlank())
      throw new IllegalArgumentException("cacheName required");
    if (!(canConsult || canFeed || canExport))
      throw new IllegalArgumentException("at least one of canConsult/canFeed/canExport must be true");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
  }
}
