package com.bowman.cspng.infinispan;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfinispanReplicationTest {

  private Vertx vertx;
  private CacheVerticle verticleA;
  private CacheVerticle verticleB;
  private EmbeddedCacheManager managerA;
  private EmbeddedCacheManager managerB;
  private Cache<Integer, byte[]> cacheA;
  private Cache<Integer, byte[]> cacheB;
  private InfinispanCacheTransport transportA;
  private InfinispanCacheTransport transportB;
  private File keystoreFile;
  private static final String KS_PASSWORD = "changeit";
  private static final String KS_ALIAS = "testkey";

  private static final EcmKey KEY = new EcmKey(0x80, 0x1111, 0x0001, 0x0B00, 0xABCD1234);
  private static final byte[] CW = new byte[]{0x01,0x02,0x03,0x04,0x05,0x06,0x07,0x08,0x09,0x0A,0x0B,0x0C,0x0D,0x0E,0x0F,0x10};

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    verticleA = new CacheVerticle();
    verticleB = new CacheVerticle();
    CountDownLatch latchA = new CountDownLatch(1);
    CountDownLatch latchB = new CountDownLatch(1);
    vertx.deployVerticle(verticleA).onComplete(ar -> latchA.countDown());
    vertx.deployVerticle(verticleB).onComplete(ar -> latchB.countDown());
    assertTrue(latchA.await(5, TimeUnit.SECONDS));
    assertTrue(latchB.await(5, TimeUnit.SECONDS));
    awaitContext(verticleA);
    awaitContext(verticleB);

    keystoreFile = createTempKeystore();

    String cluster = "test-repl-" + UUID.randomUUID();
    String cacheName = "csp-cw-repl";

    // Find free ports dynamically to avoid collisions
    int portA = findFreePort();
    int portB = findFreePort();
    System.setProperty("jgroups.tcpping.initial_hosts", "127.0.0.1[" + portA + "],127.0.0.1[" + portB + "]");

    managerA = createClusteredManager(cluster, "nodeA", "127.0.0.1", portA, cacheName, keystoreFile);
    cacheA = managerA.getCache(cacheName);
    managerB = createClusteredManager(cluster, "nodeB", "127.0.0.1", portB, cacheName, keystoreFile);
    cacheB = managerB.getCache(cacheName);

    waitForClusterMembers(managerA, managerB, 10_000);
    // introspection runtime : TCP + SYM_ENCRYPT, pas UDP
    assertTrue(hasProtocol(managerA, cacheName, "TCP"), "managerA must use TCP");
    assertTrue(hasProtocol(managerB, cacheName, "TCP"), "managerB must use TCP");
    assertFalse(hasProtocol(managerA, cacheName, "UDP"), "managerA must not use UDP");
    assertFalse(hasProtocol(managerB, cacheName, "UDP"), "managerB must not use UDP");
    assertTrue(hasProtocol(managerA, cacheName, "SYM_ENCRYPT"), "managerA secure stack");
    assertTrue(hasProtocol(managerB, cacheName, "SYM_ENCRYPT"), "managerB secure stack");

    InfinispanCacheListener listenerA = new InfinispanCacheListener(verticleA);
    InfinispanCacheListener listenerB = new InfinispanCacheListener(verticleB);
    cacheA.addListener(listenerA);
    cacheB.addListener(listenerB);

    transportA = new InfinispanCacheTransport(cacheA);
    transportB = new InfinispanCacheTransport(cacheB);

    CountDownLatch tLatch = new CountDownLatch(2);
    verticleA.runOnContext(() -> { verticleA.addTransport(transportA); tLatch.countDown(); });
    verticleB.runOnContext(() -> { verticleB.addTransport(transportB); tLatch.countDown(); });
    assertTrue(tLatch.await(2, TimeUnit.SECONDS));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (managerA != null) { try { managerA.stop(); } catch (Exception ignored) {} }
    if (managerB != null) { try { managerB.stop(); } catch (Exception ignored) {} }
    if (keystoreFile != null) keystoreFile.delete();
    // ensure no system property leak
    System.clearProperty("jgroups.keystore_name");
    System.clearProperty("jgroups.store_password");
    System.clearProperty("jgroups.keystore_password");
    System.clearProperty("jgroups.keystore_alias");
    System.clearProperty("jgroups.bind_addr");
    System.clearProperty("jgroups.bind_port");
    System.clearProperty("jgroups.tcpping.initial_hosts");
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(ar -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void replicationAtoBAndNoLoop() throws Exception {
    EcmRequest reqB = new EcmRequest(KEY, null, "infinispan", "remoteB", "originB", System.currentTimeMillis(), null, 500);
    verticleB.processRequest(5, reqB, true, 5000);
    awaitContext(verticleB);

    CwReply replyA = new CwReply(KEY, 0x80, CW, "connectorA", "10.0.0.1", System.currentTimeMillis());
    EcmRequest reqA = new EcmRequest(KEY, null, "infinispan", "remoteA", "originA", System.currentTimeMillis(), null, 500);
    CountDownLatch handleLatch = new CountDownLatch(1);
    verticleA.runOnContext(() -> { verticleA.handleReply(reqA, replyA); verticleA.runOnContext(handleLatch::countDown); });
    assertTrue(handleLatch.await(2, TimeUnit.SECONDS));

    assertTrue(waitForCacheEntry(cacheB, KEY.dataHash(), 5000), "B cache should receive replicated entry");

    byte[] raw = cacheB.get(KEY.dataHash());
    assertNotNull(raw);
    CwReply decoded = CwCacheCodec.decode(raw);
    assertArrayEquals(CW, decoded.cw());

    awaitContext(verticleB);
    Thread.sleep(400);
    awaitContext(verticleB);

    Thread.sleep(300);
    assertEquals(1, cacheA.size(), "cacheA should remain 1 after B handling (no loop)");
    assertEquals(1, cacheB.size(), "cacheB should be 1 (replicated only)");

    CwReply loopReply = new CwReply(KEY, 0x80, CW, "connB", "infinispan:10.0.0.1", System.currentTimeMillis());
    transportB.onReply(loopReply);
    Thread.sleep(400);
    assertEquals(1, cacheB.size(), "loop reply should not create new entry");
    assertEquals(1, cacheA.size());
  }

  @Test
  void listenerRedispatchViaRunOnContext() throws Exception {
    AtomicInteger handleCount = new AtomicInteger();
    CacheVerticle countingVerticle = new CacheVerticle() {
      @Override public void handleReply(EcmRequest request, CwReply reply) {
        // verify synthetic profile is "infinispan" not "default"
        assertEquals("infinispan", request.profileName());
        // ensure origin is prefixed
        assertTrue(reply.originAddress().startsWith("infinispan:"));
        handleCount.incrementAndGet();
        super.handleReply(request, reply);
      }
    };
    CountDownLatch dep = new CountDownLatch(1);
    vertx.deployVerticle(countingVerticle).onComplete(ar -> dep.countDown());
    assertTrue(dep.await(3, TimeUnit.SECONDS));
    awaitContext(countingVerticle);

    String cluster = "test-listener-" + UUID.randomUUID();
    String cacheName = "csp-cw-listener";
    int portC = findFreePort();
    int portD = findFreePort();
    System.setProperty("jgroups.tcpping.initial_hosts", "127.0.0.1[" + portC + "],127.0.0.1[" + portD + "]");
    EmbeddedCacheManager mgrC = createClusteredManager(cluster, "nodeC", "127.0.0.1", portC, cacheName, keystoreFile);
    EmbeddedCacheManager mgrD = createClusteredManager(cluster, "nodeD", "127.0.0.1", portD, cacheName, keystoreFile);
    Cache<Integer, byte[]> cacheC = mgrC.getCache(cacheName);
    Cache<Integer, byte[]> cacheD = mgrD.getCache(cacheName);
    waitForClusterMembers(mgrC, mgrD, 10_000);
    assertTrue(hasProtocol(mgrC, cacheName, "TCP"));
    assertFalse(hasProtocol(mgrC, cacheName, "UDP"));
    assertTrue(hasProtocol(mgrC, cacheName, "SYM_ENCRYPT"));
    assertTrue(hasProtocol(mgrD, cacheName, "TCP"));
    assertTrue(hasProtocol(mgrD, cacheName, "SYM_ENCRYPT"));

    InfinispanCacheListener listenerD = new InfinispanCacheListener(countingVerticle);
    cacheD.addListener(listenerD);
    try {
      EcmRequest req = new EcmRequest(KEY, null, "infinispan", "remote", "origin", System.currentTimeMillis(), null, 500);
      countingVerticle.processRequest(5, req, true, 5000);
      awaitContext(countingVerticle);

      CwReply reply = new CwReply(KEY, 0x80, CW, "connC", "1.2.3.4", System.currentTimeMillis());
      byte[] raw = CwCacheCodec.encode(reply);
      cacheC.putAsync(KEY.dataHash(), raw).get(3, TimeUnit.SECONDS);

      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline && handleCount.get() == 0) {
        Thread.sleep(100);
      }
      assertTrue(handleCount.get() > 0, "listener should have re-dispatched via runOnContext");
      awaitContext(countingVerticle);
    } finally {
      try { cacheD.removeListener(listenerD); } catch (Exception ignored) {}
      mgrC.stop(); mgrD.stop();
    }
  }

  // helpers

  private EmbeddedCacheManager createClusteredManager(String clusterName, String nodeName, String bindAddr, int bindPort, String cacheName, File ksFile) {
    java.util.Map<String,String> old = new java.util.HashMap<>();
    old.put("jgroups.bind_addr", System.getProperty("jgroups.bind_addr"));
    old.put("jgroups.bind_port", System.getProperty("jgroups.bind_port"));
    old.put("jgroups.tcpping.initial_hosts", System.getProperty("jgroups.tcpping.initial_hosts"));
    old.put("jgroups.keystore_name", System.getProperty("jgroups.keystore_name"));
    old.put("jgroups.store_password", System.getProperty("jgroups.store_password"));
    old.put("jgroups.keystore_password", System.getProperty("jgroups.keystore_password"));
    old.put("jgroups.keystore_alias", System.getProperty("jgroups.keystore_alias"));
    try {
      System.setProperty("jgroups.bind_addr", bindAddr);
      System.setProperty("jgroups.bind_port", String.valueOf(bindPort));
      System.setProperty("jgroups.keystore_name", ksFile.getAbsolutePath());
      System.setProperty("jgroups.store_password", KS_PASSWORD);
      System.setProperty("jgroups.keystore_password", KS_PASSWORD);
      System.setProperty("jgroups.keystore_alias", KS_ALIAS);
      GlobalConfigurationBuilder gcb = new GlobalConfigurationBuilder();
      gcb.transport().defaultTransport().clusterName(clusterName);
      gcb.transport().nodeName(nodeName);
      gcb.transport().addProperty("configurationFile", "jgroups-tcp-test.xml");
      gcb.globalState().disable();
      ConfigurationBuilder cb = new ConfigurationBuilder();
      cb.clustering().cacheMode(CacheMode.REPL_ASYNC);
      cb.memory().maxCount(100000);
      cb.expiration().lifespan(30000);
      DefaultCacheManager mgr = new DefaultCacheManager(gcb.build(), false);
      mgr.defineConfiguration(cacheName, cb.build());
      mgr.start();
      mgr.getCache(cacheName);
      // verify file was used
      String cfg = mgr.getCacheManagerConfiguration().transport().properties().getProperty("configurationFile");
      assertEquals("jgroups-tcp-test.xml", cfg, "configurationFile must be loaded");
      return mgr;
    } finally {
      for (var e : old.entrySet()) {
        if (e.getValue() == null) System.clearProperty(e.getKey());
        else System.setProperty(e.getKey(), e.getValue());
      }
    }
  }

  private void awaitContext(CacheVerticle cv) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    cv.runOnContext(latch::countDown);
    assertTrue(latch.await(2, TimeUnit.SECONDS));
  }

  private boolean waitForCacheEntry(Cache<Integer, byte[]> cache, int hash, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (cache.containsKey(hash)) return true;
      Thread.sleep(50);
    }
    return false;
  }

  private void waitForClusterMembers(EmbeddedCacheManager a, EmbeddedCacheManager b, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (a.getMembers().size() == 2 && b.getMembers().size() == 2) return;
      Thread.sleep(100);
    }
    fail("cluster did not form 2 members within " + timeoutMs + "ms : A=" + a.getMembers() + " B=" + b.getMembers());
  }

  private static File createTempKeystore() throws Exception {
    File f = File.createTempFile("test-keystore", ".jceks");
    char[] pwd = KS_PASSWORD.toCharArray();
    KeyStore ks = KeyStore.getInstance("JCEKS");
    ks.load(null, pwd);
    KeyGenerator kg = KeyGenerator.getInstance("AES");
    kg.init(128);
    SecretKey key = kg.generateKey();
    ks.setEntry(KS_ALIAS, new KeyStore.SecretKeyEntry(key), new KeyStore.PasswordProtection(pwd));
    try (FileOutputStream out = new FileOutputStream(f)) {
      ks.store(out, pwd);
    }
    f.deleteOnExit();
    return f;
  }

  private static int findFreePort() throws Exception {
    try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private static boolean hasProtocol(EmbeddedCacheManager mgr, String cacheName, String protocol) {
    try {
      org.infinispan.Cache<?,?> cache = mgr.getCache(cacheName);
      org.infinispan.remoting.rpc.RpcManager rpc = cache.getAdvancedCache().getRpcManager();
      if (rpc == null) return false;
      org.infinispan.remoting.transport.Transport t = rpc.getTransport();
      if (!(t instanceof org.infinispan.remoting.transport.jgroups.JGroupsTransport)) return false;
      org.infinispan.remoting.transport.jgroups.JGroupsTransport jg = (org.infinispan.remoting.transport.jgroups.JGroupsTransport) t;
      org.jgroups.JChannel ch = jg.getChannel();
      if (ch == null) return false;
      org.jgroups.stack.ProtocolStack stack = ch.getProtocolStack();
      return stack.findProtocol(protocol) != null;
    } catch (Exception e) {
      return false;
    }
  }
}
