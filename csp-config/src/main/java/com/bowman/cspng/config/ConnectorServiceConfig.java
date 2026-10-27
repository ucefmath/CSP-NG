package com.bowman.cspng.config;

/**
 * Association service explicite pour un connecteur.
 */
public record ConnectorServiceConfig(
    long id,
    String connectorName,
    int serviceId,
    String serviceName,
    long version
) {
    public ConnectorServiceConfig {
        if (connectorName == null || connectorName.isBlank()) throw new IllegalArgumentException("connectorName required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
