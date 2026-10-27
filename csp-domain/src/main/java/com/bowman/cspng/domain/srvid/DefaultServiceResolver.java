package com.bowman.cspng.domain.srvid;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Implémentation immuable, thread-safe et à lookup O(1) de {@link ServiceResolver}.
 */
public final class DefaultServiceResolver implements ServiceResolver {

  private final Map<ServiceKey, ServiceInfo> services;
  private final Map<ProviderKey, ProviderInfo> providers;

  public DefaultServiceResolver(Map<ServiceKey, ServiceInfo> services,
                                Map<ProviderKey, ProviderInfo> providers) {
    this.services = services != null ? Map.copyOf(services) : Collections.emptyMap();
    this.providers = providers != null ? Map.copyOf(providers) : Collections.emptyMap();
  }

  @Override
  public String resolveChannelName(int caid, int sid) {
    ServiceInfo info = resolveService(caid, sid);
    return info != null ? info.displayName() : null;
  }

  @Override
  public ServiceInfo resolveService(int caid, int sid) {
    return services.get(new ServiceKey(caid, sid));
  }

  @Override
  public String resolveProviderName(int caid, int provid) {
    ProviderInfo info = resolveProvider(caid, provid);
    return info != null ? info.displayName() : null;
  }

  @Override
  public ProviderInfo resolveProvider(int caid, int provid) {
    int c = caid & 0xFFFF;
    int p = provid & 0xFFFFFF;
    ProviderInfo exact = providers.get(new ProviderKey(c, p));
    if (exact != null) {
      return exact;
    }
    if (p != 0) {
      return providers.get(new ProviderKey(c, 0));
    }
    return null;
  }

  @Override
  public int serviceCount() {
    return services.size();
  }

  @Override
  public int providerCount() {
    return providers.size();
  }
}
