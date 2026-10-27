package com.bowman.cspng.codec.cccam;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parseur/sérialiseur des messages de la couche applicative CCcam.
 *
 * <p>Messages implémentés (MVP client) :
 * <ul>
 *   <li>{@code MSG_CLI_DATA} — identification client (93 octets)</li>
 *   <li>{@code MSG_SRV_DATA} — données serveur (72 octets)</li>
 *   <li>{@code MSG_NEW_CARD} — annonce de carte</li>
 *   <li>{@code MSG_CARD_REMOVED} — retrait de carte</li>
 *   <li>Requête ECM (MSG_CW_ECM client→serveur)</li>
 *   <li>Réponse CW (MSG_CW_ECM serveur→client)</li>
 * </ul>
 *
 * <p>ZÉRO dépendance Vert.x.
 */
public final class CcCardCodec {

  private CcCardCodec() {}

  // ============================================================
  // Modèle de carte CCcam
  // ============================================================

  /**
   * Représente une carte CCcam en mémoire (par connexion).
   */
  public static final class CcCard {
    public final int cardId;
    public final int remoteId;
    public final int caid;
    public final int hop;
    public final int reshare;
    public final byte[] hexserial;
    public final List<CcProvider> providers;

    public CcCard(int cardId, int remoteId, int caid, int hop, int reshare, byte[] hexserial) {
      this.cardId = cardId;
      this.remoteId = remoteId;
      this.caid = caid;
      this.hop = hop;
      this.reshare = reshare;
      this.hexserial = hexserial.clone();
      this.providers = new ArrayList<>();
    }

    /** Vérifie si cette carte supporte un couple caid/provider donné. */
    public boolean matches(int caId, int provider) {
      return this.caid == caId && (providers.isEmpty() || hasProvider(provider));
    }

    private boolean hasProvider(int provider) {
      for (CcProvider p : providers) {
        if (p.provider == provider) return true;
      }
      return false;
    }

    @Override
    public String toString() {
      return String.format("CcCard[id=%08X,caid=%04X,hop=%d,reshare=%d,nprov=%d]",
          cardId, caid, hop, reshare, providers.size());
    }
  }

  /** Provider CCcam (3 octets provider + 4 octets SA). */
  public record CcProvider(int provider, byte[] sa) {
    public CcProvider {
      sa = sa != null ? sa.clone() : new byte[4];
    }
  }

  /**
   * Données d'identification serveur (extraites de MSG_SRV_DATA).
   */
  public record SrvData(byte[] nodeId, byte[] peerVersion, byte[] peerBuild) {
    public SrvData {
      nodeId = nodeId.clone();
      peerVersion = peerVersion.clone();
      peerBuild = peerBuild.clone();
    }
  }

  // ============================================================
  // MSG_CLI_DATA (client → serveur)
  // ============================================================

  /**
   * Construit le payload MSG_CLI_DATA (93 octets).
   *
   * <pre>
   * [0..19]  = username (20 octets, padding '\0')
   * [20..27] = node_id client (8 octets)
   * [28]     = wantEMU (0 = non)
   * [29..60] = version ascii (32 octets)
   * [61..92] = build ascii (32 octets)
   * </pre>
   */
  public static byte[] buildCliData(String username, byte[] nodeId,
                                     String version, String build) {
    byte[] buf = new byte[CcConstants.MSG_CLI_DATA_SIZE];
    // username (20 octets, padding '\0')
    byte[] userBytes = username.getBytes();
    int userLen = Math.min(userBytes.length, CcConstants.USERNAME_SIZE);
    System.arraycopy(userBytes, 0, buf, 0, userLen);

    // node_id (8 octets)
    System.arraycopy(nodeId, 0, buf, 20, CcConstants.NODE_ID_SIZE);

    // wantEMU = 0 (offset 28, déjà à 0)

    // version ascii (32 octets)
    byte[] versionBytes = version.getBytes();
    int vLen = Math.min(versionBytes.length, CcConstants.VERSION_SIZE);
    System.arraycopy(versionBytes, 0, buf, 29, vLen);

    // build ascii (32 octets)
    byte[] buildBytes = build.getBytes();
    int bLen = Math.min(buildBytes.length, CcConstants.BUILD_SIZE);
    System.arraycopy(buildBytes, 0, buf, 61, bLen);

    return buf;
  }

