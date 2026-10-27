package com.bowman.cspng.connectors;

import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Probe de connecteur temporaire — teste une connexion CWS sans l'enregistrer dans le cycle de vie permanent.
 *
 * <p>Instancie directement un connecteur temporaire (pas via {@link CwsConnectorManager}), appelle
 * {@code start()}, attend {@code loginFuture()} avec timeout borné, puis {@code stop()} dans tous les cas.
 * Pour CCcam, nettoie explicitement le transport cache ({@code cacheVerticle.removeTransport}) pour éviter
 * tout transport fantôme.
 */
public class ConnectorProbe {

  private static final Logger LOG = Logger.getLogger(ConnectorProbe.class.getName());

  public record ProbeResult(boolean success, AbstractConnector.LoginState loginState, String errorMessage) {}

  /**
   * Teste la connexion à un serveur CWS.
   *
   * @param vertx         instance Vert.x
   * @param cacheVerticle verticle cache (requis pour CCcam, peut être null pour autres protocoles mais recommandé)
   * @param protocol      "newcamd", "cccam" ou "radegast" (case-insensitive)
   * @param host          hôte distant
   * @param port          port distant
   * @param username      username (pour newcamd/cccam, peut être null pour radegast)
   * @param password      mot de passe en clair (jamais loggé)
   * @param desKey14      clé DES 14 octets pour newcamd (null pour autres)
   * @param timeoutMs     timeout en ms pour le login
   * @return future avec le résultat du probe
   */
  public static Future<ProbeResult> probe(Vertx vertx, CacheVerticle cacheVerticle,
                                          String protocol, String host, int port,
                                          String username, String password,
                                          byte[] desKey14, long timeoutMs) {
    return probe(vertx, cacheVerticle, protocol, host, port, username, password, desKey14,
        timeoutMs, null);
  }

