package com.bowman.cspng.codec.cluster;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;

/**
 * Codec ClusteredCache UDP — implémentation complète du contrat gelé du plan §2.
 *
 * <p>Encode/décode les 5 types de trames sur le fil (layouts 9/12/13/16/20/29/29+N octets,
 * big-endian). Aucune dépendance Vert.x : entrée/sortie = {@code byte[]} purs, conformément
 * à la contrainte du plan §3.1 (zéro dépendance Quarkus/Vert.x pour {@code csp-codec}).
 *
 * <p>Choix documentés :
 * <ul>
 *   <li><b>Hash ECM</b> : formule {@code 31*h + (byte & 0xFF)} avec overflow int32 naturel,
 *       équivalent à {@code new String(data, "ISO-8859-1").hashCode()}.</li>
 *   <li><b>{@code commandTag}</b> : transmis tel quel (y compris 0x7F/0x82…), conformément
 *       au comportement Java legacy — pas de forçage à 0x80 comme OSCam {@code FAKE_TAG}.</li>
 *   <li><b>{@code networkId == 0}</b> : transmis tel quel (pas de {@code FAKE_ONID 0xFFFF})
 *       conformément au comportement Java legacy.</li>
 *   <li><b>PINGRPL</b> : echo binaire brut des 8 octets reçus, jamais décodé/réencodé
 *       comme un {@code (timestamp, localPort)} — reproduit le comportement legacy/OSCam.</li>
 *   <li><b>{@code connectorName}</b> : encodeur/décodeur <em>Modified UTF-8</em> complet
 *       (NUL sur 2 octets, surrogates hors-BMP sur 6 octets). Cela garantit le round-trip
 *       byte-for-byte avec les pairs legacy/Java qui émettent des noms non-ASCII, contrairement
 *       à la recommandation ASCII-only du plan §2.3 qui reste une option plus restrictive
 *       applicable au niveau métier (lot L3) si nécessaire.</li>
 *   <li><b>Asymétrie {@code requireId}</b> : la logique de filtrage {@code caid==0 && onid==0}
 *       en émission vs {@code caid==0 || onid==0} en réception n'est PAS implémentée ici :
 *       c'est une décision métier du verticle réseau (lot L3), pas du codec.</li>
 * </ul>
 */
public final class ClusterUdpCodec {

  public static final int TYPE_REQUEST = 1;
  public static final int TYPE_REPLY = 2;
  public static final int TYPE_PINGREQ = 3;
  public static final int TYPE_PINGRPL = 4;
  public static final int TYPE_RESENDREQ = 5;

  /** Taille maximale d'une trame émise (legacy utilisait un buffer de 512 octets). */
  public static final int MAX_TX_FRAME_SIZE = 512;
  /** Taille maximale acceptée en réception (on ignore l'excédent au-delà, plan §2.3). */
  public static final int MAX_RX_FRAME_SIZE = 1500;

  private ClusterUdpCodec() {}

  /**
   * Calcule le hash ECM legacy : équivalent à
   * {@code new String(body, offset, length, StandardCharsets.ISO_8859_1).hashCode()}.
   *
   * <p>Le hash doit être appliqué à l'ECM <strong>amputé de ses 3 premiers octets</strong>
   * (table_id + longueur 2 octets) pour être compatible avec OSCam {@code cacheex_ecm_hash_calc()}.
   */
  public static int ecmHash(byte[] body, int offset, int length) {
    if (body == null) {
      throw new IllegalArgumentException("body must not be null");
    }
    if (offset < 0 || length < 0 || offset + length > body.length) {
      throw new IllegalArgumentException("invalid offset/length");
    }
    int h = 0;
    for (int i = offset; i < offset + length; i++) {
      h = 31 * h + (body[i] & 0xFF);
    }
    return h;
  }

  /** Calcule le hash ECM sur tout le tableau. */
  public static int ecmHash(byte[] body) {
    return body == null ? ecmHash(new byte[0], 0, 0) : ecmHash(body, 0, body.length);
  }

  // ---------------------------------------------------------------------------------------------
  // REQUEST (12 octets sans arbitre, 20 octets avec)
  // ----------------------------------------------------------------------------------------------

  /** Encode une requête. Si {@code request.arbiterNumber()} est non-null, la taille est 20 octets. */
  public static byte[] encodeRequest(EcmRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    boolean hasArbiter = request.arbiterNumber() != null;
    byte[] buf = new byte[hasArbiter ? 20 : 12];
    buf[0] = (byte) TYPE_REQUEST;
    writeCacheReq(buf, 1, request.key());
    if (hasArbiter) {
      writeDouble(buf, 12, request.arbiterNumber());
    }
    return buf;
  }

