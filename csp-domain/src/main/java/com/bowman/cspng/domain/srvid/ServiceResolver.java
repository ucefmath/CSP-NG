package com.bowman.cspng.domain.srvid;

/**
 * Service de résolution O(1) immuable pour les noms de chaînes et de providers.
 */
public interface ServiceResolver {

  /** Résolution du nom de chaîne depuis le CAID et le SID (ou null). */
  String resolveChannelName(int caid, int sid);

  /** Résolution complète du service depuis le CAID et le SID (ou null). */
  ServiceInfo resolveService(int caid, int sid);

  /**
   * Résolution du nom de provider depuis le CAID et le PROVID.
   * Tente d'abord une correspondance exacte (caid, provid), puis un fallback (caid, 0x000000).
   */
  String resolveProviderName(int caid, int provid);

  /**
   * Résolution complète du provider depuis le CAID et le PROVID.
   * Tente d'abord une correspondance exacte (caid, provid), puis un fallback (caid, 0x000000).
   */
  ProviderInfo resolveProvider(int caid, int provid);

  /** Nombre de services chargés. */
  int serviceCount();

  /** Nombre de providers chargés. */
  int providerCount();

  /** Instance vide par défaut. */
  ServiceResolver EMPTY = new DefaultServiceResolver(java.util.Map.of(), java.util.Map.of());
}
