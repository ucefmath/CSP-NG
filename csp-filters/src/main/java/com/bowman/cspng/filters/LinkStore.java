package com.bowman.cspng.filters;

import io.vertx.core.Future;

import java.util.Set;

/**
 * SPI de persistance des groupes de liens DCW.
 *
 * <p>Aucune Java Serialization. Les implémentations doivent être asynchrones
 * (pas de blocage de l'event-loop). La persistance JSON versionnée remplace
 * le format {@code links.dat} legacy.
 *
 * <h3>Format</h3>
 * Chaque groupe est un ensemble de refs de service au format
 * {@code sidHex:profile} (ex. {@code "1a:dvbt"}).
 * Un groupe valide contient au moins 2 membres.
 *
 * <h3>Sécurité</h3>
 * Aucun CW, digest, ou secret ne transite par ce SPI.
 */
public interface LinkStore {

  /**
   * Charge les groupes de liens persistés.
   *
   * @return un Future complété avec l'ensemble des groupes.
   *         En cas d'absence de fichier : ensemble vide.
   *         En cas de corruption détectée : ensemble vide (fail-safe)
   *         après mise en quarantaine du fichier corrompu.
   */
  Future<Set<Set<String>>> load();

  /**
   * Persiste les groupes de liens de façon atomique.
   *
   * <p>L'implémentation doit garantir qu'un crash pendant l'écriture
   * ne corrompt pas le fichier existant (écriture dans un fichier
   * temporaire puis renommage atomique).
   *
   * @param groups groupes à persister ; un ensemble vide est valide
   *               et produit un fichier JSON valide
   * @return un Future complété une fois la persistance terminée
   */
  Future<Void> save(Set<Set<String>> groups);
}
