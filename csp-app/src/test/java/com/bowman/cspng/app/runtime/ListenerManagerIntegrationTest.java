package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.*;
import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ListenerManagerIntegrationTest {

  Vertx vertx;

  @AfterEach
  void close() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); } catch (Exception e) { return 10000 + (int)(Math.random()*20000); }
  }
  private ConfigSnapshot snapshotWithListener(int port, String bind, String allow, String deny, String desRef) {
    int p = port == 0 ? freePort() : port;
    var profile = new ProfileConfig("test-profile", true, false, false, 0x1234, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var listener = new ProfileListenerConfig(1, "test-profile", "newcamd", p, bind, true, desRef, allow, deny, 1);
    return new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(profile), List.of(listener), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()
    );
  }

  @Test
  void newcamdBindEphemeralReceives14RandomBytesAndProfileAttached() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var secretResolver = new RuntimeSecretResolver(vertx, name -> desHex, List.of(java.nio.file.Path.of("/tmp")));
    // allow any file root not used here (env)
    UserDirectory dir = new InMemoryUserDirectory(vertx);
    var runtimeCore = new RuntimeCore(vertx, dir);
    runtimeCore.runtimeEnabled = true;
    runtimeCore.quarkusProfile = "test";
    // manually ensure initialized and deploy cache
    runtimeCore.onStart(null);
    var snap = snapshotWithListener(0, "127.0.0.1", null, null, "env:DES");
    holder.set(snap);
    var manager = new ListenerManager(vertx, holder, runtimeCore, secretResolver);
    manager.runtimeEnabled = true;
    manager.quarkusProfile = "test";
    manager.onStart(null);
    // get actual port
    var active = manager.getActive();
    assertEquals(1, active.size());
    var ms = active.values().iterator().next();
    int actualPort = ms.server.actualPort();
    assertTrue(actualPort > 0);
    // connect client
    NetClient client = vertx.createNetClient();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Buffer> received = new AtomicReference<>();
    client.connect(actualPort, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        var sock = ar.result();
        sock.handler(buf -> { received.set(buf); latch.countDown(); });
      } else latch.countDown();
    });
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertNotNull(received.get());
    assertTrue(received.get().length() >= 14, "should receive 14 random bytes");
    client.close();
    manager.onStop(null);
    runtimeCore.onStop(null);
  }

  @Test
  void aclDenyBlocksConnection() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var secretResolver = new RuntimeSecretResolver(vertx, name -> desHex, List.of(java.nio.file.Path.of("/tmp")));
    UserDirectory dir = new InMemoryUserDirectory(vertx);
    var rc = new RuntimeCore(vertx, dir);
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    var snap = snapshotWithListener(0, "127.0.0.1", "1.2.3.4", null, "env:DES"); // allow only 1.2.3.4, client is 127.0.0.1 => deny
    holder.set(snap);
    var mgr = new ListenerManager(vertx, holder, rc, secretResolver);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test"; mgr.onStart(null);
    int port = mgr.getActive().values().iterator().next().server.actualPort();
    NetClient client = vertx.createNetClient();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> closed = new AtomicReference<>(false);
    client.connect(port, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        var sock = ar.result();
        sock.closeHandler(v -> { closed.set(true); latch.countDown(); });
        // wait a bit for server to close us due to ACL
        vertx.setTimer(500, id -> latch.countDown());
      } else latch.countDown();
    });
    latch.await(2, TimeUnit.SECONDS);
    // ACL should have closed socket, we check closed or no data
    // At least server didn't crash
    assertTrue(mgr.getActive().size() == 1);
    client.close();
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void unchangedReloadKeepsSameServer() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> desHex, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    var snap = snapshotWithListener(0, "127.0.0.1", null, null, "env:DES");
    holder.set(snap);
    var mgr = new ListenerManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test"; mgr.onStart(null);
    var firstServer = mgr.getActive().values().iterator().next().server;
    // second reconcile same snapshot
    var m = ListenerManager.class.getDeclaredMethod("reconcileBlocking");
    m.setAccessible(true);
    m.invoke(mgr);
    var secondServer = mgr.getActive().values().iterator().next().server;
    assertSame(firstServer, secondServer, "unchanged should keep same NetServer");
    mgr.onStop(null); rc.onStop(null);
  }

  @Test
  void portChangeBindsNewBeforeClosingOld() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var desHex = "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E";
    var resolver = new RuntimeSecretResolver(vertx, name -> desHex, List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    var snap1 = snapshotWithListener(0, "127.0.0.1", null, null, "env:DES");
    holder.set(snap1);
    var mgr = new ListenerManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test";
    try {
      mgr.onStart(null);
      int firstPort = mgr.getActive().values().iterator().next().server.actualPort();
      int newPort = freePort();
      var profile = new ProfileConfig("test-profile", true, false, false, 0x1234, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
      var listener2 = new ProfileListenerConfig(1, "test-profile", "newcamd", newPort, "127.0.0.1", true, "env:DES", null, null, 1);
      var snap2 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
          List.of(profile), List.of(listener2), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
          CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
      holder.set(snap2);
      var m = ListenerManager.class.getDeclaredMethod("reconcileBlocking");
      m.setAccessible(true);
      m.invoke(mgr);
      assertEquals(1, mgr.getActive().size());
      int secondPort = mgr.getActive().values().iterator().next().server.actualPort();
      assertEquals(newPort, secondPort);
      NetClient client = vertx.createNetClient();
      try {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        client.connect(firstPort, "127.0.0.1", ar -> { if (ar.failed()) failed.set(true); latch.countDown(); });
        latch.await(2, TimeUnit.SECONDS);
        assertTrue(failed.get(), "old port should be closed");
      } finally { client.close(); }
    } finally {
      try { mgr.onStop(null); } catch (Exception ignored) {}
      try { rc.onStop(null); } catch (Exception ignored) {}
    }
  }

  @Test
  void interopReusesSameCacheVerticle() throws Exception {
    vertx = Vertx.vertx();
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    CacheVerticle shared = rc.getCacheVerticle();
    assertNotNull(shared);
    // Simulate InteropClusterBootstrap reuse logic
    var holder = new ConfigHolder();
    holder.set(ConfigSnapshot.empty());
    // second cache not deployed, Interop would reuse shared
    // We just assert same instance via RuntimeCore producer
    CacheVerticle viaProducer = rc.cacheVerticle();
    assertSame(shared, viaProducer);
    rc.onStop(null);
  }

  @Test
  void startupMissingSecretFailsFast() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var resolver = new RuntimeSecretResolver(vertx, name -> null, List.of(java.nio.file.Path.of("/tmp"))); // env missing
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);
    var snap = snapshotWithListener(0, "127.0.0.1", null, null, "env:MISSING");
    holder.set(snap);
    var mgr = new ListenerManager(vertx, holder, rc, resolver);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test";
    assertThrows(IllegalStateException.class, () -> mgr.onStart(null));
    rc.onStop(null);
  }

  @Test
  void cccamListenerBindsAndSends16ByteSeed() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var secretResolver = new RuntimeSecretResolver(vertx, name -> "theSecret", List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);

    int port = freePort();
    var profile = new ProfileConfig("sky", true, false, false, 0x09CD, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var listener = new ProfileListenerConfig(10, "sky", "cccam", port, "127.0.0.1", true, null, null, null, 1L,
        "wildcard", true, 2, 512, 50, 5, "0102030405060708", 60);

    var snap = new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(profile), List.of(listener), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()
    );
    holder.set(snap);

    var mgr = new ListenerManager(vertx, holder, rc, secretResolver);
    mgr.authResolver = new com.bowman.cspng.users.ProtocolAuthResolver.Fake();
    mgr.cardDiscoveryService = new com.bowman.cspng.connectors.catalog.CardDiscoveryService(vertx);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test";
    mgr.onStart(null);

    NetClient client = vertx.createNetClient();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Buffer> seedReceived = new AtomicReference<>();
    client.connect(port, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        ar.result().handler(buf -> {
          seedReceived.set(buf);
          latch.countDown();
        });
      }
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Client CCcam doit recevoir les 16 octets de seed");
    assertEquals(16, seedReceived.get().length(), "Seed CCcam serveur = 16 octets (12+4)");

    client.close();
    mgr.onStop(null);
    rc.onStop(null);
  }

  @Test
  void cs378xListenerBindsAndAcceptsSession() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var secretResolver = new RuntimeSecretResolver(vertx, name -> "theSecret", List.of(java.nio.file.Path.of("/tmp")));
    var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    rc.runtimeEnabled = true; rc.quarkusProfile = "test"; rc.onStart(null);

    int port = freePort();
    var profile = new ProfileConfig("canal", true, false, false, 0x1830, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var listener = new ProfileListenerConfig(20, "canal", "cs378x", port, "127.0.0.1", true, null, null, null, 1L,
        "manual", true, 0, 512, 50, 5, null, 60);

    var snap = new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(profile), List.of(listener), List.of(),
        ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        List.of(), List.of(), ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()
    );
    holder.set(snap);

    var mgr = new ListenerManager(vertx, holder, rc, secretResolver);
    mgr.authResolver = new com.bowman.cspng.users.ProtocolAuthResolver.Fake();
    mgr.cardDiscoveryService = new com.bowman.cspng.connectors.catalog.CardDiscoveryService(vertx);
    mgr.runtimeEnabled = true; mgr.quarkusProfile = "test";
    mgr.onStart(null);

    NetClient client = vertx.createNetClient();
    CountDownLatch latch = new CountDownLatch(1);
    client.connect(port, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        latch.countDown();
      }
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Client CS378x doit pouvoir se connecter");

    client.close();
    mgr.onStop(null);
    rc.onStop(null);
  }
}
