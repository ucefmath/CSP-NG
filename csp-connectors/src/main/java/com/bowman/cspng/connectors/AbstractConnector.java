package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Classe de base pour les connecteurs CWS (Newcamd, Radegast, etc.).
 *
 * <p>Chaque connecteur établit une connexion TCP vers un serveur upstream
 * (cardserver) et relaie les requêtes ECM. En cas de miss cache, le
 * connecteur interroge le serveur upstream et route la réponse CW vers
 * le {@link CacheVerticle} via {@code handleReply()}.
 *
 * <h3>Support proxy SOCKS5 (Plan P2)</h3>
 * <p>Si un {@link NetworkProxyOptions} est fourni, le connecteur passe par un
 * proxy SOCKS5. La résolution DNS de la cible est déléguée au proxy (ATYP
 * domain) — aucune résolution DNS locale. Le proxy ne porte PAS de timeout :
 * le budget T est le {@code connectTimeoutMs} du CONNECTEUR (borné
 * 1 000..300 000 ms par le lifecycle sur le chemin proxy), appliqué aux deux
 * phases :
 * <ul>
 *   <li>TCP connect vers le proxy : {@code NetClientOptions.setConnectTimeout(T)}</li>
 *   <li>Handshake SOCKS5 : {@code ProxyOptions.setConnectTimeout(Duration.ofMillis(T))}</li>
 * </ul>
 * Chaque phase a son propre budget, le total peut atteindre 2×T. Les options
 * runtime du proxy n'écrasent jamais le T du connecteur.
 *
 * <p><b>Fail-closed strict</b> : si {@code NetworkProxyOptions} est non-null mais
 * contient des secrets non résolus, le connecteur doit être créé avec
 * {@code null} pour forcer l'échec — jamais de fallback en direct.
 *
 * <p><b>Secrets</b> : le mot de passe proxy résolu ne doit jamais apparaître
 * dans les logs. Les options ne sont pas loggées.
 */
public abstract class AbstractConnector {

  /** Options proxy résolu passées par le lifecycle manager. null = connexion directe.
   *  Ne porte PAS de timeout (Plan P2) : le budget T est celui du connecteur
   *  (paramètre de {@link #buildNetClientOptions(int)}), appliqué aux deux phases. */
  public static final class NetworkProxyOptions {
    public final String proxyHost;
    public final int proxyPort;
    public final String username;   // null si pas d'auth
    public final String password;   // secret résolu — JAMAIS loggé

    public NetworkProxyOptions(String proxyHost, int proxyPort,
                                String username, String password) {
      if (proxyHost == null || proxyHost.isBlank()) throw new IllegalArgumentException("proxyHost required");
      if (proxyPort < 1 || proxyPort > 65535) throw new IllegalArgumentException("proxyPort 1-65535");
      this.proxyHost = proxyHost;
      this.proxyPort = proxyPort;
      this.username = username;
      this.password = password;
    }

    /** @return toString() sans mot de passe — safe pour logs. */
    @Override public String toString() {
      return "NetworkProxyOptions{host=" + proxyHost + ", port=" + proxyPort
          + ", auth=" + (username != null) + "}";
    }
  }

  protected final Logger logger;
  protected final Vertx vertx;
  protected final CacheVerticle cacheVerticle;
  protected final String name;
  protected final String host;
  protected final int port;
  protected final String profile;
  /** Options réseau proxy — null = connexion directe (legacy). */
  protected final NetworkProxyOptions proxyOptions;
  protected com.bowman.cspng.connectors.catalog.CardDiscoveryService cardDiscoveryService;
  protected int reshareCap = 0;
  protected int priority = 50;

  public void setCardDiscoveryService(com.bowman.cspng.connectors.catalog.CardDiscoveryService service) {
    this.cardDiscoveryService = service;
  }

  public void setReshareCap(int cap) {
    this.reshareCap = Math.max(0, Math.min(10, cap));
  }

