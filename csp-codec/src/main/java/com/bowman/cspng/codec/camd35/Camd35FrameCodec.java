package com.bowman.cspng.codec.camd35;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Framing camd35/CS378x : encodeur + décodeur incrémental TCP.
 *
 * <p><b>Structure wire</b> (OSCam module-camd35.c l.44-126, tsdecrypt camd-cs378x.c) :
 * <pre>
 *   [0..3]   ucrc CLAIR (CRC32(MD5(username)) BE — préfixe de CHAQUE frame)
 *   [4..n]   payload chiffré AES-128-ECB, n = boundary16(20 + dataLen + (cmd 3/4 ? 52 : 0)),
 *            padding 0xFF (l.66-72 : « memset(sbuf + l, 0xFF, 15) »)
 *   chiffré : [0]=cmd  [1]=lenByte  [2..3] libres  [4..7]=CRC32(data) BE
 *            [8..9]=srvid  [10..11]=caid  [12..15]=prid  [16..17]=idx  [18..19]=pid
 *            [20..]=data  puis trailer 52 octets si cmd 3/4
 * </pre>
 *
 * <p><b>Dérivation de la longueur des data</b> (l.293-316, « Fix for ECM request size &gt; 255 ») :
 * <ul>
 *   <li>cmd 0 (ECM) : longueur DVB 12 bits [21..22] + 3 — TOUJOURS, même &gt; 255
 *       (le byte [1] est informatif, tronqué à 8 bits par l'émetteur OSCam l.1039) ;</li>
 *   <li>cmd 0x3D/0x3E/0x3F (cacheex) : 16 bits [1..2] ;</li>
 *   <li>autres : byte [1].</li>
 * </ul>
 *
 * <p><b>Réception TCP</b> (l.209-435) : lire le minimum 36 (4 ucrc + 32 data), authentifier
 * par ucrc, déchiffrer les 32 premiers octets, dériver la taille complète BORNÉE avant
 * toute allocation, lire/déchiffrer le reste par blocs de 16, vérifier
 * {@code crc32(buf+20, buflen) == b2i(4, buf+4)} (l.416 — invalide : « checksum error
 * (wrong password?) » → fermeture SANS réponse). Le décodeur ci-dessous est incrémental :
 * la fragmentation (arrivée octet par octet) et la coalescence (plusieurs frames TCP
 * coalescées) sont gérées, les octets excédentaires sont PRÉSERVÉS (pas d'« ignoring
 * garbage » — sur TCP les extras sont le début de la frame suivante).
 *
 * <p>ZÉRO dépendance Vert.x. NOTICE provenance : contrats OSCam/tsdecrypt (GPL), usage
 * privé, réimplémentation Java idiomatique sans copie de code.
 */
public final class Camd35FrameCodec {

  private Camd35FrameCodec() {}

  // ============================================================
  // Modèle de frame
  // ============================================================

  /**
   * Frame camd35 décodée (ou à encoder).
   *
   * @param ucrc    préfixe ucrc de la frame (4 octets, écho du compte authentifié)
   * @param cmd     commande (Camd35Constants.CMD_*)
   * @param srvid   service id [8..9] (écho pour une réponse CW — l.618 : er->src_data réutilisé)
   * @param caid    CA id [10..11] (écho)
   * @param prid    provider id [12..15] (écho)
   * @param idx     index de corrélation ECM [16..17] (écho — camd35_send_dcw réutilise src_data)
   * @param pid     pid [18..19] (écho ; client OSCam : buf[18..19] = 0xFFFF, l.1048-1049)
   * @param data    données utiles (buflen octets ; ECM = section DVB COMPLETE pour cmd 0/3)
   * @param trailer 52 octets cascading si cmd 3/4, sinon vide (l.66, l.626-630)
   * @param rcByte  octet libre [3] — porteur du rc pour les push cacheex 0x3F
   *                (module-camd35-cacheex.c : {@code buf[3] = rc}) ; 0 pour les frames classiques
   */
  public record Camd35Frame(byte[] ucrc, int cmd, int srvid, int caid, int prid,
                            int idx, int pid, byte[] data, byte[] trailer, int rcByte) {
    public Camd35Frame {
      if (ucrc == null || ucrc.length != Camd35Constants.UCRC_SIZE) {
        throw new IllegalArgumentException("ucrc must be 4 bytes");
      }
      data = data != null ? data.clone() : new byte[0];
      trailer = trailer != null ? trailer.clone() : new byte[0];
      if (trailer.length > 0 && trailer.length != Camd35Constants.TRAILER_SIZE) {
        throw new IllegalArgumentException("trailer must be 0 or " + Camd35Constants.TRAILER_SIZE);
      }
      if ((cmd == Camd35Constants.CMD_ECM_CASCADING || cmd == Camd35Constants.CMD_CW_CASCADING)
          && trailer.length != Camd35Constants.TRAILER_SIZE) {
        throw new IllegalArgumentException("cmd " + cmd + " requires the 0x34 trailer");
      }
      if (rcByte < 0 || rcByte > 255) {
        throw new IllegalArgumentException("rcByte must be 0..255");
      }
      ucrc = ucrc.clone();
    }

    /** Compat sans rcByte (pre-V15 : frames classiques, [3] = 0). */
    public Camd35Frame(byte[] ucrc, int cmd, int srvid, int caid, int prid,
                        int idx, int pid, byte[] data, byte[] trailer) {
      this(ucrc, cmd, srvid, caid, prid, idx, pid, data, trailer, 0);
    }
  }

  // ============================================================
  // Encodeur
  // ============================================================

  /**
   * Encode une frame camd35 sur le wire : ucrc clair + payload AES-128-ECB aligné 16,
   * padding 0xFF, CRC32(data) en [4..7].
   *
   * <p>Le byte [1] porte la longueur des data tronquée à 8 bits (l.1039 :
   * {@code buf[1] = er->ecmlen} ; pour cmd 0/3 la longueur FAIT FOI dans l'en-tête DVB
   * [21..22], le byte [1] est informatif).
   *
   * @param frame  frame à encoder
   * @param aesKey clé AES-128 (MD5(password))
   * @return octets wire prêts à l'envoi
   */
  public static byte[] encode(Camd35Frame frame, byte[] aesKey) {
    int dataLen = frame.data().length;
    int trailerLen = frame.trailer().length;
    int encryptedLen = Camd35Constants.align16(
        Camd35Constants.HEADER_SIZE + dataLen + trailerLen);
    byte[] out = new byte[Camd35Constants.UCRC_SIZE + encryptedLen];

    System.arraycopy(frame.ucrc(), 0, out, 0, Camd35Constants.UCRC_SIZE);
    byte[] p = Arrays.copyOfRange(out, Camd35Constants.UCRC_SIZE,
        Camd35Constants.UCRC_SIZE + encryptedLen);
    Arrays.fill(p, Camd35Constants.PADDING); // l.72 : padding 0xFF

    p[0] = (byte) frame.cmd();
    p[1] = (byte) (dataLen & 0xFF); // tronqué 8 bits — cf. contrat ci-dessus
    if (frame.cmd() == Camd35Constants.CMD_CACHEEX_ID_REQ
        || frame.cmd() == Camd35Constants.CMD_CACHEEX_ID_ANSWER
        || frame.cmd() == Camd35Constants.CMD_CACHEEX_PUSH) {
      // Cacheex : longueur 16 bits LE en [1..2] (l.1188-1190 « buf[1]=size&0xff; buf[2]=size>>8 »)
      // et rc porté par l'octet libre [3] (module-camd35-cacheex.c « buf[3] = rc »)
      p[2] = (byte) ((dataLen >>> 8) & 0xFF);
      p[3] = (byte) (frame.rcByte() & 0xFF);
    }
    byte[] crc = Camd35Crypto.crc32Bytes(frame.data(), 0, dataLen); // CRC des DATA uniquement (l.68)
    System.arraycopy(crc, 0, p, 4, 4);
    writeU16(p, 8, frame.srvid());
    writeU16(p, 10, frame.caid());
    writeU32(p, 12, frame.prid());
    writeU16(p, 16, frame.idx());
    writeU16(p, 18, frame.pid());
    if (dataLen > 0) System.arraycopy(frame.data(), 0, p, 20, dataLen);
    if (trailerLen > 0) {
      System.arraycopy(frame.trailer(), 0, p, 20 + dataLen, trailerLen);
    }

    Camd35Crypto.aesEcbInPlace(p, aesKey, true);
    System.arraycopy(p, 0, out, Camd35Constants.UCRC_SIZE, encryptedLen);
    return out;
  }

  // ============================================================
  // Décodeur incrémental
  // ============================================================

  /** Statut d'un step de décodage. */
  public enum Status {
    /** Pas assez d'octets — attendre la suite (fragmentation). */
    NEED_MORE,
    /** Frame complète décodée. */
    FRAME,
    /** CRC invalide — fermer SANS réponse (l.416-420 : rc -4). */
    CRC_MISMATCH,
    /** Taille dérivée hors bornes — flux corrompu, fermer. */
    OVERFLOW
  }

  /** Résultat d'un step : statut + frame (si FRAME). */
  public record DecodeResult(Status status, Camd35Frame frame, int wireLength) {
    public static DecodeResult needMore() { return new DecodeResult(Status.NEED_MORE, null, 0); }
    public static DecodeResult frame(Camd35Frame f, int len) { return new DecodeResult(Status.FRAME, f, len); }
  }

  /**
   * Décodeur incrémental pour UN flux TCP avec UNE clé (un compte authentifié).
   *
   * <p>Usage : {@code feed(chunk)} en boucle — rend les frames au fur et à mesure ;
   * la coalescence est gérée (les octets excédentaires amorcent la frame suivante) ;
   * les tailles sont dérivées BORNÉES avant tout traitement (jamais d'allocation
   * dépendante de l'entrée).
   */
  public static final class Decoder {

    private final byte[] aesKey;
    private final byte[] acc = new byte[Camd35Constants.MAX_WIRE_SIZE];
    private int accLen;
    /** Octets déjà déchiffrés dans la frame en cours (offset dans le payload chiffré). */
    private int decryptedThrough;

    public Decoder(byte[] aesKey) {
      this.aesKey = aesKey.clone();
    }

    /**
     * Alimente le décodeur avec un chunk réseau (eventuellement fragmenté ou coalescé).
     *
     * @return le résultat du prochain décodage possible, ou NEED_MORE
     */
    public DecodeResult feed(byte[] chunk) {
      if (chunk != null && chunk.length > 0) {
        if (accLen + chunk.length > acc.length) {
          return new DecodeResult(Status.OVERFLOW, null, 0);
        }
        System.arraycopy(chunk, 0, acc, accLen, chunk.length);
        accLen += chunk.length;
      }
      return tryDecode();
    }

    private DecodeResult tryDecode() {
      if (accLen < Camd35Constants.MIN_WIRE) {
        return DecodeResult.needMore();
      }
      // Dérivation de la taille complète, BORNÉE, après déchiffrement des 32 premiers octets
      // (l.230 « tot = 36 » ; l.311 « aes_decrypt(client->aes_keys, buf, rs) » puis l.316-316+).
      decryptThrough(32);
      int cmd = acc[Camd35Constants.UCRC_SIZE] & 0xFF;
      int dataLen = deriveDataLen(cmd);
      if (dataLen < 0) {
        return new DecodeResult(Status.OVERFLOW, null, 0);
      }
      int trailerLen = (cmd == Camd35Constants.CMD_ECM_CASCADING
          || cmd == Camd35Constants.CMD_CW_CASCADING) ? Camd35Constants.TRAILER_SIZE : 0;
      int encryptedLen = Camd35Constants.align16(
          Camd35Constants.HEADER_SIZE + dataLen + trailerLen);
      int wireLen = Camd35Constants.UCRC_SIZE + encryptedLen;
      if (encryptedLen > Camd35Constants.MAX_ENCRYPTED_SIZE || wireLen > acc.length) {
        return new DecodeResult(Status.OVERFLOW, null, 0);
      }
      if (accLen < wireLen) {
        // Déchiffrer progressivement les blocs complets disponibles (l.387 : aes_decrypt(buf+32, len))
        decryptThrough(Math.min(wireLen - Camd35Constants.UCRC_SIZE,
            accLen - Camd35Constants.UCRC_SIZE) & ~15);
        return DecodeResult.needMore();
      }
      decryptThrough(encryptedLen);

      // CRC des data (l.416 : crc32(0L, buf + 20, buflen) != b2i(4, buf + 4) → rc -4)
      byte[] crcField = new byte[4];
      System.arraycopy(acc, Camd35Constants.UCRC_SIZE + 4, crcField, 0, 4);
      byte[] expected = Camd35Crypto.crc32Bytes(acc,
          Camd35Constants.UCRC_SIZE + 20, dataLen);
      if (!Arrays.equals(crcField, expected)) {
        return new DecodeResult(Status.CRC_MISMATCH, null, wireLen);
      }

      byte[] ucrc = new byte[4];
      System.arraycopy(acc, 0, ucrc, 0, 4);
      byte[] data = new byte[dataLen];
      System.arraycopy(acc, Camd35Constants.UCRC_SIZE + 20, data, 0, dataLen);
      byte[] trailer = new byte[trailerLen];
      if (trailerLen > 0) {
        System.arraycopy(acc, Camd35Constants.UCRC_SIZE + 20 + dataLen, trailer, 0, trailerLen);
      }
      Camd35Frame frame = new Camd35Frame(ucrc, cmd,
          readU16(acc, Camd35Constants.UCRC_SIZE + 8),
          readU16(acc, Camd35Constants.UCRC_SIZE + 10),
          readU32(acc, Camd35Constants.UCRC_SIZE + 12),
          readU16(acc, Camd35Constants.UCRC_SIZE + 16),
          readU16(acc, Camd35Constants.UCRC_SIZE + 18),
          data, trailer,
          acc[Camd35Constants.UCRC_SIZE + 3] & 0xFF);

      // Coalescence : les octets suivants amorcent la frame suivante (PRÉSERVÉS)
      int rest = accLen - wireLen;
      if (rest > 0) {
        System.arraycopy(acc, wireLen, acc, 0, rest);
      }
      accLen = rest;
      decryptedThrough = 0;
      return DecodeResult.frame(frame, wireLen);
    }

    /**
     * Dérive la longueur des data selon la commande (l.293-316).
     *
     * @return longueur des data, ou -1 si hors bornes
     */
    private int deriveDataLen(int cmd) {
      int base = Camd35Constants.UCRC_SIZE;
      int len;
      if (cmd == Camd35Constants.CMD_ECM || cmd == Camd35Constants.CMD_ECM_CASCADING) {
        // Longueur DVB 12 bits [21..22] + 3 — TOUJOURS, même > 255 (l.48-49).
        // Bound : max ECM 1024 (camd35_process_ecm) ; en-tête de section à data[0..2].
        int dvb = 3 + (((acc[base + 21] & 0x0F) << 8) | (acc[base + 22] & 0xFF));
        if (dvb < 4 || dvb > Camd35Constants.MAX_ECM_SIZE) {
          return -1; // section invalide : la session ferme (divergence documentée vs OSCam)
        }
        len = dvb;
      } else if (cmd == Camd35Constants.CMD_CW || cmd == Camd35Constants.CMD_CW_CASCADING) {
        len = Camd35Constants.CW_SIZE;
      } else if (cmd == Camd35Constants.CMD_KEEPALIVE) {
        len = acc[base + 1] & 0xFF;
        if (len > 32) return -1;
      } else if (cmd == Camd35Constants.CMD_EMM || cmd == Camd35Constants.CMD_EMM_19
          || cmd == Camd35Constants.CMD_AU || cmd == Camd35Constants.CMD_NOK_NOTFOUND
          || cmd == Camd35Constants.CMD_STOPPED) {
        len = acc[base + 1] & 0xFF;
      } else if (cmd == Camd35Constants.CMD_CACHEEX_FILTER) {
        // Push-filter 0x3C : 242 octets FIXES, longueur 8 bits [1]
        // (module-camd35.c l.328-334 : 0x3C hors 16 bits — « buflen = buf[1] »)
        len = acc[base + 1] & 0xFF;
        if (len != Camd35CacheExCodec.FILTER_BODY_SIZE) return -1;
      } else if (cmd == Camd35Constants.CMD_CACHEEX_ID_REQ
          || cmd == Camd35Constants.CMD_CACHEEX_ID_ANSWER
          || cmd == Camd35Constants.CMD_CACHEEX_PUSH) {
        len = (acc[base + 1] & 0xFF) | ((acc[base + 2] & 0xFF) << 8); // l.295-301
        if (len > Camd35Constants.MAX_ECM_SIZE) return -1;
      } else {
        // Commande inconnue / payload corrompu
        return -1;
      }
      return len;
    }

    /**
     * Déchiffre (in place) les blocs de 16 du payload chiffré de {@code decryptedThrough}
     * à {@code through} (borné aux octets effectivement reçus) — jamais deux fois le
     * même bloc : on ne déchiffre que l'intervalle [decryptedThrough, limit).
     */
    private void decryptThrough(int through) {
      int limit = Math.min(through, accLen - Camd35Constants.UCRC_SIZE);
      limit &= ~15; // blocs complets uniquement
      if (limit <= decryptedThrough) return;
      int len = limit - decryptedThrough;
      byte[] view = Arrays.copyOfRange(acc,
          Camd35Constants.UCRC_SIZE + decryptedThrough,
          Camd35Constants.UCRC_SIZE + limit);
      Camd35Crypto.aesEcbInPlace(view, aesKey, false);
      System.arraycopy(view, 0, acc, Camd35Constants.UCRC_SIZE + decryptedThrough, len);
      decryptedThrough = limit;
    }
  }

  // ============================================================
  // Utilitaires
  // ============================================================

  /**
   * Extrait l'ucrc CLAIR (4 octets) du début d'un buffer wire — pour l'authentification
   * de la PREMIÈRE frame avant de connaître la clé (l.142 : camd35_auth_client(buf)).
   */
  public static byte[] extractUcrc(byte[] wire) {
    if (wire == null || wire.length < Camd35Constants.UCRC_SIZE) {
      throw new IllegalArgumentException("need at least " + Camd35Constants.UCRC_SIZE + " bytes");
    }
    return Arrays.copyOf(wire, Camd35Constants.UCRC_SIZE);
  }

  private static void writeU16(byte[] buf, int off, int v) {
    buf[off] = (byte) (v >>> 8);
    buf[off + 1] = (byte) v;
  }

  private static void writeU32(byte[] buf, int off, int v) {
    buf[off] = (byte) (v >>> 24);
    buf[off + 1] = (byte) (v >>> 16);
    buf[off + 2] = (byte) (v >>> 8);
    buf[off + 3] = (byte) v;
  }

  private static int readU16(byte[] buf, int off) {
    return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
  }

  private static int readU32(byte[] buf, int off) {
    return ((buf[off] & 0xFF) << 24) | ((buf[off + 1] & 0xFF) << 16)
        | ((buf[off + 2] & 0xFF) << 8) | (buf[off + 3] & 0xFF);
  }
}
