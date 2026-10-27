package com.bowman.cspng.web.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Remplacement complet du singleton quorum CacheEx (PUT) — version optimiste requise. */
public record UpdateQuorumRequest(
    String mode,
    @Min(2) @Max(8) Integer minOrigins,
    @Min(1000) @Max(300000) Long windowMs,
    @Min(16) @Max(4096) Integer maxKeys,
    @Size(max = 64) List<String> profiles,
    @Size(max = 32) List<Integer> caids,
    @NotNull @Min(1) Long version) {}
