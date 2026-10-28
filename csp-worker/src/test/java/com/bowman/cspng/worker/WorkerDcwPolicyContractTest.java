package com.bowman.cspng.worker;

import com.bowman.cspng.config.DcwPolicyConfig;
import com.bowman.cspng.config.DcwServiceMappingConfig;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.domain.dcw.ChecksumMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checkpoint 2 : contrat desired worker DCW (JSON, rétro-compat, typage, fail-closed). */
@DisplayName("Worker DCW contract — JSON / compat / fail-closed")
class WorkerDcwPolicyContractTest {

  final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("Desired sans dcwPolicy (central ancien) => défaut INHERIT (gardes historiques)")
  void absentPolicyDefaults() throws Exception {
    var d = new RelayWorkerContract.DesiredWorkerConfig("w1", 1, System.currentTimeMillis() + 60_000, 60_000,
        List.of(), List.of(), List.of(), List.of());
    String json = mapper.writeValueAsString(d).replaceAll(",\"dcwPolicy\":\\{.*\\}\\}$", "}");
    assertFalse(json.contains("dcwPolicy"), json);
    var back = mapper.readValue(json, RelayWorkerContract.DesiredWorkerConfig.class);
    assertEquals(ChecksumMode.INHERIT, back.dcwPolicy().toDomain().defaultChecksumMode());
  }

