package com.bowman.cspng.config;

/**
 * Validation métier (400) pour contraintes V3.
 */
public class ConfigValidationException extends IllegalArgumentException {
    public ConfigValidationException(String message) {
        super(message);
    }
    public ConfigValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
