package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cccam.CcServerCodec;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import com.bowman.cspng.codec.newcamd.NewcamdCrypto;
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

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuthAudit — Tests d'audit logging unifié pour fail2ban")
class AuthAuditTest {

  private static final String CC_USER = "ccuser";
  private static final String CC_PASS = "ccpass";
  private static final byte[] SERVER_NODE_ID = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
  private static final byte[] CLIENT_NODE_ID = { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88 };

  private static final String CS_USER = "csuser";
  private static final String CS_PASS = "cspass";
  private static final byte[] CS_UCRC = Camd35Crypto.ucrc(CS_USER);

  private static final String NC_USER = "ncuser";
  private static final String NC_PASS = "ncpass";
  private static final byte[] CONFIG_KEY_14 = new byte[]{
      0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E
  };

  private static class TestLogHandler extends Handler {
    final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {}
  }

  private Vertx vertx;
  private NetServer server;
  private NetClient client;
  private int serverPort;
  private SessionRegistry registry;
  private ProtocolAuthResolver.Fake authResolver;
  private SecretResolver secretResolver;
  private TestLogHandler logHandler;
  private Logger auditLogger;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    registry = new SessionRegistry(vertx);
    registry.addUser(NC_USER, NC_PASS, 5);

    authResolver = new ProtocolAuthResolver.Fake();
    authResolver.add("cccam", CC_USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/ccuser", 1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), null));

    long ucrcVal = ((CS_UCRC[0] & 0xFFL) << 24) | ((CS_UCRC[1] & 0xFFL) << 16) | ((CS_UCRC[2] & 0xFFL) << 8) | (CS_UCRC[3] & 0xFFL);
    authResolver.add("cs378x", CS_USER, new ProtocolAuthResolver.Fake.Entry(
        true, Instant.now().plusSeconds(3600), "file:/run/secrets/csuser", 1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), ucrcVal));

    secretResolver = (ref, ctx) -> {
      if (ref != null && ref.contains("ccuser")) return Future.succeededFuture(CC_PASS);
      if (ref != null && ref.contains("csuser")) return Future.succeededFuture(CS_PASS);
      return Future.succeededFuture(null);
    };

    client = vertx.createNetClient();

    logHandler = new TestLogHandler();
    auditLogger = Logger.getLogger(AuthAudit.LOGGER_NAME);
    auditLogger.addHandler(logHandler);
    auditLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (auditLogger != null && logHandler != null) {
      auditLogger.removeHandler(logHandler);
    }
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
  @DisplayName("Sanitization stricte user, IP, protocol, reason (anti-injection CRLF, longueur, printable ASCII, espace et =)")
  void testSanitization() {
    // User sanitization
    assertEquals("?", AuthAudit.sanitizeUser(null));
    assertEquals("?", AuthAudit.sanitizeUser(""));
    assertEquals("admin??INJECTION", AuthAudit.sanitizeUser("admin\r\nINJECTION"));
    assertEquals("user?with?ctrl", AuthAudit.sanitizeUser("user\u0000with\u001fctrl"));
    assertEquals("normal_user-123", AuthAudit.sanitizeUser("normal_user-123"));
    // Remplacement espace et '=' par '?' (anti log-injection failregex)
    assertEquals("user?1.2.3.4?ip?9.9.9.9", AuthAudit.sanitizeUser("user=1.2.3.4 ip=9.9.9.9"));
    assertEquals("user?name", AuthAudit.sanitizeUser("user name"));
    assertEquals("user?test", AuthAudit.sanitizeUser("user=test"));
    String long64 = "a".repeat(64);
    String long100 = "a".repeat(100);
    assertEquals(long64, AuthAudit.sanitizeUser(long100));

    // IP sanitization
    assertEquals("?", AuthAudit.sanitizeIp(null));
    assertEquals("?", AuthAudit.sanitizeIp("   "));
    assertEquals("127.0.0.1", AuthAudit.sanitizeIp("127.0.0.1"));
    assertEquals("127.0.0.1", AuthAudit.sanitizeIp("/127.0.0.1"));
    assertEquals("127.0.0.1", AuthAudit.sanitizeIp("127.0.0.1:54321"));
    assertEquals("127.0.0.1", AuthAudit.sanitizeIp("/127.0.0.1:54321"));
    assertEquals("::1", AuthAudit.sanitizeIp("::1"));
    assertEquals("::1", AuthAudit.sanitizeIp("/::1"));
    assertEquals("::1", AuthAudit.sanitizeIp("[::1]"));
    assertEquals("::1", AuthAudit.sanitizeIp("[::1]:8080"));
    assertEquals("::1", AuthAudit.sanitizeIp("/[::1]:8080"));
    assertEquals("2001:db8::1", AuthAudit.sanitizeIp("2001:db8::1"));
    assertEquals("127.0.0.1?bad", AuthAudit.sanitizeIp("127.0.0.1\nbad"));

    // Protocol / reason
    assertEquals("cccam", AuthAudit.sanitizeProtocol("cccam"));
    assertEquals("?", AuthAudit.sanitizeProtocol(null));
    assertEquals("unknown-user", AuthAudit.sanitizeReason("unknown-user"));
    assertEquals("?", AuthAudit.sanitizeReason(null));
    assertEquals("reason?test", AuthAudit.sanitizeReason("reason=test"));
  }

