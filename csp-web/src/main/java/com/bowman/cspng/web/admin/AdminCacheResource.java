package com.bowman.cspng.web.admin;

import com.bowman.cspng.cache.CacheStats;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.RelayCacheConfig;
import com.bowman.cspng.config.RelayUdpPeerConfig;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.NoCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * État des caches (contrat v2 §3) — fraîcheur EXPLICITE, jamais de faux zéros.
 *
 * <p>Règles (arbitrage) : stats worker absentes ou périmées ⇒ {@code null} / inconnu,
 * JAMAIS de zéros fabriqués ni d'état « actif » déduit de la seule présence de stats.
 * {@code state} reflète la CONFIG uniquement ({@code enabled|disabled}) ;
 * {@code statsAgeMs}/{@code statsFresh} portent la fraîcheur (seuil
 * {@code csp.admin.stats-fresh-ms}, défaut 90 s — une stat worker par ack, cadence ≈ TTL).
 */
@Path("/api/admin/cache")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@NoCache
public class AdminCacheResource {

  private static final Logger LOG = Logger.getLogger(AdminCacheResource.class.getName());

  @Inject
  ConfigHolder configHolder;

  @Inject
  ConfigRepository configRepository;

  @Inject
  CacheVerticle cacheVerticle;

  @Inject
  ObjectMapper mapper;

  @ConfigProperty(name = "csp.admin.stats-fresh-ms", defaultValue = "90000")
  long statsFreshMs;

  @GET
  @Path("/status")
  public Uni<Response> status() {
    if (!configHolder.isInitialized()) {
      return Uni.createFrom().item(Response.status(503)
          .entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build());
    }
    ConfigSnapshot snap = configHolder.get();
    long now = System.currentTimeMillis();

    // 1) Local (CacheVerticle central) — lecture directe, null si indisponible (pas de zéros)
    Uni<LocalView> localUni = cacheVerticle != null
        ? Uni.createFrom().completionStage(cacheVerticle.snapshotStats().toCompletionStage())
            .onItem().transform(stats -> toLocalView(snap.cache(), stats))
            .onFailure().recoverWithItem((java.util.function.Function<Throwable, LocalView>) t -> null)
        : Uni.createFrom().item((LocalView) null);

    // 2) Stats worker brutes (payload JSON borne par node — absentes => null, jamais 0 fictif)
    Uni<Map<String, String>> statsUni =
        Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
            .onFailure().recoverWithItem((java.util.function.Function<Throwable, Map<String, String>>) t -> null);

    return Uni.combine().all().unis(localUni, statsUni).asTuple().onItem().transform(tuple -> {
      LocalView local = tuple.getItem1();
      Map<String, String> workerStats = tuple.getItem2();

      List<CacheView> caches = new ArrayList<>();
      List<NodeView> nodes = new ArrayList<>();
      UdpView udp = null;

      // Index worker: nodeName -> payload parsé (ou null si illisible)
      java.util.Map<String, JsonNode> payloads = new java.util.HashMap<>();
      if (workerStats != null) {
        for (var e : workerStats.entrySet()) {
          try {
            payloads.put(e.getKey(), mapper.readTree(e.getValue()));
          } catch (Exception ex) {
            LOG.fine("Unreadable worker stats payload for " + e.getKey());
          }
        }
      }

      for (RelayCacheConfig rc : snap.relayCaches()) {
        String worker = "node".equals(rc.kind()) ? rc.nodeName() : null;
        JsonNode statsNode = worker != null ? payloads.get(worker) : null;
        FreshStat fresh = freshness(statsNode, now);
        CacheStatsView statsView = null;
        if (statsNode != null && fresh.fresh()) {
          JsonNode cachesArr = statsNode.path("caches");
          for (int i = 0; i < cachesArr.size(); i++) {
            JsonNode c = cachesArr.get(i);
            if (rc.name().equals(c.path("name").asText(null))) {
              statsView = new CacheStatsView(
                  c.path("consultHits").asLong(0), c.path("feedPuts").asLong(0));
              break;
            }
          }
        }
        caches.add(new CacheView(
            rc.name(), rc.kind(), rc.enabled() ? "enabled" : "disabled",
            rc.nodeName(), rc.maxAgeMs(), rc.maxEntries(), rc.maxInflight(), rc.version(),
            peersFor(snap, rc.name()),
            statsView, fresh.ageMs(), fresh.fresh()));
      }

      for (var e : payloads.entrySet()) {
        JsonNode payload = e.getValue();
        FreshStat fresh = freshness(payload, now);
        JsonNode nodesArr = payload.path("nodes");
        JsonNode nodeEntry = null;
        for (int i = 0; i < nodesArr.size(); i++) {
          if (e.getKey().equals(nodesArr.get(i).path("nodeName").asText(null))) {
            nodeEntry = nodesArr.get(i);
            break;
          }
        }
        // usable: null (inconnu) si pas de stat fraîche — jamais de faux état
        Boolean usable = fresh.fresh() && nodeEntry != null && nodeEntry.has("usable")
            ? nodeEntry.path("usable").asBoolean() : null;
        nodes.add(new NodeView(
            e.getKey(), usable,
            fresh.fresh() && nodeEntry != null && nodeEntry.has("consultOk")
                ? nodeEntry.path("consultOk").asLong(0) : null,
            fresh.fresh() && nodeEntry != null && nodeEntry.has("avgRttMs") && !nodeEntry.path("avgRttMs").isNull()
                ? nodeEntry.path("avgRttMs").asLong() : null,
            fresh.ageMs(), fresh.fresh()));
      }

      // UDP : config + compteurs du worker si frais, sinon stats null
      JsonNode anyFresh = payloads.values().stream()
          .filter(p -> freshness(p, now).fresh() && p.has("udp"))
          .findFirst().orElse(null);
      if (anyFresh != null) {
        JsonNode u = anyFresh.path("udp");
        UdpStatsView uv = new UdpStatsView(
            u.path("sentReq").asLong(0), u.path("sentRepl").asLong(0),
            u.path("recvReq").asLong(0), u.path("recvRepl").asLong(0),
            u.path("rejected").asLong(0), u.path("rateLimited").asLong(0),
            u.has("avgRttMs") && !u.path("avgRttMs").isNull() ? u.path("avgRttMs").asLong() : null);
        FreshStat f = freshness(anyFresh, now);
        udp = new UdpView(snap.clusterUdp().enabled(), snap.clusterUdp().bindPort(),
            uv, f.ageMs(), f.fresh());
      } else {
        udp = new UdpView(snap.clusterUdp().enabled(), snap.clusterUdp().bindPort(),
            null, null, false);
      }

      return Response.ok(new StatusResponse(now, true, local, caches, nodes, udp)).build();
    });
  }

