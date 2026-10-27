package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;

/**
 * Validations CW/DCW pures portées depuis {@code CamdNetMessage}.
 *
 * <p>Ces méthodes reproduisent le comportement exact du legacy (§2.4) :
 * <ul>
 *   <li>{@link #checksumDcw(byte[])} : 4ᵉ octet = somme des 3 précédents modulo 256.</li>
 *   <li>{@link #hasFiveZeroes(byte[])} : 5 à 7 zéros dans une demi-CW rejetés, 8 zéros tolérés.</li>
 *   <li>{@link #hasZeroDcw(byte[])} : une demi-CW entièrement à zéro.</li>
 *   <li>{@link #equalsSingleDcw(byte[], byte[])} : au moins une des deux demi-CW de 8 octets
 *       correspond, en ignorant une moitié entièrement nulle.</li>
 * </ul>
 *
 * <p>Le record {@link CwReply} ne portant pas de méthodes, ces helpers statiques sont fournis
 * dans {@code csp-cache} sans toucher à {@code csp-domain}.
 */
public final class DcwValidator {

  private DcwValidator() {
  }

  public static boolean checksumDcw(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    for (int i = 0; i < 16; i += 4) {
      int expected = ((cw[i] & 0xFF) + (cw[i + 1] & 0xFF) + (cw[i + 2] & 0xFF)) & 0xFF;
      if ((cw[i + 3] & 0xFF) != expected) {
        return false;
      }
    }
    return true;
  }

  public static boolean hasFiveZeroes(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    int cw1c = 0;
    for (int i = 0; i < 8; i++) if (cw[i] == 0) cw1c++;
    if (cw1c < 8 && cw1c > 4) return true;
    int cw2c = 0;
    for (int i = 8; i < 16; i++) if (cw[i] == 0) cw2c++;
    return cw2c < 8 && cw2c > 4;
  }

  public static boolean hasZeroDcw(byte[] cw) {
    if (cw == null || cw.length != 16) return false;
    int c = 0;
    for (int i = 0; i < 8; i++) c += cw[i] & 0xFF;
    if (c == 0) return true;
    c = 0;
    for (int i = 8; i < 16; i++) c += cw[i] & 0xFF;
    return c == 0;
  }

  /**
   * Compare deux CW : retourne vrai si au moins une demi-CW de 8 octets correspond,
   * en ignorant les demi-CW entièrement nulles.
   *
   * <p>Legacy : {@code CamdNetMessage.equalsSingleDcw} l.605-626.
   */
  public static boolean equalsSingleDcw(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != 16 || b.length != 16) return false;
    boolean cw1eq = true;
    int cz = 0;
    for (int i = 0; i < 8; i++) {
      if (a[i] != b[i]) {
        cw1eq = false;
        break;
      } else {
        cz += (a[i] & 0xFF) + (b[i] & 0xFF);
      }
    }
    if (cw1eq && cz == 0) cw1eq = false;
    boolean cw2eq = true;
    cz = 0;
    for (int i = 8; i < 16; i++) {
      if (a[i] != b[i]) {
        cw2eq = false;
        break;
      } else {
        cz += (a[i] & 0xFF) + (b[i] & 0xFF);
      }
    }
    if (cw2eq && cz == 0) cw2eq = false;
    return cw1eq || cw2eq;
  }

  public static boolean equalsSingleDcw(CwReply a, CwReply b) {
    if (a == null || b == null) return false;
    return equalsSingleDcw(a.cw(), b.cw());
  }

  public static boolean hasZeroDcw(CwReply reply) {
    return reply != null && hasZeroDcw(reply.cw());
  }
}
