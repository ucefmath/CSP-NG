package com.bowman.cspng.codec.camd35;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests du codec CacheEx camd35/CS378x (V15) — layout wire 0x3F/0x3C bit-exact
 * (miroir comportemental module-camd35-cacheex.c, aucune copie de code).
 */
class Camd35CacheExCodecTest {

    private static byte[] validCw() {
        byte[] cw = new byte[16];
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
    void pushBodyRoundTrip() {
        byte[] cw = validCw();
        byte[] ownNode = new byte[8];
        ownNode[0] = 1;
        byte[] other = new byte[8];
        other[0] = 2;
        byte[] body = Camd35CacheExCodec.encodePushBody(new byte[16], 0xCAFEBABE, cw,
            List.of(ownNode, other));

        // Layout : ecmd5(16) + hash(4 LE) + cw(16) + count(1) + nodes(8*n)
        assertEquals(37 + 16, body.length);
        assertEquals(2, body[36] & 0xFF);
        // hash LITTLE-endian
        assertEquals(0xBE, body[16] & 0xFF);
        assertEquals(0xBA, body[17] & 0xFF);
        assertEquals(0xFE, body[18] & 0xFF);
        assertEquals(0xCA, body[19] & 0xFF);

        Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
            new byte[4], Camd35Constants.CMD_CACHEEX_PUSH, 0x1234, 0x0100, 0x00006A,
            0x00AA, ((10 | 0x80) << 8) | 0x80, body, new byte[0], 0);
        Camd35CacheExCodec.Camd35Push push = Camd35CacheExCodec.parsePush(frame);
        assertEquals(0x0100, push.caid());
        assertEquals(0x00006A, push.prid());
        assertEquals(0x1234, push.srvid());
        assertEquals(0x00AA, push.pid());
        assertEquals(0xCAFEBABE, push.dataHash());
        assertArrayEquals(cw, push.cw());
        assertEquals(2, push.nodeIds().size());
        assertArrayEquals(ownNode, push.nodeIds().get(0));
        assertArrayEquals(other, push.nodeIds().get(1));
        // Métadonnées de cycle : champ pid = (cycletime<<8)|oddEven
        assertEquals(10, push.cycleTimeSec());
        assertTrue(push.nextCwCycle());
        assertEquals(0x80, push.oddEven());
        assertEquals(0, push.rc());
    }

    @Test
    void nextCycleFlagFromBit7() {
        byte[] body = Camd35CacheExCodec.encodePushBody(new byte[16], 1, validCw(), List.of(new byte[8]));
        Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
            new byte[4], Camd35Constants.CMD_CACHEEX_PUSH, 1, 1, 1, 0,
            ((0x05 | 0x80) << 8) | 0x81, body, new byte[0], 100 /* E_UNHANDLED */);
        Camd35CacheExCodec.Camd35Push push = Camd35CacheExCodec.parsePush(frame);
        assertEquals(5, push.cycleTimeSec());
        assertTrue(push.nextCwCycle());
        assertEquals(0x81, push.oddEven());
        assertEquals(100, push.rc());
    }

    @Test
    void truncatedBodyRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> Camd35CacheExCodec.parsePush(new Camd35FrameCodec.Camd35Frame(
                new byte[4], Camd35Constants.CMD_CACHEEX_PUSH, 0, 0, 0, 0, 0,
                new byte[10], new byte[0], 0)));
        // count incohérent avec la longueur
        byte[] body = new byte[40];
        body[36] = 5; // 5 nodes annoncés mais seulement 3 octets restants
        assertThrows(IllegalArgumentException.class,
            () -> Camd35CacheExCodec.parsePush(new Camd35FrameCodec.Camd35Frame(
                new byte[4], Camd35Constants.CMD_CACHEEX_PUSH, 0, 0, 0, 0, 0,
                body, new byte[0], 0)));
    }

    @Test
    void filterBodyRoundTrip() {
        List<Camd35CacheExCodec.Camd35FilterEntry> entries = List.of(
            new Camd35CacheExCodec.Camd35FilterEntry(0x0100, 0xFFFF, 0x00006A, 0),
            new Camd35CacheExCodec.Camd35FilterEntry(0x0500, 0xFFFF, 0x030B00, 0x1111));
        byte[] body = Camd35CacheExCodec.encodeFilterBody(entries);
        assertEquals(242, body.length);
        assertEquals(2, ((body[0] & 0xFF) << 8) | (body[1] & 0xFF));

        List<Camd35CacheExCodec.Camd35FilterEntry> parsed = Camd35CacheExCodec.parseFilterBody(body);
        assertEquals(2, parsed.size());
        assertEquals(0x0100, parsed.get(0).caid());
        assertEquals(0xFFFF, parsed.get(0).cmask());
        assertEquals(0x00006A, parsed.get(0).prid());
        assertEquals(0x0500, parsed.get(1).caid());
        assertEquals(0x030B00, parsed.get(1).prid());
        assertEquals(0x1111, parsed.get(1).srvid());
    }

    @Test
    void emptyFilterBodyIsAcceptAll() {
        byte[] body = Camd35CacheExCodec.encodeFilterBody(List.of());
        assertEquals(242, body.length);
        assertEquals(0, ((body[0] & 0xFF) << 8) | (body[1] & 0xFF));
        assertTrue(Camd35CacheExCodec.parseFilterBody(body).isEmpty());
    }

    @Test
    void specParsing() {
        List<Camd35CacheExCodec.Camd35FilterEntry> entries =
            Camd35CacheExCodec.entriesFromSpec("0100:00006A, 0500:030B00");
        assertEquals(2, entries.size());
        assertEquals(0x0100, entries.get(0).caid());
        assertEquals(0xFFFF, entries.get(0).cmask());
        assertEquals(0x00006A, entries.get(0).prid());
        assertTrue(Camd35CacheExCodec.entriesFromSpec(null).isEmpty());
        assertTrue(Camd35CacheExCodec.entriesFromSpec("  ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> Camd35CacheExCodec.entriesFromSpec("zz:11"));
        assertThrows(IllegalArgumentException.class, () -> Camd35CacheExCodec.entriesFromSpec("0100"));
    }

    @Test
    void frameEncoderWritesCacheexHeaderBytes() {
        // 0x3F : longueur 16 bits [1..2] LE + rc en [3] (rcByte)
        byte[] body = Camd35CacheExCodec.encodePushBody(new byte[16], 1, validCw(),
            List.of(new byte[8]));
        Camd35FrameCodec.Camd35Frame frame = new Camd35FrameCodec.Camd35Frame(
            new byte[4], Camd35Constants.CMD_CACHEEX_PUSH, 1, 1, 1, 0, 0, body, new byte[0], 0);
        byte[] ucrc = {1, 2, 3, 4};
        byte[] key = new byte[16];
        byte[] wire = Camd35FrameCodec.encode(frame, key);
        // Déchiffrer les 32 premiers octets pour vérifier l'en-tête
        byte[] encrypted = java.util.Arrays.copyOfRange(wire, 4, wire.length);
        Camd35Crypto.aesEcbInPlace(encrypted, key, false);
        assertEquals(0x3F, encrypted[0] & 0xFF);
        assertEquals(body.length & 0xFF, encrypted[1] & 0xFF);
        assertEquals((body.length >>> 8) & 0xFF, encrypted[2] & 0xFF,
            "cacheex frames carry 16-bit LE length in [1..2]");
        assertEquals(0, encrypted[3] & 0xFF, "rc byte [3] must carry rcByte");
    }
}
