package com.bowman.cspng.app;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.app.runtime.RuntimeCore;
import com.bowman.cspng.cluster.udp.UdpClusterConfig;
import com.bowman.cspng.cluster.udp.UdpClusterVerticle;
import com.bowman.cspng.cluster.udp.UdpStats;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Bootstrap de la couche cluster UDP pour les TESTS D'INTEROP (lot L3b).
 *
 * <p><b>Périmètre strictement documenté</b> : ce bean est un ajout de l'agent de test
 * d'intégration (lot L3b) dans {@code csp-app} pour pouvoir exécuter le test
 * docker-compose 3 nœuds du plan §5.3. Il n'est activé QUE si la variable
 * d'environnement {@code CSP_UDP_CLUSTER_ENABLED=true} est posée (jamais par défaut,
 * le datapath de production n'est donc pas affecté tant que la vraie configuration
 * du lot L4+ n'existe pas). Il est explicitement permis par la tâche L3b :
 * « si ce verticle n'est pas encore déployé automatiquement au démarrage de csp-app,
 * tu as la latitude d'ajouter un bootstrap minimal dans csp-app ».
 *
 * <p>Ce que fait ce bean, quand il est activé :
 * <ul>
 *   <li>Déploie un {@link CacheVerticle} (config par défaut du lot L2).</li>
 *   <li>Déploie un {@link UdpClusterVerticle} avec la configuration lue depuis l'environnement :
 *       {@code CSP_UDP_BIND_PORT} (défaut 9009), {@code CSP_UDP_BIND_ADDRESS} (défaut 0.0.0.0),
 *       {@code CSP_UDP_REMOTE_HOST} (liste de pairs séparés par des virgules),
 *       {@code CSP_UDP_REMOTE_PORT}, {@code CSP_UDP_MAX_PPS} (défaut 50).</li>
 *   <li>Expose un endpoint HTTP <b>temporaire d'observation</b> {@code /cluster-stats}
 *       retournant les compteurs {@link UdpStats} en JSON (utilisé par le harness d'interop
 *       pour vérifier le scénario négatif du plan §2.6/§5.3) et {@code /cluster-peers}
 *       retournant le nombre de pairs connus.</li>
 * </ul>
 *
 * <p>Ces endpoints ne font partie d'aucune API de production et devront être remplacés
 * par la vraie couche de métriques (Micrometer/Prometheus) au lot L6/L9.
 */
@ApplicationScoped
public class InteropClusterBootstrap {

  private static final Logger LOG = Logger.getLogger(InteropClusterBootstrap.class.getName());

  private final Vertx vertx;
  private final Router router;
  private final Instance<RuntimeCore> runtimeCoreInstance;

  private volatile UdpClusterVerticle udpVerticle;
  private volatile CacheVerticle cacheVerticle;
  private volatile String udpDeploymentId;
  private volatile String cacheFallbackDeploymentId;
  private volatile boolean usingSharedCache = false;

  @Inject
  public InteropClusterBootstrap(Vertx vertx, Router router, Instance<RuntimeCore> runtimeCoreInstance) {
    this.vertx = vertx;
    this.router = router;
    this.runtimeCoreInstance = runtimeCoreInstance;
  }

