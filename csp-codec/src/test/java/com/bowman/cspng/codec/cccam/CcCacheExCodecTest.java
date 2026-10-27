package com.bowman.cspng.codec.cccam;

import com.bowman.cspng.codec.cluster.ClusterUdpCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires exhaustifs pour {@link CcCacheExCodec}.
 *
 * <h3>Couverture</h3>
 * <ul>
 *   <li>PUSH round-trip (1/3 nodes, avec/sans lg flag)</li>
 *   <li>PUSH size LE = {@code 24 00} pour 36</li>
 *   <li>PUSH size invalide → rejet</li>
 *   <li>Hash LE : vecteur connu → 4 octets wire exacts</li>
 *   <li>Hash croisé avec {@code ClusterUdpCodec.ecmHash} sur ECM−3</li>
 *   <li>CW inchangée (pas de cwCrypt)</li>
 *   <li>FILTER 0/1/30 entrées, 482 o strict</li>
 *   <li>FILTER taille invalide → rejet</li>
 *   <li>rc 0 et 100 acceptés</li>
 *   <li>isPushValid / isAllZeros</li>
 *   <li>Feature exchange round-trip</li>
 *   <li>containsNode</li>
 * </ul>
 */
@DisplayName("CcCacheExCodec — tests unitaires")
class CcCacheExCodecTest {

  // ============================================================
  // PUSH — round-trip
  // ============================================================

  @Nested
  @DisplayName("PUSH round-trip")
  class PushRoundTrip {

    @Test
    @DisplayName("PUSH 1 node sans lg flag en RX (lg uniquement en TX)")
    void pushOneNodeNoLgRx() {
      byte[] cw = cwFixture();
      byte[] nodeId = nodeIdFixture();
      byte[] ecmd5 = new byte[16]; // zéro (MVP sans MD5)
      List<byte[]> nodes = List.of(nodeId);

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0x12345678, 0x0001,
          CcConstants.E_FOUND, 0x80,
          ecmd5, 0x42ABCDEF, cw, nodes);

      // L'encodage inclut toujours le lg flag (1 octet final = 0xFF)
      int expectedLen = 57 + 8 * nodes.size() + 1;
      assertEquals(expectedLen, encoded.length,
          "Encoded PUSH should have lg flag");

      // Le dernier octet est 0xFF (lg flag)
      assertEquals(CcConstants.CACHEEX_FLAG_LG, encoded[encoded.length - 1] & 0xFF,
          "Last byte should be lg flag 0xFF");

      // Décoder
      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);

