package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RelayPolicyService;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.connectors.ConnectorSnapshot;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * Statut relais reel (ADR 0002) : desired/applied sans faux ack, separation
 * joignabilite/utilite. Managers absents => champs {@code null}, jamais 0 fictif.
 */
@Path("/api/admin/relay")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@NoCache
public class AdminRelayStatusResource {

  @Inject
  RelayPolicyService relayPolicyService;

  @Inject
  CwsConnectorManager connectorManager;

  @Inject
  ConfigRepository configRepository;

  public AdminRelayStatusResource() {}

  AdminRelayStatusResource(RelayPolicyService relay, CwsConnectorManager cm) {
    this.relayPolicyService = relay;
    this.connectorManager = cm;
  }

  @GET
  @Path("/status")
  public Uni<Response> status() {
    try {
      if (relayPolicyService == null || relayPolicyService.snapshot() == null) {
        return Uni.createFrom().item(Response.status(503)
            .entity(ErrorResponse.of("NOT_READY", "Relay policy not ready")).build());
      }
      // FIX #3 : pas de refresh opportuniste ; lecture de la politique event-bus.
      long now = System.currentTimeMillis();
      List<RelayPolicyService.NodeStatus> nodes = relayPolicyService.nodeStatuses(now);
      List<ConnectorSnapshot> connectors = connectorManager != null
          ? connectorManager.snapshotConnectors() : null;
      var snap = relayPolicyService.snapshot();
      // Stats remontees worker->central (brutes JSON bornees, absentes => null) sans bloquer l'event-loop.
      Uni<java.util.Map<String, String>> statsUni = configRepository != null
          ? Uni.createFrom().completionStage(() -> configRepository.loadNodeStats().toCompletionStage())
              .ifNoItem().after(java.time.Duration.ofSeconds(5)).fail()
              .onFailure().recoverWithItem((java.util.function.Function<Throwable, java.util.Map<String, String>>) t -> null)
          : Uni.createFrom().item((java.util.Map<String, String>) null);

      return statsUni.onItem().transform(workerStats -> {
        var payload = new RelayStatusPayload(
            nodes,
            connectors,
            snap != null ? snap.relayCaches() : null,
            snap != null ? snap.listenerCacheLinks() : null,
            relayPolicyService.effectiveTtlMs(),
            now,
            workerStats);
        return Response.ok(payload)
            .header("Cache-Control", AdminRuntimeResource.CACHE_CONTROL)
            .header("Pragma", AdminRuntimeResource.PRAGMA).build();
      });
    } catch (Exception e) {
      return Uni.createFrom().item(Response.status(500)
          .entity(ErrorResponse.of("INTERNAL_ERROR", "Internal error")).build());
    }
  }

  public record RelayStatusPayload(
      List<RelayPolicyService.NodeStatus> nodes,
      List<ConnectorSnapshot> connectors,
      Object caches,
      Object links,
      long workerConfigTtlMs,
      long nowMs,
      java.util.Map<String, String> workerStats) {}
}
