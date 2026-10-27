package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.DcwRule;
import com.bowman.cspng.domain.dcw.DcwRuleAction;
import com.bowman.cspng.domain.dcw.DcwRuleScope;

import java.util.Locale;

/**
 * Règle DCW persistée (csp_dcw_rule). Le constructeur NORMALISE uniquement (jamais d'exception) ;
 * la validation stricte est faite par {@link DcwConfigRules} (ConfigValidator + API) afin qu'un
 * enregistrement invalide fasse échouer le snapshot (fail-closed) au lieu d'être ignoré.
 * {@code provider == null} = wildcard ; 0..0xFFFFFF = provider exact (0 légitime).
 */
public record DcwRuleConfig(
    long id,
    String profile,
    int caid,
    Integer provider,
    int sid,
    String sourceId,
    String digestSha256,
    String action,
    String checksumMode,
    String notes,
    boolean enabled,
    long version,
    long updatedAtMs
) {

  public DcwRuleConfig {
    profile = (profile != null && !profile.isBlank()) ? profile.trim() : null;
    sourceId = (sourceId != null && !sourceId.isBlank()) ? sourceId.trim() : null;
    digestSha256 = (digestSha256 != null && !digestSha256.isBlank()) ? digestSha256.trim().toLowerCase(Locale.ROOT) : null;
    action = (action != null && !action.isBlank()) ? action.trim().toLowerCase(Locale.ROOT) : "none";
    if ("no_action".equals(action)) action = "none";
    checksumMode = (checksumMode != null && !checksumMode.isBlank()) ? checksumMode.trim().toLowerCase(Locale.ROOT) : "inherit";
    notes = (notes != null) ? notes : "";
  }

  /** Compat sans checksumMode. */
  public DcwRuleConfig(long id, String profile, int caid, Integer provider, int sid, String sourceId,
                       String digestSha256, String action, String notes, boolean enabled,
                       long version, long updatedAtMs) {
    this(id, profile, caid, provider, sid, sourceId, digestSha256, action, "inherit", notes, enabled, version, updatedAtMs);
  }

  /** Compile vers le domaine (IAE si invalide : appeler après {@link DcwConfigRules#validateRule}). */
  public DcwRule toDomain() {
    return new DcwRule(id, new DcwRuleScope(profile, caid, provider, sid, sourceId), digestSha256,
        DcwRuleAction.fromString(action), ChecksumMode.fromString(checksumMode), notes, enabled, updatedAtMs, updatedAtMs);
  }
}
