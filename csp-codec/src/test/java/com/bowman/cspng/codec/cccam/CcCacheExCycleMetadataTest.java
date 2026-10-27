package com.bowman.cspng.codec.cccam;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests bit-exact des métadonnées de cycle CW transportées par les push cacheex
 * CCcam (contrat v2 §5) : byte [18] = cycletime (&lt;128) avec bit 0x80 = flag
 * next-cw-cycle — miroir wire OSCam (module-cw-cycle-check.c l.360 :
 * « share over cacheex buf[18] bit 8 »).
 */
class CcCacheExCycleMetadataTest {

    private static final int E_FOUND = 0;

    private static byte[] validCw() {
        // CW structurée valide (checksum DVB sur les 8 derniers octets du premier demi-mot)
        byte[] cw = new byte[16];
        for (int i = 0; i < 8; i++) cw[i] = (byte) (i + 1);
        cw[0] = 0x11;
        cw[1] = 0x22;
        cw[2] = 0x33;
        cw[3] = 0x44;
        cw[4] = 0x55;
        cw[5] = 0x66;
        cw[6] = 0x00;
        cw[7] = 0x00;
        int checksum = 0;
        for (int i = 0; i < 7; i++) checksum += cw[i] & 0xFF;
        cw[7] = (byte) (checksum & 0xFF);
        for (int i = 8; i < 16; i++) cw[i] = (byte) (0xA0 + i);
        return cw;
    }

    @Test
    void encodeThenParseRoundTripsCycleMetadata() {
        byte[] cw = validCw();
        byte[] push = CcCacheExCodec.encodePush(0x0100, 0x00006A, 0x1234, E_FOUND, 0x80,
            new byte[16], 0xDEADBEEF, cw, List.of(new byte[8]), 10, true);

        // Bit-exact : [18] = cycletime | 0x80 quand next-cw-cycle est porté
        assertEquals(0x8A, push[18] & 0xFF, "byte[18] must be cycletime|0x80 (10|0x80)");
        assertEquals(0x80, push[19] & 0xFF, "byte[19] must be odd/even");

        CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(push);
        assertEquals(10, rec.cycleTimeSec());
        assertTrue(rec.nextCwCycle());
        assertEquals(0x0100, rec.caid());
        assertEquals(0x00006A, rec.prid());
        assertEquals(0x1234, rec.srvid());
        assertEquals(0xDEADBEEF, rec.dataHash());
        assertArrayEquals(cw, rec.cw());
    }

    @Test
    void cycleTimeWithoutFlagKeepsBit7Clear() {
        byte[] push = CcCacheExCodec.encodePush(0x0100, 0, 1, E_FOUND, 0x81,
            new byte[16], 42, validCw(), List.of(new byte[8]), 6, false);
        assertEquals(0x06, push[18] & 0xFF);
        CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(push);
        assertEquals(6, rec.cycleTimeSec());
        assertFalse(rec.nextCwCycle());
    }

    @Test
    void zeroCycleTimeMeansNoMetadata() {
        byte[] push = CcCacheExCodec.encodePush(0x0100, 0, 1, E_FOUND, 0x80,
            new byte[16], 42, validCw(), List.of(new byte[8]));
        assertEquals(0x00, push[18] & 0xFF, "legacy encodePush must keep [18] = 0");
        CcCacheExCodec.PushRecord rec = CcCacheExCodec.parsePush(push);
        assertEquals(0, rec.cycleTimeSec());
        assertFalse(rec.nextCwCycle());
    }

    @Test
    void cycletimeBoundsAreFailClosed() {
        assertThrows(IllegalArgumentException.class,
            () -> CcCacheExCodec.encodePush(0x0100, 0, 1, E_FOUND, 0x80,
                new byte[16], 42, validCw(), List.of(new byte[8]), 128, false));
        assertThrows(IllegalArgumentException.class,
            () -> CcCacheExCodec.encodePush(0x0100, 0, 1, E_FOUND, 0x80,
                new byte[16], 42, validCw(), List.of(new byte[8]), -1, false));
        assertThrows(IllegalArgumentException.class,
            () -> new CcCacheExCodec.PushRecord(1, 1, 1, 0, 0x80, new byte[16], 1,
                validCw(), List.of(), -1, 128, false));
    }

    @Test
    void compatPushRecordConstructorDefaultsCycle() {
        CcCacheExCodec.PushRecord legacy = new CcCacheExCodec.PushRecord(1, 1, 1, 0, 0x80,
            new byte[16], 1, validCw(), List.of(), -1);
        assertEquals(0, legacy.cycleTimeSec());
        assertFalse(legacy.nextCwCycle());
    }
}
