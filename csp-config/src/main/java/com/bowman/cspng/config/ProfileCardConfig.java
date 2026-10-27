package com.bowman.cspng.config;

/**
 * Info carte upstream par profile.
 */
public record ProfileCardConfig(
    long id,
    String profileName,
    String cardId,
    String cardLabel,
    Integer caId,
    String nodeId,
    long version
) {
    public ProfileCardConfig {
        if (profileName == null || profileName.isBlank()) throw new IllegalArgumentException("profileName required");
        if (cardId == null || cardId.isBlank()) throw new IllegalArgumentException("cardId required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
