package com.bowman.cspng.domain.dcw;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Calcul et validation des empreintes SHA-256 pour les Control Words (DCW).
 */
public final class DcwDigest {

  private static final HexFormat HF = HexFormat.of();

  private DcwDigest() {}

  /**
   * Calcule l'empreinte SHA-256 en hexadécimal minuscule (64 caractères).
   */
  public static String digestSha256Hex(byte[] cw) {
    if (cw == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(cw);
      return HF.formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /**
   * Vérifie si une chaîne est une empreinte SHA-256 hexadécimale valide (64 caractères hex).
   */
  public static boolean isValidSha256Hex(String hex) {
    if (hex == null || hex.length() != 64) return false;
    for (int i = 0; i < 64; i++) {
      char c = hex.charAt(i);
      boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!isHex) return false;
    }
    return true;
  }

  /**
   * Normalise une chaîne SHA-256 hex en minuscules, ou rejette si invalide.
   */
  public static String normalizeSha256Hex(String hex) {
    if (!isValidSha256Hex(hex)) {
      throw new IllegalArgumentException("digest_sha256 must be 64 hex characters");
    }
    return hex.toLowerCase();
  }
}
