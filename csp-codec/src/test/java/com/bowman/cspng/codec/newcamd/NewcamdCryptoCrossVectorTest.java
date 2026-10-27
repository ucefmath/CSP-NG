package com.bowman.cspng.codec.newcamd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation croisée : vérifie que {@code DESede/CBC/NoPadding} JCE produit
 * EXACTEMENT le même résultat que le legacy {@code DESUtil}.
 *
 * <p>Vecteurs générés par exécution du legacy {@code DesCrossVector}
 * (CardServProxy legacy, Java 17).
 */
@DisplayName("NewcamdCrypto — validation croisée legacy via DESede/CBC JCE")
class NewcamdCryptoCrossVectorTest {

  private static final byte[] DES_KEY_16 = hexToBytes(
      "4f04baf710529bbcef73c804fbb3ae94");

  private static final byte[] ENCRYPTED = hexToBytes(
      "30c7003fbd69deea92727be2a1430e44e2e67084ce7aae36cae618723de4a4e1");

  private static final byte[] DECRYPTED_EXPECTED = hexToBytes(
      "45434d2074657374207061796c6f616445d78395aedb8143");

  private static final byte[] MESSAGE = "ECM test payload".getBytes(); // 16 bytes

  // ============================================================
  // Test de la primitive DESede/CBC/NoPadding
  // ============================================================

  @Test
  @DisplayName("DESede/CBC/NoPadding déchiffre le vecteur legacy exactement")
  void desedeCbcDecryptsLegacyVector() throws Exception {
    byte[] k1 = new byte[8], k2 = new byte[8];
    System.arraycopy(DES_KEY_16, 0, k1, 0, 8);
    System.arraycopy(DES_KEY_16, 8, k2, 0, 8);

    // Construire clé 3DES 24 octets : K1|K2|K1
    byte[] tripleKey = new byte[24];
    System.arraycopy(k1, 0, tripleKey, 0, 8);
    System.arraycopy(k2, 0, tripleKey, 8, 8);
    System.arraycopy(k1, 0, tripleKey, 16, 8);

    // Ciphertext = tout sauf les 8 derniers octets (IV)
    int ctLen = ENCRYPTED.length - 8;
    byte[] ciphertext = new byte[ctLen];
    System.arraycopy(ENCRYPTED, 0, ciphertext, 0, ctLen);

    // IV = les 8 derniers octets
    byte[] ivec = new byte[8];
    System.arraycopy(ENCRYPTED, ctLen, ivec, 0, 8);

    Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
    SecretKeySpec keySpec = new SecretKeySpec(tripleKey, "DESede");
    IvParameterSpec ivSpec = new IvParameterSpec(ivec);
    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
    byte[] decrypted = cipher.doFinal(ciphertext);

    assertArrayEquals(DECRYPTED_EXPECTED, decrypted,
        "DESede/CBC/NoPadding must decrypt the legacy vector exactly");
  }

  // ============================================================
  // Test via NewcamdCrypto.desDecrypt
  // ============================================================

  @Test
  @DisplayName("desDecrypt : vecteur legacy exact")
  void desDecryptFixedVector() {
    byte[] decrypted = NewcamdCrypto.desDecrypt(ENCRYPTED, ENCRYPTED.length, DES_KEY_16);
    assertNotNull(decrypted, "Decryption should succeed on legacy ciphertext");
    assertArrayEquals(DECRYPTED_EXPECTED, decrypted,
        "desDecrypt must exactly match legacy output");
  }

  @Test
  @DisplayName("desDecrypt : message vide legacy")
  void desDecryptEmptyLegacy() {
    byte[] emptyEnc = hexToBytes("7bb3780546f4a952de9ed6f8d2dca94f");
    byte[] emptyDec = hexToBytes("7b221be7cdd61ea0");
    byte[] decrypted = NewcamdCrypto.desDecrypt(emptyEnc, emptyEnc.length, DES_KEY_16);
    assertNotNull(decrypted);
    assertArrayEquals(emptyDec, decrypted, "Empty message decryption must match legacy");
  }

  // ============================================================
  // Round-trip
  // ============================================================

  @Test
  @DisplayName("Round-trip : notre encrypt + notre decrypt")
  void roundTripSelf() {
    byte[] encrypted = NewcamdCrypto.desEncrypt(MESSAGE, MESSAGE.length, DES_KEY_16, 512);
    assertNotNull(encrypted);

    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, DES_KEY_16);
    assertNotNull(decrypted);
    assertTrue(decrypted.length >= MESSAGE.length);

    for (int i = 0; i < MESSAGE.length; i++) {
      assertEquals(MESSAGE[i], decrypted[i], "Round-trip mismatch at index " + i);
    }
  }

  // ============================================================
  // desKeySpread
  // ============================================================

  @Test
  @DisplayName("desKeySpread : vecteur legacy")
  void desKeySpreadMatchesLegacy() {
    byte[] configKey14 = new byte[14];
    for (int i = 0; i < 14; i++) configKey14[i] = (byte) (i + 1);
    byte[] random14 = hexToBytes("4f08ecb50f60d9e6ee2a24ba66c4");

    byte[] computed = NewcamdCrypto.xorKey(configKey14, random14);
    byte[] spread = NewcamdCrypto.desKeySpread(computed);
    assertArrayEquals(DES_KEY_16, spread, "desKeySpread must match legacy");
  }

  // ============================================================
  // xorUserPass
  // ============================================================

  @Test
  @DisplayName("xorUserPass : propriété de base")
  void xorUserPassBasic() {
    byte[] key14 = new byte[14];
    for (int i = 0; i < 14; i++) key14[i] = (byte) i;
    byte[] result = NewcamdCrypto.xorUserPass(key14, "hello");
    assertEquals(14, result.length);

    // Le résultat doit être différent de la clé d'entrée
    boolean differs = false;
    for (int i = 0; i < 14; i++) {
      if (result[i] != key14[i]) { differs = true; break; }
    }
    assertTrue(differs, "xorUserPass should modify the key");
  }

  // ============================================================
  // Utilitaire
  // ============================================================

  static byte[] hexToBytes(String hex) {
    byte[] bytes = new byte[hex.length() / 2];
    for (int i = 0; i < hex.length(); i += 2) {
      bytes[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
    }
    return bytes;
  }
}
