package com.bowman.cspng.app;

import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.sessions.Camd35TcpSession;
import com.bowman.cspng.sessions.CcCamSession;
import com.bowman.cspng.sessions.SessionRegistry;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.*;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test unitaire d'intégration loopback des sessions (CCcam 2.1.x et CS378x TCP)
 * en pur Java (utilise les codecs csp-codec et un fake resolver d'auth).
 * <p>
 * Ce test valide la mécanique interne de boucle locale. L'interopérabilité RÉELLE
 * avec le binaire exécutable OSCam C compilé fait l'objet de tests dédiés séparés.
 */
@DisplayName("InboundCamsLoopbackSessionTest — Validation loopback TCP des sessions CCcam & CS378x")
class InboundCamsLoopbackSessionTest {

  private static final String USER = "loopbackUser";
  private static final String PASS = "loopbackPass123";
  private static final int CARD_ID = 0x00AABBCC;
  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final byte[] SERVER_NODE = { 1, 2, 3, 4, 5, 6, 7, 8 };
  private static final byte[] CLIENT_NODE = { 9, 8, 7, 6, 5, 4, 3, 2 };

  private static final byte[] CW_EXPECTED = {
      0x11, 0x22, 0x33, 0x66, 0x44, 0x55, 0x66, (byte) 0xFF,
      0x77, (byte) 0x88, (byte) 0x99, (byte) 0x98, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0x31
  };

  private Vertx vertx;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    authResolver = new ProtocolAuthResolver.Fake();

    byte[] ucrc = Camd35Crypto.ucrc(USER);
    long ucrcVal = ((ucrc[0] & 0xFFL) << 24) | ((ucrc[1] & 0xFFL) << 16) | ((ucrc[2] & 0xFFL) << 8) | (ucrc[3] & 0xFFL);

    authResolver.add("cccam", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/loopback", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), null));

    authResolver.add("cs378x", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/loopback", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), ucrcVal));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch latch = new CountDownLatch(1);
      vertx.close(v -> latch.countDown());
      latch.await(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("CS378x TCP — cycle complet loopback : auth par ucrc + résolution mot de passe + ECM -> CW")
  void cs378xCompleteLoopbackCycle() throws Exception {
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(
        new CwReply(req.key(), req.key().commandTag(), CW_EXPECTED, "backendSynthetic", "127.0.0.1", System.currentTimeMillis()));

    CountDownLatch serverBound = new CountDownLatch(1);
    AtomicReference<Integer> portRef = new AtomicReference<>();
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(
          vertx, registry, authResolver, (ref, ctx) -> Future.succeededFuture(PASS), router);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        portRef.set(ar.result().actualPort());
        serverBound.countDown();
      }
    });
    assertTrue(serverBound.await(5, TimeUnit.SECONDS));

    int port = portRef.get();
    byte[] ucrc = Camd35Crypto.ucrc(USER);
    byte[] key = Camd35Crypto.aesKey(PASS);

    NetClient client = vertx.createNetClient();
    CountDownLatch replyLatch = new CountDownLatch(1);
    AtomicReference<byte[]> cwReceived = new AtomicReference<>();

    client.connect(port, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(key);

      sock.handler(buf -> {
        Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
        if (res.status() == Camd35FrameCodec.Status.FRAME) {
          if (res.frame().cmd() == Camd35Constants.CMD_CW) {
            cwReceived.set(res.frame().data());
            replyLatch.countDown();
          }
        }
      });

      byte[] payload = new byte[37];
      for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i + 1);
      byte[] section = new byte[3 + payload.length];
      section[0] = (byte) 0x80;
      section[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
      section[2] = (byte) (payload.length & 0xFF);
      System.arraycopy(payload, 0, section, 3, payload.length);

      Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
          ucrc, Camd35Constants.CMD_ECM, SRVID, CAID, 0, 0x1122, 0xFFFF, section, new byte[0]);
      sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, key)));
    });

    assertTrue(replyLatch.await(5, TimeUnit.SECONDS), "CW doit être retournée par le serveur CS378x");
    assertArrayEquals(CW_EXPECTED, cwReceived.get(), "La CW retournée doit correspondre au CW du routeur");

    client.close();
    server.close();
  }

  @Test
  @DisplayName("CCcam TCP — cycle complet loopback : handshake miroir + CLI_DATA + SRV_DATA + 2 ECMs consécutifs")
  void cccamCompleteLoopbackCycle() throws Exception {
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(
        new CwReply(req.key(), req.key().commandTag(), CW_EXPECTED, "backendSynthetic", "127.0.0.1", System.currentTimeMillis()));

    CountDownLatch serverBound = new CountDownLatch(1);
    AtomicReference<Integer> portRef = new AtomicReference<>();
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CcCamSession session = new CcCamSession(
          vertx, registry, authResolver, (ref, ctx) -> Future.succeededFuture(PASS), router, SERVER_NODE);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) {
        portRef.set(ar.result().actualPort());
        serverBound.countDown();
      }
    });
    assertTrue(serverBound.await(5, TimeUnit.SECONDS));

    int port = portRef.get();
    NetClient client = vertx.createNetClient();
    CountDownLatch cwLatch = new CountDownLatch(2);
    AtomicReference<byte[]> cw1 = new AtomicReference<>();
    AtomicReference<byte[]> cw2 = new AtomicReference<>();

    client.connect(port, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();
      int[] step = new int[]{0};

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
          byte[] seed = acc.getBytes(0, 16);
          accHolder[0] = Buffer.buffer(acc.getBytes(16, acc.length()));
          byte[] seedXor = seed.clone();
          CcCrypt.xor(seedXor);
          byte[] hash = sha1(seedXor);

          cliDec.initCrypt(hash, CcConstants.SHA1_SIZE);
          cliDec.crypt(seedXor, CcConstants.SEED_SIZE, CcConstants.DECRYPT);
          cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
          cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

          byte[] hashCopy = hash.clone();
          cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(hashCopy));

          byte[] userBuf = new byte[20];
          System.arraycopy(USER.getBytes(), 0, userBuf, 0, USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = PASS.getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));

          step[0] = 1;
        }

        if (step[0] == 1 && accHolder[0].length() >= 20) {
          byte[] ack = accHolder[0].getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(accHolder[0].getBytes(20, accHolder[0].length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));

          step[0] = 2;
        }

        if (step[0] == 2) {
          while (accHolder[0].length() >= 4) {
            acc = accHolder[0];
            byte[] hdr = acc.getBytes(0, 4);
            cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
            int[] decoded = CcFrameCodec.decodeHeader(hdr);
            int total = 4 + decoded[2];
            if (acc.length() < total) return;

            byte[] payload = acc.getBytes(4, total);
            cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
            accHolder[0] = Buffer.buffer(acc.getBytes(total, acc.length()));

            if (decoded[1] == CcConstants.MSG_SRV_DATA) {
              sendEcm(sock, cliEnc, 1);
            } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
              byte[] scrambled = Arrays.copyOf(payload, 16);
              byte[] clear = scrambled.clone();
              CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);

              byte[] sync = clear.clone();
              cliDec.crypt(sync, 16, CcConstants.ENCRYPT);

              if (cw1.get() == null) {
                cw1.set(clear);
                cwLatch.countDown();
                sendEcm(sock, cliEnc, 2);
              } else {
                cw2.set(clear);
                cwLatch.countDown();
              }
            }
          }
        }
      });
    });

    assertTrue(cwLatch.await(8, TimeUnit.SECONDS), "Les deux CWs doivent être retournées");
    assertArrayEquals(CW_EXPECTED, cw1.get(), "CW #1 correcte");
    assertArrayEquals(CW_EXPECTED, cw2.get(), "CW #2 correcte après synchro crypto");

    client.close();
    server.close();
  }

  private void sendEcm(NetSocket sock, CcCrypt cliEnc, int seq) {
    byte[] payload = new byte[40];
    Arrays.fill(payload, (byte) seq);
    byte[] section = CcCardCodec.reconstructSection(0x80, payload);
    byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
    cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
    sock.write(Buffer.buffer(frame));
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
