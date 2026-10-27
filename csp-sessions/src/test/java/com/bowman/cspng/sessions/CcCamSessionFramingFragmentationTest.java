package com.bowman.cspng.sessions;

import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.SessionEcmRouter;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CcCamSession — Tests déterministes de fragmentation de flux et framing CCcam")
class CcCamSessionFramingFragmentationTest {

  private static final String USER = "ccuser";
  private static final String PASS = "ccpass";
  private static final int CARD_ID = 0x0064C0DE;
  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final byte[] SERVER_NODE_ID = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
  private static final byte[] CLIENT_NODE_ID = { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88 };

  private static final byte[] CW_CONST = {
      (byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x67, 0x12, 0x34, 0x56, (byte) 0x9C,
      0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xCE, (byte) 0xDE, (byte) 0xF0, 0x11, (byte) 0xDF
  };

  private Vertx vertx;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;
  private SecretResolver secretResolver;
  private SessionEcmRouter router;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    authResolver = new ProtocolAuthResolver.Fake();
    authResolver.add("cccam", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/ccuser", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), null));

    secretResolver = (ref, ctx) -> Future.succeededFuture(PASS);
    router = (req, auth) -> Future.succeededFuture(
        new CwReply(req.key(), req.key().commandTag(), CW_CONST, "testConn", "127.0.0.1", System.currentTimeMillis()));
  }

  @AfterEach
  void tearDown() throws Exception {
    if (vertx != null) {
      CountDownLatch vClose = new CountDownLatch(1);
      vertx.close(v -> vClose.countDown());
      vClose.await(5, TimeUnit.SECONDS);
    }
  }

  /**
   * Harnais déterministe simulant une socket NetSocket avec contrôle direct des paquets.
   */
  private static class FakeNetSocketHarness {
    Handler<Buffer> dataHandler;
    Handler<Throwable> exceptionHandler;
    Handler<Void> closeHandler;
    Buffer writtenBuffer = Buffer.buffer();
    final AtomicBoolean closed = new AtomicBoolean(false);

    NetSocket createProxy() {
      SocketAddress addr = SocketAddress.inetSocketAddress(12345, "127.0.0.1");
      return (NetSocket) Proxy.newProxyInstance(
          NetSocket.class.getClassLoader(),
          new Class<?>[]{ NetSocket.class },
          (proxy, method, args) -> {
            String name = method.getName();
            if ("remoteAddress".equals(name) || "localAddress".equals(name)) return addr;
            if ("handler".equals(name)) {
              dataHandler = (Handler<Buffer>) args[0];
              return proxy;
            }
            if ("exceptionHandler".equals(name)) {
              exceptionHandler = (Handler<Throwable>) args[0];
              return proxy;
            }
            if ("closeHandler".equals(name)) {
              closeHandler = (Handler<Void>) args[0];
              return proxy;
            }
            if ("write".equals(name) && args != null && args.length == 1 && args[0] instanceof Buffer b) {
              synchronized (this) {
                writtenBuffer.appendBuffer(Buffer.buffer(b.getBytes()));
              }
              return Future.succeededFuture();
            }
            if ("close".equals(name)) {
              closed.set(true);
              if (closeHandler != null) closeHandler.handle(null);
              return Future.succeededFuture();
            }
            return null;
          }
      );
    }

    void feed(byte[] bytes) {
      assertNotNull(dataHandler, "dataHandler doit être initialisé");
      dataHandler.handle(Buffer.buffer(bytes));
    }

    void feed(Buffer buf) {
      assertNotNull(dataHandler, "dataHandler doit être initialisé");
      dataHandler.handle(buf);
    }

    byte[] readBytes(int count) {
      synchronized (this) {
        assertTrue(writtenBuffer.length() >= count,
            "Buffer doit contenir au moins " + count + " octets, mais en a " + writtenBuffer.length());
        byte[] bytes = writtenBuffer.getBytes(0, count);
        byte[] rest = writtenBuffer.getBytes(count, writtenBuffer.length());
        writtenBuffer = Buffer.buffer(rest);
        return bytes;
      }
    }

    byte[] readWrittenBytes() {
      synchronized (this) {
        byte[] bytes = writtenBuffer.getBytes();
        writtenBuffer = Buffer.buffer();
        return bytes;
      }
    }

    int writtenLength() {
      synchronized (this) {
        return writtenBuffer.length();
      }
    }
  }

  /**
   * Client CCcam de test gérant le chiffrement/déchiffrement des échanges.
   */
  private static class TestCcCamClient {
    final CcCrypt cliDec = new CcCrypt();
    final CcCrypt cliEnc = new CcCrypt();
    final FakeNetSocketHarness harness;

    TestCcCamClient(FakeNetSocketHarness harness) {
      this.harness = harness;
    }

    void performHandshake() {
      // 1. Lire seed serveur (16 octets)
      byte[] seed = harness.readBytes(16);
      assertEquals(16, seed.length, "Seed serveur 16 octets attendu");

      // 2. Initialiser crypto
      byte[] seedXor = seed.clone();
      CcCrypt.xor(seedXor);
      byte[] hash = sha1(seedXor);

      cliDec.initCrypt(hash, CcConstants.SHA1_SIZE);
      cliDec.crypt(seedXor, CcConstants.SEED_SIZE, CcConstants.DECRYPT);
      cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
      cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

      // 3. Envoyer hash (20 octets)
      byte[] hashCopy = hash.clone();
      cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
      harness.feed(hashCopy);

      // 4. Envoyer username (20 octets)
      byte[] userBuf = new byte[20];
      byte[] userBytes = USER.getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(userBytes, 0, userBuf, 0, userBytes.length);
      cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
      harness.feed(userBuf);

      // 5. Password advance + magic token (6 octets)
      byte[] pwd = PASS.getBytes(StandardCharsets.US_ASCII);
      cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
      byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
      cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
      harness.feed(magic);

      // 6. Lire ACK 20
      byte[] ack = harness.readBytes(20);
      assertEquals(20, ack.length, "ACK 20 octets attendu");
      cliDec.crypt(ack, 20, CcConstants.DECRYPT);
      assertEquals('C', ack[0]);
      assertEquals('C', ack[1]);
      assertEquals('c', ack[2]);
    }

    byte[] buildEncryptedCliDataFrame() {
      byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
      byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
      cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
      return cliFrame;
    }

    void consumeSrvDataAndAnnouncements() {
      byte[] response = harness.readWrittenBytes();
      assertTrue(response.length >= 4, "Doit avoir reçu au moins l'en-tête ACK CLI_DATA");
      int offset = 0;
      while (offset + 4 <= response.length) {
        byte[] hdr = Arrays.copyOfRange(response, offset, offset + 4);
        cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
        int[] decoded = CcFrameCodec.decodeHeader(hdr);
        int payloadLen = decoded[2];
        offset += 4;
        if (payloadLen > 0) {
          byte[] payload = Arrays.copyOfRange(response, offset, offset + payloadLen);
          cliDec.crypt(payload, payloadLen, CcConstants.DECRYPT);
          offset += payloadLen;
        }
      }
    }

    byte[] buildEncryptedEcmFrame(int seq) {
      byte[] payload = new byte[40];
      Arrays.fill(payload, (byte) seq);
      byte[] section = CcCardCodec.reconstructSection(0x80, payload);
      byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
      byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
      cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
      return frame;
    }

    byte[] buildEncryptedKeepaliveFrame() {
      byte[] frame = CcFrameCodec.encode(CcConstants.MSG_KEEPALIVE, new byte[0]);
      cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
      return frame;
    }

    byte[] readAndDecryptCwReply() {
      byte[] written = harness.readWrittenBytes();
      assertTrue(written.length >= 20, "Réponse CW attendue (4 hdr + 16 payload)");
      byte[] hdr = Arrays.copyOfRange(written, 0, 4);
      cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
      int[] decoded = CcFrameCodec.decodeHeader(hdr);
      assertEquals(CcConstants.MSG_CW_ECM, decoded[1]);
      assertEquals(16, decoded[2]);

      byte[] payload = Arrays.copyOfRange(written, 4, 20);
      cliDec.crypt(payload, 16, CcConstants.DECRYPT);

      byte[] cw = payload.clone();
      CcCwCrypt.cwCrypt(cw, CLIENT_NODE_ID, CARD_ID);

      // Étape sacrificielle de synchronisation miroir
      byte[] sync = cw.clone();
      cliDec.crypt(sync, 16, CcConstants.ENCRYPT);

      return cw;
    }
  }

  @Test
  @DisplayName("RED: Fragmentation Header seul (4 octets) puis Payload complet -> ne doit pas fermer la session et doit répondre la CW")
  void headerAloneThenPayloadDeliversCw() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    // Envoi CLI_DATA normal
    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    // Préparer ECM (ex: 4 octets header + 56 octets payload = 60 octets)
    byte[] ecmFrame = client.buildEncryptedEcmFrame(1);
    assertEquals(60, ecmFrame.length);

    // 1. Envoyer STRICTEMENT le header 4 octets
    byte[] hdr = Arrays.copyOfRange(ecmFrame, 0, 4);
    harness.feed(hdr);

    assertFalse(harness.closed.get(), "La session ne doit pas être fermée après réception du header seul");

    // 2. Envoyer le payload restant (56 octets)
    byte[] payload = Arrays.copyOfRange(ecmFrame, 4, ecmFrame.length);
    harness.feed(payload);

    assertFalse(harness.closed.get(), "La session ne doit pas fermer avec payload invalide");
    byte[] cw = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw, "La CW désembrouillée doit être exacte");
  }

  @Test
  @DisplayName("RED: Fragmentation Payload en plusieurs petits morceaux (4 octets hdr, puis 10, 20, 26 octets)")
  void headerThenFragmentedPayloadDeliversCw() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    byte[] ecmFrame = client.buildEncryptedEcmFrame(2);
    // Header 4 octets
    harness.feed(Arrays.copyOfRange(ecmFrame, 0, 4));
    assertFalse(harness.closed.get());

    // Morceau 1: 10 octets
    harness.feed(Arrays.copyOfRange(ecmFrame, 4, 14));
    assertFalse(harness.closed.get());

    // Morceau 2: 20 octets
    harness.feed(Arrays.copyOfRange(ecmFrame, 14, 34));
    assertFalse(harness.closed.get());

    // Morceau 3: restant (26 octets)
    harness.feed(Arrays.copyOfRange(ecmFrame, 34, ecmFrame.length));
    assertFalse(harness.closed.get());

    byte[] cw = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw);
  }

  @Test
  @DisplayName("RED: Fragmentation au sein de l'en-tête (1 octet, puis 3 octets, puis payload)")
  void splitWithinHeaderDeliversCw() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    byte[] ecmFrame = client.buildEncryptedEcmFrame(3);

    // 1 octet de header
    harness.feed(Arrays.copyOfRange(ecmFrame, 0, 1));
    assertFalse(harness.closed.get());

    // 3 octets restants de header
    harness.feed(Arrays.copyOfRange(ecmFrame, 1, 4));
    assertFalse(harness.closed.get());

    // Payload complet
    harness.feed(Arrays.copyOfRange(ecmFrame, 4, ecmFrame.length));
    assertFalse(harness.closed.get());

    byte[] cw = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw);
  }

  @Test
  @DisplayName("RED: Trames coalescées — Trame 1 complète + Trame 2 partielle dans le même buffer, puis suite")
  void coalescedCompleteAndPartialFrames() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    byte[] ecm1 = client.buildEncryptedEcmFrame(1);
    byte[] ecm2 = client.buildEncryptedEcmFrame(2);

    // Buffer combiné : ECM 1 entier (60 octets) + Header de ECM 2 (4 octets) + 10 octets de payload de ECM 2 = 74 octets
    byte[] combined = new byte[ecm1.length + 14];
    System.arraycopy(ecm1, 0, combined, 0, ecm1.length);
    System.arraycopy(ecm2, 0, combined, ecm1.length, 14);

    harness.feed(combined);
    assertFalse(harness.closed.get(), "La session doit rester ouverte");

    // ECM 1 doit être traité immédiatement
    byte[] cw1 = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw1, "CW #1 reçue");

    // Envoyer le reste de ECM 2 (46 octets restants)
    byte[] restEcm2 = Arrays.copyOfRange(ecm2, 14, ecm2.length);
    harness.feed(restEcm2);
    assertFalse(harness.closed.get(), "La session ne doit pas fermer sur le reste de ECM 2");

    byte[] cw2 = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw2, "CW #2 reçue");
  }

  @Test
  @DisplayName("RED: STATE_NEED_CLI_DATA avec CLI_DATA fragmenté (Header 4 octets d'abord, puis payload)")
  void fragmentedCliDataAuthenticatesProperly() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    byte[] cliFrame = client.buildEncryptedCliDataFrame();

    // 1. Envoyer uniquement le header 4 octets de CLI_DATA
    harness.feed(Arrays.copyOfRange(cliFrame, 0, 4));
    assertFalse(harness.closed.get(), "Session ouverte après header CLI_DATA");

    // 2. Envoyer le payload en deux parties (30 octets puis le reste)
    harness.feed(Arrays.copyOfRange(cliFrame, 4, 34));
    assertFalse(harness.closed.get());

    harness.feed(Arrays.copyOfRange(cliFrame, 34, cliFrame.length));
    assertFalse(harness.closed.get());

    // Vérifier que le handshake s'est achevé avec succès
    client.consumeSrvDataAndAnnouncements();

    // Vérifier qu'un ECM subséquent fonctionne
    byte[] ecm = client.buildEncryptedEcmFrame(1);
    harness.feed(ecm);
    byte[] cw = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw);
  }

  @Test
  @DisplayName("Suite de 5 ECMs fragmentés arbitrairement — prouve la continuité de l'état du cipher")
  void sequenceOfFiveEcmsArbitraryFragmentationPreservesCipherState() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    int[] chunkSizes = { 1, 3, 7, 2, 11, 4, 8, 15, 9 };
    int chunkIdx = 0;

    for (int seq = 1; seq <= 5; seq++) {
      byte[] ecmFrame = client.buildEncryptedEcmFrame(seq);
      int offset = 0;
      while (offset < ecmFrame.length) {
        int sz = chunkSizes[chunkIdx % chunkSizes.length];
        chunkIdx++;
        int end = Math.min(offset + sz, ecmFrame.length);
        harness.feed(Arrays.copyOfRange(ecmFrame, offset, end));
        offset = end;
      }
      assertFalse(harness.closed.get(), "Session vivante au cycle seq=" + seq);
      byte[] cw = client.readAndDecryptCwReply();
      assertArrayEquals(CW_CONST, cw, "CW valide au cycle seq=" + seq);
    }
  }

  @Test
  @DisplayName("Payload 0 octets (ex: MSG_KEEPALIVE) traité immédiatement sans bloquer")
  void zeroLengthPayloadHandledCorrectly() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    // Envoyer MSG_KEEPALIVE (payload 0)
    byte[] keepalive = client.buildEncryptedKeepaliveFrame();
    harness.feed(keepalive);
    assertFalse(harness.closed.get(), "Session ouverte après keepalive");

    // Envoyer ECM immédiatement après
    byte[] ecm = client.buildEncryptedEcmFrame(1);
    harness.feed(ecm);
    byte[] cw = client.readAndDecryptCwReply();
    assertArrayEquals(CW_CONST, cw);
  }

  @Test
  @DisplayName("Limite invalide : payload > MAX_DECLARED_PAYLOAD entraîne la fermeture propre de la session")
  void payloadExceedingMaxClosesSession() {
    FakeNetSocketHarness harness = new FakeNetSocketHarness();
    CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
    session.accept(harness.createProxy());

    TestCcCamClient client = new TestCcCamClient(harness);
    client.performHandshake();

    harness.feed(client.buildEncryptedCliDataFrame());
    client.consumeSrvDataAndAnnouncements();

    // Créer une frame forgeant un payload declared > 1023 (ex: 2000)
    byte[] forgedHdr = new byte[]{ 0x00, (byte) CcConstants.MSG_CW_ECM, (byte) (2000 >> 8), (byte) (2000 & 0xFF) };
    client.cliEnc.crypt(forgedHdr, 4, CcConstants.ENCRYPT);

    harness.feed(forgedHdr);

    assertTrue(harness.closed.get(), "La session doit être fermée suite à une longueur de payload déclarée > 1023");
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
