package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Garde les candidats CW contestés par clé de cache, avec la limite de 3 du legacy.
 *
 * <p>Le legacy stocke les candidats directement dans {@code CamdNetMessage.candidates}
 * (champ transient, limité à 3). Comme {@link CwReply} est un record immuable sans ce champ,
 * ce registre externe reproduit la sémantique exacte de
 * {@code CamdNetMessage.addCandidate(CamdNetMessage)} l.833-839.
 *
 * <p>Règles portées à l'identique :
 * <ul>
 *   <li>la nouvelle reply est retirée de l'ensemble avant ajout de l'ancienne ;</li>
 *   <li>après retrait, si la taille est &gt; 3, une {@link IllegalStateException} est levée ;</li>
 *   <li>{@code addCandidate} retourne {@code true} si l'ancienne CW a réellement été ajoutée.</li>
 * </ul>
 */
public class ContestedRegistry {

  private final Map<EcmKey, Set<CwReply>> candidates = new LinkedHashMap<>();

  /**
   * Enregistre une CW contestée pour une clé.
   *
   * @param key          clé ECM concernée
   * @param currentReply nouvelle reply (retirée de l'ensemble)
   * @param oldReply     ancienne reply (ajoutée comme candidat)
   * @return {@code true} si l'ancienne reply a été ajoutée comme nouveau candidat
   * @throws IllegalStateException si la limite de 3 candidats est dépassée
   */
  public boolean addCandidate(EcmKey key, CwReply currentReply, CwReply oldReply) {
    if (currentReply == null || oldReply == null) return false;
    Set<CwReply> set = candidates.computeIfAbsent(key, k -> new LinkedHashSet<>());
    set.remove(currentReply);
    if (set.size() > 3) {
      throw new IllegalStateException("Too many cw candidates (" + set.size() + ")");
    }
    return set.add(oldReply);
  }

  public Set<CwReply> getCandidates(EcmKey key) {
    return candidates.getOrDefault(key, java.util.Collections.emptySet());
  }

  public void clear(EcmKey key) {
    candidates.remove(key);
  }
}
