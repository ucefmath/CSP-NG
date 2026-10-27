package com.bowman.cspng.codec.newcamd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Implémentation pure Java de l'algorithme md5-crypt ($1$ FreeBSD/OSCam).
 * Utilisé pour le hachage de mot de passe Newcamd avec sel fixe "$1$abcdefgh$".
 */
public final class Md5Crypt {

  private static final String MAGIC = "$1$";
  private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
  private static final char[] ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

  public static final String DEFAULT_SALT = "$1$abcdefgh$";

  private Md5Crypt() {}

  /**
   * Hache un mot de passe avec le sel par défaut "$1$abcdefgh$".
   */
  public static String md5Crypt(String password) {
    return md5Crypt(password, DEFAULT_SALT);
  }

  /**
   * Hache un mot de passe avec un sel personnalisé.
   */
  public static String md5Crypt(String password, String salt) {
    if (password == null) password = "";
    if (salt == null) salt = DEFAULT_SALT;

    byte[] pwBytes = password.getBytes(StandardCharsets.UTF_8);

    // Raffiner le sel
    String sp = salt;
    if (sp.startsWith(MAGIC)) {
      sp = sp.substring(MAGIC.length());
    }
    int dollarIdx = sp.indexOf('$');
    if (dollarIdx != -1) {
      sp = sp.substring(0, dollarIdx);
    }
    if (sp.length() > 8) {
      sp = sp.substring(0, 8);
    }
    byte[] saltBytes = sp.getBytes(StandardCharsets.UTF_8);

    try {
      MessageDigest ctx = MessageDigest.getInstance("MD5");
      ctx.update(pwBytes);
      ctx.update(MAGIC_BYTES);
      ctx.update(saltBytes);

      MessageDigest ctx1 = MessageDigest.getInstance("MD5");
      ctx1.update(pwBytes);
      ctx1.update(saltBytes);
      ctx1.update(pwBytes);
      byte[] finalBytes = ctx1.digest();

      int pwLen = pwBytes.length;
      for (int pl = pwLen; pl > 0; pl -= 16) {
        ctx.update(finalBytes, 0, Math.min(pl, 16));
      }

      byte[] zeroByte = new byte[]{ 0 };
      for (int i = pwLen; i > 0; i >>= 1) {
        if ((i & 1) != 0) {
          ctx.update(zeroByte, 0, 1);
        } else {
          ctx.update(pwBytes, 0, 1);
        }
      }

      finalBytes = ctx.digest();

      // 1000 itérations
      for (int i = 0; i < 1000; i++) {
        ctx1.reset();
        if ((i & 1) != 0) {
          ctx1.update(pwBytes);
        } else {
          ctx1.update(finalBytes, 0, 16);
        }

        if (i % 3 != 0) {
          ctx1.update(saltBytes);
        }

        if (i % 7 != 0) {
          ctx1.update(pwBytes);
        }

        if ((i & 1) != 0) {
          ctx1.update(finalBytes, 0, 16);
        } else {
          ctx1.update(pwBytes);
        }

        finalBytes = ctx1.digest();
      }

      StringBuilder out = new StringBuilder();
      out.append(MAGIC).append(sp).append("$");

      byte[] expanded = new byte[17];
      System.arraycopy(finalBytes, 0, expanded, 0, 16);
      expanded[16] = expanded[5];

      for (int i = 0; i < 5; i++) {
        int l = ((expanded[i] & 0xFF) << 16) | ((expanded[i + 6] & 0xFF) << 8) | (expanded[i + 12] & 0xFF);
        to64(out, l, 4);
      }
      int l = expanded[11] & 0xFF;
      to64(out, l, 2);

      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 MessageDigest not available", e);
    }
  }

  private static void to64(StringBuilder sb, int v, int n) {
    while (--n >= 0) {
      sb.append(ITOA64[v & 0x3f]);
      v >>= 6;
    }
  }
}
