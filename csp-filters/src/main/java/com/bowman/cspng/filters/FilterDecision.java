package com.bowman.cspng.filters;

import com.bowman.cspng.domain.CwReply;

import java.util.Objects;

/**
 * Décision explicite d'un {@link CwFilter} sur une réponse CW.
 *
 * <p>Hiérarchie scellée : il est impossible de construire un état invalide
 * (ex. {@code Rewrite} sans CW valide, ou {@code ReleasePrevious} sans
 * référent de connecteur). Chaque sous-type valide ses invariants dans
 * son constructeur canonique.
 *
 * <h3>Sémantique</h3>
 * <ul>
 *   <li>{@link Accept} – transmettre la réponse au client/cache</li>
 *   <li>{@link Drop} – ignorer silencieusement ; pas de purge de pending</li>
 *   <li>{@link Hold} – retenir temporairement ; ne pas notifier</li>
 *   <li>{@link Rewrite} – remplacer le CW (ex. continuité forcée)</li>
 *   <li>{@link ReleasePrevious} – libérer une réponse précédemment retenue,
 *       avec sa référence de connecteur source, en by-passant la chaîne
 *       de filtres à la réinjection</li>
 * </ul>
 */
public sealed interface FilterDecision {

  /** Transmettre la réponse telle quelle. */
  record Accept(CwReply reply) implements FilterDecision {
    public Accept {
      Objects.requireNonNull(reply, "reply must not be null");
    }
  }

  /** Ignorer silencieusement cette réponse. */
  record Drop() implements FilterDecision {
    public static final Drop INSTANCE = new Drop();
  }

  /** Retenir temporairement la réponse ; ne pas notifier. */
  record Hold() implements FilterDecision {
    public static final Hold INSTANCE = new Hold();
  }

  /** Remplacer le CW par une version modifiée. */
  record Rewrite(CwReply reply) implements FilterDecision {
    public Rewrite {
      Objects.requireNonNull(reply, "reply must not be null");
      if (reply.isEmpty()) {
        throw new IllegalArgumentException("rewrite reply must not be empty");
      }
    }
  }

  /**
   * Libérer une réponse précédemment retenue.
   *
   * <p>Bypasse la chaîne de filtres à la réinjection pour éviter une boucle.
   * La réponse est déjà retenue en mémoire par le filtre ; le manager la
   * complète directement sans reroutage réseau.
   *
   * @param reply        la réponse à publier (non-null, CW non vide)
   * @param connectorRef référence opaque d'origine du connecteur source,
   *                     portée pour le filtrage et l'observabilité
   */
  record ReleasePrevious(CwReply reply, String connectorRef) implements FilterDecision {
    public ReleasePrevious {
      Objects.requireNonNull(reply, "reply must not be null");
      Objects.requireNonNull(connectorRef, "connectorRef must not be null");
      if (connectorRef.isBlank()) {
        throw new IllegalArgumentException("connectorRef must not be blank");
      }
      if (reply.isEmpty()) {
        throw new IllegalArgumentException("release reply must not be empty");
      }
    }
  }

  /** Vrai si cette décision est terminale pour l'agrégation d'un connecteur. */
  default boolean isTerminating() {
    return this instanceof Accept || this instanceof Rewrite || this instanceof ReleasePrevious;
  }
}
