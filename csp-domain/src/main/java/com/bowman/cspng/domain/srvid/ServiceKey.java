package com.bowman.cspng.domain.srvid;

/**
 * Clé d'indexation d'un service TV (CAID 16-bit + SID 16-bit).
 */
public record ServiceKey(int caid, int sid) {

  public ServiceKey {
    caid = caid & 0xFFFF;
    sid = sid & 0xFFFF;
  }

  public static ServiceKey of(int caid, int sid) {
    return new ServiceKey(caid, sid);
  }
}
