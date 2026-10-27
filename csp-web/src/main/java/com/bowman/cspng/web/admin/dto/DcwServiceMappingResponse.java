package com.bowman.cspng.web.admin.dto;

public record DcwServiceMappingResponse(
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
) {}
