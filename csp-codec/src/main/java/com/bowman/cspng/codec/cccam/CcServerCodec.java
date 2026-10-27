package com.bowman.cspng.codec.cccam;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Primitives SERVEUR du protocole CCcam (rôle d'un serveur acceptant des clients).
 *
 * <p>Tous les contrats ci-dessous sont VALIDÉS EXPÉRIMENTALEMENT contre OSCam
 * 2.26.05-11959 compilé (cf. docs/INBOUND-CAMS-PLAN.md §0.2/§0.3 et
 * docs/INBOUND-CAMS-PROGRESS.md §2 — preuves : benchs scratch + patch de diagnostic
 * DBGSRV) et dérivés de la lecture d'OSCam module-cccam.c :
 * <ul>
 *   <li><b>Seed</b> (l.4253-4263) : 12 octets aléatoires + 4 checksums
 *       {@code data[12+i] = (data[i] + data[4+i] + data[8+i]) & 0xff}, envoyés EN CLAIR.</li>
 *   <li><b>Handshake miroir</b> (l.4263-4273) : {@code cc_xor(data)} PUIS SHA1 PUIS
 *       blocs AVEC MUTATION IN PLACE — {@code cc_crypt} transforme {@code data} avant
 *       {@code cc_init_crypt(block[DECRYPT], data, 16)} : le bloc DECRYPT est initialisé
 *       avec le seed MUTÉ (preuve : un client qui clonait avant mutation est rejeté
 *       « illegal username received » ; le CcCamConnector existant fait correctement
 *       la mutation). Bloc ENCRYPT = init(hash,20) + advance(seedMuté,16,DECRYPT) ;
 *       bloc DECRYPT = init(seedMuté,16) + advance(hash,20,DECRYPT).</li>
 *   <li><b>Mot de passe</b> (l.4340-4378) : sauver les 2 blocs → restaurer → avancer le
 *       bloc DECRYPT avec le password (mode ENCRYPT) → déchiffrer le token 6 octets →
 *       {@code "CCcam\0"} (memcmp 6).</li>
 *   <li><b>ACK login</b> (l.4423-4427) : 20 octets {@code "CCcam\0"} + zéros, chiffrés
 *       par le bloc ENCRYPT, sans en-tête de frame.</li>
 *   <li><b>Réponse CW</b> (cc_send_dcw l.3989-4030) : scramble {@code cc_cw_crypt} avec
 *       le node id du PAIR (peer — celui du client, lu dans CLI_DATA payload[20..27])
 *       + cardId, envoi de la frame, PUIS sync du bloc ENCRYPT par 16 octets en mode
 *       ENCRYPT AVEC LE CW SCRAMBLÉ. La divergence d'état Δ qui en résulte (le client
 *       sync avec le CW clair, l.3551) est absorbée par le PREMIER octet de la frame
 *       suivante (le flag [0], ignoré des deux côtés) — MESURÉ : états srv 0x1e vs cli
 *       0x20 après CW#1, frame suivante correcte à partir de l'octet 1, 15/15 CW
 *       validés en mode non étendu.</li>
 *   <li><b>NOK</b> (l.4054+) : frame MSG_CW_NOK1/2 SANS sync supplémentaire.</li>
 * </ul>
 *
 * <p>ZÉRO dépendance Vert.x. NOTICE provenance : OSCam COPYING GPLv3 — contrats
 * interopérables réimplémentés en Java idiomatique, aucune copie de code, usage privé.
 */
public final class CcServerCodec {

  private CcServerCodec() {}

  // ============================================================
  // Seed & handshake
  // ============================================================

  /**
   * Génère le seed de handshake serveur : 12 octets aléatoires + 4 checksums
   * « O » cccam (module-cccam.c l.4253-4259), à envoyer EN CLAIR.
   *
   * <p>12+4 (et non 16 aléatoires) : la structure de checksum est partie du contrat —
   * un client la vérifie pour distinguer un serveur « O » cccam (multics la détecte
   * aussi). Le node id local, lui, doit rester SANS signature amont OSCam/multics
   * (pas de partenaire-id 0x10/0xAA façon cc_update_nodeid) : c'est le rôle de l'appelant.
   */
  public static byte[] generateSeed(SecureRandom random) {
    byte[] seed = new byte[CcConstants.SEED_SIZE];
    random.nextBytes(seed);
    for (int i = 0; i < 4; i++) {
      seed[12 + i] = (byte) ((seed[i] + seed[4 + i] + seed[8 + i]) & 0xFF);
    }
    return seed;
  }

