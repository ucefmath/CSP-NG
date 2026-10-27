package com.bowman.cspng.web.admin.dto;

/**
 * JSON error response stable consommé par le frontend.
 */
public record ErrorResponse(
    String code,
    String message,
    String details,
    String correlationId
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, null);
    }

    public static ErrorResponse of(String code, String message, String details) {
        return new ErrorResponse(code, message, details, null);
    }
}
