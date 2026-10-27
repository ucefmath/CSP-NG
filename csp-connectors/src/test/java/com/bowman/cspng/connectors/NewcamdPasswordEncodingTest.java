package com.bowman.cspng.connectors;

import com.bowman.cspng.codec.newcamd.Md5Crypt;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests pour l'encodage du mot de passe Newcamd (plaintext vs md5crypt)
 * et dérivation de la clé de session post-login.
 */
@DisplayName("NewcamdConnector — Password Encoding & Session Key Derivation")
class NewcamdPasswordEncodingTest {

  private Vertx vertx;
  private static final String USER = "testuser";
  private static final String PASS = "secret123";

  private static final byte[] CONFIG_KEY14 = {
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E
  };
  private static final byte[] RANDOM14 = {
      0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
      0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D
  };

  private static final byte[] LOGIN_KEY16;

  static {
    byte[] xored = NewcamdCrypto.xorKey(CONFIG_KEY14, RANDOM14);
    LOGIN_KEY16 = NewcamdCrypto.desKeySpread(xored);
  }

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void tearDown() {
    if (vertx != null) vertx.close();
  }

  @Test
  @Timeout(10)
  @DisplayName("Serveur exige md5crypt : login OK et déchiffrement session OK (clé dérivée du hash)")
  void md5CryptServerSuccessWithMd5CryptConnectorPostLogin() throws Exception {
    String expectedHash = Md5Crypt.md5Crypt(PASS, Md5Crypt.DEFAULT_SALT);
    MockAuthServer server = new MockAuthServer(vertx, expectedHash);
    server.start();

    NewcamdConnector connector = new NewcamdConnector(vertx, null, "md5conn",
        "127.0.0.1", server.port(), CONFIG_KEY14, USER, PASS, "default");
    connector.setNewcamdPasswordEncoding("md5crypt");
    connector.setCardDiscovery(true);

    try {
      connector.start();
      AbstractConnector.LoginState state = connector.loginFuture()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.OK, state);
      assertEquals(expectedHash, server.receivedPassword.get());

      // Vérifier que la trame post-login (MSG_CARD_DATA_REQ) est bien déchiffrée avec la clé de session
      assertTrue(server.postLoginLatch.await(5, TimeUnit.SECONDS),
          "Le serveur doit recevoir et déchiffrer la trame post-login");
      assertEquals(NewcamdConstants.MSG_CARD_DATA_REQ, server.receivedPostLoginCmd.get(),
          "La commande post-login doit être MSG_CARD_DATA_REQ déchiffrée avec succès");
      assertFalse(server.decryptionFailed.get(), "Aucune erreur de déchiffrement post-login");
    } finally {
      connector.stop();
      server.stop();
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("Serveur exige md5crypt : échec au login si encoding=plaintext (défaut)")
  void md5CryptServerFailsWithPlaintextConnector() throws Exception {
    String expectedHash = Md5Crypt.md5Crypt(PASS, Md5Crypt.DEFAULT_SALT);
    MockAuthServer server = new MockAuthServer(vertx, expectedHash);
    server.start();

    NewcamdConnector connector = new NewcamdConnector(vertx, null, "plainconn",
        "127.0.0.1", server.port(), CONFIG_KEY14, USER, PASS, "default");
    // default encoding is plaintext

    try {
      connector.start();
      AbstractConnector.LoginState state = connector.loginFuture()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.FAILED, state);
      assertEquals(PASS, server.receivedPassword.get());
    } finally {
      connector.stop();
      server.stop();
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("Serveur classique plaintext : login OK et déchiffrement session OK (clé dérivée du clair)")
  void plaintextServerSuccessWithPlaintextConnectorPostLogin() throws Exception {
    MockAuthServer server = new MockAuthServer(vertx, PASS);
    server.start();

    NewcamdConnector connector = new NewcamdConnector(vertx, null, "plainconn",
        "127.0.0.1", server.port(), CONFIG_KEY14, USER, PASS, "default");
    assertEquals("plaintext", connector.getNewcamdPasswordEncoding());
    connector.setCardDiscovery(true);

    try {
      connector.start();
      AbstractConnector.LoginState state = connector.loginFuture()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.OK, state);
      assertEquals(PASS, server.receivedPassword.get());

      // Vérifier que la trame post-login (MSG_CARD_DATA_REQ) est bien déchiffrée avec la clé de session (clair)
      assertTrue(server.postLoginLatch.await(5, TimeUnit.SECONDS),
          "Le serveur doit recevoir et déchiffrer la trame post-login");
      assertEquals(NewcamdConstants.MSG_CARD_DATA_REQ, server.receivedPostLoginCmd.get(),
          "La commande post-login doit être MSG_CARD_DATA_REQ déchiffrée avec succès");
      assertFalse(server.decryptionFailed.get(), "Aucune erreur de déchiffrement post-login");
    } finally {
      connector.stop();
      server.stop();
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("Serveur classique plaintext : échec si encoding=md5crypt")
  void plaintextServerFailsWithMd5CryptConnector() throws Exception {
    MockAuthServer server = new MockAuthServer(vertx, PASS);
    server.start();

    NewcamdConnector connector = new NewcamdConnector(vertx, null, "md5conn",
        "127.0.0.1", server.port(), CONFIG_KEY14, USER, PASS, "default");
    connector.setNewcamdPasswordEncoding("md5crypt");

    try {
      connector.start();
      AbstractConnector.LoginState state = connector.loginFuture()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.FAILED, state);
      assertEquals(Md5Crypt.md5Crypt(PASS, Md5Crypt.DEFAULT_SALT), server.receivedPassword.get());
    } finally {
      connector.stop();
      server.stop();
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("Serveur avec mauvaise clé de session : le déchiffrement post-login échoue")
  void postLoginDecryptionFailsIfServerUsesWrongSessionKey() throws Exception {
    String expectedHash = Md5Crypt.md5Crypt(PASS, Md5Crypt.DEFAULT_SALT);
    // Serveur configuré pour dériver sa clé de session à partir du clair (mauvaise clé) au lieu du hash effectif
    MockAuthServer server = new MockAuthServer(vertx, expectedHash, PASS /* wrong key seed */);
    server.start();

    NewcamdConnector connector = new NewcamdConnector(vertx, null, "md5conn",
        "127.0.0.1", server.port(), CONFIG_KEY14, USER, PASS, "default");
    connector.setNewcamdPasswordEncoding("md5crypt");
    connector.setCardDiscovery(true);

    try {
      connector.start();
      AbstractConnector.LoginState state = connector.loginFuture()
          .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals(AbstractConnector.LoginState.OK, state);

      // Le client envoie CARD_DATA_REQ chiffré avec la clé dérivée du hash,
      // mais le serveur tente de déchiffrer avec la clé dérivée du clair -> échec de déchiffrement
      assertFalse(server.postLoginLatch.await(1, TimeUnit.SECONDS),
          "Le serveur ne doit pas réussir à déchiffrer avec une mauvaise clé de session");
      assertTrue(server.decryptionFailed.get(), "Le serveur doit détecter l'échec de déchiffrement");
    } finally {
      connector.stop();
      server.stop();
    }
  }

  @Test
  @DisplayName("Setter setNewcamdPasswordEncoding gère null -> fallback plaintext")
  void setterNullFallback() {
    NewcamdConnector connector = new NewcamdConnector(vertx, null, "c",
        "127.0.0.1", 1234, CONFIG_KEY14, USER, PASS, "default");
    assertEquals("plaintext", connector.getNewcamdPasswordEncoding());
    connector.setNewcamdPasswordEncoding("md5crypt");
    assertEquals("md5crypt", connector.getNewcamdPasswordEncoding());
    connector.setNewcamdPasswordEncoding(null);
    assertEquals("plaintext", connector.getNewcamdPasswordEncoding());
  }

  /**
   * Serveur mock Newcamd vérifiant le mot de passe reçu et le déchiffrement de session.
   */
  static class MockAuthServer {
    private final Vertx vertx;
    private final String expectedPassword;
    private final String sessionKeySeed;
    final AtomicReference<String> receivedUser = new AtomicReference<>();
    final AtomicReference<String> receivedPassword = new AtomicReference<>();
    final AtomicInteger receivedPostLoginCmd = new AtomicInteger(-1);
    final AtomicBoolean decryptionFailed = new AtomicBoolean(false);
    final CountDownLatch postLoginLatch = new CountDownLatch(1);
    private NetServer server;
    private int port;

    MockAuthServer(Vertx vertx, String expectedPassword) {
      this(vertx, expectedPassword, expectedPassword);
    }

    MockAuthServer(Vertx vertx, String expectedPassword, String sessionKeySeed) {
      this.vertx = vertx;
      this.expectedPassword = expectedPassword;
      this.sessionKeySeed = sessionKeySeed;
    }

    int port() { return port; }

    void start() throws Exception {
      CountDownLatch ready = new CountDownLatch(1);
      server = vertx.createNetServer();
      server.connectHandler(this::handleConnection);
      server.listen(0, "127.0.0.1", ar -> {
        if (ar.succeeded()) {
          port = server.actualPort();
          ready.countDown();
        }
      });
      assertTrue(ready.await(5, TimeUnit.SECONDS), "MockAuthServer should start");
    }

    void stop() {
      if (server != null) server.close();
    }

    private void handleConnection(NetSocket sock) {
      sock.write(Buffer.buffer(RANDOM14));
      FramedHandler handler = new FramedHandler(sock);
      sock.handler(handler::handle);
    }

    private class FramedHandler {
      private final NetSocket sock;
      private Buffer acc = Buffer.buffer();
      private boolean readingLength = true;
      private int msgLen;
      private int phase = 0; // 0 = login, 1 = session
      private byte[] sessionKey16;

      FramedHandler(NetSocket sock) { this.sock = sock; }

      void handle(Buffer data) {
        acc.appendBuffer(data);
        while (true) {
          if (readingLength) {
            if (acc.length() < 2) return;
            msgLen = ((acc.getByte(0) & 0xFF) << 8) | (acc.getByte(1) & 0xFF);
            if (msgLen <= 0 || msgLen > NewcamdConstants.CWS_NETMSGSIZE + 16) {
              acc = Buffer.buffer(); readingLength = true; return;
            }
            readingLength = false;
          }
          if (!readingLength) {
            if (acc.length() < 2 + msgLen) return;
            byte[] encrypted = acc.getBytes(2, 2 + msgLen);
            acc = Buffer.buffer(acc.getBytes(2 + msgLen, acc.length()));
            readingLength = true;

            if (phase == 0) {
              byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, LOGIN_KEY16);
              if (decrypted == null || decrypted.length < 13) return;

              // Extract login payload: username\0password\0
              int payloadLen = decrypted.length - 13;
              byte[] payload = new byte[payloadLen];
              System.arraycopy(decrypted, 13, payload, 0, payloadLen);

              int firstNull = -1;
              for (int i = 0; i < payload.length; i++) {
                if (payload[i] == 0) { firstNull = i; break; }
              }
              if (firstNull >= 0) {
                String u = new String(payload, 0, firstNull, StandardCharsets.UTF_8);
                receivedUser.set(u);
                int secondNull = -1;
                for (int i = firstNull + 1; i < payload.length; i++) {
                  if (payload[i] == 0) { secondNull = i; break; }
                }
                int end = secondNull >= 0 ? secondNull : payload.length;
                String p = new String(payload, firstNull + 1, end - (firstNull + 1), StandardCharsets.UTF_8);
                receivedPassword.set(p);

                if (expectedPassword.equals(p)) {
                  // Dériver la clé de session côté serveur
                  byte[] newKey14 = NewcamdCrypto.xorUserPass(CONFIG_KEY14, sessionKeySeed);
                  sessionKey16 = NewcamdCrypto.desKeySpread(newKey14);
                  phase = 1;
                  sendAck(sock);
                } else {
                  sendNak(sock);
                }
              }
            } else {
              // Phase 1 : déchiffrement avec la clé de session
              byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, sessionKey16);
              if (decrypted == null) {
                decryptionFailed.set(true);
                return;
              }
              if (decrypted.length >= 11) {
                int cmd = decrypted[10] & 0xFF;
                receivedPostLoginCmd.set(cmd);
                postLoginLatch.countDown();
              }
            }
          }
        }
      }
    }

    private void sendAck(NetSocket sock) {
      byte[] ackFrame = buildNewcamdFrame(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_ACK);
      byte[] encrypted = NewcamdCrypto.desEncrypt(ackFrame, ackFrame.length, LOGIN_KEY16, NewcamdConstants.CWS_NETMSGSIZE);
      sendFramed(sock, encrypted);
    }

    private void sendNak(NetSocket sock) {
      byte[] nakFrame = buildNewcamdFrame(NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN_NAK);
      byte[] encrypted = NewcamdCrypto.desEncrypt(nakFrame, nakFrame.length, LOGIN_KEY16, NewcamdConstants.CWS_NETMSGSIZE);
      sendFramed(sock, encrypted);
    }

    private byte[] buildNewcamdFrame(int cmdTag) {
      byte[] buf = new byte[13];
      buf[10] = (byte) cmdTag;
      return buf;
    }

    private void sendFramed(NetSocket sock, byte[] payload) {
      Buffer out = Buffer.buffer(2 + payload.length);
      out.appendByte((byte) (payload.length >> 8));
      out.appendByte((byte) (payload.length & 0xFF));
      out.appendBytes(payload);
      sock.write(out);
    }
  }
}
