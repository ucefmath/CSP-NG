package com.bowman.cspng.web.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Levée admin explicite d'une quarantaine.
 *
 * <p>La levée manuelle s'applique au nœud central (défaut). Les quarantaines
 * sur les workers expirent automatiquement en mémoire.
 */
public record ReleaseQuarantineRequest(
    @NotBlank @Size(max = 256) String peerId,
    @Size(max = 64) String nodeId
) {
  public ReleaseQuarantineRequest(String peerId) {
    this(peerId, null);
  }
}
