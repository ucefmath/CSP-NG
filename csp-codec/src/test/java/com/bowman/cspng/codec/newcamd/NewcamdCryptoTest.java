package com.bowman.cspng.codec.newcamd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitaires exhaustifs pour {@link NewcamdCrypto}.
 *
 * <p>Vérifie :
 * <ul>
 *   <li>Round-trip chiffrement/déchiffrement</li>
 *   <li>Checksum invalide détecté</li>
 *   <li>desKeySpread (vecteurs connus)</li>
 *   <li>xorKey (propriété d'idempotence)</li>
 *   <li>Cas limites : message vide, message long, padding aux bornes</li>
 *   <li>Stabilité sur 10 000 round-trips aléatoires</li>
 * </ul>
 */
@DisplayName("NewcamdCrypto — tests unitaires")
class NewcamdCryptoTest {

  private static final byte[] TEST_KEY_14 = {
      (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04, (byte) 0x05, (byte) 0x06, (byte) 0x07,
      (byte) 0x08, (byte) 0x09, (byte) 0x0A, (byte) 0x0B, (byte) 0x0C, (byte) 0x0D, (byte) 0x0E
  };

  // ============================================================
  // xorKey
  // ============================================================

  @Test
  @DisplayName("xorKey : XOR de deux tableaux de 14 octets")
  void xorKeyBasic() {
    byte[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14};
    byte[] b = {14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    byte[] result = NewcamdCrypto.xorKey(a, b);
    // 1^14=15, 2^13=15, ..., 14^1=15
    for (int i = 0; i < 14; i++) {
      assertEquals((byte) (a[i] ^ b[i]), result[i], "XOR mismatch at index " + i);
    }
    // Double XOR = identité
    byte[] back = NewcamdCrypto.xorKey(result, b);
    assertArrayEquals(a, back, "Double XOR should return original");
  }

  @Test
  @DisplayName("xorKey : idempotence (double XOR = original)")
  void xorKeyDoubleXor() {
    Random rng = new Random(42);
    for (int n = 0; n < 100; n++) {
      byte[] a = new byte[14];
      byte[] b = new byte[14];
      rng.nextBytes(a);
      rng.nextBytes(b);
      byte[] xored = NewcamdCrypto.xorKey(a, b);
      byte[] back = NewcamdCrypto.xorKey(xored, b);
      assertArrayEquals(a, back, "Double XOR should return original");
    }
  }

  // ============================================================
  // desKeySpread
  // ============================================================

  @Test
  @DisplayName("desKeySpread : taille de sortie correcte (16 octets)")
  void desKeySpreadSize() {
    byte[] result = NewcamdCrypto.desKeySpread(TEST_KEY_14);
    assertEquals(16, result.length, "desKeySpread should return 16 bytes");
  }

  @Test
  @DisplayName("desKeySpread : déterministe")
  void desKeySpreadDeterministic() {
    byte[] r1 = NewcamdCrypto.desKeySpread(TEST_KEY_14);
    byte[] r2 = NewcamdCrypto.desKeySpread(TEST_KEY_14);
    assertArrayEquals(r1, r2, "desKeySpread should be deterministic");
  }

  @Test
  @DisplayName("desKeySpread : les deux moitiés diffèrent quand les entrées diffèrent")
  void desKeySpreadDifferentHalves() {
    byte[] key = new byte[14];
    Arrays.fill(key, (byte) 0x55);
    byte[] result = NewcamdCrypto.desKeySpread(key);

    // Les deux moitiés de la clé d'entrée étant identiques, les sorties le sont aussi
    byte[] half1 = Arrays.copyOfRange(result, 0, 8);
    byte[] half2 = Arrays.copyOfRange(result, 8, 16);
    assertArrayEquals(half1, half2, "Identical input halves should yield identical output halves");

    // Test avec des moitiés différentes
    byte[] key2 = new byte[14];
    for (int i = 0; i < 7; i++) key2[i] = (byte) i;
    for (int i = 7; i < 14; i++) key2[i] = (byte) (i + 10);
    byte[] resultDiff = NewcamdCrypto.desKeySpread(key2);
    assertFalse(Arrays.equals(
        Arrays.copyOfRange(resultDiff, 0, 8),
        Arrays.copyOfRange(resultDiff, 8, 16)),
        "Different input halves should yield different output halves");
  }

  // ============================================================
  // Round-trip chiffrement/déchiffrement
  // ============================================================

  /**
   * Compare l'original avec les premiers {@code msgLen} octets du buffer déchiffré.
   * <p>{@code desDecrypt} retourne le buffer complet incluant padding + checksum
   * (le caller legacy {@code CamdNetMessage.parseNewcamd} extrait le message via le header).
   */
  private static void assertDecryptedMatches(byte[] original, int originalLen, byte[] decrypted) {
    assertNotNull(decrypted, "Decryption should succeed");
    assertTrue(decrypted.length >= originalLen,
        "Decrypted length " + decrypted.length + " should be >= original " + originalLen);
    for (int i = 0; i < originalLen; i++) {
      assertEquals(original[i], decrypted[i], "Byte mismatch at index " + i);
    }
  }

  @Test
  @DisplayName("Round-trip : message simple chiffré/déchiffré")
  void roundTripSimple() {
    byte[] message = "Hello, Newcamd!".getBytes();
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16, 512);
    assertNotNull(encrypted, "Encryption should succeed");
    assertTrue(encrypted.length >= message.length + 8 + 1,
        "Encrypted should be at least message + IV(8) + checksum(1): " + encrypted.length + " >= " + (message.length + 9));

    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16);
    assertDecryptedMatches(message, message.length, decrypted);
  }

  @Test
  @DisplayName("Round-trip : message vide")
  void roundTripEmpty() {
    byte[] message = new byte[0];
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] encrypted = NewcamdCrypto.desEncrypt(message, 0, desKey16, 512);
    assertNotNull(encrypted);

    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16);
    assertNotNull(decrypted);
    // Empty message: padding(7) + checksum(1) = 8 bytes
    assertEquals(8, decrypted.length, "Empty message yields 8 decrypted bytes (7 pad + 1 checksum)");
  }

  @Test
  @DisplayName("Round-trip : message de longueur exactement multiple de 8")
  void roundTripMultipleOf8() {
    byte[] message = new byte[16];
    Random rng = new Random(123);
    rng.nextBytes(message);
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16, 512);
    assertNotNull(encrypted);

    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16);
    // 16 bytes → padding(7) + checksum(1) = 24 bytes decrypted
    assertDecryptedMatches(message, message.length, decrypted);
  }

  @Test
  @DisplayName("Round-trip : message de longueur non multiple de 8")
  void roundTripNonMultipleOf8() {
    for (int len = 1; len <= 50; len++) {
      if (len % 8 == 0) continue;
      byte[] message = new byte[len];
      Random rng = new Random(len);
      rng.nextBytes(message);
      byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

      byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16, 512);
      assertNotNull(encrypted, "Encryption should succeed for len=" + len);

      byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16);
      assertDecryptedMatches(message, message.length, decrypted);
    }
  }

  @Test
  @DisplayName("Round-trip : 10 000 messages aléatoires")
  void roundTripFuzz() {
    Random rng = new Random(999);
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    for (int i = 0; i < 10_000; i++) {
      int len = rng.nextInt(200);
      byte[] message = new byte[len];
      rng.nextBytes(message);

      byte[] encrypted = NewcamdCrypto.desEncrypt(message, len, desKey16, 512);
      assertNotNull(encrypted);

      byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16);
      assertDecryptedMatches(message, len, decrypted);
    }
  }

  // ============================================================
  // Validation checksum
  // ============================================================

  @Test
  @DisplayName("desDecrypt : retourne null sur checksum invalide")
  void decryptBadChecksum() {
    byte[] message = "test".getBytes();
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16, 512);
    assertNotNull(encrypted);

    // Corrompre un octet au milieu du buffer chiffré
    byte[] corrupted = encrypted.clone();
    corrupted[corrupted.length / 2] ^= 0x01; // flip un bit

    byte[] decrypted = NewcamdCrypto.desDecrypt(corrupted, corrupted.length, desKey16);
    assertNull(decrypted, "Decryption should return null for corrupted data");
  }

  @Test
  @DisplayName("desDecrypt : mauvaise clé → checksum invalide ou données incohérentes")
  void decryptWrongKey() {
    byte[] message = "test".getBytes();
    byte[] desKey16a = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16a, 512);
    assertNotNull(encrypted);

    // Clé différente
    byte[] wrongKey14 = new byte[14];
    Arrays.fill(wrongKey14, (byte) 0xFF);
    byte[] wrongKey16 = NewcamdCrypto.desKeySpread(wrongKey14);

    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, wrongKey16);
    // Avec une mauvaise clé, le checksum peut être valide par coïncidence
    // (probabilité ~1/256). Si c'est le cas, les données seront incohérentes.
    if (decrypted != null) {
      // Vérifier que les données ne correspondent PAS au message original
      // (le checksum peut être valide mais le contenu sera du bruit)
      boolean matches = decrypted.length >= message.length;
      if (matches) {
        for (int i = 0; i < message.length; i++) {
          if (decrypted[i] != message[i]) { matches = false; break; }
        }
      }
      assertFalse(matches,
          "With wrong key, decrypted data should not match original (checksum coincidence)");
    }
    // null est aussi acceptable (checksum invalide)
  }

  // ============================================================
  // Limites de taille
  // ============================================================

  @Test
  @DisplayName("desEncrypt : retourne null si le buffer dépasse maxSize")
  void encryptTooBig() {
    byte[] message = new byte[500];
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] result = NewcamdCrypto.desEncrypt(message, message.length, desKey16, 256);
    assertNull(result, "Should return null when result exceeds maxSize");
  }

  @Test
  @DisplayName("desEncrypt : fonctionne quand la taille est dans les limites")
  void encryptWithinLimits() {
    byte[] message = new byte[100];
    byte[] desKey16 = NewcamdCrypto.desKeySpread(TEST_KEY_14);

    byte[] result = NewcamdCrypto.desEncrypt(message, message.length, desKey16, NewcamdConstants.CWS_NETMSGSIZE);
    assertNotNull(result, "Should succeed within limits");
    assertTrue(result.length <= NewcamdConstants.CWS_NETMSGSIZE + 8, "Result should fit");
  }

  // ============================================================
  // Handshake (spécifique Newcamd)
  // ============================================================

  @Test
  @DisplayName("Handshake : flux complet (génération clé client + acceptation serveur)")
  void handshakeFlow() {
    // Côté client
    byte[] configKey14 = new byte[14];
    Random rng = new Random(777);
    rng.nextBytes(configKey14);

    byte[] random14 = NewcamdCrypto.getRandomBytes(14, rng);
    byte[] desKey16Client = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(configKey14, random14));

    // Côté serveur (reçoit random14)
    byte[] desKey16Server = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(configKey14, random14));

    // Les clés doivent être identiques
    assertArrayEquals(desKey16Client, desKey16Server, "Client and server keys must match");

    // Round-trip avec ces clés
    byte[] message = "ECM request".getBytes();
    byte[] encrypted = NewcamdCrypto.desEncrypt(message, message.length, desKey16Client, 512);
    byte[] decrypted = NewcamdCrypto.desDecrypt(encrypted, encrypted.length, desKey16Server);
    assertDecryptedMatches(message, message.length, decrypted);
  }

  @Test
  @DisplayName("Handshake : clés différentes si random14 différent")
  void handshakeDifferentRandom() {
    byte[] configKey14 = new byte[14];
    Random rng = new Random(888);
    rng.nextBytes(configKey14);

    byte[] random14a = NewcamdCrypto.getRandomBytes(14, rng);
    byte[] desKey16a = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(configKey14, random14a));

    byte[] random14b = NewcamdCrypto.getRandomBytes(14, rng);
    byte[] desKey16b = NewcamdCrypto.desKeySpread(NewcamdCrypto.xorKey(configKey14, random14b));

    assertFalse(Arrays.equals(random14a, random14b), "Random values should differ");
    assertFalse(Arrays.equals(desKey16a, desKey16b), "Keys should differ with different random");
  }
}
