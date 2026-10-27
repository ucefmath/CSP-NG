package com.bowman.cspng.domain.dcw;

import java.util.Objects;

/**
 * Périmètre d'application d'une règle DCW (profile, caid, provider, sid, sourceId).
 * null (ou 0 pour caid/sid) indique un joker (wildcard).
 * Provider null = wildcard ; Provider 0..0xFFFFFF = provider exact (provider 0 est légitime).
 */
public record DcwRuleScope(
    String profile,
    int caid,
    Integer provider,
    int sid,
    String sourceId
) {

  public static final int UNKNOWN_PROVIDER = -1;
  public static final DcwRuleScope WILDCARD = new DcwRuleScope(null, 0, null, 0, null);

  public DcwRuleScope {
    profile = (profile != null && !profile.isBlank()) ? profile.trim() : null;
    sourceId = (sourceId != null && !sourceId.isBlank()) ? sourceId.trim() : null;
    if (caid < 0 || caid > 0xFFFF) throw new IllegalArgumentException("caid out of bounds (0..65535)");
    if (provider != null && (provider < 0 || provider > 0xFFFFFF)) {
      throw new IllegalArgumentException("provider out of bounds (0..16777215)");
    }
    if (sid < 0 || sid > 0xFFFF) throw new IllegalArgumentException("sid out of bounds (0..65535)");
  }

  /**
   * Évalue si ce périmètre correspond au contexte d'évaluation donné.
   */
  public boolean matches(String targetProfile, int targetCaid, int targetProvider, int targetSid, String targetSourceId) {
    if (profile != null) {
      if (targetProfile == null || !profile.equalsIgnoreCase(targetProfile.trim())) {
        return false;
      }
    }
    if (caid != 0) {
      if (caid != targetCaid) {
        return false;
      }
    }
    if (provider != null) {
      // Si la règle cible un provider précis (y compris 0), un provider inconnu (-1) ne matche PAS.
      if (targetProvider == UNKNOWN_PROVIDER || provider.intValue() != targetProvider) {
        return false;
      }
    }
    if (sid != 0) {
      if (sid != targetSid) {
        return false;
      }
    }
    if (sourceId != null) {
      if (targetSourceId == null || !sourceId.equalsIgnoreCase(targetSourceId.trim())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Score de spécificité pour la résolution de priorité déterministe (plus grand = plus spécifique).
   */
  public int specificityScore() {
    int score = 0;
    if (sourceId != null) score += 16;
    if (sid > 0) score += 8;
    if (provider != null) score += 4;
    if (caid > 0) score += 2;
    if (profile != null) score += 1;
    return score;
  }
}