  /**
   * Teste la connexion à un serveur CWS via un proxy SOCKS5 optionnel.
   *
   * <p>Si {@code proxyOptions} est non-null, le connecteur temporaire est créé
   * avec le proxy : la connexion passe par le SOCKS5 et la résolution DNS de la
   * cible est déléguée au proxy (ATYP domain). Les secrets du proxy doivent
   * être DÉJÀ résolus par l'appelant (worker thread) avant cet appel —
   * le probe n'accède jamais aux refs de secrets.
   *
   * @param proxyOptions options proxy résolues (null = connexion directe)
   */
  public static Future<ProbeResult> probe(Vertx vertx, CacheVerticle cacheVerticle,
                                          String protocol, String host, int port,
                                          String username, String password,
                                          byte[] desKey14, long timeoutMs,
                                          AbstractConnector.NetworkProxyOptions proxyOptions) {
    if (vertx == null) return Future.failedFuture("vertx required");
    if (protocol == null || host == null) return Future.failedFuture("protocol/host required");

    String proto = protocol.toLowerCase(java.util.Locale.ROOT).trim();
    AbstractConnector connector;
    String probeProfile = "__probe__";
    // T du probe : durée DÉDIÉE issue de la config bornée (timeoutMs = probe/login
    // timeout du service appelant, ex. csp.import.probe-timeout-ms), clampée
    // 1000..300000 — PAS un timeout porté par le proxy (Plan P2).
    int probeConnectTimeout = clampTimeout(timeoutMs);
    try {
      switch (proto) {
        case "newcamd" -> {
          if (desKey14 == null || desKey14.length != 14) {
            return Future.succeededFuture(new ProbeResult(false, AbstractConnector.LoginState.FAILED, "desKey14 required (14 bytes)"));
          }
          // Defensive copy already done in connector constructor, but ensure not null
          byte[] keyCopy = Arrays.copyOf(desKey14, desKey14.length);
          String u = username != null ? username : "";
          String p = password != null ? password : "";
          connector = new NewcamdConnector(vertx, cacheVerticle, "__probe_" + System.nanoTime(), host, port, keyCopy, u, p, probeProfile, probeConnectTimeout, proxyOptions);
        }
        case "cccam" -> {
          String u = username != null ? username : "";
          String p = password != null ? password : "";
          CacheVerticle cv = cacheVerticle != null ? cacheVerticle : new CacheVerticle();
          connector = new CcCamConnector(vertx, cv, "__probe_" + System.nanoTime(), host, port, u, p, probeProfile,
              com.bowman.cspng.codec.cccam.CcConstants.DEFAULT_VERSION,
              com.bowman.cspng.codec.cccam.CcConstants.DEFAULT_BUILD,
              com.bowman.cspng.codec.cccam.CcConstants.CACHEEX_MODE_OFF,
              com.bowman.cspng.codec.cccam.CcConstants.CACHEEX_DEFAULT_MAXHOP,
              probeConnectTimeout, proxyOptions);
        }
        case "radegast" -> {
          CacheVerticle cv = cacheVerticle != null ? cacheVerticle : new CacheVerticle();
          connector = new RadegastConnector(vertx, cv, "__probe_" + System.nanoTime(), host, port, probeProfile, probeConnectTimeout, proxyOptions);
        }
        default -> {
          return Future.succeededFuture(new ProbeResult(false, AbstractConnector.LoginState.FAILED, "unknown protocol: " + proto));
        }
      }
    } catch (Exception e) {
      return Future.succeededFuture(new ProbeResult(false, AbstractConnector.LoginState.FAILED, "probe init failed: " + e.getMessage()));
    }

    Promise<ProbeResult> resultPromise = Promise.promise();
    AtomicBoolean done = new AtomicBoolean(false);

    // Timeout timer
    long timerId = vertx.setTimer(timeoutMs, id -> {
      if (done.compareAndSet(false, true)) {
        cleanup(connector, cacheVerticle);
        resultPromise.complete(new ProbeResult(false, AbstractConnector.LoginState.FAILED, "timeout after " + timeoutMs + "ms"));
      }
    });

    // Observe loginFuture
    connector.loginFuture().onComplete(ar -> {
      if (done.compareAndSet(false, true)) {
        vertx.cancelTimer(timerId);
        if (ar.succeeded()) {
          AbstractConnector.LoginState state = ar.result();
          boolean success = state == AbstractConnector.LoginState.OK;
          cleanup(connector, cacheVerticle);
          String err = success ? null : "login " + state;
          resultPromise.complete(new ProbeResult(success, state, err));
        } else {
          cleanup(connector, cacheVerticle);
          String msg = ar.cause() != null ? ar.cause().getMessage() : "login failed";
          resultPromise.complete(new ProbeResult(false, AbstractConnector.LoginState.FAILED, msg));
        }
      }
    });

    // Start connector (fire)
    connector.start().onComplete(ar -> {
      if (ar.failed()) {
        // TCP connect failed — loginFuture will eventually complete as FAILED via failLoginOnClose,
        // but also handle fast path: if login still pending, we rely on timeout or login failure.
        // No immediate completion here to allow loginFuture to propagate.
        LOG.log(Level.FINE, "Probe TCP connect failed for " + host + ":" + port + " — waiting for loginFuture timeout/failure");
      }
    });

    // Ensure cleanup also happens if resultPromise completed via timeout but login later completes — already handled by done flag.
    // Also handle case where vertx closed before timer: resultPromise will be completed via login or timeout.

    return resultPromise.future();
  }

  private static void cleanup(AbstractConnector connector, CacheVerticle cacheVerticle) {
    try {
      connector.stop();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Probe cleanup stop failed: " + e.getMessage());
    }
    // For CCcam, remove transport phantom
    if (connector instanceof CacheTransport ct && cacheVerticle != null) {
      try {
        cacheVerticle.removeTransport(ct);
      } catch (Exception e) {
        LOG.log(Level.WARNING, "Probe cleanup removeTransport failed: " + e.getMessage());
      }
    }
  }

  /** Clamp une durée dédiée du probe dans [1000..300000] ms (Plan P2). */
  static int clampTimeout(long t) {
    if (t < 1000) return 1000;
    if (t > 300_000) return 300_000;
    return (int) t;
  }

  // Non-instantiable utility — but allow instance for CDI if needed
  public ConnectorProbe() {}
}
