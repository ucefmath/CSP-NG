package com.bowman.cspng.codec.cccam;

import com.bowman.cspng.codec.cluster.ClusterUdpCodec;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Codec pour les messages CCcam Cache-Exchange (OSCam module-cccam-cacheex.c).
 *
 * <h3>Messages implémentés (MVP sans AIO)</h3>
 * <ul>
 *   <li>{@code MSG_CACHE_PUSH (0x81)} — CW entrante/sortante</li>
 *   <li>{@code MSG_CACHE_FILTER (0x82)} — filtre de cache (482 octets)</li>
 *   <li>{@code MSG_CACHE_FEATURE_EXCHANGE (0x83)} — négociation AIO</li>
 *   <li>{@code MSG_CACHE_FEATURE_EXCHANGE_REPLY (0x84)} — réponse AIO</li>
 *   <li>{@code MSG_CACHE_FEATURE_TRIGGER (0x85)} — trigger AIO (ignoré MVP)</li>
 * </ul>
 *
 * <h3>Arbitrages techniques (validés par orchestrateur)</h3>
 * <ol>
 *   <li><b>Hash wire = LITTLE-ENDIAN</b> des 4 octets de {@code dataHash} int32.
 *       {@code CSP_HASH_SWAP} d'OSCam inverse l'effet de {@code i2b_buf} (big-endian),
 *       donc les octets wire = représentation LE du hash natif.</li>
 *   <li><b>Champ {@code size} (offsets 12-13) = LITTLE-ENDIAN</b> = 36.
 *       Seul champ LE du header PUSH avec le hash.</li>
 *   <li><b>CW dans PUSH = BRUTE</b> — JAMAIS {@code CcCwCrypt}.</li>
 *   <li><b>Hash algorithmique</b> = {@code ClusterUdpCodec.ecmHash} sur ECM amputé
 *       des 3 premiers octets.</li>
 *   <li><b>lg flag</b> : en émission, toujours ajouter 1 octet final {@code 0xFF}.
 *       En réception, parser selon longueur : {@code 57+8*N} sans lg, {@code 58+8*N} avec lg.</li>
 *   <li><b>FILTER</b> = 482 octets fixes. {@code cevnum=0} = accept all.</li>
 * </ol>
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class CcCacheExCodec {

  private CcCacheExCodec() {}

  // ============================================================
  // Records
  // ============================================================

  /**
   * Réponse PUSH décodée (MSG_CACHE_PUSH reçu d'un pair).
   *
   * @param caid     CA ID (16 bits)
   * @param prid     provider ID (32 bits)
   * @param srvid    service ID (16 bits)
   * @param rc       return code (E_FOUND=0 ou E_UNHANDLED=100)
   * @param oddEven  odd/even flag (0x80/0x81 ou 0)
   * @param ecmd5    MD5 de l'ECM (16 octets, peut être zéro)
   * @param dataHash hash de l'ECM amputé de ses 3 premiers octets
   * @param cw       control word brute (16 octets)
   * @param nodeIds  liste des node_ids (own node FIRST)
   * @param lgFlag   flag lg (-1 si absent, 0xFF si présent)
   */
  public record PushRecord(int caid, int prid, int srvid, int rc, int oddEven,
                            byte[] ecmd5, int dataHash, byte[] cw,
                            List<byte[]> nodeIds, int lgFlag,
                            int cycleTimeSec, boolean nextCwCycle) {
    public PushRecord {
      ecmd5 = ecmd5 != null ? ecmd5.clone() : new byte[16];
      cw = cw != null ? cw.clone() : new byte[16];
      nodeIds = List.copyOf(nodeIds != null ? nodeIds : List.of());
      if (cycleTimeSec < 0 || cycleTimeSec > 127) {
        throw new IllegalArgumentException("cycleTimeSec must be 0..127");
      }
    }

    /** Compat sans métadonnées de cycle (pre-V15). */
    public PushRecord(int caid, int prid, int srvid, int rc, int oddEven,
                      byte[] ecmd5, int dataHash, byte[] cw,
                      List<byte[]> nodeIds, int lgFlag) {
      this(caid, prid, srvid, rc, oddEven, ecmd5, dataHash, cw, nodeIds, lgFlag, 0, false);
    }

    public byte[] ecmd5() { return ecmd5.clone(); }
    public byte[] cw() { return cw.clone(); }
    public List<byte[]> nodeIds() { return List.copyOf(nodeIds); }
  }

  /**
   * Filtre de cache décodé (MSG_CACHE_FILTER reçu d'un pair).
   *
   * @param cevnum nombre d'entrées (0 = accept all)
   * @param caids  tableau de 30 caid (uint32 BE)
   * @param cmasks tableau de 30 cmask (uint32 BE)
   * @param prids  tableau de 30 prid (uint32 BE)
   * @param srvids tableau de 30 srvid (uint32 BE)
   */
  public record FilterRecord(int cevnum, int[] caids, int[] cmasks,
                              int[] prids, int[] srvids) {
    public FilterRecord {
      caids = caids.clone();
      cmasks = cmasks.clone();
      prids = prids.clone();
      srvids = srvids.clone();
    }

    public int[] caids() { return caids.clone(); }
    public int[] cmasks() { return cmasks.clone(); }
    public int[] prids() { return prids.clone(); }
    public int[] srvids() { return srvids.clone(); }

    /** Retourne true si le filtre est accept-all (cevnum == 0). */
    public boolean isAcceptAll() {
      return cevnum == 0;
    }
  }

  // ============================================================
  // PUSH (0x81)
  // ============================================================

  /**
   * Construit le payload d'un PUSH pour émission vers un pair.
   *
   * <h3>Layout (module-cccam-cacheex.c:1198-1274, 1314-1420)</h3>
   * <pre>
   * [0..1]   caid u16 BE
   * [2..5]   prid u32 BE
   * [6..9]   padding 0
   * [10..11] srvid u16 BE
   * [12..13] size u16 LE = 36
   * [14]     rc (0 ou 100)
   * [15..17] 0
   * [18]     cwc_cycletime (0 MVP)
   * [19]     odd/even (0x80/0x81 ou 0)
   * [20..35] ecmd5 16 octets
   * [36..39] csp_hash 4 octets LE (dataHash)
   * [40..55] cw 16 octets BRUT
   * [56]     node_count N
   * [57..57+8N) nodes
   * [+1]     lg flag 0xFF (toujours ajouté en émission)
   * </pre>
   *
   * @param caid     CA ID
   * @param prid     provider ID
   * @param srvid    service ID
   * @param rc       return code (E_FOUND ou E_UNHANDLED)
   * @param oddEven  odd/even flag
   * @param ecmd5    MD5 ECM (16 octets, peut être tout zéro)
   * @param dataHash hash ECM amputé des 3 premiers octets
   * @param cw       control word brute (16 octets)
   * @param nodeIds  node_ids à inclure (own node FIRST)
   * @return payload PUSH complet (avec lg flag)
   */
  public static byte[] encodePush(int caid, int prid, int srvid, int rc, int oddEven,
                                   byte[] ecmd5, int dataHash, byte[] cw,
                                   List<byte[]> nodeIds) {
    return encodePush(caid, prid, srvid, rc, oddEven, ecmd5, dataHash, cw, nodeIds, 0, false);
  }

  /**
   * Encode un PUSH complet avec métadonnées de cycle (contrat v2 §5) :
   * {@code [18] = cycletime (<128)} avec bit 0x80 portant le flag next-cw-cycle
   * (wire CCcam — miroir OSCam « buf[18] = cycletime; buf[18] |= 0x80 »).
   * cycletime = 0 ⇒ [18] = 0 (aucune métadonnée transportée).
   */
  /**
   * Normalisation TX (miroir OSCam module-cccam-cacheex.c:1230) :
   * {@code buf[19] = ecm[0] != 0x80 && ecm[0] != 0x81 ? 0 : ecm[0]}.
   * Toute parité non-80/81 part sur le wire comme 0 (INCONNU).
   */
  public static int normalizeOddEven(int oddEven) {
    return (oddEven == 0x80 || oddEven == 0x81) ? oddEven : 0;
  }

  public static byte[] encodePush(int caid, int prid, int srvid, int rc, int oddEven,
                                   byte[] ecmd5, int dataHash, byte[] cw,
                                   List<byte[]> nodeIds, int cycleTimeSec, boolean nextCwCycle) {
    oddEven = normalizeOddEven(oddEven);
    if (cycleTimeSec < 0 || cycleTimeSec > 127) {
      throw new IllegalArgumentException("cycleTimeSec must be 0..127");
    }
    if (cw == null || cw.length != CcConstants.CW_SIZE) {
      throw new IllegalArgumentException("cw must be exactly " + CcConstants.CW_SIZE + " bytes");
    }
    int nodeCount = (nodeIds != null) ? nodeIds.size() : 0;

    // Taille : 57 (header fixe) + 8*N (nodes) + 1 (lg flag)
    int totalSize = 57 + 8 * nodeCount + 1;
    byte[] buf = new byte[totalSize];
    Arrays.fill(buf, (byte) 0);

    int offset = 0;

    // [0..1] caid u16 BE
    writeInt16BE(buf, offset, caid);
    offset += 2;

    // [2..5] prid u32 BE
    writeInt32BE(buf, offset, prid);
    offset += 4;

    // [6..9] padding 0 (déjà rempli)
    offset += 4;

    // [10..11] srvid u16 BE
    writeInt16BE(buf, offset, srvid);
    offset += 2;

    // [12..13] size u16 LE = 36
    writeInt16LE(buf, offset, CcConstants.MSG_CACHE_PUSH_BODY_SIZE);
    offset += 2;

    // [14] rc
    buf[offset] = (byte) (rc & 0xFF);
    offset++;

    // [15..17] 0 (déjà rempli)
    offset += 3;

    // [18] cwc_cycletime : bit 0x80 = flag next_cw_cycle (V15)
    buf[offset] = (byte) ((cycleTimeSec & 0x7F) | (nextCwCycle ? 0x80 : 0x00));
    offset++;

    // [19] odd/even
    buf[offset] = (byte) (oddEven & 0xFF);
    offset++;

    // [20..35] ecmd5 (16 octets)
    if (ecmd5 != null) {
      int copyLen = Math.min(ecmd5.length, 16);
      System.arraycopy(ecmd5, 0, buf, offset, copyLen);
    }
    offset += 16;

    // [36..39] csp_hash 4 octets LE (dataHash)
    writeInt32LE(buf, offset, dataHash);
    offset += 4;

    // [40..55] cw 16 octets BRUT
    System.arraycopy(cw, 0, buf, offset, 16);
    offset += 16;

    // [56] node_count N
    buf[offset] = (byte) (nodeCount & 0xFF);
    offset++;

    // [57..57+8N) nodes
    if (nodeIds != null) {
      for (byte[] nodeId : nodeIds) {
        System.arraycopy(nodeId, 0, buf, offset, Math.min(nodeId.length, 8));
        offset += 8;
      }
    }

    // [+1] lg flag 0xFF (compatibilité OSCam AIO)
    buf[offset] = (byte) CcConstants.CACHEEX_FLAG_LG;

    return buf;
  }

  /**
   * Parse un payload PUSH reçu d'un pair.
   *
   * <p>La taille du payload détermine la présence du lg flag :
   * <ul>
   *   <li>{@code 57 + 8*N} : sans lg flag</li>
   *   <li>{@code 58 + 8*N} : avec lg flag</li>
   *   <li>Toute autre taille est rejetée</li>
   * </ul>
   *
   * @param payload payload PUSH déchiffré
   * @return PushRecord décodé
   * @throws IllegalArgumentException si le payload est invalide
   */
  public static PushRecord parsePush(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    if (payload.length < 57) {
      throw new IllegalArgumentException("PUSH payload too short: " + payload.length + " < 57");
    }

    int offset = 0;

    // [0..1] caid u16 BE
    int caid = readInt16BE(payload, offset);
    offset += 2;

    // [2..5] prid u32 BE
    int prid = readInt32BE(payload, offset);
    offset += 4;

    // [6..9] padding (skip)
    offset += 4;

    // [10..11] srvid u16 BE
    int srvid = readInt16BE(payload, offset);
    offset += 2;

    // [12..13] size u16 LE
    int size = readInt16LE(payload, offset);
    if (size != CcConstants.MSG_CACHE_PUSH_BODY_SIZE) {
      throw new IllegalArgumentException("PUSH size must be " + CcConstants.MSG_CACHE_PUSH_BODY_SIZE
          + ", got " + size);
    }
    offset += 2;

    // [14] rc
    int rc = payload[offset] & 0xFF;
    offset++;

    // [15..17] skip
    offset += 3;

    // [18] cwc_cycletime : bit 0x80 = flag next_cw_cycle, & 0x7F = cycletime (s)
    // (module-cw-cycle-check.c l.360 / module-camd35-cacheex.c : « buf[18] | 0x80 »)
    int cycleByte = payload[offset] & 0xFF;
    offset++;
    int cycleTimeSec = cycleByte & 0x7F;
    boolean nextCwCycle = (cycleByte & 0x80) != 0;

    // [19] odd/even
    int oddEven = payload[offset] & 0xFF;
    offset++;

    // [20..35] ecmd5 (16 octets)
    byte[] ecmd5 = Arrays.copyOfRange(payload, offset, offset + 16);
    offset += 16;

    // [36..39] csp_hash 4 octets LE
    int dataHash = readInt32LE(payload, offset);
    offset += 4;

    // [40..55] cw 16 octets BRUT
    byte[] cw = Arrays.copyOfRange(payload, offset, offset + 16);
    offset += 16;

    // [56] node_count
    int nodeCount = payload[offset] & 0xFF;
    offset++;

    // Vérifier la taille restante
    int remaining = payload.length - offset;

    // Calculer le nombre d'octets pour les nodes
    int nodesLen = 8 * nodeCount;

    // Déterminer la présence du lg flag
    boolean hasLg;
    if (remaining == nodesLen) {
      hasLg = false;
    } else if (remaining == nodesLen + 1) {
      hasLg = true;
    } else {
      throw new IllegalArgumentException(
          "PUSH payload tail length mismatch: expected " + nodesLen
          + " (no lg) or " + (nodesLen + 1) + " (with lg), got " + remaining
          + " (nodeCount=" + nodeCount + ")");
    }

    // [57..57+8N) nodes
    List<byte[]> nodeIds = new ArrayList<>(nodeCount);
    for (int i = 0; i < nodeCount; i++) {
      byte[] nodeId = Arrays.copyOfRange(payload, offset, offset + 8);
      nodeIds.add(nodeId);
      offset += 8;
    }

    // lg flag
    int lgFlag = -1;
    if (hasLg) {
      lgFlag = payload[offset] & 0xFF;
    }

    return new PushRecord(caid, prid, srvid, rc, oddEven, ecmd5, dataHash, cw,
        nodeIds, lgFlag, cycleTimeSec, nextCwCycle);
  }

  // ============================================================
  // FILTER (0x82)
  // ============================================================

  /**
   * Construit un payload FILTER (482 octets fixes).
   *
   * <pre>
   * [0..1]   cevnum u16 BE
   * [2..121] caid[30] u32 BE
   * [122..241] cmask[30] u32 BE
   * [242..361] prid[30] u32 BE
   * [362..481] srvid[30] u32 BE
   * </pre>
   *
   * <p>{@code cevnum=0} = accept-all (le pair accepte tous les CA IDs).
   *
   * @param cevnum nombre d'entrées (0 = accept all)
   * @param caids  tableau de 30 caid (uint32 BE, zéro-padding)
   * @param cmasks tableau de 30 cmask (uint32 BE)
   * @param prids  tableau de 30 prid (uint32 BE)
   * @param srvids tableau de 30 srvid (uint32 BE)
   * @return payload FILTER de 482 octets
   */
  public static byte[] encodeFilter(int cevnum, int[] caids, int[] cmasks,
                                     int[] prids, int[] srvids) {
    if (caids == null || caids.length != CcConstants.CACHEEX_MAX_FILTERS) {
      throw new IllegalArgumentException("caids must have exactly "
          + CcConstants.CACHEEX_MAX_FILTERS + " elements");
    }
    if (cmasks == null || cmasks.length != CcConstants.CACHEEX_MAX_FILTERS) {
      throw new IllegalArgumentException("cmasks must have exactly "
          + CcConstants.CACHEEX_MAX_FILTERS + " elements");
    }
    if (prids == null || prids.length != CcConstants.CACHEEX_MAX_FILTERS) {
      throw new IllegalArgumentException("prids must have exactly "
          + CcConstants.CACHEEX_MAX_FILTERS + " elements");
    }
    if (srvids == null || srvids.length != CcConstants.CACHEEX_MAX_FILTERS) {
      throw new IllegalArgumentException("srvids must have exactly "
          + CcConstants.CACHEEX_MAX_FILTERS + " elements");
    }

    byte[] buf = new byte[CcConstants.MSG_CACHE_FILTER_SIZE];
    int offset = 0;

    // [0..1] cevnum u16 BE
    writeInt16BE(buf, offset, cevnum);
    offset += 2;

    // [2..121] caid[30] u32 BE
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      writeInt32BE(buf, offset, caids[i]);
      offset += 4;
    }

    // [122..241] cmask[30] u32 BE
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      writeInt32BE(buf, offset, cmasks[i]);
      offset += 4;
    }

    // [242..361] prid[30] u32 BE
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      writeInt32BE(buf, offset, prids[i]);
      offset += 4;
    }

    // [362..481] srvid[30] u32 BE
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      writeInt32BE(buf, offset, srvids[i]);
      offset += 4;
    }

    return buf;
  }

  /**
   * Construit un FILTER accept-all (cevnum=0, tout à zéro).
   */
  public static byte[] encodeFilterAcceptAll() {
    return encodeFilter(0,
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS]);
  }

  /**
   * Construit un FILTER sans entrées actives (cevnum=0 mais tableaux vides).
   * Équivalent sémantique à accept-all.
   */
  public static byte[] encodeFilterEmpty() {
    return encodeFilter(0,
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS],
        new int[CcConstants.CACHEEX_MAX_FILTERS]);
  }

  // ============================================================
  // Spéc lisible "caid:provid" (config csp_connector.cacheex_filter)
  // ============================================================

  /** Entrée de filtre lisible : caid exact + provider exact (0 = tous providers). */
  public record FilterSpec(int caid, int provid) {
    public FilterSpec {
      if (caid < 0 || caid > 65535) throw new IllegalArgumentException("filter caid out of range");
      if (provid < 0 || provid > 16777215) throw new IllegalArgumentException("filter provid out of range");
    }

    /** Le push (caid, prid) matche-t-il cette entrée ? */
    public boolean matches(int pushCaid, int pushPrid) {
      if (caid != pushCaid) return false;
      return provid == 0 || provid == pushPrid;
    }
  }

  /**
   * Parse un spec CSV {@code "0100:00006A,0500:030B00"} (hex, espaces tolérés).
   * Vide/null ⇒ liste vide (= accept-all sémantique OSCam cevnum=0).
   *
   * @throws IllegalArgumentException sur entrée invalide ou > 30 entrées
   */
  public static List<FilterSpec> parseFilterSpec(String spec) {
    if (spec == null || spec.isBlank()) return List.of();
    List<FilterSpec> out = new ArrayList<>();
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
      out.add(new FilterSpec(caid, provid));
      if (out.size() > CcConstants.CACHEEX_MAX_FILTERS) {
        throw new IllegalArgumentException("cacheex filter limit " + CcConstants.CACHEEX_MAX_FILTERS);
      }
    }
    return out;
  }

  /**
   * Encode un payload FILTER depuis le spec lisible (cevnum = entrées ;
   * cmask 0xFFFF = match CAID exact ; srvid 0 = tous les services).
   * Spec vide/null ⇒ accept-all (cevnum=0).
   */
  public static byte[] encodeFilterFromSpec(String spec) {
    List<FilterSpec> entries = parseFilterSpec(spec);
    if (entries.isEmpty()) return encodeFilterAcceptAll();
    int[] caids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    int[] cmasks = new int[CcConstants.CACHEEX_MAX_FILTERS];
    int[] prids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    int[] srvids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    for (int i = 0; i < entries.size(); i++) {
      FilterSpec e = entries.get(i);
      caids[i] = e.caid();
      cmasks[i] = 0xFFFF; // CAID exact
      prids[i] = e.provid();
      srvids[i] = 0;      // tous les services
    }
    return encodeFilter(entries.size(), caids, cmasks, prids, srvids);
  }


  /**
   * Parse un payload FILTER (482 octets fixes).
   *
   * @param payload payload FILTER déchiffré
   * @return FilterRecord décodé
   * @throws IllegalArgumentException si le payload n'a pas exactement 482 octets
   */
  public static FilterRecord parseFilter(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    if (payload.length != CcConstants.MSG_CACHE_FILTER_SIZE) {
      throw new IllegalArgumentException("FILTER payload must be exactly "
          + CcConstants.MSG_CACHE_FILTER_SIZE + " bytes, got " + payload.length);
    }

    int offset = 0;

    // [0..1] cevnum u16 BE
    int cevnum = readInt16BE(payload, offset);
    offset += 2;

    // [2..121] caid[30] u32 BE
    int[] caids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      caids[i] = readInt32BE(payload, offset);
      offset += 4;
    }

    // [122..241] cmask[30] u32 BE
    int[] cmasks = new int[CcConstants.CACHEEX_MAX_FILTERS];
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      cmasks[i] = readInt32BE(payload, offset);
      offset += 4;
    }

    // [242..361] prid[30] u32 BE
    int[] prids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      prids[i] = readInt32BE(payload, offset);
      offset += 4;
    }

    // [362..481] srvid[30] u32 BE
    int[] srvids = new int[CcConstants.CACHEEX_MAX_FILTERS];
    for (int i = 0; i < CcConstants.CACHEEX_MAX_FILTERS; i++) {
      srvids[i] = readInt32BE(payload, offset);
      offset += 4;
    }

    return new FilterRecord(cevnum, caids, cmasks, prids, srvids);
  }

  // ============================================================
  // FEATURE EXCHANGE (0x83 / 0x84)
  // ============================================================

  /**
   * Encode un message de feature exchange.
   * Le payload est un bitfield uint32 en big-endian.
   *
   * @param bitfield bitfield des features supportées (MVP: 0)
   * @return payload de 4 octets
   */
  public static byte[] encodeFeatureExchange(int bitfield) {
    byte[] buf = new byte[4];
    writeInt32BE(buf, 0, bitfield);
    return buf;
  }

  /**
   * Parse un message de feature exchange.
   *
   * @param payload payload de 4 octets
   * @return bitfield décodé
   */
  public static int parseFeatureExchange(byte[] payload) {
    if (payload == null) {
      throw new IllegalArgumentException("payload must not be null");
    }
    if (payload.length != 4) {
      throw new IllegalArgumentException("Feature exchange payload must be exactly 4 bytes, got "
          + payload.length);
    }
    return readInt32BE(payload, 0);
  }

  // ============================================================
  // Calcul de hash ECM (compatible OSCam cacheex_ecm_hash_calc)
  // ============================================================

  /**
   * Calcule le hash ECM pour le Cache-Exchange.
   *
   * <p><b>IMPORTANT</b> : le hash doit être calculé sur l'ECM <strong>amputé de ses
   * 3 premiers octets</strong> (table_id + longueur 2 octets), pour être compatible
   * avec OSCam {@code cacheex_ecm_hash_calc()}.
   *
   * <p>Délègue à {@link ClusterUdpCodec#ecmHash(byte[], int, int)} qui implémente
   * l'algorithme {@code h = 31*h + (ecm[i]&0xFF)}.
   *
   * @param ecm ECM complet (16+ octets)
   * @return hash 32 bits signé
   */
  public static int cacheExEcmHash(byte[] ecm) {
    if (ecm == null || ecm.length <= 3) {
      return ClusterUdpCodec.ecmHash(ecm == null ? new byte[0] : ecm);
    }
    return ClusterUdpCodec.ecmHash(ecm, 3, ecm.length - 3);
  }

  // ============================================================
  // Validation
  // ============================================================

  /**
   * Vérifie si un PUSH est valide (rc == E_FOUND(0) ou E_UNHANDLED(100), CW
   * non-nulle et non-zéro) — miroir OSCam module-cccam-cacheex.c:1311, comme
   * le chemin CS378x qui accepte déjà les deux codes.
   */
  public static boolean isPushValid(PushRecord push) {
    return (push.rc() == CcConstants.E_FOUND || push.rc() == CcConstants.E_UNHANDLED)
        && push.cw() != null
        && push.cw().length == CcConstants.CW_SIZE
        && !isAllZeros(push.cw());
  }

  /**
   * Vérifie si un node_id est présent dans une liste.
   */
  public static boolean containsNode(List<byte[]> nodeIds, byte[] nodeId) {
    if (nodeIds == null || nodeId == null) return false;
    for (byte[] n : nodeIds) {
      if (Arrays.equals(n, nodeId)) return true;
    }
    return false;
  }

  /**
   * Vérifie si un tableau est entièrement composé de zéros.
   */
  public static boolean isAllZeros(byte[] arr) {
    for (byte b : arr) {
      if (b != 0) return false;
    }
    return true;
  }

  // ============================================================
  // Primitives big-endian (compatibles CcCardCodec)
  // ============================================================

  static void writeInt16BE(byte[] buf, int offset, int value) {
    buf[offset] = (byte) ((value >>> 8) & 0xFF);
    buf[offset + 1] = (byte) (value & 0xFF);
  }

  static int readInt16BE(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
  }

  static void writeInt32BE(byte[] buf, int offset, int value) {
    buf[offset] = (byte) ((value >>> 24) & 0xFF);
    buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
    buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
    buf[offset + 3] = (byte) (value & 0xFF);
  }

  static int readInt32BE(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 24)
         | ((buf[offset + 1] & 0xFF) << 16)
         | ((buf[offset + 2] & 0xFF) << 8)
         | (buf[offset + 3] & 0xFF);
  }

  // ============================================================
  // Primitives little-endian (hash + size PUSH)
  // ============================================================

  /**
   * Écrit un int32 en LITTLE-ENDIAN.
   *
   * <p>Utilisé pour le champ {@code csp_hash} (dataHash) dans PUSH :
   * OSCam applique {@code CSP_HASH_SWAP} sur le hash après {@code i2b_buf} (big-endian),
   * ce qui produit une représentation LE sur le wire.
   */
  static void writeInt32LE(byte[] buf, int offset, int value) {
    buf[offset] = (byte) (value & 0xFF);
    buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    buf[offset + 2] = (byte) ((value >>> 16) & 0xFF);
    buf[offset + 3] = (byte) ((value >>> 24) & 0xFF);
  }

  /**
   * Lit un int32 en LITTLE-ENDIAN.
   */
  static int readInt32LE(byte[] buf, int offset) {
    return (buf[offset] & 0xFF)
         | ((buf[offset + 1] & 0xFF) << 8)
         | ((buf[offset + 2] & 0xFF) << 16)
         | ((buf[offset + 3] & 0xFF) << 24);
  }

  /**
   * Écrit un int16 en LITTLE-ENDIAN.
   *
   * <p>Utilisé pour le champ {@code size} (offsets 12-13) du PUSH :
   * seul champ LE du header avec le hash.
   */
  static void writeInt16LE(byte[] buf, int offset, int value) {
    buf[offset] = (byte) (value & 0xFF);
    buf[offset + 1] = (byte) ((value >>> 8) & 0xFF);
  }

  /**
   * Lit un int16 en LITTLE-ENDIAN.
   */
  static int readInt16LE(byte[] buf, int offset) {
    return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
  }
}
