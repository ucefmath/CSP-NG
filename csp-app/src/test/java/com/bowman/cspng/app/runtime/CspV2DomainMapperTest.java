package com.bowman.cspng.app.runtime;

import com.bowman.cspng.cache.DcwValidator;
import com.bowman.cspng.codec.cccam.CcCardCodec;
import com.bowman.cspng.codec.cspv2.CspV2Constants;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CspV2DomainMapperTest {

    private static final String PEER = "peer01";
    private static final String ORIGIN = "cspv2:peer01";

    private final CspV2DomainMapper mapper = new CspV2DomainMapper(() -> 1000L);

    @Test void clockNonNull() {
        assertThrows(NullPointerException.class, () -> new CspV2DomainMapper(null));
    }

    // -- wire ECM → domain EcmRequest --

    @Test void wireEcmToDomainExactFields() {
        byte[] ecm = ecmBytes();
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wire = new CspV2Payload.EcmRequest(
                0x80, 0x1234, 0x5678, 0xABCD, hash, "profile1", ecm, 5000);
        EcmRequest domain = mapper.toDomain(wire, PEER);
        assertEquals(0x80, domain.key().commandTag());
        assertEquals(0x1234, domain.key().serviceId());
        assertEquals(0x5678, domain.key().networkId());
        assertEquals(0xABCD, domain.key().caId());
        assertEquals(hash, domain.key().dataHash());
        byte[] expectedBody = Arrays.copyOfRange(ecm, 3, ecm.length);
        assertArrayEquals(expectedBody, domain.body());
        assertEquals(hash, CspV2DomainMapper.ecmHash(domain.body(), 0, domain.body().length));
        assertEquals("profile1", domain.profileName());
        assertEquals(ORIGIN, domain.remoteAddress());
        assertEquals(ORIGIN, domain.originId());
        assertEquals(1000L, domain.timestamp());
        assertNull(domain.arbiterNumber());
        assertEquals(5000L, domain.maxWaitMs());
    }

    @Test void wireEcmToDomainDefensiveCopy() {
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wire = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        EcmRequest domain = mapper.toDomain(wire, PEER);
        ecm[3] = (byte) 0xFF;
        assertNotEquals(ecm[3], domain.body()[0]);
    }

    @Test void wireEcmToDomainRejectsInvalidPeer() {
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wire = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(wire, ""));
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(wire, "INVALID!"));
    }

    @Test void wireEcmToDomainRealViaccessVector() {
        byte[] ecmViaccess = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
        };
        int hash = CspV2DomainMapper.ecmHash(ecmViaccess, 3, ecmViaccess.length - 3);
        CspV2Payload.EcmRequest wire = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hash, "default", ecmViaccess, 5000);
        EcmRequest domain = mapper.toDomain(wire, PEER);
        byte[] expectedBody = Arrays.copyOfRange(ecmViaccess, 3, ecmViaccess.length);
        assertArrayEquals(expectedBody, domain.body());
        assertEquals(hash, domain.key().dataHash());
        assertEquals(hash, CspV2DomainMapper.ecmHash(domain.body(), 0, domain.body().length));
    }

    // -- domain → wire ECM_REQUEST --

    @Test void domainEcmToWireExactFields() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, hash);
        EcmRequest request = new EcmRequest(key, body, "profa", "remote1", "origin1", 0L, null, 5000);
        CspV2Payload.EcmRequest wire = mapper.toWire(request);
        assertEquals(0x80, wire.commandTag());
        assertEquals(0x100, wire.serviceId());
        assertEquals(0x200, wire.networkId());
        assertEquals(0x300, wire.caId());
        assertEquals(hash, wire.dataHash());
        assertEquals("profa", wire.profile());
        byte[] expectedWireEcm = CcCardCodec.reconstructSection(0x80, body);
        assertArrayEquals(expectedWireEcm, wire.ecm());
        assertEquals(5000L, wire.maxWaitMs());
    }

    @Test void domainEcmToWireHashMismatch() {
        byte[] body = ecmBytes(3);
        int correctHash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        EcmKey key = new EcmKey(0x80, 0, 0, 0, correctHash + 1);
        EcmRequest request = new EcmRequest(key, body, "p", "r", "o", 0L, null, 5000);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(request));
    }

    @Test void domainEcmToWireEmptyBodyAllowed() {
        byte[] emptyBody = new byte[0];
        int hash = CspV2DomainMapper.ecmHash(emptyBody, 0, 0);
        EcmKey key = new EcmKey(0x80, 0x100, 0x200, 0x300, hash);
        EcmRequest request = new EcmRequest(key, emptyBody, "profa", "remote1", "origin1", 0L, null, 5000);
        CspV2Payload.EcmRequest wire = mapper.toWire(request);
        assertEquals(0x80, wire.commandTag());
        assertEquals(hash, wire.dataHash());
        byte[] expectedWireEcm = CcCardCodec.reconstructSection(0x80, emptyBody);
        assertEquals(3, expectedWireEcm.length);
        assertArrayEquals(expectedWireEcm, wire.ecm());
    }

    @Test void domainEcmToWireBodyTooLong() {
        byte[] body = new byte[510];
        for (int i = 0; i < 510; i++) body[i] = (byte) i;
        EcmRequest request = new EcmRequest(new EcmKey(0x80, 0, 0, 0, 42), body, "p", "r", "o", 0L, null, 5000);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(request));
    }

    @Test void domainEcmToWireBlankProfile() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        EcmKey key = new EcmKey(0x80, 0, 0, 0, hash);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(new EcmRequest(key, body, null, "r", "o", 0L, null, 5000)));
    }

    @Test void domainEcmToWireProfileTooLong() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        EcmKey key = new EcmKey(0x80, 0, 0, 0, hash);
        String longProfile = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(new EcmRequest(key, body, longProfile, "r", "o", 0L, null, 5000)));
    }

    @Test void domainEcmToWireMaxWaitTooLow() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(new EcmRequest(new EcmKey(0x80, 0, 0, 0, hash), body, "p", "r", "o", 0L, null, 30)));
    }

    @Test void domainEcmToWireMaxWaitTooHigh() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(new EcmRequest(new EcmKey(0x80, 0, 0, 0, hash), body, "p", "r", "o", 0L, null, 35000)));
    }

    @Test void domainEcmToWireKeyBounds() {
        byte[] body = ecmBytes(3);
        int hash = CspV2DomainMapper.ecmHash(body, 0, body.length);
        assertThrows(IllegalArgumentException.class, () -> mapper.toWire(new EcmRequest(new EcmKey(256, 0, 0, 0, hash), body, "p", "r", "o", 0L, null, 5000)));
    }

    @Test void domainEcmToWireRealViaccessVector() {
        byte[] ecmViaccess = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] amputatedBody = Arrays.copyOfRange(ecmViaccess, 3, ecmViaccess.length);
        int hash = CspV2DomainMapper.ecmHash(amputatedBody, 0, amputatedBody.length);
        EcmKey key = new EcmKey(0x80, 0x0002, 0x0000, 0x0500, hash);
        EcmRequest domain = new EcmRequest(key, amputatedBody, "default", "rem", "orig", 0L, null, 5000);
        CspV2Payload.EcmRequest wire = mapper.toWire(domain);
        assertArrayEquals(ecmViaccess, wire.ecm());
        assertEquals(hash, wire.dataHash());
    }

    @Test void wireToDomainToWireRoundTrip() {
        byte[] ecmViaccess = new byte[] {
            (byte) 0x80, (byte) 0x70, (byte) 0x13,
            (byte) 0x40, (byte) 0x02, (byte) 0x00, (byte) 0x00,
            (byte) 0x90, (byte) 0x03, (byte) 0x03, (byte) 0x28, (byte) 0x30,
            (byte) 0x80, (byte) 0x08, 0, 0, 0, 0, 0, 0, 0, 0
        };
        int hash = CspV2DomainMapper.ecmHash(ecmViaccess, 3, ecmViaccess.length - 3);
        CspV2Payload.EcmRequest wireIn = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hash, "default", ecmViaccess, 5000);
        EcmRequest domain = mapper.toDomain(wireIn, PEER);
        CspV2Payload.EcmRequest wireOut = mapper.toWire(domain);
        assertEquals(wireIn.commandTag(), wireOut.commandTag());
        assertEquals(wireIn.serviceId(), wireOut.serviceId());
        assertEquals(wireIn.networkId(), wireOut.networkId());
        assertEquals(wireIn.caId(), wireOut.caId());
        assertEquals(wireIn.dataHash(), wireOut.dataHash());
        assertEquals(wireIn.profile(), wireOut.profile());
        assertArrayEquals(wireIn.ecm(), wireOut.ecm());
        assertEquals(wireIn.maxWaitMs(), wireOut.maxWaitMs());
    }

    @Test void wireToDomainToWireRoundTripEmptyBody() {
        byte[] headerOnly = new byte[] { (byte) 0x80, (byte) 0x70, (byte) 0x00 };
        int hash = CspV2DomainMapper.ecmHash(headerOnly, 3, 0);
        CspV2Payload.EcmRequest wireIn = new CspV2Payload.EcmRequest(
            0x80, 0x0002, 0x0000, 0x0500, hash, "default", headerOnly, 5000);
        EcmRequest domain = mapper.toDomain(wireIn, PEER);
        assertEquals(0, domain.body().length);
        CspV2Payload.EcmRequest wireOut = mapper.toWire(domain);
        assertArrayEquals(wireIn.ecm(), wireOut.ecm());
    }

    // -- wire DCW → domain CwReply (with full-field validation) --

    @Test void wireDcwOkValidToDomain() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x81, 0x0100, 0x0200, 0x0300, hash, "prof", ecm, 5000);
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x81, 0x0100, 0x0200, 0x0300, hash, 0x42, CspV2Payload.DcwStatus.OK, cw, "conn1");
        CwReply domain = mapper.toDomain(wireReply, wireReq, PEER);
        assertFalse(domain.isEmpty());
        assertEquals(0x42, domain.commandTag());
        assertArrayEquals(cw, domain.cw());
        assertEquals("conn1", domain.connectorName());
        assertEquals(ORIGIN, domain.originAddress());
    }

    @Test void wireDcwOkMismatchedFieldReturnsEmpty() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x81, 0x0100, 0x0200, 0x0300, hash, "p", ecm, 100);
        // Wrong serviceId in reply
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x81, 0x9999, 0x0200, 0x0300, hash, 0x01, CspV2Payload.DcwStatus.OK, cw, "c");
        CwReply domain = mapper.toDomain(wireReply, wireReq, PEER);
        assertTrue(domain.isEmpty());
    }

    @Test void wireDcwOkBadChecksumReturnsEmpty() {
        byte[] badCw = new byte[16];
        for (int i = 0; i < 16; i++) badCw[i] = (byte) (i + 1);
        badCw[3] = (byte) 0xFF;
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x80, 0, 0, 0, hash, 0, CspV2Payload.DcwStatus.OK, badCw, "");
        CwReply domain = mapper.toDomain(wireReply, wireReq, PEER);
        assertTrue(domain.isEmpty());
    }

    @Test void wireDcwNotFoundToDomainEmpty() {
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x80, 0, 0, 0, hash, 0, CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
        assertTrue(mapper.toDomain(wireReply, wireReq, PEER).isEmpty());
    }

    @Test void wireDcwDeniedToDomainEmpty() {
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x80, 0, 0, 0, hash, 0, CspV2Payload.DcwStatus.DENIED, new byte[0], "");
        assertTrue(mapper.toDomain(wireReply, wireReq, PEER).isEmpty());
    }

    @Test void wireDcwEmptyConnectorNameToNull() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        CspV2Payload.DcwReply wireReply = new CspV2Payload.DcwReply(0x80, 0, 0, 0, hash, 0x01, CspV2Payload.DcwStatus.OK, cw, "");
        CwReply domain = mapper.toDomain(wireReply, wireReq, PEER);
        assertNull(domain.connectorName());
    }

    // -- domain reply → wire DCW_REPLY --

    @Test void domainReplyToWireOk() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x81, 0x200, 0x300, 0x400, hash, "p", ecm, 5000);
        EcmKey key = new EcmKey(0x81, 0x200, 0x300, 0x400, hash);
        CwReply reply = new CwReply(key, 0x42, cw, "conn1", ORIGIN, 2000L);
        CspV2Payload.DcwReply wireReply = mapper.toWire(reply, wireReq, true);
        assertEquals(CspV2Payload.DcwStatus.OK, wireReply.status());
        assertEquals(0x42, wireReply.replyTag());
        assertEquals("conn1", wireReply.connectorName());
    }

    @Test void domainReplyToWireConnectorNameHidden() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x81, 0x200, 0x300, 0x400, hash, "p", ecm, 5000);
        EcmKey key = new EcmKey(0x81, 0x200, 0x300, 0x400, hash);
        CwReply reply = new CwReply(key, 0x42, cw, "conn1", ORIGIN, 2000L);
        CspV2Payload.DcwReply wireReply = mapper.toWire(reply, wireReq, false);
        assertEquals(CspV2Payload.DcwStatus.OK, wireReply.status());
        assertEquals("", wireReply.connectorName());
    }

    @Test void domainReplyToWireNullReply() {
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        CspV2Payload.DcwReply wireReply = mapper.toWire(null, wireReq, false);
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, wireReply.status());
        assertEquals(0, wireReply.replyTag());
    }

    @Test void domainReplyToWireInvalidCwNotFound() {
        byte[] badCw = new byte[16];
        for (int i = 0; i < 16; i++) badCw[i] = (byte) (i + 1);
        badCw[3] = (byte) 0xFF;
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x80, 0, 0, 0, hash, "p", ecm, 100);
        EcmKey key = new EcmKey(0x80, 0, 0, 0, hash);
        CwReply reply = new CwReply(key, 0, badCw, "c", ORIGIN, 0L);
        CspV2Payload.DcwReply wireReply = mapper.toWire(reply, wireReq, false);
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, wireReply.status());
    }

    @Test void domainReplyToWireKeyMismatchNotFound() {
        byte[] cw = createValidCw();
        byte[] ecm = ecmBytes(6);
        int hash = CspV2DomainMapper.ecmHash(ecm, 3, ecm.length - 3);
        CspV2Payload.EcmRequest wireReq = new CspV2Payload.EcmRequest(0x81, 0x200, 0x300, 0x400, hash, "p", ecm, 5000);
        EcmKey key = new EcmKey(0x81, 0x999, 0x300, 0x400, hash);
        CwReply reply = new CwReply(key, 0x42, cw, "c", ORIGIN, 0L);
        assertEquals(CspV2Payload.DcwStatus.NOT_FOUND, mapper.toWire(reply, wireReq, false).status());
    }

    // -- provenance / helpers --

    @Test void originIdValid() {
        assertEquals("cspv2:peer01", CspV2DomainMapper.originId("peer01"));
    }

    @Test void originIdRejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> CspV2DomainMapper.originId(""));
        assertThrows(IllegalArgumentException.class, () -> CspV2DomainMapper.originId("BAD!"));
    }

    @Test void validatePeerNodeId() {
        CspV2DomainMapper.validatePeerNodeId("node1");
        assertThrows(IllegalArgumentException.class, () -> CspV2DomainMapper.validatePeerNodeId("INVALID!"));
    }

    // -- helpers --

    private static byte[] createValidCw() {
        byte[] cw = new byte[16];
        for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);
        cw[3] = (byte) ((cw[0] + cw[1] + cw[2]) & 0xFF);
        cw[7] = (byte) ((cw[4] + cw[5] + cw[6]) & 0xFF);
        cw[11] = (byte) ((cw[8] + cw[9] + cw[10]) & 0xFF);
        cw[15] = (byte) ((cw[12] + cw[13] + cw[14]) & 0xFF);
        assertTrue(DcwValidator.checksumDcw(cw));
        return cw;
    }

    private static byte[] ecmBytes() { return ecmBytes(6); }

    private static byte[] ecmBytes(int len) {
        byte[] ecm = new byte[len];
        for (int i = 0; i < len; i++) ecm[i] = (byte) (0x80 + i);
        return ecm;
    }
}
