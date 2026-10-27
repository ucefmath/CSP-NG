package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheTransport;
import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Transport sortant CSPv2 — reçoit les ECM du cache pour broadcast, les envoie
 * via {@code manager.requestWithPeer}, et réinjecte les réponses valides avec
 * provenance {@code cspv2:<peerNodeId>}.
 *
 * <p>onReply est NOOP. Null-safe fail-closed.
 */
public class CspV2CacheTransport implements CacheTransport {

    private static final Logger LOG = Logger.getLogger(CspV2CacheTransport.class.getName());

    private final CspV2DomainMapper mapper;

    // Stratégies private final
    private final Function<CspV2Payload.EcmRequest, Future<CspV2Manager.CspV2PeerReply>> wireRequester;
    private final BiConsumer<EcmRequest, CwReply> cacheReply;

    /** Constructeur de production — câble manager::requestWithPeer et cache.handleReply. */
    public CspV2CacheTransport(CspV2Manager manager, CacheVerticle cacheVerticle,
                               CspV2DomainMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(cacheVerticle, "cacheVerticle");
        this.wireRequester = wireReq -> {
            try {
                return manager.requestWithPeer(wireReq);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        };
        this.cacheReply = (req, reply) -> {
            try {
                cacheVerticle.handleReply(req, reply);
            } catch (Exception e) {
                LOG.fine("cache.handleReply failed: " + e.getMessage());
            }
        };
    }

    /** Constructeur package-private pour tests. */
    CspV2CacheTransport(CspV2DomainMapper mapper,
                        Function<CspV2Payload.EcmRequest, Future<CspV2Manager.CspV2PeerReply>> wireRequester,
                        BiConsumer<EcmRequest, CwReply> cacheReply) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.wireRequester = Objects.requireNonNull(wireRequester, "wireRequester");
        this.cacheReply = Objects.requireNonNull(cacheReply, "cacheReply");
    }

    @Override
    public void onRequest(EcmRequest request) {
        if (request == null) return;
        CspV2Payload.EcmRequest wireRequest;
        try {
            wireRequest = mapper.toWire(request);
        } catch (Exception e) {
            LOG.warning("CspV2CacheTransport toWire failed: " + e.getMessage());
            return;
        }
        Future<CspV2Manager.CspV2PeerReply> future;
        try {
            future = wireRequester.apply(wireRequest);
        } catch (Exception e) {
            LOG.warning("CspV2CacheTransport wireRequester failed: " + e.getMessage());
            return;
        }
        if (future == null) return;
        future.onComplete(ar -> {
            if (ar.failed()) return;
            CspV2Manager.CspV2PeerReply peerReply = ar.result();
            if (peerReply == null) return;
            CspV2Payload.DcwReply wireReply = peerReply.reply();
            if (wireReply == null || wireReply.status() != CspV2Payload.DcwStatus.OK) return;
            CwReply domainReply;
            try {
                domainReply = mapper.toDomain(wireReply, wireRequest, peerReply.peerNodeId());
            } catch (Exception e) {
                return;
            }
            if (domainReply == null || domainReply.isEmpty()) return;
            try {
                cacheReply.accept(request, domainReply);
            } catch (Exception e) {
                // fail closed
            }
        });
    }

    @Override
    public void onReply(CwReply reply) {
        // NOOP explicite
    }
}
