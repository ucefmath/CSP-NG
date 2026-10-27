package com.bowman.cspng.config;

/**
 * Node relais (ADR 0002). Identite = SAN mTLS {@code urn:csp:node:<nodeName>}.
 * Aucun secret en base. Detache/non autorise par defaut (fail-closed).
 */
public record RelayNodeConfig(
    String nodeName,
    String displayName,
    boolean enabled,
    boolean authorized,
    boolean revoked,
    long desiredVersion,
    Long appliedVersion,
    long version,
    java.time.Instant lastSeenAt,
    String cspHost,
    Integer cspPort
) {
  public RelayNodeConfig {
    if (nodeName == null || !nodeName.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
      throw new IllegalArgumentException("nodeName must match [a-z0-9][a-z0-9._-]{0,62}");
    if (desiredVersion < 1) throw new IllegalArgumentException("desiredVersion must be >= 1");
    if (appliedVersion != null && appliedVersion < 1)
      throw new IllegalArgumentException("appliedVersion must be >= 1 or null");
    if (appliedVersion != null && appliedVersion > desiredVersion)
      throw new IllegalArgumentException("appliedVersion must be <= desiredVersion");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    if (cspPort != null && (cspPort < 1 || cspPort > 65535))
      throw new IllegalArgumentException("cspPort 1..65535 or null (accept-only)");
    if (cspPort != null && (cspHost == null || cspHost.isBlank()))
      throw new IllegalArgumentException("cspHost required when cspPort set");
  }

  /** Compat sans observation transport ni endpoint CSPv2 (anciens tests). */
  public RelayNodeConfig(String nodeName, String displayName, boolean enabled,
                         boolean authorized, boolean revoked, long desiredVersion,
                         Long appliedVersion, long version) {
    this(nodeName, displayName, enabled, authorized, revoked, desiredVersion,
        appliedVersion, version, null, null, null);
  }

  /** Compat sans endpoint CSPv2. */
  public RelayNodeConfig(String nodeName, String displayName, boolean enabled,
                         boolean authorized, boolean revoked, long desiredVersion,
                         Long appliedVersion, long version, java.time.Instant lastSeenAt) {
    this(nodeName, displayName, enabled, authorized, revoked, desiredVersion,
        appliedVersion, version, lastSeenAt, null, null);
  }

  /** Utilisable pour routage/export : les 3 gates + non expire gere par service. */
  public boolean usable() {
    return enabled && authorized && !revoked;
  }
}
