package com.bowman.cspng.web.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Remplacement complet du singleton quarantaine (PUT) — version optimiste requise. */
public record UpdateQuarantineRequest(
    @NotNull Boolean enabled,
    String mode,
    @Min(2) @Max(10000) Integer minSamples,
    @Min(0) @Max(10000) Integer invalidRatioBps,
    @Min(5000) @Max(3600000) Long windowMs,
    @Min(10000) @Max(86400000) Long durationMs,
    @Min(16) @Max(1024) Integer maxPeers,
    @NotNull @Min(1) Long version) {}