  /**
   * Initialise les blocs de chiffrement SERVEUR à partir du seed envoyé (mutation in place).
   *
   * <p>Après appel : {@code encBlock} chiffre le sens serveur→client, {@code decBlock}
   * déchiffre le sens client→serveur. Le paramètre {@code seed} est MUTÉ (comme OSCam —
   * il ne doit plus être utilisé par l'appelant ensuite) : le port exact l.4263-4273 est
   * encodé ici, toute déviation casse le miroir (preuve §0.3 du plan).
   *
   * @param seed     seed 16 octets TEL QU'ENVOYÉ (sera muté)
   * @param encBlock bloc ENCRYPT serveur (à réutiliser pour l'ACK, SRV_DATA, CW…)
   * @param decBlock bloc DECRYPT serveur (hash/username/token du client)
   * @return le SHA-1 du seed xoré (20 octets — utile aux tests, le serveur n'émet pas le hash)
   */
  public static byte[] initServerHandshake(byte[] seed, CcCrypt encBlock, CcCrypt decBlock) {
    if (seed == null || seed.length != CcConstants.SEED_SIZE) {
      throw new IllegalArgumentException("seed must be " + CcConstants.SEED_SIZE + " bytes");
    }
    CcCrypt.xor(seed); // module-cccam.c l.109-123
    byte[] hash = sha1(seed);

    // l.4270-4272 : block[ENCRYPT] = init(hash,20) puis crypt(data,16,DECRYPT) — data MUTÉ
    encBlock.initCrypt(hash, CcConstants.SHA1_SIZE);
    encBlock.crypt(seed, CcConstants.SEED_SIZE, CcConstants.DECRYPT);
    // l.4272-4273 : block[DECRYPT] = init(data_MUTÉ,16) puis crypt(hash,20,DECRYPT)
    decBlock.initCrypt(seed, CcConstants.SEED_SIZE);
    decBlock.crypt(hash, CcConstants.SHA1_SIZE, CcConstants.DECRYPT);
    return hash;
  }

  /**
   * Payload MSG_SRV_DATA (72 octets) : node_id serveur (8) + version ascii (32) +
   * build ascii (32) — cc_send_srv_data l.4545+ ({@code memcpy(buf, cc->node_id, 8)} …).
   */
  public static byte[] buildSrvData(byte[] nodeId, String version, String build) {
    if (nodeId == null || nodeId.length != CcConstants.NODE_ID_SIZE) {
      throw new IllegalArgumentException("nodeId must be 8 bytes");
    }
    byte[] buf = new byte[CcConstants.MSG_SRV_DATA_SIZE];
    System.arraycopy(nodeId, 0, buf, 0, 8);
    copyAscii(buf, 8, version, CcConstants.VERSION_SIZE);
    copyAscii(buf, 40, build, CcConstants.BUILD_SIZE);
    return buf;
  }

  /**
   * ACK de login : 20 octets « CCcam\0 » + zéros, À CHIFFRER par le bloc ENCRYPT
   * (mode ENCRYPT, sans en-tête de frame) — l.4423-4427.
   */
  public static byte[] buildLoginAck() {
    byte[] ack = new byte[20];
    System.arraycopy(CcConstants.CCCAM_MAGIC, 0, ack, 0, 6); // "CCcam\0" (6 octets exacts)
    return ack;
  }

  /**
   * Vérifie le token de mot de passe du client (6 octets chiffrés reçus).
   *
   * <p>Port de l.4340-4378 : sauvegarder les DEUX blocs, restaurer, avancer le bloc
   * DECRYPT avec le password en mode ENCRYPT, déchiffrer le token, comparer à
   * {@code "CCcam\0"} (memcmp 6). Les blocs passés sont MUTÉS selon le même protocole
   * (le save/restore interne garantit qu'un échec n'altère pas l'état de session).
   *
   * @param token         6 octets reçus (sera déchiffré en place)
   * @param password      mot de passe du compte (mode ENCRYPT sur le bloc DECRYPT)
   * @param encBlock      bloc ENCRYPT serveur (restauré intact)
   * @param decBlock      bloc DECRYPT serveur (restauré puis avancé du password)
   * @return true si le token vaut « CCcam\0 » (password correct)
   */
  public static boolean checkPasswordToken(byte[] token, String password,
                                           CcCrypt encBlock, CcCrypt decBlock) {
    if (token == null || token.length != 6) {
      throw new IllegalArgumentException("token must be 6 bytes");
    }
    CcCrypt savedEnc = encBlock.copy();
    CcCrypt savedDec = decBlock.copy();
    byte[] pwd = password.getBytes();
    decBlock.crypt(pwd, pwd.length, CcConstants.ENCRYPT);
    decBlock.crypt(token, 6, CcConstants.DECRYPT);
    boolean ok = token[0] == 'C' && token[1] == 'C' && token[2] == 'c'
        && token[3] == 'a' && token[4] == 'm' && token[5] == 0;
    if (!ok) {
      // l.4361 : restauration en cas d'échec seulement (pour tester le compte suivant)
      restore(savedEnc, encBlock);
      restore(savedDec, decBlock);
    }
    return ok;
  }

