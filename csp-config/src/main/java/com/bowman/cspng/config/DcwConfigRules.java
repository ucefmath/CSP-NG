package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.DcwDigest;
import com.bowman.cspng.domain.dcw.DcwQualityPolicy;
import com.bowman.cspng.domain.dcw.DcwRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validation stricte partagée (ConfigValidator, API admin, worker) du contrat DCW.
 * Messages sans CW ni digest reflété.
 */
public final class DcwConfigRules {

  public static final Set<String> CHECKSUM_MODES = Set.of("require_valid", "ignore", "reject_valid", "inherit");
  public static final Set<String> REPAIR_MODES = Set.of("off", "observe", "enforce");
  public static final Set<String> ACTIONS = Set.of("block", "observe", "none");
  public static final int MAX_NAME = 64;
  public static final int MAX_NOTES = 255;

  private DcwConfigRules() {}

  public static List<String> validatePolicyHeader(String defaultChecksumMode, String repairMode) {
    List<String> e = new ArrayList<>();
    if (defaultChecksumMode == null || !CHECKSUM_MODES.contains(defaultChecksumMode)) {
      e.add("DCW policy defaultChecksumMode must be one of " + CHECKSUM_MODES);
    }
    if (repairMode == null || !REPAIR_MODES.contains(repairMode)) {
      e.add("DCW policy repairMode must be one of " + REPAIR_MODES);
    }
    return e;
  }

  public static List<String> validateRule(DcwRuleConfig r) {
    List<String> e = new ArrayList<>();
    String p = "DCW rule #" + r.id() + ": ";
    if (r.profile() != null && r.profile().length() > MAX_NAME) e.add(p + "profile length must be 1..64");
    if (r.sourceId() != null && r.sourceId().length() > MAX_NAME) e.add(p + "sourceId length must be 1..64");
    if (r.caid() < 0 || r.caid() > 0xFFFF) e.add(p + "caid must be 0..65535 (0 = wildcard)");
    if (r.provider() != null && (r.provider() < 0 || r.provider() > 0xFFFFFF)) e.add(p + "provider must be null (wildcard) or 0..16777215");
    if (r.sid() < 0 || r.sid() > 0xFFFF) e.add(p + "sid must be 0..65535 (0 = wildcard)");
    if (r.notes() != null && r.notes().length() > MAX_NOTES) e.add(p + "notes length must be <= 255");
    if (!ACTIONS.contains(r.action())) e.add(p + "action must be block|observe|none");
    if (!CHECKSUM_MODES.contains(r.checksumMode())) e.add(p + "checksumMode must be one of " + CHECKSUM_MODES);
    boolean hasDigest = r.digestSha256() != null;
    if (hasDigest && !DcwDigest.isValidSha256Hex(r.digestSha256())) e.add(p + "digest_sha256 must be 64 hex characters");
    if (("block".equals(r.action()) || "observe".equals(r.action())) && !hasDigest) {
      e.add(p + "action " + r.action() + " requires digest_sha256");
    }
    if ("none".equals(r.action())) {
      if (hasDigest) e.add(p + "action none must not carry digest_sha256");
      if ("inherit".equals(r.checksumMode())) e.add(p + "action none with checksumMode inherit has no effect");
    }
    return e;
  }

  public static List<String> validateMapping(DcwServiceMappingConfig m) {
    List<String> e = new ArrayList<>();
    String p = "DCW mapping #" + m.id() + ": ";
    if (m.profile() == null || m.profile().length() > MAX_NAME) e.add(p + "profile is required (1..64)");
    if (m.caid() < 1 || m.caid() > 0xFFFF) e.add(p + "caid must be 1..65535");
    if (m.targetCaid() < 1 || m.targetCaid() > 0xFFFF) e.add(p + "targetCaid must be 1..65535");
    if (m.provider() < 0 || m.provider() > 0xFFFFFF) e.add(p + "provider must be 0..16777215");
    if (m.targetProvider() < 0 || m.targetProvider() > 0xFFFFFF) e.add(p + "targetProvider must be 0..16777215");
    if (m.sourceSid() < 0 || m.sourceSid() > 0xFFFF) e.add(p + "sourceSid must be 0..65535");
    if (m.targetSid() < 0 || m.targetSid() > 0xFFFF) e.add(p + "targetSid must be 0..65535");
    if (!((m.sourceSid() == 0 && m.targetSid() == 0) || (m.sourceSid() > 0 && m.targetSid() > 0))) {
      e.add(p + "sourceSid and targetSid must both be 0 or both 1..65535");
    }
    if (m.caid() == m.targetCaid() && m.provider() == m.targetProvider() && m.sourceSid() == m.targetSid()) {
      e.add(p + "mapping source and target must differ");
    }
    if (m.expiresAtMs() < 0) e.add(p + "expiresAtMs must be >= 0");
    return e;
  }

  /**
   * Validation complète : en-tête, bornes, chaque règle/mapping, profils référencés (si connus),
   * conflits de scope, puis compilation domaine (dernière garde).
   */
  public static List<String> validatePolicy(DcwPolicyConfig policy, Set<String> knownProfiles) {
    List<String> e = new ArrayList<>(validatePolicyHeader(policy.defaultChecksumMode(), policy.repairMode()));
    if (policy.rules().size() > DcwQualityPolicy.MAX_RULES) e.add("DCW rules exceed max " + DcwQualityPolicy.MAX_RULES);
    if (policy.mappings().size() > DcwQualityPolicy.MAX_MAPPINGS) e.add("DCW mappings exceed max " + DcwQualityPolicy.MAX_MAPPINGS);
    boolean checkProfiles = knownProfiles != null;
    List<DcwRule> compiled = new ArrayList<>();
    for (DcwRuleConfig r : policy.rules()) {
      List<String> re = validateRule(r);
      if (checkProfiles && r.profile() != null && !knownProfiles.contains(r.profile())) {
        re.add("DCW rule #" + r.id() + ": unknown profile");
      }
      e.addAll(re);
      if (re.isEmpty()) {
        try { compiled.add(r.toDomain()); } catch (IllegalArgumentException ex) { e.add("DCW rule #" + r.id() + ": " + ex.getMessage()); }
      }
    }
    for (DcwServiceMappingConfig m : policy.mappings()) {
      List<String> me = validateMapping(m);
      if (checkProfiles && m.profile() != null && !knownProfiles.contains(m.profile())) {
        me.add("DCW mapping #" + m.id() + ": unknown profile");
      }
      e.addAll(me);
    }
    for (String c : DcwQualityPolicy.findConflicts(compiled)) e.add("DCW checksum rules ambiguous: " + c);
    if (e.isEmpty()) {
      try { policy.toDomain(); } catch (IllegalArgumentException ex) { e.add("DCW policy invalid: " + ex.getMessage()); }
    }
    return e;
  }
}
