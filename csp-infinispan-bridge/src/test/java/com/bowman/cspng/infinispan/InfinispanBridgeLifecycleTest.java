package com.bowman.cspng.infinispan;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Instance.Handle;
import jakarta.enterprise.util.TypeLiteral;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.annotation.Annotation;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfinispanBridgeLifecycleTest {

  private Vertx vertx;
  private CacheVerticle cacheVerticle;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    CountDownLatch latch = new CountDownLatch(1);
    vertx.deployVerticle(cacheVerticle).onComplete(ar -> latch.countDown());
    assertTrue(latch.await(5, TimeUnit.SECONDS));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(ar -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void featureFlagOffNoManagerNorTransport() throws Exception {
    InfinispanBridgeLifecycle lifecycle = new InfinispanBridgeLifecycle();
    lifecycle.enabled = false;
    lifecycle.cacheVerticleInstance = mockInstance(cacheVerticle);
    lifecycle.clusterName = "test-cluster-off";
    lifecycle.cacheName = "csp-cw";
    lifecycle.ttlMs = 30000;
    lifecycle.maxEntries = 100000;
    lifecycle.jgroupsConfigFile = "jgroups-udp-csp.xml";
    lifecycle.jgroupsBindAddress = "127.0.0.1";
    lifecycle.jgroupsBindPort = 7800;
    lifecycle.jgroupsMcastAddress = "228.10.10.10";
    lifecycle.jgroupsMcastPort = 45588;
    lifecycle.keystorePath = Optional.empty();
    lifecycle.keystoreAlias = Optional.empty();
    lifecycle.keystorePassword = Optional.empty();

    // ensure no leftover system props after disabled start
    System.clearProperty("jgroups.keystore_name");
    lifecycle.onStart(new StartupEvent());
    assertNull(lifecycle.getManager(), "manager should be null when disabled");
    assertNull(lifecycle.getTransport(), "transport should be null when disabled");
    assertNull(lifecycle.getCache());
    assertNull(System.getProperty("jgroups.keystore_name"), "no system property leak when disabled");
    lifecycle.onStop(new ShutdownEvent());
    assertEquals(0, vertxTransportCount(), "no transport should have been added");
  }

  @Test
  void enabledWithoutKeystoreFailsFast() {
    InfinispanBridgeLifecycle lifecycle = new InfinispanBridgeLifecycle();
    lifecycle.enabled = true;
    lifecycle.cacheVerticleInstance = mockInstance(cacheVerticle);
    lifecycle.clusterName = "test-fail-" + System.nanoTime();
    lifecycle.cacheName = "csp-cw-" + System.nanoTime();
    lifecycle.ttlMs = 30000;
    lifecycle.maxEntries = 100000;
    lifecycle.jgroupsConfigFile = "jgroups-udp-csp.xml";
    lifecycle.jgroupsBindAddress = "127.0.0.1";
    lifecycle.jgroupsBindPort = 0;
    lifecycle.jgroupsMcastAddress = "228.10.10.10";
    lifecycle.jgroupsMcastPort = 45588;
    lifecycle.keystorePath = Optional.empty();
    lifecycle.keystoreAlias = Optional.empty();
    lifecycle.keystorePassword = Optional.empty();

    assertThrows(IllegalStateException.class, () -> lifecycle.onStart(new StartupEvent()));
    assertNull(lifecycle.getManager());
    // system props must be restored (no leak)
    assertNull(System.getProperty("jgroups.keystore_name"));
  }

  @Test
  void featureFlagOnCreatesManagerAndTransportWithKeystore() throws Exception {
    File ksFile = createTempKeystore();
    try {
      InfinispanBridgeLifecycle lifecycle = new InfinispanBridgeLifecycle();
      lifecycle.enabled = true;
      lifecycle.cacheVerticleInstance = mockInstance(cacheVerticle);
      lifecycle.clusterName = "test-cluster-on-" + System.nanoTime();
      lifecycle.cacheName = "csp-cw-" + System.nanoTime();
      lifecycle.ttlMs = 30000;
      lifecycle.maxEntries = 100000;
      lifecycle.jgroupsConfigFile = "jgroups-udp-csp.xml";
      lifecycle.jgroupsBindAddress = "127.0.0.1";
      lifecycle.jgroupsBindPort = 0;
      lifecycle.jgroupsMcastAddress = "228.10.10.10";
      lifecycle.jgroupsMcastPort = 45588;
      lifecycle.keystorePath = Optional.of(ksFile.getAbsolutePath());
      lifecycle.keystoreAlias = Optional.of("testkey");
      lifecycle.keystorePassword = Optional.of("changeit");

      // capture before value to verify restoration
      String before = System.getProperty("jgroups.keystore_name");
      lifecycle.onStart(new StartupEvent());
      assertNotNull(lifecycle.getManager(), "manager should be created when enabled");
      assertNotNull(lifecycle.getCache());
      assertNotNull(lifecycle.getTransport());
      // verify configurationFile property was used
      String cfg = lifecycle.getManager().getCacheManagerConfiguration().transport().properties().getProperty("configurationFile");
      assertEquals("jgroups-udp-csp.xml", cfg);
      // introspection runtime : stack doit contenir SYM_ENCRYPT et UDP
      assertTrue(hasProtocol(lifecycle.getManager(), lifecycle.cacheName, "SYM_ENCRYPT"), "runtime stack must contain SYM_ENCRYPT");
      assertTrue(hasProtocol(lifecycle.getManager(), lifecycle.cacheName, "UDP"), "runtime UDP stack must be active");
      // system props restored after start (no leak)
      assertEquals(before, System.getProperty("jgroups.keystore_name"));

      assertEquals(1, vertxTransportCount());

      lifecycle.onStop(new ShutdownEvent());
      assertNull(lifecycle.getManager(), "manager should be null after stop");
      assertNull(lifecycle.getTransport());
      assertEquals(0, vertxTransportCount(), "transport should be removed on shutdown");
    } finally {
      ksFile.delete();
    }
  }

  private int vertxTransportCount() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    final int[] count = {0};
    cacheVerticle.runOnContext(() -> { count[0] = cacheVerticle.transportCount(); latch.countDown(); });
    assertTrue(latch.await(2, TimeUnit.SECONDS));
    return count[0];
  }

  private static File createTempKeystore() throws Exception {
    File f = File.createTempFile("test-keystore", ".jceks");
    char[] pwd = "changeit".toCharArray();
    KeyStore ks = KeyStore.getInstance("JCEKS");
    ks.load(null, pwd);
    KeyGenerator kg = KeyGenerator.getInstance("AES");
    kg.init(128);
    SecretKey key = kg.generateKey();
    ks.setEntry("testkey", new KeyStore.SecretKeyEntry(key), new KeyStore.PasswordProtection(pwd));
    try (FileOutputStream out = new FileOutputStream(f)) {
      ks.store(out, pwd);
    }
    f.deleteOnExit();
    return f;
  }

  private static Instance<CacheVerticle> mockInstance(CacheVerticle cv) {
    return new SimpleInstance(cv);
  }

  private static boolean hasProtocol(org.infinispan.manager.EmbeddedCacheManager mgr, String cacheName, String protocol) {
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

  private static final class SimpleInstance implements Instance<CacheVerticle> {
    private final CacheVerticle cv;
    SimpleInstance(CacheVerticle cv) { this.cv = cv; }
    @Override public CacheVerticle get() { return cv; }
    @Override public boolean isUnsatisfied() { return cv == null; }
    @Override public boolean isAmbiguous() { return false; }
    @Override public boolean isResolvable() { return cv != null; }
    @Override public void destroy(CacheVerticle instance) {}
    @Override public Handle<CacheVerticle> getHandle() { throw new UnsupportedOperationException(); }
    @Override public Iterable<? extends Handle<CacheVerticle>> handles() { return Collections.emptyList(); }
    @Override public Instance<CacheVerticle> select(Annotation... qualifiers) { return this; }
    @Override public <U extends CacheVerticle> Instance<U> select(Class<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    @Override public <U extends CacheVerticle> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) { throw new UnsupportedOperationException(); }
    @Override public Iterator<CacheVerticle> iterator() { return Collections.singleton(cv).iterator(); }
  }
}
