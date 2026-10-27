package com.bowman.cspng.codec.cccam;

/**
 * Constantes du protocole CCcam, extraites de module-cccam-data.h (OSCam).
 *
 * <p>Périmètre MVP : client connecteur uniquement (pas de rôle serveur).
 * Les commandes cache-exchange (MSG_CACHE_PUSH/MSG_CACHE_FILTER) sont
 * implémentées. Les commandes de couche 2.2.x (MSG_CMD_0C/0D/0E) restent
 * non implémentées.
 */
public final class CcConstants {

  private CcConstants() {}

  /** Taille maximale d'un payload CCcam (module-cccam-data.h: CC_MAXMSGSIZE). */
  public static final int CC_MAXMSGSIZE = 1024;

  /** Taille de l'en-tête CCcam : [flag] [cmd] [len_hi] [len_lo]. */
  public static final int CC_HEADER_LEN = 4;

  /** Taille de la réponse CW (16 octets, DVB-CSA). */
  public static final int CW_SIZE = 16;

  /** Taille maximale d'un ECM. */
  public static final int MAX_ECM_SIZE = CC_MAXMSGSIZE - 17; // 17 = header ecm request

  // ---- Messages (module-cccam-data.h cc_msg_type_t) ----

  /** Client → Serveur : données d'identification du client (93 octets). */
  public static final int MSG_CLI_DATA = 0x00;

  /** Bidirectionnel : requête ECM ou réponse CW (selon le sens). */
  public static final int MSG_CW_ECM = 0x01;

  /** EMM ACK. */
  public static final int MSG_EMM_ACK = 0x02;

  /** Serveur → Client : une carte a été retirée. */
  public static final int MSG_CARD_REMOVED = 0x04;

  /** Bidirectionnel : challenge/réponse anti-bot. */
  public static final int MSG_CMD_05 = 0x05;

  /** Bidirectionnel : keepalive. */
  public static final int MSG_KEEPALIVE = 0x06;

  /** Serveur → Client : annonce d'une nouvelle carte disponible. */
  public static final int MSG_NEW_CARD = 0x07;

  /** Serveur → Client : données du serveur (node_id, version, build, 72 octets). */
  public static final int MSG_SRV_DATA = 0x08;

  /** Commande 0x0A. */
  public static final int MSG_CMD_0A = 0x0A;

  /** Challenge AES-128-ECB (vérification client 2.2.x). */
  public static final int MSG_CMD_0B = 0x0B;

  /** Commande 2.2.x — chiffrement CW additionnel (non implémenté MVP). */
  public static final int MSG_CMD_0C = 0x0C;

  /** Commande 2.2.x — mise à jour de clé CMD_0C (non implémenté MVP). */
  public static final int MSG_CMD_0D = 0x0D;

  /** Commande 2.2.x — non implémentée côté OSCam, ignore. */
  public static final int MSG_CMD_0E = 0x0E;

  // ---- Messages Cache-Exchange (OSCam module-cccam-cacheex.c) ----

  /** CCcam Cache-Exchange PUSH (CW entrante/sortante). */
  public static final int MSG_CACHE_PUSH = 0x81;

  /** CCcam Cache-Exchange FILTER (filtre de cache). */
  public static final int MSG_CACHE_FILTER = 0x82;

  /** CCcam Cache-Exchange feature exchange (AIO negotiation). */
  public static final int MSG_CACHE_FEATURE_EXCHANGE = 0x83;

  /** CCcam Cache-Exchange feature exchange reply. */
  public static final int MSG_CACHE_FEATURE_EXCHANGE_REPLY = 0x84;

  /** CCcam Cache-Exchange feature trigger (AIO trigger). */
  public static final int MSG_CACHE_FEATURE_TRIGGER = 0x85;

  // ---- Tailles Cache-Exchange ----

  /** Taille du corps PUSH (ecmd5 + hash + cw = 16+4+16). */
  public static final int MSG_CACHE_PUSH_BODY_SIZE = 36;

  /** Taille d'un payload FILTER standard. */
  public static final int MSG_CACHE_FILTER_SIZE = 482;

  /** Nombre maximum de couples caid/mask dans un FILTER. */
  public static final int CACHEEX_MAX_FILTERS = 30;

  /** Return code PUSH : found (CW valide). */
  public static final int E_FOUND = 0;

  /** Return code PUSH : unhandled (pas de CW). */
  public static final int E_UNHANDLED = 100;

  // ---- Modes Cache-Exchange ----

  /** Cache-Exchange désactivé. */
  public static final int CACHEEX_MODE_OFF = 0;

  /** Cache-Exchange mode 2 (receive only — OSCam out). */
  public static final int CACHEEX_MODE_RECEIVE = 2;

  /** Cache-Exchange mode 3 (send only — OSCam in). */
  public static final int CACHEEX_MODE_SEND = 3;

  /** Hop maximum par défaut pour le Cache-Exchange. */
  public static final int CACHEEX_DEFAULT_MAXHOP = 10;

  /** Flag lg (last gap) ajouté en émission pour compatibilité OSCam AIO. */
  public static final int CACHEEX_FLAG_LG = 0xFF;

  /** Carte plus disponible (échec de décodage) sur le card_id ciblé. */
  public static final int MSG_CW_NOK1 = 0xFE;

  /** Échec de décodage générique. */
  public static final int MSG_CW_NOK2 = 0xFF;

  /** Pseudo-commande indiquant l'absence d'en-tête (handshake MSG_NO_HEADER). */
  public static final int MSG_NO_HEADER = 0xFFFF;

  // ---- Tailles de payload fixes ----

  /** Taille payload MSG_CLI_DATA (client → serveur). */
  public static final int MSG_CLI_DATA_SIZE = 93; // 20+8+1+32+32

  /** Taille payload MSG_SRV_DATA standard (serveur → client). */
  public static final int MSG_SRV_DATA_SIZE = 72; // 0x48 = 8+32+32

  /** Taille du champ username (20 octets, padding '\0'). */
  public static final int USERNAME_SIZE = 20;

  /** Taille du champ version/build (32 octets ascii). */
  public static final int VERSION_SIZE = 32;

  /** Taille du champ build (32 octets ascii). */
  public static final int BUILD_SIZE = 32;

  // ---- Version/builtin utilisée par le connecteur ----

  /** Version CCcam déclarée par le connecteur (OSCam-like). */
  public static final String DEFAULT_VERSION = "2.3.2";

  /** Build CCcam déclaré par le connecteur (OSCam-like). */
  public static final String DEFAULT_BUILD = "4000";

  // ---- Modes de chiffrement ----

  public static final int DECRYPT = 0;
  public static final int ENCRYPT = 1;

  // ---- Tailles diverses ----

  /** Taille d'un node_id CCcam. */
  public static final int NODE_ID_SIZE = 8;

  /** Taille du hash SHA-1. */
  public static final int SHA1_SIZE = 20;

  /** Taille du seed de handshake. */
  public static final int SEED_SIZE = 16;

  /** Taille d'un bloc de challenge CMD_0B. */
  public static final int CMD_0B_CHALLENGE_SIZE = 16;

  /** Taille du payload CMD_05 standard. */
  public static final int CMD_05_PAYLOAD_SIZE = 256;

  /** Chaîne magique "CCcam\0" utilisée dans le handshake et l'obfuscation.
   *  En C : {@code const char cccam[] = "CCcam";} = 6 octets (null-terminator inclus). */
  public static final byte[] CCCAM_MAGIC = {'C', 'C', 'c', 'a', 'm', 0};

  /** Flag réservé (g_flag) en mode non-étendu. */
  public static final int FLAG_NORMAL = 0;
}
