package com.bowman.cspng.connectors.catalog;

import com.bowman.cspng.codec.cccam.CcCardCodec;
import io.vertx.core.Vertx;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Service de découverte runtime et d'annonces de cartes (P6/P8).
 *
 * <p>Contrats P6 / P8 (docs/INBOUND-CAMS-PLAN.md §P6/§P8) :
 * <ul>
 *   <li><b>Identité de carte globale & persistante</b> : l'ID exposé aux clients est TOUJOURS
 *       le {@code cardId} persistant (uint32 alloué par {@link CardCatalogRepository}),
 *       JAMAIS l'{@code originCardId} amont (masquage total des IDs et topologies amont).</li>
 *   <li><b>Autorisation vs Provenance</b> :
 *       <ul>
 *         <li>Mode {@code manual} (défaut) : publication explicite requise
 *             ({@code capability.state == PUBLISHED}), quelle que soit la provenance.</li>
 *         <li>Mode {@code wildcard} : toutes les capacités non bloquées
 *             ({@code capability.state != BLOCKED}) ayant une source en ligne.</li>
 *       </ul>
 *   </li>
 *   <li><b>Règles de reshare strictes</b> :
 *       <ul>
 *         <li>CCcam amont reshare = 0 : STRICTEMENT JAMAIS exposé ni annoncé.</li>
 *         <li>CCcam amont reshare &gt; 0 : {@code out = min(amont - 1, connectorCap, listenerCap)},
 *             {@code hop = amont + 1}.</li>
 *         <li>Newcamd reshare UNKNOWN : interdit si {@code grant == null} ; si {@code grant == 0},
 *             client-only (0) ; si {@code grant > 0}, {@code min(grant, connectorCap, listenerCap)}.</li>
 *         <li>Caps bornés dans 0..10.</li>
 *       </ul>
 *   </li>
 *   <li><b>Protection de génération</b> : un callback de déconnexion d'une ancienne génération
 *       n'invalide pas une génération plus récente ré-établie.</li>
 *   <li><b>Multi-sources n:n</b> : la perte d'une source ne retire pas la capacité si une autre
 *       source éligible reste en ligne.</li>
 *   <li><b>Filtrage par abonné / session</b> : les annonces évaluent les ACL propres de chaque
 *       utilisateur (profils, connecteurs autorisés).</li>
 * </ul>
 */
public class CardDiscoveryService {

  private static final Logger LOG = Logger.getLogger(CardDiscoveryService.class.getName());

  private final Vertx vertx;
  private final CardCatalogRepository catalogRepository;
  private final AtomicLong mockSequence = new AtomicLong(1);

  public record CapabilityKey(String profile, int caid, int provider) {}

  public record SourceRuntimeState(
      String connectorName,
      long originCardId,
      long generation,
      int hop,
      int upstreamReshare,
      int effectiveReshare,
      CardSource.Trust trust
  ) {}

  public static class CapabilityRuntimeState {
    public final long persistentCardId;
    public final String profile;
    public final int caid;
    public final int provider;
    public volatile CardCapability.State state;
    public final Map<String, SourceRuntimeState> sources = new ConcurrentHashMap<>();

    public CapabilityRuntimeState(long persistentCardId, String profile, int caid, int provider, CardCapability.State state) {
      this.persistentCardId = persistentCardId;
      this.profile = profile;
      this.caid = caid;
      this.provider = provider;
      this.state = state;
    }
  }

  public record UserAclContext(Set<String> profiles, Set<String> allowedConnectors) {
    public static UserAclContext unconstrained() {
      return new UserAclContext(Set.of(), Set.of());
    }
  }

  public interface CardAnnouncementListener {
    void onCapabilityAvailable(CapabilityRuntimeState cap);
    void onCapabilityWithdrawn(long cardId);
    UserAclContext getAclContext();
    String getCardMode();
    int getListenerCap();
    byte[] getServerNodeId();
  }

  private final Map<CapabilityKey, CapabilityRuntimeState> capabilities = new ConcurrentHashMap<>();
  private final Map<CapabilityKey, Long> persistentCardIdCache = new ConcurrentHashMap<>();
  private final Map<String, Long> activeGenerations = new ConcurrentHashMap<>();
  private final List<CardAnnouncementListener> listeners = new CopyOnWriteArrayList<>();

  public CardDiscoveryService(Vertx vertx, CardCatalogRepository catalogRepository) {
    this.vertx = vertx;
    this.catalogRepository = catalogRepository;
  }

  public CardDiscoveryService(Vertx vertx) {
    this(vertx, null);
  }

  public void addListener(CardAnnouncementListener listener) {
    if (listener != null) listeners.add(listener);
  }

