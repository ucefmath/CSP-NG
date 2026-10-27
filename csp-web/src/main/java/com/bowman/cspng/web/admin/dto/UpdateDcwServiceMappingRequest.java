package com.bowman.cspng.web.admin.dto;

public record UpdateDcwServiceMappingRequest(
    String profile,
    Integer caid,
    Integer provider,
    Integer sourceSid,
    Integer targetSid,
    Boolean approved,
    Boolean enabled,
    Long expiresAtMs,
    Long version
) {}
