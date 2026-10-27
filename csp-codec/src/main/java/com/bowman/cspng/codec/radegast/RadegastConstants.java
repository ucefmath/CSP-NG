package com.bowman.cspng.codec.radegast;

/**
 * Constantes et utilitaires pour le protocole Radegast.
 *
 * <p>Le protocole Radegast est un TLV simple sans chiffrement :
 * <ul>
 *   <li>Requête du client : en-tête 1 octet (type=0x01 CMD_ECM_KEY_ASK),
 *       1 octet de longueur, puis corps TLV.</li>
 *   <li>Réponse vers le client : en-tête 1 octet (type=0x02 CMD_ECM_KEY_RESPONSE),
 *       1 octet de longueur, puis 1 octet de statut (0x04=cannot decode, 0x05=success),
 *       1 octet de longueur CW (0 ou 16), puis les 16 octets de CW si succès.</li>
 *   <li>Réponse du serveur upstream (connector side) : en-tête 0x02 puis
 *       statut 0x04 (échec) ou 0x05 (succès + 16 octets CW).</li>
 * </ul>
 *
 * <p>Tags TLV connus dans le corps de requête :
 * <ul>
 *   <li>0x02 — CAID_INDEX (ignore)</li>
 *   <li>0x06 — PROVIDER (8 octets, string hexa)</li>
 *   <li>0x07 — KEYNO (4 octets, string hexa "0000")</li>
 *   <li>0x08 — LOOP (1 octet, valeur 1)</li>
 *   <li>0x09 — TRACK (ignore)</li>
 *   <li>0x0A — CAID (2 octets)</li>
 *   <li>0x10 — SOURCE_TXT (ignore)</li>
 *   <li>0x03 — PACKET : ECM data (1 octet commandTag + 2 padding + N octets customData)</li>
 *   <li>0x21 — EXTRA : serviceId (2 octets)</li>
 * </ul>
 */
public final class RadegastConstants {

  private RadegastConstants() {}

  /** Type de trame : requête ECM. */
  public static final int CMD_ECM_KEY_ASK = 0x01;

  /** Type de trame : réponse ECM. */
  public static final int CMD_ECM_KEY_RESPONSE = 0x02;

  /** Statut réponse : échec (cannot decode). */
  public static final int REPLY_STATUS_CANNOT_DECODE = 0x04;

  /** Statut réponse : succès (CW trouvée). */
  public static final int REPLY_STATUS_SUCCESS = 0x05;

  /** Tags TLV connus. */
  public static final int TAG_CAID_INDEX = 0x02;
  public static final int TAG_PACKET = 0x03;
  public static final int TAG_PROVIDER = 0x06;
  public static final int TAG_KEYNO = 0x07;
  public static final int TAG_LOOP = 0x08;
  public static final int TAG_TRACK = 0x09;
  public static final int TAG_CAID = 0x0A;
  public static final int TAG_SOURCE_TXT = 0x10;
  public static final int TAG_EXTRA = 0x21;

  /** Taille d'une CW en octets. */
  public static final int CW_LENGTH = 16;

  // ============================================================
  // Méthodes utilitaires
  // ============================================================

  /**
   * Construit une trame de réponse (serveur → client Radegast).
   *
   * @param cw les 16 octets de la CW, ou un tableau vide pour "cannot decode"
   * @return la trame complète prête à être envoyée sur le socket
   */
  public static byte[] buildReplyFrame(byte[] cw) {
    byte[] buf;
    if (cw == null || cw.length == 0) {
      // Cannot decode: status=4, len=0
      buf = new byte[4];
      buf[0] = (byte) CMD_ECM_KEY_RESPONSE;
      buf[1] = 2; // longueur du reste
      buf[2] = (byte) REPLY_STATUS_CANNOT_DECODE;
      buf[3] = 0; // CW length = 0
    } else {
      // Success: status=5, len=16, CW
      buf = new byte[4 + CW_LENGTH];
      buf[0] = (byte) CMD_ECM_KEY_RESPONSE;
      buf[1] = (byte) (2 + CW_LENGTH); // longueur du reste
      buf[2] = (byte) REPLY_STATUS_SUCCESS;
      buf[3] = CW_LENGTH;
      System.arraycopy(cw, 0, buf, 4, Math.min(cw.length, CW_LENGTH));
    }
    return buf;
  }

