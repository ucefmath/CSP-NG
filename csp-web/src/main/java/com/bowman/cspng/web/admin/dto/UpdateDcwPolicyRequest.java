package com.bowman.cspng.web.admin.dto;

/** PUT complet (tous champs obligatoires, pas de défaut implicite silencieux). */
public record UpdateDcwPolicyRequest(
    Boolean enabled,
    Boolean adaptiveRoutingEnabled,
    Boolean autoObserveEnabled,
    Boolean zeroCountingEnabled,
    String defaultChecksumMode,
    String repairMode,
    Boolean allowZeroHalf,
    Long version
) {}
