package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.*;
import com.bowman.cspng.connectors.AbstractConnector;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests lifecycle proxy SOCKS5 du {@link ConnectorLifecycleManager} (Plan P2 §5) :
 * <ul>
 *   <li>Changement proxy (version/config/enabled) → restart des SEULS connecteurs attachés.</li>
 *   <li>Kill switch : proxy disabled → connecteurs attachés fermés, PAS de restart direct.</li>
 *   <li>Re-enabled → relance.</li>
 *   <li>Proxy inconnu / secret KO → fail-closed : connecteur jamais démarré en direct.</li>
 *   <li>Connecteur direct (sans proxy) JAMAIS impacté par les changements proxy.</li>
 * </ul>
 *
 * <p>Utilise des connecteurs Radegast vers 127.0.0.1:port-libre (start() fire-and-forget :
 * l'enregistrement manager réussit même sans backend TCP ; c'est l'enregistrement et le
 * diff lifecycle qui sont testés).
 */
class ConnectorLifecycleManagerProxyTest {

  Vertx vertx;

  @AfterEach
  void close() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  // -- helpers --

  private int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); }
    catch (Exception e) { return 34000 + (int) (Math.random() * 2000); }
  }

  private ConnectorConfig radegast(String name, boolean enabled, String proxyName) {
    return new ConnectorConfig(name, "test-profile", "radegast", enabled, false,
        "127.0.0.1", freePort(), null, null, null,
        5000, 60000, null, null, null, null, null, null,
        false, null, null, null, null, null, null, null, 50, 10, 1L, proxyName);
  }

  private NetworkProxyConfig proxy(String name, boolean enabled, long version, String username, String passwordRef) {
    return new NetworkProxyConfig(name, enabled, "socks5", "127.0.0.1", freePort(),
        username, passwordRef, null, version);
  }

  private ConfigSnapshot snapshot(List<ConnectorConfig> connectors, List<NetworkProxyConfig> proxies) {
    var base = ConfigSnapshot.empty();
    return new ConfigSnapshot(
        base.root(), base.logging(), base.caProfilesGlobal(),
        base.profiles(), base.profileListeners(), base.profileCards(),
        base.connectionManager(), base.serviceMap(), connectors, base.connectorServices(),
        base.cache(), base.clusterUdp(), base.webui(),
        base.plugins(), base.sidCacheLinks(), base.extendedNewcamd(),
        base.connectorCaidProfiles(), base.profileProviderIdents(),
        proxies, System.currentTimeMillis()
    );
  }

  private RuntimeCore runtimeCore() {
    return new RuntimeCore(vertx, null);
  }

  private RuntimeSecretResolver resolver(String... envValues) {
    // envProvider : mapping name→value ; null = variable absente (secret KO)
    java.util.Map<String, String> env = new java.util.HashMap<>();
    for (int i = 0; i + 1 < envValues.length; i += 2) env.put(envValues[i], envValues[i + 1]);
    return new RuntimeSecretResolver(vertx, env::get, List.of(java.nio.file.Path.of("/tmp")));
  }

  // ============================================================
  // Changement proxy → restart des SEULS connecteurs attachés
  // ============================================================

  @Test
  void proxyChangeRestartsOnlyAttachedConnectors() throws Exception {
    vertx = Vertx.vertx();
    ConfigHolder holder = new ConfigHolder();
    RuntimeCore rc = runtimeCore();
    ConnectorLifecycleManager mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver());

    // Mêmes instances de ConnectorConfig réutilisées dans tous les snapshots :
    // seul le PROXY change — le diff doit détecter uniquement ça.
    ConnectorConfig connA = radegast("A", true, "P");
    ConnectorConfig connB = radegast("B", true, null);

    // v1 : A via proxy P, B direct
    holder.set(snapshot(
        List.of(connA, connB),
        List.of(proxy("P", true, 1L, null, null))));
    mgr.reconcileBlocking();

    var cm = rc.cwsConnectorManager();
    AbstractConnector a1 = cm.getConnector("A");
    AbstractConnector b1 = cm.getConnector("B");
    assertNotNull(a1, "A should be started via proxy P");
    assertNotNull(b1, "B should be started direct");
    assertEquals(2, mgr.activeCount());

    // v2 : proxy P bump (host change) — connectors inchangés
    NetworkProxyConfig pV1 = proxy("P", true, 1L, null, null);
    NetworkProxyConfig pV2 = new NetworkProxyConfig("P", true, "socks5", "127.0.0.2", pV1.port(),
        null, null, null, 2L);
    holder.set(snapshot(
        List.of(connA, connB),
        List.of(pV2)));
    mgr.reconcileBlocking();

    AbstractConnector a2 = cm.getConnector("A");
    AbstractConnector b2 = cm.getConnector("B");
    assertNotSame(a1, a2, "A must be RESTARTED when its proxy changes");
    assertNotNull(a2);
    assertSame(b1, b2, "B (direct) must NOT be restarted by an unrelated proxy change");
    assertEquals(2, mgr.activeCount());

    // v3 : proxy disabled → kill switch
    NetworkProxyConfig pV3 = new NetworkProxyConfig("P", false, "socks5", "127.0.0.2", pV1.port(),
        null, null, null, 3L);
    holder.set(snapshot(
        List.of(connA, connB),
        List.of(pV3)));
    mgr.reconcileBlocking();
    // Kill switch : proxy disabled → A fermé, PAS de démarrage direct
    assertNull(cm.getConnector("A"), "A must be killed when proxy disabled (no direct fallback)");
    assertSame(b1, cm.getConnector("B"), "B still untouched");

    // v4 : re-enabled → A relancé
    NetworkProxyConfig pV4 = new NetworkProxyConfig("P", true, "socks5", "127.0.0.2", pV1.port(),
        null, null, null, 4L);
    holder.set(snapshot(
        List.of(connA, connB),
        List.of(pV4)));
    mgr.reconcileBlocking();
    AbstractConnector a4 = cm.getConnector("A");
    assertNotNull(a4, "A must be relaunched when proxy re-enabled");
    assertNotSame(a2, a4);
    assertSame(b1, cm.getConnector("B"), "B still untouched through all reconciles");
    assertEquals(2, mgr.activeCount());
  }

  // ============================================================
  // Fail-closed : proxy inconnu → jamais de direct
  // ============================================================

  @Test
  void unknownProxyFailsClosed() throws Exception {
    vertx = Vertx.vertx();
    ConfigHolder holder = new ConfigHolder();
    RuntimeCore rc = runtimeCore();
    ConnectorLifecycleManager mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver());

    // A référence "ghost" (absent du snapshot) ; B direct
    holder.set(snapshot(
        List.of(radegast("A", true, "ghost"), radegast("B", true, null)),
        List.of()));
    mgr.reconcileBlocking();

    var cm = rc.cwsConnectorManager();
    assertNull(cm.getConnector("A"), "A must NOT be started (unknown proxy → fail-closed)");
    assertNotNull(cm.getConnector("B"), "B (direct) unaffected");
    assertEquals(1, mgr.activeCount());
  }

  // ============================================================
  // Fail-closed : secret proxy non résoluble → jamais de direct
  // ============================================================

  @Test
  void proxySecretKoFailsClosed() throws Exception {
    vertx = Vertx.vertx();
    ConfigHolder holder = new ConfigHolder();
    RuntimeCore rc = runtimeCore();
    // Aucune variable d'env fournie → env:MISSING_PROXY_PW ne résout pas
    ConnectorLifecycleManager mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver());

    // P a une auth passwordRef env:MISSING_PROXY_PW (non résoluble) ; A attaché à P, B direct
    holder.set(snapshot(
        List.of(radegast("A", true, "P"), radegast("B", true, null)),
        List.of(proxy("P", true, 1L, "user", "env:MISSING_PROXY_PW"))));
    mgr.reconcileBlocking();

    var cm = rc.cwsConnectorManager();
    assertNull(cm.getConnector("A"), "A must NOT start when proxy secret is unresolvable (fail-closed)");
    assertNotNull(cm.getConnector("B"), "B (direct) unaffected");
    assertEquals(1, mgr.activeCount());
  }

  // ============================================================
  // Auth proxy résolue → connecteur démarré via proxy
  // ============================================================

  @Test
  void proxyAuthResolvedStartsConnector() throws Exception {
    vertx = Vertx.vertx();
    ConfigHolder holder = new ConfigHolder();
    RuntimeCore rc = runtimeCore();
    ConnectorLifecycleManager mgr = new ConnectorLifecycleManager(
        vertx, holder, rc, resolver("PROXY_PW", "resolved-secret"));

    holder.set(snapshot(
        List.of(radegast("A", true, "P")),
        List.of(proxy("P", true, 1L, "user", "env:PROXY_PW"))));
    mgr.reconcileBlocking();

    var cm = rc.cwsConnectorManager();
    AbstractConnector a = cm.getConnector("A");
    assertNotNull(a, "A must start when proxy auth resolves");
    assertEquals(1, mgr.activeCount());
  }

  // ============================================================
  // Connecteur sans changement (config + proxy identiques) → pas de restart
  // ============================================================

  @Test
  void unchangedConnectorAndProxyNoRestart() throws Exception {
    vertx = Vertx.vertx();
    ConfigHolder holder = new ConfigHolder();
    RuntimeCore rc = runtimeCore();
    ConnectorLifecycleManager mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver());

    List<ConnectorConfig> conns = List.of(radegast("A", true, "P"), radegast("B", true, null));
    List<NetworkProxyConfig> proxies = List.of(proxy("P", true, 1L, null, null));
    holder.set(snapshot(conns, proxies));
    mgr.reconcileBlocking();
    var cm = rc.cwsConnectorManager();
    AbstractConnector a1 = cm.getConnector("A");
    AbstractConnector b1 = cm.getConnector("B");

    // Reconcile identique → mêmes instances
    holder.set(snapshot(conns, proxies));
    mgr.reconcileBlocking();
    assertSame(a1, cm.getConnector("A"), "A must not restart on identical snapshot");
    assertSame(b1, cm.getConnector("B"), "B must not restart on identical snapshot");
    assertEquals(2, mgr.activeCount());
  }

  // ============================================================
  // proxySignature — diff de signature (test direct de la fonction de diff)
  // ============================================================

  @Test
  void clampConnectTimeoutBoundsOnProxyPath() {
    // Plan P2 : sur le chemin proxy, T = cfg.connectTimeoutMs clampé 1000..300000
    assertEquals(1000, ConnectorLifecycleManager.clampConnectTimeout(0));
    assertEquals(1000, ConnectorLifecycleManager.clampConnectTimeout(500));
    assertEquals(1000, ConnectorLifecycleManager.clampConnectTimeout(1000));
    assertEquals(5000, ConnectorLifecycleManager.clampConnectTimeout(5000));
    assertEquals(300_000, ConnectorLifecycleManager.clampConnectTimeout(300_000));
    assertEquals(300_000, ConnectorLifecycleManager.clampConnectTimeout(999_999));
  }

  @Test
  void proxySignatureChangesOnConfigOrVersionOrEnabled() {
    NetworkProxyConfig p1 = new NetworkProxyConfig("P", true, "socks5", "h", 1080, null, null, null, 1L);
    NetworkProxyConfig p1Same = new NetworkProxyConfig("P", true, "socks5", "h", 1080, null, null, null, 1L);
    NetworkProxyConfig pV2 = new NetworkProxyConfig("P", true, "socks5", "h", 1080, null, null, null, 2L);
    NetworkProxyConfig pDisabled = new NetworkProxyConfig("P", false, "socks5", "h", 1080, null, null, null, 2L);
    NetworkProxyConfig pHost = new NetworkProxyConfig("P", true, "socks5", "h2", 1080, null, null, null, 2L);
    NetworkProxyConfig pAuth = new NetworkProxyConfig("P", true, "socks5", "h", 1080, "u", "env:X", null, 2L);

    var map1 = java.util.Map.of("P", p1);
    var mapSame = java.util.Map.of("P", p1Same);
    var mapV2 = java.util.Map.of("P", pV2);
    var mapDis = java.util.Map.of("P", pDisabled);
    var mapHost = java.util.Map.of("P", pHost);
    var mapAuth = java.util.Map.of("P", pAuth);

    assertEquals(ConnectorLifecycleManager.proxySignature("P", map1),
        ConnectorLifecycleManager.proxySignature("P", mapSame), "identical proxy → same signature");
    assertNotEquals(ConnectorLifecycleManager.proxySignature("P", map1),
        ConnectorLifecycleManager.proxySignature("P", mapV2), "version bump → different signature");
    assertNotEquals(ConnectorLifecycleManager.proxySignature("P", mapV2),
        ConnectorLifecycleManager.proxySignature("P", mapDis), "enabled toggle → different signature");
    assertNotEquals(ConnectorLifecycleManager.proxySignature("P", mapV2),
        ConnectorLifecycleManager.proxySignature("P", mapHost), "host change → different signature");
    assertNotEquals(ConnectorLifecycleManager.proxySignature("P", mapV2),
        ConnectorLifecycleManager.proxySignature("P", mapAuth), "auth change → different signature");
    assertEquals("direct", ConnectorLifecycleManager.proxySignature(null, map1));
    assertEquals("direct", ConnectorLifecycleManager.proxySignature("  ", map1));
    assertTrue(ConnectorLifecycleManager.proxySignature("ghost", map1).startsWith("missing:"));
  }
}
