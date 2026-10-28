package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.bowman.cspng.domain.dcw.DcwDigest;
import com.bowman.cspng.domain.dcw.LegacyGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Checkpoint 1-2 (reprise Opus) : contrat config DCW aligné domaine/DB/API/worker. */
@DisplayName("DCW config — contrat checkpoint 1-2")
class DcwConfigFoundationsTest {

  static final String D = "a".repeat(64);

  static DcwRuleConfig rule(long id, Integer provider, String digest, String action, String cm) {
    return new DcwRuleConfig(id, null, 0x1884, provider, 0, null, digest, action, cm, "", true, 1L, 0L);
  }

  static DcwPolicyConfig pol(List<DcwRuleConfig> rules, List<DcwServiceMappingConfig> maps) {
    return new DcwPolicyConfig(true, false, false, false, "inherit", "off", true, rules, maps, 1L, 0L);
  }

  static ProfileConfig prof(String name) {
    return new ProfileConfig(name, true, false, false, 0x1884, 0, false, false, 500L, null, false, 0, null, null, null, false, 1L);
  }

  static List<String> errs(DcwPolicyConfig p) {
    return ConfigValidator.validate(ConfigSnapshot.empty()
        .withProfiles(List.of(prof("p"), prof("profile1")))
        .withDcwPolicy(p));
  }

  @Test
  @DisplayName("Défauts : checksum inherit, action none ; snapshot vide valide")
  void defaults() {
    assertEquals("inherit", DcwPolicyConfig.DEFAULT.defaultChecksumMode());
    assertEquals("none", rule(1, null, null, null, "ignore").action());
    assertTrue(errs(DcwPolicyConfig.DEFAULT).isEmpty(), errs(DcwPolicyConfig.DEFAULT).toString());
    assertEquals(ChecksumMode.INHERIT, DcwPolicyConfig.DEFAULT.toDomain().defaultChecksumMode());
  }

  @Test
  @DisplayName("Action none + checksumMode scopé accepté (plus de boot-fail)")
  void noneActionAccepted() {
    var e = errs(pol(List.of(rule(1, 0x30, null, "none", "ignore")), List.of()));
    assertTrue(e.isEmpty(), e.toString());
  }

  @Test
  @DisplayName("Refus : block sans digest, allow, none sans effet, none avec digest, provider -1, caid hors borne")
  void invalidRulesRejected() {
    assertFalse(errs(pol(List.of(rule(1, null, null, "block", "inherit")), List.of())).isEmpty());
    assertFalse(errs(pol(List.of(rule(1, null, D, "allow", "inherit")), List.of())).isEmpty());
    assertFalse(errs(pol(List.of(rule(1, null, null, "none", "inherit")), List.of())).isEmpty());
    assertFalse(errs(pol(List.of(rule(1, null, D, "none", "ignore")), List.of())).isEmpty());
    assertFalse(errs(pol(List.of(rule(1, -1, D, "block", "inherit")), List.of())).isEmpty());
    var badCaid = new DcwRuleConfig(1, null, 70000, null, 0, null, D, "block", "inherit", "", true, 1L, 0L);
    assertFalse(errs(pol(List.of(badCaid), List.of())).isEmpty(), "pas d'IAE constructeur : erreur de validation");
    assertFalse(errs(pol(List.of(rule(1, null, "zz", "block", "inherit")), List.of())).isEmpty());
  }

  @Test
  @DisplayName("Provider null (wildcard) et 0 (légitime) distincts de bout en bout")
  void providerNullVsZero() {
    var p = pol(List.of(rule(1, 0, D, "block", "inherit")), List.of());
    assertTrue(errs(p).isEmpty(), errs(p).toString());
    var dom = p.toDomain();
    assertEquals(Integer.valueOf(0), dom.rules().get(0).scope().provider());
    assertNull(pol(List.of(rule(2, null, D, "block", "inherit")), List.of()).toDomain().rules().get(0).scope().provider());
  }

  @Test
  @DisplayName("Cap 1000 règles et 1000 mappings ; conflit de scope refusé")
  void capsAndConflicts() {
    List<DcwRuleConfig> many = new ArrayList<>();
    for (int i = 0; i < 1001; i++) many.add(new DcwRuleConfig(i, null, 1, null, (i % 65535) + 1, "s" + i, null, "none", "ignore", "", true, 1L, 0L));
    assertTrue(errs(pol(many, List.of())).stream().anyMatch(s -> s.contains("1000")));
    var a = rule(1, 0x30, null, "none", "ignore");
    var b = rule(2, 0x30, null, "none", "require_valid");
    assertTrue(errs(pol(List.of(a, b), List.of())).stream().anyMatch(s -> s.contains("ambiguous") || s.contains("share scope")));
    List<DcwServiceMappingConfig> maps = new ArrayList<>();
    for (int i = 0; i < 1001; i++) maps.add(new DcwServiceMappingConfig(i, "p", 1, 0, 1, 0, 1, (i % 65534) + 2, true, true, 0L, 1L, 0L));
    assertTrue(errs(pol(List.of(), maps)).stream().anyMatch(s -> s.contains("1000")));
  }

  @Test
  @DisplayName("Mappings : typés jusqu'au domaine ; source==target refusé ; zeroCounting propagé")
  void mappingsAndFlags() {
    var m = new DcwServiceMappingConfig(1, "p", 0x1884, 0x30, 0x1884, 0x30, 5, 6, true, true, 0L, 1L, 0L);
    var p = new DcwPolicyConfig(true, false, true, true, "inherit", "off", false, List.of(), List.of(m), 1L, 0L);
    var dom = p.toDomain();
    assertEquals(1, dom.mappings().size());
    assertTrue(dom.zeroCountingEnabled());
    assertTrue(dom.autoObserveEnabled());
    assertFalse(dom.allowZeroHalf());
    var self = new DcwServiceMappingConfig(1, "p", 0x1884, 0x30, 0x1884, 0x30, 5, 5, true, true, 0L, 1L, 0L);
    assertFalse(errs(pol(List.of(), List.of(self))).isEmpty());
  }