  private FreshStat freshness(JsonNode payload, long now) {
    if (payload == null) return new FreshStat(null, false);
    if (!payload.has("collectedAtMs")) return new FreshStat(null, false);
    long collected = payload.path("collectedAtMs").asLong(0);
    if (collected <= 0) return new FreshStat(null, false);
    long age = Math.max(0, now - collected);
    return new FreshStat(age, age <= statsFreshMs);
  }

  private static List<PeerView> peersFor(ConfigSnapshot snap, String cacheName) {
    List<PeerView> out = new ArrayList<>();
    for (RelayUdpPeerConfig p : snap.udpPeersForCache(cacheName)) {
      out.add(new PeerView(p.host(), p.port(), p.enabled()));
    }
    return out;
  }

  private static LocalView toLocalView(com.bowman.cspng.config.CacheConfig cfg, CacheStats stats) {
    if (stats == null) return null;
    return new LocalView(cfg.cwMaxAgeMs(), cfg.maximumSize(), cfg.maxCacheWaitMs(),
        cfg.maxCacheWaitPercent(), stats.pendingEcms(), stats.pendingPeak(),
        stats.cachedEcms(), stats.timeouts(), stats.instantHits(), stats.waitHits(),
        stats.contested(), stats.remoteHits());
  }

  private record FreshStat(Long ageMs, boolean fresh) {}

  // -- DTOs (contrat v2 §3) --

  public record StatusResponse(
      long generatedAtMs,
      boolean ready,
      LocalView local,
      List<CacheView> caches,
      List<NodeView> nodes,
      UdpView udp) {}

  public record LocalView(
      long cwMaxAgeMs, long maximumSize, long maxCacheWaitMs, int maxCacheWaitPercent,
      int pendingEcms, int pendingPeak, long cachedEcms, long timeouts,
      long instantHits, long waitHits, long contested, long remoteHits) {}

  public record PeerView(String host, int port, boolean enabled) {}

  public record CacheStatsView(long consultHits, long feedPuts) {}

  public record CacheView(
      String name, String kind, String state, String nodeName,
      long maxAgeMs, int maxEntries, int maxInflight, long version,
      List<PeerView> peers,
      CacheStatsView stats, Long statsAgeMs, boolean statsFresh) {}

  public record NodeView(
      String nodeName, Boolean usable, Long consultOk, Long avgRttMs,
      Long statsAgeMs, boolean statsFresh) {}

  public record UdpStatsView(
      long sentReq, long sentRepl, long recvReq, long recvRepl,
      long rejected, long rateLimited, Long avgRttMs) {}

  public record UdpView(boolean enabled, int bindPort, UdpStatsView stats,
                        Long statsAgeMs, boolean statsFresh) {}
}
