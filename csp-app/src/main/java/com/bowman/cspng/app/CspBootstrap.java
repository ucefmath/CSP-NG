package com.bowman.cspng.app;

import com.bowman.cspng.config.*;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bootstrap de l'application CSP-NG.
 *
 * <p>En L5a : initialise {@link ConfigHolder} (fail-fast),
 * charge le snapshot initial, et demarre le {@link ConfigWatcher}
 * pour le hot-reload via PostgreSQL NOTIFY.
 *
 * <p>Flag {@code csp.config.pg.enabled} (defaut {@code true} en prod) :
 * si {@code false}, le bootstrap de config PG est desactive
 * (tests/dev sans PG). Si {@code true} et {@code loadAll()} echoue,
 * l'application refuse de demarrer (fail-fast).
 *
 * <p>Ne rewire pas encore sessions/connecteurs/users (lots suivants).
 *
 * <p><b>Fail-fast synchrone :</b> Quarkus {@code StartupEvent} est un observer
 * synchrone — l'exception doit etre levee <em>avant</em> le retour de
 * {@code onStart} pour faire echouer le demarrage. Un {@code .onFailure(err -> throw)}
 * sur un {@code Future} Vert.x est asynchrone et ne bloque pas le demarrage
 * (bug corrige ici). On bloque donc avec
 * {@code toCompletionStage().toCompletableFuture().get(timeout)} sur le thread
 * de startup Quarkus. Ce thread <b>n'est pas</b> un event-loop Vert.x
 * (c'est le main thread Quarkus avant que le runtime Vert.x ne boucle), donc
 * bloquer avec timeout est sur. On verifie explicitement que l'on n'est pas
 * sur un event-loop avant de bloquer ; si c'est le cas on logge un warning
 * et on ne bloque pas indefiniment (timeout court).
 */
@ApplicationScoped
public class CspBootstrap {

  private static final Logger LOG = Logger.getLogger(CspBootstrap.class.getName());

  /** Timeout pour le chargement initial du snapshot (doit rester < Quarkus startup timeout). */
  static final long LOAD_TIMEOUT_SECONDS = 15;
  /** Timeout pour demarrer le watcher LISTEN (tolere, ne doit pas bloquer longtemps). */
  static final long WATCHER_START_TIMEOUT_SECONDS = 5;

  private final Vertx vertx;
  private final ConfigHolder holder;
  private ConfigWatcher watcher;

  @Inject
  public CspBootstrap(Vertx vertx, ConfigHolder holder) {
    this.vertx = vertx;
    this.holder = holder;
  }

  // Injection optionnelle — si Pool n'est pas disponible, la config PG est desactivee
  @Inject
  Instance<Pool> pgPoolInstance;

  @Inject
  @ConfigProperty(name = "csp.config.pg.enabled", defaultValue = "true")
  boolean pgEnabled;

  @Inject
  @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
  String quarkusProfile;

  public void onStart(@Observes @Priority(10) StartupEvent event) {
    LOG.info("CSP-NG bootstrap OK — Vert.x injected=" + (vertx != null));

    // Flag pour desactiver la config PG (tests/dev sans PG)
    if (!pgEnabled) {
      LOG.warning("csp.config.pg.enabled=false — config bootstrap skipped");
      return;
    }

    if (pgPoolInstance.isUnsatisfied() || pgPoolInstance.isAmbiguous()) {
      // En prod, PG doit etre configure — fail-fast
      if (!"test".equals(quarkusProfile)) {
        throw new IllegalStateException(
            "PG config is enabled (csp.config.pg.enabled=true) but no reactive "
            + "PG datasource found. Set quarkus.datasource.reactive.url or "
            + "csp.config.pg.enabled=false for dev without PG.");
      }
      LOG.warning("No reactive PG datasource available — config bootstrap skipped (L5a requires PG)");
      return;
    }

    Pool pgPool = pgPoolInstance.get();
    if (pgPool == null) {
      LOG.warning("Reactive PG pool is null — config bootstrap skipped");
      return;
    }

    // --- Securite event-loop : ne jamais bloquer un event-loop ---
    // StartupEvent est emis sur le thread principal Quarkus (pas un event-loop).
    // Bloquer avec get(timeout) y est sur. On verifie quand meme.
    if (Context.isOnEventLoopThread()) {
      LOG.severe("CspBootstrap.onStart called on Vert.x event-loop thread — blocking load may stall! "
          + "Current context isEventLoop=" + isCurrentEventLoopContext());
    }
    Vertx curCtxVertx = null;
    try {
      io.vertx.core.Context ctx = Vertx.currentContext();
      if (ctx != null && ctx.isEventLoopContext()) {
        LOG.warning("Current Vert.x context is event-loop — blocking with timeout "
            + LOAD_TIMEOUT_SECONDS + "s (may delay event-loop)");
      }
    } catch (Exception e) {
      // ignore context check failure
    }

    // L5a : charger et valider le snapshot de facon SYNCHRONE (fail-fast)
    ConfigRepository repository = createRepository(pgPool);
    ConfigSnapshot snapshot = loadSnapshotSynchronously(repository);

    // Valider avant de publier — fail-fast dans TOUS les profils quand PG active
    List<String> errors = ConfigValidator.validate(snapshot);
    if (!errors.isEmpty()) {
      String msg = "Config validation failed: " + String.join("; ", errors);
      throw new IllegalStateException("FAIL-FAST: " + msg);
    }

    holder.set(snapshot);
    LOG.info("Initial ConfigSnapshot loaded: "
        + snapshot.profiles().size() + " profiles, "
        + snapshot.connectors().size() + " connectors (holder ready before return)");

    // Demarrer le watcher (LISTEN + fallback). Son echec initial est tolere
    // car le fallback timer et la reconnexion exponentielle prennent le relais,
    // mais le snapshot initial est deja charge (fail-fast ci-dessus).
    this.watcher = createWatcher(repository, holder, pgPool, vertx);
    startWatcherSynchronously(watcher);
  }

  private ConfigSnapshot loadSnapshotSynchronously(ConfigRepository repository) {
    try {
      return repository.loadAll()
          .toCompletionStage().toCompletableFuture()
          .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (!"test".equals(quarkusProfile)) {
        throw new IllegalStateException(
            "FAIL-FAST: Interrupted while loading initial ConfigSnapshot from PG. "
            + "Set csp.config.pg.enabled=false to bypass (dev only).", e);
      }
      LOG.log(Level.WARNING, "Interrupted while loading ConfigSnapshot — continuing (test profile)", e);
      throw new IllegalStateException("Interrupted in test profile", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (!"test".equals(quarkusProfile)) {
        throw new IllegalStateException(
            "FAIL-FAST: Cannot load initial ConfigSnapshot from PG. "
            + "Check PG connectivity, credentials, and Flyway migration. "
            + "Set csp.config.pg.enabled=false to bypass (dev only). "
            + "Cause: " + cause.getMessage(), cause);
      }
      // En profile test, on propage quand meme si l'appelant attend une exception ?
      // Le contrat pour les tests unitaires : pgEnabled=true + load failure -> onStart doit lever
      // meme en profile test. On distingue : si on est appele depuis un test unitaire
      // qui a explicitement active pgEnabled, on doit lever. La variable mere garde
      // le comportement legacy "continuer en test" seulement pour le QuarkusTest global sans PG.
      // Pour respecter la demande "PG active + load failure -> onStart leve", on leve toujours
      // quand pgEnabled=true et que le repository echoue, independamment du profile.
      // Le cas "PG desactive -> startup passe" est gere plus haut.
      // Ici on est dans pgEnabled=true + echec reel -> lever meme en test pour prouver fail-fast.
      throw new IllegalStateException(
          "FAIL-FAST (test): Cannot load initial ConfigSnapshot from PG. Cause: " + cause.getMessage(), cause);
    } catch (TimeoutException e) {
      if (!"test".equals(quarkusProfile)) {
        throw new IllegalStateException(
            "FAIL-FAST: Timeout (" + LOAD_TIMEOUT_SECONDS + "s) loading initial ConfigSnapshot from PG. "
            + "Check PG connectivity. Set csp.config.pg.enabled=false to bypass (dev only).", e);
      }
      throw new IllegalStateException(
          "FAIL-FAST (test): Timeout loading ConfigSnapshot after " + LOAD_TIMEOUT_SECONDS + "s", e);
    }
  }

  private void startWatcherSynchronously(ConfigWatcher w) {
    try {
      w.start()
          .toCompletionStage().toCompletableFuture()
          .get(WATCHER_START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      LOG.info("ConfigWatcher started (LISTEN + fallback active)");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.log(Level.WARNING, "Interrupted while starting ConfigWatcher — fallback will retry", e);
    } catch (ExecutionException e) {
      // ConfigWatcher.start() ne devrait jamais echouer via ExecutionException
      // (il complete meme quand LISTEN echoue et schedule reconnect). Si on arrive ici
      // c'est une erreur inattendue — log warning, le fallback/reconnect reste actif.
      LOG.log(Level.WARNING,
          "ConfigWatcher start failed (unexpected) — fallback/reconnect will retry: "
          + e.getCause(), e.getCause());
    } catch (TimeoutException e) {
      LOG.log(Level.WARNING,
          "ConfigWatcher start timed out after " + WATCHER_START_TIMEOUT_SECONDS
          + "s — fallback/reconnect will retry", e);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "ConfigWatcher start error — fallback/reconnect will retry: " + e.getMessage(), e);
    }
  }

  private boolean isCurrentEventLoopContext() {
    try {
      io.vertx.core.Context ctx = Vertx.currentContext();
      return ctx != null && ctx.isEventLoopContext();
    } catch (Exception e) {
      return false;
    }
  }

  /** Factory pour tests : permet de mocker le repository. */
  protected ConfigRepository createRepository(Pool pool) {
    return new ConfigRepository(pool);
  }

  /** Factory pour tests : permet de mocker le watcher. */
  protected ConfigWatcher createWatcher(ConfigRepository repo, ConfigHolder h, Pool pool, Vertx v) {
    return new ConfigWatcher(repo, h, pool, v, 300_000L);
  }

  public void onStop(@Observes @Priority(400) ShutdownEvent event) {
    if (watcher != null) {
      LOG.info("Stopping ConfigWatcher...");
      watcher.stop().onComplete(ar -> LOG.info("ConfigWatcher stopped"));
    } else {
      LOG.fine("No ConfigWatcher to stop");
    }
  }
}
