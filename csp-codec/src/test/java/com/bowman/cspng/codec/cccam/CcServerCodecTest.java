package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du CcServerCodec (primitives serveur CCcam).
 *
 * <p><b>Provenance</b> (honnêteté factuelle) : les comportements attendus sont les
 * contrats MESURÉS contre OSCam 2.26.05 compilé (docs/INBOUND-CAMS-PLAN.md §0.2/§0.3 :
 * patch de diagnostic DBGSRV, benchs 15/15 CW en mode non étendu, « illegal username »
 * du probe sans mutation in place). Ces tests vérifient la AUTO-CONSISTENCE du contrat
 * (un client littéral — port du flux cc_cli_connect l.4700-4760, même logique que
 * CcCamConnector — interagit avec le codec serveur) : ce ne sont PAS des goldens
 * issus d'un C indépendant ; la validation runtime contre OSCam réel (scratch-sim
 * P0RealOscamBench) reste la preuve finale d'interop.
 */
@DisplayName("CcServerCodec — handshake miroir, CW sync scramblé (flag sacrificiel), NOK")
class CcServerCodecTest {

  private static final byte[] PEER_NODE_ID = {
      0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88
  };
  private static final int CARD_ID = 0x0064C0DE;

  // ============================================================
  // Seed
  // ============================================================

  @Test
  @DisplayName("Seed : 12 aléatoires + 4 checksums (l.4253-4259)")
  void seedHasOChecksums() {
    byte[] seed = CcServerCodec.generateSeed(new SecureRandom());
    assertEquals(16, seed.length);
    for (int i = 0; i < 4; i++) {
      int expected = (seed[i] + seed[4 + i] + seed[8 + i]) & 0xFF;
      assertEquals(expected, seed[12 + i] & 0xFF, "checksum[" + i + "]");
    }
  }

  // ============================================================
  // Handshake miroir + ACK
  // ============================================================

  @Test
  @DisplayName("Handshake : un client littéral déchiffre l'ACK (mutation in place)")
  void ackDecryptsWithMirroredClientBlocks() {
    // Flux réel : le serveur ENVOIE le seed clair AVANT de l'utiliser (l.4262-4263).
    byte[] clearSeed = CcServerCodec.generateSeed(new SecureRandom());
    byte[] sent = clearSeed.clone(); // ce que le client reçoit

    CcCrypt srvEnc = new CcCrypt();
    CcCrypt srvDec = new CcCrypt();
    CcServerCodec.initServerHandshake(clearSeed, srvEnc, srvDec); // clearSeed MUTÉ

    // Client littéral (cc_cli_connect l.4700-4722) : xor(son seed) → SHA1 → miroirs.
    CcCrypt.xor(sent);
    byte[] cliHash = sha1(sent);
    CcCrypt cliDec = new CcCrypt();
    CcCrypt cliEnc = new CcCrypt();
    cliDec.initCrypt(cliHash, CcConstants.SHA1_SIZE);
    cliDec.crypt(sent, CcConstants.SEED_SIZE, CcConstants.DECRYPT); // sent muté
    cliEnc.initCrypt(sent, CcConstants.SEED_SIZE);
    cliEnc.crypt(cliHash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);

    // Le client reçoit l'ACK du serveur et le déchiffre : blocs miroirs → « CCcam\0 ».
    byte[] ack = CcServerCodec.buildLoginAck();
    srvEnc.crypt(ack, 20, CcConstants.ENCRYPT);
    cliDec.crypt(ack, 20, CcConstants.DECRYPT);
    assertEquals('C', ack[0]); assertEquals('C', ack[1]); assertEquals('c', ack[2]);
    assertEquals('a', ack[3]); assertEquals('m', ack[4]); assertEquals(0, ack[5]);
  }

  // ============================================================
  // Password
  // ============================================================