  void onStart(@Observes @Priority(250) StartupEvent event) {
    if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("CSP_UDP_CLUSTER_ENABLED", "false"))) {
      LOG.info("InteropClusterBootstrap désactivé (CSP_UDP_CLUSTER_ENABLED != true) — démarrage nominal.");
      return;
    }
    LOG.info("InteropClusterBootstrap activé (mode test d'interop L3b).");
    registerObservationRoutes();
    deployVerticles();
  }

  private void registerObservationRoutes() {
    router.get("/cluster-stats").handler(ctx -> {
      UdpVerticleHolder current = snapshot();
      if (current.udp() == null || current.cache() == null) {
        ctx.response().setStatusCode(503).end("{\"status\":\"not-ready\"}");
        return;
      }
      Map<String, String> counters = current.udp().stats().asMap();
      counters.put("cached-ecms", String.valueOf(current.cache().stats().cachedEcms()));
      counters.put("pending-ecms", String.valueOf(current.cache().stats().pendingEcms()));
      counters.put("peer-count", String.valueOf(current.udp().peerRegistry().peerCount()));
      ctx.response().putHeader("Content-Type", "application/json")
          .end(new JsonObject(new java.util.LinkedHashMap<>(counters)).encode());
    });
    router.get("/cluster-peers").handler(ctx -> {
      UdpVerticleHolder current = snapshot();
      if (current.udp() == null) {
        ctx.response().setStatusCode(503).end("{\"status\":\"not-ready\"}");
        return;
      }
      ctx.response().putHeader("Content-Type", "application/json")
          .end(new JsonObject().put("peer-count", current.udp().peerRegistry().peerCount()).encode());
    });
    LOG.info("Endpoints d'observation enregistrés : GET /cluster-stats, GET /cluster-peers");
  }

  void onStop(@Observes @Priority(150) ShutdownEvent event) {
    LOG.info("InteropClusterBootstrap stopping — undeploy UDP then fallback cache");
    String udpId = udpDeploymentId;
    String cacheId = cacheFallbackDeploymentId;
    boolean shared = usingSharedCache;
    udpDeploymentId = null; cacheFallbackDeploymentId = null;
    if (udpId != null) {
      try { vertx.undeploy(udpId).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS); }
      catch (Exception e) { LOG.warning("Failed to undeploy UDP verticle: " + e.getMessage()); }
    }
    if (!shared && cacheId != null) {
      try { vertx.undeploy(cacheId).toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS); }
      catch (Exception e) { LOG.warning("Failed to undeploy fallback cache: " + e.getMessage()); }
    }
    udpVerticle = null;
    if (!shared) cacheVerticle = null;
  }

  private void deployVerticles() {
    CacheVerticle shared = null;
    if (runtimeCoreInstance != null && !runtimeCoreInstance.isUnsatisfied()) {
      try {
        RuntimeCore rc = runtimeCoreInstance.get();
        if (rc != null && rc.isEnabled() && rc.isStarted() && rc.getCacheVerticle() != null) {
          shared = rc.getCacheVerticle();
          usingSharedCache = true;
          LOG.info("InteropClusterBootstrap reusing shared CacheVerticle (RuntimeCore)");
        }
      } catch (Exception e) {
        LOG.warning("Failed to get RuntimeCore shared cache: " + e.getMessage());
      }
    }
    if (shared != null) {
      cacheVerticle = shared;
      deployUdpWithCache(shared);
      return;
    }
    usingSharedCache = false;
    CacheVerticle cache = new CacheVerticle();
    cacheVerticle = cache;
    vertx.deployVerticle(cache)
        .onSuccess(cacheId -> { cacheFallbackDeploymentId = cacheId; deployUdpWithCache(cache); })
        .onFailure(t -> LOG.severe("Échec du déploiement CacheVerticle : " + t.getMessage()));
  }

  private void deployUdpWithCache(CacheVerticle cache) {
    String remoteHost = envOr("CSP_UDP_REMOTE_HOST", "");
    String remotePort = envOr("CSP_UDP_REMOTE_PORT", "");
    UdpClusterConfig cfg = new UdpClusterConfig(
        intEnv("CSP_UDP_BIND_PORT", 9009),
        envOr("CSP_UDP_BIND_ADDRESS", "0.0.0.0"),
        remoteHost.isEmpty() ? null : remoteHost,
        remotePort.isEmpty() ? null : remotePort,
        null, 9009, 1,
        true,
        4000,
        intEnv("CSP_UDP_MAX_PPS", 50),
        null);
    UdpClusterVerticle udp = new UdpClusterVerticle().configure(cfg).setCacheVerticle(cache);
    // Lot 1 : policy DCW live (modes scopés) pour la validation avant injection.
    // Indisponible => garde historique (DEFAULT) via le transport.
    try {
      if (runtimeCoreInstance != null && !runtimeCoreInstance.isUnsatisfied()) {
        RuntimeCore rc = runtimeCoreInstance.get();
        if (rc != null) {
          udp.setDcwPolicySupplier(() -> {
            try {
              var cm = rc.cwsConnectorManager();
              return cm != null ? cm.effectiveDcwPolicy()
                  : com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
            } catch (Exception ignored) {
              return com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
            }
          });
        }
      }
    } catch (Exception ignored) {
      // transport en garde historique
    }
    udpVerticle = udp;
    vertx.deployVerticle(udp)
        .onSuccess(udpId -> { udpDeploymentId = udpId; LOG.info("UDP cluster verticle déployé : id=" + udpId + " config=" + cfg); })
        .onFailure(t -> LOG.severe("Échec du déploiement UdpClusterVerticle : " + t.getMessage()));
  }

  private UdpVerticleHolder snapshot() {
    return new UdpVerticleHolder(udpVerticle, cacheVerticle);
  }

  private static String envOr(String name, String def) {
    String v = System.getenv(name);
    return v == null || v.isBlank() ? def : v.trim();
  }

  private static int intEnv(String name, int def) {
    try {
      return Integer.parseInt(envOr(name, String.valueOf(def)));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private record UdpVerticleHolder(UdpClusterVerticle udp, CacheVerticle cache) {}
}
