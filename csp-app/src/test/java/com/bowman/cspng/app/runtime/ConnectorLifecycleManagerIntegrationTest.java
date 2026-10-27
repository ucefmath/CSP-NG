package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.*;
import com.bowman.cspng.connectors.AbstractConnector;
import com.bowman.cspng.users.InMemoryUserDirectory;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorLifecycleManagerIntegrationTest {

  Vertx vertx;

  @AfterEach
  void close() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); } catch (Exception e) { return 34000 + (int)(Math.random()*2000); }
  }

  private ConnectorConfig newcamd(String name, String host, int port, String passwordRef, String desRef, boolean enabled) {
    return new ConnectorConfig(name, "test-profile", "newcamd", enabled, false, host, port, "user1", passwordRef, desRef, 5000, 5000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 10, 1L);
  }
  private ConnectorConfig cccam(String name, String host, int port, String passwordRef, boolean enabled) {
    return new ConnectorConfig(name, "test-profile", "cccam", enabled, false, host, port, "user1", passwordRef, null, 5000, 5000, null, null, null, null, null, null, false, null, "2.3.2", null, null, null, null, null, 50, 10, 1L);
  }
  private ConnectorConfig radegast(String name, String host, int port, boolean enabled) {
    return new ConnectorConfig(name, "test-profile", "radegast", enabled, false, host, port, null, null, null, 5000, 5000, null, null, null, null, null, null, false, null, null, null, null, null, null, null, 50, 10, 1L);
  }

  private ConfigSnapshot snapshotWithConnectors(List<ConnectorConfig> connectors) {
    var profile = new ProfileConfig("test-profile", true, false, false, 0x1234, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    return new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(profile), List.of(), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), connectors, List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()
    );
  }

  @Test
  void reconcileInitialWithNewcamdAndCccam() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> desHex.equals(name) || "PASS".equals(name) ? desHex : desHex, List.of(java.nio.file.Path.of("/tmp")));
    // Use env refs that resolve via same provider: passwordRef=env:PASS, desRef=env:DES
    // Provide mapping: PASS-> "secretPass", DES-> desHex
    var resolver2 = new RuntimeSecretResolver(vertx, name -> {
      if ("PASS".equals(name)) return "secretPass";
      if ("DES".equals(name)) return desHex;
      return desHex;
    }, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort(); int p2 = freePort();
    var c1 = newcamd("conn-newcamd", "127.0.0.1", p1, "env:PASS", "env:DES", true);
    var c2 = cccam("conn-cccam", "127.0.0.1", p2, "env:PASS", true);
    holder.set(snapshotWithConnectors(List.of(c1, c2)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver2);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    assertEquals(2, mgr.activeCount());
    assertEquals(2, mgr.getActiveConnectors().size());
    assertNotNull(mgr.getActiveConnectors().get("conn-newcamd"));
    assertNotNull(mgr.getActiveConnectors().get("conn-cccam"));
    mgr.onStop(null);
    rc.onStop(null);
  }

  @Test
  void addConnectorViaNewSnapshot() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> {
      if ("PASS".equals(name)) return "secretPass";
      if ("DES".equals(name)) return desHex;
      return "x";
    }, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort(); int p2 = freePort();
    var c1 = newcamd("conn1", "127.0.0.1", p1, "env:PASS", "env:DES", true);
    holder.set(snapshotWithConnectors(List.of(c1)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    assertEquals(1, mgr.activeCount());
    // add second
    var c2 = cccam("conn2", "127.0.0.1", p2, "env:PASS", true);
    holder.set(snapshotWithConnectors(List.of(c1, c2)));
    mgr.reconcileBlocking();
    assertEquals(2, mgr.activeCount());
    assertNotNull(mgr.getActiveConnectors().get("conn2"));
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void removeConnectorDisappearsAndStopped() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> {
      if ("PASS".equals(name)) return "secretPass";
      if ("DES".equals(name)) return desHex;
      return "x";
    }, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort(); int p2 = freePort();
    var c1 = newcamd("conn1", "127.0.0.1", p1, "env:PASS", "env:DES", true);
    var c2 = cccam("conn2", "127.0.0.1", p2, "env:PASS", true);
    holder.set(snapshotWithConnectors(List.of(c1, c2)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    AbstractConnector before = mgr.getActiveConnectors().get("conn2");
    assertNotNull(before);
    // remove conn2
    holder.set(snapshotWithConnectors(List.of(c1)));
    mgr.reconcileBlocking();
    assertEquals(1, mgr.activeCount());
    assertNull(mgr.getActiveConnectors().get("conn2"));
    assertFalse(before.isConnected(), "removed connector should be stopped");
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void changeHostRestartsConnector() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> {
      if ("PASS".equals(name)) return "secretPass";
      if ("DES".equals(name)) return desHex;
      return "x";
    }, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort();
    var c1 = newcamd("conn1", "127.0.0.1", p1, "env:PASS", "env:DES", true);
    holder.set(snapshotWithConnectors(List.of(c1)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    AbstractConnector first = mgr.getActiveConnectors().get("conn1");
    assertEquals("127.0.0.1", first.getHost());
    // change host
    var c1b = newcamd("conn1", "127.0.0.2", p1, "env:PASS", "env:DES", true);
    holder.set(snapshotWithConnectors(List.of(c1b)));
    mgr.reconcileBlocking();
    AbstractConnector second = mgr.getActiveConnectors().get("conn1");
    assertNotSame(first, second, "changed config should create new instance");
    assertEquals("127.0.0.2", second.getHost());
    assertFalse(first.isConnected(), "old instance should be stopped");
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void invalidSecretDoesNotBlockOtherConnectors() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> {
      if ("MISSING".equals(name)) return null; // will cause SecretResolutionException (env not found)
      if ("PASS".equals(name)) return "secretPass";
      if ("DES".equals(name)) return desHex;
      return null;
    }, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort(); int p2 = freePort();
    var bad = newcamd("bad-conn", "127.0.0.1", p1, "env:MISSING", "env:DES", true);
    var good = cccam("good-conn", "127.0.0.1", p2, "env:PASS", true);
    holder.set(snapshotWithConnectors(List.of(bad, good)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    // good should be present despite bad failing
    assertNotNull(mgr.getActiveConnectors().get("good-conn"), "good connector should start even if bad fails");
    assertNull(mgr.getActiveConnectors().get("bad-conn"), "bad connector should not be active");
    assertEquals(1, mgr.activeCount());
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void disabledConnectorNeverStarted() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    String desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> desHex, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    int p1 = freePort();
    var disabled = newcamd("disabled-conn", "127.0.0.1", p1, "env:PASS", "env:DES", false);
    holder.set(snapshotWithConnectors(List.of(disabled)));
    var mgr = new ConnectorLifecycleManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true;
    mgr.reconcileBlocking();
    assertEquals(0, mgr.activeCount());
    assertNull(mgr.getActiveConnectors().get("disabled-conn"));
    mgr.onStop(null); rc.onStop(null);
  }
}
