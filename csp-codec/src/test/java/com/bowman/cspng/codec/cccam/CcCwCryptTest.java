package com.bowman.cspng.codec.cccam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires pour {@link CcCwCrypt}.
 *
 * <p>Vérifie :
 * <ul>
 *   <li>Propriété auto-inverse (cwCrypt deux fois = original)</li>
 *   <li>Modification effective des données</li>
 *   <li>Cas limites : node_id tout à zéro, card_id = 0, card_id = MAX</li>
 *   <li>Stabilité sur 10 000 round-trips aléatoires</li>
 * </ul>
 */
@DisplayName("CcCwCrypt — tests unitaires")
class CcCwCryptTest {

  private static final SecureRandom RNG = new SecureRandom();

  @Test
  @DisplayName("cwCrypt : propriété auto-inverse (round-trip)")
  void cwCryptRoundTrip() {
    byte[] cw = hexToBytes("A1B2C3D4E5F60718293A4B5C6D7E8F90");
    byte[] nodeId = hexToBytes("0102030405060708");
    byte[] original = cw.clone();

    CcCwCrypt.cwCrypt(cw, nodeId, 0x12345678);
    assertFalse(java.util.Arrays.equals(original, cw), "First crypt should modify CW");

    CcCwCrypt.cwCrypt(cw, nodeId, 0x12345678);
    assertArrayEquals(original, cw, "Second crypt should restore original (auto-inverse)");
  }

  @Test
  @DisplayName("cwCrypt : modification effective (premier crypt ≠ original)")
  void cwCryptChangesData() {
    byte[] cw = hexToBytes("00112233445566778899AABBCCDDEEFF");
    byte[] orig = cw.clone();
    byte[] nodeId = hexToBytes("FEDCBA9876543210");

    CcCwCrypt.cwCrypt(cw, nodeId, 0x42);

    boolean changed = false;
    for (int i = 0; i < cw.length; i++) {
      if (cw[i] != orig[i]) {
        changed = true;
        break;
      }
    }
    assertTrue(changed, "CW should be modified after cwCrypt");
  }

  @Test
  @DisplayName("cwCrypt : card_id = 0")
  void cwCryptCardIdZero() {
    byte[] cw = hexToBytes("A1B2C3D4E5F60718293A4B5C6D7E8F90");
    byte[] orig = cw.clone();
    byte[] nodeId = hexToBytes("0102030405060708");

    CcCwCrypt.cwCrypt(cw, nodeId, 0);
    CcCwCrypt.cwCrypt(cw, nodeId, 0);
    assertArrayEquals(orig, cw, "Round-trip with card_id=0");
  }

  @Test
  @DisplayName("cwCrypt : card_id = 0xFFFFFFFF")
  void cwCryptCardIdMax() {
    byte[] cw = hexToBytes("A1B2C3D4E5F60718293A4B5C6D7E8F90");
    byte[] orig = cw.clone();
    byte[] nodeId = hexToBytes("0102030405060708");

    CcCwCrypt.cwCrypt(cw, nodeId, 0xFFFFFFFF);
    CcCwCrypt.cwCrypt(cw, nodeId, 0xFFFFFFFF);
    assertArrayEquals(orig, cw, "Round-trip with card_id=0xFFFFFFFF");
  }

  @Test
  @DisplayName("cwCrypt : node_id tout à zéro")
  void cwCryptNodeIdAllZeros() {
    byte[] cw = hexToBytes("F1E2D3C4B5A60718293A4B5C6D7E8F90");
    byte[] orig = cw.clone();
    byte[] nodeId = new byte[8];

    CcCwCrypt.cwCrypt(cw, nodeId, 0x12345678);
    CcCwCrypt.cwCrypt(cw, nodeId, 0x12345678);
    assertArrayEquals(orig, cw, "Round-trip with node_id=0");
  }

  @Test
  @DisplayName("cwCrypt : CW tout à zéro")
  void cwCryptCwAllZeros() {
    byte[] cw = new byte[16];
    byte[] orig = cw.clone();
    byte[] nodeId = hexToBytes("0102030405060708");

    CcCwCrypt.cwCrypt(cw, nodeId, 0x42);
    CcCwCrypt.cwCrypt(cw, nodeId, 0x42);
    assertArrayEquals(orig, cw, "Round-trip with CW=0");
  }

  @Test
  @DisplayName("cwCrypt : CW tout à 0xFF")
  void cwCryptCwAllFF() {
    byte[] cw = new byte[16];
    java.util.Arrays.fill(cw, (byte) 0xFF);
    byte[] orig = cw.clone();
    byte[] nodeId = hexToBytes("0102030405060708");

    CcCwCrypt.cwCrypt(cw, nodeId, 0x42);
    CcCwCrypt.cwCrypt(cw, nodeId, 0x42);
    assertArrayEquals(orig, cw, "Round-trip with CW=0xFF");
  }

