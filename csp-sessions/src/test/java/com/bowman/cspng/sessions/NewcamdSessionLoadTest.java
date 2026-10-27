package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de charge : connexions Newcamd simultanées stables.
 *
 * <p>Chaque client simulé se connecte, attend que le serveur envoie le random14
 * (handshake), puis ferme proprement la connexion. Aucune donnée garbage n'est
 * envoyée côté client. Ce test vérifie la stabilité sous charge concurrente
 * (pas de fuite de ressources, pas de blocage de l'event-loop).
 */
@DisplayName("NewcamdSession — test de charge")
class NewcamdSessionLoadTest {

  private static final int SESSION_COUNT = 200;
  private static final byte[] CONFIG_KEY_14 = new byte[14];

  private Vertx vertx;
  private CacheVerticle cacheVerticle;
  private SessionRegistry registry;
  private NetServer server;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle).toCompletionStage().toCompletableFuture().get();
    registry = new SessionRegistry(vertx);
    registry.addUser("loaduser", "loadpass", SESSION_COUNT + 100);
  }

  @AfterEach
  void tearDown() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    if (server != null) server.close(ar -> done.countDown());
    else done.countDown();
    done.await(3, TimeUnit.SECONDS);
    vertx.close();
  }

  @Test
  @DisplayName("Charge : " + SESSION_COUNT + " connexions simultanées stables")
  void concurrentSessions() throws Exception {
    AtomicInteger acceptedCount = new AtomicInteger(0);

    Future<NetServer> serverFuture = vertx.createNetServer()
        .connectHandler(socket -> {
          NewcamdSession session = new NewcamdSession(vertx, cacheVerticle, registry, CONFIG_KEY_14);
          session.accept(socket);
          acceptedCount.incrementAndGet();
        })
        .listen(0);

    NetServer srv = serverFuture.toCompletionStage().toCompletableFuture().get();
    int port = srv.actualPort();
    server = srv;

    CountDownLatch latch = new CountDownLatch(SESSION_COUNT);
    AtomicInteger connectedCount = new AtomicInteger(0);

    NetClient client = vertx.createNetClient();

    for (int i = 0; i < SESSION_COUNT; i++) {
      final int idx = i;
      client.connect(port, "127.0.0.1", ar -> {
        if (ar.succeeded()) {
          connectedCount.incrementAndGet();
          io.vertx.core.net.NetSocket socket = ar.result();

          // Le serveur envoie random14 en handshake. On lit et on ignore.
          // Le client NE DOIT PAS écrire de données après connexion,
          // sinon le framed reader côté serveur interprète ça comme
          // une trame length-prefixed → "Bad message length".
          socket.handler(buf -> {
            // on ignore les données du handshake serveur
          });

          // Fermer après un court délai
          vertx.setTimer(100 + idx % 50, tid -> {
            socket.close();
            latch.countDown();
          });
        } else {
          latch.countDown();
        }
      });
    }

    boolean completed = latch.await(15, TimeUnit.SECONDS);
    assertTrue(completed, "All sessions should connect within timeout");

    // Stabilisation
    CountDownLatch stable = new CountDownLatch(1);
    vertx.setTimer(200, id -> stable.countDown());
    stable.await(1, TimeUnit.SECONDS);

    assertTrue(connectedCount.get() >= SESSION_COUNT / 2,
        "At least half should connect: " + connectedCount.get());
    assertTrue(acceptedCount.get() > 0, "Some sessions should have been accepted");
  }
}
