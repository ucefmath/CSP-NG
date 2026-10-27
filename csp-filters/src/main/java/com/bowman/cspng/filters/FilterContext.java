package com.bowman.cspng.filters;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;

import java.util.Objects;

/**
 * Contexte immuable décrivant l'origine d'une réponse évaluée par un filtre.
 *
 * <p>Ne dépend pas de {@code csp-connectors} ni de Vert.x : le connecteur source
 * est identifié par son nom stable ({@link #connectorName()}) et le scheduling
 * est abstrait via {@link FilterScheduler}.
 *
 * <h3>Canal de release asynchrone</h3>
 * Le champ {@link #releaseChannel()} permet à un filtre de libérer ultérieurement
 * une réponse retenue (après un {@code HOLD}), sans repasser par la chaîne de filtres
 * (bypass anti-boucle). La réponse déjà retenue est complétée directement via ce
 * canal ; aucun reroutage réseau n'est effectué par le manager.
 *
 * <h3>Scheduler pour TTL métiers</h3>
 * Le champ {@link #scheduler()} offre une abstraction de planification temporelle
 * sans dépendance Vert.x. Un filtre peut l'utiliser pour ses propres timers
 * (ex. TTL forceContinuity 12s, fenêtre verifyReplies 20s, invalidation detectLinks 2s).
 * L'implémentation du manager est basée sur {@code vertx.setTimer}, mais le filtre
 * n'a pas besoin de connaître cet ancrage.
 *
 * <h3>Référence opaque de connecteur</h3>
 * {@link #connectorName()} est une référence d'origine opaque portée pour le
 * filtrage et l'observabilité. Aucune sémantique de routage réseau n'y est attachée
 * (la réponse est déjà retenue en mémoire par le filtre ou par le manager).
 *
 * <h3>Canaux UNSUPPORTED</h3>
 * Les constantes {@link ReplyRelease#UNSUPPORTED} et {@link FilterScheduler#UNSUPPORTED}
 * sont fail-fast : toute invocation lève {@code UnsupportedOperationException}.
 * Le constructeur court (4 args) les utilise par défaut pour les tests sans manager.
 * En production, le manager fournit toujours des canaux réels.
 */
public record FilterContext(
    EcmRequest request,
    String connectorName,
    String profile,
    long timestamp,
    ReplyRelease releaseChannel,
    FilterScheduler scheduler) {

  public FilterContext {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(connectorName, "connectorName");
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(releaseChannel, "releaseChannel");
    Objects.requireNonNull(scheduler, "scheduler");
  }

  /**
   * Constructeur pratique pour tests unitaires.
   * Les canaux {@code releaseChannel} et {@code scheduler} sont
   * {@link ReplyRelease#UNSUPPORTED} et {@link FilterScheduler#UNSUPPORTED}
   * — toute tentative d'utilisation lève une exception.
   */
  public FilterContext(EcmRequest request, String connectorName, String profile, long timestamp) {
    this(request, connectorName, profile, timestamp,
        ReplyRelease.UNSUPPORTED, FilterScheduler.UNSUPPORTED);
  }

  // --- interfaces imbriquées ---

  /**
   * Canal de release asynchrone appelé par un filtre après un {@code HOLD}.
   *
   * <p>L'implémentation du manager garantit : validation de la réponse,
   * CAS first-wins, annulation du timeout, complétion de la Promise
   * (sans repasser par la chaîne), ignore des releases tardifs.
   *
   * <p>Le {@link #UNSUPPORTED} lève {@code UnsupportedOperationException}
   * si un filtre tente d'appeler {@code release()} sans canal réel
   * (par ex. depuis un test sans manager).
   */
  @FunctionalInterface
  public interface ReplyRelease {

    /** Canal fail-fast : toute invocation lève une exception. */
    ReplyRelease UNSUPPORTED = (reply, connectorRef) -> {
      throw new UnsupportedOperationException(
          "ReplyRelease channel not configured — use manager-provided context");
    };

    void release(CwReply reply, String connectorRef);
  }

  /**
   * Abstraction de planification temporelle sans dépendance Vert.x.
   *
   * <p>Un filtre peut planifier un callback différé (ex. pour implémenter
   * un TTL de rétention) et l'annuler si nécessaire.
   *
   * <p>Les délais métier supportés :
   * <ul>
   *   <li>forceContinuity : TTL 12s de conservation des octets bruts</li>
   *   <li>verifyReplies : fenêtre de comparaison 20s</li>
   *   <li>detectLinks : invalidation 2s, retrait 20s</li>
   * </ul>
   *
   * <p>Implémentation par le manager via {@code vertx.setTimer}, bornée à 60s.
   * Le {@link #UNSUPPORTED} lève une exception si appelé sans scheduler réel.
   */
  @FunctionalInterface
  public interface FilterScheduler {

    /** Scheduler fail-fast : toute invocation lève une exception. */
    FilterScheduler UNSUPPORTED = (delayMs, task) -> {
      throw new UnsupportedOperationException(
          "FilterScheduler not configured — use manager-provided context");
    };

    /**
     * Planifie l'exécution d'un callback après un délai.
     *
     * @param delayMs délai en millisecondes (doit être &gt; 0, borné à 60s par le manager)
     * @param task    callback à exécuter (sur l'event-loop Vert.x en production)
     * @return un handle annulable
     */
    ScheduledTask schedule(long delayMs, Runnable task);

    /** Handle de tâche planifiée, permettant l'annulation. */
    interface ScheduledTask {
      void cancel();
    }
  }
}
