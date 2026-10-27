package com.bowman.cspng.worker;

import com.bowman.cspng.codec.camd35.Camd35Constants;
import com.bowman.cspng.codec.camd35.Camd35Crypto;
import com.bowman.cspng.codec.camd35.Camd35FrameCodec;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cccam.CcConstants;
import com.bowman.cspng.codec.cccam.CcCrypt;
import com.bowman.cspng.codec.cccam.CcCwCrypt;
import com.bowman.cspng.codec.cccam.CcFrameCodec;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import com.bowman.cspng.config.RelayWorkerContract;
import com.bowman.cspng.connectors.routing.RelayCacheStores;
import com.bowman.cspng.connectors.routing.StrictCacheKey;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration reelle listener CCCam + CS378x -> WorkerRuntime SANS connecteur
 * local -> pair UDP allowliste (WorkerUdpExchange) -> CW. Preuve dataplane
 * complet : sessions reelles, StrictEcmRouter, catalogue publie, scope ACL,
 * cache hit, detachement, pair absent de l'allowlist, export refuse sans lien,
 * spoof droppe.
 *
 * <p>Topologie (loopback, vrais datagrammes, aucun mock transport) :
 * <ul>
 *   <li>worker B (sous test) : listeners cccam/cs378x, cache udp "u" pair=A,
 *       lien canConsult+canFeed, AUCUN connecteur amont local, catalogue publie.</li>
 *   <li>pair A : {@link WorkerUdpExchange} allowliste, lien export, index local
 *       seede (CW fraiches) — simule un pair legacy autorise.</li>
 *   <li>La CW renvoyee par A porte l'origine {@code udp:u} (convention du routeur,
 *       eligibleSet du listener B) pour que le cache hit passe le controle de provenance.</li>
 * </ul>
 */
@DisplayName("WorkerUdpConsultIntegration — CCCam/CS378x -> worker sans connecteur -> pair UDP -> CW")
class WorkerUdpConsultIntegrationTest {

  // Ports loopback fixes, distincts des autres tests worker.
  private static final int CCCAM_PORT = 14851;
  private static final int CS378X_PORT = 14852;
  private static final int UDP_PORT_B = 14853;
  private static final int UDP_PORT_A = 14854;

  private static final int CAID = 0x0100;
  private static final int SRVID = 0x0001;
  private static final int CARD_ID = 0x00AABBCC;
  private static final byte[] CLIENT_NODE = {9, 8, 7, 6, 5, 4, 3, 2};

  /** CW valide (checksum DcwValidator : chaque 4e octet = somme des 3 precedents). */
  private static final byte[] CW = {1, 2, 3, 6, 5, 6, 7, 18, 9, 10, 11, 30, 13, 14, 15, 42};

  private Vertx vertx;
  private Path secretDir;
  private Path ccPassFile;
  private Path csPassFile;
  private Path charliePassFile;

