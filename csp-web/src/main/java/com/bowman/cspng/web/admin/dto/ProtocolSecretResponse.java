package com.bowman.cspng.web.admin.dto;

import java.time.Instant;

/**
 * Réponse REST pour un secret de protocole entrant (CCcam/CS378x).
 * <p>
 * REFS ONLY : aucune valeur de secret n'est jamais exposée ni retournée.
 * {@code cacheex} reflète la politique du compte (métadonnées seulement).
 */
public record ProtocolSecretResponse(
    String userName,
    String protocol,
    String secretRef,
    long secretVersion,
    Long ucrc,
    boolean enabled,
    Instant validUntil,
    long version,
    String updatedBy,
    Instant updatedAt,
    CacheExPolicyDto cacheex
) {
  /** Compat sans politique cacheex (off). */
  public ProtocolSecretResponse(
      String userName, String protocol, String secretRef, long secretVersion,
      Long ucrc, boolean enabled, Instant validUntil, long version,
      String updatedBy, Instant updatedAt) {
    this(userName, protocol, secretRef, secretVersion, ucrc, enabled, validUntil,
        version, updatedBy, updatedAt,
        new CacheExPolicyDto(0, 10, null));
  }

  /** Politique cacheex du compte (mode 0 = off). */
  public record CacheExPolicyDto(int mode, int maxHop, String filter) {}
}
