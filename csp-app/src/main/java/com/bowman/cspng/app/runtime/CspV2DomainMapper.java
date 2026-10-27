package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cspv2.CspV2Constants;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Mapper pur, sans état, entre le domaine CSP et le wire CSPv2.
 * Pattern précompilé ; jamais EcmKey.equals.
 */
public final class CspV2DomainMapper {

    public static final String PROVENANCE = "cspv2:";

    private static final Pattern PEER_PATTERN = Pattern.compile(CspV2Constants.NODE_ID_PATTERN.pattern());

    private final LongSupplier clock;

    public CspV2DomainMapper() {
        this(System::currentTimeMillis);
    }

    public CspV2DomainMapper(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -- helper --

    public static String originId(String peerNodeId) {
        validatePeerNodeId(peerNodeId);
        return PROVENANCE + peerNodeId;
    }

    public static void validatePeerNodeId(String peerNodeId) {
        if (peerNodeId == null || peerNodeId.isBlank()
                || !PEER_PATTERN.matcher(peerNodeId).matches()) {
            throw new IllegalArgumentException("invalid peerNodeId");
        }
    }

    // -- wire ECM → domain EcmRequest --

    public EcmRequest toDomain(CspV2Payload.EcmRequest request, String peerNodeId) {
        validatePeerNodeId(peerNodeId);
        Objects.requireNonNull(request, "request");
        byte[] rawEcm = request.ecm();
        if (rawEcm == null || rawEcm.length < CspV2Constants.MIN_ECM_LEN) {
            throw new IllegalArgumentException("ecm too short");
        }
        byte[] body = Arrays.copyOfRange(rawEcm, 3, rawEcm.length);
        EcmKey key = new EcmKey(request.commandTag(), request.serviceId(),
                request.networkId(), request.caId(), request.dataHash());
        String origin = originId(peerNodeId);
        return new EcmRequest(key, body, request.profile(),
                origin, origin, clock.getAsLong(), null, request.maxWaitMs());
    }

    // -- domain → wire ECM_REQUEST --

    public CspV2Payload.EcmRequest toWire(EcmRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] body = request.body();
        if (body.length < Math.max(0, CspV2Constants.MIN_ECM_LEN - 3)
                || body.length > CspV2Constants.MAX_ECM_LEN - 3) {
            throw new IllegalArgumentException("ecm body length out of bounds");
        }
        String profile = request.profileName();
        if (profile == null || profile.isBlank()) {
            throw new IllegalArgumentException("profile must not be blank");
        }
        int profLen = profile.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (profLen < CspV2Constants.MIN_PROFILE_LEN
                || profLen > CspV2Constants.MAX_PROFILE_LEN) {
            throw new IllegalArgumentException("profile length out of bounds");
        }
        long maxWait = request.maxWaitMs();
        if (maxWait < CspV2Constants.MIN_MAX_WAIT_MS
                || maxWait > CspV2Constants.MAX_MAX_WAIT_MS) {
            throw new IllegalArgumentException("maxWait out of bounds");
        }
        EcmKey key = request.key();
        int computedHash = ecmHash(body, 0, body.length);
        if (computedHash != key.dataHash()) {
            throw new IllegalArgumentException("ecm hash mismatch");
        }
        verifyKeyBounds(key);
        byte[] wireEcm = CcCardCodec.reconstructSection(key.commandTag(), body);
        return new CspV2Payload.EcmRequest(key.commandTag(), key.serviceId(),
                key.networkId(), key.caId(), key.dataHash(), profile, wireEcm, maxWait);
    }

    // -- wire DCW_REPLY → domain CwReply --

