package com.bowman.cspng.codec.camd35;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * Primitives crypto camd35/CS378x.
 *
 * <p>Contrats sources (lecture seule) :
 * <ul>
 *   <li>ucrc = {@code CRC32(MD5(username))} en uint32 BE, 4 octets CLAIRS en préfixe de
 *       chaque frame — OSCam module-camd35.c l.142-153 (lookup compte :
 *       {@code crc == crc32(0L, MD5(account->usr), 16)}) et l.866 (client :
 *       {@code i2b_buf(4, crc32(0L, MD5(rdr->r_usr), 16), cl->ucrc)}).</li>
 *   <li>Clé AES-128 = {@code MD5(password)} — l.153 (serveur : {@code aes_set_key_alloc(
 *       &cl->aes_keys, MD5(cl->upwd))}) et l.868 (client).</li>
 *   <li>Chiffrement AES-128-ECB par blocs de 16 in place — l.68
 *       {@code aes_encrypt_idx(cl->aes_keys, sbuf, l)} (oscam-aes.c : AES_encrypt bloc à bloc),
 *       l.311/387 côté réception.</li>
 * </ul>
 *
 * <p>Goldens indépendants (calculés hors Java — python3/openssl, cf. tests) :
 * ucrc("test") = {@code e807815d} ; AES-128-ECB(MD5("test"), 030a11..656c) =
 * {@code 9646b8c32723275cf65182c9d13d0720}.
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class Camd35Crypto {

  private Camd35Crypto() {}

  /**
   * ucrc d'un nom d'utilisateur : CRC32(MD5(username)) en 4 octets big-endian (clair sur le wire).
   *
   * @param username nom du compte (ASCII)
   * @return 4 octets BE
   */
  public static byte[] ucrc(String username) {
    if (username == null || username.isEmpty()) {
      throw new IllegalArgumentException("username must not be null/empty");
    }
    long crc = crc32(md5(username.getBytes(StandardCharsets.US_ASCII)));
    return new byte[] {
        (byte) (crc >>> 24), (byte) (crc >>> 16), (byte) (crc >>> 8), (byte) crc
    };
  }

  /** Clé AES-128 = MD5(password) — 16 octets. */
  public static byte[] aesKey(String password) {
    if (password == null || password.isEmpty()) {
      throw new IllegalArgumentException("password must not be null/empty");
    }
    return md5(password.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Chiffre/déchiffre un buffer AES-128-ECB, blocs de 16, IN PLACE.
   *
   * <p>Port sémantique de {@code aes_encrypt_idx}/{@code aes_decrypt_idx} : ECB pur,
   * chaque bloc indépendant — le déchiffrement incrémental par blocs est donc valide
   * (camd35_recv déchiffre les 32 premiers octets puis le reste à l'arrivée, l.311/387).
   *
   * @param data   buffer (longueur multiple de 16), muté sur place
   * @param key16  clé AES 16 octets
   * @param encrypt true = chiffrer, false = déchiffrer
   */
  public static void aesEcbInPlace(byte[] data, byte[] key16, boolean encrypt) {
    if (data.length % Camd35Constants.AES_BLOCK != 0) {
      throw new IllegalArgumentException("AES-ECB buffer must be 16-byte aligned: " + data.length);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
          new SecretKeySpec(key16, "AES"));
      int off = 0;
      while (off < data.length) {
        cipher.doFinal(data, off, Camd35Constants.AES_BLOCK, data, off);
        off += Camd35Constants.AES_BLOCK;
      }
    } catch (Exception e) {
      throw new IllegalStateException("AES-128-ECB failed", e);
    }
  }

  /**
   * CRC32 (poly standard, reflect, init 0 — équivalent {@code crc32(0L, ...)} d'OSCam/zlib)
   * sur un buffer. Utilisé pour le champ [4..7] (CRC des DATA uniquement — l.68 :
   * {@code i2b_buf(4, crc32(0L, sbuf + 20, buflen), sbuf + 4)}).
   */
  public static long crc32(byte[] data) {
    return crc32(data, 0, data.length);
  }

  /** CRC32 sur une portion de buffer. */
  public static long crc32(byte[] data, int off, int len) {
    CRC32 crc = new CRC32();
    crc.update(data, off, len);
    return crc.getValue();
  }

  /** CRC32 en 4 octets BE (format du champ [4..7]). */
  public static byte[] crc32Bytes(byte[] data, int off, int len) {
    long v = crc32(data, off, len);
    return new byte[] { (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
  }

  private static byte[] md5(byte[] data) {
    try {
      return MessageDigest.getInstance("MD5").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("MD5 unavailable", e);
    }
  }
}
