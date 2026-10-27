package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.connectors.cacheex.CacheExStatsRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
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

  @GET
  @Path("/stats")
  public Uni<Response> stats() {
    List<CacheExStatsRegistry.PairStats> pairs = new ArrayList<>(registry.snapshot());
    return Uni.createFrom().completionStage(configRepository.loadNodeStats().toCompletionStage())
        .onItem().transform(workerStats -> {
          if (workerStats != null) {
            for (var e : workerStats.entrySet()) {
              pairs.addAll(parseWorkerPairs(e.getKey(), e.getValue()));
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
                ? p.path("lastReceiveAtMs").asLong() : null));
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
