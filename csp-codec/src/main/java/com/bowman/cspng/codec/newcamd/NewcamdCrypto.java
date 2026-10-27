package com.bowman.cspng.codec.newcamd;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * Implémentation pure Java 21 du chiffrement/déchiffrement Newcamd.
 *
 * <p>Contrairement à ce qui avait été diagnostiqué dans la première itération,
 * le legacy utilise du DESede (Triple-DES) en mode CBC <b>standard</b> avec
 * clé K1|K2|K1. La confusion venait du paramètre booléen de la primitive
 * {@code des_ecb_encrypt(..., ks, boolean)} de la librairie legacy qui suit
 * la convention OpenSSL : {@code false} = DECRYPT, {@code true} = ENCRYPT.
 *
 * <p>Cette implémentation utilise {@code javax.crypto.Cipher.getInstance("DESede/CBC/NoPadding")}
 * comme primitive de bloc. Les seuls traitements applicatifs conservés sont :
 * <ul>
 *   <li>Vérification checksum XOR cumulatif (couche anti-corruption).</li>
 *   <li>Placement non standard de l'IV en fin de buffer (protocole Newcamd).</li>
 *   <li>Padding aléatoire.</li>
 * </ul>
 *
 * <p>ZÉRO dépendance Vert.x ni librairie legacy pour le chiffrement.
 */
public final class NewcamdCrypto {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private static final int DES_KEY_SIZE = 8;
  private static final int DES_HALF_KEY_SIZE = 7;
  private static final int DES_KEY_14 = 14;
  private static final int DES_KEY_16 = 16;
  private static final int DESEDE_KEY_SIZE = 24;

  private NewcamdCrypto() {}

  // ============================================================
  // Opérations utilitaires (inchangées)
  // ============================================================

  /** XOR octet-à-octet de deux tableaux de 14 octets.
   *  Port exact de {@code DESUtil.xorKey} l.17-21. */
  public static byte[] xorKey(byte[] desKey14, byte[] xorKey14) {
    byte[] newKey = new byte[DES_KEY_14];
    for (int i = 0; i < DES_KEY_14; i++) {
      newKey[i] = (byte) (desKey14[i] ^ xorKey14[i]);
    }
    return newKey;
  }

  /** XOR cyclique du mot de passe crypté avec la clé de 14 octets.
   *  Port exact de {@code DESUtil.xorUserPass} l.29-35. */
  public static byte[] xorUserPass(byte[] desKey14, String cryptPw) {
    byte[] newKey = new byte[DES_KEY_14];
    System.arraycopy(desKey14, 0, newKey, 0, DES_KEY_14);
    byte[] cryptBytes = cryptPw.getBytes();
    for (int i = 0; i < cryptPw.length(); i++) {
      newKey[i % DES_KEY_14] ^= cryptBytes[i];
    }
    return newKey;
  }

  /** Génère un tableau d'octets aléatoires (SecureRandom). */
  public static byte[] getRandomBytes(int len) {
    byte[] random = new byte[len];
    SECURE_RANDOM.nextBytes(random);
    return random;
  }

  /** Génère un tableau d'octets aléatoires avec un RNG déterministe (pour les tests). */
  static byte[] getRandomBytes(int len, java.util.Random rng) {
    byte[] random = new byte[len];
    rng.nextBytes(random);
    return random;
  }

  // ============================================================
  // Key spreading et parité (inchangés — confirmés corrects par le reviewer)
  // ============================================================

