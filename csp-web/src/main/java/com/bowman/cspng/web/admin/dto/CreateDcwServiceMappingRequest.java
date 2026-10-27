package com.bowman.cspng.web.admin.dto;

/** {@code expiresAtMs} null/0 = sans expiration. */
public record CreateDcwServiceMappingRequest(
    String profile,
    Integer caid,
    Integer provider,
    Integer sourceSid,
    Integer targetSid,
    Boolean approved,
    Boolean enabled,
    Long expiresAtMs
) {}