  @Test
  @DisplayName("Profils inconnus ou mal orthographiés rejetés si knownProfiles est fourni")
  void unknownProfilesRejectedWhenProvided() {
    var rGood = new DcwRuleConfig(1, "profile1", 0x1884, null, 0, null, null, "none", "ignore", "", true, 1L, 0L);
    var rBad = new DcwRuleConfig(2, "unknown_p", 0x1884, null, 0, null, null, "none", "ignore", "", true, 1L, 0L);
    var mGood = new DcwServiceMappingConfig(1, "profile1", 0x1884, 0x30, 0x1884, 0x30, 5, 6, true, true, 0L, 1L, 0L);
    var mBad = new DcwServiceMappingConfig(2, "unknown_p", 0x1884, 0x30, 0x1884, 0x30, 5, 6, true, true, 0L, 1L, 0L);

    var polGood = pol(List.of(rGood), List.of(mGood));
    var polBadRule = pol(List.of(rBad), List.of(mGood));
    var polBadMap = pol(List.of(rGood), List.of(mBad));

    var known = java.util.Set.of("profile1");
    assertTrue(DcwConfigRules.validatePolicy(polGood, known).isEmpty());
    assertTrue(DcwConfigRules.validatePolicy(polBadRule, known).stream().anyMatch(s -> s.contains("unknown profile")));
    assertTrue(DcwConfigRules.validatePolicy(polBadMap, known).stream().anyMatch(s -> s.contains("unknown profile")));

    // When known profiles is empty set, any non-null profile rule/map is rejected
    assertTrue(DcwConfigRules.validatePolicy(polGood, java.util.Set.of()).stream().anyMatch(s -> s.contains("unknown profile")));
  }

  @Test
  @DisplayName("DcwPolicyProvider : compile une fois par snapshot, garde le dernier bon si compilation invalide")
  void providerKeepsLastKnownGood() {
    byte[] cw = {1, 2, 3, 6, 4, 5, 6, 15, 7, 8, 9, 24, 10, 11, 12, 33};
    var block = new DcwRuleConfig(1, null, 0, null, 0, null, DcwDigest.digestSha256Hex(cw), "block", "inherit", "", true, 1L, 0L);
    AtomicReference<DcwPolicyConfig> src = new AtomicReference<>(pol(List.of(block), List.of()));
    var provider = new DcwPolicyProvider(src::get);
    var first = provider.get();
    assertSame(first, provider.get(), "memoïsé");
    assertFalse(first.validate(cw, "p", 1, 0, 1, "c", LegacyGuard.PERMISSIVE).isValid());
    // snapshot corrompu (contourne le validateur) : conflit -> compilation échoue
    src.set(pol(List.of(rule(1, 0x30, null, "none", "ignore"), rule(2, 0x30, null, "none", "require_valid")), List.of()));
    var after = provider.get();
    assertSame(first, after, "dernier bon conservé, pas de DEFAULT fail-open");
    assertEquals(1, provider.compileFailures());
  }

  // ---------------------------------------------------------------------------------------------
  // Contrat CAID:Provider : mapping global (sourceSid = targetSid = 0) entre deux couples distincts.
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Mapping CAID:Provider global : validateMapping/validatePolicy OK et targetCaid/targetProvider préservés jusqu'au domaine")
  void globalCaidProviderMappingValidated() {
    var m = new DcwServiceMappingConfig(10L, "p", 0x1884, 0x000000, 0x1813, 0x000068, 0, 0, true, true, 0L, 1L, 0L);
    assertTrue(DcwConfigRules.validateMapping(m).isEmpty(), DcwConfigRules.validateMapping(m).toString());
    assertTrue(errs(pol(List.of(), List.of(m))).isEmpty(), errs(pol(List.of(), List.of(m))).toString());

    var dom = m.toDomain();
    assertEquals(0x1884, dom.caid());
    assertEquals(0x000000, dom.provider());
    assertEquals(0x1813, dom.targetCaid());
    assertEquals(0x000068, dom.targetProvider());
    assertEquals(0, dom.sourceSid());
    assertEquals(0, dom.targetSid());
  }

  @Test
  @DisplayName("Mapping CAID:Provider : SID mixte (0/5, 5/0) et identité complète (y compris 0/0) rejetés par validateMapping")
  void invalidCaidProviderMappingRejected() {
    var mixedLow = new DcwServiceMappingConfig(11L, "p", 0x1884, 0x000000, 0x1813, 0x000068, 0, 5, true, true, 0L, 1L, 0L);
    var mixedHigh = new DcwServiceMappingConfig(12L, "p", 0x1884, 0x000000, 0x1813, 0x000068, 5, 0, true, true, 0L, 1L, 0L);
    var identity = new DcwServiceMappingConfig(13L, "p", 0x1884, 0x000000, 0x1884, 0x000000, 0, 0, true, true, 0L, 1L, 0L);

    assertFalse(DcwConfigRules.validateMapping(mixedLow).isEmpty(), "sourceSid=0 / targetSid=5 ambigu");
    assertFalse(DcwConfigRules.validateMapping(mixedHigh).isEmpty(), "sourceSid=5 / targetSid=0 ambigu");
    assertFalse(DcwConfigRules.validateMapping(identity).isEmpty(), "auto-mapping global refusé");
    assertFalse(errs(pol(List.of(), List.of(mixedLow))).isEmpty());
    assertFalse(errs(pol(List.of(), List.of(identity))).isEmpty());
  }
}