  public void removeListener(CardAnnouncementListener listener) {
    if (listener != null) listeners.remove(listener);
  }

  public synchronized void onConnectorConnected(String connectorName, long generation) {
    activeGenerations.put(connectorName, generation);
    LOG.fine("Connector " + connectorName + " online at gen " + generation);
  }

  public synchronized void onConnectorDisconnected(String connectorName, long generation) {
    Long activeGen = activeGenerations.get(connectorName);
    // Protection de génération : si le connecteur a déjà une génération plus récente, ne pas invalider
    if (activeGen != null && activeGen != generation) {
      LOG.fine("Ignoring stale disconnect for " + connectorName + " gen " + generation + " (current is " + activeGen + ")");
      return;
    }

    List<CapabilityRuntimeState> toWithdraw = new ArrayList<>();
    for (CapabilityRuntimeState cap : capabilities.values()) {
      boolean hadSources = hasOnlineSources(cap);
      if (hadSources) {
        // Vérifier si une autre source que celle de ce connecteur reste active
        boolean hasRemaining = false;
        for (SourceRuntimeState src : cap.sources.values()) {
          if (!src.connectorName().equals(connectorName)) {
            Long gen = activeGenerations.get(src.connectorName());
            if (gen != null && gen == src.generation()) {
              hasRemaining = true;
              break;
            }
          }
        }
        if (!hasRemaining) {
          toWithdraw.add(cap);
        }
      }
      cap.sources.entrySet().removeIf(e -> e.getValue().connectorName().equals(connectorName)
          && e.getValue().generation() == generation);
    }

    activeGenerations.remove(connectorName);

    for (CapabilityRuntimeState cap : toWithdraw) {
      for (CardAnnouncementListener l : listeners) {
        l.onCapabilityWithdrawn(cap.persistentCardId);
      }
    }
    LOG.fine("Connector " + connectorName + " disconnected at gen " + generation + " — sources withdrawn");
  }

  public synchronized void onCardRemoved(String connectorName, String profile, int caid, int provider,
                                         long originCardId, long generation) {
    Long activeGen = activeGenerations.get(connectorName);
    if (activeGen == null || activeGen != generation) return;

    CapabilityKey key = new CapabilityKey(profile, caid, provider);
    CapabilityRuntimeState cap = capabilities.get(key);
    if (cap != null) {
      boolean hadSources = hasOnlineSources(cap);
      cap.sources.remove(connectorName + ":" + originCardId);

      if (hadSources && !hasOnlineSources(cap)) {
        for (CardAnnouncementListener l : listeners) {
          l.onCapabilityWithdrawn(cap.persistentCardId);
        }
      }
    }
  }

  private boolean hasOnlineSources(CapabilityRuntimeState cap) {
    for (SourceRuntimeState src : cap.sources.values()) {
      Long activeGen = activeGenerations.get(src.connectorName());
      if (activeGen != null && activeGen == src.generation()) {
        return true;
      }
    }
    return false;
  }

  public synchronized boolean isSourceOnline(String connectorName, long generation) {
    Long activeGen = activeGenerations.get(connectorName);
    return activeGen != null && activeGen == generation;
  }

