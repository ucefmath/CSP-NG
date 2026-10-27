package com.bowman.cspng.codec.camd35;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CacheEx camd35/CS378x : PUSH (0x3F) et FILTER (0x3C).
 *
 * <p><b>Layout wire</b> (module-camd35-cacheex.c, caractérisé par mécanismes interopérables —
 * NOTICE : réimplémentation Java idiomatique, aucune copie de code) :
 * <pre>
 *   PUSH 0x3F, longueur 16 bits en [1..2] LE, rc en [3] :
 *     [8..9]=srvid BE  [10..11]=caid BE  [12..15]=prid BE  [16..17]=pid
 *     [18]=cycletime (&lt;128, bit 0x80 = flag next_cw_cycle)  [19]=odd/even (0x80/0x81 sinon 0)
 *     body [0..15]=ecmd5  [16..19]=csp hash LE  [20..35]=cw  [36]=node count
 *     puis count × 8 octets de node id (own node FIRST)
 *     (AIO ajoute un octet localgenerated après les nodes — toléré en réception)
 *   FILTER 0x3C, data 242 octets :
 *     [0..1]=cevnum BE  puis 15×caid(4) 15×cmask(4) 15×prid(4) 15×srvid(4) BE
 * </pre>
 */
public final class Camd35CacheExCodec {

  private Camd35CacheExCodec() {}

  /** Taille body push sans nodes : ecmd5(16) + hash(4) + cw(16) + count(1). */
  public static final int PUSH_BODY_FIXED = 37;
  public static final int PUSH_BODY_MIN = 16 + 4 + 16; // seuil "ancien format" OSCam
  public static final int FILTER_BODY_SIZE = 242;
  public static final int FILTER_MAX_ENTRIES = 15;

  /** Push cacheex camd35 décodé / à encoder. */
  public record Camd35Push(
      int caid, int prid, int srvid, int pid,
      int rc, int oddEven,
      int cycleTimeSec, boolean nextCwCycle,
      byte[] ecmd5, int dataHash, byte[] cw,
      List<byte[]> nodeIds) {
    public Camd35Push {
      if (cw == null || cw.length != Camd35Constants.CW_SIZE) {
        throw new IllegalArgumentException("cw must be exactly " + Camd35Constants.CW_SIZE + " bytes");
      }
      if (ecmd5 == null) ecmd5 = new byte[16];
      ecmd5 = ecmd5.clone();
      cw = cw.clone();
      nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
      for (byte[] n : nodeIds) {
        if (n == null || n.length != 8) throw new IllegalArgumentException("node id must be 8 bytes");
      }
      if (cycleTimeSec < 0 || cycleTimeSec > 127) {
        throw new IllegalArgumentException("cycleTimeSec must be 0..127 (bit 0x80 = flag)");
      }
    }
  }

  /** Entrée de filtre camd35 (caid exact + cmask + prid + srvid ; 0 = wildcard). */
  public record Camd35FilterEntry(int caid, int cmask, int prid, int srvid) {}

  /**
   * Spec lisible {@code "caid:provid,caid:provid"} (hex) → entrées filtre camd35
   * (cmask 0xFFFF = CAID exact, srvid 0 = tous les services).
   * Vide/null ⇒ liste vide (= accept-all, cevnum=0).
   *
   * @throws IllegalArgumentException sur entrée invalide ou > 15 entrées
   */
  public static List<Camd35FilterEntry> entriesFromSpec(String spec) {
    if (spec == null || spec.isBlank()) return List.of();
    List<Camd35FilterEntry> out = new ArrayList<>();
    for (String raw : spec.split(",")) {
      String part = raw.trim();
      if (part.isEmpty()) continue;
      int colon = part.indexOf(':');
      if (colon <= 0 || colon == part.length() - 1) {
        throw new IllegalArgumentException("invalid cacheex filter entry (expected caid:provid): " + part);
      }
      String caidStr = part.substring(0, colon).trim();
      String pridStr = part.substring(colon + 1).trim();
      if (!caidStr.matches("[0-9a-fA-F]{1,4}") || !pridStr.matches("[0-9a-fA-F]{1,6}")) {
        throw new IllegalArgumentException("invalid cacheex filter entry (hex expected): " + part);
      }
      int caid = Integer.parseInt(caidStr, 16);
      int provid = "0".equals(pridStr) ? 0 : (int) Long.parseLong(pridStr, 16);
      out.add(new Camd35FilterEntry(caid, 0xFFFF, provid, 0));
      if (out.size() > FILTER_MAX_ENTRIES) {
        throw new IllegalArgumentException("cacheex filter limit " + FILTER_MAX_ENTRIES);
      }
    }
    return out;
  }

