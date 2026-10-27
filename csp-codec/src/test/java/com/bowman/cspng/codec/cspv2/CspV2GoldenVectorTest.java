package com.bowman.cspng.codec.cspv2;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CspV2GoldenVectorTest {

  private static final Path VECTORS_PATH =
      Path.of("..", "csp-testkit", "src", "test", "resources", "vectors", "cspv2.json");

  private static final Pattern VECTOR_PATTERN = Pattern.compile(
      "\\{\\s*\"id\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"length\"\\s*:\\s*(\\d+)\\s*,\\s*\"hex\"\\s*:\\s*\"([^\"]*)\"");

  private record VectorEntry(String id, String type, int length, String hex) {
    byte[] bytes() { return hexToBytes(hex); }
  }

  private static List<VectorEntry> loadVectors() throws IOException {
    assertTrue(Files.exists(VECTORS_PATH), "Vector file not found " + VECTORS_PATH.toAbsolutePath());
    String json = Files.readString(VECTORS_PATH, StandardCharsets.UTF_8);
    List<VectorEntry> entries = new ArrayList<>();
    Matcher m = VECTOR_PATTERN.matcher(json);
    while (m.find()) {
      entries.add(new VectorEntry(m.group(1), m.group(2), Integer.parseInt(m.group(3)), m.group(4)));
    }
    assertFalse(entries.isEmpty(), "No vectors loaded");
    return entries;
  }

  private static byte[] hexToBytes(String hex) {
    if (hex == null || hex.isEmpty()) return new byte[0];
    assertEquals(0, hex.length() % 2, "hex length even");
    byte[] out = new byte[hex.length()/2];
    for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
    return out;
  }

  private static String bytesToHex(byte[] b){
    StringBuilder sb=new StringBuilder();
    for(byte v:b) sb.append(String.format("%02X", v&0xFF));
    return sb.toString();
  }

  @Test
  void allPositiveGoldenVectorsRoundTripByteForByte() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    int tested = 0;
    for (VectorEntry v : vectors) {
      if (v.type().equals("HEADER_ONLY") || v.type().equals("RESERVED")) continue;
      byte[] original = v.bytes();
      assertEquals(v.length(), original.length, "length mismatch for " + v.id());
      CspV2Frame decoded = CspV2Codec.decodeFrame(original, CspV2Constants.MAX_MAX_PAYLOAD);
      byte[] rebuilt = CspV2Codec.encodeFrame(decoded.header(), decoded.payload());
      assertArrayEquals(original, rebuilt,
          "Round-trip mismatch for " + v.id() + " hex=" + v.hex() + " rebuilt=" + bytesToHex(rebuilt));
      tested++;
    }
    assertEquals(6, tested, "Expected 6 positive vectors HELLO, HELLO_ACK, ECM, DCW, ERROR, HEARTBEAT");
  }

  @Test
  void helloVectorFields() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e-> e.id().equals("hello-01")).findFirst().orElseThrow();
    CspV2Frame f = CspV2Codec.decodeFrame(v.bytes(), CspV2Constants.MAX_MAX_PAYLOAD);
    assertEquals(CspV2MessageType.HELLO, f.header().type());
    assertEquals(0L, f.header().streamId());
    var hello = (CspV2Payload.Hello) f.payload();
    assertEquals("node-alpha", hello.nodeId());
    assertEquals(0x1122334455667788L, hello.connectionId());
  }

  @Test
  void ecmVectorHashVerified() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e-> e.id().equals("ecm-request-01")).findFirst().orElseThrow();
    CspV2Frame f = CspV2Codec.decodeFrame(v.bytes(), CspV2Constants.MAX_MAX_PAYLOAD);
    var ecm = (CspV2Payload.EcmRequest) f.payload();
    int computed = CspV2Codec.ecmHash(ecm.ecm(),3, ecm.ecm().length-3);
    assertEquals(ecm.dataHash(), computed);
  }

  @Test
  void dcwVectorChecksumVerified() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e-> e.id().equals("dcw-reply-ok-01")).findFirst().orElseThrow();
    CspV2Frame f = CspV2Codec.decodeFrame(v.bytes(), CspV2Constants.MAX_MAX_PAYLOAD);
    var dcw = (CspV2Payload.DcwReply) f.payload();
    assertTrue(CspV2Codec.checksumDcw(dcw.cw()));
  }

  @Test
  void headerOnlyOversizeVectorIsFrameTooLarge() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e-> e.id().equals("header-oversize-u32")).findFirst().orElseThrow();
    assertEquals(20, v.bytes().length);
    CspV2ProtocolException ex = assertThrows(CspV2ProtocolException.class,
        () -> CspV2Codec.decodeHeader(v.bytes(), CspV2Constants.MAX_MAX_PAYLOAD));
    assertEquals(CspV2ProtocolException.Category.FRAME_TOO_LARGE, ex.category());
    assertEquals(CspV2ErrorCode.FRAME_TOO_LARGE, ex.errorCode());
  }

  @Test
  void reservedTypeVectorIsUnsupportedType() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e-> e.id().equals("reserved-state-snapshot")).findFirst().orElseThrow();
    CspV2ProtocolException ex = assertThrows(CspV2ProtocolException.class,
        () -> CspV2Codec.decodeHeader(v.bytes(), CspV2Constants.MAX_MAX_PAYLOAD));
    assertEquals(CspV2ProtocolException.Category.UNSUPPORTED_TYPE, ex.category());
    assertEquals(CspV2ErrorCode.UNSUPPORTED_TYPE, ex.errorCode());
  }

  @Test
  void mandatoryFrameSizesCovered() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    assertTrue(vectors.stream().anyMatch(e-> e.id().equals("hello-01")));
    assertTrue(vectors.stream().anyMatch(e-> e.id().equals("ecm-request-01")));
    assertTrue(vectors.stream().anyMatch(e-> e.id().equals("heartbeat-01")));
    assertTrue(vectors.stream().anyMatch(e-> e.id().equals("header-oversize-u32")));
    assertTrue(vectors.stream().anyMatch(e-> e.id().equals("reserved-state-snapshot")));
  }

  // -------------------------------------------------------------------------
  // Independent manual wire parsing (no codec decode/encode) – catches offset errors
  // -------------------------------------------------------------------------

  @Test
  void helloVectorManualWireOffsets() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("hello-01")).findFirst().orElseThrow();
    byte[] f = v.bytes();
    // header manual
    assertEquals(0x43535032, readInt32BE(f,0));
    assertEquals(0x01, f[4] & 0xFF);
    assertEquals(0x01, f[5] & 0xFF);
    assertEquals(0, readU16BE(f,6));
    assertEquals(32, readU32BE(f,8)); // 22+10
    assertEquals(0L, readInt64BE(f,12));
    // payload manual
    byte[] p = java.util.Arrays.copyOfRange(f,20,f.length);
    int nodeIdLen = readU16BE(p,0);
    assertEquals(10, nodeIdLen);
    String nodeId = new String(p,2,nodeIdLen, StandardCharsets.UTF_8);
    assertEquals("node-alpha", nodeId);
    int off = 2+nodeIdLen;
    long conn = readInt64BE(p,off); off+=8;
    assertEquals(0x1122334455667788L, conn);
    long caps = readU32BE(p,off); off+=4;
    assertEquals(1L, caps);
    long maxPayload = readU32BE(p,off); off+=4;
    assertEquals(1048576L, maxPayload);
    long maxInflight = readU32BE(p,off); off+=4;
    assertEquals(1000L, maxInflight);
    assertEquals(p.length, off);
    assertEquals(22+nodeIdLen, p.length);
  }

  @Test
  void ecmVectorManualHashAndOffsets() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("ecm-request-01")).findFirst().orElseThrow();
    byte[] f = v.bytes();
    assertEquals(0x10, f[5] & 0xFF);
    byte[] p = java.util.Arrays.copyOfRange(f,20,f.length);
    int off=0;
    int tag = p[off++] & 0xFF; assertEquals(0x80, tag);
    int sid = readU16BE(p,off); off+=2; assertEquals(0x1234, sid);
    int nid = readU16BE(p,off); off+=2; assertEquals(0x0001, nid);
    int caid = readU16BE(p,off); off+=2; assertEquals(0x0B00, caid);
    int dataHash = readInt32BE(p,off); off+=4;
    int profLen = readU16BE(p,off); off+=2; assertEquals(12, profLen);
    String prof = new String(p,off,profLen, StandardCharsets.UTF_8); off+=profLen;
    assertEquals("test-profile", prof);
    int ecmLen = readU16BE(p,off); off+=2; assertEquals(13, ecmLen);
    byte[] ecm = java.util.Arrays.copyOfRange(p,off,off+ecmLen); off+=ecmLen;
    assertEquals((byte)0x80, ecm[0]); assertEquals((byte)0x00, ecm[1]); assertEquals((byte)0x0A, ecm[2]);
    // manual hash offset 3
    int h=0; for(int i=3;i<ecm.length;i++) h=31*h + (ecm[i] & 0xFF);
    assertEquals(h, dataHash);
    long maxWait = readU32BE(p,off); off+=4; assertEquals(5000L, maxWait);
    assertEquals(p.length, off);
    assertEquals(19+profLen+ecmLen, p.length);
  }

  @Test
  void dcwVectorManualChecksumAndOffsets() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("dcw-reply-ok-01")).findFirst().orElseThrow();
    byte[] f = v.bytes();
    assertEquals(0x11, f[5] & 0xFF);
    byte[] p = java.util.Arrays.copyOfRange(f,20,f.length);
    int off=0;
    int tag = p[off++] & 0xFF; assertEquals(0x80, tag);
    int sid = readU16BE(p,off); off+=2; assertEquals(0x1234, sid);
    int nid = readU16BE(p,off); off+=2; assertEquals(0x0001, nid);
    int caid = readU16BE(p,off); off+=2; assertEquals(0x0B00, caid);
    int dh = readInt32BE(p,off); off+=4;
    int rtag = p[off++] & 0xFF; assertEquals(0x80, rtag);
    int status = p[off++] & 0xFF; assertEquals(0, status);
    int cwLen = p[off++] & 0xFF; assertEquals(16, cwLen);
    byte[] cw = java.util.Arrays.copyOfRange(p,off,off+cwLen); off+=cwLen;
    // manual checksum
    for(int i=0;i<16;i+=4){
      int exp = ((cw[i] &0xFF)+(cw[i+1]&0xFF)+(cw[i+2]&0xFF)) &0xFF;
      assertEquals(exp, cw[i+3] &0xFF, "checksum group "+i);
    }
    int cnLen = readU16BE(p,off); off+=2; assertEquals(12, cnLen);
    String cn = new String(p,off,cnLen, StandardCharsets.UTF_8); off+=cnLen;
    assertEquals("connector-42", cn);
    assertEquals(p.length, off);
    assertEquals(16+cwLen+cnLen, p.length);
    // also verify dh matches ecm hash (same ecm as ecm vector)
    // ecm from ecm vector is same
    byte[] ecm = new byte[]{(byte)0x80,0x00,0x0A,0x11,0x22,0x33,0x44,0x55,0x66,0x77,(byte)0x88,(byte)0x99,(byte)0xAA};
    int h=0; for(int i=3;i<ecm.length;i++) h=31*h + (ecm[i]&0xFF);
    assertEquals(h, dh);
  }

  @Test
  void errorVectorManualCodeReason() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("error-rate-limited-01")).findFirst().orElseThrow();
    byte[] f = v.bytes();
    assertEquals(0x31, f[5] & 0xFF);
    byte[] p = java.util.Arrays.copyOfRange(f,20,f.length);
    int code = readU16BE(p,0); assertEquals(CspV2ErrorCode.RATE_LIMITED.code(), code);
    assertEquals("rate_limited", CspV2ErrorCode.RATE_LIMITED.canonicalReason());
    int rlen = readU16BE(p,2); assertEquals(12, rlen);
    String reason = new String(p,4,rlen, StandardCharsets.UTF_8);
    assertEquals("rate_limited", reason);
    assertEquals(reason, CspV2ErrorCode.fromCode(code).canonicalReason());
    assertEquals(4+rlen, p.length);
  }

  // manual helpers independent of codec (duplicate logic)
  private static int readInt32BE(byte[] b,int o){ return (b[o]<<24)|((b[o+1]&0xFF)<<16)|((b[o+2]&0xFF)<<8)|(b[o+3]&0xFF); }
  private static int readU16BE(byte[] b,int o){ return ((b[o]&0xFF)<<8)|(b[o+1]&0xFF); }
  private static long readU32BE(byte[] b,int o){ return ((b[o]&0xFFL)<<24)|((b[o+1]&0xFFL)<<16)|((b[o+2]&0xFFL)<<8)|(b[o+3]&0xFFL); }
  private static long readInt64BE(byte[] b,int o){ long v=0; for(int i=0;i<8;i++) v=(v<<8)|(b[o+i]&0xFFL); return v; }
}
