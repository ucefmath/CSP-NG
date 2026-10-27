package com.bowman.cspng.web.admin.dto;

/** Lien candidat inter-provider (observation seule, jamais confirmé ni réutilisé). */
public record DcwCandidateLinkResponse(
    String scopeA,
    String scopeB,
    int distinctEvidenceCount,
    long firstObservedMs,
    long lastObservedMs,
    String state
) {}
