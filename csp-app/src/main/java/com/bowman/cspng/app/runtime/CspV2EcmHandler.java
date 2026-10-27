package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.EcmProviderExtractor;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.connectors.RelayDiagnostic;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.cluster.tcp.CspV2ConnectionListener;
import io.vertx.core.Future;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Handler ECM entrant CSPv2 — convertit le wire en domaine, interroge le
 * connector manager par candidats CAID/provider, et renvoie une réponse wire.
 *
 * <p>Ne JAMAIS appeler cache.handleRemoteRequest/processRequest/handleReply/addTransport.
 */
public class CspV2EcmHandler implements CspV2ConnectionListener {

    private static final Logger LOG = Logger.getLogger(CspV2EcmHandler.class.getName());

    @FunctionalInterface
    interface RequestResolver {
        Future<CwReply> resolve(EcmRequest request, int provider, long reqId);
    }

    private final CspV2DomainMapper mapper;
    private final boolean exposeConnectorName;
    private volatile java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> dcwPolicySupplier;

    public void setDcwPolicySupplier(java.util.function.Supplier<com.bowman.cspng.domain.dcw.DcwQualityPolicy> supplier) {
        this.dcwPolicySupplier = supplier;
    }

    // Strategies final/private
    private final Function<EcmKey, CwReply> cacheLookup;
    private final RequestResolver connectorResolver;

    /** Constructeur de production avec CardDiscoveryService — câble cache, connector manager, discovery, mapper. */
    public CspV2EcmHandler(CacheVerticle cacheVerticle, CwsConnectorManager connectorManager,
                           CardDiscoveryService discoveryService,
                           CspV2DomainMapper mapper, boolean exposeConnectorName) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.exposeConnectorName = exposeConnectorName;
        Objects.requireNonNull(cacheVerticle, "cacheVerticle");
        Objects.requireNonNull(connectorManager, "connectorManager");
        this.cacheLookup = cacheVerticle::peekLocalCache;
        this.connectorResolver = (request, provider, reqId) -> {
            try {
                return connectorManager.sendEcmRequest(request, provider, discoveryService, "wildcard", reqId);
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        };
    }

    /** Constructeur de production sans discovery service (fallback connecteurs seuls). */
    public CspV2EcmHandler(CacheVerticle cacheVerticle, CwsConnectorManager connectorManager,
                           CspV2DomainMapper mapper, boolean exposeConnectorName) {
        this(cacheVerticle, connectorManager, null, mapper, exposeConnectorName);
    }

