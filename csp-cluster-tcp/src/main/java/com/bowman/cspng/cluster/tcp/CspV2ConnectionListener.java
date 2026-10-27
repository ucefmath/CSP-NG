package com.bowman.cspng.cluster.tcp;

import com.bowman.cspng.codec.cspv2.CspV2Payload;
import io.vertx.core.Future;

/**
 * Listener minimal async pour les ECM entrants.
 * established/closed ont des defaults no-op.
 */
public interface CspV2ConnectionListener {

  /**
   * Appelé pour chaque ECM_REQUEST entrant validé (parité, monotonicité, profil, rate-limit passés).
   * Doit retourner un Future<DcwReply> ; timeout maxWait, exceptions/non-réponse => NOT_FOUND géré par le moteur.
   */
  Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId, CspV2Payload.EcmRequest request);

  /** Retour non-checksummé uniquement après intersection des capacités et validation métier du listener. */
  default Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId, CspV2Payload.EcmRequest request,
                                                     boolean checksumPolicyNegotiated) {
    return onEcmRequest(peerNodeId, request);
  }

  /** Validation à la frontière avant émission ; par défaut les implémentations anciennes restent strictes. */
  default boolean acceptsReply(String peerNodeId, CspV2Payload.EcmRequest request,
                               CspV2Payload.DcwReply reply, boolean checksumPolicyNegotiated) {
    return reply.status() != CspV2Payload.DcwStatus.OK ||
        com.bowman.cspng.codec.cspv2.CspV2Codec.checksumDcw(reply.cw());
  }

  /** Validation à la frontière après décodage, avant livraison ; défaut strict. */
  default boolean acceptsInboundReply(String peerNodeId, CspV2Payload.EcmRequest request,
                                      CspV2Payload.DcwReply reply, boolean checksumPolicyNegotiated) {
    return reply.status() != CspV2Payload.DcwStatus.OK ||
        com.bowman.cspng.codec.cspv2.CspV2Codec.checksumDcw(reply.cw());
  }

  default void onEstablished(String peerNodeId) {}

  default void onClosed(String peerNodeId, String reason) {}
}