  private WorkerRuntime runtimeB;
  private WorkerState stateB;
  private WorkerUdpExchange peerA;
  private WorkerState stateA;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    secretDir = Path.of("target/udp-consult-secrets");
    Files.createDirectories(secretDir);
    ccPassFile = secretDir.resolve("cc-pass.txt");
    csPassFile = secretDir.resolve("cs-pass.txt");
    charliePassFile = secretDir.resolve("charlie-pass.txt");
    Files.writeString(ccPassFile, "secret1");
    Files.writeString(csPassFile, "secret2");
    Files.writeString(charliePassFile, "secret3");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (peerA != null) peerA.failClosed();
    if (runtimeB != null) runtimeB.close();
    if (vertx != null) {
      CountDownLatch l = new CountDownLatch(1);
      vertx.close(v -> l.countDown());
      assertTrue(l.await(10, TimeUnit.SECONDS));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers topologie
  // ---------------------------------------------------------------------------------------------

  private static <T> T join(Future<T> f, long timeoutMs) throws Exception {
    return f.toCompletionStage().toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
  }

  /** Pair A : echange UDP allowliste avec lien export, index seede. */
  private void startPeerA(boolean exportLink) throws Exception {
    stateA = new WorkerState("node-a");
    peerA = new WorkerUdpExchange(vertx, stateA);
    assertEquals(UDP_PORT_A, (int) join(peerA.start(UDP_PORT_A), 5000));
    long now = System.currentTimeMillis();
    var desiredA = new RelayWorkerContract.DesiredWorkerConfig(
        "node-a", 1L, now + 120000L, 30000L, List.of(),
        List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
            List.of(new RelayWorkerContract.PeerView("127.0.0.1", UDP_PORT_B)))),
        List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, exportLink, true)),
        List.of());
    assertTrue(stateA.applyDesired(desiredA, now));
    peerA.updateBindings(desiredA, Set.of("default"));
  }

  private void seedPeer(EcmKey key) {
    CwReply reply = new CwReply(key, 0x80, CW.clone(), "udp:u", "127.0.0.1",
        System.currentTimeMillis());
    peerA.onLocalFeed(7L, "u", reply);
  }

  private void startWorkerB(long version, boolean linksEnabled, boolean withPeers,
                            List<RelayWorkerContract.CatalogView> catalog) throws Exception {
    // Ferme la generation precedente (listeners + socket UDP) avant de rebinder.
    if (runtimeB != null) runtimeB.close();
    stateB = new WorkerState("node-b");
    runtimeB = new WorkerRuntime(vertx, "node-b", stateB);
    runtimeB.setUdpPort(UDP_PORT_B);
    long now = System.currentTimeMillis();
    var peers = withPeers
        ? List.of(new RelayWorkerContract.PeerView("127.0.0.1", UDP_PORT_A))
        : List.<RelayWorkerContract.PeerView>of();
    var desired = new RelayWorkerContract.DesiredWorkerConfig(
        "node-b", version, now + 120000L, 30000L,
        List.of(
            new RelayWorkerContract.ListenerView(7L, "default", "cccam", CCCAM_PORT,
                "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null),
            new RelayWorkerContract.ListenerView(8L, "default", "cs378x", CS378X_PORT,
                "127.0.0.1", "manual", 0, 512, 32, 5, 60, null, null)),
        List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null, peers)),
        List.of(
            new RelayWorkerContract.LinkView(7L, "u", true, true, false, linksEnabled),
            new RelayWorkerContract.LinkView(8L, "u", true, true, false, linksEnabled)),
        List.of(),
        List.of(
            new RelayWorkerContract.UserAclView("alice", true, List.of("default")),
            new RelayWorkerContract.UserAclView("bob", true, List.of("default")),
            new RelayWorkerContract.UserAclView("charlie", true, List.of("default"),
                List.of("dummy_local"), java.util.Map.of(), java.util.Map.of())),
        List.of(
            new RelayWorkerContract.UserSecretView("alice", "cccam",
                "file:" + ccPassFile.toAbsolutePath(), 1L, true),
            new RelayWorkerContract.UserSecretView("bob", "cs378x",
                "file:" + csPassFile.toAbsolutePath(), 1L, true),
            new RelayWorkerContract.UserSecretView("charlie", "cccam",
                "file:" + charliePassFile.toAbsolutePath(), 1L, true)),
        catalog != null ? catalog
            : List.of(new RelayWorkerContract.CatalogView("default", CAID, 0, "published", 1L)),
        List.of(), List.of());
    Boolean applied = join(runtimeB.applyDesired(desired, System.currentTimeMillis()), 15_000);
    assertTrue(applied, "desired B applique");
    assertEquals(2, runtimeB.listenerCount());
  }

  private RelayCacheStores storesB() {
    return stateB.stores();
  }

  // ---------------------------------------------------------------------------------------------
  // Client CCCam reel (codecs, pas de mock)
  // ---------------------------------------------------------------------------------------------

  /** Connecte, handshake complet, envoie N ECM identiques, retourne les CW recues. */
  private List<byte[]> runCccamClient(int port, byte[] body, int ecmCount, long timeoutMs) throws Exception {
    NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
    CountDownLatch cwLatch = new CountDownLatch(ecmCount);
    java.util.List<byte[]> cws = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    try {
      client.connect(port, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();
        Buffer[] acc = {Buffer.buffer()};
        CcCrypt cliDec = new CcCrypt();
        CcCrypt cliEnc = new CcCrypt();
        int[] step = {0};
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          if (step[0] == 0 && acc[0].length() >= 16) {
            byte[] seed = acc[0].getBytes(0, 16);
            acc[0] = Buffer.buffer(acc[0].getBytes(16, acc[0].length()));
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
            System.arraycopy("alice".getBytes(StandardCharsets.US_ASCII), 0, userBuf, 0, 5);
            cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(userBuf));
            byte[] pwd = "secret1".getBytes(StandardCharsets.US_ASCII);
            cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
            byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
            cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(magic));
            step[0] = 1;
          }
          if (step[0] == 1 && acc[0].length() >= 20) {
            byte[] ack = acc[0].getBytes(0, 20);
            cliDec.crypt(ack, 20, CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(20, acc[0].length()));
            byte[] cliData = CcCardCodec.buildCliData("alice", CLIENT_NODE, "2.3.2", "4000");
            byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
            cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(cliFrame));
            step[0] = 2;
          }
          if (step[0] == 2) {
            while (acc[0].length() >= 4) {
              byte[] hdr = acc[0].getBytes(0, 4);
              cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
              int[] decoded = CcFrameCodec.decodeHeader(hdr);
              int total = 4 + decoded[2];
              if (acc[0].length() < total) return;
              byte[] payload = acc[0].getBytes(4, total);
              cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
              acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
              if (decoded[1] == CcConstants.MSG_SRV_DATA) {
                sendCccamEcm(sock, cliEnc, body, 1);
              } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                byte[] scrambled = Arrays.copyOf(payload, 16);
                byte[] clear = scrambled.clone();
                CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);
                byte[] sync = clear.clone();
                cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
                cws.add(clear);
                cwLatch.countDown();
                if (cws.size() < ecmCount) {
                  sendCccamEcm(sock, cliEnc, body, cws.size() + 1);
                }
              }
            }
          }
        });
      });
      cwLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
      return List.copyOf(cws);
    } finally {
      client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  private static void sendCccamEcm(NetSocket sock, CcCrypt cliEnc, byte[] body, int seq) {
    byte[] section = CcCardCodec.reconstructSection(0x80, body);
    byte[] req = CcCardCodec.buildEcmRequest(CAID, 0, CARD_ID, SRVID, section);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, req);
    cliEnc.crypt(frame, frame.length, CcConstants.ENCRYPT);
    sock.write(Buffer.buffer(frame));
  }

  // ---------------------------------------------------------------------------------------------
  // Client CS378x reel
  // ---------------------------------------------------------------------------------------------

  private byte[] runCs378xClient(int port, byte[] payload, long timeoutMs) throws Exception {
    byte[] ucrc = Camd35Crypto.ucrc("bob");
    byte[] key = Camd35Crypto.aesKey("secret2");
    NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
    CountDownLatch replyLatch = new CountDownLatch(1);
    AtomicReference<byte[]> cw = new AtomicReference<>();
    try {
      client.connect(port, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();
        Camd35FrameCodec.Decoder clientDec = new Camd35FrameCodec.Decoder(key);
        sock.handler(buf -> {
          Camd35FrameCodec.DecodeResult res = clientDec.feed(buf.getBytes());
          if (res.status() == Camd35FrameCodec.Status.FRAME
              && res.frame().cmd() == Camd35Constants.CMD_CW) {
            cw.set(res.frame().data());
            replyLatch.countDown();
          }
        });
        byte[] section = new byte[3 + payload.length];
        section[0] = (byte) 0x80;
        section[1] = (byte) (0x70 | ((payload.length >> 8) & 0x0F));
        section[2] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, section, 3, payload.length);
        Camd35FrameCodec.Camd35Frame req = new Camd35FrameCodec.Camd35Frame(
            ucrc, Camd35Constants.CMD_ECM, SRVID, CAID, 0, 0x1122, 0xFFFF, section, new byte[0]);
        sock.write(Buffer.buffer(Camd35FrameCodec.encode(req, key)));
      });
      if (!replyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null;
      return cw.get();
    } finally {
      client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("CCCam + CS378x -> worker sans connecteur -> pair UDP -> CW ; cache hit ; detach ; pair absent")
  void udpConsultCccamCs378xCacheHitDetachPeerDisabled() throws Exception {
    startPeerA(true);
    startWorkerB(1L, true, true, null);

    // Corps ECM distincts par protocole (cliches de tests existants).
    byte[] ccBody = new byte[40];
    Arrays.fill(ccBody, (byte) 1);
    byte[] csPayload = new byte[40];
    Arrays.fill(csPayload, (byte) 2);

    EcmKey keyCc = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(ccBody));
    EcmKey keyCs = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(csPayload));
    seedPeer(keyCc);
    seedPeer(keyCs);

    // 1. CCcam : ECM #1 (consult UDP) + ECM #2 identique (cache hit).
    List<byte[]> cws = runCccamClient(CCCAM_PORT, ccBody, 2, 12_000);
    assertEquals(2, cws.size(), "deux CW attendues pour le client CCcam");
    assertArrayEquals(CW, cws.get(0), "CW #1 via pair UDP");
    assertArrayEquals(CW, cws.get(1), "CW #2 via cache hit");
    assertEquals(1L, runtimeB.udpExchange().stats().sentPending(),
        "exactement UNE requete UDP emise : le 2e ECM est servi par le store");
    assertTrue(storesB().consultHits("u") >= 1, "store B : cache hit compte");

    // 2. CS378x : ECM -> CW via pair UDP (cle distincte).
    byte[] csCw = runCs378xClient(CS378X_PORT, csPayload, 8_000);
    assertNotNull(csCw, "CW attendue pour le client CS378x");
    assertArrayEquals(CW, csCw, "CW CS378x via pair UDP");

    // 3. Detachement : lien desactive, version bump => plus de retour, store purge.
    startWorkerB(2L, false, true, null);
    assertFalse(stateB.policy().canConsult(7L, "u"));
    List<byte[]> afterDetach = runCccamClient(CCCAM_PORT, ccBody, 1, 2_500);
    assertTrue(afterDetach.isEmpty(), "apres detachement : aucune CW (NOK rapide)");
    assertNull(storesB().peek("u", new StrictCacheKey("default", CAID, 0, SRVID, 0x80,
        ccBody, 2L, Set.of("udp:u"))), "store purge au detachement (pas de cache stale)");

    // 4. Pair absent de l'allowlist (peers vides) : echec ferme immediat.
    startWorkerB(3L, true, false, null);
    List<byte[]> afterPeerGone = runCccamClient(CCCAM_PORT, ccBody, 1, 2_500);
    assertTrue(afterPeerGone.isEmpty(), "sans pair allowliste : aucune CW");
  }

  @Test
  @DisplayName("Spoof : REPLY forgee (checksum valide) hors allowlist droppee, aucune alimentation")
  void spoofRepliesDropped() throws Exception {
    startPeerA(true);
    startWorkerB(1L, true, true, null);

    // REPLY forgee avec checksum valide mais depuis un port NON allowliste :
    // le transport strict la droppe (notAllowlisted) et rien n'entre dans le store.
    DatagramSocket evil = vertx.createDatagramSocket(new DatagramSocketOptions());
    try {
      byte[] body = new byte[40];
      Arrays.fill(body, (byte) 7);
      EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(body));
      CwReply forged = new CwReply(key, 0x80, CW.clone(), "udp:u", "127.0.0.1",
          System.currentTimeMillis());
      byte[] frame = ClusterUdpCodec.encodeReply(forged);
      long before = runtimeB.udpExchange().stats().notAllowlisted();
      evil.send(Buffer.buffer(frame), UDP_PORT_B, "127.0.0.1")
          .toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
      long deadline = System.currentTimeMillis() + 2000;
      while (runtimeB.udpExchange().stats().notAllowlisted() == before
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(runtimeB.udpExchange().stats().notAllowlisted() > before,
          "REPLY forgee hors allowlist : comptabilisee et droppee");
      assertEquals(0L, runtimeB.udpExchange().droppedUnsolicited(),
          "REPLY non-allowliste = drop transport, pas de tentative de matching pending");
      assertTrue(storesB().feedPuts("u") == 0 && storesB().consultHits("u") == 0,
          "aucune alimentation depuis une source non allowliste");
    } finally {
      evil.close();
    }
  }

  @Test
  @DisplayName("Export refuse si canExport=false : onLocalFeed muet (ni envoi, ni index)")
  void noExportWithoutExportLink() throws Exception {
    stateA = new WorkerState("node-a");
    peerA = new WorkerUdpExchange(vertx, stateA);
    assertEquals(UDP_PORT_A, (int) join(peerA.start(UDP_PORT_A), 5000));
    long now = System.currentTimeMillis();
    var desiredA = new RelayWorkerContract.DesiredWorkerConfig(
        "node-a", 1L, now + 120000L, 30000L, List.of(),
        List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
            List.of(new RelayWorkerContract.PeerView("127.0.0.1", UDP_PORT_B)))),
        List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, false, true)),
        List.of());
    assertTrue(stateA.applyDesired(desiredA, now));
    peerA.updateBindings(desiredA, Set.of("default"));

    EcmKey key = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(new byte[40]));
    CwReply reply = new CwReply(key, 0x80, CW.clone(), "up1", "127.0.0.1",
        System.currentTimeMillis());
    peerA.onLocalFeed(7L, "u", reply);
    assertEquals(0L, peerA.stats().sentEntries(),
        "canExport=false : aucune trame exportee vers les pairs");
    assertEquals(0L, peerA.droppedUnsolicited(), "aucun echo, aucune emission parasite");
  }

  @Test
  @DisplayName("Anti-boucle/anti-amplification : B sert depuis son index, jamais de re-emission")
  void remoteRequestNeverForwarded() throws Exception {
    // Topologie 2 echangeurs UDP A<->B, export+consult des deux cotes, index seede chez B.
    WorkerState stA = new WorkerState("node-a");
    WorkerUdpExchange exA = new WorkerUdpExchange(vertx, stA);
    WorkerState stB = new WorkerState("node-b");
    WorkerUdpExchange exB = new WorkerUdpExchange(vertx, stB);
    try {
      assertEquals(UDP_PORT_A, (int) join(exA.start(UDP_PORT_A), 5000));
      assertEquals(UDP_PORT_B, (int) join(exB.start(UDP_PORT_B), 5000));
      long now = System.currentTimeMillis();
      var cfgA = new RelayWorkerContract.DesiredWorkerConfig(
          "node-a", 1L, now + 120000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", UDP_PORT_B)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)),
          List.of());
      var cfgB = new RelayWorkerContract.DesiredWorkerConfig(
          "node-b", 1L, now + 120000L, 30000L, List.of(),
          List.of(new RelayWorkerContract.CacheView("u", "udp", true, 8000L, 10000, 512, null,
              List.of(new RelayWorkerContract.PeerView("127.0.0.1", UDP_PORT_A)))),
          List.of(new RelayWorkerContract.LinkView(7L, "u", true, true, true, true)),
          List.of());
      assertTrue(stA.applyDesired(cfgA, now));
      assertTrue(stB.applyDesired(cfgB, now));
      exA.updateBindings(cfgA, Set.of("default"));
      exB.updateBindings(cfgB, Set.of("default"));

      EcmKey k1 = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(new byte[40]));
      EcmKey k2 = new EcmKey(0x80, 0x0005, 0, CAID, 0x55667788);
      // B detient k1 : une requete distante est SERVIE depuis l'index (jamais re-emise).
      exB.onLocalFeed(7L, "u", new CwReply(k1, 0x80, CW.clone(), "udp:u",
          "127.0.0.1", System.currentTimeMillis()));
      CwReply served = join(exA.consult(new com.bowman.cspng.domain.EcmRequest(
          k1, new byte[0], "default", "127.0.0.1", "test", System.currentTimeMillis(), null, 0L),
          Set.of("up1"), "u", 7L, 1L, 0, 3000L), 5000);
      assertNotNull(served);
      assertFalse(served.isEmpty());
      assertArrayEquals(CW, served.cw());
      // Laisse le trafic de l'echange k1 se poser (export onLocalFeed + serveRemote).
      Thread.sleep(300);
      // Cle inconnue : B ne repond pas ET n'emet rien (aucune amplification/boucle).
      long bPending = exB.stats().sentPending();
      long bEntries = exB.stats().sentEntries();
      CwReply missed = join(exA.consult(new com.bowman.cspng.domain.EcmRequest(
          k2, new byte[0], "default", "127.0.0.1", "test", System.currentTimeMillis(), null, 0L),
          Set.of("up1"), "u", 7L, 1L, 0, 400L), 3000);
      assertTrue(missed == null || missed.isEmpty(), "cle inconnue => aucune reponse");
      assertEquals(bPending, exB.stats().sentPending(),
          "B n'a jamais re-emis de requete (anti-boucle/anti-amplification)");
      assertEquals(bEntries, exB.stats().sentEntries(),
          "B n'a rien exporte pour une cle hors index");
    } finally {
      exA.failClosed();
      exB.failClosed();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Verification annonces de cartes CCcam cluster sur worker relais sans connecteur
  // ---------------------------------------------------------------------------------------------

  public record CccamClientResult(List<CcCardCodec.CcCard> announcedCards, List<byte[]> receivedCws, boolean sessionStayedOpen) {}

  private CccamClientResult runCccamClientWithCardCapture(int port, String user, String pass, byte[] body, int ecmCount, long timeoutMs) throws Exception {
    NetClient client = vertx.createNetClient(new NetClientOptions().setConnectTimeout(5000));
    CountDownLatch cwLatch = new CountDownLatch(ecmCount);
    CountDownLatch srvDataLatch = new CountDownLatch(1);
    java.util.List<byte[]> cws = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.List<CcCardCodec.CcCard> cards = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    java.util.concurrent.atomic.AtomicBoolean socketClosed = new java.util.concurrent.atomic.AtomicBoolean(false);
    try {
      client.connect(port, "127.0.0.1", ar -> {
        assertTrue(ar.succeeded());
        NetSocket sock = ar.result();
        sock.closeHandler(v -> socketClosed.set(true));
        Buffer[] acc = {Buffer.buffer()};
        CcCrypt cliDec = new CcCrypt();
        CcCrypt cliEnc = new CcCrypt();
        int[] step = {0};
        sock.handler(data -> {
          acc[0].appendBuffer(data);
          if (step[0] == 0 && acc[0].length() >= 16) {
            byte[] seed = acc[0].getBytes(0, 16);
            acc[0] = Buffer.buffer(acc[0].getBytes(16, acc[0].length()));
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
            System.arraycopy(user.getBytes(StandardCharsets.US_ASCII), 0, userBuf, 0, user.length());
            cliEnc.crypt(userBuf, 20, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(userBuf));
            byte[] pwd = pass.getBytes(StandardCharsets.US_ASCII);
            cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
            byte[] magic = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
            cliEnc.crypt(magic, 6, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(magic));
            step[0] = 1;
          }
          if (step[0] == 1 && acc[0].length() >= 20) {
            byte[] ack = acc[0].getBytes(0, 20);
            cliDec.crypt(ack, 20, CcConstants.DECRYPT);
            acc[0] = Buffer.buffer(acc[0].getBytes(20, acc[0].length()));
            byte[] cliData = CcCardCodec.buildCliData(user, CLIENT_NODE, "2.3.2", "4000");
            byte[] cliFrame = CcFrameCodec.encode(CcConstants.MSG_CLI_DATA, cliData);
            cliEnc.crypt(cliFrame, cliFrame.length, CcConstants.ENCRYPT);
            sock.write(Buffer.buffer(cliFrame));
            step[0] = 2;
          }
          if (step[0] == 2) {
            while (acc[0].length() >= 4) {
              byte[] hdr = acc[0].getBytes(0, 4);
              cliDec.crypt(hdr, 4, CcConstants.DECRYPT);
              int[] decoded = CcFrameCodec.decodeHeader(hdr);
              int total = 4 + decoded[2];
              if (acc[0].length() < total) return;
              byte[] payload = acc[0].getBytes(4, total);
              cliDec.crypt(payload, decoded[2], CcConstants.DECRYPT);
              acc[0] = Buffer.buffer(acc[0].getBytes(total, acc[0].length()));
              if (decoded[1] == CcConstants.MSG_SRV_DATA) {
                srvDataLatch.countDown();
                if (body != null && ecmCount > 0) {
                  sendCccamEcm(sock, cliEnc, body, 1);
                }
              } else if (decoded[1] == CcConstants.MSG_NEW_CARD) {
                CcCardCodec.CcCard card = CcCardCodec.parseNewCard(payload);
                cards.add(card);
              } else if (decoded[1] == CcConstants.MSG_CW_ECM && payload.length >= 16) {
                byte[] scrambled = Arrays.copyOf(payload, 16);
                byte[] clear = scrambled.clone();
                CcCwCrypt.cwCrypt(clear, CLIENT_NODE, CARD_ID);
                byte[] sync = clear.clone();
                cliDec.crypt(sync, 16, CcConstants.ENCRYPT);
                cws.add(clear);
                cwLatch.countDown();
                if (cws.size() < ecmCount && body != null) {
                  sendCccamEcm(sock, cliEnc, body, cws.size() + 1);
                }
              }
            }
          }
        });
      });
      assertTrue(srvDataLatch.await(timeoutMs, TimeUnit.MILLISECONDS), "Handshake CCcam réussi");
      if (ecmCount > 0) {
        assertTrue(cwLatch.await(timeoutMs, TimeUnit.MILLISECONDS), "CWs reçues");
      }
      Thread.sleep(250);
      return new CccamClientResult(List.copyOf(cards), List.copyOf(cws), !socketClosed.get());
    } finally {
      client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("CCcam réel sur worker sans connecteur : client reçoit MSG_NEW_CARD du catalogue, session RESTE ouverte, ECM résolu UDP")
  void clusterCardsAnnouncedToCccamClientSessionPersistsAndEcmResolved() throws Exception {
    startPeerA(true);
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", CAID, 0, "published", (long) CARD_ID),
        new RelayWorkerContract.CatalogView("default", 0x0500, 0x032830, "published", 0x00DDEEFFL)
    );
    startWorkerB(1L, true, true, catalog);

    byte[] ccBody = new byte[40];
    Arrays.fill(ccBody, (byte) 1);
    EcmKey keyCc = new EcmKey(0x80, SRVID, 0, CAID, ClusterUdpCodec.ecmHash(ccBody));
    seedPeer(keyCc);

    // Client 'alice' (sans restriction allowedConnectors)
    CccamClientResult result = runCccamClientWithCardCapture(CCCAM_PORT, "alice", "secret1", ccBody, 1, 10_000);

    // 1. Annonces de cartes reçues et parsées avec succès
    assertEquals(2, result.announcedCards().size(), "Exactement 2 cartes cluster doivent être annoncées");
    CcCardCodec.CcCard card1 = result.announcedCards().get(0);
    assertEquals(CARD_ID, card1.cardId);
    assertEquals(CAID, card1.caid);
    assertEquals(2, card1.hop);
    assertEquals(0, card1.reshare);

    CcCardCodec.CcCard card2 = result.announcedCards().get(1);
    assertEquals(0x00DDEEFF, card2.cardId);
    assertEquals(0x0500, card2.caid);
    assertEquals(2, card2.hop);
    assertEquals(0, card2.reshare);

    // 2. Session restée établie
    assertTrue(result.sessionStayedOpen(), "La session CCcam ne doit pas tomber (aucun drop)");

    // 3. ECM résolu et CW correcte
    assertEquals(1, result.receivedCws().size());
    assertArrayEquals(CW, result.receivedCws().get(0), "CW reçue via UDP consult");
  }

  @Test
  @DisplayName("CCcam user avec allowedConnectors restrictif sans 'cluster' : reçoit 0 cartes cluster, session reste ouverte")
  void cccamClientWithRestrictedAllowedConnectorsReceivesZeroClusterCards() throws Exception {
    startPeerA(true);
    var catalog = List.of(
        new RelayWorkerContract.CatalogView("default", CAID, 0, "published", (long) CARD_ID)
    );
    startWorkerB(1L, true, true, catalog);

    // Client 'charlie' a allowedConnectors = ["dummy_local"] (sans "cluster")
    CccamClientResult result = runCccamClientWithCardCapture(CCCAM_PORT, "charlie", "secret3", null, 0, 5_000);

    // 0 cartes annoncées car 'cluster' n'est pas dans son allowedConnectors
    assertEquals(0, result.announcedCards().size(), "User restreint sans 'cluster' ne reçoit aucune carte cluster");
    assertTrue(result.sessionStayedOpen(), "Session reste établie même avec 0 cartes annoncées");
  }
}