  // ============================================================
  // MSG_SRV_DATA (serveur → client)
  // ============================================================

  /**
   * Parse le payload MSG_SRV_DATA (72 octets standard).
   *
   * <pre>
   * [0..7]   = node_id serveur (8 octets)
   * [8..39]  = version ascii (32 octets)
   * [40..71] = build ascii (32 octets)
   * </pre>
   */
  public static SrvData parseSrvData(byte[] payload) {
    if (payload.length < CcConstants.MSG_SRV_DATA_SIZE) {
      throw new IllegalArgumentException("SRV_DATA payload too short: "
          + payload.length + " < " + CcConstants.MSG_SRV_DATA_SIZE);
    }

    byte[] nodeId = Arrays.copyOfRange(payload, 0, 8);
    byte[] version = Arrays.copyOfRange(payload, 8, 40);
    byte[] build = Arrays.copyOfRange(payload, 40, 72);
    return new SrvData(nodeId, version, build);
  }

  // ============================================================
  // MSG_NEW_CARD / MSG_CARD_REMOVED
  // ============================================================

  /**
   * Parse le payload MSG_NEW_CARD.
   *
   * <pre>
   * [0..3]   = card_id (BE)
   * [4..7]   = remote_id (BE)
   * [8..9]   = caid (BE)
   * [10]     = hop
   * [11]     = reshare
   * [12..19] = hexserial (8 octets)
   * [20]     = nprov
   * puis nprov × 7 octets : provider(3) + SA(4)
   * puis 1 octet remote_count + remote_count × 8 octets (node-ids, ignorés MVP)
   * </pre>
   */
  public static CcCard parseNewCard(byte[] payload) {
    if (payload.length < 21) {
      throw new IllegalArgumentException("NEW_CARD payload too short: " + payload.length);
    }

    int cardId = readIntBE(payload, 0);
    int remoteId = readIntBE(payload, 4);
    int caid = readShortBE(payload, 8);
    int hop = payload[10] & 0xFF;
    int reshare = payload[11] & 0xFF;
    byte[] hexserial = Arrays.copyOfRange(payload, 12, 20);

    CcCard card = new CcCard(cardId, remoteId, caid, hop, reshare, hexserial);

    int nprov = payload[20] & 0xFF;
    int offset = 21;
    for (int i = 0; i < nprov && offset + 7 <= payload.length; i++) {
      int provider = ((payload[offset] & 0xFF) << 16)
                   | ((payload[offset + 1] & 0xFF) << 8)
                   | (payload[offset + 2] & 0xFF);
      byte[] sa = Arrays.copyOfRange(payload, offset + 3, offset + 7);
      card.providers.add(new CcProvider(provider, sa));
      offset += 7;
    }

    // Ignorer remote_count + remote_nodes (MVP : pas de vérification anti-boucle)
    return card;
  }

  /**
   * Construit le payload MSG_NEW_CARD.
   *
   * <pre>
   * [0..3]   = card_id (BE)
   * [4..7]   = remote_id (BE)
   * [8..9]   = caid (BE)
   * [10]     = hop
   * [11]     = reshare
   * [12..19] = hexserial (8 octets)
   * [20]     = nprov
   * puis nprov × 7 octets : provider(3) + SA(4)
   * puis 1 octet remote_count + remote_count × 8 octets (node-ids)
   * </pre>
   */
  public static byte[] buildNewCard(int cardId, int remoteId, int caid, int hop, int reshare,
                                    byte[] hexserial, List<CcProvider> providers, List<byte[]> nodeIds) {
    int nprov = providers != null ? providers.size() : 0;
    int nnode = nodeIds != null ? nodeIds.size() : 0;
    int len = 21 + nprov * 7 + 1 + nnode * 8;
    byte[] buf = new byte[len];
    writeIntBE(buf, 0, cardId);
    writeIntBE(buf, 4, remoteId);
    writeShortBE(buf, 8, caid);
    buf[10] = (byte) (hop & 0xFF);
    buf[11] = (byte) (reshare & 0xFF);
    if (hexserial != null && hexserial.length >= 8) {
      System.arraycopy(hexserial, 0, buf, 12, 8);
    }
    buf[20] = (byte) (nprov & 0xFF);
    int off = 21;
    if (providers != null) {
      for (CcProvider p : providers) {
        buf[off] = (byte) ((p.provider() >> 16) & 0xFF);
        buf[off + 1] = (byte) ((p.provider() >> 8) & 0xFF);
        buf[off + 2] = (byte) (p.provider() & 0xFF);
        byte[] sa = p.sa();
        if (sa != null && sa.length >= 4) {
          System.arraycopy(sa, 0, buf, off + 3, 4);
        }
        off += 7;
      }
    }
    buf[off++] = (byte) (nnode & 0xFF);
    if (nodeIds != null) {
      for (byte[] nid : nodeIds) {
        if (nid != null && nid.length >= 8) {
          System.arraycopy(nid, 0, buf, off, 8);
        }
        off += 8;
      }
    }
    return buf;
  }

