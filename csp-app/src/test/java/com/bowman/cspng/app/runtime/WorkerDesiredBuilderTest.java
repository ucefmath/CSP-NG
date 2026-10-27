package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.CacheConfig;
import com.bowman.cspng.config.CaProfilesGlobalConfig;
import com.bowman.cspng.config.ClusterUdpConfig;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectionManagerConfig;
import com.bowman.cspng.config.ConnectorConfig;
import com.bowman.cspng.config.ExtendedNewcamdConfig;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.LoggingConfig;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.config.RelayNodeConnectorAssignment;
import com.bowman.cspng.config.RelayNodeListenerAssignment;
import com.bowman.cspng.config.RelayUdpPeerConfig;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.config.RootConfig;
import com.bowman.cspng.config.WebUiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


@DisplayName("WorkerDesiredBuilder — filtrage strict par affectations, sans secret en valeur")
class WorkerDesiredBuilderTest {

  private static ConfigSnapshot snap() {
    ProfileConfig p = new ProfileConfig("default", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileConfig p2 = new ProfileConfig("other", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileListenerConfig li = new ProfileListenerConfig(7L, "default", "cccam", 12000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    ProfileListenerConfig li2 = new ProfileListenerConfig(8L, "other", "cs378x", 13000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    ConnectorConfig conn = new ConnectorConfig("up1", "default", "newcamd", true, false,
        "upstream.example", 4321, "user1", "env:UP1_PASS", null, 5000, 10000, null, null, null,
        null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L, null,
        false, null, "legacy", null);
    ConnectorConfig conn2 = new ConnectorConfig("up2", "other", "cccam", true, false,
        "upstream2.example", 4322, "user2", "env:UP2_PASS", null, 5000, 10000, null, null, null,
        null, null, null, false, null, "2.1.4", null, null, null, null, null, 50, 0, 1L, null,
        false, null, "legacy", null);
    var node = new RelayNodeConfig("peer-a", null, true, true, false, 3L, null, 1L);
    var nodeB = new RelayNodeConfig("peer-b", null, true, true, false, 1L, null, 1L);
    var cache = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var ucache = new RelayCacheConfig("u1", "udp", null, true, 8000L, 10000, 512, 1L);
    var ncache = new RelayCacheConfig("n1", "node", "peer-b", true, 8000L, 10000, 512, 1L);
    var link = new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L);
    var link2 = new ListenerCacheLinkConfig(8L, "c1", true, true, false, true, 1L);
    var ulink = new ListenerCacheLinkConfig(7L, "u1", true, true, true, true, 1L);
    var nlink = new ListenerCacheLinkConfig(7L, "n1", true, false, false, true, 1L);
    var peers = List.of(new RelayUdpPeerConfig(1L, "u1", "127.0.0.1", 14501, true, 1L));
    return new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(p, p2), List.of(li, li2), List.of(), ConnectionManagerConfig.DEFAULT, List.of(),
        List.of(conn, conn2), List.of(), CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), List.of(),
        List.of(node, nodeB), List.of(cache, ucache, ncache), List.of(link, link2, ulink, nlink),
        List.of(new RelayNodeListenerAssignment("peer-a", 7L, true, 1L)),
        List.of(new RelayNodeConnectorAssignment("peer-a", "up1", true, 1L)),
        peers, System.currentTimeMillis());
  }

  @Test
  @DisplayName("Desired filtre : seuls listeners/connecteurs/users/catalogue affectes, refs seules")
  void buildsFilteredDesired() {
    var snap = snap();
    long now = System.currentTimeMillis();
    var users = List.of(
        new RelayWorkerContract.UserAclView("alice", true, List.of("default")),
        new RelayWorkerContract.UserAclView("bob", true, List.of("other")));
    var secrets = List.of(
        new RelayWorkerContract.UserSecretView("alice", "cccam", "env:ALICE_CC", 1L, true),
        new RelayWorkerContract.UserSecretView("bob", "cccam", "env:BOB_CC", 1L, true));
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", 0x100, 0, "published", 11L),
        new RelayWorkerContract.CatalogView("other", 0x200, 0, "published", 22L));
    var d = WorkerDesiredBuilder.build(snap, "peer-a", 30000L, now, users, secrets, catalog);
    assertEquals("peer-a", d.nodeId());
    assertEquals(3L, d.desiredVersion());
    assertEquals(1, d.listeners().size());
    assertEquals(7L, d.listeners().get(0).listenerId());
    assertEquals(3, d.caches().size());
    var byName = new java.util.HashMap<String, RelayWorkerContract.CacheView>();
    for (var c : d.caches()) byName.put(c.name(), c);
    // Cache node = DISTANT (peer-b), jamais masque en local.
    assertEquals("node", byName.get("n1").kind());
    assertEquals("peer-b", byName.get("n1").nodeName());
    // Pairs UDP attaches, endpoint node vide (accept-only ici).
    assertEquals(1, byName.get("u1").peers().size());
    assertEquals(14501, byName.get("u1").peers().get(0).port());
    assertTrue(byName.get("n1").peers().isEmpty());
    // Visibilite node distant pour la policy.
    assertEquals(1, d.nodes().size());
    assertEquals("peer-b", d.nodes().get(0).nodeName());
    assertEquals(3, d.links().size());
    assertEquals(1, d.connectors().size());
    assertEquals("up1", d.connectors().get(0).name());
    assertEquals("env:UP1_PASS", d.connectors().get(0).passwordRef());
    assertEquals("plaintext", d.connectors().get(0).newcamdPasswordEncoding());
    assertEquals(1, d.users().size());
    assertEquals("alice", d.users().get(0).userName());
    assertEquals(1, d.userSecrets().size());
    assertEquals(1, d.catalog().size());
    assertEquals(11L, d.catalog().get(0).cardId());
    String json = d.toString();
    assertFalse(json.contains("secret-value"), "Aucun secret en valeur");
  }