  /**
   * Étale une clé Newcamd de 14 octets en une clé DES de 16 octets.
   * Port exact de {@code DESUtil.desKeySpread} l.45-58.
   */
  public static byte[] desKeySpread(byte[] desKey14) {
    byte[] key1 = new byte[DES_HALF_KEY_SIZE];
    byte[] key2 = new byte[DES_HALF_KEY_SIZE];
    System.arraycopy(desKey14, 0, key1, 0, DES_HALF_KEY_SIZE);
    System.arraycopy(desKey14, DES_HALF_KEY_SIZE, key2, 0, DES_HALF_KEY_SIZE);
    key1 = addParity(key1);
    key2 = addParity(key2);

    byte[] desKey16 = new byte[DES_KEY_16];
    System.arraycopy(key1, 0, desKey16, 0, DES_KEY_SIZE);
    System.arraycopy(key2, 0, desKey16, DES_KEY_SIZE, DES_KEY_SIZE);
    return desKey16;
  }

  // ============================================================
  // Dialecte explicite OSCam NCD525 (module-newcamd-des.c)
  // ============================================================

  private static final int[][] PC1 = {
      {57, 49, 41, 33, 25, 17, 9, 1},
      {58, 50, 42, 34, 26, 18, 10, 2},
      {59, 51, 43, 35, 27, 19, 11, 3},
      {60, 52, 44, 36, 63, 55, 47, 39},
      {31, 23, 15, 7, 62, 54, 46, 38},
      {30, 22, 14, 6, 61, 53, 45, 37},
      {29, 21, 13, 5, 28, 20, 12, 4}
  };

  /**
   * Dérivation de clé de login selon le dialecte OSCam NCD525
   * (port exact de {@code nc_des_login_key_get} module-newcamd-des.c l.490).
   */
  public static byte[] loginKeyNcd525(byte[] key1, byte[] key2, int len) {
    byte[] des14 = new byte[14];
    System.arraycopy(key1, 0, des14, 0, 14);
    for (int i = 0; i < len; i++) {
      des14[i % 14] ^= key2[i];
    }
    byte[] des16 = desKeySpreadNcd525(des14);
    doPC1(des16, 0);
    doPC1(des16, 8);
    return des16;
  }

  public static byte[] desKeySpreadNcd525(byte[] normal) {
    byte[] spread = new byte[16];
    spread[ 0] = (byte) (normal[ 0] & 0xfe);
    spread[ 1] = (byte) (((normal[ 0] << 7) | ((normal[ 1] & 0xFF) >>> 1)) & 0xfe);
    spread[ 2] = (byte) (((normal[ 1] << 6) | ((normal[ 2] & 0xFF) >>> 2)) & 0xfe);
    spread[ 3] = (byte) (((normal[ 2] << 5) | ((normal[ 3] & 0xFF) >>> 3)) & 0xfe);
    spread[ 4] = (byte) (((normal[ 3] << 4) | ((normal[ 4] & 0xFF) >>> 4)) & 0xfe);
    spread[ 5] = (byte) (((normal[ 4] << 3) | ((normal[ 5] & 0xFF) >>> 5)) & 0xfe);
    spread[ 6] = (byte) (((normal[ 5] << 2) | ((normal[ 6] & 0xFF) >>> 6)) & 0xfe);
    spread[ 7] = (byte) (normal[ 6] << 1);
    spread[ 8] = (byte) (normal[ 7] & 0xfe);
    spread[ 9] = (byte) (((normal[ 7] << 7) | ((normal[ 8] & 0xFF) >>> 1)) & 0xfe);
    spread[10] = (byte) (((normal[ 8] << 6) | ((normal[ 9] & 0xFF) >>> 2)) & 0xfe);
    spread[11] = (byte) (((normal[ 9] << 5) | ((normal[10] & 0xFF) >>> 3)) & 0xfe);
    spread[12] = (byte) (((normal[10] << 4) | ((normal[11] & 0xFF) >>> 4)) & 0xfe);
    spread[13] = (byte) (((normal[11] << 3) | ((normal[12] & 0xFF) >>> 5)) & 0xfe);
    spread[14] = (byte) (((normal[12] << 2) | ((normal[13] & 0xFF) >>> 6)) & 0xfe);
    spread[15] = (byte) (normal[13] << 1);

    for (int i = 0; i < 16; i++) {
      int parity = 1;
      for (int j = 1; j < 8; j++) {
        if (((spread[i] >> j) & 0x01) != 0) parity = (~parity) & 0x01;
      }
      spread[i] = (byte) (spread[i] | parity);
    }
    return spread;
  }

