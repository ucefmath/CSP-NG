package com.bowman.cspng.config;

/**
 * Affectation explicite listener → worker (Gemini FIX : aucun listener servi
 * sans affectation). Desactive par defaut cote lien ; l'affectation seule ne
 * suffit pas, le lien listener×cache et le listener enabled restent requis.
 */
public record RelayNodeListenerAssignment(
    String nodeName,
    long listenerId,
    boolean enabled,
    long version
) {
  public RelayNodeListenerAssignment {
    if (nodeName == null || !nodeName.matches("^[a-z0-9][a-z0-9._-]{0,62}$"))
      throw new IllegalArgumentException("nodeName invalid");
    if (listenerId < 1) throw new IllegalArgumentException("listenerId must be >= 1");
    if (version < 1) throw new IllegalArgumentException("version must be >= 1");
  }
}
