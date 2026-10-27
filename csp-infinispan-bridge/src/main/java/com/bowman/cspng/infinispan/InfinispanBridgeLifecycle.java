package com.bowman.cspng.infinispan;

import com.bowman.cspng.cache.CacheVerticle;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.jgroups.JChannel;
import org.jgroups.stack.ProtocolStack;

@ApplicationScoped
public class InfinispanBridgeLifecycle {

  private static final Logger LOG = Logger.getLogger(InfinispanBridgeLifecycle.class.getName());

  @Inject
  Instance<CacheVerticle> cacheVerticleInstance;

  @ConfigProperty(name = "csp.infinispan.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "csp.infinispan.cluster-name", defaultValue = "csp-infinispan")
  String clusterName;

  @ConfigProperty(name = "csp.infinispan.cache-name", defaultValue = "csp-cw")
  String cacheName;

  @ConfigProperty(name = "csp.infinispan.ttl-ms", defaultValue = "30000")
  long ttlMs;

  @ConfigProperty(name = "csp.infinispan.max-entries", defaultValue = "100000")
  long maxEntries;

  @ConfigProperty(name = "csp.infinispan.jgroups.config-file", defaultValue = "jgroups-udp-csp.xml")
  String jgroupsConfigFile;

  @ConfigProperty(name = "csp.infinispan.jgroups.bind-address", defaultValue = "127.0.0.1")
  String jgroupsBindAddress;

  @ConfigProperty(name = "csp.infinispan.jgroups.bind-port", defaultValue = "7800")
  int jgroupsBindPort;

  @ConfigProperty(name = "csp.infinispan.jgroups.mcast-address", defaultValue = "228.10.10.10")
  String jgroupsMcastAddress;

  @ConfigProperty(name = "csp.infinispan.jgroups.mcast-port", defaultValue = "45588")
  int jgroupsMcastPort;

  @ConfigProperty(name = "csp.infinispan.keystore.path")
  Optional<String> keystorePath;

  @ConfigProperty(name = "csp.infinispan.keystore.alias")
  Optional<String> keystoreAlias;

  @ConfigProperty(name = "csp.infinispan.keystore.password")
  Optional<String> keystorePassword;

  private volatile EmbeddedCacheManager manager;
  private volatile Cache<Integer, byte[]> cache;
  private volatile InfinispanCacheTransport transport;
  private volatile InfinispanCacheListener listener;

  public void onStart(@Observes @Priority(220) StartupEvent event) {
    if (!enabled) {
      LOG.info("Infinispan bridge disabled (csp.infinispan.enabled=false) — no manager/transport");
      return;
    }
    if (cacheVerticleInstance == null || cacheVerticleInstance.isUnsatisfied()) {
      LOG.warning("Infinispan bridge: CacheVerticle not available — skipping");
      return;
    }
    CacheVerticle cv;
    try {
      cv = cacheVerticleInstance.get();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Infinispan bridge: cannot resolve CacheVerticle", e);
      throw new IllegalStateException("Infinispan bridge: cannot resolve CacheVerticle", e);
    }
    if (cv == null) {
      throw new IllegalStateException("Infinispan bridge: resolved CacheVerticle is null");
    }

    // Fail-fast sécurité keystore obligatoire (uniquement si enabled=true)
    // Propriétés optionnelles : absentes => Optional.empty() => orElse("")
    String ksPath = keystorePath != null ? keystorePath.orElse("") : "";
    String ksAlias = keystoreAlias != null ? keystoreAlias.orElse("") : "";
    String ksPassword = keystorePassword != null ? keystorePassword.orElse("") : "";
    if (isBlank(ksPath) || isBlank(ksAlias) || isBlank(ksPassword)) {
      throw new IllegalStateException("Infinispan security required: csp.infinispan.keystore.path/alias/password must be set when enabled=true");
    }
    java.io.File ksFile = new java.io.File(ksPath);
    if (!ksFile.isFile() || !ksFile.canRead()) {
      throw new IllegalStateException("Infinispan keystore not readable: " + ksPath);
    }

    Map<String, String> oldProps = captureSystemProperties();
    try {
      // poser temporairement les propriétés pour substitution JGroups
      System.setProperty("jgroups.bind_addr", jgroupsBindAddress);
      System.setProperty("jgroups.bind_port", String.valueOf(jgroupsBindPort));
      System.setProperty("jgroups.mcast_addr", jgroupsMcastAddress);
      System.setProperty("jgroups.mcast_port", String.valueOf(jgroupsMcastPort));
      System.setProperty("jgroups.keystore_name", ksPath);
      System.setProperty("jgroups.store_password", ksPassword);
      System.setProperty("jgroups.keystore_password", ksPassword);
      System.setProperty("jgroups.keystore_alias", ksAlias);

      GlobalConfigurationBuilder gcb = new GlobalConfigurationBuilder();
      gcb.transport().defaultTransport().clusterName(clusterName);
      gcb.transport().addProperty("configurationFile", jgroupsConfigFile);
      GlobalConfiguration global = gcb.build();

      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.clustering().cacheMode(CacheMode.REPL_ASYNC);
      builder.memory().maxCount(maxEntries);
      builder.expiration().lifespan(ttlMs);
      builder.persistence().passivation(false);

      EmbeddedCacheManager mgr = new DefaultCacheManager(global, false);
      mgr.defineConfiguration(cacheName, builder.build());
      mgr.start();
      Cache<Integer, byte[]> c = mgr.getCache(cacheName);
      validateSecureStack(mgr, cacheName);

      InfinispanCacheListener l = new InfinispanCacheListener(cv);
      c.addListener(l);

      InfinispanCacheTransport t = new InfinispanCacheTransport(c);
      cv.addTransport(t);

      this.manager = mgr;
      this.cache = c;
      this.listener = l;
      this.transport = t;
      LOG.info("Infinispan bridge started cluster=" + clusterName + " cache=" + cacheName);
    } catch (IllegalStateException e) {
      cleanupQuietly();
      throw e;
    } catch (Exception e) {
      cleanupQuietly();
      LOG.log(Level.SEVERE, "Failed to start Infinispan bridge", e);
      throw new IllegalStateException("Failed to start Infinispan bridge", e);
    } finally {
      restoreSystemProperties(oldProps);
    }
  }

  public void onStop(@Observes @Priority(180) ShutdownEvent event) {
    if (transport != null) {
      try {
        Instance<CacheVerticle> inst = cacheVerticleInstance;
        if (inst != null && !inst.isUnsatisfied()) {
          try {
            CacheVerticle cv = inst.get();
            if (cv != null) cv.removeTransport(transport);
          } catch (Exception ignored) {
          }
        }
      } finally {
        transport = null;
      }
    }
    if (cache != null && listener != null) {
      try { cache.removeListener(listener); } catch (Exception ignored) {}
      listener = null;
    }
    cache = null;
    if (manager != null) {
      try { manager.stop(); } catch (Exception e) {
        LOG.log(Level.WARNING, "Error stopping Infinispan manager", e);
      }
      manager = null;
    }
  }

  private void cleanupQuietly() {
    if (cache != null && listener != null) { try { cache.removeListener(listener); } catch (Exception ignored) {} }
    listener = null;
    cache = null;
    if (manager != null) { try { manager.stop(); } catch (Exception ignored) {} }
    manager = null;
    transport = null;
  }

  private static void validateSecureStack(EmbeddedCacheManager mgr, String cacheName) {
    try {
      org.infinispan.Cache<?,?> cache = mgr.getCache(cacheName);
      org.infinispan.remoting.rpc.RpcManager rpc = cache.getAdvancedCache().getRpcManager();
      if (rpc == null) throw new IllegalStateException("secure JGroups stack not active");
      org.infinispan.remoting.transport.Transport transport = rpc.getTransport();
      if (!(transport instanceof JGroupsTransport)) throw new IllegalStateException("secure JGroups stack not active");
      JGroupsTransport jg = (JGroupsTransport) transport;
      JChannel channel = jg.getChannel();
      if (channel == null) throw new IllegalStateException("secure JGroups stack not active");
      ProtocolStack stack = channel.getProtocolStack();
      if (stack == null) throw new IllegalStateException("secure JGroups stack not active");
      Object sym = null;
      try {
        sym = stack.findProtocol("SYM_ENCRYPT");
      } catch (Exception e) {
        // fallback via class
        try { sym = stack.findProtocol(org.jgroups.protocols.SYM_ENCRYPT.class); } catch (Exception ignored) {}
      }
      if (sym == null) throw new IllegalStateException("secure JGroups stack not active");
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("secure JGroups stack not active", e);
    }
  }

  private static Map<String, String> captureSystemProperties() {
    Map<String, String> m = new HashMap<>();
    String[] keys = {"jgroups.bind_addr","jgroups.bind_port","jgroups.mcast_addr","jgroups.mcast_port",
        "jgroups.keystore_name","jgroups.store_password","jgroups.keystore_password","jgroups.keystore_alias"};
    for (String k : keys) m.put(k, System.getProperty(k));
    return m;
  }

  private static void restoreSystemProperties(Map<String, String> old) {
    for (Map.Entry<String, String> e : old.entrySet()) {
      if (e.getValue() == null) System.clearProperty(e.getKey());
      else System.setProperty(e.getKey(), e.getValue());
    }
  }

  private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

  // Visible for testing
  public EmbeddedCacheManager getManager() { return manager; }
  public Cache<Integer, byte[]> getCache() { return cache; }
  public InfinispanCacheTransport getTransport() { return transport; }
  public boolean isEnabled() { return enabled; }
}
