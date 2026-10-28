package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.bowman.cspng.domain.srvid.ServiceResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stats CacheEx PAR PAIR (contrat v2 §4).
 *
 * <p>Compteurs réels uniquement : une paire sans trafic n'apparaît pas (jamais de zéros
 * fabriqués). Les paires du central (registry local) sont fusionnées avec celles
 * remontées par les workers (payload {@code WorkerStatsView.cacheex}, peerId préfixé
 * {@code <node>/<peerId>} pour lever toute ambiguïté — jamais de zéros si le
 * payload est absent ou illisible). {@code POST /stats/reset} remet les compteurs
 * CENTRAUX à zéro sans toucher à la config (les workers remettent à zéro à leur
 * prochain ack — compteurs monotones par nature).
 */
@Path("/api/admin/cacheex")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@NoCache
public class AdminCacheexResource {

  private static final Logger LOG = Logger.getLogger(AdminCacheexResource.class.getName());

  @Inject
  CacheExStatsRegistry registry;

  @Inject
  ConfigRepository configRepository;

  @Inject
  ObjectMapper mapper;

  @Inject
  Instance<ServiceResolver> serviceResolverInstance;

  private ServiceResolver effectiveResolver() {
    return (serviceResolverInstance != null && serviceResolverInstance.isResolvable())
        ? serviceResolverInstance.get()
        : ServiceResolver.EMPTY;
  }

  private static String resolveChannel(int caid, int srvid,
                                       ServiceResolver resolver,
                                       Map<Integer, String> channelNameCache) {
    int key = (caid << 16) | (srvid & 0xFFFF);
    if (channelNameCache.containsKey(key)) {
      return channelNameCache.get(key);
    }
    String name = resolver.resolveChannelName(caid, srvid);
    channelNameCache.put(key, name);
    return name;
  }

  private static CacheExStatsRegistry.PairStats enrichPair(
      CacheExStatsRegistry.PairStats p,
      ServiceResolver resolver,
      Map<Integer, String> channelNameCache) {
    if (p.sids() == null || p.sids().isEmpty()) return p;
    List<CacheExStatsRegistry.SidStats> enriched = new ArrayList<>(p.sids().size());
    for (CacheExStatsRegistry.SidStats s : p.sids()) {
      String name = resolveChannel(s.caid(), s.srvid(), resolver, channelNameCache);
      enriched.add(s.withChannelName(name));
    }
    return new CacheExStatsRegistry.PairStats(
        p.peerId(), p.direction(), p.protocol(), p.mode(), p.profile(), p.online(), p.state(),
        p.maxHop(), p.maxSendQueue(), p.filterActive(), p.pushedCw(), p.receivedCw(), p.droppedCw(),
        p.dropReasons(), p.lastPushAtMs(), p.lastReceiveAtMs(), p.remoteIp(), p.remoteNodeId(),
        p.hits(), enriched);
  }

  @GET
  @Path("/stats")
  public Uni<Response> stats() {
    ServiceResolver resolver = effectiveResolver();
    Map<Integer, String> channelNameCache = new HashMap<>();
    List<CacheExStatsRegistry.PairStats> pairs = new ArrayList<>();
    for (CacheExStatsRegistry.PairStats p : registry.snapshot()) {
      pairs.add(enrichPair(p, resolver, channelNameCache));
    }
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(workerStats -> {
          if (workerStats != null) {
            for (var e : workerStats.entrySet()) {
              pairs.addAll(parseWorkerPairs(e.getKey(), e.getValue(), resolver, channelNameCache));
            }
          }
          pairs.sort((a, b) -> a.peerId().compareTo(b.peerId()));
          return Response.ok(new StatsResponse(System.currentTimeMillis(), pairs)).build();
        })
        .onFailure().recoverWithItem(t -> {
          LOG.log(Level.FINE, "Worker stats unavailable for cacheex merge", t);
          pairs.sort((a, b) -> a.peerId().compareTo(b.peerId()));
          return Response.ok(new StatsResponse(System.currentTimeMillis(), pairs)).build();
        });
  }

