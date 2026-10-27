package com.bowman.cspng.connectors;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock serveur SOCKS5 (RFC 1928 + auth user/pass RFC 1929) pour tests Plan P2.
 *
 * <p>Comportement :
 * <ul>
 *   <li>Greeting : accepte NO_AUTH (0x00) si aucune credentials attendues, sinon USER/PASS (0x02).</li>
 *   <li>Auth user/pass : compare aux credentials attendues, répond succès [0x01,0x00] ou échec [0x01,0x01]+close.</li>
 *   <li>CONNECT : parse ATYP (0x01 IPv4, 0x03 DOMAIN, 0x04 IPv6), enregistre la requête,
 *       répond SUCCESS puis PIPE brut vers le backend TCP (127.0.0.1:backendPort).</li>
 *   <li>{@code blackhole=true} : ignore tout (pour tester le timeout handshake borné).</li>
 *   <li>{@code failAuth=true} : échec auth même avec bonnes credentials.</li>
 * </ul>
 *
 * <p>Les requêtes CONNECT enregistrées permettent d'assert ATYP DOMAIN (pas de DNS locale)
 * et le hostname/port exacts reçus par le proxy.
 */
class Socks5MockServer {

  private final Vertx vertx;
  private final int backendPort;
  private final String expectedUser;
  private final String expectedPass;
  private NetServer server;
  private int port;

  /** Requêtes CONNECT reçues (atyp, host, port). */
  final List<ConnectRecord> connectRequests = new CopyOnWriteArrayList<>();
  /** Nombre de pipes établis vers le backend. */
  final AtomicInteger backendPipes = new AtomicInteger();
  /** Connexions TCP reçues par le proxy (greeting ou non) — détecte les reconnects obsolètes. */
  final AtomicInteger proxyConnections = new AtomicInteger();
  /** Latch ouvert au premier CONNECT reçu. */
  final CountDownLatch firstConnect = new CountDownLatch(1);
  /** Latch ouvert à chaque CONNECT reçu (count global via connectRequests). */
  volatile boolean blackhole = false;
  /** Retarde la réponse greeting de N ms (0 = immédiat) — simule un handshake
   *  lent pour tester les callbacks de connexion tardifs après stop. */
  volatile long greetingDelayMs = 0;
  volatile boolean failAuth = false;
  volatile boolean failConnect = false;

  record ConnectRecord(int atyp, String host, int port) {
    static final int ATYP_IPV4 = 0x01;
    static final int ATYP_DOMAIN = 0x03;
    static final int ATYP_IPV6 = 0x04;
  }

  Socks5MockServer(Vertx vertx, int backendPort, String expectedUser, String expectedPass) {
    this.vertx = vertx;
    this.backendPort = backendPort;
    this.expectedUser = expectedUser;
    this.expectedPass = expectedPass;
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
    assertTrue(ready.await(5, TimeUnit.SECONDS), "Socks5MockServer should start");
  }

  void stop() {
    if (server != null) {
      server.close();
    }
  }