  /** Décode une trame de type {@code REQUEST}. Le champ {@code body} de l'ECM n'est pas transporté
   * sur le réseau : il est initialisé à un tableau vide. */
  public static EcmRequest decodeRequest(byte[] frame) {
    requireFrame(frame, TYPE_REQUEST, 12, 20);
    EcmKey key = readCacheReq(frame, 1);
    Double arbiter = frame.length == 20 ? readDouble(frame, 12) : null;
    return new EcmRequest(key, new byte[0], null, null, null, 0L, arbiter, 0L);
  }

  // ---------------------------------------------------------------------------------------------
  // REPLY (13 octets vide, 29+N octets avec CW)
  // ----------------------------------------------------------------------------------------------

  /** Encode une réponse CW. Réponse vide si {@code reply.isEmpty()}. */
  public static byte[] encodeReply(CwReply reply) {
    if (reply == null) {
      throw new IllegalArgumentException("reply must not be null");
    }
    EcmKey key = reply.key();
    boolean empty = reply.isEmpty();
    String name = reply.connectorName();
    byte[] nameBytes = null;
    int nameBytesLen = 0;
    boolean includeName = false;
    if (!empty && name != null && !name.isEmpty()) {
      nameBytes = writeModifiedUtf8(name);
      nameBytesLen = nameBytes.length;
      if (nameBytesLen > 0xFFFF) {
        throw new IllegalArgumentException("connectorName exceeds 65535 Modified-UTF-8 bytes");
      }
      includeName = true;
    }
    int size = empty ? 13 : 29 + (includeName ? 2 + nameBytesLen : 0);
    if (size > MAX_TX_FRAME_SIZE) {
      throw new IllegalArgumentException(
          "encoded REPLY exceeds MAX_TX_FRAME_SIZE (" + MAX_TX_FRAME_SIZE + "): " + size);
    }
    byte[] buf = new byte[size];
    buf[0] = (byte) TYPE_REPLY;
    writeCacheReq(buf, 1, key);
    buf[12] = (byte) reply.commandTag(); // rplTag
    if (!empty) {
      byte[] cw = reply.cw();
      if (cw.length != 16) {
        throw new IllegalArgumentException("CW must be exactly 16 bytes, got " + cw.length);
      }
      System.arraycopy(cw, 0, buf, 13, 16);
      if (includeName) {
        writeInt16(buf, 29, nameBytesLen);
        System.arraycopy(nameBytes, 0, buf, 31, nameBytesLen);
      }
    }
    return buf;
  }

  /** Décode une trame de type {@code REPLY}. */
  public static CwReply decodeReply(byte[] frame) {
    if (frame == null) {
      throw new IllegalArgumentException("frame must not be null");
    }
    if (frame.length == 0) {
      throw new IllegalArgumentException("empty frame");
    }
    if (frame.length > MAX_RX_FRAME_SIZE) {
      throw new IllegalArgumentException(
          "frame exceeds MAX_RX_FRAME_SIZE (" + MAX_RX_FRAME_SIZE + "): " + frame.length);
    }
    requireType(frame, TYPE_REPLY);
    if (frame.length != 13 && frame.length < 29) {
      throw new IllegalArgumentException(
          "REPLY frame size must be 13 or >=29, got " + frame.length);
    }
    EcmKey key = readCacheReq(frame, 1);
    int rplTag = frame[12] & 0xFF;
    if (frame.length == 13) {
      return new CwReply(key, rplTag, new byte[0], null, null, 0L);
    }
    byte[] cw = new byte[16];
    System.arraycopy(frame, 13, cw, 0, 16);
    String connectorName = "";
    int remaining = frame.length - 29;
    if (remaining > 0) {
      if (remaining < 2) {
        throw new IllegalArgumentException("REPLY frame truncated before connectorName length");
      }
      int nameLen = readInt16(frame, 29);
      if (remaining - 2 != nameLen) {
        throw new IllegalArgumentException(
            "connectorName length mismatch: declared " + nameLen + ", available " + (remaining - 2));
      }
      connectorName = nameLen > 0 ? readModifiedUtf8(frame, 31, nameLen) : "";
    }
    return new CwReply(key, rplTag, cw, connectorName, null, 0L);
  }

  // ---------------------------------------------------------------------------------------------
  // PINGREQ (13 octets) / PINGRPL (9 octets)
  // ----------------------------------------------------------------------------------------------

  public record PingFrame(long timestamp, int localPort) {}

  public record PingReplyFrame(byte[] echoBytes) {
    public PingReplyFrame {
      echoBytes = echoBytes != null ? echoBytes.clone() : new byte[0];
    }

    public byte[] echoBytes() {
      return echoBytes.clone();
    }
  }

