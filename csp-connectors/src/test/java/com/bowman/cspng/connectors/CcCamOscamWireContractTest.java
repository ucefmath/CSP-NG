package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CONTRAT P0 (wire CCcam ↔ OSCam) — test INDÉPENDANT, pas un round-trip maison.
 *
 * <p>Le mock serveur embarque un port littéral de la validation OSCam :
 * <ul>
 *   <li>{@code module-cccam.c} l.3375+ (branche serveur de MSG_CW_ECM) :
 *       {@code er->ecmlen = l - CCMSG_HEADER_LEN} avec {@code CCMSG_HEADER_LEN = 17}
 *       (4 header frame + 13 header ECM), {@code memcpy(er->ecm, buf + 17, er->ecmlen)}.</li>
 *   <li>{@code oscam-ecm.c} l.3030 : {@code sct_len = SCT_LEN(er->ecm)} ;
 *       {@code sct_len > er->ecmlen || sct_len < 4} → E_INVALID → réponse NOK
 *       (MSG_CW_NOK2 « can't decode »), cf. cc_send_dcw l.4054+.</li>
 *   <li>{@code globals.h} l.662 : {@code SCT_LEN(sct) = 3+((sct[1]&0x0f)<<8)+sct[2]}.</li>
 *   <li>Handshake serveur {@code cc_srv_connect} l.4221+ : seed 12 random + 4 checksums,
 *       mutation in-place des buffers par cc_crypt (l.4270-4273), username 20 case-exact,
 *       avancement password + token "CCcam\0" 6 octets, ACK 20, CLI_DATA → SRV_DATA →
 *       NEW_CARD. Réponse CW : cc_cw_crypt(peerNodeId, cardId) + sync block[ENCRYPT]
 *       avec le CW SCRAMBLÉ (l.4030) — comportement validé expérimentalement
 *       (voir docs/INBOUND-CAMS-PLAN.md §0.1/§0.2 : RED/GREEN prouvés contre OSCam réel).</li>
 * </ul>
 *
 * <p>Hypothèse P0 testée : {@code CcCamConnector.sendEcm} envoie {@code request.body()}
 * tel quel — or l'invariant du domaine est un ECM <b>amputé de ses 3 octets de section
 * DVB</b> ({@code EcmKey} javadoc ; {@code NewcamdSession} customData). Le wire attend la
 * section ENTIÈRE. Le test échoue (RED) tant que le connecteur n'a pas reconstruit
 * {@code [tag][len_hi/lo]+body} ; il passe (GREEN) après le correctif, avec égalité
 * octet-par-octet de la section reçue.
 */
@DisplayName("P0 — contrat wire CCcam/OSCam : sendEcm doit émettre la section DVB complète")
class CcCamOscamWireContractTest {

  private static final String USER = "contuser";
  private static final String PASS = "contpass";
  private static final int SRVID = 0x0001;
  private static final int CAID = 0x0100;
  private static final int CARD_ID = 0x0064C0DE;

  private Vertx vertx;
  private NetServer server;
  private int port;
  private CacheVerticle cacheVerticle;
  private final AtomicReference<OscamLikeServer> mockRef = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

    CountDownLatch listening = new CountDownLatch(1);
    AtomicReference<Integer> portRef = new AtomicReference<>();
    server = vertx.createNetServer();
    // connectHandler AVANT listen (exigence Vert.x) ; le mock est capturé pour
    // l'assertion wire du test.
    server.connectHandler(sock -> {
      OscamLikeServer mock = new OscamLikeServer(sock);
      mockRef.set(mock);
      mock.start();
    });
    server.listen(0, "127.0.0.1", ar -> {
      if (ar.succeeded()) { portRef.set(ar.result().actualPort()); listening.countDown(); }
      else listening.countDown();
    });
    assertTrue(listening.await(10, TimeUnit.SECONDS), "server should listen");
    port = portRef.get();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (server != null) {
      CountDownLatch closed = new CountDownLatch(1);
      server.close(v -> closed.countDown());
      closed.await(5, TimeUnit.SECONDS);
    }
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(60)
  @DisplayName("GREEN (après fix) : OSCam reçoit une section DVB complète et répond la CW")
  void sendEcmEmitsFullDvbSectionOnTheWire() throws Exception {
    byte[] body = new byte[40];
    new SecureRandom().nextBytes(body);

    CcCamConnector connector = new CcCamConnector(vertx, cacheVerticle,
        "wire-contract", "127.0.0.1", port, USER, PASS, "test-profile");
    connector.start().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    try {
      AbstractConnector.LoginState login =
          connector.loginFuture().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.OK, login, "login contre le mock OSCam doit réussir");
      for (int i = 0; i < 50 && !connector.hasCards(); i++) Thread.sleep(100);
      assertTrue(connector.hasCards(), "le serveur doit annoncer la carte (NEW_CARD)");

      // Domaine : body AMPUTÉ (invariant EcmKey) — c'est ce que les sessions fournissent.
      EcmRequest request = new EcmRequest(
          new EcmKey(0x80, SRVID, 0, CAID, 0x4242),
          body, "test-profile", "127.0.0.1", "wire-contract",
          System.currentTimeMillis(), null, 5000);

      CountDownLatch done = new CountDownLatch(1);
      AtomicReference<CwReply> replyRef = new AtomicReference<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      connector.sendEcm(request).onComplete(ar -> {
        if (ar.succeeded()) replyRef.set(ar.result());
        else failure.set(ar.cause());
        done.countDown();
      });
      assertTrue(done.await(15, TimeUnit.SECONDS), "une réponse (CW ou NOK) doit revenir");

      // RED (avant fix) : le serveur OSCam-littéral rejette la section invalide → NOK2
      //   → failure = "decode failed". GREEN : CW déscramblée == CW synthétique.
      assertNull(failure.get(), "le serveur ne doit PAS renvoyer de NOK — cause: "
          + (failure.get() != null ? failure.get().getMessage() : ""));
      CwReply reply = replyRef.get();
      assertNotNull(reply, "une CW doit être retournée");

      // Assertion WIRE (pas seulement "une CW est revenue") : la section DVB reçue par
      // le serveur doit être EXACTEMENT [tag=commandTag][0x70|len_hi][len_lo]+body —
      // la reconstruction déterministe du correctif (CcCardCodec.reconstructSection).
      OscamLikeServer mock = mockRef.get();
      assertNotNull(mock, "le mock doit avoir été instancié");
      byte[] received = mock.lastEcmSection();
      assertNotNull(received, "le serveur doit avoir validé (SCT_LEN) la section ECM");
      byte[] expected = com.bowman.cspng.codec.cccam.CcCardCodec.reconstructSection(0x80, body);
      assertArrayEquals(expected, received,
          "la section DVB sur le wire doit être la reconstruction exacte [tag][0x70|len_hi/lo]+body");
      assertEquals(3 + body.length, received.length, "longueur de section = 3 + body");
      assertTrue(reply.cw().length >= 16, "CW 16 octets");
    } finally {
      connector.stop();
    }
  }

  /**
   * Mock serveur = port LITTÉRAL du comportement OSCam observé (pas de réutilisation du
   * parseur maison pour la validation). N'est PAS un serveur générique : il encode
   * exactement les invariants du contrat P0.
   */
  private final class OscamLikeServer {

    private final NetSocket socket;
    private final CcCrypt encBlock = new CcCrypt(); // serveur → client
    private final CcCrypt decBlock = new CcCrypt(); // client → serveur
    private final byte[] srvNodeId = new byte[8];
    private final byte[] cw = new byte[16];

    private byte[] clientNodeId;  // lu dans CLI_DATA (payload[20..27])
    private byte[] lastEcmSection; // section DVB validée, pour l'assertion wire du test
    private boolean handshakeDone;

    OscamLikeServer(NetSocket socket) {
      this.socket = socket;
      new SecureRandom().nextBytes(srvNodeId);
      new SecureRandom().nextBytes(cw);
    }

    /** Section DVB validée de la dernière requête ECM reçue (null si rejetée). */
    byte[] lastEcmSection() { return lastEcmSection; }

    void start() {
      socket.exceptionHandler(t -> closeQuietly());
      socket.closeHandler(v -> closeQuietly());

      // --- cc_srv_connect l.4253 : seed = 12 random + 4 checksums, envoyé EN CLAIR ---
      byte[] seed = new byte[16];
      new SecureRandom().nextBytes(seed);
      for (int i = 0; i < 4; i++) {
        seed[12 + i] = (byte) ((seed[i] + seed[4 + i] + seed[8 + i]) & 0xFF);
      }
      socket.write(Buffer.buffer(seed));

      // --- l.4263-4273 : cc_xor(data) ; SHA1 ; blocs miroirs AVEC MUTATION IN PLACE ---
      CcCrypt.xor(seed); // module-cccam.c l.109-123 (buf[8+i]=i*buf[i] ; buf[0..5]^="CCcam")
      byte[] hash = sha1(seed);
      encBlock.initCrypt(hash, CcConstants.SHA1_SIZE);
      encBlock.crypt(seed, CcConstants.SEED_SIZE, CcConstants.DECRYPT); // MUTATION de seed
      decBlock.initCrypt(seed, CcConstants.SEED_SIZE);                   // init avec le seed MUTÉ
      decBlock.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT); // hash muté ensuite

      // État handshake : hash(20) → username(20) → token(6)
      socket.handler(new HandshakeHandler());
    }

    private final class HandshakeHandler implements Handler<Buffer> {
      private Buffer buf = Buffer.buffer();
      private int step = 0;

      @Override
      public void handle(Buffer data) {
        buf.appendBuffer(data);
        boolean progress = true;
        while (progress) {
          progress = false;
          switch (step) {
            case 0 -> { // hash client (20 octets chiffrés sans header)
              if (buf.length() >= 20) {
                byte[] h = buf.getBytes(0, 20);
                decBlock.crypt(h, 20, CcConstants.DECRYPT); // l.4280 : consume + discard
                buf = Buffer.buffer(buf.getBytes(20, buf.length()));
                step = 1;
                progress = true;
              }
            }
            case 1 -> { // username (20 octets, case-exact strncmp 20) — l.4298
              if (buf.length() >= 20) {
                byte[] user = buf.getBytes(0, 20);
                decBlock.crypt(user, 20, CcConstants.DECRYPT);
                buf = Buffer.buffer(buf.getBytes(20, buf.length()));
                // l.4306 : caractère non-imprimable → rejet ; l.4357 : strncmp(usr, account->usr, 20)
                for (byte b : user) {
                  if (b > 0 && b < 0x20) { closeQuietly(); return; }
                }
                byte[] expected = new byte[20];
                System.arraycopy(USER.getBytes(), 0, expected, 0, USER.length());
                if (!Arrays.equals(user, expected)) { closeQuietly(); return; }
                step = 2;
                progress = true;
              }
            }
            case 2 -> { // token "CCcam\0" (6 octets) — l.4318-4372
              if (buf.length() >= 6) {
                byte[] token = buf.getBytes(0, 6);
                buf = Buffer.buffer(buf.getBytes(6, buf.length()));
                // l.4361 : restaurer les blocs sauvegardés, avancer avec le PASSWORD
                // (mode ENCRYPT), puis déchiffrer le token ; == "CCcam\0"
                CcCrypt savedEnc = encBlock.copy();
                CcCrypt savedDec = decBlock.copy();
                byte[] pwd = PASS.getBytes();
                decBlock.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
                decBlock.crypt(token, 6, CcConstants.DECRYPT);
                if (token[0] != 'C' || token[1] != 'C' || token[2] != 'c'
                    || token[3] != 'a' || token[4] != 'm' || token[5] != 0) {
                  closeQuietly(); // l.4380 : password invalide → cs_add_violation + close
                  return;
                }
                step = 3;
                progress = true;
              }
            }
            case 3 -> { // ACK 20 = "CCcam\0"+0 chiffré — l.4423-4427
              byte[] ack = new byte[20];
              System.arraycopy(CcConstants.CCCAM_MAGIC, 0, ack, 0, 6);
              encBlock.crypt(ack, 20, CcConstants.ENCRYPT);
              socket.write(Buffer.buffer(ack));
              step = 4;
              progress = true;
            }
            case 4 -> { // CLI_DATA (frame ≥66) — l.4431 : i < 66 → rejet
              if (buf.length() < 4) return;
              // Le header de frame est CHIFFRÉ : déchiffrer 4 octets PUIS décoder
              // (structure du flux cc_crypt : header et payload dans la continuité).
              byte[] hdr = buf.getBytes(0, 4);
              decBlock.crypt(hdr, 4, CcConstants.DECRYPT);
              int[] header = CcFrameCodec.decodeHeader(hdr);
              int total = 4 + header[2];
              if (header[1] != CcConstants.MSG_CLI_DATA) { closeQuietly(); return; }
              if (buf.length() < total) return;
              byte[] payload = buf.getBytes(4, total);
              decBlock.crypt(payload, total - 4, CcConstants.DECRYPT);
              buf = Buffer.buffer(buf.getBytes(total, buf.length()));
              if (payload.length < 66) { closeQuietly(); return; }
              // l.4471 : memcpy(cc->peer_node_id, buf + 24, 8) = payload[20..27]
              clientNodeId = Arrays.copyOfRange(payload, 20, 28);
              // ack CLI_DATA (frame vide) — l.4487
              sendFrame(CcConstants.MSG_CLI_DATA, new byte[0]);
              // SRV_DATA 72 = nodeid(8)+version(32)+build(32) — cc_send_srv_data l.4545+
              byte[] srvData = new byte[CcConstants.MSG_SRV_DATA_SIZE];
              System.arraycopy(srvNodeId, 0, srvData, 0, 8);
              byte[] ver = "2.3.2".getBytes();
              System.arraycopy(ver, 0, srvData, 8, ver.length);
              byte[] build = "4000".getBytes();
              System.arraycopy(build, 0, srvData, 40, build.length);
              sendFrame(CcConstants.MSG_SRV_DATA, srvData);
              // NEW_CARD — payload l.1850+ (id, remote_id, caid, hop, reshare, hexserial, nprov)
              byte[] card = new byte[21];
              card[0] = (byte) (CARD_ID >>> 24);
              card[1] = (byte) (CARD_ID >>> 16);
              card[2] = (byte) (CARD_ID >>> 8);
              card[3] = (byte) CARD_ID;
              card[8] = (byte) (CAID >>> 8);
              card[9] = (byte) CAID;
              card[10] = 1;  // hop
              card[11] = 3;  // reshare
              card[20] = 0;  // nprov = 0 → matches() accepte tout provider
              sendFrame(CcConstants.MSG_NEW_CARD, card);
              handshakeDone = true;
              socket.handler(new StreamHandler(buf));
              progress = true;
            }
            default -> { }
          }
        }
      }
    }

    /** Parsing de flux déchiffré progressif (header 4 octets d'abord, payload ensuite). */
    private final class StreamHandler implements Handler<Buffer> {
      private Buffer acc;

      StreamHandler(Buffer remaining) {
        this.acc = remaining != null ? remaining.copy() : Buffer.buffer();
      }

      @Override
      public void handle(Buffer data) {
        acc.appendBuffer(data);
        while (true) {
          if (acc.length() < 4) return;
          byte[] hdr = acc.getBytes(0, 4);
          decBlock.crypt(hdr, 4, CcConstants.DECRYPT);
          int[] header = CcFrameCodec.decodeHeader(hdr);
          int total = 4 + header[2];
          if (acc.length() < total) return;
          byte[] payload = acc.getBytes(4, total);
          decBlock.crypt(payload, total - 4, CcConstants.DECRYPT);
          acc = Buffer.buffer(acc.getBytes(total, acc.length()));
          handleMessage(header[1], payload);
        }
      }
    }

    /** Validation OSCam LITTÉRALE de la requête ECM (branche serveur MSG_CW_ECM). */
    private void handleMessage(int cmd, byte[] payload) {
      if (cmd != CcConstants.MSG_CW_ECM) return; // keepalive etc. : ignorés

      // module-cccam.c l.3380 : #define CCMSG_HEADER_LEN 17 ; l.3402-3403 :
      //   er->ecmlen = l - 17 ; memcpy(er->ecm, buf + 17, er->ecmlen)
      //   → ici : l = 4 + payload.length ; er->ecm = payload[13..] (17 - 4 header frame)
      int l = 4 + payload.length;
      if (l < 17) { sendNok(); return; }
      int ecmlen = l - 17;
      byte[] erEcm = Arrays.copyOfRange(payload, 13, payload.length);

      // oscam-ecm.c l.3030-3037 : sct_len = SCT_LEN(er->ecm) ;
      //   sct_len > er->ecmlen || sct_len < 4 → E_INVALID (→ NOK)
      int sctLen = 3 + (((erEcm[1] & 0x0F) << 8) | (erEcm[2] & 0xFF));
      if (sctLen > ecmlen || sctLen < 4) { sendNok(); return; }
      ecmlen = sctLen;
      lastEcmSection = Arrays.copyOf(erEcm, ecmlen);

      // "reader" synthétique du contrat : caid/sid/cardId/tag attendus → CW, sinon NOK.
      // header ECM (payload[0..12]) : caid@[0..1], prid@[2..5], card@[6..9], srvid@[10..11]
      int reqCaid = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
      int reqSrvid = ((payload[10] & 0xFF) << 8) | (payload[11] & 0xFF);
      int reqCardId = ((payload[6] & 0xFF) << 24) | ((payload[7] & 0xFF) << 16)
          | ((payload[8] & 0xFF) << 8) | (payload[9] & 0xFF);
      boolean cardOk = reqCardId == CARD_ID;
      boolean tagsOk = (erEcm[0] & 0xFE) == 0x80; // table_id 0x80/0x81
      if (reqCaid != CAID || reqSrvid != SRVID || !cardOk || !tagsOk) { sendNok(); return; }

      // cc_send_dcw l.3989+ : scramble avec peerNodeId (node id du client) + cardId,
      // envoi, puis sync block[ENCRYPT] avec le CW SCRAMBLÉ (l.4030).
      byte[] scrambled = Arrays.copyOf(cw, 16);
      CcCwCrypt.cwCrypt(scrambled, clientNodeId, CARD_ID);
      sendFrame(CcConstants.MSG_CW_ECM, scrambled);
      byte[] sync = scrambled.clone();
      encBlock.crypt(sync, 16, CcConstants.ENCRYPT);
    }

    private void sendNok() {
      // cc_send_dcw l.4054+ : NOK2 sans sync supplémentaire
      sendFrame(CcConstants.MSG_CW_NOK2, new byte[0]);
    }

    private void sendFrame(int cmd, byte[] payload) {
      byte[] frame = CcFrameCodec.encode(cmd, payload);
      encBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
      socket.write(Buffer.buffer(frame));
    }


    private void closeQuietly() {
      try { socket.close(); } catch (Exception ignore) { }
    }
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
  }
}
