package com.bowman.cspng.config;

import java.util.Collections;
import java.util.List;

/**
 * Configuration proxy — facade immuable preservee pour compatibilite avec les tests existants.
 *
 * <p>Pour les nouveaux usages, preferer {@link ConfigSnapshot} et ses sous-records
 * qui offrent une modelisation complete de la configuration.
 */
public record ProxyConfig(String name, List<String> profiles) {

  public ProxyConfig {
    profiles = profiles != null ? List.copyOf(profiles) : Collections.emptyList();
  }

  /** Construct a minimal ProxyConfig from a full snapshot. */
  public static ProxyConfig fromSnapshot(ConfigSnapshot snapshot) {
    return new ProxyConfig(
        snapshot.root().proxyName(),
        snapshot.profiles().stream().map(ProfileConfig::name).toList()
    );
  }
}
