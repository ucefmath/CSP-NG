package com.bowman.cspng.web.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Requête REST d'upsert pour un secret de protocole entrant (CCcam/CS378x).
 * <p>
 * REFS ONLY : la valeur du secret n'est JAMAIS fournie dans le corps JSON.
 * Seule la référence syntaxique {@code env:VAR_NAME} ou {@code file:/abs/path}
 * est acceptée.
 *
 * <p>{@code cacheex} (optionnel, contrat v2 §6.2) : politique cacheex du compte —
 * mode 2 = NOUS émettons vers le client (il reçoit) ; mode 3 = NOUS recevons
 * (il émet). Absent/null = off.
 */
public record UpsertProtocolSecretRequest(
    @NotBlank(message = "secretRef is required")
    @Pattern(regexp = "^(env:[a-zA-Z0-9_]+|file:/[^\\s]+)$", message = "secretRef must be env:VAR or file:/path")
    String secretRef,
    Instant validUntil,
    Boolean enabled,
    CacheExPolicyDto cacheex
) {

  /** Compat sans politique cacheex. */
  public UpsertProtocolSecretRequest(String secretRef, Instant validUntil, Boolean enabled) {
    this(secretRef, validUntil, enabled, null);
  }

  /** Politique cacheex d'un compte protocole entrant. */
  public record CacheExPolicyDto(
      @Min(2) @Max(3) Integer mode,
      @Min(0) @Max(10) Integer maxHop,
      @Size(max = 512) @Pattern(regexp = "^[0-9a-fA-F:,\\s]*$", message = "filter must be caid:provid hex pairs")
      String filter) {}
}