  /**
   * Enregistre une carte découverte sur un connecteur.
   */
  public io.vertx.core.Future<Void> onCardDiscovered(String connectorName, String profile, int caid, int provider,
                                                     long originCardId, int hop, int upstreamReshare,
                                                     CardSource.Trust trust, int connectorCap, Integer newcamdGrant,
                                                     long generation) {
    Long activeGen = activeGenerations.get(connectorName);
    if (activeGen == null || activeGen != generation) {
      LOG.fine("Ignoring card from " + connectorName + " — stale or inactive generation " + generation);
      return io.vertx.core.Future.succeededFuture();
    }

    int capBound = Math.max(0, Math.min(10, connectorCap));

    // Calcul du reshare effectif selon le protocole/trust
    int effectiveReshare;
    if (newcamdGrant != null || trust == CardSource.Trust.DECLARED_UNVERIFIED) {
      // Newcamd : grant obligatoire (NULL = STRICTEMENT interdit / non annoncé)
      if (newcamdGrant == null) {
        LOG.fine("Newcamd card without grant from " + connectorName + " — reshare UNKNOWN forbidden");
        return io.vertx.core.Future.succeededFuture();
      }
      int grant = Math.max(0, Math.min(10, newcamdGrant));
      effectiveReshare = (grant == 0) ? 0 : Math.min(grant, capBound);
    } else {
      // CCcam : amont reshare = 0 STRICTEMENT interdit (jamais exposé)
      if (upstreamReshare <= 0) {
        LOG.fine("CCcam card with upstream reshare 0 from " + connectorName + " — never exposed");
        return io.vertx.core.Future.succeededFuture();
      }
      effectiveReshare = Math.max(0, Math.min(upstreamReshare - 1, capBound));
    }

    String sourceKey = connectorName + ":" + originCardId;
    SourceRuntimeState source = new SourceRuntimeState(
        connectorName, originCardId, generation, hop + 1, upstreamReshare, effectiveReshare, trust);

    if (catalogRepository != null) {
      // Flow asynchrone PG réel obligatoire (pas de mock fallback)
      io.vertx.core.Future<Void> fut = catalogRepository.allocateOrGetCapability(profile, caid, provider)
          .compose(capRecord -> catalogRepository.upsertSource(capRecord.id(), connectorName, originCardId, trust, hop + 1, effectiveReshare)
              .map(capRecord))
          .compose(capRecord -> {
            Long curGen = activeGenerations.get(connectorName);
            if (curGen == null || curGen != generation) {
              LOG.fine("Generation moved during async PG persistence for " + connectorName);
              return io.vertx.core.Future.<Void>succeededFuture();
            }

            CapabilityKey key = new CapabilityKey(profile, caid, provider);
            CapabilityRuntimeState cap = capabilities.computeIfAbsent(key, k ->
                new CapabilityRuntimeState(capRecord.cardId(), profile, caid, provider, capRecord.state())
            );
            cap.state = capRecord.state();
            cap.sources.put(sourceKey, source);

            // Annonce UNIQUEMENT après complétion PG
            if (vertx != null) {
              vertx.setTimer(2000, timerId -> {
                Long g = activeGenerations.get(connectorName);
                if (g != null && g == generation && cap.sources.containsKey(sourceKey)) {
                  notifySubscribersAvailable(cap);
                }
              });
            } else {
              notifySubscribersAvailable(cap);
            }
            return io.vertx.core.Future.<Void>succeededFuture();
          });
      return fut.onFailure(err -> LOG.warning("Async catalog persist error: " + err.getMessage()));
    } else {
      // Constructeur dédié mémoire (pour tests unitaires sans base PG)
      CapabilityKey key = new CapabilityKey(profile, caid, provider);
      CapabilityRuntimeState cap = capabilities.computeIfAbsent(key, k -> {
        long cardId = mockSequence.getAndIncrement();
        return new CapabilityRuntimeState(cardId, profile, caid, provider, CardCapability.State.DISCOVERED);
      });
      cap.sources.put(sourceKey, source);

      if (vertx != null) {
        vertx.setTimer(2000, timerId -> {
          Long curGen = activeGenerations.get(connectorName);
          if (curGen != null && curGen == generation && cap.sources.containsKey(sourceKey)) {
            notifySubscribersAvailable(cap);
          }
        });
      } else {
        notifySubscribersAvailable(cap);
      }
      return io.vertx.core.Future.succeededFuture();
    }
  }

  public synchronized void updateCapabilityState(String profile, int caid, int provider, CardCapability.State newState) {
    CapabilityKey key = new CapabilityKey(profile, caid, provider);
    CapabilityRuntimeState cap = capabilities.get(key);
    if (cap != null) {
      cap.state = newState;
      if (newState == CardCapability.State.BLOCKED) {
        for (CardAnnouncementListener l : listeners) {
          l.onCapabilityWithdrawn(cap.persistentCardId);
        }
      } else {
        notifySubscribersAvailable(cap);
      }
    }
  }

  /**
   * Enregistre une capacite publiee au niveau cluster / desired (worker sans connecteur local).
   */
  public synchronized void publishClusterCapability(String profile, int caid, int provider, long persistentCardId) {
    CapabilityKey key = new CapabilityKey(profile, caid, provider);
    CapabilityRuntimeState cap = capabilities.computeIfAbsent(key,
        k -> new CapabilityRuntimeState(persistentCardId > 0 ? persistentCardId : mockSequence.getAndIncrement(),
            profile, caid, provider, CardCapability.State.PUBLISHED));
    cap.state = CardCapability.State.PUBLISHED;
  }

  private void notifySubscribersAvailable(CapabilityRuntimeState cap) {
    for (CardAnnouncementListener l : listeners) {
      if (isEligibleForListener(cap, l)) {
        l.onCapabilityAvailable(cap);
      }
    }
  }

  private boolean isEligibleForListener(CapabilityRuntimeState cap, CardAnnouncementListener l) {
    if (cap.state == CardCapability.State.BLOCKED) return false;

    boolean isWildcard = "wildcard".equalsIgnoreCase(l.getCardMode());
    // Mode manual : publication obligatoire (PUBLISHED), indépendamment du trust de provenance
    if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) {
      return false;
    }

