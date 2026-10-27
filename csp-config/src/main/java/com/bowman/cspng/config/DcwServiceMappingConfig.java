package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.DcwServiceMapping;

/**
 * Mapping explicite approuvé (csp_dcw_service_mapping) entre deux SIDs d'un même profil/CAID/provider.
 * {@code expiresAtMs = 0} : sans expiration. Normalisation seule ; validation par {@link DcwConfigRules}.
 */
public record DcwServiceMappingConfig(
    long id,
    String profile,
    int caid,
    int provider,
    int sourceSid,
    int targetSid,
    boolean approved,
    boolean enabled,
    long expiresAtMs,
    long version,
    long updatedAtMs
) {
  public DcwServiceMappingConfig {
    profile = (profile != null && !profile.isBlank()) ? profile.trim() : null;
  }

  public DcwServiceMappingConfig(long id, String profile, int caid, int provider, int sourceSid, int targetSid,
                                 boolean approved, boolean enabled, long version, long updatedAtMs) {
    this(id, profile, caid, provider, sourceSid, targetSid, approved, enabled, 0L, version, updatedAtMs);
  }

  public DcwServiceMapping toDomain() {
    return new DcwServiceMapping(id, profile, caid, provider, sourceSid, targetSid, approved, enabled, expiresAtMs, version, updatedAtMs);
  }
}
