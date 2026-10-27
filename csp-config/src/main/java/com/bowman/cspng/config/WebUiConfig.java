package com.bowman.cspng.config;

/**
 * Configuration de la Web UI (remplacement RMI).
 * Tous les secrets sont des references (env/file/vault), jamais en clair.
 */
public record WebUiConfig(
    boolean enabled,
    int listenPort,
    String bindAddress,
    boolean tlsEnabled,
    String tlsCertRef,
    String tlsKeyRef,
    String adminUserRef,
    String adminPasswordRef,
    String superUsers,
    String allowList,
    String welcomeFile,
    int sessionTimeoutMin,
    long version
) {
    public static final WebUiConfig DEFAULT = new WebUiConfig(
        true, 8080, "0.0.0.0", false,
        null, null, null, null,
        null, null, "web/cs-status.html", 30, 1
    );

    public WebUiConfig {
        if (listenPort < 1 || listenPort > 65535) throw new IllegalArgumentException("listenPort must be 1-65535");
        if (bindAddress == null || bindAddress.isBlank()) throw new IllegalArgumentException("bindAddress required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
    }
}