  /** Paires worker : absentes/illisibles => liste vide (jamais de zéros fictifs). */
  List<CacheExStatsRegistry.PairStats> parseWorkerPairs(String nodeName, String payloadJson) {
    return parseWorkerPairs(nodeName, payloadJson, effectiveResolver(), new HashMap<>());
  }

  List<CacheExStatsRegistry.PairStats> parseWorkerPairs(
      String nodeName, String payloadJson,
      ServiceResolver resolver,
      Map<Integer, String> channelNameCache) {
    List<CacheExStatsRegistry.PairStats> out = new ArrayList<>();
    if (nodeName == null || payloadJson == null || payloadJson.isBlank()) return out;
    try {
      JsonNode root = mapper.readTree(payloadJson);
      JsonNode arr = root.path("cacheex");
      if (!arr.isArray()) return out;
      for (JsonNode p : arr) {
        String peerId = p.path("peerId").asText(null);
        if (peerId == null || peerId.isBlank()) continue;
        Map<String, Long> reasons = new LinkedHashMap<>();
        JsonNode dr = p.path("dropReasons");
        for (String r : List.of("loop", "hop", "checksum", "filtered", "invalid", "queue", "flood",
            "quarantine", "quorum")) {
          reasons.put(r, dr.path(r).asLong(0));
        }
        String remoteIp = p.path("remoteIp").asText("");
        String remoteNodeId = p.path("remoteNodeId").asText("");
        long hits = p.path("hits").asLong(0);

        List<CacheExStatsRegistry.SidStats> sidList = new ArrayList<>();
        JsonNode sidsNode = p.path("sids");
        if (sidsNode.isArray()) {
          for (JsonNode sidNode : sidsNode) {
            if (sidList.size() >= CacheExStatsRegistry.MAX_SIDS_PER_PAIR) break;
            int caid = sidNode.path("caid").asInt(0);
            int provid = sidNode.path("provid").asInt(0);
            int srvid = sidNode.path("srvid").asInt(0);
            long pushedCw = sidNode.path("pushedCw").asLong(0);
            long receivedCw = sidNode.path("receivedCw").asLong(0);
            long sHits = sidNode.path("hits").asLong(0);
            long droppedCw = sidNode.path("droppedCw").asLong(0);
            Long lastActivityAtMs = sidNode.has("lastActivityAtMs") && !sidNode.path("lastActivityAtMs").isNull()
                ? sidNode.path("lastActivityAtMs").asLong() : null;
            String channelName = resolveChannel(caid, srvid, resolver, channelNameCache);
            sidList.add(new CacheExStatsRegistry.SidStats(
                caid, provid, srvid,
                CacheExStatsRegistry.formatServiceKey(caid, provid, srvid),
                channelName,
                pushedCw, receivedCw, sHits, droppedCw, lastActivityAtMs));
          }
        }

        out.add(new CacheExStatsRegistry.PairStats(
            nodeName + "/" + peerId,
            p.path("direction").asText(""),
            p.path("protocol").asText(""),
            p.path("mode").asInt(0),
            p.path("profile").asText(""),
            p.path("online").asBoolean(false),
            p.path("state").asText("unknown"),
            p.path("maxHop").asInt(10),
            p.path("maxSendQueue").asInt(2000),
            p.path("filterActive").asBoolean(false),
            p.path("pushedCw").asLong(0),
            p.path("receivedCw").asLong(0),
            p.path("droppedCw").asLong(0),
            reasons,
            p.has("lastPushAtMs") && !p.path("lastPushAtMs").isNull()
                ? p.path("lastPushAtMs").asLong() : null,
            p.has("lastReceiveAtMs") && !p.path("lastReceiveAtMs").isNull()
                ? p.path("lastReceiveAtMs").asLong() : null,
            remoteIp,
            remoteNodeId,
            hits,
            sidList));
      }
    } catch (Exception e) {
      LOG.log(Level.FINE, "Unreadable worker cacheex payload for " + nodeName, e);
    }
    return out;
  }

  @POST
  @Path("/stats/reset")
  @Consumes(MediaType.WILDCARD)
  public Uni<Response> reset() {
    registry.reset();
    return Uni.createFrom().item(Response.noContent().build());
  }

  public record StatsResponse(long generatedAtMs, List<CacheExStatsRegistry.PairStats> pairs) {}
}