    UserAclContext acl = l.getAclContext();
    if (acl != null) {
      if (acl.profiles() != null && !acl.profiles().isEmpty() && !acl.profiles().contains(cap.profile)) {
        return false;
      }
    }

    // Doit avoir au moins une source en ligne autorisée par l'ACL
    for (SourceRuntimeState src : cap.sources.values()) {
      Long activeGen = activeGenerations.get(src.connectorName());
      if (activeGen != null && activeGen == src.generation()) {
        if (acl == null || acl.allowedConnectors() == null || acl.allowedConnectors().isEmpty()
            || acl.allowedConnectors().contains(src.connectorName())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Construit les annonces initiales pour une session CCcam (filtrées par son ACL/mode).
   */
  public List<byte[]> getInitialAnnouncements(String profile, String cardMode, int listenerCap,
                                              int maxCards, byte[] serverNodeId, UserAclContext acl) {
    boolean isWildcard = "wildcard".equalsIgnoreCase(cardMode);
    int capBound = Math.max(0, Math.min(10, listenerCap));
    List<byte[]> announcements = new ArrayList<>();

    List<CapabilityRuntimeState> eligibleCaps = new ArrayList<>();
    for (CapabilityRuntimeState cap : capabilities.values()) {
      if (!cap.profile.equals(profile)) continue;
      if (cap.state == CardCapability.State.BLOCKED) continue;
      if (!isWildcard && cap.state != CardCapability.State.PUBLISHED) continue;

      if (acl != null && acl.profiles() != null && !acl.profiles().isEmpty() && !acl.profiles().contains(profile)) {
        continue;
      }

      // Filtrer sources actives et autorisées
      int minHop = 999;
      int maxReshare = -1;
      for (SourceRuntimeState src : cap.sources.values()) {
        Long curGen = activeGenerations.get(src.connectorName());
        if (curGen != null && curGen == src.generation()) {
          if (acl == null || acl.allowedConnectors() == null || acl.allowedConnectors().isEmpty()
              || acl.allowedConnectors().contains(src.connectorName())) {
            minHop = Math.min(minHop, src.hop());
            maxReshare = Math.max(maxReshare, src.effectiveReshare());
          }
        }
      }

      if (maxReshare >= 0 && minHop < 999) {
        int outReshare = Math.min(maxReshare, capBound);
        eligibleCaps.add(cap);
      }
    }

    // Tri déterministe par caid, provider, persistentCardId
    eligibleCaps.sort(Comparator.comparingInt((CapabilityRuntimeState c) -> c.caid)
        .thenComparingInt(c -> c.provider)
        .thenComparingLong(c -> c.persistentCardId));

    int limit = Math.min(eligibleCaps.size(), Math.max(1, Math.min(4096, maxCards)));
    byte[] localNode = (serverNodeId != null && serverNodeId.length == 8) ? serverNodeId.clone() : new byte[8];

    for (int i = 0; i < limit; i++) {
      CapabilityRuntimeState cap = eligibleCaps.get(i);
      int minHop = 999;
      int maxReshare = 0;
      for (SourceRuntimeState src : cap.sources.values()) {
        minHop = Math.min(minHop, src.hop());
        maxReshare = Math.max(maxReshare, src.effectiveReshare());
      }
      int outReshare = Math.min(maxReshare, capBound);

      List<CcCardCodec.CcProvider> provs = new ArrayList<>();
      if (cap.provider > 0) {
        provs.add(new CcCardCodec.CcProvider(cap.provider, new byte[4])); // SA = 0
      }
      List<byte[]> nodes = List.of(localNode); // Nodes amont masqués

      byte[] payload = CcCardCodec.buildNewCard(
          (int) cap.persistentCardId, 0, cap.caid, minHop, outReshare,
          new byte[8], provs, nodes);
      announcements.add(payload);
    }
    return announcements;
  }

  public CapabilityRuntimeState getCapability(String profile, int caid, int provider) {
    return capabilities.get(new CapabilityKey(profile, caid, provider));
  }

  /**
   * Retourne toutes les capacités d'un profil et d'un CAID donné (quel que soit le provider).
   */
  public List<CapabilityRuntimeState> getCapabilitiesForCaid(String profile, int caid) {
    List<CapabilityRuntimeState> result = new ArrayList<>();
    for (CapabilityRuntimeState cap : capabilities.values()) {
      if (cap.profile.equals(profile) && cap.caid == caid) {
        result.add(cap);
      }
    }
    return result;
  }
}