  /**
   * Construit le payload MSG_CARD_REMOVED (4 octets = card_id).
   */
  public static byte[] buildCardRemoved(int cardId) {
    byte[] buf = new byte[4];
    writeIntBE(buf, 0, cardId);
    return buf;
  }

  /**
   * Parse le payload MSG_CARD_REMOVED (4 octets = card_id).
   */
  public static int parseCardRemoved(byte[] payload) {
    if (payload.length < 4) {
      throw new IllegalArgumentException("CARD_REMOVED payload too short: " + payload.length);
    }
    return readIntBE(payload, 0);
  }

  // ============================================================
  // Requête ECM (MSG_CW_ECM, client → serveur)
  // ============================================================

  /**
   * Reconstruit la section DVB complète à partir du corps ECM amputé (invariant du domaine).
   *
   * <p>Contrat wire CCcam (OSCam module-cccam.c l.1873) : le champ ECM de la requête
   * porte la section ENTIÈRE {@code er->ecm} = [table_id][flags|len_hi][len_lo][payload...],
   * validée côté serveur par {@code SCT_LEN} (oscam-ecm.c l.3030,
   * {@code SCT_LEN(sct) = 3+((sct[1]&amp;0x0f)&lt;&lt;8)+sct[2]} — globals.h l.662 :
   * seuls les 12 bits de poids faible de la longueur comptent, les 4 bits de poids fort
   * de l'octet [1] sont des flags réservés).
   *
   * <p>L'invariant du domaine csp-ng est un ECM <b>amputé de ses 3 premiers octets</b>
   * (cf. {@code EcmKey} : dataHash = hash de l'ECM amputé ; {@code NewcamdSession} y
   * place le customData newcamad). Le table_id survit dans {@code EcmKey.commandTag} ;
   * les flags d'origine sont perdus — on émet {@code 0x70} (valeur standard des sections
   * ECM, celle que mgcamd émet et que CSP legacy reproduit — cf. CamdNetMessage
   * « mimic what mgcamd puts there »), sûre car SCT_LEN masque 0x0F.
   *
   * <p>La longueur portée par la section est la longueur du corps amputé (12 bits max) ;
   * la longueur de section résultante est {@code 3 + body.length}. Le hash domaine
   * {@code hash(ecm[3..])} n'est PAS altéré : la reconstruction n'intervient qu'à
   * l'émission, jamais dans la clé de cache.
   *
   * @param commandTag table_id/tag DVB (0x80/0x81), porté par {@code EcmKey.commandTag}
   * @param body       corps ECM amputé de ses 3 octets de section
   * @return section DVB complète [tag][0x70|len_hi][len_lo]+body
   */
  public static byte[] reconstructSection(int commandTag, byte[] body) {
    if (body == null) {
      throw new IllegalArgumentException("body must not be null");
    }
    if (body.length > 0x0FFF) {
      // Longueur 12 bits max encodable dans l'en-tête de section (SCT_LEN).
      throw new IllegalArgumentException("ECM body too large: " + body.length + " > 4095");
    }
    byte[] section = new byte[3 + body.length];
    section[0] = (byte) (commandTag & 0xFF);
    section[1] = (byte) (0x70 | ((body.length >> 8) & 0x0F));
    section[2] = (byte) (body.length & 0xFF);
    System.arraycopy(body, 0, section, 3, body.length);
    return section;
  }

