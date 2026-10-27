package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;

/**
 * Enrichissement local d'une {@link CwReply} dans le cache L1.
 *
 * <p>Le legacy attachait des champs transients ({@code profileName}, {@code networkId})
 * à la réponse mutable. {@link CwReply} étant un record immuable, ce wrapper conserve le
 * {@code profileName} hérité sans toucher au domaine partagé.
 */
public record CachedReply(CwReply reply, String profileName) {

  public CachedReply {
    if (reply == null) throw new NullPointerException("reply");
  }

  /**
   * Crée une copie de la réponse avec un nouveau networkId si celui-ci diffère.
   */
  public CwReply withNetworkId(int networkId) {
    EcmKey key = reply.key();
    if (key.networkId() == networkId) return reply;
    EcmKey newKey = new EcmKey(key.commandTag(), key.serviceId(), networkId, key.caId(), key.dataHash());
    return new CwReply(newKey, reply.commandTag(), reply.cw(), reply.connectorName(), reply.originAddress(), reply.timestamp());
  }
}