    /**
     * Convertit une DCW_REPLY wire en CwReply domaine.
     * Compare manuellement les 5 champs reply vs request.
     * Si mismatch, non-OK, ou checksum/cw length invalide → CwReply vide avec key de request.
     */
    public CwReply toDomain(CspV2Payload.DcwReply reply,
                            CspV2Payload.EcmRequest request,
                            String peerNodeId) {
        validatePeerNodeId(peerNodeId);
        EcmKey key = new EcmKey(request.commandTag(), request.serviceId(),
                request.networkId(), request.caId(), request.dataHash());
        String origin = originId(peerNodeId);

        // Vérifier statut et champs
        if (reply.status() != CspV2Payload.DcwStatus.OK) {
            return emptyReply(key, origin);
        }
        // Comparaison manuelle des 5 champs
        if (reply.commandTag() != request.commandTag()
                || reply.serviceId() != request.serviceId()
                || reply.networkId() != request.networkId()
                || reply.caId() != request.caId()
                || reply.dataHash() != request.dataHash()) {
            return emptyReply(key, origin);
        }
        // Longueur CW + checksum
        if (reply.cw().length != CspV2Constants.CW_LEN_OK
                || !DcwValidator.checksumDcw(reply.cw())) {
            return emptyReply(key, origin);
        }

        String connectorName = reply.connectorName();
        if (connectorName != null && connectorName.isEmpty()) {
            connectorName = null;
        }
        return new CwReply(key, reply.replyTag(), reply.cw(),
                connectorName, origin, clock.getAsLong());
    }

    // -- domain reply → wire DCW_REPLY --

    /**
     * Encode un CwReply domaine en payload wire.
     * Si reply est null, invalide (champs, checksum) → NOT_FOUND avec replyTag=0.
     */
    public CspV2Payload.DcwReply toWire(CwReply reply,
                                         CspV2Payload.EcmRequest request,
                                         boolean exposeConnectorName) {
        return toWire(reply, request, exposeConnectorName, false);
    }

    /** Relaxation wire seulement après négociation mutuelle ; caller doit valider la policy avant. */
    public CspV2Payload.DcwReply toWire(CwReply reply,
                                         CspV2Payload.EcmRequest request,
                                         boolean exposeConnectorName,
                                         boolean checksumPolicyNegotiated) {
        if (reply == null) {
            return notFoundWire(request);
        }
        EcmKey key = reply.key();
        boolean valid = (key.commandTag() == request.commandTag())
                && (key.serviceId() == request.serviceId())
                && (key.networkId() == request.networkId())
                && (key.caId() == request.caId())
                && (key.dataHash() == request.dataHash())
                && reply.cw().length == CspV2Constants.CW_LEN_OK
                && (checksumPolicyNegotiated || DcwValidator.checksumDcw(reply.cw()));
        if (valid) {
            String cn = reply.connectorName();
            if (cn == null) cn = "";
            if (!exposeConnectorName) cn = "";
            return new CspV2Payload.DcwReply(request.commandTag(), request.serviceId(),
                    request.networkId(), request.caId(), request.dataHash(),
                    reply.commandTag(), CspV2Payload.DcwStatus.OK,
                    reply.cw(), cn);
        }
        return notFoundWire(request);
    }

    // -- internal --

    private CwReply emptyReply(EcmKey key, String origin) {
        return new CwReply(key, 0, new byte[0], null, origin, clock.getAsLong());
    }

    private static CspV2Payload.DcwReply notFoundWire(CspV2Payload.EcmRequest req) {
        return new CspV2Payload.DcwReply(req.commandTag(), req.serviceId(),
                req.networkId(), req.caId(), req.dataHash(),
                0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    }

    private static void verifyKeyBounds(EcmKey key) {
        if (key.commandTag() < 0 || key.commandTag() > 255) {
            throw new IllegalArgumentException("commandTag out of bounds");
        }
        if (key.serviceId() < 0 || key.serviceId() > 0xFFFF) {
            throw new IllegalArgumentException("serviceId out of bounds");
        }
        if (key.networkId() < 0 || key.networkId() > 0xFFFF) {
            throw new IllegalArgumentException("networkId out of bounds");
        }
        if (key.caId() < 0 || key.caId() > 0xFFFF) {
            throw new IllegalArgumentException("caId out of bounds");
        }
    }

    /** ECM hash — reproduit l'algorithme de CspV2Codec.ecmHash. */
    static int ecmHash(byte[] body, int offset, int length) {
        if (body == null) throw new IllegalArgumentException("body null");
        if (offset < 0 || length < 0 || offset + length > body.length) {
            throw new IllegalArgumentException("invalid offset/length");
        }
        int h = 0;
        for (int i = offset; i < offset + length; i++) {
            h = 31 * h + (body[i] & 0xFF);
        }
        return h;
    }
}