  public static byte[] encodePingRequest(PingFrame ping) {
    if (ping == null) {
      throw new IllegalArgumentException("ping must not be null");
    }
    byte[] buf = new byte[13];
    buf[0] = (byte) TYPE_PINGREQ;
    writeInt64(buf, 1, ping.timestamp());
    writeInt32(buf, 9, ping.localPort());
    return buf;
  }

  public static PingFrame decodePingRequest(byte[] frame) {
    requireFrame(frame, TYPE_PINGREQ, 13);
    return new PingFrame(readInt64(frame, 1), readInt32(frame, 9));
  }

  public static byte[] encodePingReply(PingReplyFrame reply) {
    if (reply == null) {
      throw new IllegalArgumentException("reply must not be null");
    }
    byte[] echo = reply.echoBytes();
    if (echo.length != 8) {
      throw new IllegalArgumentException("PINGRPL echo must be exactly 8 bytes, got " + echo.length);
    }
    byte[] buf = new byte[9];
    buf[0] = (byte) TYPE_PINGRPL;
    System.arraycopy(echo, 0, buf, 1, 8);
    return buf;
  }

  public static PingReplyFrame decodePingReply(byte[] frame) {
    requireFrame(frame, TYPE_PINGRPL, 9);
    byte[] echo = new byte[8];
    System.arraycopy(frame, 1, echo, 0, 8);
    return new PingReplyFrame(echo);
  }

  // ---------------------------------------------------------------------------------------------
  // RESENDREQ (16 octets)
  // ----------------------------------------------------------------------------------------------

  public record ResendFrame(int localPort, EcmKey key) {}

  public static byte[] encodeResendRequest(ResendFrame frame) {
    if (frame == null) {
      throw new IllegalArgumentException("frame must not be null");
    }
    byte[] buf = new byte[16];
    buf[0] = (byte) TYPE_RESENDREQ;
    writeInt32(buf, 1, frame.localPort());
    writeCacheReq(buf, 5, frame.key());
    return buf;
  }

  public static ResendFrame decodeResendRequest(byte[] frame) {
    requireFrame(frame, TYPE_RESENDREQ, 16);
    return new ResendFrame(readInt32(frame, 1), readCacheReq(frame, 5));
  }

  // ---------------------------------------------------------------------------------------------
  // CacheReq commun (11 octets)
  // ----------------------------------------------------------------------------------------------

  private static void writeCacheReq(byte[] buf, int offset, EcmKey key) {
    buf[offset] = (byte) key.commandTag();
    writeInt16(buf, offset + 1, key.serviceId());
    writeInt16(buf, offset + 3, key.networkId());
    writeInt16(buf, offset + 5, key.caId());
    writeInt32(buf, offset + 7, key.dataHash());
  }

  private static EcmKey readCacheReq(byte[] buf, int offset) {
    return new EcmKey(
        buf[offset] & 0xFF,
        readInt16(buf, offset + 1),
        readInt16(buf, offset + 3),
        readInt16(buf, offset + 5),
        readInt32(buf, offset + 7));
  }

  // ---------------------------------------------------------------------------------------------
  // Primitives big-endian
  // ----------------------------------------------------------------------------------------------

  private static void writeInt16(byte[] buf, int offset, int value) {
    buf[offset] = (byte) (value >>> 8);
    buf[offset + 1] = (byte) value;
  }

  private static int readInt16(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
  }

  private static void writeInt32(byte[] buf, int offset, int value) {
    buf[offset] = (byte) (value >>> 24);
    buf[offset + 1] = (byte) (value >>> 16);
    buf[offset + 2] = (byte) (value >>> 8);
    buf[offset + 3] = (byte) value;
  }

  private static int readInt32(byte[] buf, int offset) {
    return (buf[offset] << 24)
        | ((buf[offset + 1] & 0xFF) << 16)
        | ((buf[offset + 2] & 0xFF) << 8)
        | (buf[offset + 3] & 0xFF);
  }

  private static void writeInt64(byte[] buf, int offset, long value) {
    buf[offset] = (byte) (value >>> 56);
    buf[offset + 1] = (byte) (value >>> 48);
    buf[offset + 2] = (byte) (value >>> 40);
    buf[offset + 3] = (byte) (value >>> 32);
    buf[offset + 4] = (byte) (value >>> 24);
    buf[offset + 5] = (byte) (value >>> 16);
    buf[offset + 6] = (byte) (value >>> 8);
    buf[offset + 7] = (byte) value;
  }

