package com.bowman.cspng.config;

/**
 * Provider ident associe a un profil CA (0..0xFFFFFF).
 */
public record ProfileProviderIdentConfig(
    long id,
    String profileName,
    int providerIdent,
    String label,
    long version
) {
    public ProfileProviderIdentConfig {
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
        if (providerIdent < 0 || providerIdent > 0xFFFFFF) throw new IllegalArgumentException("providerIdent must be 0-0xFFFFFF");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