  /**
   * Encode le body du PUSH 0x3F (caller construit la frame avec
   * {@code cmd=0x3F, idx=pid, pid=(cycletime&lt;&lt;8)|oddEven, rcByte=rc}).
   */
  public static byte[] encodePushBody(byte[] ecmd5, int dataHash, byte[] cw, List<byte[]> nodeIds) {
    if (cw == null || cw.length != Camd35Constants.CW_SIZE) {
      throw new IllegalArgumentException("cw must be exactly " + Camd35Constants.CW_SIZE + " bytes");
    }
    int count = nodeIds == null ? 0 : nodeIds.size();
    byte[] body = new byte[PUSH_BODY_FIXED + 8 * count];
    Arrays.fill(body, (byte) 0);
    int ofs = 0;
    if (ecmd5 != null) {
      System.arraycopy(ecmd5, 0, body, ofs, Math.min(ecmd5.length, 16));
    }
    ofs += 16;
    // csp hash : 4 octets LITTLE-endian (i2b_buf(4, CSP_HASH_SWAP(h)) ≡ LE du hash natif)
    body[ofs] = (byte) (dataHash & 0xFF);
    body[ofs + 1] = (byte) ((dataHash >>> 8) & 0xFF);
    body[ofs + 2] = (byte) ((dataHash >>> 16) & 0xFF);
    body[ofs + 3] = (byte) ((dataHash >>> 24) & 0xFF);
    ofs += 4;
    System.arraycopy(cw, 0, body, ofs, Camd35Constants.CW_SIZE);
    ofs += Camd35Constants.CW_SIZE;
    body[ofs] = (byte) count;
    ofs++;
    if (nodeIds != null) {
      for (byte[] n : nodeIds) {
        System.arraycopy(n, 0, body, ofs, 8);
        ofs += 8;
      }
    }
    return body;
  }

  /**
   * Parse le body d'un PUSH 0x3F depuis une frame décodée.
   *
   * @param frame frame décodée (cmd 0x3F) — srvid/caid/prid/pid/rc extraits de l'en-tête
   * @throws IllegalArgumentException si le body est invalide (ancien format, count incohérent)
   */
  public static Camd35Push parsePush(Camd35FrameCodec.Camd35Frame frame) {
    if (frame == null || frame.cmd() != Camd35Constants.CMD_CACHEEX_PUSH) {
      throw new IllegalArgumentException("not a cacheex push frame");
    }
    byte[] data = frame.data();
    if (data == null || data.length < PUSH_BODY_MIN) {
      throw new IllegalArgumentException("cacheex push body too short (old format?)");
    }
    if (data.length < PUSH_BODY_FIXED) {
      throw new IllegalArgumentException("cacheex push body missing node section");
    }
    int count = data[36] & 0xFF;
    int expected = PUSH_BODY_FIXED + 8 * count;
    if (data.length < expected) {
      throw new IllegalArgumentException("cacheex push body truncated (count=" + count + ")");
    }
    byte[] ecmd5 = Arrays.copyOfRange(data, 0, 16);
    int dataHash = (data[16] & 0xFF) | ((data[17] & 0xFF) << 8)
        | ((data[18] & 0xFF) << 16) | ((data[19] & 0xFF) << 24);
    byte[] cw = Arrays.copyOfRange(data, 20, 36);
    List<byte[]> nodes = new ArrayList<>(count);
    int ofs = 37;
    for (int i = 0; i < count; i++) {
      nodes.add(Arrays.copyOfRange(data, ofs, ofs + 8));
      ofs += 8;
    }
    // [18] = cycletime + flag 0x80 ; [19] = odd/even — portés par le champ pid de la frame
    int pidField = frame.pid();
    int cycleteByte = (pidField >>> 8) & 0xFF;
    int cycleTime = cycleteByte & 0x7F;
    boolean nextCwCycle = (cycleteByte & 0x80) != 0;
    int oddEven = pidField & 0xFF;
    return new Camd35Push(
        frame.caid(), frame.prid(), frame.srvid(), frame.idx(),
        frame.rcByte(), oddEven,
        cycleTime, nextCwCycle,
        ecmd5, dataHash, cw, nodes);
  }