      assertEquals(0x0D00, decoded.caid());
      assertEquals(0x12345678, decoded.prid());
      assertEquals(0x0001, decoded.srvid());
      assertEquals(CcConstants.E_FOUND, decoded.rc());
      assertEquals(0x80, decoded.oddEven());
      assertEquals(0x42ABCDEF, decoded.dataHash());
      assertArrayEquals(cw, decoded.cw());
      assertEquals(1, decoded.nodeIds().size());
      assertArrayEquals(nodeId, decoded.nodeIds().get(0));
      assertEquals(CcConstants.CACHEEX_FLAG_LG, decoded.lgFlag());
    }

    @Test
    @DisplayName("PUSH 3 nodes, lg flag présent en RX")
    void pushThreeNodes() {
      byte[] cw = cwFixture();
      byte[] node1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
      byte[] node2 = {0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18};
      byte[] node3 = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28};
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(node1, node2, node3);

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0500, 0, 0x1234,
          CcConstants.E_FOUND, 0x81,
          ecmd5, -1, cw, nodes);

      // 57 + 8*3 + 1 = 82
      assertEquals(82, encoded.length);

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);

      assertEquals(0x0500, decoded.caid());
      assertEquals(0x81, decoded.oddEven());
      assertEquals(-1, decoded.dataHash());
      assertEquals(3, decoded.nodeIds().size());
      assertArrayEquals(node1, decoded.nodeIds().get(0));
      assertArrayEquals(node2, decoded.nodeIds().get(1));
      assertArrayEquals(node3, decoded.nodeIds().get(2));
    }

    @Test
    @DisplayName("PUSH RX sans lg flag (taille 57+8N)")
    void pushRxWithoutLg() {
      // Construire manuellement un payload SANS lg flag
      byte[] cw = cwFixture();
      byte[] node1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
      List<byte[]> nodes = List.of(node1);

      // Encoder normalement (avec lg)
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          new byte[16], 0x42, cw, nodes);

      // Enlever le lg flag (dernier octet)
      byte[] withoutLg = new byte[encoded.length - 1];
      System.arraycopy(encoded, 0, withoutLg, 0, withoutLg.length);
      assertEquals(57 + 8, withoutLg.length); // 65

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(withoutLg);
      assertEquals(CcConstants.E_FOUND, decoded.rc());
      assertEquals(-1, decoded.lgFlag(), "lgFlag should be -1 (absent)");
      assertArrayEquals(cw, decoded.cw());
      assertArrayEquals(node1, decoded.nodeIds().get(0));
    }
  }

  // ============================================================
  // PUSH — validations
  // ============================================================

  @Nested
  @DisplayName("PUSH validations")
  class PushValidations {

    @Test
    @DisplayName("size LE = 24 00 pour 36 (vérification octets wire)")
    void sizeLeEncoding() {
      byte[] cw = cwFixture();
      byte[] nodeId = nodeIdFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeId);

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, 0, cw, nodes);

      // Offsets 12-13 : size u16 LE = 36 = 0x0024
      assertEquals(0x24, encoded[12] & 0xFF, "size low byte = 0x24");
      assertEquals(0x00, encoded[13] & 0xFF, "size high byte = 0x00");

      // Vérifier que size LE = 36 (0x0024 en LE → 0x2400 wire)
      int sizeWire = ((encoded[13] & 0xFF) << 8) | (encoded[12] & 0xFF);
      assertEquals(36, sizeWire);
    }

    @Test
    @DisplayName("size ≠ 36 → rejet")
    void sizeRejection() {
      byte[] cw = cwFixture();
      byte[] nodeId = nodeIdFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeId);

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, 0, cw, nodes);

      // Corrompre le size (offsets 12-13)
      encoded[12] = 0x25; // 37 au lieu de 36
      encoded[13] = 0x00;

      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parsePush(encoded),
          "PUSH with wrong size should be rejected");
    }

    @Test
    @DisplayName("rc 0 (E_FOUND) accepté")
    void rcEFound() {
      byte[] cw = cwFixture();
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          new byte[16], 0, cw, List.of(nodeIdFixture()));

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(CcConstants.E_FOUND, decoded.rc());
      assertTrue(CcCacheExCodec.isPushValid(decoded));
    }

    @Test
    @DisplayName("rc 100 (E_UNHANDLED) accepté comme E_FOUND (R1, miroir OSCam :1311)")
    void rcEUnhandled() {
      byte[] cw = cwFixture();
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_UNHANDLED, 0x80,
          new byte[16], 0, cw, List.of(nodeIdFixture()));

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(CcConstants.E_UNHANDLED, decoded.rc());
      assertTrue(CcCacheExCodec.isPushValid(decoded),
          "Push with rc=100 (E_UNHANDLED) must be valid like rc=0");
    }

    @Test
    @DisplayName("R1 vecteurs : rc 0/100 valides, autres rc rejetés")
    void rcVectors() {
      byte[] cw = cwFixture();
      for (int rc : new int[]{0, 100}) {
        byte[] encoded = CcCacheExCodec.encodePush(
            0x0D00, 0, 0x0001, rc, 0x80,
            new byte[16], 0, cw, List.of(nodeIdFixture()));
        assertTrue(CcCacheExCodec.isPushValid(CcCacheExCodec.parsePush(encoded)),
            "rc=" + rc + " must be valid");
      }
      for (int rc : new int[]{1, 2, 99, 101, 255}) {
        byte[] encoded = CcCacheExCodec.encodePush(
            0x0D00, 0, 0x0001, rc, 0x80,
            new byte[16], 0, cw, List.of(nodeIdFixture()));
        assertFalse(CcCacheExCodec.isPushValid(CcCacheExCodec.parsePush(encoded)),
            "rc=" + rc + " must be rejected");
      }
    }

    @Test
    @DisplayName("taille invalide (payload trop court) → rejet")
    void tooShortPayload() {
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parsePush(new byte[10]),
          "Payload < 57 should be rejected");
    }

    @Test
    @DisplayName("taille résiduelle invalide → rejet")
    void invalidTailLength() {
      byte[] cw = cwFixture();
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          new byte[16], 0, cw, List.of(nodeIdFixture()));
      // Le payload encode a nodeCount=1 + lg → 57+8+1=66
      // Créer un payload avec 3 octets de trop
      byte[] bad = new byte[encoded.length + 3];
      System.arraycopy(encoded, 0, bad, 0, encoded.length);

      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parsePush(bad));
    }
  }

  // ============================================================
  // PUSH — hash LITTLE-ENDIAN
  // ============================================================

  @Nested
  @DisplayName("PUSH hash LITTLE-ENDIAN")
  class PushHashLE {

    @Test
    @DisplayName("hash LE : dataHash connu → 4 octets wire exacts")
    void hashLeEncoding() {
      byte[] cw = cwFixture();
      byte[] nodeId = nodeIdFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeId);

      int dataHash = 0x12345678;
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, dataHash, cw, nodes);

      // Offsets 36-39 : csp_hash en LE
      // dataHash = 0x12345678 → LE wire = [0x78, 0x56, 0x34, 0x12]
      assertEquals(0x78, encoded[36] & 0xFF, "hash byte 0 (LSB)");
      assertEquals(0x56, encoded[37] & 0xFF, "hash byte 1");
      assertEquals(0x34, encoded[38] & 0xFF, "hash byte 2");
      assertEquals(0x12, encoded[39] & 0xFF, "hash byte 3 (MSB)");

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(dataHash, decoded.dataHash());
    }

    @Test
    @DisplayName("hash LE : valeur négative (−1 = 0xFFFFFFFF)")
    void hashLeNegative() {
      byte[] cw = cwFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeIdFixture());

      int dataHash = -1; // 0xFFFFFFFF
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, dataHash, cw, nodes);

      // En LE, -1 → [0xFF, 0xFF, 0xFF, 0xFF]
      assertEquals(0xFF, encoded[36] & 0xFF);
      assertEquals(0xFF, encoded[37] & 0xFF);
      assertEquals(0xFF, encoded[38] & 0xFF);
      assertEquals(0xFF, encoded[39] & 0xFF);

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(-1, decoded.dataHash());
    }

    @Test
    @DisplayName("hash LE : valeur zéro")
    void hashLeZero() {
      byte[] cw = cwFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeIdFixture());

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, 0, cw, nodes);

      assertEquals(0x00, encoded[36] & 0xFF);
      assertEquals(0x00, encoded[37] & 0xFF);
      assertEquals(0x00, encoded[38] & 0xFF);
      assertEquals(0x00, encoded[39] & 0xFF);

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(0, decoded.dataHash());
    }

    @Test
    @DisplayName("hash croisé avec ClusterUdpCodec.ecmHash sur ECM−3")
    void hashCrossCheckWithClusterCodec() {
      // ECM factice : table_id(1) + len(2) + payload(N)
      byte[] ecm = {
          0x00, 0x01, 0x02, // table_id + length
          0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
          (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
          (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00
      };

      // Hash sur ECM complet
      int hashFull = ClusterUdpCodec.ecmHash(ecm);

      // Hash sur ECM amputé des 3 premiers octets (convention OSCam)
      int hashMinus3 = ClusterUdpCodec.ecmHash(ecm, 3, ecm.length - 3);

      // Ce sont deux hash différents
      assertNotEquals(hashFull, hashMinus3,
          "Hash on full ECM ≠ hash on ECM−3");

      // Le codec utilise cacheExEcmHash qui délègue à ecmHash(ecm, 3, len-3)
      int codecHash = CcCacheExCodec.cacheExEcmHash(ecm);
      assertEquals(hashMinus3, codecHash,
          "cacheExEcmHash should match ClusterUdpCodec.ecmHash on ECM−3");
    }
  }

  // ============================================================
  // PUSH — CW inchangée
  // ============================================================

  @Nested
  @DisplayName("PUSH CW brute (pas de cwCrypt)")
  class PushCwRaw {

    @Test
    @DisplayName("CW round-trip inchangée (pas de CcCwCrypt)")
    void cwUnchanged() {
      byte[] cw = cwFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeIdFixture());

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, 0, cw, nodes);

      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertArrayEquals(cw, decoded.cw(),
          "CW should be unchanged — no CcCwCrypt applied");
    }

    @Test
    @DisplayName("CW offsets 40-55 dans le payload")
    void cwOffsets() {
      byte[] cw = cwFixture();
      byte[] ecmd5 = new byte[16];
      List<byte[]> nodes = List.of(nodeIdFixture());

      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          ecmd5, 0, cw, nodes);

      // Vérifier que la CW est aux offsets [40..55]
      for (int i = 0; i < 16; i++) {
        assertEquals(cw[i], encoded[40 + i],
            "CW byte at offset " + (40 + i));
      }
    }
  }

  // ============================================================
  // FILTER
  // ============================================================

  @Nested
  @DisplayName("FILTER")
  class FilterTests {

    @Test
    @DisplayName("FILTER accept-all (cevnum=0)")
    void filterAcceptAll() {
      byte[] filter = CcCacheExCodec.encodeFilterAcceptAll();
      assertEquals(CcConstants.MSG_CACHE_FILTER_SIZE, filter.length,
          "FILTER must be exactly 482 bytes");

      CcCacheExCodec.FilterRecord decoded = CcCacheExCodec.parseFilter(filter);
      assertEquals(0, decoded.cevnum());
      assertTrue(decoded.isAcceptAll());
    }

    @Test
    @DisplayName("FILTER 1 entrée (cevnum=1)")
    void filterOneEntry() {
      int[] caids = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] cmasks = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] prids = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] srvids = new int[CcConstants.CACHEEX_MAX_FILTERS];

      caids[0] = 0x0D00;
      cmasks[0] = 0xFFFF;
      prids[0] = 0x123456;
      srvids[0] = 0x0001;

      byte[] filter = CcCacheExCodec.encodeFilter(1, caids, cmasks, prids, srvids);
      assertEquals(CcConstants.MSG_CACHE_FILTER_SIZE, filter.length);

      CcCacheExCodec.FilterRecord decoded = CcCacheExCodec.parseFilter(filter);
      assertEquals(1, decoded.cevnum());
      assertEquals(0x0D00, decoded.caids()[0]);
      assertEquals(0xFFFF, decoded.cmasks()[0]);
      assertEquals(0x123456, decoded.prids()[0]);
      assertEquals(0x0001, decoded.srvids()[0]);
    }

    @Test
    @DisplayName("FILTER 30 entrées round-trip")
    void filterThirtyEntries() {
      int[] caids = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] cmasks = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] prids = new int[CcConstants.CACHEEX_MAX_FILTERS];
      int[] srvids = new int[CcConstants.CACHEEX_MAX_FILTERS];

      for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
        caids[i] = 0x0100 + i;
        cmasks[i] = 0xFF00;
        prids[i] = i;
        srvids[i] = 0x1000 + i;
      }

      byte[] filter = CcCacheExCodec.encodeFilter(CcConstants.CACHEEX_MAX_FILTERS,
          caids, cmasks, prids, srvids);
      assertEquals(CcConstants.MSG_CACHE_FILTER_SIZE, filter.length);

      CcCacheExCodec.FilterRecord decoded = CcCacheExCodec.parseFilter(filter);
      assertEquals(CcConstants.CACHEEX_MAX_FILTERS, decoded.cevnum());

      for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
        assertEquals(caids[i], decoded.caids()[i], "caid[" + i + "]");
        assertEquals(cmasks[i], decoded.cmasks()[i], "cmask[" + i + "]");
        assertEquals(prids[i], decoded.prids()[i], "prid[" + i + "]");
        assertEquals(srvids[i], decoded.srvids()[i], "srvid[" + i + "]");
      }
    }

    @Test
    @DisplayName("FILTER taille invalide → rejet")
    void filterInvalidSize() {
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parseFilter(new byte[100]),
          "FILTER < 482 should be rejected");

      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parseFilter(new byte[500]),
          "FILTER > 482 should be rejected");

      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parseFilter(new byte[0]),
          "Empty FILTER should be rejected");
    }

    @Test
    @DisplayName("FILTER null → rejet")
    void filterNull() {
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parseFilter(null));
    }

    @Test
    @DisplayName("FILTER encode avec tableaux de mauvaise taille → rejet")
    void filterBadArraySize() {
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.encodeFilter(0, new int[10], new int[30], new int[30], new int[30]));
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.encodeFilter(0, new int[30], new int[10], new int[30], new int[30]));
    }
  }

  // ============================================================
  // Feature Exchange
  // ============================================================

  @Nested
  @DisplayName("Feature Exchange")
  class FeatureExchange {

    @Test
    @DisplayName("Feature exchange round-trip (bitfield 0)")
    void featureExchangeZero() {
      byte[] encoded = CcCacheExCodec.encodeFeatureExchange(0);
      assertEquals(4, encoded.length);
      assertEquals(0, CcCacheExCodec.parseFeatureExchange(encoded));
    }

    @Test
    @DisplayName("Feature exchange round-trip (bitfield non-zero)")
    void featureExchangeNonZero() {
      int bitfield = 0x12345678;
      byte[] encoded = CcCacheExCodec.encodeFeatureExchange(bitfield);
      assertEquals(4, encoded.length);
      assertEquals(bitfield, CcCacheExCodec.parseFeatureExchange(encoded));
    }

    @Test
    @DisplayName("Feature exchange parse taille invalide → rejet")
    void featureExchangeInvalidSize() {
      assertThrows(IllegalArgumentException.class,
          () -> CcCacheExCodec.parseFeatureExchange(new byte[3]));
    }
  }

  // ============================================================
  // isAllZeros / isPushValid / containsNode
  // ============================================================

  @Nested
  @DisplayName("Utilitaires")
  class Utilities {

    @Test
    @DisplayName("isAllZeros : tableau tout zéro")
    void isAllZerosTrue() {
      assertTrue(CcCacheExCodec.isAllZeros(new byte[16]));
      assertTrue(CcCacheExCodec.isAllZeros(new byte[]{0, 0, 0}));
    }

    @Test
    @DisplayName("isAllZeros : tableau avec au moins un non-zéro")
    void isAllZerosFalse() {
      assertFalse(CcCacheExCodec.isAllZeros(new byte[]{0, 0, 1}));
      assertFalse(CcCacheExCodec.isAllZeros(new byte[]{(byte) 0xFF}));
    }

    @Test
    @DisplayName("isPushValid : rc=FOUND, CW non-zero → true")
    void isPushValidTrue() {
      CcCacheExCodec.PushRecord push = new CcCacheExCodec.PushRecord(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          new byte[16], 0, cwFixture(), List.of(), -1);
      assertTrue(CcCacheExCodec.isPushValid(push));
    }

    @Test
    @DisplayName("isPushValid : rc=UNHANDLED(100) → true (R1, miroir OSCam :1311)")
    void isPushValidFalseRc() {
      CcCacheExCodec.PushRecord push = new CcCacheExCodec.PushRecord(
          0x0D00, 0, 0x0001, CcConstants.E_UNHANDLED, 0x80,
          new byte[16], 0, cwFixture(), List.of(), -1);
      assertTrue(CcCacheExCodec.isPushValid(push));
    }

    @Test
    @DisplayName("R1 vecteurs : rc 0/100 valides, autres rc rejetés")
    void isPushValidRcVectors() {
      for (int rc : new int[]{0, 100}) {
        CcCacheExCodec.PushRecord push = new CcCacheExCodec.PushRecord(
            0x0D00, 0, 0x0001, rc, 0x80,
            new byte[16], 0, cwFixture(), List.of(), -1);
        assertTrue(CcCacheExCodec.isPushValid(push), "rc=" + rc + " valide");
      }
      for (int rc : new int[]{1, 2, 99, 101, 255}) {
        CcCacheExCodec.PushRecord push = new CcCacheExCodec.PushRecord(
            0x0D00, 0, 0x0001, rc, 0x80,
            new byte[16], 0, cwFixture(), List.of(), -1);
        assertFalse(CcCacheExCodec.isPushValid(push), "rc=" + rc + " rejeté");
      }
    }

    @Test
    @DisplayName("isPushValid : CW all-zero → false")
    void isPushValidFalseCwZero() {
      CcCacheExCodec.PushRecord push = new CcCacheExCodec.PushRecord(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x80,
          new byte[16], 0, new byte[16], List.of(), -1);
      assertFalse(CcCacheExCodec.isPushValid(push));
    }

    @Test
    @DisplayName("containsNode : node présent")
    void containsNodeTrue() {
      byte[] n1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
      byte[] n2 = {0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18};
      assertTrue(CcCacheExCodec.containsNode(List.of(n1, n2), n1));
    }

    @Test
    @DisplayName("containsNode : node absent")
    void containsNodeFalse() {
      byte[] n1 = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
      byte[] n3 = {(byte) 0xFF, 0, 0, 0, 0, 0, 0, 0};
      assertFalse(CcCacheExCodec.containsNode(List.of(n1), n3));
    }

    @Test
    @DisplayName("containsNode : list null")
    void containsNodeNullList() {
      assertFalse(CcCacheExCodec.containsNode(null, new byte[8]));
    }
  }

  // ============================================================
  // Constantes
  // ============================================================

  @Test
  @DisplayName("Constantes MSG_CACHE_PUSH_BODY_SIZE = 36")
  void constantsPushBodySize() {
    assertEquals(36, CcConstants.MSG_CACHE_PUSH_BODY_SIZE);
  }

  @Test
  @DisplayName("Constantes MSG_CACHE_FILTER_SIZE = 482")
  void constantsFilterSize() {
    assertEquals(482, CcConstants.MSG_CACHE_FILTER_SIZE);
  }

  @Test
  @DisplayName("Constantes CACHEEX_MAX_FILTERS = 30")
  void constantsMaxFilters() {
    assertEquals(30, CcConstants.CACHEEX_MAX_FILTERS);
  }

  @Test
  @DisplayName("Constantes E_FOUND = 0, E_UNHANDLED = 100")
  void constantsRc() {
    assertEquals(0, CcConstants.E_FOUND);
    assertEquals(100, CcConstants.E_UNHANDLED);
  }

  // ============================================================
  // Fixtures
  // ============================================================

  private static byte[] cwFixture() {
    return new byte[] {
        0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88,
        (byte) 0x99, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
        (byte) 0xDD, (byte) 0xEE, (byte) 0xFF, 0x00
    };
  }

  private static byte[] nodeIdFixture() {
    return new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
  }

  @Nested
  @DisplayName("Normalisation TX oddEven (miroir OSCam :1230)")
  class TxNormalize {
    @Test
    @DisplayName("0x80/0x81 conservés, autres valeurs => 0 sur le wire [19]")
    void normalizeOddEven() {
      assertEquals(0x80, CcCacheExCodec.normalizeOddEven(0x80));
      assertEquals(0x81, CcCacheExCodec.normalizeOddEven(0x81));
      assertEquals(0, CcCacheExCodec.normalizeOddEven(0x00));
      assertEquals(0, CcCacheExCodec.normalizeOddEven(0x42));
      assertEquals(0, CcCacheExCodec.normalizeOddEven(0xFF));
    }

    @Test
    @DisplayName("encodePush écrit buf[19]=0 pour tag non-80/81")
    void encodePushNormalizesWire() {
      byte[] cw = cwFixture();
      byte[] encoded = CcCacheExCodec.encodePush(
          0x0D00, 0, 0x0001, CcConstants.E_FOUND, 0x42,
          new byte[16], 0, cw, List.of(nodeIdFixture()));
      assertEquals(0, encoded[19] & 0xFF, "tag non-80/81 normalisé en 0 sur le wire");
      // Round-trip : 0 accepté comme INCONNU
      CcCacheExCodec.PushRecord decoded = CcCacheExCodec.parsePush(encoded);
      assertEquals(0, decoded.oddEven());
      assertTrue(CcCacheExCodec.isPushValid(decoded));
    }
  }
}