  public int getReshareCap() {
    return reshareCap;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public int getPriority() {
    return priority;
  }

  protected volatile boolean connected;
  protected volatile boolean stopped;

  /** État dérivé du connecteur (machine à états). */
  public enum ConnectorState {
    CONNECTED,
    UNSTABLE,
    DEAD
  }

  protected final java.util.concurrent.atomic.AtomicLong hitCount = new java.util.concurrent.atomic.AtomicLong();
  protected final java.util.concurrent.atomic.AtomicLong ecmSentCount = new java.util.concurrent.atomic.AtomicLong();
  protected final java.util.concurrent.atomic.AtomicLong ecmFailedCount = new java.util.concurrent.atomic.AtomicLong();
  protected final java.util.concurrent.atomic.AtomicLong lastReplyTimeMs = new java.util.concurrent.atomic.AtomicLong();
  private final java.util.ArrayDeque<Long> errorTimestamps = new java.util.ArrayDeque<>(32);
  private final Object errorLock = new Object();
  private final java.util.Set<Integer> observedCaids = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Set<Integer> observedProviders = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public void recordHit() {
    hitCount.incrementAndGet();
    lastReplyTimeMs.set(System.currentTimeMillis());
  }

  public void recordEcmSent(int caid, int provid) {
    ecmSentCount.incrementAndGet();
    if (caid != 0 && observedCaids.size() < 64) {
      observedCaids.add(caid & 0xFFFF);
    }
    if (provid != 0 && observedProviders.size() < 64) {
      observedProviders.add(provid & 0xFFFFFF);
    }
  }

  public void recordError() {
    ecmFailedCount.incrementAndGet();
    recordErrorInternal();
  }

  public void recordReconnect() {
    recordErrorInternal();
  }

  private void recordErrorInternal() {
    long now = System.currentTimeMillis();
    synchronized (errorLock) {
      pruneErrorsUnderLock(now);
      if (errorTimestamps.size() >= 32) {
        errorTimestamps.pollFirst();
      }
      errorTimestamps.addLast(now);
    }
  }

  private void pruneErrorsUnderLock(long now) {
    long cutoff = now - 300_000L; // 5 minutes
    while (!errorTimestamps.isEmpty()) {
      Long first = errorTimestamps.peekFirst();
      if (first == null || first >= cutoff) break;
      errorTimestamps.pollFirst();
    }
  }

  public boolean isUnstable() {
    long now = System.currentTimeMillis();
    synchronized (errorLock) {
      pruneErrorsUnderLock(now);
      return errorTimestamps.size() >= 3;
    }
  }

  public ConnectorState getState() {
    if (stopped || !connected) {
      return ConnectorState.DEAD;
    }
    try {
      var fut = loginFuture();
      if (fut != null && fut.isComplete() && (fut.failed() || fut.result() == LoginState.FAILED)) {
        return ConnectorState.DEAD;
      }
    } catch (Exception ignored) {}
    if (isUnstable()) {
      return ConnectorState.UNSTABLE;
    }
    return ConnectorState.CONNECTED;
  }

  public long getHits() { return hitCount.get(); }
  public long getEcmSent() { return ecmSentCount.get(); }
  public long getEcmFailed() { return ecmFailedCount.get(); }
  public long getLastSeenMs() { return lastReplyTimeMs.get(); }
  public java.util.Set<Integer> getObservedCaids() { return java.util.Set.copyOf(observedCaids); }
  public java.util.Set<Integer> getObservedProviders() { return java.util.Set.copyOf(observedProviders); }

  /**
   * Jeton de génération : incrémenté à chaque {@code start()} et {@code stop()}.
   * Les callbacks de connexion obsolètes (succès/échec TARDIFS après stop, ou
   * résultats d'un cycle de vie antérieur après restart/update de la même
   * instance) sont neutralisés par {@link #isStale(int)} — kill switch étanche :
   * aucune réassignation de socket/connected, aucun handler attaché, aucune
   * reconnexion relancée à partir d'un résultat obsolète.
   */
  private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();

  /** @return le jeton de génération courant (à capturer AVANT un connect). */
  protected int currentGeneration() { return generation.get(); }

  /** Nouvelle génération : invalide tous les callbacks en vol. @return le nouveau jeton. */
  protected int nextGeneration() { return generation.incrementAndGet(); }

  /**
   * @param gen jeton capturé avant le connect
   * @return true si le résultat est obsolète (stopped, ou génération dépassée
   *         par un stop/restart/update) — le callback appelant doit ignorer le
   *         résultat et fermer toute socket obtenue.
   */
  protected boolean isStale(int gen) { return gen != generation.get() || stopped; }

  /**
   * Hook de création du {@link NetClient} — surchargeable par les tests pour
   * tracker clients créés/fermés. Le chemin production crée un client Vert.x
   * avec les options construites par {@link #buildNetClientOptions(int)}.
   */
  protected NetClient createNetClient(NetClientOptions options) {
    return vertx.createNetClient(options);
  }

  /** Constructeur legacy (connexion directe, pas de proxy). */
  protected AbstractConnector(Vertx vertx, CacheVerticle cacheVerticle,
                               String name, String host, int port, String profile) {
    this(vertx, cacheVerticle, name, host, port, profile, null);
  }

  /** Constructeur avec support proxy SOCKS5 optionnel. */
  protected AbstractConnector(Vertx vertx, CacheVerticle cacheVerticle,
                               String name, String host, int port, String profile,
                               NetworkProxyOptions proxyOptions) {
    this.vertx = vertx;
    this.cacheVerticle = cacheVerticle;
    this.name = name;
    this.host = host;
    this.port = port;
    this.profile = profile;
    this.proxyOptions = proxyOptions;
    this.logger = Logger.getLogger(getClass().getName() + "." + name);
  }

  public String getName() { return name; }
  public String getHost() { return host; }
  public int getPort() { return port; }
  public String getProfile() { return profile; }
  public boolean isConnected() { return connected; }
  public NetworkProxyOptions proxyOptions() { return proxyOptions; }

  /**
   * Identité de source pour la corroboration. Deux noms vers le même host:port
   * (et le même proxy) sont la même source. Un groupe explicite fusionne des
   * endpoints distincts déclarés dépendants.
   */
  public String independenceKey() {
    String endpoint = (host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT)) + ":" + port;
    if (proxyOptions != null) {
      endpoint = proxyOptions.proxyHost.trim().toLowerCase(java.util.Locale.ROOT) + ":" + proxyOptions.proxyPort + ">" + endpoint;
    }
    return "endpoint:" + endpoint;
  }

