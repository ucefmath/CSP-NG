package com.bowman.cspng.worker;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.cluster.tcp.CspV2ConnectionListener;
import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.cluster.tcp.CspV2Settings;
import com.bowman.cspng.cluster.tcp.CspV2TlsMaterial;
import com.bowman.cspng.connectors.routing.StrictEcmRouter;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.ProtocolAuthResolver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pont CSP inter-noeuds du worker : protocole CSPv2 existant (mTLS, framing,
 * multiplexage — jamais reimplemente, jamais de compat TCP legacy inventee).
 * UDP cache legacy clairement separe : non servi ici (export central seul).
 *
 * <p>Les ECM entrantes sont routees vers le routeur strict local partage
 * (auth synthetique profil-restreinte, epoch = config appliquee).
 */
public final class WorkerCspV2Bridge {

  private static final Logger LOG = Logger.getLogger(WorkerCspV2Bridge.class.getName());

  private final Vertx vertx;
  private final String nodeId;
  private final int listenPort;
  private final String certFile;
  private final String keyFile;
  private final String trustFile;
  private final StrictEcmRouter uplinkRouter;
  private final java.util.concurrent.atomic.AtomicReference<Set<String>> profilesRef;
  private final AtomicLong appliedEpoch;
  private final java.util.concurrent.atomic.AtomicReference<List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings>> peersRef =
      new java.util.concurrent.atomic.AtomicReference<>(List.of());
  private final java.util.function.BiFunction<String, CspV2Payload.EcmRequest, Future<CspV2Manager.CspV2PeerReply>> peerRequester;

  private volatile CspV2Manager manager;

  public WorkerCspV2Bridge(Vertx vertx, String nodeId, int listenPort,
                           String certFile, String keyFile, String trustFile,
                           StrictEcmRouter uplinkRouter, Set<String> profiles,
                           AtomicLong appliedEpoch,
                           List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> peers) {
    this.vertx = vertx;
    this.nodeId = nodeId;
    this.listenPort = listenPort;
    this.certFile = certFile;
    this.keyFile = keyFile;
    this.trustFile = trustFile;
    this.uplinkRouter = uplinkRouter;
    this.profilesRef = new java.util.concurrent.atomic.AtomicReference<>(
        profiles != null ? Set.copyOf(profiles) : Set.of());
    this.appliedEpoch = appliedEpoch != null ? appliedEpoch : new AtomicLong(1L);
    if (peers != null) this.peersRef.set(List.copyOf(peers));
    this.peerRequester = (node, wire) -> {
      CspV2Manager m = manager;
      if (m == null) return Future.failedFuture(new IllegalStateException("cspv2 bridge not running"));
      return m.requestFromPeer(node, wire);
    };
  }

  WorkerCspV2Bridge(java.util.function.BiFunction<String, CspV2Payload.EcmRequest, Future<CspV2Manager.CspV2PeerReply>> peerRequester) {
    this.vertx = null;
    this.nodeId = "test-node";
    this.listenPort = 0;
    this.certFile = null;
    this.keyFile = null;
    this.trustFile = null;
    this.uplinkRouter = null;
    this.profilesRef = new java.util.concurrent.atomic.AtomicReference<>(Set.of());
    this.appliedEpoch = new AtomicLong(1L);
    this.peerRequester = java.util.Objects.requireNonNull(peerRequester, "peerRequester");
  }

  public WorkerCspV2Bridge(Vertx vertx, String nodeId, int listenPort,
                           String certFile, String keyFile, String trustFile,
                           StrictEcmRouter uplinkRouter, Set<String> profiles,
                           AtomicLong appliedEpoch) {
    this(vertx, nodeId, listenPort, certFile, keyFile, trustFile, uplinkRouter,
        profiles, appliedEpoch, List.of());
  }

  public WorkerCspV2Bridge(Vertx vertx, String nodeId, int listenPort,
                           String certFile, String keyFile, String trustFile,
                           StrictEcmRouter uplinkRouter) {
    this(vertx, nodeId, listenPort, certFile, keyFile, trustFile, uplinkRouter,
        Set.of(), new AtomicLong(1L), List.of());
  }

