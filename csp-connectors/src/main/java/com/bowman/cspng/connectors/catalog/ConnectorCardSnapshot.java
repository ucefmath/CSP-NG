package com.bowman.cspng.connectors.catalog;

import java.util.List;
import java.util.Objects;

/**
 * Snapshot immuable des cartes d'un connecteur pour une génération runtime donnée (P6).
 * <p>
 * RUNTIME ONLY : l'état online et la génération ne sont jamais persistés en base ;
 * toute reconnexion ou redémarrage incrémente la génération et invalide l'ancienne snapshot.
 */
public record ConnectorCardSnapshot(
    String connectorName,
    long generation,
    boolean online,
    List<DiscoveredCard> cards
) {
  public record DiscoveredCard(
      String profile,
      int caid,
      int provider,
      long originCardId,
      int hop,
      int reshare,
      CardSource.Trust trust
  ) {}

  public ConnectorCardSnapshot {
    Objects.requireNonNull(connectorName, "connectorName");
    cards = cards != null ? List.copyOf(cards) : List.of();
  }

  public static ConnectorCardSnapshot offline(String connectorName, long generation) {
    return new ConnectorCardSnapshot(connectorName, generation, false, List.of());
  }
}
