package com.bowman.cspng.connectors.cacheex;

import java.util.List;

/**
 * Pont CacheEx vers le vrai cache d'un noeud SANS CacheVerticle (worker, C1).
 *
 * <p>Sur le central, les push reçus sont injectés via
 * {@code CacheVerticle.handleReply} et l'émission est fan-out via le SPI
 * {@code CacheTransport}. Sur le worker (pas de CacheVerticle — pas de
 * « double cache fantôme »), les sessions/connecteurs appellent ce pont :
 * <ul>
 *   <li>réception : le pont injecte la CW dans le vrai cache servi
 *       (lookup par hash du routeur) et évalue le forwarding vers les
 *       paires d'émission avec le chemin complet ;</li>
 *   <li>le pont ne re-contrôle JAMAIS la CW (check unique côté réception,
 *       principe C5) et ne reflète jamais vers la source.</li>
 * </ul>
 */
public interface CacheExBridge {

  /**
   * Injecte une CW reçue par cacheex (déjà validée par l'appelant : rc,
   * anti-boucle, hop, checksum, filtre, CW-cycle) dans le vrai cache.
   *
   * @param profile      profil de la session / du connecteur
   * @param listenerId   listener d'origine (sessions) ou -1 (connecteurs)
   * @param caid         CAID du push
   * @param prid         provider du push
   * @param srvid        service du push
   * @param oddEven      0x80 / 0x81 normalisé
   * @param cw           CW 16 octets (copiée par l'implémentation)
   * @param dataHash     hash ECM du push
   * @param cycleTimeSec cycletime transporté (0 = absent)
   * @param nextCwCycle  flag next-cycle transporté
   * @param nodePath     chemin de nodes reçu (copié par l'implémentation)
   * @param originAddr   adresse du pair source (anti-reflet)
   * @param sourceName   nom du connecteur / username (provenance, jamais loggé en valeur)
   * @return true si acceptée dans un vrai cache (l'appelant incrémente receivedCw)
   */
  boolean feedReceived(String profile, long listenerId,
                       int caid, int prid, int srvid, int oddEven,
                       byte[] cw, int dataHash,
                       int cycleTimeSec, boolean nextCwCycle,
                       List<byte[]> nodePath,
                       String originAddr, String sourceName);

  /**
   * Enregistre une cible d'émission (connecteur mode 3 / session mode 2) :
   * ses {@code onReply} seront évalués sur chaque CW fraîche (dispatch local
   * + forwarding), avec sa propre gate (filtre/hop/file/dédup/anti-reflet).
   */
  void registerEmitter(String peerId, CacheExEmitter emitter);

  /** Désenregistre une cible d'émission (arrêt connecteur / fermeture session). */
  void unregisterEmitter(String peerId);
}
