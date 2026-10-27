package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cluster.tcp.CspV2Identity;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.config.RelayWorkerContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.cert.Certificate;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Canal controle worker HTTPS mTLS sur port dedie (FIX W4).
 *
 * <p>Separe du portail Web/nginx : le portail n'impose jamais de certificat
 * client ; seul ce port exige {@code ClientAuth.REQUIRED} avec la CA cluster.
 * Identite = SAN {@code urn:csp:node:<id>} extrait du certificat (jamais
 * query/header seuls). Worker SANS acces PG : desired/ack via ce canal.
 */
@ApplicationScoped
public class WorkerControlServer {

  private static final Logger LOG = Logger.getLogger(WorkerControlServer.class.getName());

  @Inject
  Vertx vertx;

  @Inject
  ConfigHolder configHolder;

  @Inject
  ConfigRepository configRepository;

  @Inject
  RelayPolicyService relayPolicyService;

  @Inject
  com.bowman.cspng.app.logs.LogBufferManager logBufferManager;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-enabled", defaultValue = "false")
  boolean workerEnabled;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-port", defaultValue = "8444")
  int workerPort;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-tls-cert-file")
  java.util.Optional<String> tlsCertFile;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-tls-key-file")
  java.util.Optional<String> tlsKeyFile;

  @Inject
  @ConfigProperty(name = "csp.relay.worker-tls-trust-file")
  java.util.Optional<String> tlsTrustFile;

  @Inject
  @ConfigProperty(name = "csp.relay.ack-max-body", defaultValue = "524288")
  int ackMaxBody;

  private HttpServer server;
  private final ObjectMapper mapper = new ObjectMapper();

