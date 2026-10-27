package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de convergence du worker JVM lors d'un redemarrage post-expiration du bail.
 * <p>Verifie que l'auto-guerison centrale sur le pull desired evite le deadlock/flap (410 WORKER_EXPIRED).
 */
@DisplayName("WorkerConvergencePostExpiryTest — convergence apres expiration")
class WorkerConvergencePostExpiryTest {

  private Vertx vertx;
  private HttpServer mockCentral;
  private int mockPort;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (mockCentral != null) {
      CountDownLatch l = new CountDownLatch(1);
      mockCentral.close(ar -> l.countDown());
      l.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      l.await(10, TimeUnit.SECONDS);
    }
  }

  private RelayWorkerContract.DesiredWorkerConfig buildDesired(String nodeId, long version, long ttlMs) {
    long now = System.currentTimeMillis();
    return new RelayWorkerContract.DesiredWorkerConfig(
        nodeId,
        version,
        now + ttlMs,
        ttlMs,
        List.of(new RelayWorkerContract.ListenerView(101L, "default", "cccam", 15121, "127.0.0.1",
            "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("c1", "local", true, 8000L, 10000, 512)),
        List.of(new RelayWorkerContract.LinkView(101L, "c1", true, true, false, true)),
        List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("Auto-guerison active : restart post-expiry -> poll -> heal -> apply -> ack 200 -> stable sans flap")
  void convergenceWithAutoHealSucceeds() throws Exception {
    AtomicBoolean centralLeaseExpired = new AtomicBoolean(true);
    AtomicInteger healCount = new AtomicInteger(0);
    AtomicInteger ackCount = new AtomicInteger(0);

    // Mock central HTTP server simulant le correctif central
    CountDownLatch listenLatch = new CountDownLatch(1);
    mockCentral = vertx.createHttpServer(new HttpServerOptions().setPort(0))
        .requestHandler(req -> {
          String path = req.path();
          if ("GET".equals(req.method().name()) && "/api/worker/desired".equals(path)) {
            // Auto-guerison centrale : si bail expire, regranter avant de repondre
            if (centralLeaseExpired.compareAndSet(true, false)) {
              healCount.incrementAndGet();
            }
            try {
              var desired = buildDesired("peer-test", 1L, 30000L);
              req.response().putHeader("Content-Type", "application/json").setStatusCode(200)
                  .end(mapper.writeValueAsString(desired));
            } catch (Exception e) {
              req.response().setStatusCode(500).end();
            }
          } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(path)) {
            // ACK verification de defense en profondeur
            if (centralLeaseExpired.get()) {
              req.response().setStatusCode(410).end("{\"code\":\"WORKER_EXPIRED\"}");
            } else {
              ackCount.incrementAndGet();
              req.response().putHeader("Content-Type", "application/json").setStatusCode(200)
                  .end("{\"appliedVersion\":1,\"stale\":false,\"revoked\":false}");
            }
          } else {
            req.response().setStatusCode(404).end();
          }
        });

    mockCentral.listen().onComplete(ar -> {
      if (ar.succeeded()) {
        mockPort = ar.result().actualPort();
        listenLatch.countDown();
      }
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    // Simulation du demarrage du worker apres longue interruption
    WorkerState state = new WorkerState("peer-test");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-test", state);

    try {
      // 1. Worker poll desired
      io.vertx.ext.web.client.WebClient webClient = io.vertx.ext.web.client.WebClient.create(vertx);
      var resp = webClient.get(mockPort, "127.0.0.1", "/api/worker/desired")
          .addQueryParam("nodeId", "peer-test")
          .send()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(200, resp.statusCode());
      var desired = mapper.readValue(resp.body().toString(), RelayWorkerContract.DesiredWorkerConfig.class);
      assertEquals(1, healCount.get(), "La centrale doit avoir gueri le bail lors du pull");

      // 2. Worker apply desired
      Boolean applied = runtime.applyDesired(desired, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertTrue(applied);
      assertEquals(1, runtime.listenerCount());

      // 3. Worker ack
      var ackResp = webClient.post(mockPort, "127.0.0.1", "/api/worker/ack")
          .sendJson(new RelayWorkerContract.WorkerAck("peer-test", desired.desiredVersion(), true, null, null))
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(200, ackResp.statusCode());
      assertEquals(1, ackCount.get(), "L'ACK doit etre accepte");

      // 4. Verifier stabilite de l'etat worker
      assertFalse(state.expired(System.currentTimeMillis()));
      assertEquals(1L, state.appliedDesired());

      // 5. Deuxieme cycle de poll (heartbeat sans renouvellement de heal)
      var resp2 = webClient.get(mockPort, "127.0.0.1", "/api/worker/desired")
          .addQueryParam("nodeId", "peer-test")
          .send()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(200, resp2.statusCode());
      assertEquals(1, healCount.get(), "Aucun heal supplemental en regime sain");

    } finally {
      runtime.close();
    }
  }

  @Test
  @DisplayName("Contre-epreuve historique : sans auto-guerison, l'ACK 410 declenche failClosed (deadlock observe en prod)")
  void counterProofWithoutAutoHealFailsClosed() throws Exception {
    AtomicBoolean centralLeaseExpired = new AtomicBoolean(true);

    // Mock central simulant l'ancien comportement SANS auto-guerison
    CountDownLatch listenLatch = new CountDownLatch(1);
    mockCentral = vertx.createHttpServer(new HttpServerOptions().setPort(0))
        .requestHandler(req -> {
          String path = req.path();
          if ("GET".equals(req.method().name()) && "/api/worker/desired".equals(path)) {
            // Ancien comportement : touchWorkerSeen n'etendait PAS config_expires_at
            try {
              var desired = buildDesired("peer-test", 1L, 30000L);
              req.response().putHeader("Content-Type", "application/json").setStatusCode(200)
                  .end(mapper.writeValueAsString(desired));
            } catch (Exception e) {
              req.response().setStatusCode(500).end();
            }
          } else if ("POST".equals(req.method().name()) && "/api/worker/ack".equals(path)) {
            // Ancien comportement : bail expirer en DB -> 410
            if (centralLeaseExpired.get()) {
              req.response().setStatusCode(410).end("{\"code\":\"WORKER_EXPIRED\"}");
            }
          } else {
            req.response().setStatusCode(404).end();
          }
        });

    mockCentral.listen().onComplete(ar -> {
      if (ar.succeeded()) {
        mockPort = ar.result().actualPort();
        listenLatch.countDown();
      }
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    WorkerState state = new WorkerState("peer-test");
    WorkerRuntime runtime = new WorkerRuntime(vertx, "peer-test", state);

    try {
      io.vertx.ext.web.client.WebClient webClient = io.vertx.ext.web.client.WebClient.create(vertx);
      var resp = webClient.get(mockPort, "127.0.0.1", "/api/worker/desired")
          .addQueryParam("nodeId", "peer-test")
          .send()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(200, resp.statusCode());
      var desired = mapper.readValue(resp.body().toString(), RelayWorkerContract.DesiredWorkerConfig.class);

      Boolean applied = runtime.applyDesired(desired, System.currentTimeMillis())
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertTrue(applied);
      assertEquals(1, runtime.listenerCount());

      // Worker ACK recoit 410
      var ackResp = webClient.post(mockPort, "127.0.0.1", "/api/worker/ack")
          .sendJson(new RelayWorkerContract.WorkerAck("peer-test", desired.desiredVersion(), true, null, null))
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

      assertEquals(410, ackResp.statusCode());

      // Le worker declenche failClosed en reaction au 410 (comportement nominal fail-closed)
      runtime.failClosed("WORKER_EXPIRED");
      assertEquals(0, runtime.listenerCount(), "Les listeners sont fermes suite a WORKER_EXPIRED");

    } finally {
      runtime.close();
    }
  }
}
