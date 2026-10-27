package com.bowman.cspng.codec.camd35;

/**
 * Constantes du protocole camd35/CS378x (camd35 sur TCP).
 *
 * <p>Sources (lecture seule, interop par mécanismes) :
 * <ul>
 *   <li>OSCam {@code module-camd35.c} (en-tête l.30-38 : « CMD55 - connect_on_init/keepalive »,
 *       « CMD0x3c..0x3f - CACHEEX » ; auth l.128-176 ; send l.44-126 ; recv l.209-435 ;
 *       ECM l.684-713 ; server l.947-1004 ; dcw l.618-654).</li>
 *   <li>tsdecrypt {@code camd-cs378x.c} (cs378x_buf_init/do_ecm/get_cw — CRC, AES,
 *       contrôles de taille 48/52, cmd 0x01/0x08/0x44).</li>
 * </ul>
 * NOTICE provenance : comportements interopérables réimplémentés en Java idiomatique,
 * aucune copie de code. Usage privé.
 */
public final class Camd35Constants {

  private Camd35Constants() {}

  // ---- Commandes (module-camd35.c camd35_server switch(mbuf[0])) ----

  /** ECM request (client → serveur). */
  public static final int CMD_ECM = 0x00;

  /** CW reply (serveur → client), len 16. */
  public static final int CMD_CW = 0x01;

  /** ECM request cascading (client → serveur) — trailer 0x34 en plus des données. */
  public static final int CMD_ECM_CASCADING = 0x03;

  /** CW reply cascading (serveur → client) — trailer 0x34 après les 16 octets de CW. */
  public static final int CMD_CW_CASCADING = 0x04;

  /** AU/EMM request (reçu : drop sans réponse). */
  public static final int CMD_AU = 0x05;

  /** EMM (6 et 19, reçus : drop sans réponse — camd35_server case 6/19). */
  public static final int CMD_EMM = 0x06;
  public static final int CMD_EMM_19 = 19;

  /** « stopped/sleep » — PEUT être reçu d'un serveur amont ; JAMAIS émis par nous
   *  (mode suppress cmd08 : camd35_send_dcw, E_INVALID tombe sur CMD_NOK_NOTFOUND). */
  public static final int CMD_STOPPED = 0x08;

  /** NOK « no code word found » — émis pour ECM 0/3 uniquement (l.651-654). */
  public static final int CMD_NOK_NOTFOUND = 0x44;

  /** Keepalive — 55 DÉCIMAL (= 0x37, camd35_server case 55 ; réponse [55, 1, 0]). */
  public static final int CMD_KEEPALIVE = 55;

  /** Cacheex push filter (longueur 8 bits [1] = 242, fixe — hors 16 bits l.328-334). */
  public static final int CMD_CACHEEX_FILTER = 0x3C;

  /** Cacheex push id request (16 bits de longueur [1..2] — l.295-301). */
  public static final int CMD_CACHEEX_ID_REQ = 0x3D;

  /** Cacheex push id answer (16 bits de longueur). */
  public static final int CMD_CACHEEX_ID_ANSWER = 0x3E;

  /** Cacheex push (16 bits de longueur). */
  public static final int CMD_CACHEEX_PUSH = 0x3F;

  // ---- Tailles fixes du framing ----

  /** Préfixe ucrc CLAIR devant CHAQUE frame (module-camd35.c l.60 « memcpy(rbuf, cl->ucrc, 4) »). */
  public static final int UCRC_SIZE = 4;

  /** En-tête interne chiffré : cmd, len, 2 libres, CRC32(4), sid(2), caid(2), prid(4), idx(2), pid(2). */
  public static final int HEADER_SIZE = 20;

  /** CW = 16 octets. */
  public static final int CW_SIZE = 16;

  /** Trailer cascading 0x34 = 52 octets (cmd 3 et 4 — l.66 « (buf[0]==3)||(buf[0]==4) ? 0x34 »). */
  public static final int TRAILER_SIZE = 0x34;

  /** Padding : 0xFF (l.72 « set unused space to 0xFF for newer camd3's », tsdecrypt idem). */
  public static final byte PADDING = (byte) 0xFF;

  /** Minimum lu avant dérivation : 4 ucrc + 32 data (l.196 « read minimum packet size
   *  (4 byte ucrc + 32 byte data) », l.230 « tot = 36 »). */
  public static final int MIN_WIRE = UCRC_SIZE + 32;

  /** Taille wire d'une réponse CW cmd 1 : 4 + boundary16(20+16) = 4+48 = 52
   *  (tsdecrypt cs378x_get_cw : « Code word packet len != 48 » + 4 ucrc). */
  public static final int CW_REPLY_WIRE_SIZE = UCRC_SIZE + 48;

  /** ECM max (OSCam MAX_ECM_SIZE ; camd35_process_ecm « ecmlen > MAX_ECM_SIZE » → drop). */
  public static final int MAX_ECM_SIZE = 1024;

  /** Taille chiffrée max bornée : boundary16(20 + 1024 + 52) = 1096. */
  public static final int MAX_ENCRYPTED_SIZE = align16(HEADER_SIZE + MAX_ECM_SIZE + TRAILER_SIZE);

  /** Taille wire max bornée avant toute allocation (dérivation bornée, l.310-316). */
  public static final int MAX_WIRE_SIZE = UCRC_SIZE + MAX_ENCRYPTED_SIZE;

  /** Bloc AES. */
  public static final int AES_BLOCK = 16;

  /** Arrondi au multiple de 16 supérieur — port de {@code boundary(4, l)}
   *  (oscam-string.c l.603 : (((n-1) >> exp) + 1) << exp, exp=4 → multiples de 16). */
  public static int align16(int n) {
    if (n <= 0) return 0;
    return (((n - 1) >> 4) + 1) << 4;
  }

  /** Longueur DVB 12 bits d'une section ECM : {@code SCT_LEN(sct) = 3+((sct[1]&amp;0x0f)&lt;&lt;8)+sct[2]}
   *  (OSCam globals.h l.662) — utilisée par camd35 pour cmd 0/3 TOUJOURS, même &gt; 255. */
  public static int sctLen(byte[] sct) {
    return 3 + (((sct[1] & 0x0F) << 8) | (sct[2] & 0xFF));
  }
}
