package com.bowman.cspng.config;

/**
 * Configuration d'un plugin.
 */
public record PluginConfig(
    String name,
    String className,
    boolean enabled,
    String description,
    String configJson,
    String profiles,
    long version
) {
    public PluginConfig {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (className == null || className.isBlank()) throw new IllegalArgumentException("className required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
