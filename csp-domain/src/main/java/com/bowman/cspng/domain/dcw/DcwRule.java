package com.bowman.cspng.domain.dcw;

/**
 * Règle DCW immuable : scope + (digest SHA-256 pour BLOCK/OBSERVE) ou (checksumMode pour NO_ACTION).
 *
 * <p>Invariants (IAE si violés) :
 * <ul>
 *   <li>BLOCK/OBSERVE exigent un digest SHA-256 valide (jamais de blocage "tout le scope").</li>
 *   <li>NO_ACTION ne porte pas de digest.</li>
 * </ul>
 */
public record DcwRule(
    Long id,
    DcwRuleScope scope,
    String digestSha256,
    DcwRuleAction action,
    ChecksumMode checksumMode,
    String notes,
    boolean enabled,
    long createdAtMs,
    long updatedAtMs
) {

  public DcwRule {
    scope = (scope != null) ? scope : DcwRuleScope.WILDCARD;
    if (digestSha256 != null && !digestSha256.isBlank()) {
      digestSha256 = DcwDigest.normalizeSha256Hex(digestSha256);
    } else {
      digestSha256 = null;
    }
    action = (action != null) ? action : DcwRuleAction.NO_ACTION;
    checksumMode = (checksumMode != null) ? checksumMode : ChecksumMode.INHERIT;
    notes = (notes != null) ? notes : "";
    if ((action == DcwRuleAction.BLOCK || action == DcwRuleAction.OBSERVE) && digestSha256 == null) {
      throw new IllegalArgumentException("DCW rule action " + action.value() + " requires digest_sha256");
    }
    if (action == DcwRuleAction.NO_ACTION && digestSha256 != null) {
      throw new IllegalArgumentException("DCW rule action none must not carry digest_sha256");
    }
  }

  /** Compat constructor without checksumMode. */
  public DcwRule(Long id, DcwRuleScope scope, String digestSha256, DcwRuleAction action,
                 String notes, boolean enabled, long createdAtMs, long updatedAtMs) {
    this(id, scope, digestSha256, action, ChecksumMode.INHERIT, notes, enabled, createdAtMs, updatedAtMs);
  }

  /** Correspondance digest + scope (règles BLOCK/OBSERVE uniquement). */
  public boolean matches(String targetDigest, String profile, int caid, int provider, int sid, String sourceId) {
    if (!enabled || digestSha256 == null) return false;
    if (!digestSha256.equalsIgnoreCase(targetDigest)) return false;
    return scope.matches(profile, caid, provider, sid, sourceId);
  }

  /** Correspondance scope seule (résolution checksumMode). */
  public boolean matchesScopeOnly(String profile, int caid, int provider, int sid, String sourceId) {
    if (!enabled) return false;
    return scope.matches(profile, caid, provider, sid, sourceId);
  }
}