  /**
   * Profils/epoch/peers live (Sonnet FIX #1) : reconstruit le CspV2Manager
   * si la liste des pairs a change ou si le manager n'etait pas demarre.
   */
  public synchronized Future<Void> updatePeersAndProfiles(List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> newPeers,
                                                          Set<String> profiles, long epoch) {
    profilesRef.set(profiles != null ? Set.copyOf(profiles) : Set.of());
    appliedEpoch.set(epoch);
    List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> normPeers = newPeers != null
        ? List.copyOf(newPeers) : List.of();
    List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> oldPeers = peersRef.get();
    peersRef.set(normPeers);
    if (manager == null) {
      if (listenPort > 0) return start().mapEmpty();
      return Future.succeededFuture();
    }
    if (!normPeers.equals(oldPeers)) {
      LOG.info("Worker CSPv2 peers updated (" + oldPeers.size() + " -> " + normPeers.size() + ") — restarting manager");
      return stop().compose(v -> {
        if (listenPort > 0) return start().mapEmpty();
        return Future.succeededFuture();
      });
    }
    return Future.succeededFuture();
  }

  /** Compat anciens appels sans pairs. */
  public void updateProfiles(Set<String> profiles, long epoch) {
    updatePeersAndProfiles(peersRef.get(), profiles, epoch);
  }

