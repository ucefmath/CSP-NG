package com.bowman.cspng.web.admin.dto;

import java.util.List;

public record DcwPolicyResponse(
    boolean enabled,
    boolean adaptiveRoutingEnabled,
    boolean autoObserveEnabled,
    boolean zeroCountingEnabled,
    String defaultChecksumMode,
    String repairMode,
    boolean allowZeroHalf,
    int rulesCount,
    List<DcwRuleResponse> rules,
    int mappingsCount,
    List<DcwServiceMappingResponse> mappings,
    long version,
    long updatedAtMs
) {}
