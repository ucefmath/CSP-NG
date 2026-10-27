package com.bowman.cspng.codec.newcamd;

/**
 * Constantes du protocole Newcamd, reproduites du legacy.
 *
 * <p>Ces constantes sont issues de {@code CamdConstants} (legacy Java) et du protocole
 * newcamd standard.
 */
public final class NewcamdConstants {

  private NewcamdConstants() {}

  /** Taille maximale d'un message Newcamd (legacy : {@code CamdConstants.CWS_NETMSGSIZE}). */
  public static final int CWS_NETMSGSIZE = 240;

  /** Première commande (legacy {@code CWS_FIRSTCMDNO}). */
  public static final int CWS_FIRSTCMDNO = 0xE0;

  // Commandes Newcamd standard
  public static final int MSG_CLIENT_2_SERVER_LOGIN      = CWS_FIRSTCMDNO;       // 0xE0
  public static final int MSG_CLIENT_2_SERVER_LOGIN_ACK  = CWS_FIRSTCMDNO + 1;   // 0xE1
  public static final int MSG_CLIENT_2_SERVER_LOGIN_NAK  = CWS_FIRSTCMDNO + 2;   // 0xE2
  public static final int MSG_CARD_DATA_REQ              = CWS_FIRSTCMDNO + 3;   // 0xE3
  public static final int MSG_CARD_DATA                 = CWS_FIRSTCMDNO + 4;    // 0xE4
  public static final int MSG_SERVER_2_CLIENT_NAME       = CWS_FIRSTCMDNO + 5;   // 0xE5
  public static final int MSG_SERVER_2_CLIENT_NAME_ACK   = CWS_FIRSTCMDNO + 6;   // 0xE6
  public static final int MSG_SERVER_2_CLIENT_NAME_NAK   = CWS_FIRSTCMDNO + 7;   // 0xE7
  public static final int MSG_SERVER_2_CLIENT_LOGIN      = CWS_FIRSTCMDNO + 8;   // 0xE8
  public static final int MSG_SERVER_2_CLIENT_LOGIN_ACK  = CWS_FIRSTCMDNO + 9;   // 0xE9
  public static final int MSG_SERVER_2_CLIENT_LOGIN_NAK  = CWS_FIRSTCMDNO + 10;  // 0xEA
  public static final int MSG_ADMIN                      = CWS_FIRSTCMDNO + 11;  // 0xEB
  public static final int MSG_ADMIN_ACK                  = CWS_FIRSTCMDNO + 12;  // 0xEC
  public static final int MSG_ADMIN_LOGIN                = CWS_FIRSTCMDNO + 13;  // 0xED
  public static final int MSG_ADMIN_LOGIN_ACK            = CWS_FIRSTCMDNO + 14;  // 0xEE
  public static final int MSG_ADMIN_LOGIN_NAK            = CWS_FIRSTCMDNO + 15;  // 0xEF
  public static final int MSG_ADMIN_COMMAND              = CWS_FIRSTCMDNO + 16;  // 0xF0
  public static final int MSG_ADMIN_COMMAND_ACK          = CWS_FIRSTCMDNO + 17;  // 0xF1
  public static final int MSG_ADMIN_COMMAND_NAK          = CWS_FIRSTCMDNO + 18;  // 0xF2
  public static final int MSG_KEEPALIVE                  = CWS_FIRSTCMDNO + 0x1D; // 0xFD

  /** Tag ECM standard (DVB). */
  public static final int ECM_COMMAND_TAG = 0x80;

  /** Tag EMM. */
  public static final int EMM_COMMAND_TAG = 0x82;

  // Extended Newcamd (ExtNewcamd)
  public static final int EXT_ADD_CARD = 0xD3;
  public static final int EXT_REMOVE_CARD = 0xD4;
  public static final int EXT_GET_VERSION = 0xD6;
  public static final int EXT_OSD_MESSAGE = 0xD7; // legacy
  public static final int EXT_SID_LIST = 0xD5;
}
