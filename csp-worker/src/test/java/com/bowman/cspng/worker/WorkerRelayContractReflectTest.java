package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hotfix natif cwCycle : anti-oubli reflection + contrat JSON desired/ack/stats.
 * Tout nouveau record de {@link RelayWorkerContract} transitant sur le canal
 * desired/ack/stats DOIT être déclaré dans
 * {@code META-INF/native-image/reflect-config.json} avec
 * {@code allDeclaredConstructors/allDeclaredFields/allDeclaredMethods}
 * (introspection Jackson en natif, monde fermé GraalVM — cf. docs Quarkus
 * "Registering for reflection" : Jackson échoue sans enregistrement explicite).
 */
@DisplayName("Worker relay contract — reflection native exhaustive + JSON cwCycle/cacheex")
class WorkerRelayContractReflectTest {

  final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("reflect-config.json déclare TOUS les records RelayWorkerContract (anti-oubli dynamique)")
  void reflectConfigDeclaresAllRelayRecords() throws Exception {
    var stream = getClass().getResourceAsStream("/META-INF/native-image/reflect-config.json");
    assertNotNull(stream, "reflect-config.json doit être présent dans le classpath");
    var tree = mapper.readTree(stream);
    assertTrue(tree.isArray());
    var names = new java.util.HashSet<String>();
    for (var node : tree) {
      names.add(node.path("name").asText());
      // Conventions Jackson natif : même triplet que les autres records.
      if (node.path("name").asText().startsWith("com.bowman.cspng.config.RelayWorkerContract$")) {
        assertTrue(node.path("allDeclaredConstructors").asBoolean(false),
            node.path("name").asText() + " doit avoir allDeclaredConstructors=true");
        assertTrue(node.path("allDeclaredFields").asBoolean(false),
            node.path("name").asText() + " doit avoir allDeclaredFields=true");
        assertTrue(node.path("allDeclaredMethods").asBoolean(false),
            node.path("name").asText() + " doit avoir allDeclaredMethods=true");
      }
    }
    for (Class<?> nested : RelayWorkerContract.class.getDeclaredClasses()) {
      // Seuls les records transitent en JSON ; les classes utilitaires sont ignorées.
      if (!nested.isRecord()) continue;
      String binary = nested.getName();
      assertTrue(names.contains(binary), binary + " manquant dans reflect-config.json");
    }
    // Explicite pour message d'échec lisible sur les 3 records du hotfix.
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$CwCycleView"),
        "CwCycleView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$CwCycleStatsView"),
        "CwCycleStatsView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$CacheExPairStatsView"),
        "CacheExPairStatsView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$CacheExSidStatsView"),
        "CacheExSidStatsView manquant dans reflect-config.json");
  }

  @Test
  @DisplayName("Round-trip desired avec cwCycle explicite + ack/stats cwCycle + cacheex")
  void desiredAckStatsCwCycleRoundTrip() throws Exception {
    var cw = new RelayWorkerContract.CwCycleView(true, List.of(0x0100), 600, 20, "log", false, true);
    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "w1", 1L, System.currentTimeMillis() + 60000L, 30000L,
        List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), null, cw);
    String json = mapper.writeValueAsString(desired);
    assertTrue(json.contains("\"cwCycle\""), "desired doit transporter cwCycle : " + json);
    var back = mapper.readValue(json, RelayWorkerContract.DesiredWorkerConfig.class);
    assertNotNull(back.cwCycle());
    assertTrue(back.cwCycle().enabled());
    assertEquals(List.of(0x0100), back.cwCycle().caids());
    assertEquals(600, back.cwCycle().maxList());

    var cwStats = new RelayWorkerContract.CwCycleStatsView(true, 3, 10L, 2L, 1L, 1L, 0L);
    var pair = new RelayWorkerContract.CacheExPairStatsView(
        "L7:alice", "in", "cccam", 3, "default", true, "ok", 10, 32, true,
        5L, 4L, 1L, Map.of("invalid", 1L), 123L, 456L);
    var pq = new RelayWorkerContract.PeerQualityStatView("connA", 10L, 1L, 11L, 909, 300_000L, false);
    var ch = new RelayWorkerContract.CacheHitOriginStatView("10.0.0.1:12000", 42L, 789L);
    var qEntry = new RelayWorkerContract.PeerQuarantineStatView("connQ", "ratio", 100L, 200L, false);
    var qmStats = new RelayWorkerContract.QuorumStatsView(5, 10L, 8L, 2L, 0L, 0L);
    var stats = new RelayWorkerContract.WorkerStatsView(
        List.of(), null, List.of(), List.of(), List.of(pair), cwStats,
        List.of(pq), List.of(ch), List.of(qEntry), qmStats, System.currentTimeMillis());
    var ack = new RelayWorkerContract.WorkerAck("w1", 1L, true, null, stats);
    String ackJson = mapper.writeValueAsString(ack);
    assertTrue(ackJson.contains("\"cwCycle\""), "ack.stats doit transporter cwCycle : " + ackJson);
    assertTrue(ackJson.contains("\"cacheex\""), "ack.stats doit transporter cacheex : " + ackJson);
    assertTrue(ackJson.contains("\"peerQuality\""), "ack.stats doit transporter peerQuality : " + ackJson);
    assertTrue(ackJson.contains("\"cacheHits\""), "ack.stats doit transporter cacheHits : " + ackJson);
    assertTrue(ackJson.contains("\"quarantine\""), "ack.stats doit transporter quarantine : " + ackJson);
    assertTrue(ackJson.contains("\"quorum\""), "ack.stats doit transporter quorum : " + ackJson);
    var backAck = mapper.readValue(ackJson, RelayWorkerContract.WorkerAck.class);
    assertNotNull(backAck.stats());
    assertNotNull(backAck.stats().cwCycle());
    assertTrue(backAck.stats().cwCycle().enabled());
    assertEquals(1, backAck.stats().cacheex().size());
    assertEquals("L7:alice", backAck.stats().cacheex().get(0).peerId());
    assertEquals(1, backAck.stats().peerQuality().size());
    assertEquals("connA", backAck.stats().peerQuality().get(0).peerId());
    assertEquals(1, backAck.stats().cacheHits().size());
    assertEquals("10.0.0.1:12000", backAck.stats().cacheHits().get(0).origin());
    assertEquals(1, backAck.stats().quarantine().size());
    assertEquals("connQ", backAck.stats().quarantine().get(0).peerId());
    assertNotNull(backAck.stats().quorum());
    assertEquals(5, backAck.stats().quorum().keys());
  }

  @Test
  @DisplayName("Compat ancien JSON sans cwCycle/cacheex : défauts sûrs, jamais null bloquant")
  void oldJsonWithoutNewFieldsDefaults() throws Exception {
    long exp = System.currentTimeMillis() + 60000L;
    String oldDesired = "{\"nodeId\":\"w1\",\"desiredVersion\":1,\"expiresAtMs\":" + exp
        + ",\"ttlMs\":30000,\"listeners\":[],\"caches\":[],\"links\":[],\"connectors\":[],"
        + "\"users\":[],\"userSecrets\":[],\"catalog\":[],\"proxies\":[],\"nodes\":[]}";
    var d = mapper.readValue(oldDesired, RelayWorkerContract.DesiredWorkerConfig.class);
    assertNotNull(d.cwCycle(), "ancien desired sans cwCycle => DEFAULT, jamais null");
    assertFalse(d.cwCycle().enabled());
    assertNotNull(d.dcwPolicy());

    String oldStats = "{\"connectors\":[],\"caches\":[],\"nodes\":[],\"collectedAtMs\":1}";
    var s = mapper.readValue(oldStats, RelayWorkerContract.WorkerStatsView.class);
    assertNotNull(s.connectors());
    assertNotNull(s.cacheex(), "ancien stats sans cacheex => liste vide");
    assertNull(s.cwCycle(), "ancien stats sans cwCycle => null honnête (jamais configuré)");
  }

  @Test
  @DisplayName("V16 : vues quarantine/quorum déclarées, round-trip + défauts ancien JSON")
  void v16QuarantineQuorumViews() throws Exception {
    var stream = getClass().getResourceAsStream("/META-INF/native-image/reflect-config.json");
    assertNotNull(stream);
    var tree = mapper.readTree(stream);
    var names = new java.util.HashSet<String>();
    for (var node : tree) names.add(node.path("name").asText());
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$PeerQuarantineView"),
        "PeerQuarantineView manquant dans reflect-config.json");
    assertTrue(names.contains("com.bowman.cspng.config.RelayWorkerContract$CacheExQuorumView"),
        "CacheExQuorumView manquant dans reflect-config.json");

    var quarantine = new RelayWorkerContract.PeerQuarantineView(
        true, "enforce", 20, 8_000, 120_000L, 300_000L, 128);
    var quorum = new RelayWorkerContract.CacheExQuorumView(
        "observe", 3, 15_000L, 512, List.of("prof"), List.of(0x100));
    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "w1", 2L, System.currentTimeMillis() + 60000L, 30000L,
        List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        null, null, quarantine, quorum);
    String json = mapper.writeValueAsString(desired);
    assertTrue(json.contains("\"quarantine\""), "desired doit transporter quarantine");
    assertTrue(json.contains("\"quorum\""), "desired doit transporter quorum");
    var back = mapper.readValue(json, RelayWorkerContract.DesiredWorkerConfig.class);
    assertTrue(back.quarantine().enabled());
    assertEquals("enforce", back.quarantine().mode());
    assertEquals("observe", back.quorum().mode());
    assertEquals(3, back.quorum().minOrigins());

    long exp = System.currentTimeMillis() + 60000L;
    String oldDesired = "{\"nodeId\":\"w1\",\"desiredVersion\":1,\"expiresAtMs\":" + exp
        + ",\"ttlMs\":30000,\"listeners\":[],\"caches\":[],\"links\":[],\"connectors\":[],"
        + "\"users\":[],\"userSecrets\":[],\"catalog\":[],\"proxies\":[],\"nodes\":[]}";
    var d = mapper.readValue(oldDesired, RelayWorkerContract.DesiredWorkerConfig.class);
    assertNotNull(d.quarantine(), "ancien desired sans quarantine => DEFAULT");
    assertFalse(d.quarantine().enabled(), "quarantaine off par défaut");
    assertNotNull(d.quorum(), "ancien desired sans quorum => DEFAULT");
    assertEquals("off", d.quorum().mode(), "quorum off par défaut");
  }

  @Test
  @DisplayName("V16 : WorkerState applique quarantine/quorum relayés, rejette l'invalide")
  void workerStateAppliesV16() {
    var state = new WorkerState("w1");
    assertFalse(state.quarantine().enabled());
    assertEquals("off", state.quorum().mode());
    long exp = System.currentTimeMillis() + 60000L;
    var quarantine = new RelayWorkerContract.PeerQuarantineView(
        true, "observe", 10, 5_000, 60_000L, 120_000L, 64);
    var quorum = new RelayWorkerContract.CacheExQuorumView(
        "enforce", 2, 10_000L, 256, List.of(), List.of());
    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "w1", 1L, exp, 30000L,
        List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(),
        null, null, quarantine, quorum);
    assertTrue(state.applyDesired(desired, System.currentTimeMillis()));
    assertTrue(state.quarantine().enabled());
    assertEquals("observe", state.quarantine().mode());
    assertEquals("enforce", state.quorum().mode());
  }

  @Test
  @DisplayName("B2 & B3 : WorkerAck logs compatibilité wire bidirectionnelle et garde-fous")
  void workerAckLogsWireCompatibilityAndBounds() throws Exception {
    var strictMapper = new ObjectMapper()
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    // 1. WorkerAck sans logs : sérialisation omet le champ "logs"
    var ackEmptyLogs = new RelayWorkerContract.WorkerAck("w1", 1L, true, null, null, List.of());
    String jsonNoLogs = mapper.writeValueAsString(ackEmptyLogs);
    assertFalse(jsonNoLogs.contains("\"logs\""), "logs vide doit être omis du JSON : " + jsonNoLogs);

    // 2. Ancien JSON (sans logs) désérialisé par mapper strict
    String oldJson = "{\"nodeId\":\"w1\",\"appliedVersion\":1,\"ok\":true,\"error\":null,\"stats\":null}";
    var backFromOld = strictMapper.readValue(oldJson, RelayWorkerContract.WorkerAck.class);
    assertNotNull(backFromOld.logs());
    assertTrue(backFromOld.logs().isEmpty());

    // 3. Nouveau JSON (avec logs) désérialisé par mapper strict
    var logEntry = new RelayWorkerContract.WorkerLogEntry(1, 1000L, "INFO", "w.test", "hello world");
    var ackWithLogs = new RelayWorkerContract.WorkerAck("w1", 1L, true, null, null, List.of(logEntry));
    String jsonWithLogs = mapper.writeValueAsString(ackWithLogs);
    assertTrue(jsonWithLogs.contains("\"logs\""));
    var backFromNew = strictMapper.readValue(jsonWithLogs, RelayWorkerContract.WorkerAck.class);
    assertEquals(1, backFromNew.logs().size());
    assertEquals("hello world", backFromNew.logs().get(0).message());

    // 4. Bornes et troncatures
    var longLog = new RelayWorkerContract.WorkerLogEntry(
        2, 2000L, "VERY_LONG_LEVEL_NAME_EXCEEDING_LIMIT",
        "a".repeat(200), "m".repeat(5000));
    assertEquals(16, longLog.level().length());
    assertEquals(128, longLog.logger().length());
    assertEquals(2048, longLog.message().length());

    // 5. Rejet si > 200 logs
    var manyLogs = new java.util.ArrayList<RelayWorkerContract.WorkerLogEntry>();
    for (int i = 0; i < 201; i++) {
      manyLogs.add(new RelayWorkerContract.WorkerLogEntry(i, 1000L, "INFO", "test", "msg"));
    }
    assertThrows(IllegalArgumentException.class, () ->
        new RelayWorkerContract.WorkerAck("w1", 1L, true, null, null, manyLogs));
  }

  /**
   * SID-map : extension ADDITIVE de {@link RelayWorkerContract.CacheExPairStatsView}
   * (remoteIp/remoteNodeId/hits/sids). Un worker à jour doit rester lisible par un central
   * v1.0.0 : la forme 16 clés doit être strictement préservée quand les champs v2 sont absents
   * (omission NON_NULL), et l'ack doit tenir dans le budget HTTP même au pire cas.
   */
  @Test
  @DisplayName("SID-map : CacheExPairStatsView v2 additif, JSON v1 strict (16 clés) et budget ack 256 KiB")
  void cacheExPairStatsViewV2RoundTripAndV1JsonCompatibility() throws Exception {
    // 1. Forme v1.0.0 (constructeur 16 args) : AUCUNE clé v2 ne doit apparaître.
    var v1 = new RelayWorkerContract.CacheExPairStatsView(
        "L7:alice", "in", "cccam", 3, "default", true, "ok", 10, 32, true,
        5L, 4L, 1L, Map.of("invalid", 1L), 123L, 456L);
    String v1Json = mapper.writeValueAsString(v1);
    var v1Tree = mapper.readTree(v1Json);
    assertFalse(v1Tree.has("remoteIp"), "remoteIp doit être omis quand absent : " + v1Json);
    assertFalse(v1Tree.has("remoteNodeId"), "remoteNodeId doit être omis quand absent : " + v1Json);
    assertFalse(v1Tree.has("hits"), "hits doit être omis quand absent : " + v1Json);
    assertFalse(v1Tree.has("sids"), "sids doit être omis quand absent : " + v1Json);
    assertEquals(16, v1Tree.size(),
        "une paire sans champs v2 doit émettre strictement les 16 clés v1.0.0 : " + v1Json);

    // 2. Forme v2 complète : peer distant + compteurs de hits + stats par SID.
    var sid = new RelayWorkerContract.CacheExSidStatsView(
        0x1884, 0, 0x32DC, 1, 10, 5, 0, 1700000000000L);
    var v2 = new RelayWorkerContract.CacheExPairStatsView(
        "L7:alice", "in", "cccam", 3, "default", true, "ok", 10, 32, true,
        5L, 4L, 1L, Map.of("invalid", 1L), 123L, 456L,
        "77.90.60.222", "1A2B3C4D5E6F7081", 5L, List.of(sid));
    String v2Json = mapper.writeValueAsString(v2);
    assertTrue(v2Json.contains("\"remoteIp\""), "remoteIp attendu en v2 : " + v2Json);
    assertTrue(v2Json.contains("\"remoteNodeId\""), "remoteNodeId attendu en v2 : " + v2Json);
    assertTrue(v2Json.contains("\"hits\""), "hits attendu en v2 : " + v2Json);
    assertTrue(v2Json.contains("\"sids\""), "sids attendu en v2 : " + v2Json);
    var v2Back = mapper.readValue(v2Json, RelayWorkerContract.CacheExPairStatsView.class);
    assertEquals("77.90.60.222", v2Back.remoteIp());
    assertEquals("1A2B3C4D5E6F7081", v2Back.remoteNodeId());
    assertEquals(Long.valueOf(5L), v2Back.hits());
    assertNotNull(v2Back.sids(), "sids doit survivre au round-trip");
    assertEquals(1, v2Back.sids().size());
    assertEquals(sid, v2Back.sids().get(0), "stats par SID identiques après round-trip");
    assertEquals("L7:alice", v2Back.peerId());
    assertEquals(5L, v2Back.pushedCw());

    // 3. Repli v1 : stripCacheExV2Fields retire les champs v2 des paires CacheEx.
    var ack = new RelayWorkerContract.WorkerAck("w1", 1L, true, null,
        new RelayWorkerContract.WorkerStatsView(
            List.of(), null, List.of(), List.of(), List.of(v2), null,
            List.of(), List.of(), List.of(), null, 1L),
        List.of());
    var stripped = WorkerClient.stripCacheExV2Fields(ack);
    assertNotNull(stripped);
    assertNotNull(stripped.stats());
    assertEquals(1, stripped.stats().cacheex().size(), "les paires restent présentes (sans v2)");
    var strippedPair = stripped.stats().cacheex().get(0);
    assertEquals("L7:alice", strippedPair.peerId());
    assertNull(strippedPair.remoteIp(), "remoteIp retiré");
    assertNull(strippedPair.remoteNodeId(), "remoteNodeId retiré");
    assertNull(strippedPair.hits(), "hits retiré");
    assertTrue(strippedPair.sids() == null || strippedPair.sids().isEmpty(), "sids retirés");
    String strippedJson = mapper.writeValueAsString(stripped);
    assertFalse(strippedJson.contains("remoteIp"), strippedJson);
    assertFalse(strippedJson.contains("remoteNodeId"), strippedJson);
    assertFalse(strippedJson.contains("\"hits\""), strippedJson);
    assertFalse(strippedJson.contains("\"sids\""), strippedJson);

    // 4. Budget HTTP : 200 logs de 2000 octets + 64 paires × 16 SIDs saturés => <= 256 KiB.
    var sids = new java.util.ArrayList<RelayWorkerContract.CacheExSidStatsView>();
    for (int s = 0; s < 16; s++) {
      sids.add(new RelayWorkerContract.CacheExSidStatsView(
          0x1884, 0, 0x100 + s, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
          Long.MAX_VALUE));
    }
    var pairs = new java.util.ArrayList<RelayWorkerContract.CacheExPairStatsView>();
    for (int p = 0; p < 64; p++) {
      pairs.add(new RelayWorkerContract.CacheExPairStatsView(
          "peer-" + p, "in", "cccam", 3, "default", true, "ok", 10, 32, true,
          Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Map.of("invalid", Long.MAX_VALUE),
          Long.MAX_VALUE, Long.MAX_VALUE,
          "77.90.60." + p, String.format("1A2B3C4D5E6F70%02X", p), Long.MAX_VALUE, List.copyOf(sids)));
    }
    var logs = new java.util.ArrayList<RelayWorkerContract.WorkerLogEntry>();
    for (int i = 0; i < 200; i++) {
      logs.add(new RelayWorkerContract.WorkerLogEntry(
          i, 1700000000000L, "ERROR", "x".repeat(20), "m".repeat(2000)));
    }
    var worstCaseAck = new RelayWorkerContract.WorkerAck("w1", 1L, true, null,
        new RelayWorkerContract.WorkerStatsView(
            List.of(), null, List.of(), List.of(), pairs, null,
            List.of(), List.of(), List.of(), null, Long.MAX_VALUE),
        logs);
    String budgeted = WorkerClient.serializeAckWithBudget(mapper, worstCaseAck, 262_144);
    assertNotNull(budgeted);
    int budgetedBytes = budgeted.getBytes(StandardCharsets.UTF_8).length;
    assertTrue(budgetedBytes <= 262_144,
        "ack sérialisé doit tenir dans 262144 octets, obtenu " + budgetedBytes);
    var budgetedBack = mapper.readValue(budgeted, RelayWorkerContract.WorkerAck.class);
    assertEquals("w1", budgetedBack.nodeId());
    assertTrue(budgetedBack.ok());
  }
}
