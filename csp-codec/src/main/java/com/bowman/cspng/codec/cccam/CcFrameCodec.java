package com.bowman.cspng.codec.cccam;

/**
 * Encodeur/décodeur du framing CCcam (en-tête 4 octets + payload).
 *
 * <p>Format de l'en-tête (network byte order / big-endian) :
 * <pre>
 *   [0] = flag (réservé, 0 en mode non-étendu)
 *   [1] = commande (cc_msg_type_t)
 *   [2..3] = longueur payload (BE, uint16)
 * </pre>
 *
 * <p>Le framing lui-même est indépendant du chiffrement. Le chiffrement/déchiffrement
 * est appliqué AU-DESSUS du framing par l'appelant (le connecteur).
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class CcFrameCodec {

  private CcFrameCodec() {}

  /**
   * Encode une trame CCcam : en-tête 4 octets + payload.
   *
   * @param flag    flag (0 en mode normal)
   * @param cmd     type de commande
   * @param payload données utiles (peut être null ou vide)
   * @return trame complète
   */
  public static byte[] encode(int flag, int cmd, byte[] payload) {
    int payloadLen = (payload != null) ? payload.length : 0;
    byte[] frame = new byte[4 + payloadLen];
    frame[0] = (byte) (flag & 0xFF);
    frame[1] = (byte) (cmd & 0xFF);
    frame[2] = (byte) ((payloadLen >> 8) & 0xFF);
    frame[3] = (byte) (payloadLen & 0xFF);
    if (payload != null && payloadLen > 0) {
      System.arraycopy(payload, 0, frame, 4, payloadLen);
    }
    return frame;
  }

  /**
   * Encode une trame CCcam sans flag (flag = 0).
   *
   * @param cmd     type de commande
   * @param payload données utiles
   * @return trame complète
   */
  public static byte[] encode(int cmd, byte[] payload) {
    return encode(CcConstants.FLAG_NORMAL, cmd, payload);
  }

  /**
   * Extrait la commande et la longueur payload de l'en-tête.
   *
   * @param header 4 octets d'en-tête (déchiffrés)
   * @return tableau [flag, cmd, payloadLength]
   */
  public static int[] decodeHeader(byte[] header) {
    if (header.length < 4) {
      throw new IllegalArgumentException("Header must be at least 4 bytes, got " + header.length);
    }
    int flag = header[0] & 0xFF;
    int cmd = header[1] & 0xFF;
    int payloadLen = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
    return new int[]{flag, cmd, payloadLen};
  }

  /**
   * Vérifie si une commande est connue et gérée par le MVP.
   */
  public static boolean isKnownCommand(int cmd) {
    return switch (cmd) {
      case CcConstants.MSG_CLI_DATA,
           CcConstants.MSG_CW_ECM,
           CcConstants.MSG_EMM_ACK,
           CcConstants.MSG_CARD_REMOVED,
           CcConstants.MSG_CMD_05,
           CcConstants.MSG_KEEPALIVE,
           CcConstants.MSG_NEW_CARD,
           CcConstants.MSG_SRV_DATA,
           CcConstants.MSG_CMD_0A,
           CcConstants.MSG_CMD_0B,
           CcConstants.MSG_CACHE_PUSH,
           CcConstants.MSG_CACHE_FILTER,
           CcConstants.MSG_CACHE_FEATURE_EXCHANGE,
           CcConstants.MSG_CACHE_FEATURE_EXCHANGE_REPLY,
           CcConstants.MSG_CACHE_FEATURE_TRIGGER,
           CcConstants.MSG_CW_NOK1,
           CcConstants.MSG_CW_NOK2 -> true;
      default -> false;
    };
  }

  /**
   * Retourne le nom lisible d'une commande pour les logs.
   */
  public static String cmdName(int cmd) {
    return switch (cmd) {
      case CcConstants.MSG_CLI_DATA -> "CLI_DATA";
      case CcConstants.MSG_CW_ECM -> "CW_ECM";
      case CcConstants.MSG_EMM_ACK -> "EMM_ACK";
      case CcConstants.MSG_CARD_REMOVED -> "CARD_REMOVED";
      case CcConstants.MSG_CMD_05 -> "CMD_05";
      case CcConstants.MSG_KEEPALIVE -> "KEEPALIVE";
      case CcConstants.MSG_NEW_CARD -> "NEW_CARD";
      case CcConstants.MSG_SRV_DATA -> "SRV_DATA";
      case CcConstants.MSG_CMD_0A -> "CMD_0A";
      case CcConstants.MSG_CMD_0B -> "CMD_0B";
      case CcConstants.MSG_CMD_0C -> "CMD_0C";
      case CcConstants.MSG_CMD_0D -> "CMD_0D";
      case CcConstants.MSG_CMD_0E -> "CMD_0E";
      case CcConstants.MSG_CACHE_PUSH -> "CACHE_PUSH";
      case CcConstants.MSG_CACHE_FILTER -> "CACHE_FILTER";
      case CcConstants.MSG_CACHE_FEATURE_EXCHANGE -> "CACHE_FEATURE_EXCHANGE";
      case CcConstants.MSG_CACHE_FEATURE_EXCHANGE_REPLY -> "CACHE_FEATURE_REPLY";
      case CcConstants.MSG_CACHE_FEATURE_TRIGGER -> "CACHE_FEATURE_TRIGGER";
      case CcConstants.MSG_CW_NOK1 -> "CW_NOK1";
      case CcConstants.MSG_CW_NOK2 -> "CW_NOK2";
      default -> String.format("UNKNOWN(0x%02X)", cmd);
    };
  }
}
