package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.config.CwCycleConfig;
import com.bowman.cspng.filters.CwCycleChecker;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service CW-cycle (P4) : détient le {@link CwCycleChecker} construit depuis la
 * config V15 ({@code csp_cw_cycle_config}) et le RECONSTRUIT à chaque reload
 * (event bus {@code CONFIG_RELOADED}) — les paramètres (maxList, keepTime,
 * onBad, dropOld, usePeerCycleMetadata) sont toujours actuels, jamais fictifs.
 *
 * <p>Compteurs réels exposés via {@code GET /api/admin/dcw/stats} (clé cwCycle).
 * Le checker est sans effet tant que {@code enabled=false} : les appelants
 * (router, connecteurs, sessions) appliquent la gate enabled/caids AVANT check.
 */
@ApplicationScoped
public class CwCycleService {

  private static final Logger LOG = Logger.getLogger(CwCycleService.class.getName());

  @Inject
  ConfigHolder holder;

  @Inject
  Vertx vertx;

  /** Instance STABLE (bean singleton produit) — reconfigurée en place à chaque reload. */
  private final CwCycleChecker checker = new CwCycleChecker(
      CwCycleConfig.DEFAULT.enabled(), CwCycleConfig.DEFAULT.caids(),
      CwCycleConfig.DEFAULT.maxList(), CwCycleConfig.DEFAULT.keepTimeSec(),
      CwCycleConfig.DEFAULT.onBad(), CwCycleConfig.DEFAULT.dropOld(),
      CwCycleConfig.DEFAULT.usePeerCycleMetadata());
  private volatile CwCycleConfig config = CwCycleConfig.DEFAULT;
  private MessageConsumer<Object> reloadConsumer;

  /** Producer CDI : le MÊME checker est injecté partout (router, connecteurs, sessions, API). */
  @jakarta.enterprise.inject.Produces
  @jakarta.inject.Singleton
  public CwCycleChecker cwCycleChecker() {
    return checker;
  }

  public void onStart(@Observes @Priority(350) StartupEvent event) {
    rebuild();
    reloadConsumer = vertx.eventBus().consumer(ConfigWatcher.CONFIG_RELOADED_ADDRESS,
        msg -> rebuild());
    LOG.info("CwCycleService started — enabled=" + config.enabled()
        + " maxList=" + config.maxList() + " keepTimeSec=" + config.keepTimeSec()
        + " onBad=" + config.onBad());
  }

  public void onStop(@Observes @Priority(150) ShutdownEvent event) {
    if (reloadConsumer != null) {
      reloadConsumer.unregister();
      reloadConsumer = null;
    }
  }

  private void rebuild() {
    try {
      if (holder == null || !holder.isInitialized()) {
        return;
      }
      ConfigSnapshot snap = holder.get();
      CwCycleConfig cfg = snap.cwCycle() != null ? snap.cwCycle() : CwCycleConfig.DEFAULT;
      this.config = cfg;
      // Reconfiguration EN PLACE : l'identité du bean injecté reste stable,
      // l'historique est réinitialisé (les paramètres changent la sémantique).
      checker.reconfigure(cfg.enabled(), cfg.caids(), cfg.maxList(), cfg.keepTimeSec(),
          cfg.onBad(), cfg.dropOld(), cfg.usePeerCycleMetadata());
      LOG.fine("CwCycleChecker reconfigured from config v" + cfg.version()
          + " (enabled=" + cfg.enabled() + ")");
    } catch (Exception e) {
      LOG.log(Level.WARNING, "CwCycleChecker rebuild failed — keeping previous", e);
    }
  }

  /** Checker courant (null avant le premier build — les appelants gates null). */
  public CwCycleChecker checker() {
    return checker;
  }

  /** Config CW-cycle courante (enabled/caids portés par l'appelant). */
  public CwCycleConfig config() {
    return config;
  }
}
