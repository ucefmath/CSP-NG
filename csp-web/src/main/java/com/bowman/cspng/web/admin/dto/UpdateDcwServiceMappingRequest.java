package com.bowman.cspng.web.admin.dto;

public record UpdateDcwServiceMappingRequest(
    String profile,
    Integer caid,
    Integer provider,
    Integer targetCaid,
    Integer targetProvider,
    Integer sourceSid,
    Integer targetSid,
    Boolean approved,
    Boolean enabled,
    Long expiresAtMs,
    Long version
) {
  public UpdateDcwServiceMappingRequest(
      String profile,
      Integer caid,
      Integer provider,
      Integer sourceSid,
      Integer targetSid,
      Boolean approved,
      Boolean enabled,
      Long expiresAtMs,
      Long version) {
    this(profile, caid, provider, null, null, sourceSid, targetSid, approved, enabled, expiresAtMs, version);
  }
}

