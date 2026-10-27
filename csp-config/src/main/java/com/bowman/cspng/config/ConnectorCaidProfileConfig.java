package com.bowman.cspng.config;

/**
 * Mapping CAID -> profil pour un connecteur dynamique (profile_name NULL).
 * Ordinal = ordre d'affichage/diff uniquement, version portee par le connecteur.
 */
public record ConnectorCaidProfileConfig(
    long id,
    String connectorName,
    int ordinal,
    int caId,
    String profileName,
    long version
) {
    public ConnectorCaidProfileConfig {
        if (connectorName == null || connectorName.isBlank()) throw new IllegalArgumentException("connectorName required");
        if (ordinal < 0) throw new IllegalArgumentException("ordinal must be >= 0");
        if (caId < 0 || caId > 65535) throw new IllegalArgumentException("caId must be 0-65535");
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
