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
 * DTO for updating an existing user via the admin API.
 * All fields optional; null means no change, empty means clear (for collections).
 *
 * <p>Noms ACL : même pattern que CreateUserRequest
 * {@code ^[A-Za-z0-9_\-.:]+(?: [A-Za-z0-9_\-.:]+)*$} — pas vide, pas leading/trailing
 * whitespace, pas de contrôle/NUL, charset lettres/chiffres/_-.: + espaces internes.
 */
public record UpdateUserRequest(
    @Size(max = 255) String displayName,
    @Size(min = 4, max = 128) String password,
    @Email @Size(max = 255) String email,
    Boolean enabled,
    Boolean admin,
    Boolean debug,
    Boolean mapExcluded,
    @Min(-1) @Max(100000) Integer maxConnections,
    String ipMask,
    @Min(-1) @Max(100000) Integer allowedEcmRate,
    @Size(max = 500) Set<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String> profiles,
    @Size(max = 500) Map<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String, @NotNull @Size(max = 500) Set<@NotNull @Min(0) @Max(65535) Integer>> allowedServices,
    @Size(max = 500) Map<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 64) String, @NotNull @Size(max = 500) Set<@NotNull @Min(0) @Max(65535) Integer>> blockedServices,
    @Size(max = 500) Set<@NotNull @Pattern(regexp = "^[A-Za-z0-9_\\-.:]+(?: [A-Za-z0-9_\\-.:]+)*$") @Size(min = 1, max = 128) String> allowedConnectors
) {}
