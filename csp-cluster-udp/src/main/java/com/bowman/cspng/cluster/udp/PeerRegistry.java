package com.bowman.cspng.cluster.udp;

import io.vertx.core.net.SocketAddress;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Registre des pairs UDP connus (unicast + multicast).
 *
 * <h3>Fonctionnalités</h3>
 * <ul>
 *   <li>Pairs statiques configurés ({@code remote-host}/{@code remote-port}).</li>
 *   <li>{@code auto-add-peers} : tout émetteur valide devient pair automatiquement
 *       (utilise l'adresse réelle du paquet UDP, {@code SocketAddress}).</li>
 *   <li>Multicast : rejoindre un {@code multicast-group} configurable avec TTL.</li>
 *   <li><b>Pas d'auto-add sur {@code TYPE_RESENDREQ}</b> (mitigation anti-amplification,
 *       plan §2.6).</li>
 * </ul>
 *
 * <h3>Reporté à un lot ultérieur</h3>
 * <p>Le tracker HTTP/FTP distant ({@code tracker-url}, liste de pairs chiffrée Blowfish)
 * n'est PAS implémenté dans L3a. Non bloquant pour l'interop OSCam/CSP legacy qui utilise
 * principalement l'unicast/multicast.
 */
public class PeerRegistry {

  private final Set<SocketAddress> staticPeers = new CopyOnWriteArraySet<>();
  private final Set<SocketAddress> dynamicPeers = new CopyOnWriteArraySet<>();
  private final Set<SocketAddress> blockedIps = new CopyOnWriteArraySet<>();

  private volatile boolean autoAddPeers = true;
  private volatile SocketAddress multicastAddress;

  /**
   * Ajoute un pair statique (configuré).
   */
  public void addStaticPeer(String host, int port) {
    staticPeers.add(SocketAddress.inetSocketAddress(port, host));
  }

  /**
   * Supprime un pair statique.
   */
  public void removeStaticPeer(String host, int port) {
    staticPeers.remove(SocketAddress.inetSocketAddress(port, host));
  }

  /**
   * Configure l'adresse multicast.
   */
  public void setMulticastGroup(String host, int port) {
    this.multicastAddress = SocketAddress.inetSocketAddress(port, host);
  }

  /**
   * Retourne l'adresse multicast, ou {@code null} si non configurée.
   */
  public SocketAddress multicastAddress() {
    return multicastAddress;
  }

  /**
   * Active ou désactive l'auto-ajout des pairs.
   */
  public void setAutoAddPeers(boolean enabled) {
    this.autoAddPeers = enabled;
  }

  /**
   * Ajoute un pair à la blacklist.
   */
  public void blockIp(String host) {
    blockedIps.add(SocketAddress.inetSocketAddress(0, host));
  }

  /**
   * Vérifie si une IP est blacklistée.
   */
  public boolean isBlocked(String host) {
    return blockedIps.stream().anyMatch(a -> a.host().equals(host));
  }

  /**
   * Enregistre un pair dynamique découvert par auto-add.
   * Retourne {@code true} si le pair a été ajouté (nouveau), {@code false} s'il
   * était déjà connu, blacklisté, ou si {@code autoAddPeers} est désactivé.
   */
  public boolean registerDynamicPeer(SocketAddress address) {
    if (!autoAddPeers) return false;
    if (isBlocked(address.host())) return false;
    if (isKnown(address)) return false;
    return dynamicPeers.add(address);
  }

  /**
   * Retourne tous les pairs connus (statiques + dynamiques).
   */
  public Collection<SocketAddress> allPeers() {
    Set<SocketAddress> all = ConcurrentHashMap.newKeySet();
    all.addAll(staticPeers);
    all.addAll(dynamicPeers);
    return Collections.unmodifiableCollection(all);
  }

  /**
   * Retourne le nombre total de pairs.
   */
  public int peerCount() {
    return staticPeers.size() + dynamicPeers.size();
  }

  /**
   * Allowlist stricte : adresse EXACTE (host+port), sans repli host-only.
   * En mode strict, un pair dont le port source differe du port configure est
   * rejete (l'admin doit declarer le port d'emission reel ; en pratique les
   * workers emettent depuis leur socket liee = port configure).
   */
  public boolean isKnownExact(SocketAddress address) {
    if (address == null) return false;
    if (staticPeers.contains(address)) return true;
    return dynamicPeers.contains(address);
  }

  /**
   * Vérifie si une adresse est déjà connue comme pair (statique ou dynamique).
   */
  public boolean isKnown(SocketAddress address) {
    if (staticPeers.contains(address)) return true;
    if (dynamicPeers.contains(address)) return true;
    // Comparaison par host uniquement (un pair peut avoir des ports différents
    // pour l'envoi et la réception)
    String host = address.host();
    for (SocketAddress p : staticPeers) {
      if (p.host().equals(host)) return true;
    }
    for (SocketAddress p : dynamicPeers) {
      if (p.host().equals(host)) return true;
    }
    return false;
  }

  /**
   * Vérifie si l'IP source d'un paquet correspond à un pair connu (statique ou dynamique).
   * Utilisé pour la vérification anti-amplification.
   */
  public boolean isKnownHost(String host) {
    if (isBlocked(host)) return false;
    for (SocketAddress p : staticPeers) {
      if (p.host().equals(host)) return true;
    }
    for (SocketAddress p : dynamicPeers) {
      if (p.host().equals(host)) return true;
    }
    return false;
  }

  /**
   * Retourne les pairs statiques.
   */
  Collection<SocketAddress> staticPeers() {
    return Collections.unmodifiableCollection(staticPeers);
  }

  /**
   * Retourne les pairs dynamiques.
   */
  Collection<SocketAddress> dynamicPeers() {
    return Collections.unmodifiableCollection(dynamicPeers);
  }

  /**
   * Supprime tous les pairs (sauf les statiques si keepStatic est true).
   */
  public void clear(boolean keepStatic) {
    dynamicPeers.clear();
    if (!keepStatic) staticPeers.clear();
  }

  @Override
  public String toString() {
    return "PeerRegistry{static=" + staticPeers.size()
        + ", dynamic=" + dynamicPeers.size()
        + ", multicast=" + multicastAddress + "}";
  }
}
