package com.bowman.cspng.users.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Port à l'identique de {@code src/com/bowman/cardserv/crypto/MD5Crypt.java} legacy.
 * Comportement conservé y compris la troncature sel à 8 chars, le bug {@code finalState}
 * zeroé avant usage dans la boucle {@code i}, et l'alphabet {@code ./0-9A-Za-z}.
 *
 * <p>Charset : le legacy utilise {@code String.getBytes()} sans charset (plateforme).
 * Sur Linux UTF-8 cela équivaut à UTF-8. On explicite {@link StandardCharsets#UTF_8}
 * car les vecteurs golden restent identiques pour ASCII et UTF-8 (testé).
 * Pour migration d'un historique encodé en CP1252, recalculer les hash via
 * {@code new String(oldBytes, Charset.forName("windows-1252"))} puis re-hacher en UTF-8.
 */
public final class MD5Crypt {

  private static final String B64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final String MAGIC = "$1$";

  private MD5Crypt() {}

  private static String toBase64(long v, int size) {
    StringBuilder result = new StringBuilder();
    while (--size >= 0) {
      result.append(B64.charAt((int) (v & 0x3f)));
      v >>>= 6;
    }
    return result.toString();
  }

  private static int unsignExtend(byte b) {
    return b & 0xff;
  }

  public static String crypt(String password, String salt) {
    if (password == null) password = "";
    if (salt == null) salt = "";
    if (salt.startsWith(MAGIC)) salt = salt.substring(MAGIC.length());
    if (salt.indexOf('$') != -1) salt = salt.substring(0, salt.indexOf('$'));
    if (salt.length() > 8) salt = salt.substring(0, 8);

    try {
      MessageDigest md1 = MessageDigest.getInstance("MD5");
      md1.reset();
      md1.update(password.getBytes(StandardCharsets.UTF_8));
      md1.update(MAGIC.getBytes(StandardCharsets.UTF_8));
      md1.update(salt.getBytes(StandardCharsets.UTF_8));

      MessageDigest md2 = MessageDigest.getInstance("MD5");
      md2.update(password.getBytes(StandardCharsets.UTF_8));
      md2.update(salt.getBytes(StandardCharsets.UTF_8));
      md2.update(password.getBytes(StandardCharsets.UTF_8));

      byte[] finalState = md2.digest();
      for (int pl = password.length(); pl > 0; pl -= 16) {
        md1.update(finalState, 0, pl > 16 ? 16 : pl);
      }
      Arrays.fill(finalState, (byte) 0);

      for (int i = password.length(); i != 0; i >>>= 1) {
        if ((i & 1) != 0) {
          md1.update(finalState, 0, 1);
        } else {
          md1.update(password.getBytes(StandardCharsets.UTF_8), 0, 1);
        }
      }
      finalState = md1.digest();

      for (int i = 0; i < 1000; i++) {
        md2 = MessageDigest.getInstance("MD5");
        if ((i & 1) != 0) {
          md2.update(password.getBytes(StandardCharsets.UTF_8));
        } else {
          md2.update(finalState, 0, 16);
        }
        if ((i % 3) != 0) md2.update(salt.getBytes(StandardCharsets.UTF_8));
        if ((i % 7) != 0) md2.update(password.getBytes(StandardCharsets.UTF_8));
        if ((i & 1) != 0) {
          md2.update(finalState, 0, 16);
        } else {
          md2.update(password.getBytes(StandardCharsets.UTF_8));
        }
        finalState = md2.digest();
      }

      StringBuilder result = new StringBuilder();
      result.append(MAGIC);
      result.append(salt);
      result.append("$");

      long l = (unsignExtend(finalState[0]) << 16) | (unsignExtend(finalState[6]) << 8) | unsignExtend(finalState[12]);
      result.append(toBase64(l, 4));
      l = (unsignExtend(finalState[1]) << 16) | (unsignExtend(finalState[7]) << 8) | unsignExtend(finalState[13]);
      result.append(toBase64(l, 4));
      l = (unsignExtend(finalState[2]) << 16) | (unsignExtend(finalState[8]) << 8) | unsignExtend(finalState[14]);
      result.append(toBase64(l, 4));
      l = (unsignExtend(finalState[3]) << 16) | (unsignExtend(finalState[9]) << 8) | unsignExtend(finalState[15]);
      result.append(toBase64(l, 4));
      l = (unsignExtend(finalState[4]) << 16) | (unsignExtend(finalState[10]) << 8) | unsignExtend(finalState[5]);
      result.append(toBase64(l, 4));
      l = unsignExtend(finalState[11]);
      result.append(toBase64(l, 2));

      Arrays.fill(finalState, (byte) 0);
      return result.toString();

    } catch (NoSuchAlgorithmException e) {
      // MD5 always available
      throw new IllegalStateException(e);
    }
  }

  /**
   * Extrait le sel normalisé depuis un hash complet {@code $1$salt$hash}.
   */
  public static String extractSalt(String hash) {
    if (hash == null || !hash.startsWith(MAGIC)) return null;
    String rest = hash.substring(MAGIC.length());
    int dollar = rest.indexOf('$');
    if (dollar == -1) return null;
    String salt = rest.substring(0, dollar);
    if (salt.length() > 8) salt = salt.substring(0, 8);
    return salt;
  }
}