  @Test
  @DisplayName("cwCrypt : 10 000 round-trips aléatoires")
  void cwCryptRandomRoundTrips() {
    for (int n = 0; n < 10_000; n++) {
      byte[] cw = new byte[16];
      byte[] nodeId = new byte[8];
      RNG.nextBytes(cw);
      RNG.nextBytes(nodeId);
      int cardId = RNG.nextInt();

      byte[] orig = cw.clone();
      CcCwCrypt.cwCrypt(cw, nodeId, cardId);
      CcCwCrypt.cwCrypt(cw, nodeId, cardId);

      assertArrayEquals(orig, cw, "Round-trip #" + n);
    }
  }

  @Test
  @DisplayName("cwCrypt : validation croisée manuelle des index pairs/impairs")
  void cwCryptManualIndexValidation() {
    // CW = [0x00 x 16], nodeId = [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08], cardId = 0
    byte[] cw = new byte[16];
    byte[] nodeId = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

    CcCwCrypt.cwCrypt(cw, nodeId, 0);

    // Après inversion : nod = [0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01]
    // Pour i=0 (pair): n = nod[0] = 0x08
    //   tmp = 0x00 ^ 0x08 = 0x08
    //   i even → pas de complément
    //   cw[0] = (0 >> 0) ^ 0x08 = 0x08
    assertEquals((byte) 0x08, cw[0], "cw[0] with nodeId[7]=0x08");

    // Pour i=1 (impair): n = (nod[0]>>4) | (nod[1]<<4) = (0x08>>4) | (0x07<<4) = 0x00 | 0x70 = 0x70
    //   tmp = 0x00 ^ 0x70 = 0x70
    //   ~tmp = ~0x70 = 0x8F
    //   cw[1] = (0 >> 2) ^ 0x8F = 0x8F
    assertEquals((byte) 0x8F, cw[1], "cw[1] with merged node bytes");

    // Double application → identité
    CcCwCrypt.cwCrypt(cw, nodeId, 0);
    for (int i = 0; i < 16; i++) {
      assertEquals(0, cw[i], "cw[" + i + "] should be 0 after auto-inverse");
    }
  }

  @Test
  @DisplayName("cwCrypt : rejette une CW de taille incorrecte")
  void cwCryptRejectsWrongSize() {
    assertThrows(IllegalArgumentException.class, () ->
        CcCwCrypt.cwCrypt(new byte[8], new byte[8], 0));
    assertThrows(IllegalArgumentException.class, () ->
        CcCwCrypt.cwCrypt(new byte[16], new byte[4], 0));
  }

  // ============================================================
  // Test du décalage non signé (verrouille la correction du bug >> → >>>)
  // ============================================================

  @Test
  @DisplayName("cwCrypt : cardId avec bit de poids fort à 1 (test >>> vs >>)")
  void cwCryptHighBitCardId() {
    // cardId = 0x80000001 : bit 31 à 1 → serait corrompu avec >> signé
    byte[] cw = new byte[16];
    byte[] nodeId = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
    int cardId = 0x80000001;

    byte[] orig = cw.clone();
    CcCwCrypt.cwCrypt(cw, nodeId, cardId);
    CcCwCrypt.cwCrypt(cw, nodeId, cardId);

    // L'auto-inverse doit fonctionner même avec bit de poids fort à 1
    assertArrayEquals(orig, cw,
        "Round-trip must work with cardId=0x80000001 (unsigned shift)");
  }

  @Test
  @DisplayName("cwCrypt : cardId = 0xFFFFFFFF (tous bits à 1)")
  void cwCryptAllBitsSet() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) i;
    byte[] orig = cw.clone();
    byte[] nodeId = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

    CcCwCrypt.cwCrypt(cw, nodeId, 0xFFFFFFFF);
    CcCwCrypt.cwCrypt(cw, nodeId, 0xFFFFFFFF);
    assertArrayEquals(orig, cw, "Round-trip with cardId=0xFFFFFFFF");
  }

  @Test
  @DisplayName("cwCrypt : cardId = 0x80000000 (bit de signe uniquement)")
  void cwCryptSignBitOnly() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) (i * 17);
    byte[] orig = cw.clone();
    byte[] nodeId = new byte[8];
    new SecureRandom().nextBytes(nodeId);

    CcCwCrypt.cwCrypt(cw, nodeId, 0x80000000);
    CcCwCrypt.cwCrypt(cw, nodeId, 0x80000000);
    assertArrayEquals(orig, cw, "Round-trip with cardId=0x80000000");
  }

  // ============================================================
  // Utilitaire hex
  // ============================================================

  static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                          + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
  }
}
