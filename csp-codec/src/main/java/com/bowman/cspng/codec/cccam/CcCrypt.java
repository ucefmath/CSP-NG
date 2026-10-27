package com.bowman.cspng.codec.cccam;

import java.util.Arrays;

/**
 * Implémentation du chiffrement de flux CCcam (RC4-like avec état de feedback).
 *
 * <h3>Port exact de {@code cc_init_crypt} / {@code cc_crypt} / {@code cc_rc4_crypt} / {@code cc_xor}</h3>
 *
 * <p>Ce N'EST PAS du RC4 standard. Différences critiques :
 * <ul>
 *   <li>L'état initial ({@code state}) est le PREMIER octet de la clé, pas 0.</li>
 *   <li>Un XOR supplémentaire {@code data[i] ^= state} est appliqué après le XOR
 *       standard RC4 (sauf dans la variante {@code rc4Crypt}).</li>
 *   <li>Le feedback de l'état dépend du mode (DECRYPT=0 ou ENCRYPT=1) :
 *     <ul>
 *       <li>ENCRYPT : {@code state ^= z} où z est l'octet CLAIR avant chiffrement.</li>
 *       <li>DECRYPT : {@code state ^= z} où z est l'octet DÉCHIFFRÉ après chiffrement.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Chaque instance est mutable et représente UN bloc de chiffrement directionnel.
 * Deux instances sont nécessaires par connexion : une pour l'envoi (ENCRYPT) et
 * une pour la réception (DECRYPT).
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class CcCrypt {

  /** Table de 256 octets pour l'état RC4. */
  public final byte[] keytable;

  /** État de feedback (n'existe pas dans RC4 standard). */
  public byte state;

  /** Compteur RC4 (i dans l'algo standard). */
  public byte counter;

  /** Somme RC4 (j dans l'algo standard). */
  public byte sum;

  /** Crée un nouveau bloc non initialisé. */
  public CcCrypt() {
    this.keytable = new byte[256];
  }

  /**
   * Initialise le bloc de chiffrement avec une clé.
   *
   * <p>Port exact de {@code cc_init_crypt} (module-cccam.c l.41-60).
   *
   * @param key la clé
   * @param len longueur utile de la clé
   */
  public void initCrypt(byte[] key, int len) {
    // KSA standard RC4
    for (int i = 0; i < 256; i++) {
      keytable[i] = (byte) i;
    }

    int j = 0;
    for (int i = 0; i < 256; i++) {
      j = (j + (key[i % len] & 0xFF) + (keytable[i] & 0xFF)) & 0xFF;
      swap(i, j);
    }

    // ⚠️ PAS standard RC4 : état initial = 1er octet de la clé
    this.state = key[0];
    this.counter = 0;
    this.sum = 0;
  }

  /**
   * Chiffre/déchiffre un buffer avec le chiffrement CCcam (RC4-like avec feedback).
   *
   * <p>Port exact de {@code cc_crypt} (module-cccam.c l.62-83).
   *
   * @param data buffer à transformer IN PLACE
   * @param len  nombre d'octets à traiter
   * @param mode {@link CcConstants#DECRYPT} ou {@link CcConstants#ENCRYPT}
   */
  public void crypt(byte[] data, int len, int mode) {
    byte z;
    for (int i = 0; i < len; i++) {
      counter++;
      sum += keytable[counter & 0xFF];
      swap(counter & 0xFF, sum & 0xFF);

      z = data[i];
      // XOR standard RC4 : output = keytable[(keytable[i] + keytable[j]) & 0xff]
      data[i] = (byte) (z ^ keytable[(keytable[counter & 0xFF] + keytable[sum & 0xFF]) & 0xFF]);
      // ⚠️ XOR supplémentaire avec state (PAS dans RC4 standard)
      data[i] ^= state;

      // Feedback : l'octet déchiffré est utilisé pour mettre à jour l'état
      if (mode == CcConstants.DECRYPT) {
        z = data[i]; // en mode DECRYPT, feedback = octet DÉCHIFFRÉ
      }
      // en mode ENCRYPT, z reste l'octet CLAIR (avant chiffrement)

      state ^= z;
    }
  }

  /**
   * Chiffre/déchiffre un buffer avec le chiffrement RC4 CCcam (sans l'étape
   * {@code data[i] ^= state}).
   *
   * <p>Utilisé uniquement pour CMD_05 mode RC4.
   * Port exact de {@code cc_rc4_crypt} (module-cccam.c l.86-107).
   *
   * @param data buffer à transformer IN PLACE
   * @param len  nombre d'octets à traiter
   * @param mode {@link CcConstants#DECRYPT} ou {@link CcConstants#ENCRYPT}
   */
  public void rc4Crypt(byte[] data, int len, int mode) {
    byte z;
    for (int i = 0; i < len; i++) {
      counter++;
      sum += keytable[counter & 0xFF];
      swap(counter & 0xFF, sum & 0xFF);

      z = data[i];
      data[i] = (byte) (z ^ keytable[(keytable[counter & 0xFF] + keytable[sum & 0xFF]) & 0xFF]);
      // PAS de data[i] ^= state ici

      if (mode == CcConstants.DECRYPT) {
        z = data[i];
      }

      state ^= z;
    }
  }

  /**
   * Obfuscation du seed initial du handshake.
   *
   * <p>Port exact de {@code cc_xor} (module-cccam.c l.109-123).
   *
   * @param buf buffer de 16 octets, modifié IN PLACE
   */
  public static void xor(byte[] buf) {
    final byte[] cccam = CcConstants.CCCAM_MAGIC;
    for (int i = 0; i < 8; i++) {
      buf[8 + i] = (byte) (i * buf[i]);
      if (i <= 5) {
        buf[i] ^= cccam[i];
      }
    }
  }

  /**
   * Échange deux octets dans la table de clés.
   */
  private void swap(int i, int j) {
    byte p = keytable[i];
    keytable[i] = keytable[j];
    keytable[j] = p;
  }

  /**
   * Crée une copie profonde de ce bloc de chiffrement.
   * Utile pour les tests (capture d'état).
   */
  public CcCrypt copy() {
    CcCrypt copy = new CcCrypt();
    System.arraycopy(this.keytable, 0, copy.keytable, 0, 256);
    copy.state = this.state;
    copy.counter = this.counter;
    copy.sum = this.sum;
    return copy;
  }

  @Override
  public String toString() {
    return String.format("CcCrypt[state=%02x,counter=%02x,sum=%02x]",
        state & 0xFF, counter & 0xFF, sum & 0xFF);
  }
}
