package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorProbeTest {

  Vertx vertx;
  private int freePort() {
    try (var ss = new java.net.ServerSocket(0)) { ss.setReuseAddress(true); return ss.getLocalPort(); } catch (Exception e) { return 35000 + (int)(Math.random()*2000); }
  }

  @AfterEach
  void close() throws Exception {
    if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void radegastProbeSuccess() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    NetServer server = vertx.createNetServer();
    CountDownLatch ready = new CountDownLatch(1);
    server.connectHandler(sock -> {});
    server.listen(port, "127.0.0.1", ar -> { if (ar.succeeded()) ready.countDown(); });
    assertTrue(ready.await(5, TimeUnit.SECONDS));

    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CountDownLatch ctx = new CountDownLatch(1);
    cv.runOnContext(ctx::countDown);
    assertTrue(ctx.await(5, TimeUnit.SECONDS));

    var fut = ConnectorProbe.probe(vertx, cv, "radegast", "127.0.0.1", port, null, null, null, 4000);
    var result = fut.toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
    assertTrue(result.success());
    assertEquals(AbstractConnector.LoginState.OK, result.loginState());
    assertEquals(0, cv.transportCount(), "no phantom transport for radegast");
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void cccamProbeSuccessAndNoPhantom() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    NetServer server = vertx.createNetServer();
    CountDownLatch ready = new CountDownLatch(1);
    server.connectHandler(sock -> {
      CeMockServer ce = new CeMockServer(sock, "testuser", "testpass");
      ce.onHandshakeComplete(() -> {
        byte[] srvData = buildSrvData(ce.getNodeId());
        ce.sendFrame(CcConstants.MSG_SRV_DATA, srvData);
      });
      ce.startHandshake();
    });
    server.listen(port, "127.0.0.1", ar -> { if (ar.succeeded()) ready.countDown(); });
    assertTrue(ready.await(5, TimeUnit.SECONDS));

    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CountDownLatch ctx = new CountDownLatch(1);
    cv.runOnContext(ctx::countDown);
    assertTrue(ctx.await(5, TimeUnit.SECONDS));

    assertEquals(0, cv.transportCount());
    var fut = ConnectorProbe.probe(vertx, cv, "cccam", "127.0.0.1", port, "testuser", "testpass", null, 6000);
    var result = fut.toCompletionStage().toCompletableFuture().get(8, TimeUnit.SECONDS);
    assertTrue(result.success(), "cccam probe should succeed: " + result.errorMessage());
    assertEquals(AbstractConnector.LoginState.OK, result.loginState());
    // wait a bit for cleanup
    Thread.sleep(300);
    // ensure transport phantom cleaned
    CountDownLatch check = new CountDownLatch(1);
    AtomicReference<Integer> cnt = new AtomicReference<>();
    cv.runOnContext(() -> { cnt.set(cv.transportCount()); check.countDown(); });
    assertTrue(check.await(5, TimeUnit.SECONDS));
    assertEquals(0, cnt.get(), "transportCount should be 0 after successful probe");
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void cccamProbeFailedInvalidMagicAndNoPhantom() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    NetServer server = vertx.createNetServer();
    CountDownLatch ready = new CountDownLatch(1);
    server.connectHandler(sock -> {
      // Send seed but respond with invalid magic (not CCcam)
      byte[] seed = new byte[16];
      new SecureRandom().nextBytes(seed);
      sock.write(Buffer.buffer(seed));
      // Use same crypto derivation as CeMockServer to be able to decrypt client messages then send invalid response
      CcCrypt decryptBlock = new CcCrypt();
      CcCrypt encryptBlock = new CcCrypt();
      // Derive quickly: mimic CeMockServer handshake derivation
      byte[] seedCopy = seed.clone();
      CcCrypt.xor(seedCopy); // cc_xor
      byte[] hash = sha1(seedCopy);
      decryptBlock.initCrypt(hash, 20);
      decryptBlock.crypt(seedCopy, 16, CcConstants.DECRYPT);
      encryptBlock.initCrypt(seedCopy, 16);
      encryptBlock.crypt(hash, 20, CcConstants.DECRYPT);
      // Now wait for client hash/user/magic
      Handler<Buffer> h = new Handler<>() {
        Buffer buf = Buffer.buffer();
        int step = 0;
        @Override public void handle(Buffer data) {
          buf.appendBuffer(data);
          if (step == 0 && buf.length() >= 20) { byte[] b = buf.getBytes(0,20); decryptBlock.crypt(b,20,CcConstants.DECRYPT); buf = Buffer.buffer(buf.getBytes(20, buf.length())); step=1; }
          if (step == 1 && buf.length() >= 20) { byte[] b = buf.getBytes(0,20); decryptBlock.crypt(b,20,CcConstants.DECRYPT); byte[] pwd = "testpass".getBytes(); decryptBlock.crypt(pwd, pwd.length, CcConstants.ENCRYPT); buf = Buffer.buffer(buf.getBytes(20, buf.length())); step=2; }
          if (step == 2 && buf.length() >= 6) { byte[] b = buf.getBytes(0,6); decryptBlock.crypt(b,6,CcConstants.DECRYPT); // now send invalid response
            byte[] resp = new byte[20]; Arrays.fill(resp, (byte)0xFF); // not CCcam
            encryptBlock.crypt(resp, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(resp));
            step=99;
            sock.closeHandler(v->{});
            // close after a bit
            vertx.setTimer(500, id-> sock.close());
          }
        }
      };
      sock.handler(h);
    });
    server.listen(port, "127.0.0.1", ar -> { if (ar.succeeded()) ready.countDown(); });
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CountDownLatch ctx = new CountDownLatch(1);
    cv.runOnContext(ctx::countDown);
    assertTrue(ctx.await(5, TimeUnit.SECONDS));
    var fut = ConnectorProbe.probe(vertx, cv, "cccam", "127.0.0.1", port, "testuser", "testpass", null, 3000);
    var result = fut.toCompletionStage().toCompletableFuture().get(6, TimeUnit.SECONDS);
    assertFalse(result.success());
    assertEquals(AbstractConnector.LoginState.FAILED, result.loginState());
    Thread.sleep(300);
    CountDownLatch check = new CountDownLatch(1);
    AtomicReference<Integer> cnt = new AtomicReference<>();
    cv.runOnContext(() -> { cnt.set(cv.transportCount()); check.countDown(); });
    assertTrue(check.await(5, TimeUnit.SECONDS));
    assertEquals(0, cnt.get(), "phantom should be cleaned even on failed probe");
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void cccamProbeTimeoutAndNoPhantom() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    NetServer server = vertx.createNetServer();
    CountDownLatch ready = new CountDownLatch(1);
    server.connectHandler(sock -> {
      // Accept but never send seed -> client waits forever -> probe timeout
    });
    server.listen(port, "127.0.0.1", ar -> { if (ar.succeeded()) ready.countDown(); });
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    CountDownLatch ctx = new CountDownLatch(1);
    cv.runOnContext(ctx::countDown);
    assertTrue(ctx.await(5, TimeUnit.SECONDS));
    long start = System.currentTimeMillis();
    var fut = ConnectorProbe.probe(vertx, cv, "cccam", "127.0.0.1", port, "testuser", "testpass", null, 1500);
    var result = fut.toCompletionStage().toCompletableFuture().get(4, TimeUnit.SECONDS);
    long elapsed = System.currentTimeMillis() - start;
    assertFalse(result.success());
    assertTrue(elapsed >= 1200 && elapsed < 4000, "should timeout around 1500ms, elapsed=" + elapsed);
    Thread.sleep(300);
    CountDownLatch check = new CountDownLatch(1);
    AtomicReference<Integer> cnt = new AtomicReference<>();
    cv.runOnContext(() -> { cnt.set(cv.transportCount()); check.countDown(); });
    assertTrue(check.await(5, TimeUnit.SECONDS));
    assertEquals(0, cnt.get(), "phantom should be cleaned on timeout");
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  @Test
  @Timeout(15)
  void newcamdProbeTimeoutAndNoPhantom() throws Exception {
    vertx = Vertx.vertx();
    int port = freePort();
    NetServer server = vertx.createNetServer();
    server.connectHandler(sock -> { /* silent */ });
    server.listen(port, "127.0.0.1", ar -> {});
    Thread.sleep(200);
    CacheVerticle cv = new CacheVerticle();
    vertx.deployVerticle(cv).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    byte[] key14 = new byte[14];
    Arrays.fill(key14, (byte)0x01);
    var fut = ConnectorProbe.probe(vertx, cv, "newcamd", "127.0.0.1", port, "user", "pass", key14, 1200);
    var result = fut.toCompletionStage().toCompletableFuture().get(4, TimeUnit.SECONDS);
    assertFalse(result.success());
    server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  // ---- Helpers copied from CeMockServer minimal ----

  static byte[] buildSrvData(byte[] nodeId) {
    byte[] data = new byte[72];
    System.arraycopy(nodeId, 0, data, 0, 8);
    byte[] ver = "2.3.2".getBytes();
    System.arraycopy(ver, 0, data, 8, Math.min(ver.length, 6));
    byte[] bld = "4000".getBytes();
    System.arraycopy(bld, 0, data, 40, Math.min(bld.length, 4));
    return data;
  }
  static byte[] sha1(byte[] data) {
    try { return MessageDigest.getInstance("SHA-1").digest(data); } catch (Exception e) { throw new RuntimeException(e); }
  }

  static class CeMockServer {
    final NetSocket socket;
    final String expectedUser, expectedPass;
    final byte[] ourNodeId = new byte[8];
    final CcCrypt encryptBlock = new CcCrypt();
    final CcCrypt decryptBlock = new CcCrypt();
    private Buffer accumulator = Buffer.buffer();
    private boolean headerPhase = true;
    private int payloadLen;
    private int currentCmd;
    private Runnable onHandshakeComplete;
    CeMockServer(NetSocket socket, String user, String pass) {
      this.socket = socket; this.expectedUser=user; this.expectedPass=pass;
      new SecureRandom().nextBytes(ourNodeId);
    }
    void onHandshakeComplete(Runnable r){ this.onHandshakeComplete=r; }
    byte[] getNodeId(){ return ourNodeId; }
    void startHandshake() {
      byte[] seed = new byte[16];
      new SecureRandom().nextBytes(seed);
      sendRaw(seed);
      byte[] seedXor = seed.clone();
      CcCrypt.xor(seedXor);
      byte[] hash = sha1(seedXor);
      byte[] seedForEncrypt = seedXor.clone();
      encryptBlock.initCrypt(hash, 20);
      encryptBlock.crypt(seedXor, 16, CcConstants.DECRYPT);
      decryptBlock.initCrypt(seedXor, 16);
      decryptBlock.crypt(hash, 20, CcConstants.DECRYPT);
      socket.handler(new HandshakeState(seedForEncrypt));
    }
    void sendRaw(byte[] data){ socket.write(Buffer.buffer(data)); }
    void sendFrame(int cmd, byte[] payload){ byte[] frame=CcFrameCodec.encode(cmd,payload); encryptBlock.crypt(frame,frame.length,CcConstants.ENCRYPT); sendRaw(frame); }
    private class HandshakeState implements Handler<Buffer> {
      private final byte[] seedXor;
      private Buffer buf = Buffer.buffer();
      private int step = 0;
      HandshakeState(byte[] seedXor){ this.seedXor=seedXor; }
      @Override public void handle(Buffer data){ buf.appendBuffer(data); while(processStep()){} }
      private boolean processStep(){
        switch(step){
          case 0: if(buf.length()<20) return false; byte[] hash=buf.getBytes(0,20); decryptBlock.crypt(hash,20,CcConstants.DECRYPT); buf=Buffer.buffer(buf.getBytes(20,buf.length())); step=1; return true;
          case 1: if(buf.length()<20) return false; byte[] user=buf.getBytes(0,20); decryptBlock.crypt(user,20,CcConstants.DECRYPT); buf=Buffer.buffer(buf.getBytes(20,buf.length())); byte[] pwdBytes=expectedPass.getBytes(); decryptBlock.crypt(pwdBytes,pwdBytes.length,CcConstants.ENCRYPT); step=2; return true;
          case 2: if(buf.length()<6) return false; byte[] magic=buf.getBytes(0,6); decryptBlock.crypt(magic,6,CcConstants.DECRYPT); boolean ok=magic[0]=='C'&&magic[1]=='C'&&magic[2]=='c'&&magic[3]=='a'&&magic[4]=='m'; if(!ok){ socket.close(); return false; } byte[] resp=new byte[20]; resp[0]='C';resp[1]='C';resp[2]='c';resp[3]='a';resp[4]='m'; encryptBlock.crypt(resp,20,CcConstants.ENCRYPT); sendRaw(resp); socket.handler(new NormalState()); if(onHandshakeComplete!=null) onHandshakeComplete.run(); step=99; return false;
          default: return false;
        }
      }
    }
    private class NormalState implements Handler<Buffer> {
      @Override public void handle(Buffer data){ accumulator.appendBuffer(data); process(); }
      private void process(){ while(true){ if(headerPhase){ if(accumulator.length()<4) return; byte[] header=accumulator.getBytes(0,4); decryptBlock.crypt(header,4,CcConstants.DECRYPT); int[] decoded=CcFrameCodec.decodeHeader(header); currentCmd=decoded[1]; payloadLen=decoded[2]; if(payloadLen==0){ accumulator=Buffer.buffer(accumulator.getBytes(4,accumulator.length())); } else headerPhase=false; } else { if(accumulator.length()<4+payloadLen) return; byte[] payload=accumulator.getBytes(4,4+payloadLen); decryptBlock.crypt(payload,payloadLen,CcConstants.DECRYPT); accumulator=Buffer.buffer(accumulator.getBytes(4+payloadLen,accumulator.length())); headerPhase=true; } } }
    }
  }
}
