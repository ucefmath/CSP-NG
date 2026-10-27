import com.bowman.cardserv.CamdNetMessage;
import com.bowman.cardserv.ClusteredCache;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Générateur de vecteurs golden ClusteredCache UDP.
 *
 * <p>Compilé et exécuté contre le code Java legacy (CardServProxy) sans le modifier.
 * Utilise les méthodes statiques :
 * <ul>
 *   <li>{@link ClusteredCache#writeCacheReq(java.io.DataOutputStream, CamdNetMessage, boolean)}</li>
 *   <li>{@link ClusteredCache#writeCacheRpl(java.io.DataOutputStream, CamdNetMessage, boolean)}</li>
 * </ul>
 * pour les types REQUEST (0x01) et REPLY (0x02). Les types PINGREQ (0x03),
 * PINGRPL (0x04) et RESENDREQ (0x05) sont retranscrits à partir du code
 * d'origine de {@code ClusteredCache} car les méthodes d'émission
 * correspondantes sont privées.
 *
 * <p>Référence : plan §2.3 et §5.1.
 */
public class LegacyVectorGenerator {

  private static final int TYPE_REQUEST = 1;
  private static final int TYPE_REPLY = 2;
  private static final int TYPE_PINGREQ = 3;
  private static final int TYPE_PINGRPL = 4;
  private static final int TYPE_RESENDREQ = 5;

  private final List<Vector> vectors = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    String target = args.length > 0
        ? args[0]
        : "../../../test/resources/vectors/cluster-udp.json";

    LegacyVectorGenerator gen = new LegacyVectorGenerator();
    gen.generate();

    File out = new File(target);
    out.getParentFile().mkdirs();
    try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
      w.write(gen.toJson());
    }
    System.out.println("Generated " + gen.vectors.size() + " vectors to " + out.getAbsolutePath());
  }

  private void generate() throws Exception {
    // --- REQUEST : taille 12, arbitre absent, onid=0, caid=0
    addRequest("req-onid0", 0x80, 0x1234, 0x0000, 0x0B00, sampleEcmForHash(1), false, null,
        "REQUEST 12o, onid=0 (pas de FAKE_ONID côté Java legacy)");

    addRequest("req-caid0", 0x80, 0x1234, 0x0001, 0x0000, sampleEcmForHash(2), false, null,
        "REQUEST 12o, caid=0");

    addRequest("req-basic-80", 0x80, 0x1234, 0x0001, 0x0B00, sampleEcmForHash(3), false, null,
        "REQUEST 12o, commandTag=0x80");

    addRequest("req-basic-81", 0x81, 0x5678, 0x0002, 0x0B01, sampleEcmForHash(4), false, null,
        "REQUEST 12o, commandTag=0x81");

    // REQUEST : taille 20, arbitre présent
    addRequest("req-arbiter", 0x80, 0x1234, 0x0001, 0x0B00, sampleEcmForHash(5), true, 3.141592653589793,
        "REQUEST 20o, arbitre présent (ignoré par OSCam)");

    addRequest("req-arbiter-neg", 0x81, 0x5678, 0x0002, 0x0B01, sampleEcmForHashNeg(), true, -1.0,
        "REQUEST 20o, hash négatif, arbitre présent");

    addRequest("req-arbiter-zero", 0x80, 0x0000, 0x0000, 0x0000, new byte[0], true, 0.0,
        "REQUEST 20o, sid/onid/caid tous à 0, ECM vide (hash 0), arbitre 0.0");

    // --- REPLY : taille 13, CW vide (échec décodage)
    // Conformément au code legacy (ClusteredCache.sendMessage), une trame REPLY
    // concatène : type(1) + CacheReq(11) depuis l'ECM + rplTag(1) [+ CW(16) + connectorName].
    addReply("reply-empty-80", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(6), true, null, null,
        "REPLY 13o (CW vide), ecmTag=0x80, rplTag=0x80");

    addReply("reply-empty-81", 0x81, 0x81, 0x1238, 0x0003, 0x0B02, sampleHash(7), true, null, null,
        "REPLY 13o (CW vide), ecmTag=0x81, rplTag=0x81");

    // REPLY avec rplTag différent de l'ecmTag (cas réel possible)
    addReply("reply-empty-tagdiff", 0x80, 0x81, 0x1234, 0x0001, 0x0B00, sampleHash(27), true, null, null,
        "REPLY 13o (CW vide), ecmTag=0x80, rplTag=0x81");

    // --- REPLY : taille 29, CW 16 octets, connectorName vide
    addReply("reply-cw-empty-name", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(8), false, sampleCw(1), "",
        "REPLY 29o, CW valide, connectorName vide");

    // REPLY : taille 29, CW 16 octets, sans connectorName (extra=false)
    addReply("reply-cw-no-name", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(9), false, sampleCw(2), null,
        "REPLY 29o, CW valide, pas de connectorName (extra=false)");

    // REPLY : taille 63 (29 + 2 octets writeUTF + 32 octets de nom)
    addReply("reply-cw-name-32", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(10), true, sampleCw(3), "a".repeat(32),
        "REPLY 63o (29+2+32), connectorName 32 octets ASCII");

    // REPLY : taille 48 (29 + 2 octets writeUTF + 17 octets Modified UTF-8)
    addReply("reply-cw-name-utf8", 0x81, 0x81, 0x5678, 0x0002, 0x0B01, sampleHash(11), true, sampleCw(4), "héllo_€_测试",
        "REPLY 48o (29+2+17), connectorName non-ASCII (Modified UTF-8 Java)");

    // REPLY : hash négatif
    addReply("reply-cw-neg-hash", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHashNeg(), true, sampleCw(5), "neg-hash",
        "REPLY 39o (29+2+8), dataHash négatif, CW valide");

    // REPLY : onid=0, caid=0
    addReply("reply-cw-zeros", 0x80, 0x80, 0x0000, 0x0000, 0x0000, 0, true, sampleCw(6), "zeros",
        "REPLY 36o (29+2+5), sid/onid/caid tous à 0");

    // REPLY : CW avec zéros (pour tests filtres futurs)
    addReply("reply-cw-halfzero", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(12), true, halfZeroCw(), "halfzero",
        "REPLY 39o (29+2+8), demi-CW tout zéros (legacy tolère 8 zéros exacts)");

    // Quelques variants supplémentaires pour atteindre ≥30 vecteurs
    addRequest("req-tag-7f", 0x7F, 0x1234, 0x0001, 0x0B00, sampleEcmForHash(20), false, null,
        "REQUEST 12o, commandTag=0x7F (hors plage 0x80/0x81, écrit tel quel par le legacy Java)");
    addRequest("req-extra-no-arbiter", 0x80, 0xBEEF, 0x00DE, 0x0C00, sampleEcmForHash(21), true, null,
        "REQUEST 12o, extra=true mais arbitre null (taille 12, pas 20)");

    addReply("reply-empty-extra", 0x81, 0x81, 0x1234, 0x0001, 0x0B00, sampleHash(22), true, null, null,
        "REPLY 13o (CW vide), extra=true");
    addReply("reply-cw-midname", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(23), true, sampleCw(7), "connector-17b",
        "REPLY 44o (29+2+13), connectorName 13 octets ASCII");
    addReply("reply-cw-checksum", 0x80, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(24), true, checksumCw(), "checksum",
        "REPLY 39o, CW dont les 4e et 8e octets sont la somme des 3 précédents (checksum legacy)");

    // --- PINGREQ / PINGRPL / RESENDREQ : retranscrits de ClusteredCache
    addPingReq("pingreq-basic", 0x12345678L, 12345, "PINGREQ 13o, timestamp + localPort");
    addPingReq("pingreq-port0", 0x0L, 0, "PINGREQ 13o, timestamp et port nuls");
    addPingRpl("pingrpl-basic", 0x12345678L, "PINGRPL 9o, echo des 8 octets bruts");
    addPingRpl("pingrpl-zeros", 0L, "PINGRPL 9o, timestamp et port nuls");
    addResendReq("resendreq-basic", 12345, 0x80, 0x1234, 0x0001, 0x0B00, sampleHash(25),
        "RESENDREQ 16o, localPort + CacheReq");
    addResendReq("resendreq-caid0", 65535, 0x81, 0x0000, 0x0000, 0x0000, 0,
        "RESENDREQ 16o, caid=0/onid=0/sid=0/hash=0");
    addResendReq("resendreq-maxport", 65535, 0x80, 0xBEEF, 0x00DE, 0x0C00, sampleHash(26),
        "RESENDREQ 16o, localPort=65535");

    // --- Cas de réception (tronqué / surdimensionné / type inconnu) générés analytiquement
    addRaw("truncated-req", "REQUEST tronqué : seulement 5 octets au lieu de 12", "0112340001");
    addRaw("oversized-req", "REQUEST surdimensionné : type 01 + CacheReq + 4 octets en trop",
        "01123400010B00" + hexInt(sampleHash(14)) + "DEADBEEF");
    addRaw("unknown-type", "Type inconnu 0xFF suivi de 8 octets", "FF0102030405060708");
    addRaw("empty", "Paquet vide (0 octet)", "");
    addRaw("single-byte", "Paquet d'un seul octet (type inconnu)", "05");
  }

  private void addRequest(String id, int commandTag, int serviceId, int networkId, int caId,
      byte[] ecm, boolean extra, Double arbiter, String desc) throws Exception {
    CamdNetMessage msg = new CamdNetMessage(commandTag, ecm);
    msg.setServiceId(serviceId);
    msg.setNetworkId(networkId);
    msg.setCaId(caId);
    if (extra && arbiter != null) msg.setArbiterNumber(arbiter);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeByte(TYPE_REQUEST);
    ClusteredCache.writeCacheReq(dos, msg, extra);
    dos.flush();

    vectors.add(new Vector(id, "REQUEST", bos.toByteArray(),
        new Fields().with("commandTag", commandTag)
            .with("serviceId", serviceId)
            .with("networkId", networkId)
            .with("caId", caId)
            .with("dataHash", msg.getDataHash())
            .with("arbiterNumber", extra ? arbiter : null)
            .with("extra", extra),
        desc));
  }

  private void addReply(String id, int ecmCommandTag, int rplTag, int serviceId, int networkId,
      int caId, int dataHash, boolean extra, byte[] cw, String connectorName, String desc) throws Exception {
    // Le legacy construit une trame REPLY en deux parties (ClusteredCache.sendMessage) :
    //   1) le CacheReq(11 octets) depuis l'objet REQUEST (l'ECM)
    //   2) le rplTag(1) + CW(16) + connectorName depuis l'objet REPLY (la réponse CW)
    CamdNetMessage request = new CamdNetMessage(ecmCommandTag);
    request.setServiceId(serviceId);
    request.setNetworkId(networkId);
    request.setCaId(caId);
    setDataHash(request, dataHash);

    CamdNetMessage reply = new CamdNetMessage(rplTag);
    if (cw != null && cw.length > 0) {
      reply.setCustomData(cw);
    }
    if (connectorName != null) reply.setConnectorName(connectorName);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeByte(TYPE_REPLY);
    ClusteredCache.writeCacheReq(dos, request, false); // sendMessage fait setArbiterNumber(null) puis extra=true
    ClusteredCache.writeCacheRpl(dos, reply, extra);
    dos.flush();

    vectors.add(new Vector(id, "REPLY", bos.toByteArray(),
        new Fields().with("ecmCommandTag", ecmCommandTag)
            .with("rplTag", rplTag)
            .with("serviceId", serviceId)
            .with("networkId", networkId)
            .with("caId", caId)
            .with("dataHash", dataHash)
            .with("cw", cw != null ? hex(cw) : null)
            .with("connectorName", connectorName)
            .with("extra", extra),
        desc));
  }

  private void addPingReq(String id, long timestamp, int localPort, String desc) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeByte(TYPE_PINGREQ);
    dos.writeLong(timestamp);
    dos.writeInt(localPort);
    dos.flush();
    vectors.add(new Vector(id, "PINGREQ", bos.toByteArray(),
        new Fields().with("timestamp", timestamp).with("localPort", localPort), desc));
  }

  private void addPingRpl(String id, long timestamp, String desc) throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeByte(TYPE_PINGRPL);
    dos.writeLong(timestamp);
    dos.flush();
    byte[] full = bos.toByteArray();
    vectors.add(new Vector(id, "PINGRPL", full,
        new Fields().with("echoBytes", hex(full, 1, full.length - 1)), desc));
  }

  private void addResendReq(String id, int localPort, int commandTag, int serviceId,
      int networkId, int caId, int dataHash, String desc) throws Exception {
    CamdNetMessage msg = new CamdNetMessage(commandTag);
    msg.setServiceId(serviceId);
    msg.setNetworkId(networkId);
    msg.setCaId(caId);
    setDataHash(msg, dataHash);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    DataOutputStream dos = new DataOutputStream(bos);
    dos.writeByte(TYPE_RESENDREQ);
    dos.writeInt(localPort);
    ClusteredCache.writeCacheReq(dos, msg, false);
    dos.flush();
    vectors.add(new Vector(id, "RESENDREQ", bos.toByteArray(),
        new Fields().with("localPort", localPort)
            .with("commandTag", commandTag)
            .with("serviceId", serviceId)
            .with("networkId", networkId)
            .with("caId", caId)
            .with("dataHash", dataHash), desc));
  }

  private void addRaw(String id, String desc, String hex) {
    byte[] bytes = hex.isEmpty() ? new byte[0] : hexToBytes(hex);
    vectors.add(new Vector(id, "RAW", bytes, new Fields().with("rawHex", hex), desc));
  }

  private void setDataHash(CamdNetMessage msg, int dataHash) throws Exception {
    java.lang.reflect.Field f = CamdNetMessage.class.getDeclaredField("dataHash");
    f.setAccessible(true);
    f.setInt(msg, dataHash);
  }

  private byte[] sampleEcmForHash(int seed) {
    byte[] b = new byte[16 + (seed % 32)];
    for (int i = 0; i < b.length; i++) b[i] = (byte) ((i * 7 + seed * 13) & 0xFF);
    return b;
  }

  private byte[] sampleEcmForHashNeg() {
    // Brute-force un ECM dont le hash Java est négatif
    for (int seed = 0; ; seed++) {
      byte[] b = sampleEcmForHash(seed);
      int h = new String(b, 0, b.length, StandardCharsets.ISO_8859_1).hashCode();
      if (h < 0) return b;
    }
  }

  private int sampleHash(int seed) {
    byte[] b = sampleEcmForHash(seed);
    return new String(b, 0, b.length, StandardCharsets.ISO_8859_1).hashCode();
  }

  private int sampleHashNeg() {
    return new String(sampleEcmForHashNeg(), 0, 16, StandardCharsets.ISO_8859_1).hashCode();
  }

  private byte[] sampleCw(int seed) {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) {
      cw[i] = (byte) ((i * 11 + seed * 17) & 0xFF);
    }
    return cw;
  }

  private byte[] halfZeroCw() {
    byte[] cw = new byte[16];
    // première demi-CW à zéro, deuxième non
    for (int i = 8; i < 16; i++) cw[i] = (byte) (0xA0 + i);
    return cw;
  }

  private byte[] checksumCw() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i += 4) {
      cw[i] = (byte) 0x11;
      cw[i + 1] = (byte) 0x22;
      cw[i + 2] = (byte) 0x33;
      cw[i + 3] = (byte) ((cw[i] + cw[i + 1] + cw[i + 2]) & 0xFF);
    }
    return cw;
  }

  private static String hex(byte[] data, int offset, int length) {
    StringBuilder sb = new StringBuilder(length * 2);
    for (int i = offset; i < offset + length; i++) sb.append(String.format("%02X", data[i] & 0xFF));
    return sb.toString();
  }

  private static String hex(byte[] data) {
    return hex(data, 0, data.length);
  }

  private static String hexInt(int value) {
    return String.format("%08X", value);
  }

  private static byte[] hexToBytes(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private String toJson() {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"generator\": \"LegacyVectorGenerator compiled against CardServProxy legacy (Java 17)\",\n");
    sb.append("  \"method\": \"ClusteredCache.writeCacheReq / writeCacheRpl for REQUEST/REPLY; analytical re-implementation of sendPing/sendPingReply/sendResendReq for PINGREQ/PINGRPL/RESENDREQ\",\n");
    sb.append("  \"count\": ").append(vectors.size()).append(",\n");
    sb.append("  \"vectors\": [\n");
    for (int i = 0; i < vectors.size(); i++) {
      sb.append(vectors.get(i).toJson());
      if (i < vectors.size() - 1) sb.append(",");
      sb.append("\n");
    }
    sb.append("  ]\n");
    sb.append("}\n");
    return sb.toString();
  }

  private static class Vector {
    final String id;
    final String type;
    final byte[] bytes;
    final Fields fields;
    final String description;

    Vector(String id, String type, byte[] bytes, Fields fields, String description) {
      this.id = id;
      this.type = type;
      this.bytes = bytes;
      this.fields = fields;
      this.description = description;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder();
      sb.append("    {\n");
      sb.append("      \"id\": \"").append(jsonEscape(id)).append("\",\n");
      sb.append("      \"type\": \"").append(jsonEscape(type)).append("\",\n");
      sb.append("      \"length\": ").append(bytes.length).append(",\n");
      sb.append("      \"hex\": \"").append(hex(bytes)).append("\",\n");
      sb.append("      \"description\": \"").append(jsonEscape(description)).append("\",\n");
      sb.append("      \"fields\": ").append(fields.toJson()).append("\n");
      sb.append("    }");
      return sb.toString();
    }
  }

  private static class Fields {
    private final List<Pair> pairs = new ArrayList<>();

    Fields with(String name, Object value) {
      pairs.add(new Pair(name, value));
      return this;
    }

    String toJson() {
      StringBuilder sb = new StringBuilder();
      sb.append("{");
      for (int i = 0; i < pairs.size(); i++) {
        Pair p = pairs.get(i);
        sb.append("\"").append(jsonEscape(p.name)).append("\": ");
        if (p.value == null) sb.append("null");
        else if (p.value instanceof Boolean) sb.append(p.value);
        else if (p.value instanceof Number) sb.append(p.value);
        else sb.append("\"").append(jsonEscape(String.valueOf(p.value))).append("\"");
        if (i < pairs.size() - 1) sb.append(", ");
      }
      sb.append("}");
      return sb.toString();
    }
  }

  private static class Pair {
    final String name;
    final Object value;

    Pair(String name, Object value) {
      this.name = name;
      this.value = value;
    }
  }

  private static String jsonEscape(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\b': sb.append("\\b"); break;
        case '\f': sb.append("\\f"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }
}
