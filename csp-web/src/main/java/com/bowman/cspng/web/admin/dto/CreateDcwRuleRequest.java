package com.bowman.cspng.web.admin.dto;

/**
 * Création de règle. {@code provider} null = wildcard, 0..16777215 exact. {@code badDcwHex}
 * (32 hex) est converti en SHA-256 en mémoire et jamais stocké/retourné.
 */
public record CreateDcwRuleRequest(
    String profile,
    Integer caid,
    Integer provider,
    Integer sid,
    String sourceId,
    String digestSha256,
    String badDcwHex,
    String action,
    String checksumMode,
    String notes,
    Boolean enabled
) {}
