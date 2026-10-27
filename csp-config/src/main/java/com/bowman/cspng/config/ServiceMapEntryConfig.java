package com.bowman.cspng.config;

/**
 * Entree de service-map (mapping service vers profile).
 */
public record ServiceMapEntryConfig(
    long id,
    String profileName,
    int serviceId,
    String serviceName,
    int serviceType,
    Integer providerIdent,
    Integer ecmLength,
    String customData,
    long version
) {
    public ServiceMapEntryConfig {
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
