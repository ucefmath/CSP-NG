package com.bowman.cspng.sessions;

import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.domain.CwReply;
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
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Camd35TcpSession — TCP loopback tests réels (auth, framing, ECM, keepalive, erreurs)")
class Camd35TcpSessionTest {

  private static final String USER = "csuser";
  private static final String PASS = "cspass";
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
  private SecretResolver secretResolver;
  private SessionEcmRouter router;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    authResolver = new ProtocolAuthResolver.Fake();
    long ucrcVal = ((UCRC[0] & 0xFFL) << 24) | ((UCRC[1] & 0xFFL) << 16) | ((UCRC[2] & 0xFFL) << 8) | (UCRC[3] & 0xFFL);
    authResolver.add("cs378x", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/csuser", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), ucrcVal));

    secretResolver = (ref, ctx) -> Future.succeededFuture(PASS);
    router = (req, auth) -> Future.succeededFuture(
        new CwReply(req.key(), req.key().commandTag(), CW_CONST, "testConn", "127.0.0.1", System.currentTimeMillis()));

    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(vertx, registry, authResolver, secretResolver, router);
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

  @Test
  @DisplayName("Connexion, ECM standard (cmd 0) -> CW correcte reçue (cmd 1)")
  void ecmRequestReceivesCwReply() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Camd35FrameCodec.Camd35Frame> replyFrame = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded(), "Client doit se connecter");
      NetSocket sock = ar.result();

      Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
        if (res.status() == Camd35FrameCodec.Status.FRAME) {
          replyFrame.set(res.frame());
          latch.countDown();
        }
      });

      // Forger section ECM DVB complète [0x80, lenhi, lenlo, payload...]
      byte[] payload = new byte[37];
      for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
      byte[] ecm = new byte[3 + payload.length];
      ecm[0] = (byte) 0x80;
      ecm[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
      ecm[2] = (byte) (payload.length & 0xFF);
      System.arraycopy(payload, 0, ecm, 3, payload.length);

      Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_ECM, 0x0001, 0x0100, 0, 0x1234, 0xFFFF, ecm, new byte[0]);

      byte[] wire = Camd35FrameCodec.encode(req, AES_KEY);
      sock.write(Buffer.buffer(wire));
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS), "Réponse CW attendue");
    Camd35FrameCodec.Camd35Frame reply = replyFrame.get();
    assertNotNull(reply);
    assertEquals(Camd35Constants.CMD_CW, reply.cmd());
    assertEquals(0x1234, reply.idx(), "idx écho");
    assertEquals(0x0001, reply.srvid());
    assertEquals(0x0100, reply.caid());
    assertArrayEquals(CW_CONST, reply.data(), "CW reçue doit correspondre à la réponse du routeur");
  }

  @Test
  @DisplayName("Keepalive (cmd 55) -> réponse [55, 1, 0]")
  void keepaliveReceivesReply() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Camd35FrameCodec.Camd35Frame> replyFrame = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
        if (res.status() == Camd35FrameCodec.Status.FRAME) {
          replyFrame.set(res.frame());
          latch.countDown();
        }
      });

      Camd35FrameCodec.Camd35Frame ka = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0, new byte[0], new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(ka, AES_KEY)));
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(Camd35Constants.CMD_KEEPALIVE, replyFrame.get().cmd());
  }

  @Test
  @DisplayName("Cascading ECM (cmd 3) -> réponse NOK 0x44 (choix standard)")
  void cascadingEcmReceivesNok() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Camd35FrameCodec.Camd35Frame> replyFrame = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(AES_KEY);
      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
        if (res.status() == Camd35FrameCodec.Status.FRAME) {
          replyFrame.set(res.frame());
          latch.countDown();
        }
      });

      byte[] ecm = new byte[]{ (byte)0x80, 0x70, 0x05, 1, 2, 3, 4, 5 };
      Camd35FrameCodec.Camd35Frame casc = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_ECM_CASCADING, 0x0001, 0x0100, 0, 0x5555, 0xFFFF,
          ecm, new byte[Camd35Constants.TRAILER_SIZE]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(casc, AES_KEY)));
    });

    assertTrue(latch.await(5, TimeUnit.SECONDS));
    assertEquals(Camd35Constants.CMD_NOK_NOTFOUND, replyFrame.get().cmd());
    assertEquals(0x5555, replyFrame.get().idx());
  }

  @Test
  @DisplayName("ucrc inconnu -> fermeture immédiate")
  void unknownUcrcClosesConnection() throws Exception {
    CountDownLatch closedLatch = new CountDownLatch(1);
    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());
      byte[] badUcrc = new byte[]{ (byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF };
      Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
          badUcrc, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0, new byte[0], new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, AES_KEY)));
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "Socket doit être fermée par le serveur");
  }

  @Test
  @DisplayName("Mauvais mot de passe (CRC mismatch AES) -> fermeture SANS réponse")
  void wrongPasswordClosesWithoutReply() throws Exception {
    CountDownLatch closedLatch = new CountDownLatch(1);
    byte[] wrongKey = Camd35Crypto.aesKey("wrongPassword");

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());
      Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
          UCRC, Camd35Constants.CMD_KEEPALIVE, 0, 0, 0, 0, 0, new byte[0], new byte[0]);
      // Encrypter avec la mauvaise clé
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, wrongKey)));
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "Fermeture sans réponse attendue");
  }
}
