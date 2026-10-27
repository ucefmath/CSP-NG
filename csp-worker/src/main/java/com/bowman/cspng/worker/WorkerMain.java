package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import io.vertx.core.Vertx;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Entree worker headless (JVM) : dataplane reel CCcam/CS378x + CSPv2,
 * connecteurs amont assignes, catalogue/ACL sans DB. Aucune PG, aucune UI.
 *
 * <p>Boucle : poll desired → apply (version change) ou heartbeat (meme
 * version, renouvellement lease authentifie) ; 403/410 typés →
 * {@code failClosed} immediat avec fermeture sessions/listeners/pont.
 * Jamais de prolongation d'un revoque.
 */
public final class WorkerMain {

  public static void main(String[] args) {
    WorkerConfig cfg;
    try {
      cfg = WorkerConfig.fromEnv();
    } catch (Exception e) {
      System.err.println("Worker config invalid: " + e.getMessage());
      System.exit(2);
      return;
    }
    // Refus config locale independante : le worker ne lit aucun fichier de
    // connecteurs/listeners local — tout vient du desired central.
    Vertx vertx = Vertx.vertx();
    WorkerState state = new WorkerState(cfg.nodeId());
    WorkerRuntime runtime = new WorkerRuntime(vertx, cfg.nodeId(), state);
    runtime.setUdpPort(cfg.udpPort());
    WorkerClient client = new WorkerClient(vertx, cfg);
    if (cfg.cspv2Port() > 0) {
      StrictEcmRouter uplink = new StrictEcmRouter(runtime.connectors(), null, null,
          runtime.discovery(), "manual");
      uplink.setRelayPolicySupplier(state::policy, -1L);
      uplink.setRelayStores(state.stores());
      Set<String> profiles = Set.of();
      AtomicLong epoch = new AtomicLong(1L);
      WorkerCspV2Bridge bridge = new WorkerCspV2Bridge(vertx, cfg.nodeId(), cfg.cspv2Port(),
          cfg.certFile(), cfg.keyFile(), cfg.trustFile(), uplink, profiles, epoch);
      runtime.attachCspV2(bridge, uplink, epoch);
    }
    long poll = Math.min(cfg.pollIntervalMs(), cfg.ttlMs() / 3);
    vertx.setPeriodic(poll, id -> {
      long now = System.currentTimeMillis();
      if (state.expired(now) && state.appliedDesired() >= 0) {
        runtime.failClosed("expired");
      }
      // TTL qualité/quarantaine/quorum (lots 2/4/5) : sans ce balayage
      // périodique, une entrée jamais retouchée n'est jamais libérée
      // activement (purge paresseuse seule, au prochain accès).
      runtime.sweepPeerQualityState();
      client.fetchDesired()
          .onSuccess(desired -> onDesired(cfg, runtime, state, client, desired))
          .onFailure(err -> onControlError(runtime, err));
    });
    System.out.println("csp-worker started node=" + cfg.nodeId());
  }

  private static void onDesired(WorkerConfig cfg, WorkerRuntime runtime, WorkerState state,
                                WorkerClient client,
                                RelayWorkerContract.DesiredWorkerConfig desired) {
    runtime.applyDesired(desired, System.currentTimeMillis())
        .onSuccess(applied -> {
          var logs = runtime.drainLogs(200);
          client.ack(desired.desiredVersion(), safeStats(runtime), logs)
              .onFailure(err -> onControlError(runtime, err));
        })
        .onFailure(err -> {
          if (err instanceof WorkerHttpException) onControlError(runtime, err);
          else System.err.println("Worker apply failed: " + shortCause(err));
        });
  }

  static RelayWorkerContract.WorkerStatsView safeStats(WorkerRuntime runtime) {
    try {
      return runtime.collectStats();
    } catch (Exception e) {
      return null;
    }
  }

  static void onControlError(WorkerRuntime runtime, Throwable err) {
    if (err instanceof WorkerHttpException whe && whe.isRevoked()) {
      // 403/410 : failClosed immediat + fermeture sessions/listeners/pont.
      runtime.failClosed(whe.code());
    } else {
      // Cause bornee (classe + message court, jamais de body/secret).
      System.err.println("Worker control failed: " + shortCause(err));
    }
  }

  static String shortCause(Throwable err) {
    if (err == null) return "unknown";
    // Chaine COMPLETE (jamais de body : les causes reseau/TLS ne portent
    // ni secrets ni refs, uniquement classes/messages techniques).
    StringBuilder out = new StringBuilder();
    java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<>());
    for (Throwable t = err; t != null && seen.add(t); t = t.getCause()) {
      if (out.length() > 0) out.append(" <= ");
      String msg = t.getMessage();
      String shortMsg = msg == null ? "" : (msg.length() > 160 ? msg.substring(0, 160) : msg);
      out.append(t.getClass().getName());
      if (!shortMsg.isEmpty()) out.append(": ").append(shortMsg);
      if (out.length() > 1200) break;
    }
    return out.toString();
  }
}
