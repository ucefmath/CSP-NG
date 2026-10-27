package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwRepairMode;

import java.util.List;
import java.util.Locale;

/**
 * Politique DCW persistée (csp_dcw_policy + règles + mappings). Défauts = comportement historique
 * de chaque appelant (checksum "inherit"), aucune fonctionnalité activée.
 */
public record DcwPolicyConfig(
    boolean enabled,
    boolean adaptiveRoutingEnabled,
    boolean autoObserveEnabled,
    boolean zeroCountingEnabled,
    String defaultChecksumMode,
    String repairMode,
    boolean allowZeroHalf,
    List<DcwRuleConfig> rules,
    List<DcwServiceMappingConfig> mappings,
    long version,
    long updatedAtMs
) {

  public static final DcwPolicyConfig DEFAULT = new DcwPolicyConfig(
      true, false, false, false, "inherit", "off", true, List.of(), List.of(), 1L, 0L);

  public DcwPolicyConfig {
    defaultChecksumMode = (defaultChecksumMode != null && !defaultChecksumMode.isBlank())
        ? defaultChecksumMode.trim().toLowerCase(Locale.ROOT) : "inherit";
    repairMode = (repairMode != null && !repairMode.isBlank()) ? repairMode.trim().toLowerCase(Locale.ROOT) : "off";
    rules = List.copyOf(rules != null ? rules : List.of());
    mappings = List.copyOf(mappings != null ? mappings : List.of());
  }

  /** Compat sans zeroCounting. */
  public DcwPolicyConfig(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                         String defaultChecksumMode, String repairMode, boolean allowZeroHalf,
                         List<DcwRuleConfig> rules, List<DcwServiceMappingConfig> mappings, long version, long updatedAtMs) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode, repairMode, allowZeroHalf,
        rules, mappings, version, updatedAtMs);
  }

  /** Compat sans mappings. */
  public DcwPolicyConfig(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                         String defaultChecksumMode, String repairMode, boolean allowZeroHalf,
                         List<DcwRuleConfig> rules, long version, long updatedAtMs) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode, repairMode,
        allowZeroHalf, rules, List.of(), version, updatedAtMs);
  }

  /** Compat sans repairMode/mappings. */
  public DcwPolicyConfig(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                         String defaultChecksumMode, boolean allowZeroHalf,
                         List<DcwRuleConfig> rules, long version, long updatedAtMs) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode, "off",
        allowZeroHalf, rules, List.of(), version, updatedAtMs);
  }

  /** Compat booléen : true = require_valid explicite, false = inherit. */
  public DcwPolicyConfig(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                         boolean requireChecksum, boolean allowZeroHalf,
                         List<DcwRuleConfig> rules, long version, long updatedAtMs) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false,
        requireChecksum ? "require_valid" : "inherit", "off", allowZeroHalf, rules, List.of(), version, updatedAtMs);
  }

  public DcwPolicyConfig withRulesAndMappings(List<DcwRuleConfig> r, List<DcwServiceMappingConfig> m) {
    return new DcwPolicyConfig(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled,
        defaultChecksumMode, repairMode, allowZeroHalf, r, m, version, updatedAtMs);
  }

  /** Compile vers le domaine (IAE si invalide : valider d'abord via {@link DcwConfigRules}). */
  public DcwQualityPolicy toDomain() {
    return new DcwQualityPolicy(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled,
        ChecksumMode.fromString(defaultChecksumMode), DcwRepairMode.fromString(repairMode), allowZeroHalf,
        rules.stream().map(DcwRuleConfig::toDomain).toList(),
        mappings.stream().map(DcwServiceMappingConfig::toDomain).toList());
  }
}
