package com.bowman.cspng.config;

/**
 * Configuration d'un proxy réseau SOCKS5 (entité de premier ordre).
 *
 * <p>Distinct de {@link ProxyConfig} (legacy façade sur le nom du serveur CSP).
 * Toutes les instances sont immuables. Le secret {@code passwordRef} est une
 * référence {@code env:VAR} ou {@code file:/path}, jamais une valeur en clair.
 *
 * <p><b>Pas de champ timeout sur le proxy (Plan P2)</b> : le budget de connexion
 * T est celui du CONNECTEUR ({@code ConnectorConfig.connectTimeoutMs}, validé
 * 1 000..300 000 sur le chemin proxy) appliqué aux deux phases (TCP vers le
 * proxy + handshake SOCKS5). Le proxy ne porte que l'adresse et l'auth.
 *
 * <p>Règles de validation (alignées sur V5__csp_proxy.sql et plan P2) :
 * <ul>
 *   <li>{@code name} : regex {@code [A-Za-z0-9_.-]{1,64}}, immuable (PK).</li>
 *   <li>{@code type} : actuellement uniquement {@code "socks5"}.</li>
 *   <li>{@code host} : IP littérale (v4 ou v6 brut sans crochets) ou DNS
 *       sans schéma, sans userinfo, sans caractère de contrôle. Pas de résolution
 *       DNS locale lors de la construction.</li>
 *   <li>{@code port} : 1..65535.</li>
 *   <li>{@code username}/{@code passwordRef} : appariés — tous deux null ou
 *       tous deux non-null.</li>
 *   <li>{@code description} : null ou longueur ≤ 500.</li>
 *   <li>{@code version} : ≥ 1 (optimistic locking).</li>
 * </ul>
 *
 * <p><b>IPv6</b> : stocké sans crochets (ex: {@code 2001:db8::1}).
 * Le résolveur SOCKS5 runtime passe le host tel quel au proxy (ATYP domain pour
 * les noms DNS, ATYP IPv4/IPv6 pour les littéraux) — pas de résolution locale.
 */
public record NetworkProxyConfig(
    String name,
    boolean enabled,
    String type,
    String host,
    int port,
    String username,
    String passwordRef,
    String description,
    long version
) {
    /** Regex PK immutable alignée sur CHECK SQL de V5. */
    private static final java.util.regex.Pattern NAME_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");

    /** Types de proxy supportés. */
    private static final java.util.Set<String> SUPPORTED_TYPES =
        java.util.Set.of("socks5");

    public NetworkProxyConfig {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "proxy name must match [A-Za-z0-9_.-]{1,64}, got: " + name);
        }
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                "proxy type must be one of " + SUPPORTED_TYPES + ", got: " + type);
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("proxy host required");
        }
        // Reject URI-like, userinfo, spaces, control chars (mirrors SQL CHECK)
        if (host.contains("://") || host.contains("@") || host.contains(" ")) {
            throw new IllegalArgumentException(
                "proxy host must not contain '://', '@', or spaces");
        }
        for (char c : host.toCharArray()) {
            if (c < 32 || c == 127) {
                throw new IllegalArgumentException("proxy host contains control character");
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxy port must be 1-65535, got: " + port);
        }
        // Auth pair: both null or both non-null
        boolean hasUser = username != null;
        boolean hasPwd  = passwordRef != null;
        if (hasUser != hasPwd) {
            throw new IllegalArgumentException(
                "proxy username and passwordRef must both be null or both be non-null");
        }
        if (hasPwd && (passwordRef.isBlank()
                || !(passwordRef.startsWith("env:") || passwordRef.startsWith("file:"))
                || !ConfigValidator.isValidSecretRef(passwordRef))) {
            // Mécanisme EXISTANT ConfigValidator.isValidSecretRef : regex stricte
            // (pas de trim silencieux, pas de whitespace), file: chemin ABSOLU sans
            // segment ".." ni NUL/ctrl — file:../ REJETÉ au CRUD et au record.
            // Le proxy n'accepte QUE env:/file: (pas vault:, pas ${...}).
            throw new IllegalArgumentException(
                "proxy passwordRef must be a valid env: or file: secret ref, got: " + passwordRef);
        }
        if (passwordRef != null) {
            for (char c : passwordRef.toCharArray()) {
                if (c < 32 || c == 127) {
                    throw new IllegalArgumentException("proxy passwordRef contains control character");
                }
            }
        }
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("proxy description max 500 chars");
        }
        if (version < 1) {
            throw new IllegalArgumentException("proxy version must be >= 1");
        }
    }

    /** True if the proxy has authentication credentials. */
    public boolean hasAuth() {
        return username != null;
    }
}
