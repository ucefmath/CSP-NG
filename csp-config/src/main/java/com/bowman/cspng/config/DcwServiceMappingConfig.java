package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.DcwServiceMapping;

/**
 * Mapping explicite approuvé (csp_dcw_service_mapping) au sein d'un profil.
 * Relie soit deux SIDs d'un même couple (caid, provider), soit deux couples (caid, provider) distincts
 * (globalement lorsque sourceSid = targetSid = 0, ou ciblé par service).
 * {@code expiresAtMs = 0} : sans expiration. Normalisation seule ; validation par {@link DcwConfigRules}.
 */
public record DcwServiceMappingConfig(
    long id,
    String profile,
    int caid,
    int provider,
    int targetCaid,
    int targetProvider,
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
                                 boolean approved, boolean enabled, long expiresAtMs, long version, long updatedAtMs) {
    this(id, profile, caid, provider, caid, provider, sourceSid, targetSid, approved, enabled, expiresAtMs, version, updatedAtMs);
  }

  public DcwServiceMappingConfig(long id, String profile, int caid, int provider, int sourceSid, int targetSid,
                                 boolean approved, boolean enabled, long version, long updatedAtMs) {
    this(id, profile, caid, provider, caid, provider, sourceSid, targetSid, approved, enabled, 0L, version, updatedAtMs);
  }

  public DcwServiceMapping toDomain() {
    return new DcwServiceMapping(id, profile, caid, provider, targetCaid, targetProvider, sourceSid, targetSid, approved, enabled, expiresAtMs, version, updatedAtMs);
  }
}
