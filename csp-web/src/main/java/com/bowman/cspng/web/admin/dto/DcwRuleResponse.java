package com.bowman.cspng.web.admin.dto;

public record DcwRuleResponse(
    long id,
    String profile,
    int caid,
    Integer provider,
    int sid,
    String sourceId,
    String digestSha256,
    String action,
    String checksumMode,
    String notes,
    boolean enabled,
    long version,
    long updatedAtMs
) {}