  // ============================================================
  // Réponses ECM (CW / NOK)
  // ============================================================

  /**
   * Encode la réponse CW d'un ECM résolu : scramble avec le node id du CLIENT (pair)
   * + cardId (cc_cw_crypt l.3989 : le serveur utilise {@code cc->peer_node_id}), frame
   * MSG_CW_ECM (4 + 16 octets) chiffrée par {@code encBlock}, PUIS avance l'état de
   * {@code encBlock} de 16 octets en mode ENCRYPT avec le CW SCRAMBLÉ (l.4030
   * « additional crypto step »).
   *
   * <p>Le sync avec le CW scramblé (et non clair) est le contrat mesuré §0.2 : la
   * divergence Δ d'un octet créée avec le sync client (CW clair, l.3551) est absorbée
   * par le flag [0] de la frame suivante — sacrifié par design. NE PAS « corriger » en
   * syncant avec le CW clair : cela décalerait la divergence vers l'octet de COMMANDE.
   *
   * @param cw         16 octets de control word EN CLAIR
   * @param peerNodeId node id du client (extrait de CLI_DATA payload[20..27])
   * @param cardId     card id annoncé (celui de la requête ECM)
   * @param encBlock   bloc ENCRYPT serveur (avancé de 20 par la frame + 16 par le sync)
   * @return la frame chiffrée prête à écrire sur le socket
   */
  public static byte[] encodeCwReply(byte[] cw, byte[] peerNodeId, int cardId, CcCrypt encBlock) {
    if (cw == null || cw.length != CcConstants.CW_SIZE) {
      throw new IllegalArgumentException("cw must be 16 bytes");
    }
    if (peerNodeId == null || peerNodeId.length != CcConstants.NODE_ID_SIZE) {
      throw new IllegalArgumentException("peerNodeId must be 8 bytes");
    }
    byte[] scrambled = cw.clone();
    CcCwCrypt.cwCrypt(scrambled, peerNodeId, cardId);
    byte[] frame = CcFrameCodec.encode(CcConstants.MSG_CW_ECM, scrambled);
    encBlock.crypt(frame, frame.length, CcConstants.ENCRYPT); // l.4021 : cc_cmd_send
    byte[] sync = scrambled.clone();
    encBlock.crypt(sync, 16, CcConstants.ENCRYPT);           // l.4030 : sync avec le SCRAMBLÉ
    return frame;
  }

  /**
   * Encode une réponse NOK (MSG_CW_NOK1 = carte plus disponible / MSG_CW_NOK2 = échec de
   * décodage) : frame à payload VIDE, SUNC supplémentaire (l.4054+ : le bloc ENCRYPT
   * n'est avancé que par l'envoi de la frame elle-même).
   */
  public static byte[] encodeNok(int msgNok, CcCrypt encBlock) {
    if (msgNok != CcConstants.MSG_CW_NOK1 && msgNok != CcConstants.MSG_CW_NOK2) {
      throw new IllegalArgumentException("not a NOK command: " + msgNok);
    }
    byte[] frame = CcFrameCodec.encode(msgNok, new byte[0]);
    encBlock.crypt(frame, frame.length, CcConstants.ENCRYPT);
    return frame;
  }

  // ============================================================
  // Utilitaires
  // ============================================================

  /** Extrait le node id du client d'un payload MSG_CLI_DATA (payload[20..27] — l.4471). */
  public static byte[] extractPeerNodeId(byte[] cliDataPayload) {
    if (cliDataPayload == null || cliDataPayload.length < 28) {
      throw new IllegalArgumentException("CLI_DATA payload too short: "
          + (cliDataPayload == null ? 0 : cliDataPayload.length));
    }
    return Arrays.copyOfRange(cliDataPayload, 20, 28);
  }

  private static void restore(CcCrypt from, CcCrypt to) {
    System.arraycopy(from.keytable, 0, to.keytable, 0, 256);
    to.state = from.state;
    to.counter = from.counter;
    to.sum = from.sum;
  }

  private static void copyAscii(byte[] buf, int off, String s, int max) {
    byte[] b = s.getBytes();
    System.arraycopy(b, 0, buf, off, Math.min(b.length, max));
  }

  private static byte[] sha1(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
  }
}