  public void onStart(@Observes @Priority(260) StartupEvent event) {
    if (!workerEnabled) {
      LOG.info("WorkerControlServer disabled (csp.relay.worker-enabled=false)");
      return;
    }
    String cert = tlsCertFile != null ? tlsCertFile.orElse("") : "";
    String key = tlsKeyFile != null ? tlsKeyFile.orElse("") : "";
    String trust = tlsTrustFile != null ? tlsTrustFile.orElse("") : "";
    if (cert.isBlank() || key.isBlank() || trust.isBlank()) {
      throw new IllegalStateException("Worker mTLS requires cert/key/trust files");
    }
    HttpServerOptions opts = new HttpServerOptions()
        .setSsl(true)
        .setClientAuth(ClientAuth.REQUIRED)
        .setIdleTimeout(30)
        .setPemKeyCertOptions(new PemKeyCertOptions()
            .setCertPath(cert).setKeyPath(key))
        .setPemTrustOptions(new PemTrustOptions().addCertPath(trust));
    server = vertx.createHttpServer(opts);
    server.requestHandler(req -> {
      try {
        String path = req.path();
        if ("GET".equals(req.method().name()) && path != null && path.startsWith("/api/worker/desired")) {
          handleDesired(req);
        } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(path)) {
          handleAck(req);
        } else {
          req.response().setStatusCode(404).end("{\"code\":\"NOT_FOUND\"}");
        }
      } catch (Exception e) {
        try {
          req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
        } catch (Exception ignored) {
        }
      }
    });
    try {
      server.listen(workerPort).toCompletionStage().toCompletableFuture()
          .get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("WorkerControlServer listen interrupted", ie);
    } catch (Exception e) {
      throw new IllegalStateException("WorkerControlServer listen failed: " + e.getMessage(), e);
    }
    LOG.info("WorkerControlServer listening mTLS on port " + workerPort);
  }

  public void onStop(@Observes @Priority(10) ShutdownEvent event) {
    if (server != null) {
      try {
        server.close().toCompletionStage().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
      server = null;
    }
  }

  private String sanOf(io.vertx.core.http.HttpServerRequest req) {
    try {
      List<Certificate> certs = req.connection().peerCertificates();
      return CspV2Identity.extractNodeId(certs);
    } catch (Exception e) {
      return null;
    }
  }

  private void handleDesired(io.vertx.core.http.HttpServerRequest req) {
    String san = sanOf(req);
    String q = req.getParam("nodeId");
    if (san == null || q == null || !san.equals(q)) {
      req.response().setStatusCode(403).end("{\"code\":\"WORKER_UNAUTHORIZED\"}");
      return;
    }
    if (!configHolder.isInitialized()) {
      req.response().setStatusCode(503).end("{\"code\":\"NOT_READY\"}");
      return;
    }
    var snap = configHolder.get();
    var node = snap.relayNodeByName().get(q);
    if (node == null || !node.usable()) {
      req.response().setStatusCode(403).end("{\"code\":\"WORKER_REVOKED\"}");
      return;
    }
    long ttl = relayPolicyService != null ? relayPolicyService.effectiveTtlMs()
        : RelayWorkerContract.TTL_DEFAULT_MS;
    String actor = "worker:" + san;
    var usersF = configRepository.loadWorkerUserAclsFull();
    var secretsF = configRepository.loadWorkerProtocolSecrets();
    var catalogF = configRepository.loadWorkerCatalog();
    var healF = configRepository.touchWorkerSeenAndHeal(q, ttl, actor);
    io.vertx.core.Future.all(usersF, secretsF, catalogF, healF).onComplete(ar -> {
      try {
        if (ar.failed()) {
          LOG.log(Level.WARNING, "Worker desired failed", ar.cause());
          try {
            req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
          } catch (Exception ignored) {
          }
          return;
        }
        List<RelayWorkerContract.UserAclView> users =
            usersF.succeeded() && usersF.result() != null ? usersF.result() : List.of();
        List<RelayWorkerContract.UserSecretView> secrets =
            secretsF.succeeded() && secretsF.result() != null ? secretsF.result() : List.of();
        List<RelayWorkerContract.CatalogView> catalog =
            catalogF.succeeded() && catalogF.result() != null ? catalogF.result() : List.of();
        var desired = WorkerDesiredBuilder.build(snap, q, ttl, System.currentTimeMillis(),
            users, secrets, catalog);
        // Jamais de secret en log : seul nodeId/version.
        LOG.fine("Worker desired for node (version=" + desired.desiredVersion() + ")");
        String json = mapper.writeValueAsString(desired);
        req.response().putHeader("Content-Type", "application/json").setStatusCode(200).end(json);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Worker desired failed");
        try {
          req.response().setStatusCode(500).end("{\"code\":\"INTERNAL_ERROR\"}");
        } catch (Exception ignored) {
        }
      }
    });
  }

  public int effectiveAckMaxBody() {
    return Math.max(4096, Math.min(1048576, ackMaxBody > 0 ? ackMaxBody : 524288));
  }

  private void sendErrorAndClose(io.vertx.core.http.HttpServerRequest req, int statusCode, String jsonBody) {
    try {
      req.response()
          .putHeader("Connection", "close")
          .setStatusCode(statusCode)
          .endHandler(v -> {
            try {
              req.connection().close();
            } catch (Exception ignored) {}
          })
          .end(jsonBody);
    } catch (Exception e) {
      try {
        req.connection().close();
      } catch (Exception ignored) {}
    }
  }

  private void handleAck(io.vertx.core.http.HttpServerRequest req) {
    req.exceptionHandler(err -> {
      try {
        req.connection().close();
      } catch (Exception ignored) {}
    });

    String san = sanOf(req);
    if (san == null) {
      sendErrorAndClose(req, 403, "{\"code\":\"WORKER_UNAUTHORIZED\"}");
      return;
    }

    int maxBody = effectiveAckMaxBody();
    String clStr = req.getHeader("Content-Length");
    if (clStr != null && !clStr.isBlank()) {
      try {
        long cl = Long.parseLong(clStr.trim());
        if (cl > maxBody) {
          sendErrorAndClose(req, 413, "{\"code\":\"PAYLOAD_TOO_LARGE\"}");
          return;
        }
      } catch (NumberFormatException ignored) {}
    }

    io.vertx.core.buffer.Buffer body = io.vertx.core.buffer.Buffer.buffer();
    java.util.concurrent.atomic.AtomicBoolean exceeded = new java.util.concurrent.atomic.AtomicBoolean(false);

    req.handler(chunk -> {
      if (exceeded.get()) return;
      if (body.length() + chunk.length() > maxBody) {
        exceeded.set(true);
        req.pause();
        sendErrorAndClose(req, 413, "{\"code\":\"PAYLOAD_TOO_LARGE\"}");
        return;
      }
      body.appendBuffer(chunk);
    });

    req.endHandler(v -> {
      if (exceeded.get()) return;
      try {
        var ack = mapper.readValue(body.toString(), RelayWorkerContract.WorkerAck.class);
        if (!san.equals(ack.nodeId())) {
          req.response().setStatusCode(403).end("{\"code\":\"WORKER_UNAUTHORIZED\"}");
          return;
        }
        if (ack.logs() != null && !ack.logs().isEmpty() && logBufferManager != null) {
          logBufferManager.recordWorkerLogs(san, ack.logs());
        }
        long ttl = relayPolicyService != null ? relayPolicyService.effectiveTtlMs()
            : RelayWorkerContract.TTL_DEFAULT_MS;
        String actor = "worker:" + san;
        // Stats worker->central : JSON borne, jamais de secret (contrat par construction).
        String statsJson = null;
        try {
          if (ack.stats() != null) statsJson = mapper.writeValueAsString(ack.stats());
        } catch (Exception ignored) {
        }
        final String statsPayload = statsJson;
        configRepository.recordWorkerAck(ack.nodeId(), ack.appliedVersion(), actor, ttl, statsPayload)
            .onSuccess(applied -> {
              try {
                String json = mapper.writeValueAsString(new RelayWorkerContract.WorkerAckResult(applied, false, false));
                req.response().putHeader("Content-Type", "application/json").setStatusCode(200).end(json);
              } catch (Exception ignored) {
              }
            })
            .onFailure(err -> {
              try {
                if (err instanceof com.bowman.cspng.config.OptimisticLockException) {
                  req.response().setStatusCode(409).end("{\"code\":\"STALE_ACK\"}");
                } else if (err instanceof com.bowman.cspng.config.WorkerExpiredException) {
                  req.response().setStatusCode(410).end("{\"code\":\"WORKER_EXPIRED\"}");
                } else {
                  req.response().setStatusCode(403).end("{\"code\":\"WORKER_REVOKED\"}");
                }
              } catch (Exception ignored) {}
            });
      } catch (Exception e) {
        try {
          req.response().setStatusCode(400).end("{\"code\":\"INVALID_INPUT\"}");
        } catch (Exception ignored) {}
      }
    });
  }
}
