package com.bowman.cspng.sessions;

import com.bowman.cspng.codec.cccam.*;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CcCamSession — TCP loopback tests réels (handshake miroir, CW scramble/descramble, multi-ECM, erreurs)")
class CcCamSessionTest {

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
    authResolver.add("cccam", USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/ccuser", 1,
        Set.of("default"), java.util.Map.of(), java.util.Map.of(), Set.of(), null));

    secretResolver = (ref, ctx) -> Future.succeededFuture(PASS);
    router = (req, auth) -> Future.succeededFuture(
        new CwReply(req.key(), req.key().commandTag(), CW_CONST, "testConn", "127.0.0.1", System.currentTimeMillis()));

    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      CcCamSession session = new CcCamSession(vertx, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
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
  @DisplayName("Handshake complet + 2 ECMs successifs -> CW reçues et désembrouillées avec succès")
  void handshakeAndTwoEcmsSuccess() throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    AtomicReference<byte[]> cw1Ref = new AtomicReference<>();
    AtomicReference<byte[]> cw2Ref = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();
      int[] step = new int[]{0}; // 0=need_seed, 1=need_ack, 2=normal

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (step[0] == 0 && acc.length() >= 16) {
          byte[] seed = acc.getBytes(0, 16);
          accHolder[0] = Buffer.buffer(acc.getBytes(16, acc.length()));
          acc = accHolder[0];

          // Client handshake crypto
          byte[] seedXor = seed.clone();
          CcCrypt.xor(seedXor);
          byte[] hash = sha1(seedXor);

          cliDec.initCrypt(hash, CcConstants.SHA1_SIZE);
          cliDec.crypt(seedXor, CcConstants.SEED_SIZE, CcConstants.DECRYPT);
          cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
          cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

          // 1. Envoyer hash (20)
          byte[] hashCopy = hash.clone();
          cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(hashCopy));

          // 2. Envoyer username (20)
          byte[] userBuf = new byte[20];
          System.arraycopy(USER.getBytes(), 0, userBuf, 0, USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          // 3. Password advance + magic token (6)
          byte[] pwd = PASS.getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));

          step[0] = 1;
        }

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));
          acc = accHolder[0];
          assertEquals('C', ack[0]);
          assertEquals('C', ack[1]);
          assertEquals('c', ack[2]);

          // Envoyer CLI_DATA (93 octets de payload -> 97 total)
          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
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
              // SRV_DATA reçu -> envoyer 1er ECM
              sendClientEcm(sock, cliEnc, 1);
            } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
              // Réponse CW reçue !
              byte[] scrambled = Arrays.copyOf(payload, 16);
              byte[] clear = scrambled.clone();
              CcCwCrypt.cwCrypt(clear, CLIENT_NODE_ID, CARD_ID);

              // Additional crypto step côté client avec CW clair
              byte[] sync = clear.clone();
              cliDec.crypt(sync, 16, CcConstants.ENCRYPT);

              if (cw1Ref.get() == null) {
                cw1Ref.set(clear);
                latch.countDown();
                // Envoyer 2ème ECM
                sendClientEcm(sock, cliEnc, 2);
              } else {
                cw2Ref.set(clear);
                latch.countDown();
              }
            }
          }
        }
      });
    });

    assertTrue(latch.await(8, TimeUnit.SECONDS), "Les deux CW doivent être reçues");
    assertArrayEquals(CW_CONST, cw1Ref.get(), "CW #1 correcte");
    assertArrayEquals(CW_CONST, cw2Ref.get(), "CW #2 correcte après synchro");
  }

  private void sendClientEcm(NetSocket sock, CcCrypt cliEnc, int seq) {
    byte[] payload = new byte[40];
    Arrays.fill(payload, (byte) seq);
    byte[] section = CcCardCodec.reconstructSection(0x80, payload);
    byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
    cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
    sock.write(Buffer.buffer(frame));
  }

  @Test
  @DisplayName("Mauvais mot de passe -> serveur ferme la socket")
  void wrongPasswordClosesSocket() throws Exception {
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      Buffer acc = Buffer.buffer();
      CcCrypt cliEnc = new CcCrypt();
      boolean[] sent = new boolean[]{false};

      sock.handler(data -> {
        acc.appendBuffer(data);
        if (!sent[0] && acc.length() >= 16) {
          sent[0] = true;
          byte[] seed = acc.getBytes(0, 16);
          byte[] seedXor = seed.clone();
          CcCrypt.xor(seedXor);
          byte[] hash = sha1(seedXor);

          cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
          cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

          byte[] hashCopy = hash.clone();
          cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(hashCopy));

          byte[] userBuf = new byte[20];
          System.arraycopy(USER.getBytes(), 0, userBuf, 0, USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          // Mauvais password
          byte[] wrongPwd = "wrongPassword".getBytes();
          cliEnc.crypt(wrongPwd, wrongPwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "Fermeture socket attendue");
  }

  @Test
  @DisplayName("Nom d'utilisateur contenant des caractères de contrôle -> rejet immédiat")
  void illegalUsernameClosesSocket() throws Exception {
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      Buffer acc = Buffer.buffer();
      CcCrypt cliEnc = new CcCrypt();
      boolean[] sent = new boolean[]{false};

      sock.handler(data -> {
        acc.appendBuffer(data);
        if (!sent[0] && acc.length() >= 16) {
          sent[0] = true;
          byte[] seed = acc.getBytes(0, 16);
          byte[] seedXor = seed.clone();
          CcCrypt.xor(seedXor);
          byte[] hash = sha1(seedXor);

          cliEnc.initCrypt(seedXor, CcConstants.SEED_SIZE);
          cliEnc.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

          byte[] hashCopy = hash.clone();
          cliEnc.crypt(hashCopy, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(hashCopy));

          // Username avec byte de contrôle (ex: 0x01)
          byte[] userBuf = new byte[20];
          userBuf[0] = 0x01;
          userBuf[1] = 'b';
          userBuf[2] = 'a';
          userBuf[3] = 'd';
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "Fermeture socket attendue sur nom illégal");
  }

  @Test
  @DisplayName("ECM avec section invalide (SCT_LEN corrompu) -> reçoit NOK2")
  void invalidEcmSectionReceivesNok2() throws Exception {
    CountDownLatch nokLatch = new CountDownLatch(1);
    AtomicReference<Integer> nokCmdRef = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
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

        if (step[0] == 1 && acc.length() >= 20) {
          byte[] ack = acc.getBytes(0, 20);
          cliDec.crypt(ack, 20, CcConstants.DECRYPT);
          accHolder[0] = Buffer.buffer(acc.getBytes(20, acc.length()));

          byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
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
              // Envoyer ECM corrompu : section DVB déclare 200 octets alors que le payload n'en a que 10
              byte[] corruptSection = new byte[]{ (byte)0x80, 0x70, (byte)200, 1, 2, 3, 4, 5, 6, 7 };
              byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, corruptSection);
              byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
              cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
              sock.write(Buffer.buffer(frame));
            } else if (decoded[1] == CcConstants.MSG_CW_NOK1 || decoded[1] == CcConstants.MSG_CW_NOK2) {
              nokCmdRef.set(decoded[1]);
              nokLatch.countDown();
            }
          }
        }
      });
    });

    assertTrue(nokLatch.await(5, TimeUnit.SECONDS), "Réponse NOK2 attendue sur section corrompue");
    assertEquals(CcConstants.MSG_CW_NOK2, nokCmdRef.get());
  }

  @Test
  @DisplayName("TCP Loopback : Fragmentation CLI_DATA et ECM avec délais réels -> CW reçues et continuité crypto")
  void fragmentedTcpStreamDeliversCwSuccessfully() throws Exception {
    CountDownLatch cwLatch = new CountDownLatch(2);
    AtomicReference<byte[]> cw1Ref = new AtomicReference<>();
    AtomicReference<byte[]> cw2Ref = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();

    client.connect(serverPort, "127.0.0.1", ar -> {
      if (ar.failed()) {
        errorRef.set(ar.cause());
        cwLatch.countDown();
        cwLatch.countDown();
        return;
      }
      NetSocket sock = ar.result();

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();
      int[] step = new int[]{0};

      boolean[] cliHeaderPhase = new boolean[]{true};
      int[] cliCmd = new int[]{0};
      int[] cliLen = new int[]{0};

      sock.handler(data -> {
        try {
          accHolder[0].appendBuffer(data);

          if (step[0] == 0 && accHolder[0].length() >= 16) {
            byte[] seed = accHolder[0].getBytes(0, 16);
            accHolder[0] = Buffer.buffer(accHolder[0].getBytes(16, accHolder[0].length()));

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
            System.arraycopy(USER.getBytes(StandardCharsets.US_ASCII), 0, userBuf, 0, USER.length());
            cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(userBuf));

            byte[] pwd = PASS.getBytes(StandardCharsets.US_ASCII);
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
            assertEquals('C', ack[0]);
            assertEquals('C', ack[1]);
            assertEquals('c', ack[2]);

            // Envoi CLI_DATA fragmenté avec timer : 4 octets d'abord, puis 50 octets, puis le reste
            byte[] cliData = CcCardCodec.buildCliData(USER, CLIENT_NODE_ID, "2.3.2", "4000");
            byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
            cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);

            sock.write(Buffer.buffer(Arrays.copyOfRange(cliFrame, 0, 4)));
            vertx.setTimer(20, id1 -> {
              try {
                sock.write(Buffer.buffer(Arrays.copyOfRange(cliFrame, 4, 54)));
                vertx.setTimer(20, id2 -> {
                  try {
                    sock.write(Buffer.buffer(Arrays.copyOfRange(cliFrame, 54, cliFrame.length)));
                  } catch (Throwable t) {
                    errorRef.set(t);
                    cwLatch.countDown();
                    cwLatch.countDown();
                  }
                });
              } catch (Throwable t) {
                errorRef.set(t);
                cwLatch.countDown();
                cwLatch.countDown();
              }
            });

            step[0] = 2;
          }

          if (step[0] == 2) {
            while (true) {
              if (cliHeaderPhase[0]) {
                if (accHolder[0].length() < 4) break;
                byte[] hdr = accHolder[0].getBytes(0, 4);
                cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
                accHolder[0] = Buffer.buffer(accHolder[0].getBytes(4, accHolder[0].length()));
                int[] decoded = CcFrameCodec.decodeHeader(hdr);
                cliCmd[0] = decoded[1];
                cliLen[0] = decoded[2];
                if (cliLen[0] == 0) {
                  // Message sans payload (ex: ACK CLI_DATA)
                  if (cliCmd[0] == CcConstants.MSG_CLI_DATA) {
                    // ACK reçu, rien à faire
                  }
                } else {
                  cliHeaderPhase[0] = false;
                }
              } else {
                if (accHolder[0].length() < cliLen[0]) break;
                byte[] payload = accHolder[0].getBytes(0, cliLen[0]);
                cliDec.crypt(payload, cliLen[0], CcConstants.DECRYPT);
                accHolder[0] = Buffer.buffer(accHolder[0].getBytes(cliLen[0], accHolder[0].length()));
                cliHeaderPhase[0] = true;

                if (cliCmd[0] == CcConstants.MSG_SRV_DATA) {
                  // SRV_DATA reçu -> envoyer 1er ECM fragmenté en 3 morceaux temporisés
                  byte[] payload1 = new byte[40];
                  Arrays.fill(payload1, (byte) 1);
                  byte[] section1 = CcCardCodec.reconstructSection(0x80, payload1);
                  byte[] req1 = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section1);
                  byte[] frame1 = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req1);
                  cliEnc.crypt(frame1, frame1.length, CcConstants.ENCRYPT);

                  sock.write(Buffer.buffer(Arrays.copyOfRange(frame1, 0, 4))); // Header seul
                  vertx.setTimer(25, t1 -> {
                    try {
                      sock.write(Buffer.buffer(Arrays.copyOfRange(frame1, 4, 30)));
                      vertx.setTimer(25, t2 -> {
                        try {
                          sock.write(Buffer.buffer(Arrays.copyOfRange(frame1, 30, frame1.length)));
                        } catch (Throwable t) {
                          errorRef.set(t);
                          cwLatch.countDown();
                          cwLatch.countDown();
                        }
                      });
                    } catch (Throwable t) {
                      errorRef.set(t);
                      cwLatch.countDown();
                      cwLatch.countDown();
                    }
                  });
                } else if (cliCmd[0] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                  byte[] scrambled = Arrays.copyOf(payload, 16);
                  byte[] clear = scrambled.clone();
                  CcCwCrypt.cwCrypt(clear, CLIENT_NODE_ID, CARD_ID);

                  // Additional crypto step
                  byte[] sync = clear.clone();
                  cliDec.crypt(sync, 16, CcConstants.ENCRYPT);

                  if (cw1Ref.get() == null) {
                    cw1Ref.set(clear);
                    cwLatch.countDown();
                    // Envoyer 2ème ECM (non fragmenté pour tester la continuité de l'état crypto)
                    sendClientEcm(sock, cliEnc, 2);
                  } else {
                    cw2Ref.set(clear);
                    cwLatch.countDown();
                  }
                }
              }
            }
          }
        } catch (Throwable t) {
          errorRef.set(t);
          cwLatch.countDown();
          cwLatch.countDown();
        }
      });
    });

    assertTrue(cwLatch.await(8, TimeUnit.SECONDS), "Les 2 CWs doivent être reçues après fragmentation TCP");
    if (errorRef.get() != null) {
      fail("Erreur dans callback client TCP: " + errorRef.get().getMessage(), errorRef.get());
    }
    assertArrayEquals(CW_CONST, cw1Ref.get(), "CW #1 correcte après ECM fragmenté");
    assertArrayEquals(CW_CONST, cw2Ref.get(), "CW #2 correcte (continuité cipher)");
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