  /**
   * Construit la requête ECM (MSG_CW_ECM, client → serveur).
   *
   * <pre>
   * [0..1]   = caid (BE)
   * [2..5]   = provider (BE, 4 octets)
   * [6..9]   = card_id CIBLE (BE)
   * [10..11] = srvid (BE)
   * [12]     = ecmlen (tronqué à 8 bits, cf. OSCam module-cccam.c l.1872
   *            « ecmbuf[12] = cur_er->ecmlen &amp; 0xff » — la longueur réelle sur
   *            le wire vient de l'en-tête de frame [2..3], le champ est informatif)
   * [13..]   = ECM (section DVB complète — cf. reconstructSection)
   * </pre>
   *
   * @param caid     CA ID (16 bits)
   * @param provider provider ID (32 bits)
   * @param cardId   card_id ciblé (32 bits)
   * @param srvid    service ID (16 bits)
   * @param ecm      ECM : section DVB COMPLETE [table_id][len_hi/lo][payload...]
   */
  public static byte[] buildEcmRequest(int caid, int provider, int cardId,
                                        int srvid, byte[] ecm) {
    if (ecm.length > CcConstants.MAX_ECM_SIZE) {
      throw new IllegalArgumentException("ECM exceeds CC_MAXMSGSIZE: "
          + ecm.length + " > " + CcConstants.MAX_ECM_SIZE);
    }
    byte[] buf = new byte[13 + ecm.length];
    writeShortBE(buf, 0, caid);
    writeIntBE(buf, 2, provider);
    writeIntBE(buf, 6, cardId);
    writeShortBE(buf, 10, srvid);
    // Champ [12] tronqué à 8 bits comme OSCam (module-cccam.c l.1872) : la longueur
    // réelle portée sur le wire par l'en-tête de frame (CcFrameCodec), pas par ce champ.
    buf[12] = (byte) (ecm.length & 0xFF);
    System.arraycopy(ecm, 0, buf, 13, ecm.length);
    return buf;
  }

  /**
   * Parse l'en-tête de la requête ECM (côté serveur).
   */
  public static EcmRequestHeader parseEcmRequest(byte[] payload) {
    if (payload.length < 13) {
      throw new IllegalArgumentException("ECM request too short: " + payload.length);
    }
    int caid = readShortBE(payload, 0);
    int provider = readIntBE(payload, 2);
    int cardId = readIntBE(payload, 6);
    int srvid = readShortBE(payload, 10);
    int ecmLen = payload[12] & 0xFF;
    byte[] ecm = Arrays.copyOfRange(payload, 13, 13 + ecmLen);
    return new EcmRequestHeader(caid, provider, cardId, srvid, ecm);
  }

  public record EcmRequestHeader(int caid, int provider, int cardId, int srvid, byte[] ecm) {
    public EcmRequestHeader {
      ecm = ecm.clone();
    }
  }

  // ============================================================
  // Réponse CW (MSG_CW_ECM, serveur → client)
  // ============================================================

  /**
   * Extrait les 16 octets CW de la réponse (après déchiffrement cc_cw_crypt).
   */
  public static byte[] parseCwReply(byte[] payload) {
    if (payload.length < CcConstants.CW_SIZE) {
      throw new IllegalArgumentException("CW reply too short: " + payload.length);
    }
    return Arrays.copyOf(payload, CcConstants.CW_SIZE);
  }

  // ============================================================
  // Utilitaires big-endian
  // ============================================================

  static int readShortBE(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
  }

  static int readIntBE(byte[] buf, int offset) {
    return ((buf[offset] & 0xFF) << 24)
         | ((buf[offset + 1] & 0xFF) << 16)
         | ((buf[offset + 2] & 0xFF) << 8)
         | (buf[offset + 3] & 0xFF);
  }

  static void writeShortBE(byte[] buf, int offset, int value) {
    buf[offset] = (byte) ((value >>> 8) & 0xFF);
    buf[offset + 1] = (byte) (value & 0xFF);
  }

  static void writeIntBE(byte[] buf, int offset, int value) {
    // ⚠️ >>> (unsigned shift) obligatoire : value peut représenter un uint32_t
    // avec bit de poids fort à 1 (ex. cardId > 0x7FFFFFFF, courant en CCcam)
    buf[offset] = (byte) ((value >>> 24) & 0xFF);
    buf[offset + 1] = (byte) ((value >>> 16) & 0xFF);
    buf[offset + 2] = (byte) ((value >>> 8) & 0xFF);
    buf[offset + 3] = (byte) (value & 0xFF);
  }
}
