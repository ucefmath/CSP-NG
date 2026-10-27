package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration corrigé : serveur envoie random14, client lit et répond.
 */
@DisplayName("NewcamdSession — intégration (handshake corrigé)")
class NewcamdSessionIntegrationTest {

  private Vertx vertx;
  private CacheVerticle cacheVerticle;
  private SessionRegistry registry;
  private NetServer server;

  private static final byte[] CONFIG_KEY_14 = new byte[]{
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E
  };

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    cacheVerticle = new CacheVerticle();
    vertx.deployVerticle(cacheVerticle).toCompletionStage().toCompletableFuture().get();
    registry = new SessionRegistry(vertx);
    registry.addUser("testuser", "testpass", 5);
  }

  @AfterEach
  void tearDown() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    if (server != null) server.close(ar -> done.countDown());
    else done.countDown();
    done.await(2, TimeUnit.SECONDS);
    vertx.close();
  }

  @Test
  @DisplayName("Handshake corrigé : serveur envoie random14 → client lit → login")
  void correctedHandshakeAndLogin() throws Exception {
    AtomicReference<NewcamdSession> sessionRef = new AtomicReference<>();

    CountDownLatch ready = new CountDownLatch(1);
    vertx.createNetServer().connectHandler(socket -> {
      NewcamdSession s = new NewcamdSession(vertx, cacheVerticle, registry, CONFIG_KEY_14);
      sessionRef.set(s);
      s.accept(socket); // Le serveur envoie random14
    }).listen(0, ar -> {
      if (ar.succeeded()) { server = ar.result(); ready.countDown(); }
    });

    assertTrue(ready.await(5, TimeUnit.SECONDS));
    int port = server.actualPort();

    CountDownLatch connected = new CountDownLatch(1);
    AtomicReference<io.vertx.core.net.NetSocket> clientRef = new AtomicReference<>();
    vertx.createNetClient().connect(port, "127.0.0.1", ar -> {
      if (ar.succeeded()) { clientRef.set(ar.result()); connected.countDown(); }
    });
    assertTrue(connected.await(5, TimeUnit.SECONDS));

    io.vertx.core.net.NetSocket client = clientRef.get();
    assertNotNull(client);

    // === Client attend les 14 octets du serveur ===
    CountDownLatch gotRandom = new CountDownLatch(1);
    AtomicReference<byte[]> random14Ref = new AtomicReference<>();
    client.handler(buf -> {
      if (random14Ref.get() == null && buf.length() >= 14) {
        random14Ref.set(buf.getBytes(0, 14));
        gotRandom.countDown();
      }
    });

    assertTrue(gotRandom.await(5, TimeUnit.SECONDS), "Client should receive random14 from server");
    byte[] random14 = random14Ref.get();

    // Client calcule la clé
    byte[] desKey16Client = NewcamdCrypto.desKeySpread(
        NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

    // Client envoie le login
    byte[] loginPayload = "testuser\0testpass\0".getBytes();
    byte[] loginFrame = new byte[13 + loginPayload.length];
    loginFrame[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
    loginFrame[11] = 0;
    loginFrame[12] = (byte) loginPayload.length;
    System.arraycopy(loginPayload, 0, loginFrame, 13, loginPayload.length);

    byte[] encrypted = NewcamdCrypto.desEncrypt(loginFrame, loginFrame.length,
        desKey16Client, NewcamdConstants.CWS_NETMSGSIZE);
    assertNotNull(encrypted);

    Buffer out = Buffer.buffer(2 + encrypted.length);
    out.appendByte((byte) (encrypted.length >> 8));
    out.appendByte((byte) (encrypted.length & 0xFF));
    out.appendBytes(encrypted);
    client.write(out);

    // Attendre le traitement du login
    CountDownLatch done = new CountDownLatch(1);
    vertx.setTimer(300, id -> done.countDown());
    assertTrue(done.await(2, TimeUnit.SECONDS));

    NewcamdSession session = sessionRef.get();
    assertNotNull(session, "Session should exist");
    assertTrue(session.isAuthenticated(),
        "Session should be authenticated. alive=" + session.isAlive()
            + " user=" + session.getUsername());
    assertEquals("testuser", session.getUsername());
    assertTrue(session.isAlive(), "Session should still be alive");

    // Le client doit aussi switcher sa clé (xorUserPass)
    byte[] newKey14 = NewcamdCrypto.xorUserPass(CONFIG_KEY_14, "testpass");
    byte[] newKeyClient = NewcamdCrypto.desKeySpread(newKey14);

    // Round-trip de vérification : encrypt avec la nouvelle clé, le serveur doit pouvoir déchiffrer
    byte[] testMsg = "ECM test".getBytes();
    byte[] testFrame = new byte[13 + testMsg.length];
    testFrame[10] = (byte) 0x80;
    testFrame[11] = 0;
    testFrame[12] = (byte) testMsg.length;
    System.arraycopy(testMsg, 0, testFrame, 13, testMsg.length);

    byte[] enc2 = NewcamdCrypto.desEncrypt(testFrame, testFrame.length, newKeyClient,
        NewcamdConstants.CWS_NETMSGSIZE);
    assertNotNull(enc2, "Encryption with new key should succeed");

    // Le serveur devrait pouvoir déchiffrer avec la nouvelle clé (desKey16 post-login)
    // Note: on ne vérifie pas ici que le serveur reçoit, juste le round-trip crypto
    byte[] dec2 = NewcamdCrypto.desDecrypt(enc2, enc2.length, newKeyClient);
    assertNotNull(dec2, "Decryption with new key should succeed");

    client.close();
  }
}
