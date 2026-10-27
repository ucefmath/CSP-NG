package com.bowman.cspng.codec.cccam;

/**
 * Scrambling/Déscrambling des Control Words (CW) du protocole CCcam.
 *
 * <h3>Port exact de {@code cc_cw_crypt} (module-cccam.c l.125-180)</h3>
 *
 * <p>Cette opération est auto-inverse (type XOR) : appliquer deux fois
 * avec le même node_id et card_id redonne l'original.
 *
 * <p>Utilisée en émission (côté serveur, avant envoi du CW) ET en
 * réception (côté client, après réception du CW).
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class CcCwCrypt {

  private CcCwCrypt() {}

  /**
   * Applique le scrambling/déscrambling CW CCcam.
   *
   * <p>Port exact de {@code cc_cw_crypt}.
   * La formule utilise le node_id <strong>INVERSÉ</strong> du pair
   * (côté client : son PROPRE node_id inversé ; côté serveur : le
   * peer_node_id inversé).
   *
   * <p>Opération IN PLACE sur le tableau {@code cws}.
   *
   * @param cws    tableau de 16 octets (CW), modifié sur place
   * @param nodeId node_id de 8 octets (sera inversé en interne)
   * @param cardId card_id 32 bits ciblé
   */
  public static void cwCrypt(byte[] cws, byte[] nodeId, int cardId) {
    if (cws.length != 16) {
      throw new IllegalArgumentException("cws must be 16 bytes");
    }
    if (nodeId.length != 8) {
      throw new IllegalArgumentException("nodeId must be 8 bytes");
    }

    // Inverse le node_id (le pair distant pour le client, ou son propre node_id pour le serveur)
    byte[] nod = new byte[8];
    for (int i = 0; i < 8; i++) {
      nod[i] = nodeId[7 - i];
    }

    for (int i = 0; i < 16; i++) {
      int n;
      if ((i & 1) != 0) {
        // index impair
        if (i != 15) {
          n = ((nod[i >> 1] & 0xFF) >> 4) | ((nod[(i >> 1) + 1] & 0xFF) << 4);
        } else {
          n = (nod[i >> 1] & 0xFF) >> 4;
        }
      } else {
        // index pair
        n = nod[i >> 1] & 0xFF;
      }

      n &= 0xFF;
      int tmp = (cws[i] & 0xFF) ^ n;

      if ((i & 1) != 0) {
        tmp = (~tmp) & 0xFF; // complément à 1 sur les index impairs
      }

      // ⚠️ >>> (unsigned shift) obligatoire : cardId est un uint32_t en C,
      // donc le décalage ne doit jamais propager le bit de signe Java.
      cws[i] = (byte) (((cardId >>> (2 * i)) ^ tmp) & 0xFF);
    }
  }
}