    /** Constructeur package-private pour tests (avec resolver unaire). */
    CspV2EcmHandler(CspV2DomainMapper mapper, boolean exposeConnectorName,
                    Function<EcmKey, CwReply> cacheLookup,
                    Function<EcmRequest, Future<CwReply>> connectorResolver) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.exposeConnectorName = exposeConnectorName;
        this.cacheLookup = Objects.requireNonNull(cacheLookup, "cacheLookup");
        Objects.requireNonNull(connectorResolver, "connectorResolver");
        this.connectorResolver = (req, prov, reqId) -> connectorResolver.apply(req);
    }

    /** Constructeur package-private pour tests (avec resolver binaire req + provider). */
    CspV2EcmHandler(CspV2DomainMapper mapper, boolean exposeConnectorName,
                    Function<EcmKey, CwReply> cacheLookup,
                    BiFunction<EcmRequest, Integer, Future<CwReply>> connectorResolver) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.exposeConnectorName = exposeConnectorName;
        this.cacheLookup = Objects.requireNonNull(cacheLookup, "cacheLookup");
        Objects.requireNonNull(connectorResolver, "connectorResolver");
        this.connectorResolver = (req, prov, reqId) -> connectorResolver.apply(req, prov);
    }

    /** Constructeur package-private pour tests (avec RequestResolver). */
    CspV2EcmHandler(CspV2DomainMapper mapper, boolean exposeConnectorName,
                    Function<EcmKey, CwReply> cacheLookup,
                    RequestResolver connectorResolver) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.exposeConnectorName = exposeConnectorName;
        this.cacheLookup = Objects.requireNonNull(cacheLookup, "cacheLookup");
        this.connectorResolver = Objects.requireNonNull(connectorResolver, "connectorResolver");
    }

    @Override
    public Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId,
                                                        CspV2Payload.EcmRequest wireRequest) {
        return onEcmRequest(peerNodeId, wireRequest, false);
    }

    @Override
    public Future<CspV2Payload.DcwReply> onEcmRequest(String peerNodeId,
                                                       CspV2Payload.EcmRequest wireRequest,
                                                       boolean checksumPolicyNegotiated) {
        long startNs = System.nanoTime();
        long reqId = RelayDiagnostic.nextReqId();

        EcmRequest domainRequest;
        try {
            domainRequest = mapper.toDomain(wireRequest, peerNodeId);
        } catch (Exception e) {
            RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
            return notFound(wireRequest);
        }

        int caid = wireRequest.caId();
        int sid = wireRequest.serviceId();
        var ctx = EcmProviderExtractor.extractContext(wireRequest.caId(), wireRequest.ecm());
        int provider = ctx.routingProvider();
        int policyProvider = ctx.policyProvider();

        // Cache lookup — ne retourner immédiatement que si status OK
        CwReply cached;
        try {
            cached = cacheLookup.apply(domainRequest.key());
        } catch (Exception e) {
            cached = null;
        }
        var policy = (dcwPolicySupplier != null) ? dcwPolicySupplier.get() : com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
        if (policy == null) policy = com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
        final var activePolicy = policy;
        // CAP négociée autorise le wire sans checksum, pas une relaxation globale.
        // La garde historique reste stricte ; seule une règle scopée IGNORE/REJECT_VALID s'applique.
        var guard = com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED;
        if (cached != null && !cached.isEmpty() && cached.cw() != null && cached.cw().length == 16
                && activePolicy.validate(cached.cw(), domainRequest.profileName(), caid, policyProvider, sid,
                        cached.connectorName(), guard).isValid()) {
            try {
                CspV2Payload.DcwReply wireReply = mapper.toWire(cached, wireRequest, exposeConnectorName,
                        checksumPolicyNegotiated);
                if (wireReply.status() == CspV2Payload.DcwStatus.OK) {
                    RelayDiagnostic.logCacheHit(reqId, caid, provider, sid);
                    RelayDiagnostic.logHandlerOk(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                    return Future.succeededFuture(wireReply);
                }
                // Collision: cache hit but fields mismatch → fallthrough connector
                RelayDiagnostic.logCacheCollision(reqId, caid, provider, sid);
            } catch (Exception e) {
                // fall through to connector
                RelayDiagnostic.logCacheCollision(reqId, caid, provider, sid);
            }
        } else {
            RelayDiagnostic.logCacheMiss(reqId, caid, provider, sid);
        }

        // Connector resolver
        Future<CwReply> future;
        try {
            future = connectorResolver.resolve(domainRequest, provider, reqId);
        } catch (Exception e) {
            RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
            return notFound(wireRequest);
        }
        if (future == null) {
            RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
            return notFound(wireRequest);
        }

        return future
                .map(domainReply -> {
                    if (domainReply == null || domainReply.isEmpty()) {
                        RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                        return notFoundWire(wireRequest);
                    }
                    try {
                        if (!activePolicy.validate(domainReply.cw(), domainRequest.profileName(), caid, policyProvider,
                                sid, domainReply.connectorName(), guard).isValid()) {
                            return notFoundWire(wireRequest);
                        }
                        CspV2Payload.DcwReply wireReply = mapper.toWire(domainReply, wireRequest, exposeConnectorName,
                                checksumPolicyNegotiated);
                        if (wireReply.status() == CspV2Payload.DcwStatus.OK) {
                            RelayDiagnostic.logHandlerOk(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                        } else {
                            RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                        }
                        return wireReply;
                    } catch (Exception e) {
                        RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                        return notFoundWire(wireRequest);
                    }
                })
                .recover(err -> {
                    RelayDiagnostic.logHandlerNotFound(reqId, (System.nanoTime() - startNs) / 1_000_000L);
                    return Future.succeededFuture(notFoundWire(wireRequest));
                });
    }

    @Override
    public boolean acceptsReply(String peerNodeId, CspV2Payload.EcmRequest request,
                                CspV2Payload.DcwReply reply, boolean negotiated) {
        if (reply.status() != CspV2Payload.DcwStatus.OK) return true;
        var ctx = EcmProviderExtractor.extractContext(request.caId(), request.ecm());
        var policy = dcwPolicySupplier != null ? dcwPolicySupplier.get() : com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
        if (policy == null) policy = com.bowman.cspng.domain.dcw.DcwQualityPolicy.DEFAULT;
        var guard = com.bowman.cspng.domain.dcw.LegacyGuard.CHECKSUM_REQUIRED;
        boolean policyOk = policy.validate(reply.cw(), request.profile(), request.caId(), ctx.policyProvider(),
                request.serviceId(), null, guard).isValid();
        if (!policyOk) return false;
        if (!negotiated) return com.bowman.cspng.codec.cspv2.CspV2Codec.checksumDcw(reply.cw());
        return reply.cw() != null && reply.cw().length == 16;
    }

    @Override
    public void onEstablished(String peerNodeId) { /* no-op */ }

    @Override
    public void onClosed(String peerNodeId, String reason) { /* no-op */ }

    private static Future<CspV2Payload.DcwReply> notFound(CspV2Payload.EcmRequest req) {
        return Future.succeededFuture(notFoundWire(req));
    }

    static CspV2Payload.DcwReply notFoundWire(CspV2Payload.EcmRequest req) {
        return new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(),
                req.networkId(), req.caId(), req.dataHash(),
                0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    }
}