  private static long readInt64(byte[] buf, int offset) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (buf[offset + i] & 0xFFL);
    }
    return value;
  }

  private static void writeDouble(byte[] buf, int offset, double value) {
    writeInt64(buf, offset, Double.doubleToRawLongBits(value));
  }

  private static double readDouble(byte[] buf, int offset) {
    return Double.longBitsToDouble(readInt64(buf, offset));
  }

  // ---------------------------------------------------------------------------------------------
  // Validation helpers
  // ----------------------------------------------------------------------------------------------

  private static void requireType(byte[] frame, int expectedType) {
    int actual = frame[0] & 0xFF;
    if (actual != expectedType) {
      throw new IllegalArgumentException(
          "expected frame type " + expectedType + ", got " + actual);
    }
  }

  private static void requireFrame(byte[] frame, int type, int... allowedLengths) {
    if (frame == null) {
      throw new IllegalArgumentException("frame must not be null");
    }
    if (frame.length == 0) {
      throw new IllegalArgumentException("empty frame");
    }
    if (frame.length > MAX_RX_FRAME_SIZE) {
      throw new IllegalArgumentException(
          "frame exceeds MAX_RX_FRAME_SIZE (" + MAX_RX_FRAME_SIZE + "): " + frame.length);
    }
    requireType(frame, type);
    for (int len : allowedLengths) {
      if (frame.length == len) {
        return;
      }
    }
    StringBuilder sb = new StringBuilder("frame length must be one of ");
    for (int i = 0; i < allowedLengths.length; i++) {
      if (i > 0) sb.append(", ");
      sb.append(allowedLengths[i]);
    }
    sb.append(", got ").append(frame.length);
    throw new IllegalArgumentException(sb.toString());
  }

  // ---------------------------------------------------------------------------------------------
  // Modified UTF-8 (DataInput/DataOutput compatible)
  // ----------------------------------------------------------------------------------------------

  /**
   * Encode une chaîne en Modified UTF-8 : NUL sur 2 octets, caractères hors-BMP représentés
   * par leur paire de surrogates UTF-16, chacun encodé sur 3 octets.
   *
   * @return les octets Modified-UTF-8 (sans le préfixe de longueur u16)
   */
  static byte[] writeModifiedUtf8(String s) {
    if (s == null) {
      throw new IllegalArgumentException("string must not be null");
    }
    // Précalcul de la taille pour éviter les reallocations.
    int byteCount = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == 0) {
        byteCount += 2;
      } else if (c <= 0x7F) {
        byteCount += 1;
      } else if (c <= 0x7FF) {
        byteCount += 2;
      } else {
        byteCount += 3;
      }
    }
    byte[] out = new byte[byteCount];
    int off = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == 0) {
        out[off++] = (byte) 0xC0;
        out[off++] = (byte) 0x80;
      } else if (c <= 0x7F) {
        out[off++] = (byte) c;
      } else if (c <= 0x7FF) {
        out[off++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
        out[off++] = (byte) (0x80 | (c & 0x3F));
      } else {
        out[off++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
        out[off++] = (byte) (0x80 | ((c >> 6) & 0x3F));
        out[off++] = (byte) (0x80 | (c & 0x3F));
      }
    }
    return out;
  }

  /**
   * Décode {@code byteLen} octets Modified UTF-8 à partir de {@code buf[offset]}.
   */
  static String readModifiedUtf8(byte[] buf, int offset, int byteLen) {
    if (buf == null) {
      throw new IllegalArgumentException("buf must not be null");
    }
    if (offset < 0 || byteLen < 0 || offset + byteLen > buf.length) {
      throw new IllegalArgumentException("invalid offset/byteLen");
    }
    StringBuilder sb = new StringBuilder(byteLen);
    int end = offset + byteLen;
    int i = offset;
    while (i < end) {
      int b1 = buf[i] & 0xFF;
      if ((b1 & 0x80) == 0) {
        sb.append((char) b1);
        i++;
      } else if ((b1 & 0xE0) == 0xC0) {
        if (i + 1 >= end) {
          throw new IllegalArgumentException("truncated 2-byte Modified UTF-8 at offset " + i);
        }
        int b2 = buf[i + 1] & 0xFF;
        if (b1 == 0xC0 && b2 == 0x80) {
          sb.append('\0');
        } else {
          sb.append((char) (((b1 & 0x1F) << 6) | (b2 & 0x3F)));
        }
        i += 2;
      } else if ((b1 & 0xF0) == 0xE0) {
        if (i + 2 >= end) {
          throw new IllegalArgumentException("truncated 3-byte Modified UTF-8 at offset " + i);
        }
        int b2 = buf[i + 1] & 0xFF;
        int b3 = buf[i + 2] & 0xFF;
        sb.append((char) (((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)));
        i += 3;
      } else {
        throw new IllegalArgumentException("invalid Modified UTF-8 lead byte 0x"
            + Integer.toHexString(b1) + " at offset " + i);
      }
    }
    return sb.toString();
  }
}