  /** Attend qu'au moins n requêtes CONNECT aient été reçues. */
  boolean awaitConnects(int n, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (connectRequests.size() >= n) return true;
      Thread.sleep(20);
    }
    return connectRequests.size() >= n;
  }

  private void handleConnection(NetSocket sock) {
    proxyConnections.incrementAndGet();
    sock.handler(new SocksHandler(sock)::handle);
    sock.closeHandler(v -> { /* pipe cleanup handled by Vert.x pipes */ });
  }

  private class SocksHandler {
    private final NetSocket sock;
    private Buffer acc = Buffer.buffer();
    private int phase = 0; // 0=greeting, 1=auth, 2=connect, 3=tunnel

    SocksHandler(NetSocket sock) { this.sock = sock; }

    void handle(Buffer data) {
      if (blackhole || phase == 3) return; // blackhole: ignore everything
      acc.appendBuffer(data);
      try {
        switch (phase) {
          case 0 -> handleGreeting();
          case 1 -> handleAuth();
          case 2 -> handleConnect();
          default -> { /* tunnel handled by pipes */ }
        }
      } catch (Exception e) {
        // Malformed — close
        try { sock.close(); } catch (Exception ignore) {}
      }
    }

    private void handleGreeting() {
      if (acc.length() < 2) return;
      int ver = acc.getByte(0) & 0xFF;
      int nmethods = acc.getByte(1) & 0xFF;
      if (ver != 5) { sock.close(); return; }
      if (acc.length() < 2 + nmethods) return;
      boolean hasNoAuth = false;
      boolean hasUserPass = false;
      for (int i = 0; i < nmethods; i++) {
        int m = acc.getByte(2 + i) & 0xFF;
        if (m == 0x00) hasNoAuth = true;
        if (m == 0x02) hasUserPass = true;
      }
      acc = Buffer.buffer(acc.getBytes(2 + nmethods, acc.length()));
      // Réponse éventuellement RETARDÉE (greetingDelayMs > 0) : le client attend
      // la réponse greeting pendant ce délai — permet de tester les callbacks de
      // connexion tardifs (le TCP est établi, le handshake SOCKS5 attend).
      java.util.function.Consumer<byte[]> respond = bytes -> {
        Runnable write = () -> {
          sock.write(Buffer.buffer(bytes));
        };
        if (greetingDelayMs > 0) {
          vertx.setTimer(greetingDelayMs, v -> write.run());
        } else {
          write.run();
        }
      };
      if (expectedUser != null) {
        if (hasUserPass) {
          respond.accept(new byte[]{0x05, 0x02});
          phase = 1;
        } else {
          respond.accept(new byte[]{0x05, (byte) 0xFF});
          sock.close();
        }
      } else {
        if (hasNoAuth) {
          respond.accept(new byte[]{0x05, 0x00});
          phase = 2;
        } else if (hasUserPass) {
          // Client propose auth mais le mock n'en attend pas → NO_AUTH obligatoire côté client
          respond.accept(new byte[]{0x05, (byte) 0xFF});
          sock.close();
        } else {
          respond.accept(new byte[]{0x05, (byte) 0xFF});
          sock.close();
        }
      }
    }

    private void handleAuth() {
      // [VER=1, ULEN, UNAME..., PLEN, PASSWD...]
      if (acc.length() < 2) return;
      int ver = acc.getByte(0) & 0xFF;
      if (ver != 1) { sock.close(); return; }
      int ulen = acc.getByte(1) & 0xFF;
      if (acc.length() < 2 + ulen + 1) return;
      String user = acc.getString(2, 2 + ulen, "UTF-8");
      int plen = acc.getByte(2 + ulen) & 0xFF;
      if (acc.length() < 2 + ulen + 1 + plen) return;
      String pass = acc.getString(2 + ulen + 1, 2 + ulen + 1 + plen, "UTF-8");
      acc = Buffer.buffer(acc.getBytes(2 + ulen + 1 + plen, acc.length()));
      boolean ok = !failAuth && user.equals(expectedUser) && pass.equals(expectedPass);
      if (ok) {
        sock.write(Buffer.buffer(new byte[]{0x01, 0x00}));
        phase = 2;
      } else {
        sock.write(Buffer.buffer(new byte[]{0x01, 0x01}));
        sock.close();
      }
    }

    private void handleConnect() {
      // [VER=5, CMD=0x01, RSV=0x00, ATYP, DST.ADDR, DST.PORT]
      if (acc.length() < 4) return;
      int ver = acc.getByte(0) & 0xFF;
      int cmd = acc.getByte(1) & 0xFF;
      if (ver != 5 || cmd != 1) { sock.close(); return; }
      int atyp = acc.getByte(3) & 0xFF;
      int addrLen;
      switch (atyp) {
        case ConnectRecord.ATYP_IPV4 -> addrLen = 4;
        case ConnectRecord.ATYP_DOMAIN -> {
          if (acc.length() < 5) return;
          addrLen = acc.getByte(4) & 0xFF;
          if (addrLen == 0) { sock.close(); return; }
        }
        case ConnectRecord.ATYP_IPV6 -> addrLen = 16;
        default -> { sock.close(); return; }
      }
      int addrStart = (atyp == ConnectRecord.ATYP_DOMAIN) ? 5 : 4;
      if (acc.length() < addrStart + addrLen + 2) return;
      String host;
      if (atyp == ConnectRecord.ATYP_DOMAIN) {
        host = acc.getString(addrStart, addrStart + addrLen, "UTF-8");
      } else if (atyp == ConnectRecord.ATYP_IPV4) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
          if (i > 0) sb.append('.');
          sb.append(acc.getUnsignedByte(addrStart + i));
        }
        host = sb.toString();
      } else {
        host = "ipv6"; // non utilisé dans les asserts
      }
      int port = ((acc.getUnsignedByte(addrStart + addrLen) << 8)
          | acc.getUnsignedByte(addrStart + addrLen + 1));
      acc = Buffer.buffer(acc.getBytes(addrStart + addrLen + 2, acc.length()));

      connectRequests.add(new ConnectRecord(atyp, host, port));
      firstConnect.countDown();

      if (failConnect) {
        // REFUSED
        sock.write(Buffer.buffer(new byte[]{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0}));
        sock.close();
        return;
      }
      // SUCCESS — bound addr irrelevant
      sock.write(Buffer.buffer(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}));
      phase = 3;
      // Pipe vers le backend
      sock.handler(null);
      vertx.createNetClient().connect(backendPort, "127.0.0.1").onComplete(ar -> {
        if (ar.succeeded()) {
          NetSocket backend = ar.result();
          backendPipes.incrementAndGet();
          sock.pipe().to(backend).onComplete(pipeRes -> {
            try { backend.close(); } catch (Exception ignore) {}
            try { sock.close(); } catch (Exception ignore) {}
          });
          backend.pipe().to(sock).onComplete(pipeRes -> {
            try { backend.close(); } catch (Exception ignore) {}
            try { sock.close(); } catch (Exception ignore) {}
          });
        } else {
          try { sock.close(); } catch (Exception ignore) {}
        }
      });
    }
  }
}
