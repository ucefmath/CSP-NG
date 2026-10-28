package com.bowman.cspng.domain.dcw;

import java.util.Optional;

/**
 * Mapping explicite, approuvé par un admin, au sein d'un profil.
 * Relie soit deux SIDs d'un même couple (caid, provider), soit deux couples (caid, provider) distincts
 * (globalement lorsque sourceSid = targetSid = 0, ou ciblé par service).
 * N'autorise aucun contournement ACL : c'est une autorisation de correspondance / référence de corroboration.
 * {@code expiresAtMs = 0} : sans expiration.
 */
public record DcwServiceMapping(
    Long id,
    String profile,
    int caid,
    int provider,
    int targetCaid,
    int targetProvider,
    int sourceSid,
    int targetSid,
    boolean approved,
    boolean enabled,
    long expiresAtMs,
    long version,
    long updatedAtMs
) {
  public DcwServiceMapping {
    if (profile == null || profile.isBlank() || profile.length() > 64) {
      throw new IllegalArgumentException("mapping profile is required (1..64)");
    }
    profile = profile.trim();
    if (caid < 1 || caid > 0xFFFF) {
      throw new IllegalArgumentException("mapping caid out of bounds (1..65535)");
    }
    if (targetCaid < 1 || targetCaid > 0xFFFF) {
      throw new IllegalArgumentException("mapping targetCaid out of bounds (1..65535)");
    }
    if (provider < 0 || provider > 0xFFFFFF) {
      throw new IllegalArgumentException("mapping provider out of bounds (0..16777215)");
    }
    if (targetProvider < 0 || targetProvider > 0xFFFFFF) {
      throw new IllegalArgumentException("mapping targetProvider out of bounds (0..16777215)");
    }
    if (sourceSid < 0 || sourceSid > 0xFFFF) {
      throw new IllegalArgumentException("mapping sourceSid out of bounds (0..65535)");
    }
    if (targetSid < 0 || targetSid > 0xFFFF) {
      throw new IllegalArgumentException("mapping targetSid out of bounds (0..65535)");
    }
    if (!((sourceSid == 0 && targetSid == 0) || (sourceSid > 0 && targetSid > 0))) {
      throw new IllegalArgumentException("mapping sids must both be 0 or both > 0");
    }
    if (caid == targetCaid && provider == targetProvider && sourceSid == targetSid) {
      throw new IllegalArgumentException("mapping source and target must differ");
    }
    if (expiresAtMs < 0) {
      throw new IllegalArgumentException("mapping expiresAtMs must be >= 0");
    }
  }

  public DcwServiceMapping(Long id, String profile, int caid, int provider, int sourceSid, int targetSid,
                           boolean approved, boolean enabled, long expiresAtMs, long version, long updatedAtMs) {
    this(id, profile, caid, provider, caid, provider, sourceSid, targetSid, approved, enabled, expiresAtMs, version, updatedAtMs);
  }

  public record MappedTarget(int caid, int provider, int sid) {}

  public boolean isActive(long nowMs) {
    return enabled && approved && (expiresAtMs == 0 || nowMs <= expiresAtMs);
  }

  /**
   * Résout la cible mappée si la requête correspond à l'un des deux côtés du mapping actif.
   * provider UNKNOWN, profil non correspondant ou SID hors borne renvoient {@link Optional#empty()}.
   */
  public Optional<MappedTarget> resolveTarget(String reqProfile, int reqCaid, int reqProvider, int reqSid, long nowMs) {
    if (!isActive(nowMs) || reqProfile == null || !profile.equalsIgnoreCase(reqProfile.trim())
        || reqProvider == DcwRuleScope.UNKNOWN_PROVIDER || reqSid < 1 || reqSid > 0xFFFF) {
      return Optional.empty();
    }
    if (sourceSid == 0 && targetSid == 0) {
      if (reqCaid == caid && reqProvider == provider) {
        return Optional.of(new MappedTarget(targetCaid, targetProvider, reqSid));
      }
      if (reqCaid == targetCaid && reqProvider == targetProvider) {
        return Optional.of(new MappedTarget(caid, provider, reqSid));
      }
    } else if (sourceSid > 0 && targetSid > 0) {
      if (reqCaid == caid && reqProvider == provider && reqSid == sourceSid) {
        return Optional.of(new MappedTarget(targetCaid, targetProvider, targetSid));
      }
      if (reqCaid == targetCaid && reqProvider == targetProvider && reqSid == targetSid) {
        return Optional.of(new MappedTarget(caid, provider, sourceSid));
      }
    }
    return Optional.empty();
  }

  /** Correspondance stricte même profil/CAID/provider, paire de SIDs non orientée, mapping actif. */
  public boolean matches(String targetProfile, int reqCaid, int reqProvider, int sidA, int sidB, long nowMs) {
    if (!isActive(nowMs)) return false;
    if (this.caid != this.targetCaid || this.provider != this.targetProvider || sourceSid <= 0 || targetSid <= 0) {
      return false;
    }
    if (targetProfile == null || !profile.equalsIgnoreCase(targetProfile.trim())) return false;
    if (reqProvider == DcwRuleScope.UNKNOWN_PROVIDER) return false;
    if (caid != reqCaid || provider != reqProvider) return false;
    return (sourceSid == sidA && targetSid == sidB) || (sourceSid == sidB && targetSid == sidA);
  }
}
