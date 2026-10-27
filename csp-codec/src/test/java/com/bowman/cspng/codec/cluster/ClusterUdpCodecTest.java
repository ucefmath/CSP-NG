package com.bowman.cspng.codec.cluster;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.PingReplyFrame;
import com.bowman.cspng.codec.cluster.ClusterUdpCodec.ResendFrame;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ClusterUdpCodecTest {

  private static final Path VECTORS_PATH =
      Path.of("..", "csp-testkit", "src", "test", "resources", "vectors", "cluster-udp.json");

  private static final Pattern VECTOR_PATTERN = Pattern.compile(
      "\\{\\s*\"id\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]*)\"\\s*,"
          + "\\s*\"length\"\\s*:\\s*(\\d+)\\s*,\\s*\"hex\"\\s*:\\s*\"([^\"]*)\"\\s*,"
          + "\\s*\"description\"\\s*:\\s*\"([^\"]*)\"");

  private record VectorEntry(String id, String type, int length, String hex, String description) {
    byte[] bytes() {
      return hexToBytes(hex);
    }
  }

  private static List<VectorEntry> loadVectors() throws IOException {
    assertTrue(Files.exists(VECTORS_PATH),
        "Vector resource not found at " + VECTORS_PATH.toAbsolutePath());
    String json = Files.readString(VECTORS_PATH, StandardCharsets.UTF_8);
    List<VectorEntry> entries = new ArrayList<>();
    Matcher m = VECTOR_PATTERN.matcher(json);
    while (m.find()) {
      entries.add(new VectorEntry(
          m.group(1), m.group(2), Integer.parseInt(m.group(3)), m.group(4), m.group(5)));
    }
    assertFalse(entries.isEmpty(), "No vectors loaded from " + VECTORS_PATH);
    return entries;
  }

  private static byte[] hexToBytes(String hex) {
    if (hex == null || hex.isEmpty()) {
      return new byte[0];
    }
    assertEquals(0, hex.length() % 2, "Hex string length must be even");
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02X", b & 0xFF));
    }
    return sb.toString();
  }

  // -----------------------------------------------------------------------------------------------
  // Hash
  // -----------------------------------------------------------------------------------------------

  @Test
  void ecmHashMatchesStringHashCode() {
    byte[] body = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05};
    int expected = new String(body, 0, body.length, StandardCharsets.ISO_8859_1).hashCode();
    assertEquals(expected, ClusterUdpCodec.ecmHash(body, 0, body.length));
  }

  @Test
  void ecmHashOnEmptyArrayIsZero() {
    assertEquals(0, ClusterUdpCodec.ecmHash(new byte[0], 0, 0));
  }

  @Test
  void ecmHashSkipsFirstThreeBytesForCacheIdentity() {
    // ECM fictif : 3 premiers octets (table_id + longueur) + 5 octets de payload.
    byte[] ecm = new byte[] {(byte) 0x80, 0x00, 0x1A, 0x01, 0x02, 0x03, 0x04, 0x05};
    int hashOnFullBody = ClusterUdpCodec.ecmHash(ecm, 0, ecm.length);
    int hashOnPayload = ClusterUdpCodec.ecmHash(ecm, 3, ecm.length - 3);
    int hashOnExpectedPayload =
        ClusterUdpCodec.ecmHash(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05}, 0, 5);

    assertNotEquals(hashOnFullBody, hashOnPayload,
        "Le hash legacy ne doit PAS inclure les 3 premiers octets");
    assertEquals(hashOnPayload, hashOnExpectedPayload,
        "Le hash legacy doit être calculé sur l'ECM moins ses 3 premiers octets");
  }

  @Test
  void ecmHashFuzzMatchesStringHashCode() {
    Random rnd = new Random(0xC5L); // seed fixe pour reproductibilité
    int iterations = 10_000;
    for (int i = 0; i < iterations; i++) {
      int len = 1 + rnd.nextInt(512);
      byte[] b = new byte[len];
      rnd.nextBytes(b);

      // motifs pathologiques toutes les 250 itérations
      if (i % 250 == 0) {
        switch ((i / 250) % 3) {
          case 0 -> java.util.Arrays.fill(b, (byte) 0x00);
          case 1 -> java.util.Arrays.fill(b, (byte) (byte) 0xFF);
          default -> {
            for (int j = 0; j < b.length; j++) {
              b[j] = (j % 2 == 0) ? (byte) 0x00 : (byte) 0xFF;
            }
          }
        }
      }

      int expected = new String(b, 0, b.length, StandardCharsets.ISO_8859_1).hashCode();
      assertEquals(expected, ClusterUdpCodec.ecmHash(b, 0, b.length),
          "Fuzz mismatch at iteration " + i + " for length " + len);
    }
  }

  // -----------------------------------------------------------------------------------------------
  // Golden vectors round-trip
  // -----------------------------------------------------------------------------------------------

  @Test
  void allGoldenVectorsRoundTripByteForByte() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    int tested = 0;
    for (VectorEntry v : vectors) {
      byte[] original = v.bytes();
      byte[] rebuilt = switch (v.type()) {
        case "REQUEST" -> ClusterUdpCodec.encodeRequest(ClusterUdpCodec.decodeRequest(original));
        case "REPLY" -> ClusterUdpCodec.encodeReply(ClusterUdpCodec.decodeReply(original));
        case "PINGREQ" -> ClusterUdpCodec.encodePingRequest(ClusterUdpCodec.decodePingRequest(original));
        case "PINGRPL" -> ClusterUdpCodec.encodePingReply(ClusterUdpCodec.decodePingReply(original));
        case "RESENDREQ" -> ClusterUdpCodec.encodeResendRequest(ClusterUdpCodec.decodeResendRequest(original));
        default -> {
          // RAW vectors are negative cases tested separately
          yield null;
        }
      };
      if (rebuilt != null) {
        assertArrayEquals(original, rebuilt,
            "Round-trip mismatch for vector " + v.id() + " (" + v.description() + ")\n"
                + "original: " + v.hex() + "\nrebuilt:  " + bytesToHex(rebuilt));
        tested++;
      }
    }
    assertEquals(29, tested, "Expected 29 positive round-trip vectors");
  }

  @Test
  void goldenReplyWithNonAsciiConnectorNameRoundTrips() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    VectorEntry v = vectors.stream().filter(e -> e.id().equals("reply-cw-name-utf8")).findFirst()
        .orElseThrow(() -> new AssertionError("Vector reply-cw-name-utf8 not found"));
    CwReply decoded = ClusterUdpCodec.decodeReply(v.bytes());
    assertEquals("héllo_€_测试", decoded.connectorName());
    byte[] rebuilt = ClusterUdpCodec.encodeReply(decoded);
    assertArrayEquals(v.bytes(), rebuilt);
  }

  // -----------------------------------------------------------------------------------------------
  // Edge cases (plan §5.1)
  // -----------------------------------------------------------------------------------------------

  @Test
  void truncatedRequestThrows() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("truncated-req")).findFirst()
        .orElseThrow();
    assertThrows(IllegalArgumentException.class,
        () -> ClusterUdpCodec.decodeRequest(v.bytes()));
  }

  @Test
  void oversizedRequestThrows() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("oversized-req")).findFirst()
        .orElseThrow();
    assertThrows(IllegalArgumentException.class,
        () -> ClusterUdpCodec.decodeRequest(v.bytes()));
  }

  @Test
  void unknownTypeThrows() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("unknown-type")).findFirst()
        .orElseThrow();
    assertThrows(IllegalArgumentException.class,
        () -> ClusterUdpCodec.decodeRequest(v.bytes()));
  }

  @Test
  void emptyFrameThrows() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("empty")).findFirst()
        .orElseThrow();
    assertThrows(IllegalArgumentException.class,
        () -> ClusterUdpCodec.decodeRequest(v.bytes()));
  }

  @Test
  void singleByteFrameThrows() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("single-byte")).findFirst()
        .orElseThrow();
    assertThrows(IllegalArgumentException.class,
        () -> ClusterUdpCodec.decodeResendRequest(v.bytes()));
  }

  @Test
  void mandatoryFrameSizesAreCoveredByGoldenVectors() throws Exception {
    List<VectorEntry> vectors = loadVectors();
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 9));   // PINGRPL
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 12));  // REQUEST
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 13));  // REPLY vide / PINGREQ
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 16));  // RESENDREQ
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 20));  // REQUEST arbitre
    assertTrue(vectors.stream().anyMatch(v -> v.length() == 29));  // REPLY CW sans nom
    assertTrue(vectors.stream().anyMatch(v -> v.length() >= 31)); // REPLY CW avec nom
  }

  @Test
  void requestWithZeroNetworkIdRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("req-onid0")).findFirst()
        .orElseThrow();
    EcmRequest decoded = ClusterUdpCodec.decodeRequest(v.bytes());
    assertEquals(0, decoded.key().networkId());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeRequest(decoded));
  }

  @Test
  void requestWithZeroCaIdRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("req-caid0")).findFirst()
        .orElseThrow();
    EcmRequest decoded = ClusterUdpCodec.decodeRequest(v.bytes());
    assertEquals(0, decoded.key().caId());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeRequest(decoded));
  }

  @Test
  void requestArbiterPresenceAndAbsenceRoundTrip() throws Exception {
    VectorEntry without = loadVectors().stream().filter(e -> e.id().equals("req-basic-80")).findFirst()
        .orElseThrow();
    VectorEntry with = loadVectors().stream().filter(e -> e.id().equals("req-arbiter")).findFirst()
        .orElseThrow();

    EcmRequest decodedWithout = ClusterUdpCodec.decodeRequest(without.bytes());
    assertNull(decodedWithout.arbiterNumber());
    assertArrayEquals(without.bytes(), ClusterUdpCodec.encodeRequest(decodedWithout));

    EcmRequest decodedWith = ClusterUdpCodec.decodeRequest(with.bytes());
    assertNotNull(decodedWith.arbiterNumber());
    assertEquals(3.141592653589793, decodedWith.arbiterNumber(), 1e-15);
    assertArrayEquals(with.bytes(), ClusterUdpCodec.encodeRequest(decodedWith));
  }

  @Test
  void requestWithNegativeHashRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("req-arbiter-neg")).findFirst()
        .orElseThrow();
    EcmRequest decoded = ClusterUdpCodec.decodeRequest(v.bytes());
    assertTrue(decoded.key().dataHash() < 0);
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeRequest(decoded));
  }

  @Test
  void replyEmptyRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("reply-empty-80")).findFirst()
        .orElseThrow();
    CwReply decoded = ClusterUdpCodec.decodeReply(v.bytes());
    assertTrue(decoded.isEmpty());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeReply(decoded));
  }

  @Test
  void replyWithThirtyTwoByteConnectorNameRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("reply-cw-name-32")).findFirst()
        .orElseThrow();
    CwReply decoded = ClusterUdpCodec.decodeReply(v.bytes());
    assertEquals(32, decoded.connectorName().length());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeReply(decoded));
  }

  @Test
  void resendRequestWithZeroFieldsRoundTrips() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("resendreq-caid0")).findFirst()
        .orElseThrow();
    ResendFrame decoded = ClusterUdpCodec.decodeResendRequest(v.bytes());
    assertEquals(0, decoded.key().caId());
    assertEquals(0, decoded.key().networkId());
    assertEquals(0, decoded.key().dataHash());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodeResendRequest(decoded));
  }

  @Test
  void pingReplyIsPureEightByteEcho() throws Exception {
    VectorEntry v = loadVectors().stream().filter(e -> e.id().equals("pingrpl-basic")).findFirst()
        .orElseThrow();
    PingReplyFrame decoded = ClusterUdpCodec.decodePingReply(v.bytes());
    assertArrayEquals(hexToBytes("0000000012345678"), decoded.echoBytes());
    assertArrayEquals(v.bytes(), ClusterUdpCodec.encodePingReply(decoded));
  }

  @Test
  void encodeReplyRejectsConnectorNameExceedingTxLimit() {
    EcmKey key = new EcmKey(0x80, 0x1234, 0x0001, 0x0B00, 0x12345678);
    byte[] cw = new byte[16];
    StringBuilder name = new StringBuilder();
    // 481 caractères ASCII => 481 octets, 29+2+481 = 512 octets OK.
    // 482 caractères => 513 octets, dépassement.
    for (int i = 0; i < 482; i++) {
      name.append('a');
    }
    CwReply reply = new CwReply(key, 0x80, cw, name.toString(), null, 0L);
    assertThrows(IllegalArgumentException.class, () -> ClusterUdpCodec.encodeReply(reply));
  }

  @Test
  void decodeReplyRejectsFrameExceedingRxLimit() {
    byte[] frame = new byte[ClusterUdpCodec.MAX_RX_FRAME_SIZE + 1];
    frame[0] = (byte) ClusterUdpCodec.TYPE_REPLY;
    assertThrows(IllegalArgumentException.class, () -> ClusterUdpCodec.decodeReply(frame));
  }

  @Test
  void modifiedUtf8HandlesNullAndSupplementaryCharacters() {
    // NUL encodé sur 2 octets, émoji hors-BMP encodé comme paire surrogate (6 octets).
    String s = "\0A\uD83D\uDE00"; // NUL + A + 😀
    byte[] encoded = ClusterUdpCodec.writeModifiedUtf8(s);
    assertEquals(9, encoded.length); // NUL=2, A=1, surrogate high=3, surrogate low=3 => 9
    // 0xC0 0x80 0x41 0xED 0xA0 0xBD 0xED 0xB8 0x80
    assertArrayEquals(
        new byte[] {(byte) 0xC0, (byte) 0x80, 0x41, (byte) 0xED, (byte) 0xA0, (byte) 0xBD,
            (byte) 0xED, (byte) 0xB8, (byte) 0x80},
        encoded);
    assertEquals(s, ClusterUdpCodec.readModifiedUtf8(encoded, 0, encoded.length));
  }
}
