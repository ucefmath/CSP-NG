package com.bowman.cspng.config;

/**
 * Configuration racine (parametres globaux du proxy).
 *
 * @param proxyName     nom affichable du proxy
 * @param proxyOriginId identifiant d'origine pour le cluster UDP
 * @param version       version optimistic locking
 */
public record RootConfig(
    String proxyName,
    int proxyOriginId,
    long version
) {
    public static final RootConfig DEFAULT = new RootConfig("CSP-NG", 0, 1);

    public RootConfig {
        if (proxyName == null || proxyName.isBlank()) throw new IllegalArgumentException("proxyName required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