  /**
   * Encode le body du FILTER 0x3C (242 octets). Entrées vides = accept-all
   * (cevnum=0, sémantique OSCam).
   */
  public static byte[] encodeFilterBody(List<Camd35FilterEntry> entries) {
    if (entries != null && entries.size() > FILTER_MAX_ENTRIES) {
      throw new IllegalArgumentException("camd35 filter limit " + FILTER_MAX_ENTRIES);
    }
    byte[] body = new byte[FILTER_BODY_SIZE];
    Arrays.fill(body, (byte) 0);
    int cevnum = entries == null ? 0 : entries.size();
    body[0] = (byte) ((cevnum >>> 8) & 0xFF);
    body[1] = (byte) (cevnum & 0xFF);
    int ofs = 2;
    for (int j = 0; j < FILTER_MAX_ENTRIES; j++) {
      int v = (entries != null && j < entries.size()) ? entries.get(j).caid() : 0;
      writeU32BE(body, ofs, v);
      ofs += 4;
    }
    for (int j = 0; j < FILTER_MAX_ENTRIES; j++) {
      int v = (entries != null && j < entries.size()) ? entries.get(j).cmask() : 0;
      writeU32BE(body, ofs, v);
      ofs += 4;
    }
    for (int j = 0; j < FILTER_MAX_ENTRIES; j++) {
      int v = (entries != null && j < entries.size()) ? entries.get(j).prid() : 0;
      writeU32BE(body, ofs, v);
      ofs += 4;
    }
    for (int j = 0; j < FILTER_MAX_ENTRIES; j++) {
      int v = (entries != null && j < entries.size()) ? entries.get(j).srvid() : 0;
      writeU32BE(body, ofs, v);
      ofs += 4;
    }
    return body;
  }

  /** Parse un body FILTER 0x3C — tolère une longueur courte (cevnum lu, reste zéro-padded). */
  public static List<Camd35FilterEntry> parseFilterBody(byte[] data) {
    if (data == null || data.length < 2) {
      throw new IllegalArgumentException("cacheex filter body too short");
    }
    int cevnum = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    cevnum = Math.min(cevnum, FILTER_MAX_ENTRIES);
    List<Camd35FilterEntry> out = new ArrayList<>(cevnum);
    int base = 2;
    for (int i = 0; i < cevnum; i++) {
      int caid = readU32BE(data, base + 4 * i);
      int cmask = readU32BE(data, base + 4 * FILTER_MAX_ENTRIES + 4 * i);
      int prid = readU32BE(data, base + 8 * FILTER_MAX_ENTRIES + 4 * i);
      int srvid = readU32BE(data, base + 12 * FILTER_MAX_ENTRIES + 4 * i);
      out.add(new Camd35FilterEntry(caid, cmask, prid, srvid));
    }
    return out;
  }

  private static void writeU32BE(byte[] buf, int ofs, int v) {
    buf[ofs] = (byte) ((v >>> 24) & 0xFF);
    buf[ofs + 1] = (byte) ((v >>> 16) & 0xFF);
    buf[ofs + 2] = (byte) ((v >>> 8) & 0xFF);
    buf[ofs + 3] = (byte) (v & 0xFF);
  }

  private static int readU32BE(byte[] buf, int ofs) {
    if (ofs + 4 > buf.length) return 0;
    return ((buf[ofs] & 0xFF) << 24) | ((buf[ofs + 1] & 0xFF) << 16)
        | ((buf[ofs + 2] & 0xFF) << 8) | (buf[ofs + 3] & 0xFF);
  }
}
