package com.bowman.cspng.filters;

import com.bowman.cspng.domain.CwReply;

import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Chaîne de filtres de réponse ordonnée.
 *
 * <p>Applique les {@link CwFilter} dans l'ordre défini par {@link CwFilter#order()}.
 * Chaque décision est évaluée séquentiellement :
 * <ul>
 *   <li>{@code ACCEPT} – la réponse inchangée passe au filtre suivant</li>
 *   <li>{@code REWRITE} – la réponse modifiée passe au filtre suivant</li>
 *   <li>{@code DROP} / {@code HOLD} / {@code RELEASE_PREVIOUS} – arrêt immédiat</li>
 * </ul>
 *
 * <p>API entièrement synchrone : aucune allocation de {@code Future} inutile.
 * L'exception d'un filtre est traitée en fail-closed (→ {@code DROP}) sans
 * impacter les autres réponses.
 *
 * <h3>Protection anti-CW-vide</h3>
 * Une décision {@code ACCEPT}, {@code REWRITE} ou {@code RELEASE_PREVIOUS}
 * portant une réponse vide est traîtée comme {@code DROP} (fail-closed)
 * car elle provoquerait un {@code handleReply(empty)} côté cache.
 * Le chemin historique sans chaîne n'est pas affecté.
 *
 * <h3>Bypass anti-boucle</h3>
 * Le manager complète directement la Future avec la réponse d'un
 * {@code RELEASE_PREVIOUS} (ou via le canal asynchrone {@code ReplyRelease}),
 * sans repasser par cette chaîne.
 */
public class FilterChain {

  private static final Logger LOG = Logger.getLogger(FilterChain.class.getName());

  private final List<CwFilter> filters;
  private final boolean fanOut;

  public FilterChain(List<CwFilter> filters) {
    this.filters = filters == null || filters.isEmpty()
        ? List.of()
        : List.copyOf(filters.stream()
              .sorted(Comparator.comparingInt(CwFilter::order))
              .toList());
    this.fanOut = this.filters.stream().anyMatch(CwFilter::requiresMultipleReplies);
  }

  /** @return true si au moins un filtre exige le fan-out multi-connecteurs */
  public boolean requiresMultipleReplies() {
    return fanOut;
  }

  /** Liste immuable des filtres (triés par ordre). */
  public List<CwFilter> filters() {
    return filters;
  }

  /**
   * Évalue une réponse à travers la chaîne.
   *
   * @param context contexte d'origine (requête, connecteur, profil, horodatage, canal release)
   * @param reply   réponse brute du connecteur
   * @return la décision finale de la chaîne (jamais null) ;
   *         retourne {@code DROP} si un filtre accepte/rewrite/release une réponse CW-vide
   */
  public FilterDecision evaluate(FilterContext context, CwReply reply) {
    CwReply current = reply;
    for (CwFilter filter : filters) {
      try {
        FilterDecision decision = filter.evaluateReply(context, current);

        // ensure accepting/rewriting/releasing non-empty CW
        if (decision instanceof FilterDecision.Accept(CwReply r) && r.isEmpty()) {
          LOG.warning(() -> "Filter " + filter.name() + " ACCEPTed empty CW from "
              + context.connectorName() + " — treating as DROP");
          return FilterDecision.Drop.INSTANCE;
        }
        if (decision instanceof FilterDecision.Rewrite(CwReply r) && r.isEmpty()) {
          LOG.warning(() -> "Filter " + filter.name() + " REWRITE with empty CW from "
              + context.connectorName() + " — treating as DROP");
          return FilterDecision.Drop.INSTANCE;
        }
        if (decision instanceof FilterDecision.ReleasePrevious(CwReply r, String ref)
            && r.isEmpty()) {
          LOG.warning(() -> "Filter " + filter.name() + " RELEASE_PREVIOUS with empty CW from "
              + context.connectorName() + " — treating as DROP");
          return FilterDecision.Drop.INSTANCE;
        }

        switch (decision) {
          case FilterDecision.Accept(CwReply accepted) -> current = accepted;
          case FilterDecision.Rewrite(CwReply rewritten) -> current = rewritten;
          case FilterDecision.Drop d -> { return d; }
          case FilterDecision.Hold h -> { return h; }
          case FilterDecision.ReleasePrevious rp -> { return rp; }
        }
      } catch (Exception e) {
        LOG.warning(() -> "Filter " + filter.name() + " threw for connector "
            + context.connectorName() + ": " + e);
        return FilterDecision.Drop.INSTANCE;
      }
    }
    // final Accept after all filters: guard against empty
    if (current.isEmpty()) {
      LOG.warning("Chain produced empty CW — treating as DROP");
      return FilterDecision.Drop.INSTANCE;
    }
    return new FilterDecision.Accept(current);
  }
}
