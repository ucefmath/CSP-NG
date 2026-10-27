package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.CacheConfig;
import com.bowman.cspng.config.CaProfilesGlobalConfig;
import com.bowman.cspng.config.ClusterUdpConfig;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConnectionManagerConfig;
import com.bowman.cspng.config.ExtendedNewcamdConfig;
import com.bowman.cspng.config.ListenerCacheLinkConfig;
import com.bowman.cspng.config.LoggingConfig;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.ProfileListenerConfig;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayNodeConfig;
import com.bowman.cspng.config.RootConfig;
import com.bowman.cspng.config.WebUiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RelayPolicyService — event-bus, expiry bornee, ack DB, localNodeId (FIX Sonnet)")
class RelayPolicyServiceTest {

  private static ConfigSnapshot snapWith(RelayNodeConfig n, RelayCacheConfig c, ListenerCacheLinkConfig l) {
    ProfileConfig p = new ProfileConfig("default", true, false, false, 0, 0, null, null,
        5000L, null, false, 0, null, null, null, null, 1L);
    ProfileListenerConfig li = new ProfileListenerConfig(7L, "default", "cccam", 12000, "0.0.0.0",
        true, null, null, null, 1L, "manual", true, 0, 512, 32, 5, null, 60);
    return new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(p), List.of(li), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(),
        List.of(), CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(),
        List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), List.of(),
        n == null ? List.of() : List.of(n),
        c == null ? List.of() : List.of(c),
        l == null ? List.of() : List.of(l),
        List.of(), List.of(), List.of(),
        System.currentTimeMillis());
  }

  @Test
  @DisplayName("Sans snapshot => politique vide fail-closed, status vide")
  void noSnapshotFailsClosed() {
    ConfigHolder h = new ConfigHolder();
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "local1");
    assertTrue(svc.policy().consultableCaches(7L).isEmpty());
    assertTrue(svc.nodeStatuses(System.currentTimeMillis()).isEmpty());
    assertTrue(svc.recordAck("peer-a", "peer-a", 1L, System.currentTimeMillis()).isEmpty());
  }

  @Test
  @DisplayName("TTL hors bornes normalisee au defaut (jamais de lease 2s en dur)")
  void ttlOutOfBoundsDefaults() {
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(null, null, null));
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "local1");
    assertEquals(30000L, svc.effectiveTtlMs());
  }

  @Test
  @DisplayName("Ack : SAN mismatch rejete, stale rejete, ok valide (DB source de verite)")
  void ackRules() {
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 2L, null, 1L);
    var c = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var l = new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L);
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(n, c, l));
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "local1");
    long now = System.currentTimeMillis();
    assertTrue(svc.recordAck("peer-b", "peer-a", 2L, now).isEmpty());
    assertTrue(svc.recordAck("peer-a", "peer-a", 1L, now).isEmpty());
    assertEquals(2L, svc.recordAck("peer-a", "peer-a", 2L, now).orElseThrow());
    // Source de verite DB : status reste stale tant que le reload DB n'a pas
    // rapporte applied_version=2 (pas de faux ack memoire).
    var stBefore = svc.nodeStatuses(now).stream()
        .filter(s -> s.nodeName().equals("peer-a")).findFirst().orElseThrow();
    assertTrue(stBefore.stale());
    // Simule le reload post-ack DB.
    var nAcked = new RelayNodeConfig("peer-a", null, true, true, false, 2L, 2L, 1L);
    h.set(snapWith(nAcked, c, l));
    svc.onConfigReloaded();
    var st = svc.nodeStatuses(now).stream()
        .filter(s -> s.nodeName().equals("peer-a")).findFirst().orElseThrow();
    assertFalse(st.stale());
    assertFalse(st.expired());
  }

  @Test
  @DisplayName("Event reload : session deja ouverte voit la nouvelle politique (FIX #3)")
  void openSessionSeesReload() {
    var c = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var l = new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L);
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 1L, null, 1L);
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(n, c, l));
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "local1");
    assertFalse(svc.policy().consultableCaches(7L).isEmpty());
    // Detachement en DB puis evenement reload : politique live change sans refresh manuel.
    var lOff = new ListenerCacheLinkConfig(7L, "c1", true, true, false, false, 1L);
    h.set(snapWith(n, c, lOff));
    svc.onConfigReloaded();
    assertTrue(svc.policy().consultableCaches(7L).isEmpty());
  }

  @Test
  @DisplayName("localNodeId obligatoire si relais actif (FIX #6)")
  void localNodeRequiredWhenActive() {
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 1L, null, 1L);
    var c = new RelayCacheConfig("c1", "local", null, true, 8000L, 10000, 512, 1L);
    var l = new ListenerCacheLinkConfig(7L, "c1", true, true, false, true, 1L);
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(n, c, l));
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "");
    assertTrue(svc.policy().consultableCaches(7L).isEmpty(),
        "Politique videe fail-closed sans local-node-id");
  }

  @Test
  @DisplayName("Utilite = consultHits seuls ; reachable null sans observation reelle")
  void servedAndReachableHonest() {
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 2L, 2L, 1L);
    var c = new RelayCacheConfig("n1", "node", "peer-a", true, 8000L, 10000, 512, 1L);
    var l = new ListenerCacheLinkConfig(7L, "n1", true, true, false, true, 1L);
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(n, c, l));
    RelayPolicyService svc = new RelayPolicyService(h, 30000L, "local1");
    long now = System.currentTimeMillis();
    // Sans last_seen : reachable null malgre applied (un ack ancien ne prouve rien).
    assertNull(svc.nodeStatuses(now).stream()
        .filter(s -> s.nodeName().equals("peer-a")).findFirst().orElseThrow().reachable());
    assertEquals(0L, svc.servedResponses("peer-a"));
    // Observation fraiche => reachable true.
    var nSeen = new RelayNodeConfig("peer-a", null, true, true, false, 2L, 2L, 1L,
        java.time.Instant.ofEpochMilli(now));
    h.set(snapWith(nSeen, c, l));
    svc.onConfigReloaded();
    assertTrue(svc.nodeStatuses(now).stream()
        .filter(s -> s.nodeName().equals("peer-a")).findFirst().orElseThrow().reachable());
    // served = consultHits seuls : 1 hit + 1 put => 1.
    var stores = svc.stores();
    stores.ensure("n1", c);
    var key = new com.bowman.cspng.connectors.routing.StrictCacheKey(
        "default", 0x100, 0, 1, 0x80, new byte[]{1}, 1L, java.util.Set.of("x"));
    var reply = new com.bowman.cspng.domain.CwReply(
        new com.bowman.cspng.domain.EcmKey(0x80, 1, 0, 0x100, 9), 0x80,
        new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}, "x", "127.0.0.1", now);
    stores.put("n1", key, reply, 8000L);
    assertNotNull(stores.peek("n1", key));
    assertEquals(1L, svc.servedResponses("peer-a"),
        "feedPuts exclus de l'utilite");
  }

  @Test
  @DisplayName("Expiration bornee : apres TTL => expired + non usable")
  void expiryFailsClosed() {
    var n = new RelayNodeConfig("peer-a", null, true, true, false, 1L, null, 1L);
    ConfigHolder h = new ConfigHolder();
    h.set(snapWith(n, null, null));
    RelayPolicyService svc = new RelayPolicyService(h, 5000L, "local1");
    long now = System.currentTimeMillis();
    assertFalse(svc.isConfigExpired("peer-a", now));
    assertTrue(svc.isConfigExpired("peer-a", now + 6000L));
    var st = svc.nodeStatuses(now + 6000L).stream().filter(s -> s.nodeName().equals("peer-a")).findFirst().orElseThrow();
    assertTrue(st.expired());
    assertFalse(st.usable());
  }
}
