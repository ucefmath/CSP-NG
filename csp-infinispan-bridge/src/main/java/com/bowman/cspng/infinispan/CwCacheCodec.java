package com.bowman.cspng.infinispan;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Codec binaire versionné pour les CW répliquées via Infinispan.
 * Format v1 : [version:1][keyCommandTag:4][serviceId:4][networkId:4][caId:4][dataHash:4]
 *             [replyCommandTag:4][cwLen:4 (=16)][cw:16][connectorLen:4][connectorBytes][originLen:4][originBytes][timestamp:8]
 * Borné : strings max 256 octets, cw exactement 16, taille totale max 2048.
 */
public final class CwCacheCodec {

  private static final byte VERSION = 1;
  private static final int CW_LEN = 16;
  private static final int MAX_STRING_BYTES = 256;
  private static final int MAX_TOTAL_SIZE = 2048;

  private CwCacheCodec() {}

  public static byte[] encode(CwReply reply) {
    if (reply == null) throw new IllegalArgumentException("reply null");
    byte[] cw = reply.cw();
    if (cw == null || cw.length != CW_LEN) throw new IllegalArgumentException("CW must be exactly 16 bytes");
    EcmKey k = reply.key();
    byte[] connBytes = toBytes(reply.connectorName());
    byte[] origBytes = toBytes(reply.originAddress());
    if (connBytes != null && connBytes.length > MAX_STRING_BYTES)
      throw new IllegalArgumentException("connectorName too long");
    if (origBytes != null && origBytes.length > MAX_STRING_BYTES)
      throw new IllegalArgumentException("originAddress too long");

    int size = 1 + 4*6 + 4 + CW_LEN + 4 + (connBytes == null ? 0 : connBytes.length)
        + 4 + (origBytes == null ? 0 : origBytes.length) + 8;
    if (size > MAX_TOTAL_SIZE) throw new IllegalArgumentException("encoded size too large");
    ByteBuffer buf = ByteBuffer.allocate(size);
    buf.put(VERSION);
    buf.putInt(k.commandTag());
    buf.putInt(k.serviceId());
    buf.putInt(k.networkId());
    buf.putInt(k.caId());
    buf.putInt(k.dataHash());
    buf.putInt(reply.commandTag());
    buf.putInt(CW_LEN);
    buf.put(cw);
    putString(buf, connBytes);
    putString(buf, origBytes);
    buf.putLong(reply.timestamp());
    return buf.array();
  }

  public static CwReply decode(byte[] data, String overriddenOrigin) throws IllegalArgumentException {
    if (data == null) throw new IllegalArgumentException("data null");
    if (data.length < 1 + 4*6 + 4 + CW_LEN + 4 + 4 + 8) throw new IllegalArgumentException("data too short");
    if (data.length > MAX_TOTAL_SIZE) throw new IllegalArgumentException("data too large");
    ByteBuffer buf = ByteBuffer.wrap(data);
    byte v = buf.get();
    if (v != VERSION) throw new IllegalArgumentException("unsupported version " + v);
    int keyTag = buf.getInt();
    int serviceId = buf.getInt();
    int networkId = buf.getInt();
    int caId = buf.getInt();
    int dataHash = buf.getInt();
    int replyTag = buf.getInt();
    int cwLen = buf.getInt();
    if (cwLen != CW_LEN) throw new IllegalArgumentException("invalid cw length");
    if (buf.remaining() < cwLen) throw new IllegalArgumentException("truncated cw");
    byte[] cw = new byte[CW_LEN];
    buf.get(cw);
    String connector = getString(buf);
    String origin = getString(buf);
    if (buf.remaining() < 8) throw new IllegalArgumentException("truncated timestamp");
    long ts = buf.getLong();
    if (buf.hasRemaining()) throw new IllegalArgumentException("trailing bytes");
    EcmKey key = new EcmKey(keyTag, serviceId, networkId, caId, dataHash);
    String finalOrigin = overriddenOrigin != null ? overriddenOrigin : origin;
    return new CwReply(key, replyTag, cw, connector, finalOrigin, ts);
  }

  public static CwReply decode(byte[] data) {
    return decode(data, null);
  }

  private static byte[] toBytes(String s) {
    if (s == null) return null;
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static void putString(ByteBuffer buf, byte[] bytes) {
    if (bytes == null) {
      buf.putInt(-1);
    } else {
      buf.putInt(bytes.length);
      buf.put(bytes);
    }
  }

  private static String getString(ByteBuffer buf) {
    if (buf.remaining() < 4) throw new IllegalArgumentException("truncated string len");
    int len = buf.getInt();
    if (len == -1) return null;
    if (len < 0 || len > MAX_STRING_BYTES) throw new IllegalArgumentException("invalid string length");
    if (buf.remaining() < len) throw new IllegalArgumentException("truncated string bytes");
    byte[] b = new byte[len];
    buf.get(b);
    return new String(b, StandardCharsets.UTF_8);
  }
}
