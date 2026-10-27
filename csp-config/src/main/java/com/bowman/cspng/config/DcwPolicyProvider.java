package com.bowman.cspng.config;

import com.bowman.cspng.domain.dcw.DcwQualityPolicy;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fournisseur de policy DCW compilée, mémoïsée par identité du {@link DcwPolicyConfig} source
 * (compilation une seule fois par snapshot, jamais par ECM). Si la compilation échoue (snapshot
 * incohérent contournant le validateur) : dernier bon conservé (au départ : DEFAULT = gardes
 * historiques), compteur incrémenté, log sans contenu sensible. Jamais d'exception vers le datapath.
 */
public final class DcwPolicyProvider implements Supplier<DcwQualityPolicy> {

  private static final Logger LOG = Logger.getLogger(DcwPolicyProvider.class.getName());

  private final Supplier<DcwPolicyConfig> source;
  private final AtomicLong failures = new AtomicLong();
  private volatile DcwPolicyConfig lastSource;
  private volatile DcwQualityPolicy lastGood = DcwQualityPolicy.DEFAULT;

  public DcwPolicyProvider(Supplier<DcwPolicyConfig> source) {
    this.source = source;
  }

  @Override
  public DcwQualityPolicy get() {
    DcwPolicyConfig cfg;
    try {
      cfg = source.get();
    } catch (RuntimeException e) {
      return lastGood;
    }
    if (cfg == null || cfg == lastSource) return lastGood;
    synchronized (this) {
      if (cfg == lastSource) return lastGood;
      try {
        lastGood = cfg.toDomain();
      } catch (RuntimeException e) {
        failures.incrementAndGet();
        LOG.log(Level.SEVERE, "DCW policy compilation failed; keeping last known good (version "
            + cfg.version() + "): " + e.getClass().getSimpleName());
      }
      lastSource = cfg;
      return lastGood;
    }
  }

  public long compileFailures() {
    return failures.get();
  }
}
