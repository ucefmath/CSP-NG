package com.bowman.cspng.domain.dcw;

/**
 * Mapping explicite, approuvé par un admin, entre deux SIDs d'un même (profile, CAID, provider).
 * N'autorise jamais de changement de provider/CAID/profil ni de contournement ACL : c'est uniquement
 * une autorisation de chercher une référence de corroboration (checkpoint suivant).
 * {@code expiresAtMs = 0} : sans expiration.
 */
public record DcwServiceMapping(
    Long id,
    String profile,
    int caid,
    int provider,
    int sourceSid,
    int targetSid,
    boolean approved,
    boolean enabled,
    long expiresAtMs,
    long version,
    long updatedAtMs
) {
  public DcwServiceMapping {
    if (profile == null || profile.isBlank() || profile.length() > 64) throw new IllegalArgumentException("mapping profile is required (1..64)");
    profile = profile.trim();
    if (caid < 1 || caid > 0xFFFF) throw new IllegalArgumentException("mapping caid out of bounds (1..65535)");
    if (provider < 0 || provider > 0xFFFFFF) throw new IllegalArgumentException("mapping provider out of bounds (0..16777215)");
    if (sourceSid < 1 || sourceSid > 0xFFFF) throw new IllegalArgumentException("mapping sourceSid out of bounds (1..65535)");
    if (targetSid < 1 || targetSid > 0xFFFF) throw new IllegalArgumentException("mapping targetSid out of bounds (1..65535)");
    if (sourceSid == targetSid) throw new IllegalArgumentException("mapping sourceSid must differ from targetSid");
    if (expiresAtMs < 0) throw new IllegalArgumentException("mapping expiresAtMs must be >= 0");
  }

  public boolean isActive(long nowMs) {
    return enabled && approved && (expiresAtMs == 0 || nowMs <= expiresAtMs);
  }

  /** Correspondance stricte même profil/CAID/provider, paire de SIDs non orientée, mapping actif. */
  public boolean matches(String targetProfile, int targetCaid, int targetProvider, int sidA, int sidB, long nowMs) {
    if (!isActive(nowMs)) return false;
    if (targetProfile == null || !profile.equalsIgnoreCase(targetProfile.trim())) return false;
    if (targetProvider == DcwRuleScope.UNKNOWN_PROVIDER) return false;
    if (caid != targetCaid || provider != targetProvider) return false;
    return (sourceSid == sidA && targetSid == sidB) || (sourceSid == sidB && targetSid == sidA);
  }
}