  /**
   * Construit une trame de requête ECM (connector → serveur upstream Radegast).
   *
   * <p>Reproduit le format exact de {@code RadegastCwsConnector.sendMessage} :
   * tag 0x01 + longueur + TLV fields (CAID_INDEX, PROVIDER, KEYNO, LOOP, CAID,
   * EXTRA si sid≠0, PACKET contenant commandTag + customData).
   *
   * @param caId CA ID (2 octets)
   * @param providerIdent provider ident (int 24 bits, toujours "00000000" si inconnu)
   * @param serviceId service ID (0 si inconnu)
   * @param commandTag tag ECM (0x80 pour impair, 0x81 pour pair)
   * @param customData données ECM brutes
   * @return trame complète
   */
  public static byte[] buildRequestFrame(int caId, int providerIdent,
                                          int serviceId, int commandTag,
                                          byte[] customData) {
    int hdrSize = (serviceId == 0) ? 33 : 37;
    int dataLen = (customData != null) ? customData.length : 0;
    byte[] buf = new byte[hdrSize + dataLen];

    buf[0] = (byte) CMD_ECM_KEY_ASK;
    buf[1] = (byte) (buf.length - 2); // len

    // CAID_INDEX (tag 0x02)
    buf[2] = (byte) TAG_CAID_INDEX;
    buf[3] = 1; // len
    buf[4] = (byte) ((caId >> 8) & 0xFF);

    // PROVIDER (tag 0x06)
    buf[5] = (byte) TAG_PROVIDER;
    buf[6] = 8; // len
    String providerStr = String.format("%08x", providerIdent & 0xFFFFFF);
    System.arraycopy(providerStr.getBytes(), 0, buf, 7, 8);

    // KEYNO (tag 0x07)
    buf[15] = (byte) TAG_KEYNO;
    buf[16] = 4; // len
    buf[17] = '0'; buf[18] = '0'; buf[19] = '0'; buf[20] = '0';

    // LOOP (tag 0x08)
    buf[21] = (byte) TAG_LOOP;
    buf[22] = 1; // len
    buf[23] = 1;

    // CAID (tag 0x0A)
    buf[24] = (byte) TAG_CAID;
    buf[25] = 2; // len
    buf[26] = (byte) ((caId >> 8) & 0xFF);
    buf[27] = (byte) (caId & 0xFF);

    int offs = 28;

    if (serviceId != 0) {
      buf[28] = (byte) TAG_EXTRA;
      buf[29] = 2; // len
      buf[30] = (byte) ((serviceId >> 8) & 0xFF);
      buf[31] = (byte) (serviceId & 0xFF);
      offs = 32;
    }

    // PACKET (tag 0x03)
    buf[offs] = (byte) TAG_PACKET;
    buf[offs + 1] = (byte) (dataLen + 3);
    buf[offs + 2] = (byte) commandTag;
    buf[offs + 3] = 0x70; // constant legacy
    buf[offs + 4] = (byte) dataLen;

    if (dataLen > 0) {
      System.arraycopy(customData, 0, buf, hdrSize, dataLen);
    }

    return buf;
  }

  /**
   * Parse une réponse d'un serveur upstream. Extrait la CW si succès.
   *
   * @param buf la trame reçue (header 0x02 + len + status + cwLen + cw)
   * @return les 16 octets de CW, ou tableau vide si échec
   */
  public static byte[] parseReplyCw(byte[] buf) {
    if (buf.length < 4) return new byte[0];
    int status = buf[2] & 0xFF;
    if (status == REPLY_STATUS_SUCCESS && buf.length >= 4 + CW_LENGTH) {
      byte[] cw = new byte[CW_LENGTH];
      System.arraycopy(buf, 4, cw, 0, CW_LENGTH);
      return cw;
    }
    return new byte[0];
  }

  /**
   * Parse une requête entrante d'un client Radegast. Extrait les champs clés.
   */
  public static RadegastRequest parseRequest(byte[] raw) {
    int commandTag = -1, caId = 0, serviceId = 0;
    byte[] customData = null;
    byte[] providerId = new byte[8];
    byte[] keyNr = new byte[4];

    if (raw.length < 3) return null;
    int len = raw[1] & 0xFF;
    if (len < 2) return null;

    int n = 2;
    int maxN = Math.min(raw.length, 2 + len);

    while (n < maxN - 1) {
      int tag = raw[n] & 0xFF;
      n++;
      if (n >= maxN) break;
      int fieldLen = raw[n] & 0xFF;
      n++;

      int end = n + fieldLen;
      if (end > maxN) break;

      switch (tag) {
        case TAG_PACKET:
          if (fieldLen >= 3) {
            commandTag = raw[n] & 0xFF;
            customData = new byte[fieldLen - 3];
            System.arraycopy(raw, n + 3, customData, 0, customData.length);
          }
          break;
        case TAG_CAID:
          if (fieldLen == 2) {
            caId = ((raw[n] & 0xFF) << 8) | (raw[n + 1] & 0xFF);
          }
          break;
        case TAG_PROVIDER:
          if (fieldLen == 8) {
            System.arraycopy(raw, n, providerId, 0, 8);
          }
          break;
        case TAG_KEYNO:
          if (fieldLen == 4) {
            System.arraycopy(raw, n, keyNr, 0, 4);
          }
          break;
        case TAG_EXTRA:
          if (fieldLen == 2) {
            serviceId = ((raw[n] & 0xFF) << 8) | (raw[n + 1] & 0xFF);
          }
          break;
        default:
          // Skip unknown tags
          break;
      }
      n = end;
    }

    return new RadegastRequest(commandTag, caId, serviceId,
        providerId, keyNr, customData != null ? customData : new byte[0]);
  }

  /**
   * Représentation d'une requête Radegast parsée.
   */
  public record RadegastRequest(
      int commandTag,
      int caId,
      int serviceId,
      byte[] providerId,
      byte[] keyNumber,
      byte[] customData
  ) {
    public boolean isValid() {
      return commandTag >= 0;
    }
  }
}
