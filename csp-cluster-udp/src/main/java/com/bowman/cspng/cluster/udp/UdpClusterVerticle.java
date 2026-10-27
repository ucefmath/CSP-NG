package com.bowman.cspng.cluster.udp;

import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Verticle UDP cluster — point d'entrée de la couche de transport L2 (plan §3.4).
 *
 * <h3>Responsabilités</h3>
 * <ul>
 *   <li>Crée le {@link DatagramSocket} Vert.x et le bind sur le port configuré.</li>
 *   <li>Rejoint le groupe multicast si configuré.</li>
 *   <li>Instancie {@link PeerRegistry}, {@link UdpStats}, {@link UdpLegacyTransport}.</li>
 *   <li>Enregistre le transport auprès de {@link CacheVerticle}.</li>
 *   <li>Lance le timer de ping périodique (~4s).</li>
 *   <li>Expose les compteurs via {@link #stats()}.</li>
 * </ul>
 *
 * <h3>Configuration (via {@code UdpClusterConfig})</h3>
 * <ul>
 *   <li>{@code bind-port} : port d'écoute UDP.</li>
 *   <li>{@code bind-address} : adresse d'écoute (défaut 0.0.0.0).</li>
 *   <li>{@code remote-host} / {@code remote-port} : pairs statiques.</li>
 *   <li>{@code multicast-group} / {@code multicast-port} : adresse multicast.</li>
 *   <li>{@code auto-add-peers} : activer l'auto-ajout (défaut true).</li>
 *   <li>{@code ping-interval-ms} : intervalle ping (défaut 4000).</li>
 *   <li>{@code max-packets-per-sec} : seuil rate-limiting (défaut 50).</li>
 *   <li>{@code blacklist} : liste d'IP bloquées.</li>
 * </ul>
 */
public class UdpClusterVerticle extends AbstractVerticle {

  private static final Logger LOG = Logger.getLogger(UdpClusterVerticle.class.getName());

  private UdpClusterConfig config = UdpClusterConfig.defaults();
  private DatagramSocket socket;
  private PeerRegistry peerRegistry;
  private UdpStats stats;
  private UdpLegacyTransport transport;
  private CacheVerticle cacheVerticle;
  private Long pingTimerId;

  // ---- Configuration ----

  public UdpClusterVerticle configure(UdpClusterConfig config) {
    this.config = config;
    return this;
  }

  public UdpClusterVerticle setCacheVerticle(CacheVerticle cacheVerticle) {
    this.cacheVerticle = cacheVerticle;
    return this;
  }

  /**
   * Politique DCW pour la validation contextuelle avant injection centrale
   * (lot 1). Appliquée au transport à sa création (et immédiatement s'il
   * existe déjà). Null = gardes historiques.
   */
  private volatile java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> dcwPolicySupplier;

  public UdpClusterVerticle setDcwPolicySupplier(
      java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> supplier) {
    this.dcwPolicySupplier = supplier;
    UdpLegacyTransport t = transport;
    if (t != null) t.setDcwPolicySupplier(supplier);
    return this;
  }

  // ---- Lifecycle ----

  @Override
  public void start(Promise<Void> startPromise) {
    this.stats = new UdpStats();
    this.peerRegistry = new PeerRegistry();

    // Configuration des pairs statiques
    if (config.remoteHost() != null && !config.remoteHost().isEmpty()) {
      String[] hosts = config.remoteHost().split(",");
      int[] ports = parsePorts(config.remotePort());
      for (int i = 0; i < hosts.length; i++) {
        String host = hosts[i].trim();
        int port = i < ports.length ? ports[i] : ports[ports.length - 1];
        if (!host.isEmpty()) {
          peerRegistry.addStaticPeer(host, port);
        }
      }
    }

    // Blacklist
    if (config.blacklist() != null) {
      for (String ip : config.blacklist().split(",")) {
        String trimmed = ip.trim();
        if (!trimmed.isEmpty()) peerRegistry.blockIp(trimmed);
      }
    }

    peerRegistry.setAutoAddPeers(config.autoAddPeers());

    // Multicast
    if (config.multicastGroup() != null && !config.multicastGroup().isEmpty()) {
      peerRegistry.setMulticastGroup(config.multicastGroup(), config.multicastPort());
    }

    // Création du DatagramSocket
    DatagramSocketOptions opts = new DatagramSocketOptions()
        .setReuseAddress(true)
        .setReusePort(true);
    if (config.multicastGroup() != null && !config.multicastGroup().isEmpty()) {
      opts.setMulticastTimeToLive(config.multicastTtl());
    }

    socket = vertx.createDatagramSocket(opts);

    socket.exceptionHandler(t -> {
      LOG.log(Level.WARNING, "DatagramSocket error", t);
    });

    // Handler de paquets
    socket.handler(packet -> {
      if (transport != null) {
        transport.handlePacket(packet);
      }
    });

    // Bind + join multicast
    Future<Void> bindFuture = socket.listen(config.bindPort(), config.bindAddress())
        .compose(v -> {
          if (config.multicastGroup() != null && !config.multicastGroup().isEmpty()) {
            return socket.listenMulticastGroup(config.multicastGroup())
                .onSuccess(v2 -> LOG.info("Joined multicast group " + config.multicastGroup()))
                .onFailure(t -> LOG.warning("Failed to join multicast group: " + t.getMessage()))
                .mapEmpty();
          }
          return Future.succeededFuture();
        })
        .compose(v -> {
          int actualPort = socket.localAddress().port();
          LOG.info("UDP cluster listening on " + socket.localAddress());

          // Création du transport
          transport = new UdpLegacyTransport(socket, peerRegistry, stats, cacheVerticle,
              config.bindPort(), config.maxPacketsPerSec());
          transport.setBindPort(actualPort);
          transport.setDcwPolicySupplier(dcwPolicySupplier);

          // Enregistrement auprès du CacheVerticle
          if (cacheVerticle != null) {
            cacheVerticle.addTransport(transport);
            cacheVerticle.addListener(transport);
          }

          // Démarrage du ping périodique
          startPingTimer();

          return Future.succeededFuture();
        });

    bindFuture.onComplete(ar -> {
      if (ar.succeeded()) {
        startPromise.complete();
      } else {
        startPromise.fail(ar.cause());
      }
    });
  }

  @Override
  public void stop(Promise<Void> stopPromise) {
    if (pingTimerId != null) {
      vertx.cancelTimer(pingTimerId);
      pingTimerId = null;
    }
    if (socket != null) {
      socket.close()
          .onComplete(ar -> stopPromise.complete());
    } else {
      stopPromise.complete();
    }
  }

  // ---- Ping / Keep-alive ----

  private void startPingTimer() {
    pingTimerId = vertx.setPeriodic(config.pingIntervalMs(), id -> {
      if (transport != null) {
        transport.sendPing();
      }
    });
  }

  // ---- Accesseurs ----

  public UdpStats stats() {
    return stats;
  }

  public PeerRegistry peerRegistry() {
    return peerRegistry;
  }

  public UdpLegacyTransport transport() {
    return transport;
  }

  public DatagramSocket socket() {
    return socket;
  }

  // ---- Helpers ----

  private static int[] parsePorts(String portsStr) {
    if (portsStr == null || portsStr.isEmpty()) return new int[]{8099};
    String[] parts = portsStr.split(",");
    int[] result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      result[i] = Integer.parseInt(parts[i].trim());
    }
    return result;
  }
}