  /**
   * Construit un {@link NetClientOptions} configuré avec les timeouts et
   * le proxy SOCKS5 si présent.
   *
   * <p>Si {@code proxyOptions} est non-null :
   * <ul>
   *   <li>TCP connect timeout = {@code connectTimeoutMs} (T du CONNECTEUR)</li>
   *   <li>SOCKS5 handshake timeout = {@code Duration.ofMillis(connectTimeoutMs)} (même T)</li>
   *   <li>ProxyType = SOCKS5, host = proxyHost, port = proxyPort</li>
   *   <li>Auth conditionnelle (username/password)</li>
   * </ul>
   * Le proxy ne porte PAS son propre timeout — les options runtime n'écrasent
   * JAMAIS le T du connecteur : T est le paramètre, appliqué aux deux phases.
   *
   * <p>SANS proxy (connexion directe), les options restent les DÉFAUTS
   * historiques de Vert.x — y compris le connect timeout par défaut
   * (60 000 ms) : le paramètre {@code connectTimeoutMs} est IGNORÉ sur le
   * chemin direct (Plan P2 : "Direct legacy préserver options par défaut").
   *
   * @param connectTimeoutMs budget T du connecteur (uniquement utilisé sur le chemin proxy)
   * @return options prêtes à l'emploi (jamais null)
   */
  protected NetClientOptions buildNetClientOptions(int connectTimeoutMs) {
    NetClientOptions opts = new NetClientOptions();

    if (proxyOptions != null) {
      // Chemin proxy : T = connectTimeoutMs du CONNECTEUR (paramètre) — aux DEUX phases.
      // Borné par l'appelant (lifecycle : 1000..300000 sur le chemin proxy).
      int t = connectTimeoutMs;
      // TCP connect vers proxy host
      opts.setConnectTimeout(t);
      // Handshake SOCKS5
      ProxyOptions po = new ProxyOptions()
          .setType(ProxyType.SOCKS5)
          .setHost(proxyOptions.proxyHost)
          .setPort(proxyOptions.proxyPort)
          .setConnectTimeout(Duration.ofMillis(t));
      if (proxyOptions.username != null) {
        po.setUsername(proxyOptions.username);
        po.setPassword(proxyOptions.password);
      }
      opts.setProxyOptions(po);
      // Ne jamais logger les options (contiennent potentiellement le mot de passe)
    }
    // PAS d'override direct : sans proxy, défauts Vert.x historiques (timeout 60s inclus).
    return opts;
  }

  /**
   * État d'authentification du connecteur.
   */
  public enum LoginState {
    PENDING,
    OK,
    FAILED
  }

  /**
   * Démarre le connecteur (établit la connexion TCP).
   */
  public abstract Future<Void> start();

  /**
   * Arrête le connecteur.
   */
  public abstract void stop();

  /**
   * Future qui se complète quand l'état d'authentification est connu.
   * Ne change pas le comportement de {@link #start()} — ajout additif.
   *
   * @return future avec {@link LoginState#OK} si login réussi, {@link LoginState#FAILED} sinon
   */
  public abstract Future<LoginState> loginFuture();

  /**
   * Envoie une requête ECM au serveur upstream.
   *
   * @param request la requête ECM
   * @return future résolue avec la CW, ou échouée en cas d'erreur
   */
  public abstract Future<CwReply> sendEcm(EcmRequest request);
}
