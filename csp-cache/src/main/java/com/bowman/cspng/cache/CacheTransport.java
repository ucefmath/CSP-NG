package com.bowman.cspng.cache;

import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;

/**
 * SPI d'extension du cache (ex. bridge Infinispan L3 optionnel).
 *
 * <p>Principe d'architecture (plan §3.4) : {@code csp-cache} ne dépend JAMAIS
 * d'un transport secondaire ; ce sont les transports secondaires qui dépendent de
 * {@code csp-cache} et implémentent cette interface pour s'enregistrer.
 */
public interface CacheTransport {

  void onRequest(EcmRequest request);

  void onReply(CwReply reply);
}
