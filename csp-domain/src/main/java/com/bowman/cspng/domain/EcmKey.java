package com.bowman.cspng.domain;

import java.util.Objects;

/**
 * Clé de cache ECM/DCW.
 * <p>
 * Reproduit le comportement legacy : l'identité est <strong>uniquement</strong>
 * le {@code dataHash} (équivalent à {@code String(customData, "ISO-8859-1").hashCode()}),
 * pas le couple sid/caid. Deux ECM différents avec collision de hash sont considérés
 * comme identiques par design.
 *
 * @param commandTag tag de commande DVB/Newcamd (0x80/0x81 typiquement)
 * @param serviceId  service id (16 bits)
 * @param networkId  original network id (16 bits)
 * @param caId       CA id (16 bits)
 * @param dataHash   hash 32 bits signé de l'ECM amputé de ses 3 premiers octets
 */
public record EcmKey(int commandTag, int serviceId, int networkId, int caId, int dataHash) {

  @Override
  public int hashCode() {
    return dataHash;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof EcmKey other)) return false;
    return this.dataHash == other.dataHash;
  }

  @Override
  public String toString() {
    return String.format(
        "EcmKey[tag=0x%02X,sid=0x%04X,onid=0x%04X,caid=0x%04X,hash=%s]",
        commandTag, serviceId, networkId, caId, Integer.toHexString(dataHash));
  }
}
