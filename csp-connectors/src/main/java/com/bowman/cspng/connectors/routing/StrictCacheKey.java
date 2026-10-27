package com.bowman.cspng.connectors.routing;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * Clé de cache stricte partitionnée par IDENTITÉ COMPLÈTE (P7).
 *
 * <p>Contrats P7 (docs/INBOUND-CAMS-PLAN.md §P7) :
 * <ul>
 *   <li><b>Partition intégrale</b> : profile, caid, provider, serviceId, commandTag,
 *       <b>exactBody (comparaison octet par octet)</b>, policyEpoch, et eligibleConnectorSet.</li>
 *   <li><b>Anti-poisoning</b> : deux requêtes avec le même hash 32 bits mais des corps
 *       différents ne collisionnent JAMAIS.</li>
 *   <li><b>Isolation ACL inter-utilisateurs</b> : deux utilisateurs avec des ensembles
 *       de connecteurs autorisés distincts ne partagent pas aveuglément le cache.</li>
 *   <li><b>Invalidation par epoch</b> : tout bump d'epoch isole immédiatement le cache.</li>
 * </ul>
 */
public final class StrictCacheKey {

  private final String profile;
  private final int caid;
  private final int provider;
  private final int serviceId;
  private final int commandTag;
  private final byte[] exactBody;
  private final long policyEpoch;
  private final Set<String> eligibleConnectors;
  private final int hashCode;

  public StrictCacheKey(String profile, int caid, int provider, int serviceId, int commandTag,
                        byte[] exactBody, long policyEpoch, Set<String> eligibleConnectors) {
    this.profile = Objects.requireNonNull(profile, "profile");
    this.caid = caid;
    this.provider = provider;
    this.serviceId = serviceId;
    this.commandTag = commandTag;
    this.exactBody = exactBody != null ? exactBody.clone() : new byte[0];
    this.policyEpoch = policyEpoch;
    this.eligibleConnectors = eligibleConnectors != null ? Set.copyOf(eligibleConnectors) : Set.of();

    int h = Objects.hash(profile, caid, provider, serviceId, commandTag, policyEpoch, this.eligibleConnectors);
    h = 31 * h + Arrays.hashCode(this.exactBody);
    this.hashCode = h;
  }

  public String profile() { return profile; }
  public int caid() { return caid; }
  public int provider() { return provider; }
  public int serviceId() { return serviceId; }
  public int commandTag() { return commandTag; }
  public byte[] exactBody() { return exactBody.clone(); }
  public long policyEpoch() { return policyEpoch; }
  public Set<String> eligibleConnectors() { return eligibleConnectors; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof StrictCacheKey that)) return false;
    return caid == that.caid
        && provider == that.provider
        && serviceId == that.serviceId
        && commandTag == that.commandTag
        && policyEpoch == that.policyEpoch
        && profile.equals(that.profile)
        && Arrays.equals(exactBody, that.exactBody)
        && eligibleConnectors.equals(that.eligibleConnectors);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }
}
