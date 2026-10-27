package com.bowman.cspng.web.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;

/**
 * DTO for creating a new user via the admin API.
 * password is required for creation.
 * ACL fields: null -&gt; empty (creation always initializes).
 *
 * <p>Noms ACL (profiles, clés allowedServices/blockedServices, connectors) :
 * <ul>
 *   <li>1..64 (profiles/keys) ou 1..128 (connectors)</li>
 *   <li>lettres/chiffres {@code A-Za-z0-9} et {@code _ - . :}</li>
 *   <li>espaces internes simples autorisés, pas de leading/trailing whitespace</li>
 *   <li>aucun caractère de contrôle / NUL ({@code \p{Cntrl}})</li>
 * </ul>
 * Pattern strict : {@code ^[A-Za-z0-9_\-.:]+(?: [A-Za-z0-9_\-.:]+)*$}
 */
public record CreateUserRequest(
    @NotNull @Pattern(regexp = "^[A-Za-z0-9_.@-]+$") @Size(min = 1, max = 128) String name,
    @Size(max = 255) String displayName,
    @NotNull @Size(min = 4, max = 128) String password,
    @Email @Size(max = 255) String email,
    boolean enabled,
    boolean admin,
    boolean debug,
    boolean mapExcluded,
    @Min(-1) @Max(100000) int maxConnections,
    String ipMask,
    @Min(-1) @Max(100000) int allowedEcmRate,
    @Size(max = 500) Set<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String> profiles,
    @Size(max = 500) Map<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String, @NotNull @Size(max = 500) Set<@NotNull @Min(0) @Max(65535) Integer>> allowedServices,
    @Size(max = 500) Map<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String, @NotNull @Size(max = 500) Set<@NotNull @Min(0) @Max(65535) Integer>> blockedServices,
    @Size(max = 500) Set<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 128) String> allowedConnectors
) {
    public CreateUserRequest {
        if (profiles == null) profiles = Set.of();
        if (allowedServices == null) allowedServices = Map.of();
        if (blockedServices == null) blockedServices = Map.of();
        if (allowedConnectors == null) allowedConnectors = Set.of();
    }
}