  @Test
  @DisplayName("Isolation : worker sans affectation ne recoit rien")
  void unassignedWorkerGetsNothing() {
    var snap = snap();
    long now = System.currentTimeMillis();
    var users = List.of(new RelayWorkerContract.UserAclView("alice", true, List.of("default")));
    var d = WorkerDesiredBuilder.build(snap, "peer-b", 30000L, now, users,
        List.of(), List.of());
    assertTrue(d.listeners().isEmpty());
    assertTrue(d.connectors().isEmpty());
    assertTrue(d.links().isEmpty());
    assertTrue(d.caches().isEmpty());
    assertTrue(d.users().isEmpty());
  }

  @Test
  @DisplayName("B1 : config CW-cycle transportée telle quelle ; défaut off si absente")
  void transportsCwCycleView() {
    var snap = snap().withCwCycle(new com.bowman.cspng.config.CwCycleConfig(
        true, java.util.List.of(0x100, 0x500), 600, 20, "log", false, true, 7L, null, null));
    long now = System.currentTimeMillis();
    var d = WorkerDesiredBuilder.build(snap, "peer-a", 30000L, now,
        List.of(), List.of(), List.of());
    var cw = d.cwCycle();
    assertNotNull(cw, "vue transportée (jamais null)");
    assertTrue(cw.enabled());
    assertEquals(java.util.List.of(0x100, 0x500), cw.caids());
    assertEquals(600, cw.maxList());
    assertEquals(20, cw.keepTimeSec());
    assertEquals("log", cw.onBad());
    assertFalse(cw.dropOld());
    assertTrue(cw.usePeerCycleMetadata());
    // Round-trip sémantique vers la config worker
    var cfg = cw.toConfig();
    assertTrue(cfg.enabled());
    assertEquals(600, cfg.maxList());

    // Snapshot sans cw-cycle (compat pre-V15) => DEFAULT off, jamais null
    var d2 = WorkerDesiredBuilder.build(snap(), "peer-a", 30000L, now,
        List.of(), List.of(), List.of());
    assertNotNull(d2.cwCycle());
    assertFalse(d2.cwCycle().enabled());
  }
}