  public synchronized Future<CspV2Manager> start() {
    if (manager != null) return Future.succeededFuture(manager);
    Promise<CspV2Manager> p = Promise.promise();
    try {
      byte[] cert = Files.readAllBytes(Path.of(certFile));
      byte[] key = Files.readAllBytes(Path.of(keyFile));
      byte[] trust = Files.readAllBytes(Path.of(trustFile));
      CspV2TlsMaterial material = new CspV2TlsMaterial(cert, key, trust);
      List<com.bowman.cspng.cluster.tcp.CspV2PeerSettings> currentPeers = peersRef.get();
      CspV2Settings settings = new CspV2Settings(true, nodeId, "0.0.0.0", listenPort,
          1048576, 1000, 100, 10, 5000, 100,
          5000L, 5000L, 30000L, 10000L, 5000L, 10000L, 5000L, 15000L,
          100L, 30000L, 10000, 60000L, false,
          "file:" + certFile, "file:" + keyFile, "file:" + trustFile,
          false, currentPeers);
      CspV2ConnectionListener listener = new CspV2ConnectionListener() {
        @Override
         public Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId,
                                                           CspV2Payload.EcmRequest request) {
          return handleEcm(peerNodeId, request, false);
         }

         @Override
         public Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId,
                                                           CspV2Payload.EcmRequest request, boolean negotiated) {
           return handleEcm(peerNodeId, request, negotiated);
         }

         @Override
          public boolean acceptsReply(String peerNodeId, CspV2Payload.EcmRequest req,
                                      CspV2Payload.DcwReply reply, boolean negotiated) {
             return acceptLocalPolicy(req, reply, negotiated);
          }

          @Override
          public boolean acceptsInboundReply(String peerNodeId, CspV2Payload.EcmRequest req,
                                             CspV2Payload.DcwReply reply, boolean negotiated) {
             return acceptLocalPolicy(req, reply, negotiated);
          }

        @Override
        public void onEstablished(String peer) {
          LOG.info("Worker CSPv2 peer established: " + peer);
        }

        @Override
        public void onClosed(String peer, String reason) {
          LOG.info("Worker CSPv2 peer closed: " + peer + " (" + reason + ")");
        }
      };
      vertx.runOnContext(v -> {
        CspV2Manager.start(vertx, settings, material, listener)
            .onSuccess(m -> {
              manager = m;
              LOG.info("Worker CSPv2 listening on port " + listenPort + " with " + currentPeers.size() + " peer(s)");
              p.complete(m);
            })
            .onFailure(e -> {
              LOG.log(Level.SEVERE, "Worker CSPv2 start failed: " + e.getMessage());
              p.fail(e);
            });
      });
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Worker CSPv2 material failed: " + e.getMessage());
      p.fail(e);
    }
    return p.future();
  }

  private static int policyProvider(int caid, byte[] ecm) {
    return com.bowman.cspng.codec.EcmProviderExtractor.extractContext(caid, ecm).policyProvider();
  }

  /**
   * Policy locale, garde historique stricte. La CAP négociée n'autorise le wire
   * sans checksum que si cette policy accepte déjà la CW (règle scopée).
   */
  private boolean acceptLocalPolicy(CspV2Payload.EcmRequest req, CspV2Payload.DcwReply reply, boolean negotiated) {
    if (reply.status() != CspV2Payload.DcwStatus.OK) return true;
    if (uplinkRouter == null) {
      return negotiated ? reply.cw() != null && reply.cw().length == 16 : DcwValidator.checksumDcw(reply.cw());
    }
    boolean policyOk = uplinkRouter.effectiveDcwPolicy().validate(reply.cw(), req.profile(),
        req.caId(), policyProvider(req.caId(), req.ecm()), req.serviceId(), null,
        com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED).isValid();
    if (!policyOk) return false;
    if (!negotiated) return DcwValidator.checksumDcw(reply.cw());
    return reply.cw() != null && reply.cw().length == 16;
  }

  private Future<CspV2Payload.DcwReply> handleEcm(String peer, CspV2Payload.EcmRequest req, boolean negotiated) {
    Promise<CspV2Payload.DcwReply> p = Promise.promise();
    // Compteur inbound : preuve anti-boucle, incremente AVANT toute decision de routage.
    nodeStats.recordInbound(peer);
    try {
      // Sonnet FIX #2 : FAIL-CLOSED strict sur profils vides ou non servis
      Set<String> profs = profilesRef.get();
      if (profs == null || profs.isEmpty() || !profs.contains(req.profile())) {
        LOG.fine("Worker CSPv2 inbound ECM rejected: profile '" + req.profile() + "' not in served profiles " + profs);
        return Future.succeededFuture(notFound(req));
      }
      EcmKey key = new EcmKey(req.commandTag(), req.serviceId(), req.networkId(),
          req.caId(), req.dataHash());
      byte[] rawEcm = req.ecm();
      byte[] amputatedBody = (rawEcm != null && rawEcm.length >= 3)
          ? java.util.Arrays.copyOfRange(rawEcm, 3, rawEcm.length)
          : (rawEcm != null ? rawEcm.clone() : new byte[0]);
      EcmRequest domain = new EcmRequest(key, amputatedBody, req.profile(), "cspv2:" + peer,
          peer, System.currentTimeMillis(), null,
          req.maxWaitMs() > 0 ? req.maxWaitMs() : 5000L);
      var auth = new ProtocolAuthResolver.AuthResult("cspv2:" + peer, "none",
          1L, 1L, Instant.now().plusSeconds(3600), appliedEpoch.get(),
          profs,
          java.util.Map.of(), java.util.Map.of(), Set.of(), null);
       uplinkRouter.routeEcm(domain, auth).onComplete(ar -> {
        try {
          if (ar.failed() || ar.result() == null) {
            p.complete(notFound(req));
          } else {
            boolean policyOk = uplinkRouter.effectiveDcwPolicy().validate(ar.result().cw(), req.profile(),
                req.caId(), policyProvider(req.caId(), req.ecm()), req.serviceId(), ar.result().connectorName(),
                com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED).isValid();
            p.complete(policyOk ? toWire(ar.result(), req, negotiated) : notFound(req));
          }
        } catch (Exception e) {
          p.complete(notFound(req));
        }
      });
    } catch (Exception e) {
      p.complete(notFound(req));
    }
    return p.future();
  }

  private static CspV2Payload.DcwReply toWire(CwReply reply, CspV2Payload.EcmRequest req, boolean negotiated) {
    EcmKey key = reply.key();
    boolean valid = !reply.isEmpty()
        && key.commandTag() == req.commandTag()
        && key.serviceId() == req.serviceId()
        && key.networkId() == req.networkId()
        && key.caId() == req.caId()
        && key.dataHash() == req.dataHash()
        && reply.cw().length == 16
        // Wire CSPv2 v1 (sans capacite negociee) : checksum DVB exige par le codec ;
        // relaxation IGNORE/REJECT_VALID reportee a l'extension negociee (checkpoint suivant).
         && (negotiated || DcwValidator.checksumDcw(reply.cw()));
    if (valid) {
      return new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(),
          req.caId(), req.dataHash(), reply.commandTag(),
          CspV2Payload.DcwStatus.OK, reply.cw(), "");
    }
    return notFound(req);
  }

  private static CspV2Payload.DcwReply notFound(CspV2Payload.EcmRequest req) {
    return new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(), req.networkId(),
        req.caId(), req.dataHash(), 0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
  }

  /** Stats d'echange par node distant (mesures reelles, jamais de zero fictif cumule). */
  public static final class NodeExchangeStats {
    final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> consultOk =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> rttSumMs =
        new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> rttCount =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** ECM recus en provenance d'un node distant (onEcmRequest inbound). */
    final java.util.concurrent.ConcurrentHashMap<String, AtomicLong> inboundEcmReceived =
        new java.util.concurrent.ConcurrentHashMap<>();

    void recordOk(String node, long rttMs) {
      consultOk.computeIfAbsent(node, k -> new AtomicLong()).incrementAndGet();
      rttSumMs.computeIfAbsent(node, k -> new AtomicLong()).addAndGet(Math.max(0, rttMs));
      rttCount.computeIfAbsent(node, k -> new AtomicLong()).incrementAndGet();
    }

    void recordInbound(String fromNode) {
      inboundEcmReceived.computeIfAbsent(fromNode, k -> new AtomicLong()).incrementAndGet();
    }

    public long consultOk(String node) {
      AtomicLong v = consultOk.get(node);
      return v == null ? 0L : v.get();
    }

    /** Nombre d'ECM recus (inbound) en provenance d'un node distant. Preuve anti-boucle. */
    public long inboundEcmReceived(String fromNode) {
      AtomicLong v = inboundEcmReceived.get(fromNode);
      return v == null ? 0L : v.get();
    }

    /** RTT moyen mesure, null si aucun echange reussi. */
    public Long avgRttMs(String node) {
      AtomicLong c = rttCount.get(node);
      AtomicLong s = rttSumMs.get(node);
      if (c == null || s == null || c.get() == 0) return null;
      return s.get() / c.get();
    }
  }

  private final NodeExchangeStats nodeStats = new NodeExchangeStats();

  public NodeExchangeStats nodeStats() {
    return nodeStats;
  }

  public boolean hasEstablishedConnection() {
    CspV2Manager m = manager;
    return m != null && m.snapshot() != null && m.snapshot().establishedConnections() > 0;
  }

  /**
   * Consultation sortante ciblee d'un node nomme (cache kind=node).
   * Echec ferme sans connexion ETABLIE avec ce node exact (jamais de fallback
   * vers un autre node). Reponse validee (champs + checksum) et identifiee
   * {@code node:<peer>} (provenance explicite, jamais confondue au local).
   */
  public Future<CwReply> consultNode(String nodeName, EcmRequest domain, long maxWaitMs) {
    if (nodeName == null || nodeName.isBlank()) {
      return Future.failedFuture(new IllegalStateException("cspv2 bridge not running"));
    }
    EcmKey key = domain.key();
    byte[] fullWireEcm = domain.body();
    if (fullWireEcm != null && (fullWireEcm.length == 0 || (fullWireEcm[0] & 0xFF) != key.commandTag())) {
      fullWireEcm = com.bowman.cspng.codec.cccam.CcCardCodec.reconstructSection(key.commandTag(), domain.body());
    }
    var wire = new CspV2Payload.EcmRequest(
        key.commandTag(), key.serviceId(), key.networkId(), key.caId(), key.dataHash(),
        domain.profileName(), fullWireEcm, maxWaitMs > 0 ? maxWaitMs : 5000L);
    long startMs = System.currentTimeMillis();
    Promise<CwReply> p = Promise.promise();
    peerRequester.apply(nodeName, wire).onComplete(ar -> {
      try {
        if (ar.failed() || ar.result() == null) {
          p.fail(ar.cause() != null ? ar.cause().getMessage() : "node consult failed");
          return;
        }
        var peerReply = ar.result();
        var dw = peerReply.reply();
        if (dw == null) {
          p.fail("invalid node reply");
          return;
        }
        boolean fieldsMatch = dw.commandTag() == key.commandTag()
            && dw.serviceId() == key.serviceId()
            && dw.networkId() == key.networkId()
            && dw.caId() == key.caId()
            && dw.dataHash() == key.dataHash();
        if (!fieldsMatch) {
          p.fail("invalid node reply");
          return;
        }
        if (dw.status() == CspV2Payload.DcwStatus.NOT_FOUND) {
          p.fail("node reply not found");
          return;
        }
        boolean valid = dw.status() == CspV2Payload.DcwStatus.OK
            && dw.cw() != null && dw.cw().length == 16;
        if (valid && uplinkRouter != null) {
          valid = uplinkRouter.effectiveDcwPolicy().validate(dw.cw(), domain.profileName(), key.caId(),
              policyProvider(key.caId(), wire.ecm()), key.serviceId(), "node:" + peerReply.peerNodeId(),
              com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED).isValid();
          if (valid && !peerReply.checksumPolicyNegotiated()) valid = DcwValidator.checksumDcw(dw.cw());
        } else if (valid) {
          valid = DcwValidator.checksumDcw(dw.cw());
        }
        if (!valid) {
          p.fail("invalid node reply");
          return;
        }
        nodeStats.recordOk(peerReply.peerNodeId(), System.currentTimeMillis() - startMs);
        p.complete(new CwReply(key, dw.replyTag(), dw.cw().clone(),
            "node:" + peerReply.peerNodeId(), "cspv2:" + peerReply.peerNodeId(),
            System.currentTimeMillis()));
      } catch (Exception e) {
        p.fail(e.getMessage() != null ? e.getMessage() : "node consult failed");
      }
    });
    return p.future();
  }

  public synchronized Future<Void> stop() {
    CspV2Manager m = manager;
    manager = null;
    if (m != null) {
      return m.stop();
    }
    return Future.succeededFuture();
  }
}
