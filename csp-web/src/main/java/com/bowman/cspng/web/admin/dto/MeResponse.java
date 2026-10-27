package com.bowman.cspng.web.admin.dto;

import java.util.Set;

/**
 * DTO retourné par GET /api/admin/me.
 */
public record MeResponse(
    String name,
    Set<String> roles
) {}