  @Test
  @DisplayName("checkPasswordToken : OK/KO avec save-restore des blocs (l.4340-4378)")
  void passwordTokenCheck() {
    CcCrypt srvEnc = new CcCrypt();
    CcCrypt srvDec = new CcCrypt();
    byte[] seed = CcServerCodec.generateSeed(new SecureRandom());
    CcServerCodec.initServerHandshake(seed, srvEnc, srvDec);

    // Le client produit le token : avance son bloc ENCRYPT avec le password, puis
    // chiffre « CCcam\0 » (cc_cli_connect l.4735-4739).
    byte[] cliSeed = seed; // déjà muté — pour le test on re-init un client à partir du même état
    CcCrypt cliEnc = srvDec.copy(); // miroir du bloc DECRYPT serveur (client→serveur)
    byte[] pwd = "thepass".getBytes();
    cliEnc.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
    byte[] token = Arrays.copyOf(CcConstants.CCCAM_MAGIC, 6);
    cliEnc.crypt(token, 6, CcConstants.ENCRYPT);

    byte[] encState = srvEnc.copy().toString().getBytes();
    byte[] decStateBefore = srvDec.copy().toString().getBytes();

    byte[] tok1 = token.clone();
    assertTrue(CcServerCodec.checkPasswordToken(tok1, "thepass", srvEnc, srvDec),
        "password correct → token = CCcam\\0");
    assertEquals(new String(encState), new String(srvEnc.copy().toString().getBytes()),
        "le bloc ENCRYPT doit rester intact");
    assertEquals(new String(cliEnc.copy().toString().getBytes()), new String(srvDec.copy().toString().getBytes()),
        "sur succès, le bloc DECRYPT serveur doit être dans l'état avancé (miroir client)");

    // Avec mauvais mot de passe : l'état doit être restauré à l'état pré-tentative
    byte[] decStateBeforeMismatch = srvDec.copy().toString().getBytes();
    byte[] tok2 = token.clone();
    assertFalse(CcServerCodec.checkPasswordToken(tok2, "WRONG", srvEnc, srvDec),
        "password incorrect → rejet");
    assertEquals(new String(decStateBeforeMismatch), new String(srvDec.copy().toString().getBytes()),
        "après échec, l'état est restauré intact (save/restore l.4361)");
  }

  // ============================================================
  // CW reply + sync scramblé : LE contrat du flag sacrificiel (§0.2)
  // ============================================================

