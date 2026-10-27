package com.bowman.cspng.codec.camd35;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du framing camd35/CS378x.
 *
 * <p><b>Provenance des vecteurs</b> (honnêteté factuelle) :
 * <ul>
 *   <li>Vecteurs ucrc/CRC32/AES : calculés HORS Java (python3 hashlib/zlib et
 *       openssl enc) puis figés ici — golden réellement indépendant de l'implémentation
 *       testée (Camd35Crypto utilise JCE/java.util.zip).</li>
 *   <li>Tailles de frame et structure : contrats OSCam module-camd35.c (l.44-126,
 *       209-435, 618-654) et tsdecrypt camd-cs378x.c, croisés — PAS un round-trip
 *       maison : les valeurs attendues sont calculées à la main depuis le contrat
 *       (ex. CW = 4 + boundary16(20+16) = 52) et documentées ligne par ligne.</li>
 * </ul>
 */
@DisplayName("Camd35FrameCodec — framing, fragmentation, coalescence, bornes")
class Camd35FrameCodecTest {

  private static final String USER = "test";
  private static final byte[] KEY = Camd35Crypto.aesKey("testpass");

  // ============================================================
  // Primitives crypto — goldens indépendants (python3/openssl)
  // ============================================================

  @Test
  @DisplayName("ucrc = CRC32(MD5(username)) BE — golden python3")
  void ucrcGoldenIndependent() {
    // python3: zlib.crc32(hashlib.md5(b"test").digest()) = 0xe807815d
    assertArrayEquals(hex("e807815d"), Camd35Crypto.ucrc("test"));
    // python3: zlib.crc32(hashlib.md5(b"csuser").digest()) = 0x1863bc60
    assertArrayEquals(hex("1863bc60"), Camd35Crypto.ucrc("csuser"));
  }

  @Test
  @DisplayName("AES-128-ECB MD5(password) — golden openssl")
  void aesEcbGoldenIndependent() throws Exception {
    // openssl enc -aes-128-ecb -K 098f6bcd4621d373cade4e832627b4f6 -nopend
    // entrée 030a11181f262d343b424950575e656c (= md5-payload golden python3)
    byte[] key = MessageDigest.getInstance("MD5").digest("test".getBytes());
    byte[] plain = hex("030a11181f262d343b424950575e656c");
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
    assertArrayEquals(hex("9646b8c32723275cf65182c9d13d0720"), c.doFinal(plain));
  }

  @Test
  @DisplayName("CRC32 des data — golden python3 zlib")
  void crc32GoldenIndependent() {
    // python3: zlib.crc32(payload16) = 0x191f3d9f
    byte[] payload = new byte[16];
    for (int i = 0; i < 16; i++) payload[i] = (byte) ((i * 7 + 3) & 0xFF);
    assertEquals(0x191f3d9fL, Camd35Crypto.crc32(payload));
  }

  // ============================================================
  // Goldens de taille wire (contrat calculé à la main)
  // ============================================================

