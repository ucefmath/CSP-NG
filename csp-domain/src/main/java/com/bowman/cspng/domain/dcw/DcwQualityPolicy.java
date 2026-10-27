package com.bowman.cspng.domain.dcw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Politique immuable de qualité des Control Words (DCW).
 *
 * <h2>Sémantique checksum (DVB : 4 triplets, octet 3/7/11/15 = somme des 3 précédents mod 256)</h2>
 * <ul>
 *   <li>Le checksum n'est PAS une preuve cryptographique : une CW fausse peut avoir un checksum valide
 *       par coïncidence, et certains providers n'utilisent pas le checksum. D'où des modes explicites
 *       scopés (jamais inférés automatiquement).</li>
 *   <li>Résolution déterministe : règle activée la plus spécifique (sourceId &gt; sid &gt; provider &gt; caid
 *       &gt; profile) dont le mode != INHERIT ; sinon {@link #defaultChecksumMode} ; si INHERIT, la garde
 *       historique de l'appelant ({@link LegacyGuard}). Deux règles de scope identique avec des modes
 *       différents sont refusées (IAE / validation config).</li>
 *   <li>Provider inconnu ({@link DcwRuleScope#UNKNOWN_PROVIDER}) avec garde CHECKSUM_REQUIRED : aucune
 *       relaxation (IGNORE / REJECT_VALID) n'est appliquée ; il faut un contexte provider connu.</li>
 *   <li>Demi-CW nulle : 8 octets nuls satisfont trivialement la somme ; en REJECT_VALID seule la moitié
 *       active est évaluée (pas de rejet inversé accidentel). {@link #allowZeroHalf}=false rejette.</li>
 * </ul>
 *
 * <h2>Invariants non contournables</h2>
 * Longueur exacte 16, CW tout-zéro rejetée, règles BLOCK par digest ; aucune règle "allow".
 * {@link #enabled}=false : règles custom ignorées, seules les gardes historiques s'appliquent.
 */
public record DcwQualityPolicy(
    boolean enabled,
    boolean adaptiveRoutingEnabled,
    boolean autoObserveEnabled,
    boolean zeroCountingEnabled,
    ChecksumMode defaultChecksumMode,
    DcwRepairMode repairMode,
    boolean allowZeroHalf,
    List<DcwRule> rules,
    List<DcwServiceMapping> mappings
) {

  public static final int MAX_RULES = 1000;
  public static final int MAX_MAPPINGS = 1000;

  /** Défaut : comportement historique strict de chaque appelant (INHERIT), rien d'activé. */
  public static final DcwQualityPolicy DEFAULT = new DcwQualityPolicy(
      true, false, false, false, ChecksumMode.INHERIT, DcwRepairMode.OFF, true, List.of(), List.of());

  public DcwQualityPolicy {
    defaultChecksumMode = (defaultChecksumMode != null) ? defaultChecksumMode : ChecksumMode.INHERIT;
    repairMode = (repairMode != null) ? repairMode : DcwRepairMode.OFF;
    rules = List.copyOf(rules != null ? rules : List.of());
    mappings = List.copyOf(mappings != null ? mappings : List.of());
    if (rules.size() > MAX_RULES) {
      throw new IllegalArgumentException("DCW rules exceed max " + MAX_RULES);
    }
    if (mappings.size() > MAX_MAPPINGS) {
      throw new IllegalArgumentException("DCW service mappings exceed max " + MAX_MAPPINGS);
    }
    List<String> conflicts = findConflicts(rules);
    if (!conflicts.isEmpty()) {
      throw new IllegalArgumentException("DCW checksum rules ambiguous: " + conflicts.get(0));
    }
  }

  public DcwQualityPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                          boolean zeroCountingEnabled, ChecksumMode defaultChecksumMode, DcwRepairMode repairMode,
                          boolean allowZeroHalf, List<DcwRule> rules) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled, defaultChecksumMode, repairMode,
        allowZeroHalf, rules, List.of());
  }

  /** Compat (zeroCounting=false). */
  public DcwQualityPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                          ChecksumMode defaultChecksumMode, DcwRepairMode repairMode,
                          boolean allowZeroHalf, List<DcwRule> rules) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode, repairMode, allowZeroHalf, rules, List.of());
  }

  /** Compat (zeroCounting=false, repair OFF). */
  public DcwQualityPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                          ChecksumMode defaultChecksumMode, boolean allowZeroHalf, List<DcwRule> rules) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false, defaultChecksumMode, DcwRepairMode.OFF, allowZeroHalf, rules, List.of());
  }

  /** Compat booléen : true = REQUIRE_VALID explicite, false = INHERIT (legacy appelant). */
  public DcwQualityPolicy(boolean enabled, boolean adaptiveRoutingEnabled, boolean autoObserveEnabled,
                          boolean requireChecksum, boolean allowZeroHalf, List<DcwRule> rules) {
    this(enabled, adaptiveRoutingEnabled, autoObserveEnabled, false,
        requireChecksum ? ChecksumMode.REQUIRE_VALID : ChecksumMode.INHERIT, DcwRepairMode.OFF, allowZeroHalf, rules, List.of());
  }

  public DcwQualityPolicy withAdaptiveRouting(boolean v) {
    return new DcwQualityPolicy(enabled, v, autoObserveEnabled, zeroCountingEnabled, defaultChecksumMode, repairMode, allowZeroHalf, rules, mappings);
  }

  public DcwQualityPolicy withChecksumMode(ChecksumMode mode) {
    return new DcwQualityPolicy(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled, mode, repairMode, allowZeroHalf, rules, mappings);
  }

  public DcwQualityPolicy withRepairMode(DcwRepairMode mode) {
    return new DcwQualityPolicy(enabled, adaptiveRoutingEnabled, autoObserveEnabled, zeroCountingEnabled, defaultChecksumMode, mode, allowZeroHalf, rules, mappings);
  }

  public DcwQualityPolicy withZeroCounting(boolean v) {
    return new DcwQualityPolicy(enabled, adaptiveRoutingEnabled, autoObserveEnabled, v, defaultChecksumMode, repairMode, allowZeroHalf, rules, mappings);
  }

  /**
   * Règles activées de scope identique (profil/sourceId insensibles à la casse) portant des
   * checksumMode explicites différents. Liste vide = résolution déterministe garantie
   * (scores de spécificité égaux impliquent des dimensions identiques, donc scope identique).
   */
  public static List<String> findConflicts(List<DcwRule> rules) {
    List<String> out = new ArrayList<>();
    if (rules == null) return out;
    Map<String, DcwRule> seen = new HashMap<>();
    for (DcwRule r : rules) {
      if (r == null || !r.enabled() || r.checksumMode() == ChecksumMode.INHERIT) continue;
      String key = scopeKey(r.scope());
      DcwRule prev = seen.putIfAbsent(key, r);
      if (prev != null && prev.checksumMode() != r.checksumMode()) {
        out.add("rules #" + prev.id() + " and #" + r.id() + " share scope with different checksumMode");
      }
    }
    return out;
  }

  private static String scopeKey(DcwRuleScope s) {
    return (s.profile() == null ? "" : s.profile().toLowerCase(Locale.ROOT)) + "|" + s.caid() + "|"
        + (s.provider() == null ? "*" : s.provider()) + "|" + s.sid() + "|"
        + (s.sourceId() == null ? "" : s.sourceId().toLowerCase(Locale.ROOT));
  }

  /**
   * Mode explicite : règle la plus spécifique (mode != INHERIT) sinon défaut policy (peut être INHERIT).
   */
  public ChecksumMode resolveExplicitChecksumMode(String profile, int caid, int provider, int sid, String sourceId) {
    ChecksumMode effective = defaultChecksumMode;
    int maxSpecificity = -1;
    for (DcwRule r : rules) {
      if (!r.enabled() || r.checksumMode() == ChecksumMode.INHERIT) continue;
      if (!r.matchesScopeOnly(profile, caid, provider, sid, sourceId)) continue;
      int spec = r.scope().specificityScore();
      if (spec > maxSpecificity) {
        maxSpecificity = spec;
        effective = r.checksumMode();
      }
    }
    return effective;
  }

  /** Mode effectif pour un appelant donné (jamais INHERIT). */
  public ChecksumMode resolveEffectiveChecksumMode(String profile, int caid, int provider, int sid, String sourceId,
                                                   LegacyGuard legacy) {
    LegacyGuard g = legacy != null ? legacy : LegacyGuard.CHECKSUM_REQUIRED;
    ChecksumMode legacyMode = g == LegacyGuard.CHECKSUM_REQUIRED ? ChecksumMode.REQUIRE_VALID : ChecksumMode.IGNORE;
    if (!enabled) return legacyMode;
    ChecksumMode m = resolveExplicitChecksumMode(profile, caid, provider, sid, sourceId);
    if (m == ChecksumMode.INHERIT) m = legacyMode;
    if (provider == DcwRuleScope.UNKNOWN_PROVIDER && g == LegacyGuard.CHECKSUM_REQUIRED) {
      return ChecksumMode.REQUIRE_VALID;
    }
    return m;
  }

  /**
   * Valide une CW selon invariants, garde appelant, modes scopés et règles digest.
   * Les messages d'erreur ne contiennent jamais la CW.
   */
  public DcwValidationResult validate(byte[] cw, String profile, int caid, int provider, int sid, String sourceId,
                                      LegacyGuard legacy) {
    if (cw == null || cw.length != 16) {
      return DcwValidationResult.invalid(DcwValidationResult.Reason.NULL_OR_INVALID_LENGTH, null,
          "CW length must be exactly 16 bytes");
    }
    String digest = DcwDigest.digestSha256Hex(cw);
    if (isAllZero(cw)) {
      return DcwValidationResult.invalid(DcwValidationResult.Reason.ALL_ZERO, digest, "CW is all zeroes");
    }
    LegacyGuard g = legacy != null ? legacy : LegacyGuard.CHECKSUM_REQUIRED;
    if (!enabled) {
      if (g == LegacyGuard.CHECKSUM_REQUIRED && !validateChecksumFull(cw)) {
        return DcwValidationResult.invalid(DcwValidationResult.Reason.CHECKSUM_FAIL, digest,
            "DVB checksum validation failed (legacy guard)");
      }
      return DcwValidationResult.valid(digest, false);
    }
    if (zeroCountingEnabled && hasFiveZeroes(cw)) {
      return DcwValidationResult.invalid(DcwValidationResult.Reason.FIVE_ZEROES, digest,
          "CW contains 5 to 7 zeroes in half-word");
    }
    boolean halfZero = isHalfZero(cw);
    if (halfZero && !allowZeroHalf) {
      return DcwValidationResult.invalid(DcwValidationResult.Reason.HALF_ZERO, digest, "Half-zero CW not allowed");
    }
    ChecksumMode mode = resolveEffectiveChecksumMode(profile, caid, provider, sid, sourceId, g);
    if (mode == ChecksumMode.REQUIRE_VALID) {
      if (!validateChecksumFull(cw)) {
        return DcwValidationResult.invalid(DcwValidationResult.Reason.CHECKSUM_FAIL, digest,
            "DVB checksum validation failed (REQUIRE_VALID)");
      }
    } else if (mode == ChecksumMode.REJECT_VALID) {
      boolean validCs = halfZero ? validateActiveHalfChecksum(cw) : validateChecksumFull(cw);
      if (validCs) {
        return DcwValidationResult.invalid(DcwValidationResult.Reason.CHECKSUM_VALID_REJECTED, digest,
            "Valid DVB checksum rejected (REJECT_VALID)");
      }
    }
    boolean observed = false;
    for (DcwRule rule : rules) {
      if (!rule.matches(digest, profile, caid, provider, sid, sourceId)) continue;
      if (rule.action() == DcwRuleAction.BLOCK) {
        return DcwValidationResult.invalid(DcwValidationResult.Reason.RULE_BLOCKED, digest,
            "CW blocked by rule #" + rule.id());
      }
      if (rule.action() == DcwRuleAction.OBSERVE) observed = true;
    }
    return DcwValidationResult.valid(digest, observed);
  }

  /**
   * Helper de réparation checksum (non câblé au runtime à ce checkpoint).
   * Conditions cumulatives : policy activée, repair != OFF, origines non vides et distinctes,
   * provider connu, mode REQUIRE_VALID EXPLICITE (règle ou défaut policy, jamais legacy/INHERIT),
   * candidat réellement invalide, deux demi-CW non nulles (candidat ET référence), référence
   * valide sous la policy (checksum, blacklist, structure), 12 octets de données identiques,
   * original et réparé non blacklistés. Ne mute jamais l'entrée.
   * <p>Limite connue : l'égalité de nom d'origine n'est PAS une preuve d'indépendance ; l'appelant
   * doit fournir une identité de transport autoritaire (checkpoint suivant).
   */
  public DcwRepairResult tryRepair(byte[] candidateCw, byte[] referenceCw, String candidateOrigin,
                                   String referenceOrigin, String profile, int caid, int provider, int sid) {
    if (!enabled || repairMode == DcwRepairMode.OFF) return DcwRepairResult.disabled();
    if (candidateOrigin == null || candidateOrigin.isBlank() || referenceOrigin == null || referenceOrigin.isBlank()) {
      return DcwRepairResult.of(DcwRepairResult.Status.UNTRUSTED_ORIGIN, null, "Origins must be identified");
    }
    if (candidateOrigin.trim().equalsIgnoreCase(referenceOrigin.trim())) {
      return DcwRepairResult.of(DcwRepairResult.Status.SAME_ORIGIN, null, "Corroboration must come from another origin");
    }
    if (candidateCw == null || candidateCw.length != 16 || referenceCw == null || referenceCw.length != 16) {
      return DcwRepairResult.of(DcwRepairResult.Status.INVALID_REFERENCE, null, "Invalid CW length");
    }
    if (isAllZero(candidateCw) || isHalfZero(candidateCw) || isAllZero(referenceCw) || isHalfZero(referenceCw)) {
      return DcwRepairResult.of(DcwRepairResult.Status.INELIGIBLE_HALF_ZERO, null, "Half-zero CW cannot be repaired");
    }
    if (provider == DcwRuleScope.UNKNOWN_PROVIDER
        || resolveExplicitChecksumMode(profile, caid, provider, sid, candidateOrigin) != ChecksumMode.REQUIRE_VALID) {
      return DcwRepairResult.of(DcwRepairResult.Status.INELIGIBLE_CHECKSUM_MODE, null, "Explicit REQUIRE_VALID required");
    }
    if (validateChecksumFull(candidateCw)) {
      return DcwRepairResult.of(DcwRepairResult.Status.NOT_NEEDED, null, "Candidate checksum already valid");
    }
    DcwValidationResult ref = validate(referenceCw, profile, caid, provider, sid, referenceOrigin, LegacyGuard.CHECKSUM_REQUIRED);
    if (!ref.isValid()) {
      return DcwRepairResult.of(ref.reason() == DcwValidationResult.Reason.RULE_BLOCKED
          ? DcwRepairResult.Status.RULE_BLOCKED : DcwRepairResult.Status.INVALID_REFERENCE, null, "Reference rejected");
    }
    if (!matchData12(candidateCw, referenceCw)) {
      return DcwRepairResult.of(DcwRepairResult.Status.DATA_MISMATCH, null, "12 data bytes do not match reference");
    }
    byte[] repaired = candidateCw.clone();
    for (int i = 0; i < 16; i += 4) {
      repaired[i + 3] = (byte) (((repaired[i] & 0xFF) + (repaired[i + 1] & 0xFF) + (repaired[i + 2] & 0xFF)) & 0xFF);
    }
    String origDigest = DcwDigest.digestSha256Hex(candidateCw);
    for (DcwRule rule : rules) {
      if (rule.action() == DcwRuleAction.BLOCK
          && rule.matches(origDigest, profile, caid, provider, sid, candidateOrigin)) {
        return DcwRepairResult.of(DcwRepairResult.Status.RULE_BLOCKED, null, "Original CW is blacklisted");
      }
    }
    DcwValidationResult rep = validate(repaired, profile, caid, provider, sid, candidateOrigin, LegacyGuard.CHECKSUM_REQUIRED);
    if (!rep.isValid()) {
      return DcwRepairResult.of(rep.reason() == DcwValidationResult.Reason.RULE_BLOCKED
          ? DcwRepairResult.Status.RULE_BLOCKED : DcwRepairResult.Status.INVALID_REFERENCE, null, "Repaired CW rejected");
    }
    if (repairMode == DcwRepairMode.OBSERVE) {
      return DcwRepairResult.of(DcwRepairResult.Status.OBSERVED, null, "Repair opportunity observed (not enforced)");
    }
    return DcwRepairResult.of(DcwRepairResult.Status.REPAIRED, repaired, "Checksum repaired with corroboration");
  }

  private static boolean matchData12(byte[] a, byte[] b) {
    for (int i = 0; i < 16; i++) {
      if ((i & 3) == 3) continue;
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  static boolean isAllZero(byte[] cw) {
    for (byte b : cw) if (b != 0) return false;
    return true;
  }

  static boolean isHalfZero(byte[] cw) {
    boolean first = true, second = true;
    for (int i = 0; i < 8; i++) if (cw[i] != 0) { first = false; break; }
    for (int i = 8; i < 16; i++) if (cw[i] != 0) { second = false; break; }
    return first || second;
  }

  static boolean hasFiveZeroes(byte[] cw) {
    int c1 = 0, c2 = 0;
    for (int i = 0; i < 8; i++) if (cw[i] == 0) c1++;
    for (int i = 8; i < 16; i++) if (cw[i] == 0) c2++;
    return (c1 > 4 && c1 < 8) || (c2 > 4 && c2 < 8);
  }

  public static boolean validateChecksumFull(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    for (int i = 0; i < 16; i += 4) {
      int expected = ((cw[i] & 0xFF) + (cw[i + 1] & 0xFF) + (cw[i + 2] & 0xFF)) & 0xFF;
      if ((cw[i + 3] & 0xFF) != expected) return false;
    }
    return true;
  }

  private static boolean validateActiveHalfChecksum(byte[] cw) {
    boolean firstZero = true;
    for (int i = 0; i < 8; i++) if (cw[i] != 0) { firstZero = false; break; }
    int start = firstZero ? 8 : 0;
    for (int i = start; i < start + 8; i += 4) {
      int expected = ((cw[i] & 0xFF) + (cw[i + 1] & 0xFF) + (cw[i + 2] & 0xFF)) & 0xFF;
      if ((cw[i + 3] & 0xFF) != expected) return false;
    }
    return true;
  }
}