  @Test
  @DisplayName("Round-trip JSON : provider null vs 0, mappings, zeroCounting conservés")
  void roundTrip() throws Exception {
    var r1 = new RelayWorkerContract.DcwRuleView(1, "p", 0x1884, null, 0, null, null, "none", "ignore", "", true);
    var r2 = new RelayWorkerContract.DcwRuleView(2, "p", 0x1884, 0, 0, null, "b".repeat(64), "block", "inherit", "", true);
    var m = new RelayWorkerContract.DcwMappingView(3, "p", 0x1884, 0x30, 5, 6, true, true, 0L);
    var pv = new RelayWorkerContract.DcwPolicyView(true, false, true, true, "inherit", "off", true, List.of(r1, r2), List.of(m));
    var d = new RelayWorkerContract.DesiredWorkerConfig("w1", 1, System.currentTimeMillis() + 60_000, 60_000,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), pv);
    var back = mapper.readValue(mapper.writeValueAsString(d), RelayWorkerContract.DesiredWorkerConfig.class);
    var dom = back.dcwPolicy().toDomain();
    assertNull(dom.rules().get(0).scope().provider());
    assertEquals(Integer.valueOf(0), dom.rules().get(1).scope().provider());
    assertEquals(1, dom.mappings().size());
    assertTrue(dom.zeroCountingEnabled());
    assertTrue(dom.autoObserveEnabled());
  }

  @Test
  @DisplayName("Policy invalide (conflit / block sans digest) : toDomain échoue explicitement (pas de policy vidée)")
  void invalidPolicyFailsExplicitly() {
    var a = new RelayWorkerContract.DcwRuleView(1, "p", 1, 0x30, 0, null, null, "none", "ignore", "", true);
    var b = new RelayWorkerContract.DcwRuleView(2, "p", 1, 0x30, 0, null, null, "none", "require_valid", "", true);
    var pv = new RelayWorkerContract.DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(a, b), List.of());
    assertThrows(IllegalArgumentException.class, pv::toDomain);
    var noDigest = new RelayWorkerContract.DcwRuleView(3, null, 0, null, 0, null, null, "block", "inherit", "", true);
    var pv2 = new RelayWorkerContract.DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(noDigest), List.of());
    assertThrows(IllegalArgumentException.class, pv2::toDomain);
  }

  @Test
  @DisplayName("Policy présente mais mode manquant ou règles nulles : pas de défaut silencieux")
  void presentMalformedPolicyMustNotBecomeDefault() {
    var missingMode = new RelayWorkerContract.DcwPolicyView(true, false, false, false,
        null, "off", true, List.of(), List.of());
    assertThrows(IllegalArgumentException.class, missingMode::toDomain);
    var missingRules = new RelayWorkerContract.DcwPolicyView(true, false, false, false,
        "inherit", "off", true, null, List.of());
    assertThrows(IllegalArgumentException.class, missingRules::toDomain);
  }

  @Test
  @DisplayName("WorkerState : desired avec policy DCW invalide refusé entier, policy précédente conservée ; valide appliquée atomiquement")
  void workerStateFailClosedOnInvalidPolicy() {
    var st = new WorkerState("w1");
    long now = System.currentTimeMillis();
    var block = new RelayWorkerContract.DcwRuleView(1, null, 0, null, 0, null, "c".repeat(64), "block", "inherit", "", true);
    var good = new RelayWorkerContract.DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(block), List.of());
    var d1 = new RelayWorkerContract.DesiredWorkerConfig("w1", 1, now + 60_000, 60_000, List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), good);
    assertTrue(st.validateDesired(d1, now));
    assertTrue(st.applyDesired(d1, now));
    assertEquals(1, st.dcwPolicy().rules().size(), "policy appliquée avec le desired");
    var bad = new RelayWorkerContract.DcwRuleView(2, null, 0, null, 0, null, null, "block", "inherit", "", true);
    var badPv = new RelayWorkerContract.DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(bad), List.of());
    var d2 = new RelayWorkerContract.DesiredWorkerConfig("w1", 2, now + 60_000, 60_000, List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), badPv);
    assertFalse(st.validateDesired(d2, now), "pré-validation refuse");
    assertFalse(st.applyDesired(d2, now), "apply refuse");
    assertEquals(1, st.appliedDesired());
    assertEquals(1, st.dcwPolicy().rules().size(), "dernier bon conservé");
  }

  @Test
  @DisplayName("Ordre de déploiement obligatoire (WORKERS D'ABORD) : ancien parser sans dcwPolicy échoue sur desired V13 avec FAIL_ON_UNKNOWN_PROPERTIES")
  void oldWorkerParserRejectsNewDesiredJson() throws Exception {
    var strictOldMapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    // DTO d'un ancien worker (base 717c6dd) ignorant le champ dcwPolicy
    record LegacyDesiredConfig(String nodeId, long desiredVersion, long expiresAtMs, long ttlMs,
                               List<Object> listeners, List<Object> caches, List<Object> links,
                               List<Object> connectors, List<Object> users, List<Object> userSecrets,
                               List<Object> catalog, List<Object> proxies, List<Object> nodes) {}

    var r = new RelayWorkerContract.DcwRuleView(1, "p", 0x1884, null, 0, null, null, "none", "ignore", "", true);
    var pv = new RelayWorkerContract.DcwPolicyView(true, false, false, false, "inherit", "off", true, List.of(r), List.of());
    var newDesired = new RelayWorkerContract.DesiredWorkerConfig("w1", 1, System.currentTimeMillis() + 60_000, 60_000,
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), pv);
    String newJson = mapper.writeValueAsString(newDesired);

    // L'ancien worker rejette la nouvelle payload à cause de "dcwPolicy" inconnu
    assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
        () -> strictOldMapper.readValue(newJson, LegacyDesiredConfig.class));

    // Le nouveau worker accepte l'ancien JSON (sans dcwPolicy) et applique les défauts sûrs
    String oldJson = "{\"nodeId\":\"w1\",\"desiredVersion\":1,\"expiresAtMs\":" + (System.currentTimeMillis() + 60_000)
        + ",\"ttlMs\":60000,\"listeners\":[],\"caches\":[],\"links\":[],\"connectors\":[],\"users\":[],\"userSecrets\":[],\"catalog\":[],\"proxies\":[],\"nodes\":[]}";
    var parsedByNewWorker = mapper.readValue(oldJson, RelayWorkerContract.DesiredWorkerConfig.class);
    assertNotNull(parsedByNewWorker.dcwPolicy());
    assertEquals(ChecksumMode.INHERIT, parsedByNewWorker.dcwPolicy().toDomain().defaultChecksumMode());
  }

  @Test
  @DisplayName("Non-régression reflection native : reflect-config.json doit déclarer DcwPolicyView, DcwRuleView, DcwMappingView")
  void reflectConfigDeclaresDcwViews() throws Exception {
    var stream = getClass().getResourceAsStream("/META-INF/native-image/reflect-config.json");
    assertNotNull(stream, "reflect-config.json doit être présent dans le classpath");
    var tree = mapper.readTree(stream);
    assertTrue(tree.isArray());
    var names = new java.util.HashSet<String>();
    for (var node : tree) {
      names.add(node.path("name").asText());
    }
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$DcwPolicyView"),
        "DcwPolicyView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$DcwRuleView"),
        "DcwRuleView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$DcwMappingView"),
        "DcwMappingView manquant dans reflect-config.json");
  }

  /**
   * SID-map : le mapping cross-CAID (source et cible de couples CAID:Provider distincts) doit
   * traverser le canal worker, et la forme v1.0.0 (target implicite = caid/provider) doit rester
   * strictement inchangée sur le fil : un central ancien ignore des champs absents, il ne doit
   * jamais recevoir {@code targetCaid}/{@code targetProvider} redondants.
   */
  @Test
  @DisplayName("SID-map : mapping cross-CAID conservé, forme v1 (cible = source) inchangée sur le fil")
  void dcwMappingViewCrossCaidAndV1BackwardCompatibility() throws Exception {
    // 1. Mapping même couple (SID -> SID) : cible implicite => champs v2 nuls et omis du JSON.
    var sameCaid = new DcwServiceMappingConfig(
        7L, "p", 0x1884, 0, 0x1884, 0, 0x100, 0x200, true, true, 0L, 1L, 0L);
    var pvV1 = RelayWorkerContract.DcwPolicyView.from(
        new DcwPolicyConfig(true, false, false, false, "inherit", "off", true,
            List.of(), List.of(sameCaid), 1L, 0L), null);
    assertEquals(1, pvV1.mappings().size());
    var mvV1 = pvV1.mappings().get(0);
    assertNull(mvV1.targetCaid(), "targetCaid identique à caid => normalisé null dans la vue");
    assertNull(mvV1.targetProvider(), "targetProvider identique à provider => normalisé null dans la vue");
    String mvV1Json = mapper.writeValueAsString(mvV1);
    assertFalse(mvV1Json.contains("targetCaid"), "targetCaid omis (NON_NULL) : " + mvV1Json);
    assertFalse(mvV1Json.contains("targetProvider"), "targetProvider omis (NON_NULL) : " + mvV1Json);
    var sameCfg = mvV1.toConfig();
    assertEquals(0x1884, sameCfg.targetCaid(), "toConfig normalise targetCaid = caid");
    assertEquals(0, sameCfg.targetProvider(), "toConfig normalise targetProvider = provider");
    assertEquals(0x100, sameCfg.sourceSid());
    assertEquals(0x200, sameCfg.targetSid());

    // 2. Mapping cross-CAID global (1L, "p", 0x1884, 0, 0x1813, 0x68, 0, 0, true, true, 0L, 1L, 0L).
    var crossCaid = new DcwServiceMappingConfig(
        1L, "p", 0x1884, 0, 0x1813, 0x68, 0, 0, true, true, 0L, 1L, 0L);
    var pvV2 = RelayWorkerContract.DcwPolicyView.from(
        new DcwPolicyConfig(true, false, false, false, "inherit", "off", true,
            List.of(), List.of(crossCaid), 1L, 0L), null);
    assertEquals(1, pvV2.mappings().size());
    var mvV2 = pvV2.mappings().get(0);
    assertEquals(Integer.valueOf(0x1813), mvV2.targetCaid(), "cible CAID explicite conservée");
    assertEquals(Integer.valueOf(0x68), mvV2.targetProvider(), "cible provider explicite conservée");

    String json = mapper.writeValueAsString(pvV2);
    var back = mapper.readValue(json, RelayWorkerContract.DcwPolicyView.class);
    var dom = back.toDomain();
    assertEquals(1, dom.mappings().size());
    var m = dom.mappings().get(0);
    assertEquals(0x1884, m.caid());
    assertEquals(0, m.provider());
    assertEquals(0x1813, m.targetCaid(), "targetCaid cross-CAID conservé après round-trip JSON");
    assertEquals(0x68, m.targetProvider(), "targetProvider cross-CAID conservé après round-trip JSON");
    assertEquals(0, m.sourceSid());
    assertEquals(0, m.targetSid());
  }
}
