package com.bowman.cspng.config;

/**
 * Cache relais (ADR 0002). kind {@code local|node|udp} ; {@code udp} = export legacy seul,
 * jamais propose en consultation.
 */
public record RelayCacheConfig(
    String name,
    String kind,
    String nodeName,
    boolean enabled,
    long maxAgeMs,
    int maxEntries,
    int maxInflight,
    long version
) {
  public RelayCacheConfig {
    if (name == null || !name.matches("^[A-Za-z0-9_.-]{1,64}$"))
      throw new IllegalArgumentException("cache name must match [A-Za-z0-9_.-]{1,64}");
    if (!"local".equals(kind) && !"node".equals(kind) && !"udp".equals(kind))
      throw new IllegalArgumentException("kind must be local|node|udp");
    if ("node".equals(kind) && (nodeName == null || nodeName.isBlank()))
      throw new IllegalArgumentException("node cache requires nodeName");
    if (maxAgeMs < 500 || maxAgeMs > 60000)
      throw new IllegalArgumentException("maxAgeMs must be 500..60000");
    if (maxEntries < 1 || maxEntries > 1000000)
      throw new IllegalArgumentException("maxEntries must be 1..1000000");
    if (maxInflight < 1 || maxInflight > 8192)
      throw new IllegalArgumentException("maxInflight must be 1..8192");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
  }
}
