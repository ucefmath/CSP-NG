package com.bowman.cspng.web.admin;

import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fusion des paires worker dans /api/admin/cacheex/stats (C1) :
 * peerId préfixé node/, payloads absents/illisibles ignorés (jamais de zéros).
 */
@DisplayName("AdminCacheexResource — merge paires worker")
class AdminCacheexResourceTest {

  private AdminCacheexResource resource() {
    AdminCacheexResource res = new AdminCacheexResource();
    res.registry = new CacheExStatsRegistry();
    res.configRepository = null;
    res.mapper = new ObjectMapper();
    return res;
  }

  private static String workerPayload() {
    return "{"
        + "\"collectedAtMs\": 1700000000000,"
        + "\"caches\": [], \"nodes\": [],"
        + "\"cacheex\": [{"
        + "  \"peerId\": \"cx-up\", \"direction\": \"outbound\", \"protocol\": \"cccam\","
        + "  \"mode\": 3, \"profile\": \"default\", \"online\": true, \"state\": \"connected\","
        + "  \"maxHop\": 10, \"maxSendQueue\": 2000, \"filterActive\": true,"
        + "  \"pushedCw\": 42, \"receivedCw\": 7, \"droppedCw\": 3,"
        + "  \"dropReasons\": {\"loop\": 1, \"hop\": 0, \"checksum\": 2, \"filtered\": 0,"
        + "    \"invalid\": 0, \"queue\": 0},"
        + "  \"lastPushAtMs\": 1700000001000, \"lastReceiveAtMs\": null"
        + "}]"
        + "}";
  }

  @Test
  @DisplayName("Paires worker parsées, peerId préfixé, compteurs intacts")
  void workerPairsMergedWithNodePrefix() {
    var pairs = resource().parseWorkerPairs("worker-1", workerPayload());
    assertEquals(1, pairs.size());
    var p = pairs.get(0);
    assertEquals("worker-1/cx-up", p.peerId(), "préfixe node pour lever l'ambiguïté");
    assertEquals("outbound", p.direction());
    assertEquals("cccam", p.protocol());
    assertEquals(3, p.mode());
    assertTrue(p.online());
    assertEquals(42, p.pushedCw());
    assertEquals(7, p.receivedCw());
    assertEquals(3, p.droppedCw());
    assertEquals(1L, p.dropReasons().get("loop"));
    assertEquals(2L, p.dropReasons().get("checksum"));
    assertEquals(0L, p.dropReasons().get("hop"));
    assertEquals(0L, p.dropReasons().get("flood"), "raison flood présente (défaut 0 si absente)");
    assertEquals(1700000001000L, p.lastPushAtMs());
    assertNull(p.lastReceiveAtMs(), "null JSON => null (pas de 0 fictif)");
  }

  @Test
  @DisplayName("Payload absent/illisible/sans cacheex => liste vide (jamais de zéros)")
  void absentOrUnreadableYieldsEmpty() {
    var res = resource();
    assertTrue(res.parseWorkerPairs("w1", null).isEmpty());
    assertTrue(res.parseWorkerPairs("w1", "").isEmpty());
    assertTrue(res.parseWorkerPairs("w1", "not-json{{{").isEmpty());
    assertTrue(res.parseWorkerPairs("w1", "{\"caches\":[]}").isEmpty());
    assertTrue(res.parseWorkerPairs(null, workerPayload()).isEmpty());
  }

  @Test
  @DisplayName("Entrées sans peerId ignorées, autres conservées")
  void entriesWithoutPeerIdSkipped() {
    String payload = "{\"cacheex\": ["
        + "{\"direction\": \"outbound\", \"pushedCw\": 5},"
        + "{\"peerId\": \"ok\", \"direction\": \"inbound\", \"protocol\": \"cs378x\","
        + " \"mode\": 2, \"pushedCw\": 1}"
        + "]}";
    var pairs = resource().parseWorkerPairs("w2", payload);
    assertEquals(1, pairs.size());
    assertEquals("w2/ok", pairs.get(0).peerId());
  }

  @Test
  @DisplayName("Paires worker : identité distante, hits et SID résolus propagés (SID-map)")
  void workerAndCentralPairsIncludeRemoteIdentityHitsAndResolvedSids() {
    String jsonWithRemoteIpNodeIdHitsAndSids = "{\"cacheex\": [{"
        + "  \"peerId\": \"cx-up\", \"direction\": \"outbound\", \"protocol\": \"cccam\","
        + "  \"mode\": 3, \"profile\": \"default\", \"online\": true, \"state\": \"connected\","
        + "  \"remoteIp\": \"77.90.60.222\","
        + "  \"remoteNodeId\": \"1A2B3C4D5E6F7081\","
        + "  \"hits\": 5,"
        + "  \"sids\": [{"
        + "    \"caid\": 6276, \"provid\": 0, \"srvid\": 13020,"
        + "    \"pushedCw\": 0, \"receivedCw\": 10, \"hits\": 5, \"droppedCw\": 1,"
        + "    \"lastActivityAtMs\": 1700000000000"
        + "  }]"
        + "}]}";
    var pairs = resource().parseWorkerPairs("w1", jsonWithRemoteIpNodeIdHitsAndSids);
    assertEquals(1, pairs.size(), "une paire worker valide => une entrée");
    var p = pairs.get(0);
    assertEquals("77.90.60.222", p.remoteIp(), "IP distante du pair remontée par le worker");
    assertEquals("1A2B3C4D5E6F7081", p.remoteNodeId(), "identifiant de nœud distant remonté");
    assertEquals(5L, p.hits(), "hits servis depuis ce pair");
    assertEquals(1, p.sids().size(), "un SID résolu");
    var sid = p.sids().get(0);
    assertEquals("1884@000000:32DC", sid.serviceKey(), "clé de service canonique CAID@PROVID:SID");
    assertEquals(6276, sid.caid());
    assertEquals(13020, sid.srvid());
    assertEquals(10L, sid.receivedCw());
    assertEquals(5L, sid.hits());
    assertEquals(1L, sid.droppedCw());
  }

  @Test
  @DisplayName("Reset central ne touche que le registry local")
  void resetOnlyLocalRegistry() {
    var res = resource();
    res.registry.registerOutbound("c1", "cccam", 2, "p", 10, 2000);
    res.registry.countReceived("c1");
    assertEquals(1, res.registry.snapshot().size());
    res.reset().await().indefinitely();
    var p = res.registry.snapshot().get(0);
    assertEquals(0, p.receivedCw(), "compteurs remis à zéro");
    assertEquals("c1", p.peerId(), "paire conservée (pas de config touchée)");
  }
}