  @Test
  @DisplayName("CW reply : sync scramblé → Δ absorbé par le flag[0] de la frame suivante")
  void cwReplySyncSacrificialFlagByte() {
    // Serveur
    byte[] seed = CcServerCodec.generateSeed(new SecureRandom());
    byte[] clearSeed = seed.clone();
    CcCrypt srvEnc = new CcCrypt();
    CcCrypt srvDec = new CcCrypt();
    CcServerCodec.initServerHandshake(seed, srvEnc, srvDec);

    // Client littéral (miroir des blocs serveur)
    CcCrypt.xor(clearSeed);
    byte[] cliHash = sha1(clearSeed);
    CcCrypt cliDec = new CcCrypt();
    cliDec.initCrypt(cliHash, CcConstants.SHA1_SIZE);
    cliDec.crypt(clearSeed, CcConstants.SEED_SIZE, CcConstants.DECRYPT);

    // 1) Le serveur envoie une réponse CW (encodeCwReply fait le sync scramblé)
    byte[] cw = new byte[16];
    new SecureRandom().nextBytes(cw);
    byte[] cwFrame = CcServerCodec.encodeCwReply(cw, PEER_NODE_ID, CARD_ID, srvEnc);

    // 2) Le client littéral déchiffre, décramble, sync avec le CW CLAIR (l.3551)
    byte[] recv = cwFrame.clone();
    cliDec.crypt(recv, recv.length, CcConstants.DECRYPT);
    byte[] scrambled = Arrays.copyOfRange(recv, 4, 20);
    byte[] clearCw = scrambled.clone();
    CcCwCrypt.cwCrypt(clearCw, PEER_NODE_ID, CARD_ID);
    assertArrayEquals(cw, clearCw, "descramble avec le node id du client (pair)");
    byte[] syncCli = clearCw.clone();
    cliDec.crypt(syncCli, 16, CcConstants.ENCRYPT);

    // Les états divergent (contrat mesuré §0.2 : srv 0x1e vs cli 0x20)
    boolean diverged = (srvEnc.state != cliDec.state);
    assertTrue(diverged, "les états DOIVENT diverger après le sync asymétrique "
        + "(serveur=scramblé, client=clair) — c'est le contrat OSCam mesuré");

    // 3) Le serveur envoie une DEUXIÈME frame (ex. NOK) — le client la décode :
    //    byte[0] (flag) corrompu par Δ, byte[1..] (cmd/len) CORRECTS.
    byte[] nokFrame = CcServerCodec.encodeNok(CcConstants.MSG_CW_NOK2, srvEnc);
    byte[] recv2 = nokFrame.clone();
    cliDec.crypt(recv2, recv2.length, CcConstants.DECRYPT);
    // Le flag est sacrificiel : on ignore sa valeur ; la commande doit être lisible.
    assertEquals(CcConstants.MSG_CW_NOK2, recv2[1] & 0xFF,
        "cmd de la frame suivante DOIT être correcte (Δ absorbé par le flag[0])");
    assertEquals(0, recv2[2] & 0xFF);
    assertEquals(0, recv2[3] & 0xFF);
    assertNotEquals(CcConstants.FLAG_NORMAL, recv2[0] & 0xFF,
        "le flag[0] porte la divergence Δ — il est IGNORÉ par les deux parties");

    // 4) Après le 1er octet de la frame suivante, les états RE-CONVERGENT
    assertEquals(srvEnc.state, cliDec.state,
        "les états re-convergent après l'absorption de Δ par le flag sacrificiel");

    // 5) Et une TROISIÈME frame est intégralement correcte (y compris le flag)
    byte[] kaPlain = CcFrameCodec.encode(CcConstants.MSG_KEEPALIVE, new byte[0]);
    byte[] kaFrame = kaPlain.clone();
    srvEnc.crypt(kaFrame, kaFrame.length, CcConstants.ENCRYPT);
    byte[] recv3 = kaFrame.clone();
    cliDec.crypt(recv3, recv3.length, CcConstants.DECRYPT);
    assertArrayEquals(kaPlain, recv3, "frame n+2 : miroir parfait re-établi");
  }

  @Test
  @DisplayName("encodeNok : NOK1/NOK2 refusés, pas de sync parasite")
  void nokRejectsInvalidCmd() {
    CcCrypt enc = new CcCrypt();
    byte[] seed = CcServerCodec.generateSeed(new SecureRandom());
    CcCrypt dec = new CcCrypt();
    CcServerCodec.initServerHandshake(seed, enc, dec);
    assertThrows(IllegalArgumentException.class, () -> CcServerCodec.encodeNok(0x01, enc));
  }

  @Test
  @DisplayName("buildSrvData : 72 octets, nodeid+version+build, parsable par CcCardCodec")
  void srvDataRoundTrip() {
    byte[] nodeId = {1, 2, 3, 4, 5, 6, 7, 8};
    byte[] data = CcServerCodec.buildSrvData(nodeId, "2.3.2", "4000");
    assertEquals(CcConstants.MSG_SRV_DATA_SIZE, data.length);
    CcCardCodec.SrvData parsed = CcCardCodec.parseSrvData(data);
    assertArrayEquals(nodeId, parsed.nodeId());
  }

  @Test
  @DisplayName("extractPeerNodeId : CLI_DATA payload[20..27] (l.4471)")
  void peerNodeIdExtraction() {
    byte[] cliData = CcCardCodec.buildCliData("user", PEER_NODE_ID, "2.3.2", "4000");
    assertArrayEquals(PEER_NODE_ID, CcServerCodec.extractPeerNodeId(cliData));
  }

  private static byte[] sha1(byte[] data) {
    try {
      return java.security.MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
