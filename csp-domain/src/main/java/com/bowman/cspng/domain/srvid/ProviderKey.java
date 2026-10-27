package com.bowman.cspng.domain.srvid;

/**
 * Clé d'indexation d'un provider CAS (CAID 16-bit + PROVID 24-bit).
 */
public record ProviderKey(int caid, int provid) {

  public ProviderKey {
    caid = caid & 0xFFFF;
    provid = provid & 0xFFFFFF;
  }

  public static ProviderKey of(int caid, int provid) {
    return new ProviderKey(caid, provid);
  }
}