  private static void doPC1(byte[] data, int off) {
    byte[] buf = new byte[8];
    for (int j = 0; j < 7; j++) {
      for (int i = 0; i < 8; i++) {
        int lookup = PC1[j][i];
        buf[j] |= (byte) ((((data[off + (lookup >> 3)] & 0xFF) >> (8 - (lookup & 7))) & 1) << (7 - i));
      }
    }
    System.arraycopy(buf, 0, data, off, 8);
  }

  /**
   * Ajoute les bits de parité DES standard à une clé de 7 octets (56 bits).
   * Port EXACT de {@code DESUtil.addParity} l.126-147 — confirmé correct.
   */
  private static byte[] addParity(byte[] in) {
    byte[] result = new byte[DES_KEY_SIZE];
    int resultIx = 1;
    int bitCount = 0;

    for (int i = 0; i < 56; i++) {
      boolean bit = (in[6 - i / 8] & (1 << (i % 8))) > 0;
      if (bit) {
        result[7 - resultIx / 8] |= (1 << (resultIx % 8)) & 0xFF;
        bitCount++;
      }
      if ((i + 1) % 7 == 0) {
        if (bitCount % 2 == 0) {
          result[7 - resultIx / 8] |= 1;
        }
        resultIx++;
        bitCount = 0;
      }
      resultIx++;
    }
    return result;
  }

  /**
   * Construit une clé 3DES 24 octets (K1|K2|K1) à partir d'une desKey16.
   */
  private static byte[] buildTripleDesKey(byte[] desKey16) {
    byte[] key = new byte[DESEDE_KEY_SIZE];
    // K1
    System.arraycopy(desKey16, 0, key, 0, DES_KEY_SIZE);
    // K2
    System.arraycopy(desKey16, DES_KEY_SIZE, key, DES_KEY_SIZE, DES_KEY_SIZE);
    // K1 again
    System.arraycopy(desKey16, 0, key, 2 * DES_KEY_SIZE, DES_KEY_SIZE);
    return key;
  }

  // ============================================================
  // Primitives 3DES-EDE-CBC via JCE standard
  // ============================================================

  /**
   * Chiffre un buffer complet en 3DES-EDE-CBC avec IV placé en fin de buffer.
   *
   * <p>Cette méthode remplace l'étape interne de chiffrement DESede/CBC.
   * Le padding aléatoire et le checksum XOR sont gérés par {@link #desEncrypt}.
   */
  private static byte[] desedeCbcEncrypt(byte[] plaintext, byte[] ivec, byte[] desKey16) {
    try {
      Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
      SecretKeySpec keySpec = new SecretKeySpec(buildTripleDesKey(desKey16), "DESede");
      IvParameterSpec ivSpec = new IvParameterSpec(ivec);
      cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
      return cipher.doFinal(plaintext);
    } catch (Exception e) {
      throw new RuntimeException("DESede/CBC encrypt failed", e);
    }
  }

  /**
   * Déchiffre un buffer complet en 3DES-EDE-CBC avec IV passé explicitement.
   */
  private static byte[] desedeCbcDecrypt(byte[] ciphertext, byte[] ivec, byte[] desKey16) {
    try {
      Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
      SecretKeySpec keySpec = new SecretKeySpec(buildTripleDesKey(desKey16), "DESede");
      IvParameterSpec ivSpec = new IvParameterSpec(ivec);
      cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
      return cipher.doFinal(ciphertext);
    } catch (Exception e) {
      throw new RuntimeException("DESede/CBC decrypt failed", e);
    }
  }

  // ============================================================
  // Déchiffrement Newcamd (port sémantique de DESUtil.desDecrypt)
  // ============================================================