  @Test
  @DisplayName("PREAUTH_REJECT : rejet per-ip-quota émet un log WARNING conforme")
  void testPreauthPerIpReject() {
    String ip = "192.168.1.50";
    // Marge preauth = 2 * maxPerIp = 2 * 1 = 2
    assertTrue(registry.tryAcquirePreauthIp(ip, 1));
    assertTrue(registry.tryAcquirePreauthIp(ip, 1));
    // 3ème tentative -> rejet per-ip-quota
    assertFalse(registry.tryAcquirePreauthIp(ip, 1));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("PREAUTH_REJECT"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "Un LogRecord PREAUTH_REJECT doit être émis");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("PREAUTH_REJECT ip=192.168.1.50 reason=per-ip-quota", rec.getMessage());
  }

  @Test
  @DisplayName("PREAUTH_REJECT : rejet global-capacity émet un log WARNING conforme")
  void testPreauthGlobalCapacityReject() {
    for (int i = 0; i < SessionRegistry.DEFAULT_MAX_GLOBAL_PREAUTH; i++) {
      assertTrue(registry.tryAcquirePreauthIp("10.0.0." + (i % 250), 1000));
    }
    // Dépassement capacité globale
    assertFalse(registry.tryAcquirePreauthIp("10.0.0.254", 1000));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("reason=global-capacity"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "Un LogRecord PREAUTH_REJECT global-capacity doit être émis");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("PREAUTH_REJECT ip=10.0.0.254 reason=global-capacity", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : échec auth user inconnu -> AUTH_FAIL protocol=cccam user=baduser ip=127.0.0.1 reason=unknown-user")
  void testCccamUnknownUser() throws Exception {
    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

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
          String badUser = "baduser";
          System.arraycopy(badUser.getBytes(), 0, userBuf, 0, badUser.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = "badpass".getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));
          step[0] = 1;
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_FAIL protocol=cccam"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL CCcam attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=baduser ip=127.0.0.1 reason=unknown-user", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : échec password mismatch -> AUTH_FAIL protocol=cccam user=ccuser ip=127.0.0.1 reason=password-mismatch")
  void testCccamPasswordMismatch() throws Exception {
    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

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
          System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          // Mauvais mot de passe passé au cryptage token
          byte[] pwd = "wrongpassword".getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));
          step[0] = 1;
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_FAIL protocol=cccam"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL CCcam password-mismatch attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=ccuser ip=127.0.0.1 reason=password-mismatch", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : auth réussie -> AUTH_OK protocol=cccam user=ccuser ip=127.0.0.1")
  void testCccamAuthOk() throws Exception {
    startCccamServer();
    CountDownLatch authOkLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
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
          System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = CC_PASS.getBytes();
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

          byte[] cliData = CcCardCodec.buildCliData(CC_USER, CLIENT_NODE_ID, "2.3.2", "4000");
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }

        if (step[0] == 2 && accHolder[0].length() >= 4) {
          authOkLatch.countDown();
        }
      });
    });

    assertTrue(authOkLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_OK protocol=cccam"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_OK CCcam attendu");
    assertEquals(Level.INFO, rec.getLevel());
    assertEquals("AUTH_OK protocol=cccam user=ccuser ip=127.0.0.1", rec.getMessage());
  }

  @Test
  @DisplayName("Camd35 / CS378x : ucrc inconnu -> AUTH_FAIL protocol=cs378x user=? ip=127.0.0.1 reason=unknown-ucrc")
  void testCamd35UnknownUcrc() throws Exception {
    startCamd35Server();
    CountDownLatch closedLatch = new CountDownLatch(1);

    byte[] badUcrc = new byte[]{ (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF };

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());
      sock.write(Buffer.buffer(badUcrc));
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_FAIL protocol=cs378x"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL cs378x unknown-ucrc attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cs378x user=? ip=127.0.0.1 reason=unknown-ucrc", rec.getMessage());
  }

  @Test
  @DisplayName("Camd35 / CS378x : auth réussie -> AUTH_OK protocol=cs378x user=csuser ip=127.0.0.1")
  void testCamd35AuthOk() throws Exception {
    startCamd35Server();
    CountDownLatch authOkLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.write(Buffer.buffer(CS_UCRC));
      vertx.setTimer(100, id -> authOkLatch.countDown());
    });

    assertTrue(authOkLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_OK protocol=cs378x"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_OK cs378x attendu");
    assertEquals(Level.INFO, rec.getLevel());
    assertEquals("AUTH_OK protocol=cs378x user=csuser ip=127.0.0.1", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : mot de passe incorrect -> AUTH_FAIL protocol=newcamd user=ncuser ip=127.0.0.1 reason=bad-password")
  void testNewcamdBadPassword() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          // Login message avec mauvais password
          byte[] userBytes = NC_USER.getBytes();
          byte[] passBytes = "wrongpass".getBytes();
          byte[] payload = new byte[userBytes.length + 1 + passBytes.length + 1];
          System.arraycopy(userBytes, 0, payload, 0, userBytes.length);
          payload[userBytes.length] = 0;
          System.arraycopy(passBytes, 0, payload, userBytes.length + 1, passBytes.length);
          payload[payload.length - 1] = 0;

          byte[] msg = new byte[13 + payload.length];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
          msg[11] = (byte) ((payload.length >> 8) & 0xFF);
          msg[12] = (byte) (payload.length & 0xFF);
          System.arraycopy(payload, 0, msg, 13, payload.length);

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_FAIL protocol=newcamd"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL newcamd bad-password attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=ncuser ip=127.0.0.1 reason=bad-password", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : auth réussie -> AUTH_OK protocol=newcamd user=ncuser ip=127.0.0.1")
  void testNewcamdAuthOk() throws Exception {
    startNewcamdServer();
    CountDownLatch authOkLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          // Login message avec bon password
          byte[] userBytes = NC_USER.getBytes();
          byte[] passBytes = NC_PASS.getBytes();
          byte[] payload = new byte[userBytes.length + 1 + passBytes.length + 1];
          System.arraycopy(userBytes, 0, payload, 0, userBytes.length);
          payload[userBytes.length] = 0;
          System.arraycopy(passBytes, 0, payload, userBytes.length + 1, passBytes.length);
          payload[payload.length - 1] = 0;

          byte[] msg = new byte[13 + payload.length];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
          msg[11] = (byte) ((payload.length >> 8) & 0xFF);
          msg[12] = (byte) (payload.length & 0xFF);
          System.arraycopy(payload, 0, msg, 13, payload.length);

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);

          // Changer de handler pour recevoir le login ACK
          sock.handler(ackBuf -> {
            if (ackBuf.length() >= 2) {
              authOkLatch.countDown();
            }
          });
        }
      });
    });

    assertTrue(authOkLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().startsWith("AUTH_OK protocol=newcamd"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_OK newcamd attendu");
    assertEquals(Level.INFO, rec.getLevel());
    assertEquals("AUTH_OK protocol=newcamd user=ncuser ip=127.0.0.1", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : illegal-username -> AUTH_FAIL reason=illegal-username")
  void testCccamIllegalUsername() throws Exception {
    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();

      sock.handler(data -> {
        if (data.length() >= 16) {
          byte[] seed = data.getBytes(0, 16);
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

          // User avec octet non imprimable 0x05
          byte[] userBuf = new byte[20];
          userBuf[0] = 0x05;
          userBuf[1] = 'b';
          userBuf[2] = 'a';
          userBuf[3] = 'd';
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("reason=illegal-username"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL illegal-username attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=? ip=127.0.0.1 reason=illegal-username", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : username-length vide -> AUTH_FAIL reason=username-length")
  void testCccamUsernameLength() throws Exception {
    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();

      sock.handler(data -> {
        if (data.length() >= 16) {
          byte[] seed = data.getBytes(0, 16);
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

          // User vide (0x00 au début)
          byte[] userBuf = new byte[20];
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("reason=username-length"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL username-length attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=? ip=127.0.0.1 reason=username-length", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : preauth-overflow -> AUTH_FAIL reason=preauth-overflow")
  void testCccamPreauthOverflow() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    io.vertx.core.Promise<ProtocolAuthResolver.AuthResult> pendingAuth = io.vertx.core.Promise.promise();
    ProtocolAuthResolver pendingResolver = new ProtocolAuthResolver() {
      @Override
      public Future<AuthResult> authenticate(String protocol, String protocolName) {
        return pendingAuth.future();
      }
      @Override
      public Future<AuthResult> authenticateByUcrc(String protocol, long ucrc) {
        return pendingAuth.future();
      }
      @Override
      public Future<Long> currentGeneration() {
        return Future.succeededFuture(1L);
      }
    };

    server.connectHandler(sock -> {
      CcCamSession session = new CcCamSession(
          vertx, null, registry, pendingResolver, secretResolver, (req, auth) -> Future.succeededFuture(CwReply.empty(req.key())), SERVER_NODE_ID);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (acc.length() >= 16) {
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
          System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = CC_PASS.getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));

          // Envoyer 10000 octets pendant que l'auth est en attente
          sock.write(Buffer.buffer(new byte[10000]));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=cccam") && r.getMessage().contains("reason=preauth-overflow"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord AUTH_FAIL preauth-overflow attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=ccuser ip=127.0.0.1 reason=preauth-overflow", rec.getMessage());
  }

  @Test
  @DisplayName("Camd35 : preauth-overflow -> AUTH_FAIL reason=preauth-overflow")
  void testCamd35PreauthOverflow() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    io.vertx.core.Promise<ProtocolAuthResolver.AuthResult> pendingAuth = io.vertx.core.Promise.promise();
    ProtocolAuthResolver pendingResolver = new ProtocolAuthResolver() {
      @Override
      public Future<AuthResult> authenticate(String protocol, String protocolName) {
        return pendingAuth.future();
      }
      @Override
      public Future<AuthResult> authenticateByUcrc(String protocol, long ucrc) {
        return pendingAuth.future();
      }
      @Override
      public Future<Long> currentGeneration() {
        return Future.succeededFuture(1L);
      }
    };

    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(
          vertx, null, registry, pendingResolver, secretResolver, (req, auth) -> Future.succeededFuture(CwReply.empty(req.key())));
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());
      // 1. Envoyer UCRC valide pour enclencher authInProgress
      sock.write(Buffer.buffer(CS_UCRC));
      // 2. Envoyer un flot de 10000 octets pendant que l'auth est en cours
      sock.write(Buffer.buffer(new byte[10000]));
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=cs378x") && r.getMessage().contains("reason=preauth-overflow"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord cs378x preauth-overflow attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cs378x user=? ip=127.0.0.1 reason=preauth-overflow", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : malformed-login (message tronqué) -> AUTH_FAIL reason=malformed-login")
  void testNewcamdMalformedLogin() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          // Message trop court (< 14 octets décryptés)
          byte[] msg = new byte[11];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=malformed-login"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd malformed-login attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=? ip=127.0.0.1 reason=malformed-login", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : ip-denied -> AUTH_FAIL reason=ip-denied")
  void testCccamIpDenied() throws Exception {
    // Configuration d'un utilisateur avec ipMask refusé
    registry.addTestUser(new com.bowman.cspng.users.UserRecord(
        CC_USER, CC_USER, "hash", null, true, false, false, false, 1, "192.168.1.1", -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, null, null));

    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      Buffer[] accHolder = new Buffer[]{ Buffer.buffer() };
      CcCrypt cliDec = new CcCrypt();
      CcCrypt cliEnc = new CcCrypt();

      sock.handler(data -> {
        accHolder[0].appendBuffer(data);
        Buffer acc = accHolder[0];

        if (acc.length() >= 16) {
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
          System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = CC_PASS.getBytes();
          cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
          byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
          cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(magic));
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=cccam") && r.getMessage().contains("reason=ip-denied"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord cccam ip-denied attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=ccuser ip=127.0.0.1 reason=ip-denied", rec.getMessage());
  }

  @Test
  @DisplayName("Camd35 : ip-denied -> AUTH_FAIL reason=ip-denied")
  void testCamd35IpDenied() throws Exception {
    registry.addTestUser(new com.bowman.cspng.users.UserRecord(
        CS_USER, CS_USER, "hash", null, true, false, false, false, 1, "192.168.1.1", -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, null, null));

    startCamd35Server();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());
      sock.write(Buffer.buffer(CS_UCRC));
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=cs378x") && r.getMessage().contains("reason=ip-denied"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord cs378x ip-denied attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cs378x user=csuser ip=127.0.0.1 reason=ip-denied", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : ip-denied -> AUTH_FAIL reason=ip-denied")
  void testNewcamdIpDenied() throws Exception {
    registry.addTestUser(new com.bowman.cspng.users.UserRecord(
        NC_USER, NC_USER, com.bowman.cspng.users.crypto.MD5Crypt.crypt(NC_PASS, "salt1234"), null, true, false, false, false, 1, "192.168.1.1", -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, null, null));

    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          byte[] userBytes = NC_USER.getBytes();
          byte[] passBytes = NC_PASS.getBytes();
          byte[] payload = new byte[userBytes.length + 1 + passBytes.length + 1];
          System.arraycopy(userBytes, 0, payload, 0, userBytes.length);
          payload[userBytes.length] = 0;
          System.arraycopy(passBytes, 0, payload, userBytes.length + 1, passBytes.length);
          payload[payload.length - 1] = 0;

          byte[] msg = new byte[13 + payload.length];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
          msg[11] = (byte) ((payload.length >> 8) & 0xFF);
          msg[12] = (byte) (payload.length & 0xFF);
          System.arraycopy(payload, 0, msg, 13, payload.length);

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=ip-denied"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd ip-denied attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=ncuser ip=127.0.0.1 reason=ip-denied", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : bad-length -> AUTH_FAIL reason=bad-length")
  void testNewcamdBadLength() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          // Envoyer une longueur invalide (0x0FFF = 4095 > maxMsgSize + 16 = 2064)
          Buffer out = Buffer.buffer(2);
          out.appendByte((byte) 0x0F);
          out.appendByte((byte) 0xFF);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=bad-length"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd bad-length attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=? ip=127.0.0.1 reason=bad-length", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : decrypt-fail -> AUTH_FAIL reason=decrypt-fail")
  void testNewcamdDecryptFail() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          // Envoyer 16 octets de ciphertext non déchiffrable (checksum XOR invalide)
          byte[] junk = new byte[16];
          Arrays.fill(junk, (byte) 0xAA);
          Buffer out = Buffer.buffer(2 + junk.length);
          out.appendByte((byte) 0);
          out.appendByte((byte) junk.length);
          out.appendBytes(junk);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=decrypt-fail"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd decrypt-fail attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=? ip=127.0.0.1 reason=decrypt-fail", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : truncated message (< 11 octets décryptés) -> AUTH_FAIL reason=truncated")
  void testNewcamdTruncatedMessage() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      NewcamdSession session = new NewcamdSession(vertx, null, registry, CONFIG_KEY_14);
      session.setNoEncrypt(true);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));

    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          // Envoyer un message de 5 octets en mode noEncrypt (< 11 octets requis)
          byte[] payload = new byte[5];
          Buffer out = Buffer.buffer(2 + payload.length);
          out.appendByte((byte) 0);
          out.appendByte((byte) payload.length);
          out.appendBytes(payload);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=truncated"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd truncated attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=? ip=127.0.0.1 reason=truncated", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : unexpected-command (commandTag != LOGIN avant auth) -> AUTH_FAIL reason=unexpected-command")
  void testNewcamdUnexpectedCommand() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          // Message de 15 octets avec commandTag = 0x80 (ECM au lieu de LOGIN 0xE0)
          byte[] msg = new byte[15];
          msg[10] = (byte) 0x80;

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("reason=unexpected-command"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd unexpected-command attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=? ip=127.0.0.1 reason=unexpected-command", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : compte désactivé (enabled=false) -> LOGIN NAK + socket fermée + AUTH_FAIL reason=expired")
  void testNewcamdDisabledAccount() throws Exception {
    String disabledUser = "disabledUser";
    registry.addTestUser(new com.bowman.cspng.users.UserRecord(
        disabledUser, disabledUser, com.bowman.cspng.users.crypto.MD5Crypt.crypt("pass123", "salt1234"),
        null, false, false, false, false, 1, null, -1,
        Set.of("default"), Map.of(), Map.of(), Set.of(), 1L, null, null));

    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);
    CountDownLatch nakReceivedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          byte[] userBytes = disabledUser.getBytes();
          byte[] passBytes = "pass123".getBytes();
          byte[] payload = new byte[userBytes.length + 1 + passBytes.length + 1];
          System.arraycopy(userBytes, 0, payload, 0, userBytes.length);
          payload[userBytes.length] = 0;
          System.arraycopy(passBytes, 0, payload, userBytes.length + 1, passBytes.length);
          payload[payload.length - 1] = 0;

          byte[] msg = new byte[13 + payload.length];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
          msg[11] = (byte) ((payload.length >> 8) & 0xFF);
          msg[12] = (byte) (payload.length & 0xFF);
          System.arraycopy(payload, 0, msg, 13, payload.length);

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);

          // Changer de handler pour vérifier la réception du LOGIN NAK
          sock.handler(respBuf -> {
            if (respBuf.length() >= 2) {
              nakReceivedLatch.countDown();
            }
          });
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));
    assertTrue(nakReceivedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("user=disabledUser") && r.getMessage().contains("reason=expired"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd reason=expired attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=disabledUser ip=127.0.0.1 reason=expired", rec.getMessage());
  }

  @Test
  @DisplayName("Newcamd : user inconnu (no record in userDirectory) -> AUTH_FAIL reason=unknown-user")
  void testNewcamdUnknownUser() throws Exception {
    startNewcamdServer();
    CountDownLatch closedLatch = new CountDownLatch(1);
    CountDownLatch nakReceivedLatch = new CountDownLatch(1);

    String unknownUser = "unknownNcUser";

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

      sock.handler(buf -> {
        if (buf.length() >= 14) {
          byte[] random14 = buf.getBytes(0, 14);
          byte[] desKey16 = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(CONFIG_KEY_14, random14));

          byte[] userBytes = unknownUser.getBytes();
          byte[] passBytes = "anypass".getBytes();
          byte[] payload = new byte[userBytes.length + 1 + passBytes.length + 1];
          System.arraycopy(userBytes, 0, payload, 0, userBytes.length);
          payload[userBytes.length] = 0;
          System.arraycopy(passBytes, 0, payload, userBytes.length + 1, passBytes.length);
          payload[payload.length - 1] = 0;

          byte[] msg = new byte[13 + payload.length];
          msg[10] = (byte) NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
          msg[11] = (byte) ((payload.length >> 8) & 0xFF);
          msg[12] = (byte) (payload.length & 0xFF);
          System.arraycopy(payload, 0, msg, 13, payload.length);

          byte[] enc = NewcamdCrypto.desEncrypt(msg, msg.length, desKey16, 2048);
          Buffer out = Buffer.buffer(2 + enc.length);
          out.appendByte((byte) (enc.length >> 8));
          out.appendByte((byte) (enc.length & 0xFF));
          out.appendBytes(enc);
          sock.write(out);

          sock.handler(respBuf -> {
            if (respBuf.length() >= 2) {
              nakReceivedLatch.countDown();
            }
          });
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));
    assertTrue(nakReceivedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=newcamd") && r.getMessage().contains("user=" + unknownUser) && r.getMessage().contains("reason=unknown-user"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord newcamd reason=unknown-user attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=newcamd user=" + unknownUser + " ip=127.0.0.1 reason=unknown-user", rec.getMessage());
  }

  @Test
  @DisplayName("CCcam : invalid-cli-data après password OK -> AUTH_FAIL reason=invalid-cli-data")
  void testCccamInvalidCliData() throws Exception {
    startCccamServer();
    CountDownLatch closedLatch = new CountDownLatch(1);

    client.connect(serverPort, "127.0.0.1", ar -> {
      assertTrue(ar.succeeded());
      NetSocket sock = ar.result();
      sock.closeHandler(v -> closedLatch.countDown());

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
          System.arraycopy(CC_USER.getBytes(), 0, userBuf, 0, CC_USER.length());
          cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(userBuf));

          byte[] pwd = CC_PASS.getBytes();
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

          // Envoyer CLI_DATA invalide (payload de 10 octets < 66 octets requis)
          byte[] invalidCliPayload = new byte[10];
          byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, invalidCliPayload);
          cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
          sock.write(Buffer.buffer(cliFrame));
          step[0] = 2;
        }
      });
    });

    assertTrue(closedLatch.await(5, TimeUnit.SECONDS));

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("protocol=cccam") && r.getMessage().contains("reason=invalid-cli-data"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "LogRecord cccam invalid-cli-data attendu");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("AUTH_FAIL protocol=cccam user=ccuser ip=127.0.0.1 reason=invalid-cli-data", rec.getMessage());
  }

  @Test
  @DisplayName("PREAUTH_REJECT : rejet listener-acl émet un log WARNING conforme")
  void testListenerAclDeny() {
    AuthAudit.preauthReject("192.168.1.100", "listener-acl");

    LogRecord rec = logHandler.records.stream()
        .filter(r -> r.getMessage().contains("reason=listener-acl"))
        .findFirst()
        .orElse(null);
    assertNotNull(rec, "Un LogRecord PREAUTH_REJECT listener-acl doit être émis");
    assertEquals(Level.WARNING, rec.getLevel());
    assertEquals("PREAUTH_REJECT ip=192.168.1.100 reason=listener-acl", rec.getMessage());
  }

  // --- Helpers pour démarrer les serveurs ---

  private void startCccamServer() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(CwReply.empty(req.key()));
    server.connectHandler(sock -> {
      CcCamSession session = new CcCamSession(
          vertx, null, registry, authResolver, secretResolver, router, SERVER_NODE_ID);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
  }

  private void startCamd35Server() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    SessionEcmRouter router = (req, auth) -> Future.succeededFuture(CwReply.empty(req.key()));
    server.connectHandler(sock -> {
      Camd35TcpSession session = new Camd35TcpSession(
          vertx, null, registry, authResolver, secretResolver, router);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
  }

  private void startNewcamdServer() throws Exception {
    CountDownLatch listenLatch = new CountDownLatch(1);
    server = vertx.createNetServer();
    server.connectHandler(sock -> {
      NewcamdSession session = new NewcamdSession(vertx, null, registry, CONFIG_KEY_14);
      session.accept(sock);
    });
    server.listen(0, "127.0.0.1", ar -> {
      serverPort = ar.result().actualPort();
      listenLatch.countDown();
    });
    assertTrue(listenLatch.await(5, TimeUnit.SECONDS));
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
