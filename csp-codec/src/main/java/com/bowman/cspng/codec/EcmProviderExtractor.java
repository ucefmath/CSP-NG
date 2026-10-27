package com.bowman.cspng.codec;

/**
 * Extracteur pur et robuste de provider ident à partir du corps d'un ECM binaire.
 *
 * <p>Permet au handler CSPv2 (qui ne transporte pas le provider sur le wire)
 * de dériver le provider selon la famille de CAID (DVB CAS) :
 * <ul>
 *   <li><b>Viaccess (0x0500..0x05FF)</b> : scan TLV des nanos {@code 0x90} (ident 24-bit)
 *       et {@code 0xD2} (ident 16/24-bit), avec fallback heuristique.</li>
 *   <li><b>Seca / Mediaguard (0x0100..0x01FF)</b> : ident 16-bit d'en-tête payload.</li>
 *   <li><b>Cryptoworks (0x0D00..0x0DFF)</b> : nano {@code 0x83} ou {@code 0xDF}.</li>
 *   <li><b>Autres familles (NAGRA, NDS, Conax, Irdeto)</b> ou indéterminable : {@code 0}.</li>
 * </ul>
 */
public final class EcmProviderExtractor {

  private EcmProviderExtractor() {}

  /**
   * Extrait le provider ident pour un CAID et un corps d'ECM donnés.
   *
   * @param caid identifiant CAID (ex: 0x0500, 0x0100, 0x1810)
   * @param ecm  octets de l'ECM (section DVB complète ou corps amputé)
   * @return provider ident extrait (24-bit ou 16-bit), ou 0 si indéterminable / non applicable
   */
  /**
   * Contexte provider pour la policy. Distinct de la valeur catalogue.
   * {@link Status#NOT_IN_FAMILY} : la famille n'encode pas de provider ; {@code 0} est la valeur réelle.
   * {@link Status#UNKNOWN} : la famille encode un provider mais l'ECM ne permet pas de le lire.
   */
  public record ProviderContext(Status status, int value) {
    public enum Status { KNOWN, NOT_IN_FAMILY, UNKNOWN }

    public int policyProvider() {
      return status == Status.UNKNOWN ? com.bowman.cspng.domain.dcw.DcwRuleScope.UNKNOWN_PROVIDER : value;
    }

    public int routingProvider() {
      return status == Status.UNKNOWN ? 0 : value;
    }
  }

  public static ProviderContext extractContext(int caid, byte[] ecm) {
    int family = caid & 0xFF00;
    boolean encodes = family == 0x0500 || family == 0x0100 || family == 0x0D00;
    if (!encodes) return new ProviderContext(ProviderContext.Status.NOT_IN_FAMILY, 0);
    if (ecm == null || ecm.length == 0) return new ProviderContext(ProviderContext.Status.UNKNOWN, 0);
    int value = extractProvider(caid, ecm);
    if (value == 0) return new ProviderContext(ProviderContext.Status.UNKNOWN, 0);
    return new ProviderContext(ProviderContext.Status.KNOWN, value);
  }

  public static int extractProvider(int caid, byte[] ecm) {
    if (ecm == null || ecm.length == 0) {
      return 0;
    }
    int family = caid & 0xFF00;
    if (family == 0x0500) {
      return extractViaccessProvider(ecm);
    } else if (family == 0x0100) {
      return extractSecaProvider(ecm);
    } else if (family == 0x0D00) {
      return extractCryptoworksProvider(ecm);
    }
    return 0;
  }

  /**
   * Extraction Viaccess (0x0500) : recherche du nano 0x90 ou 0xD2.
   */
  public static int extractViaccessProvider(byte[] ecm) {
    if (ecm == null || ecm.length < 4) {
      return 0;
    }
    int start = 0;
    if ((ecm[0] == (byte) 0x80 || ecm[0] == (byte) 0x81) && ecm.length >= 6) {
      start = 3;
    }

    // 1. Parcours TLV standard
    int offset = start;
    while (offset + 1 < ecm.length) {
      int tag = ecm[offset] & 0xFF;
      int len = ecm[offset + 1] & 0xFF;
      if (offset + 2 + len > ecm.length) {
        break;
      }
      if (tag == 0x90 && len >= 3) {
        return ((ecm[offset + 2] & 0xFF) << 16)
            | ((ecm[offset + 3] & 0xFF) << 8)
            | (ecm[offset + 4] & 0xFF);
      }
      if (tag == 0xD2 && len >= 2) {
        if (len == 2) {
          return ((ecm[offset + 2] & 0xFF) << 8) | (ecm[offset + 3] & 0xFF);
        } else {
          return ((ecm[offset + 2] & 0xFF) << 16)
              | ((ecm[offset + 3] & 0xFF) << 8)
              | (ecm[offset + 4] & 0xFF);
        }
      }
      offset += 2 + len;
    }

    // 2. Scan heuristique si structure TLV décalée ou incomplète
    for (int i = start; i + 4 < ecm.length; i++) {
      if ((ecm[i] & 0xFF) == 0x90 && ((ecm[i + 1] & 0xFF) == 0x03 || (ecm[i + 1] & 0xFF) == 0x04)) {
        return ((ecm[i + 2] & 0xFF) << 16)
            | ((ecm[i + 3] & 0xFF) << 8)
            | (ecm[i + 4] & 0xFF);
      }
    }
    return 0;
  }

  /**
   * Extraction Seca / Mediaguard (0x0100) : ident 16-bit d'en-tête payload.
   */
  public static int extractSecaProvider(byte[] ecm) {
    if (ecm == null || ecm.length < 2) {
      return 0;
    }
    int off = 0;
    if ((ecm[0] == (byte) 0x80 || ecm[0] == (byte) 0x81) && ecm.length >= 5) {
      off = 3;
    }
    if (off + 2 <= ecm.length) {
      return ((ecm[off] & 0xFF) << 8) | (ecm[off + 1] & 0xFF);
    }
    return 0;
  }

  /**
   * Extraction Cryptoworks (0x0D00) : nano 0x83 ou 0xDF.
   */
  public static int extractCryptoworksProvider(byte[] ecm) {
    if (ecm == null || ecm.length < 3) {
      return 0;
    }
    int off = ((ecm[0] == (byte) 0x80 || ecm[0] == (byte) 0x81) && ecm.length >= 5) ? 3 : 0;
    while (off + 1 < ecm.length) {
      int tag = ecm[off] & 0xFF;
      int len = ecm[off + 1] & 0xFF;
      if (off + 2 + len > ecm.length) {
        break;
      }
      if (tag == 0x83 && len >= 1) {
        if (len == 1) return ecm[off + 2] & 0xFF;
        return ((ecm[off + 2] & 0xFF) << 8) | (ecm[off + 3] & 0xFF);
      }
      if (tag == 0xDF && len >= 1) {
        return ecm[off + 2] & 0xFF;
      }
      off += 2 + len;
    }
    return 0;
  }
}
