package com.bowman.cspng.web.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(String username, String password) {
}
