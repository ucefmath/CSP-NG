package com.bowman.cspng.web.admin.dto;

import java.time.Instant;

public record SessionResponse(String user, Instant expiresAt) {
}
