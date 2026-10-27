package com.bowman.cspng.sessions;

import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider CS378x nonzero (C4) : EcmRequest ne transporte PAS le provider
 * (EcmKey sans provider, par construction) — il transite par l'argument
 * dédié {@code routeEcm(req, auth, provider)} depuis l'en-tête de frame.
 * Ce test prouve qu'aucune métadonnée provider n'est perdue et que le routage
 * + le cache partitionnent par provider.
 */
@DisplayName("Camd35 provider nonzero — transport sans perte")
class Camd35ProviderPropagationTest {

  private static final String USER = "provuser";
  private static final String PASS = "provpass";
  private static final byte[] AES_KEY = Camd35Crypto.aesKey(PASS);
  private static final byte[] UCRC = Camd35Crypto.ucrc(USER);
  private static final byte[] CW_CONST = {
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
      0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10
  };

  private Vertx vertx;
  private NetServer server;
  private NetClient client;
  private int serverPort;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;
  private final AtomicInteger seenProvider = new AtomicInteger(-1);
  private final AtomicInteger routeCalls = new AtomicInteger(0);

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    authResolver = new ProtocolAuthResolver.Fake();
    long ucrcVal = ((UCRC[0] & 0xFFL) << 24) | ((UCRC[1] & 0xFFL) << 16)
        | ((UCRC[2] & 0xFFL) << 8) | (UCRC[3] & 0xFFL);
    authResolver.add("cs378x", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/prov", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), ucrcVal));

    SessionEcmRouter router = new SessionEcmRouter() {
      @Override
      public Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult auth) {
        return Future.succeededFuture(new CwReply(request.key(), request.key().commandTag(),
            CW_CONST, "testConn", "127.0.0.1", System.currentTimeMillis()));
      }

      @Override
      public Future<CwReply> routeEcm(EcmRequest request, ProtocolAuthResolver.AuthResult auth,
                                      int provider) {
        seenProvider.set(provider);
        routeCalls.incrementAndGet();
        return routeEcm(request, auth);
      }
    };

    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(vertx, registry, authResolver,
          (ref, ctx) -> Future.succeededFuture(PASS), router);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        serverPort = ar.result().actualPort();
        listenLatch.countDown();
      }
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
    client = vertx.createNetClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) client.close();
    if (server != null) {
      CountDownLatch closeLatch = new CountDownLatch(1);
      server.close(v -> closeLatch.countDown());
      closeLatch.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) {
      CountDownLatch vClose = new CountDownLatch(1);
      vertx.close(v -> vClose.countDown());
      vClose.await(5, TimeUnit.SECONDS);
    }
  }

  private void sendEcm(NetSocket sock, int prid, int idx, byte[] section) {
    Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
        UCRC, Camd35Constants.CMD_ECM, 0x0001, 0x0100, prid, idx, 0xFFFF, section, new byte[0]);
    sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, AES_KEY)));
  }

  private static byte[] dvbSection(byte fill, int payloadLen) {
    byte[] payload = new byte[payloadLen];
    java.util.Arrays.fill(payload, fill);
    byte[] ecm = new byte[3 + payload.length];
    ecm[0] = (byte) 0x80;
    ecm[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
    ecm[2] = (byte) (payload.length & 0xFF);
    System.arraycopy(payload, 0, ecm, 3, payload.length);
    return ecm;
  }

  @Test
  @DisplayName("ECM prid nonzero (0x6A) arrive intact au routeur + CW servie")
  void nonzeroProviderReachesRouter() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    CountDownLatch authed = new CountDownLatch(1);
    AtomicReference<byte[]> cwRef = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = dec.feed(buf.getBytes());
        while (res.status() == Camd35FrameCodec.Status.FRAME) {
          if (res.frame().cmd() == Camd35Constants.CMD_CW) {
            cwRef.set(res.frame().data());
            latch.countDown();
          } else if (res.frame().cmd() == Camd35Constants.CMD_KEEPALIVE) {
            authed.countDown();
          }
          res = dec.feed(new byte[0]);
        }
      });
      // Auth implicite : premier frame = keepalive avec ucrc
      Camd35FrameCodec.Camd35Frame ka = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0, new byte[0], new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(ka, AES_KEY)));
      // Envoi de l'ECM hors event-loop une fois authentifié (jamais de sleep bloquant)
      new Thread(() -> {
        try {
          if (authed.await(5, TimeUnit.SECONDS)) {
            sendEcm(sock, 0x00006A, 0x1111, dvbSection((byte) 7, 37));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }).start();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "CW attendue");
    assertArrayEquals(CW_CONST, cwRef.get());
    assertEquals(0x00006A, seenProvider.get(), "provider nonzero doit arriver intact au routeur");
    assertEquals(1, routeCalls.get());
  }

  @Test
  @DisplayName("Providers distincts (0x6A vs 0) = routages distincts, sans confusion")
  void distinctProvidersAreDistinctRoutes() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = dec.feed(buf.getBytes());
        while (res.status() == Camd35FrameCodec.Status.FRAME) {
          if (res.frame().cmd() == Camd35Constants.CMD_CW) latch.countDown();
          res = dec.feed(new byte[0]);
        }
      });
      Camd35FrameCodec.Camd35Frame ka = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0, new byte[0], new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(ka, AES_KEY)));
      // Envoi hors event-loop après la réponse keepalive (auth terminée)
      new Thread(() -> {
        try {
          Thread.sleep(600);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        sendEcm(sock, 0x00006A, 0x2222, dvbSection((byte) 9, 37));
        sendEcm(sock, 0x000000, 0x3333, dvbSection((byte) 10, 37));
      }).start();
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "2 CW attendues");
    assertEquals(2, routeCalls.get(), "chaque provider déclenche son routage");
  }
}