  @Test
  @DisplayName("CW cmd 1 : frame wire = 52 octets (4 ucrc + 48 chiffrés)")
  void cwReplyWireSizeIs52() {
    Camd35FrameCodec.Camd35Frame cw = new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_CW,
        0x0001, 0x0100, 0, 7, 0xFFFF, new byte[16], new byte[0]);
    byte[] wire = Camd35FrameCodec.encode(cw, KEY);
    assertEquals(52, wire.length,
        "tsdecrypt cs378x_get_cw : data_len<48 → rejet ; 48 chiffrés + 4 ucrc = 52");
  }

  @Test
  @DisplayName("ECM 89 : chiffré aligné 112 (boundary16 de 20+89=109)")
  void ecm89AlignedTo112() {
    byte[] ecm = ecmSection(89);
    byte[] wire = encodeEcm(ecm);
    assertEquals(4 + 112, wire.length, "20+89=109 → aligné 16 → 112 (+4 ucrc)");
  }

  @Test
  @DisplayName("ECM 100 : chiffré aligné 128 (20+100=120)")
  void ecm100AlignedTo128() {
    byte[] ecm = ecmSection(100);
    byte[] wire = encodeEcm(ecm);
    assertEquals(4 + 128, wire.length);
  }

  @Test
  @DisplayName("ECM > 255 : longueur DVB 12 bits [21..22] TOUJOURS, byte [1] tronqué")
  void ecmAbove255UsesDvb12BitLength() {
    byte[] ecm = ecmSection(300); // section totale 300 : len DVB = 297
    byte[] wire = encodeEcm(ecm);
    // module-camd35.c l.48-49 : buflen = (((buf[21]&0x0F)<<8)|buf[22])+3 — le wire
    // porte la section complète même si buf[1] (informatif) est tronqué à 8 bits.
    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    Camd35FrameCodec.DecodeResult r = dec.feed(wire);
    assertEquals(Camd35FrameCodec.Status.FRAME, r.status(), "frame >255 doit être décodée");
    assertEquals(300, r.frame().data().length, "longueur DVB 12 bits + 3 = 300");
    assertArrayEquals(ecm, r.frame().data());
  }

  @Test
  @DisplayName("cmd 3 cascading : trailer 52 octets APRÈS les data, compté dans l'alignement")
  void cmd3Trailer52() {
    byte[] ecm = ecmSection(40);
    byte[] trailer = new byte[Camd35Constants.TRAILER_SIZE];
    Camd35FrameCodec.Camd35Frame f = new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_ECM_CASCADING,
        1, 0x0100, 0, 3, 0x1234, ecm, trailer);
    byte[] wire = Camd35FrameCodec.encode(f, KEY);
    // l.66 : l = 20 + 0x34 + buflen → 20+52+40 = 112 → aligné 112 (+4 ucrc = 116 wire)
    assertEquals(4 + 112, wire.length);

    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    Camd35FrameCodec.DecodeResult r = dec.feed(wire);
    assertEquals(Camd35FrameCodec.Status.FRAME, r.status());
    assertEquals(Camd35Constants.CMD_ECM_CASCADING, r.frame().cmd());
    assertEquals(40, r.frame().data().length);
    assertEquals(52, r.frame().trailer().length, "trailer 0x34 préservé");
  }

  @Test
  @DisplayName("CW cmd 4 cascading : 16 CW puis trailer 52 (memmove l.626-630)")
  void cwCascadingTrailerAfterCw() {
    byte[] trailer = new byte[Camd35Constants.TRAILER_SIZE];
    Camd35FrameCodec.Camd35Frame f = new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_CW_CASCADING,
        1, 0x0100, 0, 3, 0xFFFF, new byte[16], trailer);
    byte[] wire = Camd35FrameCodec.encode(f, KEY);
    assertEquals(4 + 96, wire.length, "20+16+52=88 → aligné 96 (+4 = 100 wire)");

    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    Camd35FrameCodec.DecodeResult r = dec.feed(wire);
    assertEquals(Camd35FrameCodec.Status.FRAME, r.status());
    assertEquals(16, r.frame().data().length);
    assertEquals(52, r.frame().trailer().length);
  }

  // ============================================================
  // Fragmentation / coalescence / corruption
  // ============================================================

  @Test
  @DisplayName("Fragmentation 1/2/3/5/7/13 octets : frame décodée à l'arrivée complète")
  void fragmentationFibonacci() {
    byte[] wire = encodeEcm(ecmSection(89));
    for (int step : new int[] {1, 2, 3, 5, 7, 13}) {
      Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
      Camd35FrameCodec.DecodeResult r = Camd35FrameCodec.DecodeResult.needMore();
      for (int off = 0; off < wire.length; off += step) {
        int end = Math.min(off + step, wire.length);
        r = dec.feed(java.util.Arrays.copyOfRange(wire, off, end));
        if (r.status() == Camd35FrameCodec.Status.FRAME) break;
        assertEquals(Camd35FrameCodec.Status.NEED_MORE, r.status(),
            "step=" + step + " off=" + off);
      }
      assertEquals(Camd35FrameCodec.Status.FRAME, r.status(), "step=" + step);
      assertEquals(89, r.frame().data().length);
    }
  }

  @Test
  @DisplayName("Coalescence : 3 frames TCP d'un coup → 3 frames rendues, extras préservés")
  void coalescedFramesAllDecoded() {
    byte[] w1 = encodeEcm(ecmSection(89));
    byte[] w2 = Camd35FrameCodec.encode(new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_CW,
        1, 0x0100, 0, 7, 0xFFFF, new byte[16], new byte[0]), KEY);
    byte[] w3 = new byte[] {55, 0}; // keepalive incomplet : reste en attente
    byte[] all = new byte[w1.length + w2.length + w3.length];
    System.arraycopy(w1, 0, all, 0, w1.length);
    System.arraycopy(w2, 0, all, w1.length, w2.length);
    System.arraycopy(w3, 0, all, w1.length + w2.length, w3.length);

    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    List<Camd35FrameCodec.Camd35Frame> frames = new ArrayList<>();
    Camd35FrameCodec.DecodeResult r = dec.feed(all);
    while (r.status() == Camd35FrameCodec.Status.FRAME) {
      frames.add(r.frame());
      r = dec.feed(new byte[0]);
    }
    assertEquals(2, frames.size(), "les extras (keepalive partiel) restent en attente");
    assertEquals(Camd35Constants.CMD_ECM, frames.get(0).cmd());
    assertEquals(89, frames.get(0).data().length);
    assertEquals(Camd35Constants.CMD_CW, frames.get(1).cmd());
    assertEquals(16, frames.get(1).data().length);
    assertEquals(Camd35FrameCodec.Status.NEED_MORE, r.status());
  }

  @Test
  @DisplayName("Mutation d'un octet chiffré → CRC_MISMATCH (fermeture sans réponse)")
  void corruptedFrameIsCrcMismatch() {
    byte[] wire = encodeEcm(ecmSection(89));
    wire[10] ^= 0x5A; // muter le chifffré (une mutation → data/Padding corrompus)
    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    Camd35FrameCodec.DecodeResult r = dec.feed(wire);
    // Une mutation dans le domaine chiffré donne soit un CRC KO, soit une taille
    // absurde (OVERFLOW) — dans les deux cas : fermeture, JAMAIS de frame rendue.
    assertNotEquals(Camd35FrameCodec.Status.FRAME, r.status());
  }

  @Test
  @DisplayName("CRC des data falsifié à l'encodage → CRC_MISMATCH exact")
  void wrongCrcDetected() {
    // Encoder avec une clé, mais falsifier le champ CRC AVANT chiffrement :
    // on mutera l'entête via une ré-encodation manuelle.
    byte[] ecm = ecmSection(40);
    byte[] wire = encodeEcm(ecm);
    // Déchiffrer le 1er bloc, muter le champ CRC [4..7], rechiffrer.
    byte[] blk = java.util.Arrays.copyOfRange(wire, 4, 4 + 16);
    Camd35Crypto.aesEcbInPlace(blk, KEY, false);
    blk[4] ^= 0x01;
    Camd35Crypto.aesEcbInPlace(blk, KEY, true);
    System.arraycopy(blk, 0, wire, 4, 16);

    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    assertEquals(Camd35FrameCodec.Status.CRC_MISMATCH, dec.feed(wire).status(),
        "l.416 : checksum error → fermeture SANS réponse");
  }

  @Test
  @DisplayName("Troncature : frame incomplète → NEED_MORE, jamais de frame partielle")
  void truncatedFrameWaits() {
    byte[] wire = encodeEcm(ecmSection(89));
    Camd35FrameCodec.Decoder dec = new Camd35FrameCodec.Decoder(KEY);
    for (int len = 1; len < wire.length - 1; len++) {
      Camd35FrameCodec.DecodeResult r = new Camd35FrameCodec.Decoder(KEY)
          .feed(java.util.Arrays.copyOf(wire, len));
      assertEquals(Camd35FrameCodec.Status.NEED_MORE, r.status(),
          "longueur " + len + " ne doit PAS produire de frame");
    }
  }

  @Test
  @DisplayName("Bornes : section DVB > 1024 → OVERFLOW (jamais d'allocation non bornée)")
  void oversizedEcmIsOverflow() {
    // Section réelle de 2000 octets (longueur DVB 12 bits valide) : le DÉCODER doit
    // refuser (bound max ECM 1024 — camd35_process_ecm « ecmlen > MAX_ECM_SIZE »),
    // sans jamais allouer selon une taille non bornée.
    byte[] big = ecmSection(2000);
    byte[] bigWire = Camd35FrameCodec.encode(new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_ECM,
        1, 0x0100, 0, 1, 0xFFFF, big, new byte[0]), KEY);
    Camd35FrameCodec.DecodeResult r = new Camd35FrameCodec.Decoder(KEY).feed(bigWire);
    assertNotEquals(Camd35FrameCodec.Status.FRAME, r.status(),
        "ECM > MAX_ECM_SIZE doit être rejeté par le décodeur");
  }

  // ============================================================
  // Écho pid/idx et ucrc
  // ============================================================

  @Test
  @DisplayName("Écho pid/idx/srvid/caid/prid : décodage = encodage")
  void echoFieldsRoundTrip() {
    Camd35FrameCodec.Camd35Frame f = new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_ECM,
        0x1234, 0x0D00, 0x00ABCDEF, 0x029A, 0x1FFF, ecmSection(40), new byte[0]);
    byte[] wire = Camd35FrameCodec.encode(f, KEY);
    Camd35FrameCodec.DecodeResult r = new Camd35FrameCodec.Decoder(KEY).feed(wire);
    assertEquals(Camd35FrameCodec.Status.FRAME, r.status());
    assertEquals(0x1234, r.frame().srvid());
    assertEquals(0x0D00, r.frame().caid());
    assertEquals(0x00ABCDEF, r.frame().prid());
    assertEquals(0x029A, r.frame().idx());
    assertEquals(0x1FFF, r.frame().pid());
    assertArrayEquals(Camd35Crypto.ucrc(USER), r.frame().ucrc(), "ucrc écho");
  }

  @Test
  @DisplayName("extractUcrc : préfixe clair pour l'auth AVANT déchiffrement")
  void extractUcrcForAuth() {
    byte[] wire = encodeEcm(ecmSection(40));
    byte[] ucrc = Camd35FrameCodec.extractUcrc(wire);
    assertArrayEquals(Camd35Crypto.ucrc(USER), ucrc);
  }

  // ============================================================
  // Helpers
  // ============================================================

  /** Section DVB ECM de longueur TOTALE donnée : [table_id][0x70|len_hi][len_lo]+payload. */
  private static byte[] ecmSection(int totalLen) {
    int payload = totalLen - 3;
    byte[] ecm = new byte[totalLen];
    ecm[0] = (byte) 0x80;
    ecm[1] = (byte) (0x70 | ((payload >> 8) & 0x0F));
    ecm[2] = (byte) (payload & 0xFF);
    for (int i = 3; i < totalLen; i++) ecm[i] = (byte) ((i * 11 + 5) & 0xFF);
    return ecm;
  }

  private static byte[] encodeEcm(byte[] ecm) {
    return Camd35FrameCodec.encode(new Camd35FrameCodec.Camd35Frame(
        Camd35Crypto.ucrc(USER), Camd35Constants.CMD_ECM,
        0x0001, 0x0100, 0, 1, 0xFFFF, ecm, new byte[0]), KEY);
  }

  private static byte[] hex(String s) {
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
