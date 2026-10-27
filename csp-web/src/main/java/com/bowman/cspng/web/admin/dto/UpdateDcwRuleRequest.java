package com.bowman.cspng.web.admin.dto;

public record UpdateDcwRuleRequest(
    String profile,
    Integer caid,
    Integer provider,
    Integer sid,
    String sourceId,
    String digestSha256,
    String badDcwHex,
    String action,
    String checksumMode,
    String notes,
    Boolean enabled,
    Long version
) {}