  /**
   * Déchiffre un buffer Newcamd.
   *
   * <p>L'IV est dans les 8 derniers octets du buffer (protocole Newcamd).
   * Le reste est déchiffré en 3DES-EDE-CBC standard.
   * Vérification checksum XOR cumulatif après déchiffrement.
   *
   * @param buffer   buffer chiffré (ciphertext + IV final)
   * @param len      longueur totale
   * @param desKey16 clé DES de 16 octets (K1[8] + K2[8])
   * @return données déchiffrées, ou {@code null} si checksum invalide
   */
  public static byte[] desDecrypt(byte[] buffer, int len, byte[] desKey16) {
    int ctLen = len - DES_KEY_SIZE; // retirer l'IV final
    byte[] ivec = new byte[DES_KEY_SIZE];
    System.arraycopy(buffer, ctLen, ivec, 0, DES_KEY_SIZE); // IV = buffer[ctLen:len]

    byte[] ciphertext = new byte[ctLen];
    System.arraycopy(buffer, 0, ciphertext, 0, ctLen);

    byte[] decrypted = desedeCbcDecrypt(ciphertext, ivec, desKey16);

    // Vérification checksum XOR cumulatif
    byte checksum = 0;
    for (int i = 0; i < ctLen; i++) checksum ^= decrypted[i];

    if (checksum != 0) return null;
    return decrypted;
  }

  // ============================================================
  // Chiffrement Newcamd (port sémantique de DESUtil.desEncrypt)
  // ============================================================

  /**
   * Chiffre un buffer Newcamd.
   *
   * <p>Ajoute du padding aléatoire + 1 octet de checksum XOR, génère un IV
   * aléatoire, chiffre en 3DES-EDE-CBC, et place l'IV à la fin du buffer de sortie
   * (conformément au protocole Newcamd).
   *
   * @param buffer   données en clair
   * @param len      longueur des données utiles
   * @param desKey16 clé DES de 16 octets
   * @param maxSize  taille maximale du buffer de sortie
   * @return données chiffrées (ciphertext + IV final), ou {@code null} si dépassement
   */
  public static byte[] desEncrypt(byte[] buffer, int len, byte[] desKey16, int maxSize) {
    // Padding : arrondir à un multiple de 8, moins 1 pour le checksum
    int noPadBytes = DES_KEY_SIZE - (len % DES_KEY_SIZE);
    if (noPadBytes == 0) noPadBytes = DES_KEY_SIZE;
    noPadBytes--;

    byte[] padBytes = getRandomBytes(noPadBytes);

    if (len + noPadBytes + 1 + DES_KEY_SIZE > maxSize) return null;

    byte[] plaintext = new byte[len + noPadBytes + 1];
    System.arraycopy(buffer, 0, plaintext, 0, len);
    int pos = len;
    for (int p = 0; p < noPadBytes; p++) plaintext[pos++] = padBytes[p];

    // Checksum XOR cumulatif
    byte checksum = 0;
    for (int i = 0; i < pos; i++) checksum ^= plaintext[i];
    plaintext[pos] = checksum;
    // plaintext.length = pos + 1

    // IV aléatoire
    byte[] ivec = getRandomBytes(DES_KEY_SIZE);

    // Chiffrer en 3DES-EDE-CBC
    byte[] ciphertext = desedeCbcEncrypt(plaintext, ivec, desKey16);

    // Assembler : ciphertext + IV (protocole Newcamd)
    byte[] encrypted = new byte[ciphertext.length + DES_KEY_SIZE];
    System.arraycopy(ciphertext, 0, encrypted, 0, ciphertext.length);
    System.arraycopy(ivec, 0, encrypted, ciphertext.length, DES_KEY_SIZE);

    return encrypted;
  }

  private static byte[] copyBlock(byte[] src, int offset) {
    byte[] block = new byte[DES_KEY_SIZE];
    System.arraycopy(src, offset, block, 0, DES_KEY_SIZE);
    return block;
  }
}
