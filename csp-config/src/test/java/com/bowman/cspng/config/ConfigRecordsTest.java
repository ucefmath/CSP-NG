package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires des records de configuration (immutabilite, validation).
 */
class ConfigRecordsTest {

    @Test
    void rootConfigDefaults() {
        assertEquals("CSP-NG", RootConfig.DEFAULT.proxyName());
        assertEquals(0, RootConfig.DEFAULT.proxyOriginId());
    }

    @Test
    void rootConfigRejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new RootConfig("", 0, 1L));
        assertThrows(IllegalArgumentException.class, () -> new RootConfig("  ", 0, 1L));
    }

    @Test
    void profileConfigRejectsShortMaxCwWait() {
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileConfig("p", true, false, false, 0, 0, null, null,
                50L, null, false, 0, null, null, null, null, 1L));
    }

    @Test
    void profileConfigMinimalValid() {
        ProfileConfig p = new ProfileConfig("ok", true, false, false, 0x1702, 0x0001,
            null, null, 5000L, null, false, 0, null, null, null, null, 1L);
        assertEquals(5000L, p.effectiveCongestionLimit());
    }

    @Test
    void profileConfigExplicitCongestionLimit() {
        ProfileConfig p = new ProfileConfig("ok", true, false, false, 0x1702, 0x0001,
            null, null, 8000L, 3000L, false, 0, null, null, null, null, 1L);
        assertEquals(3000L, p.effectiveCongestionLimit());
    }

    @Test
    void cacheConfigDefaults() {
        assertEquals(30_000L, CacheConfig.DEFAULT.cwMaxAgeMs());
        assertEquals(100_000L, CacheConfig.DEFAULT.maximumSize());
    }

    @Test
    void cacheConfigRejectsBothMinus1() {
        assertThrows(IllegalArgumentException.class, () ->
            new CacheConfig(30_000L, 100_000L, -1L, -1, 1L));
    }

    @Test
    void connectorConfigRejectsBadPort() {
        assertThrows(IllegalArgumentException.class, () ->
            new ConnectorConfig("c", "p", "newcamd", true, false, "h", 0,
                null, null, null, 5000, 10000, null, null, null, null, null, null,
                false, null, null, 0, null, null, null, null, 50, 0, 1L));
        assertThrows(IllegalArgumentException.class, () ->
            new ConnectorConfig("c", "p", "newcamd", true, false, "h", 99999,
                null, null, null, 5000, 10000, null, null, null, null, null, null,
                false, null, null, 0, null, null, null, null, 50, 0, 1L));
    }

    @Test
    void connectorConfigValidCacheexModes() {
        // null and 0, 2, 3 are valid
        new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
            null, null, null, 5000, 10000, null, null, null, null, null, null,
            false, null, null, null, null, null, null, null, 50, 0, 1L);
        new ConnectorConfig("c2", "p", "newcamd", true, false, "h", 15000,
            null, null, null, 5000, 10000, null, null, null, null, null, null,
            true, "n1", "2.3.0", 2, "filter", 500, null, null, 50, 0, 1L);
    }

    @Test
    void connectorConfigRejectsBadCacheexMode() {
        assertThrows(IllegalArgumentException.class, () ->
            new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
                null, null, null, 5000, 10000, null, null, null, null, null, null,
                false, null, null, 5, null, null, null, null, 50, 0, 1L));
    }

    @Test
    void connectorConfigNewcamdPasswordEncoding() {
        ConnectorConfig c1 = new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
            null, null, null, 5000, 10000, null, null, null, null, null, null,
            false, null, null, null, null, null, null, null, 50, 0, 1L, null,
            false, null, "legacy", null, null);
        assertEquals("plaintext", c1.newcamdPasswordEncoding());

        ConnectorConfig c2 = new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
            null, null, null, 5000, 10000, null, null, null, null, null, null,
            false, null, null, null, null, null, null, null, 50, 0, 1L, null,
            false, null, "legacy", null, "md5crypt");
        assertEquals("md5crypt", c2.newcamdPasswordEncoding());

        ConnectorConfig c3 = new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
            null, null, null, 5000, 10000, null, null, null, null, null, null,
            false, null, null, null, null, null, null, null, 50, 0, 1L, null,
            false, null, "OSCAM_NCD525", null, "MD5CRYPT");
        assertEquals("md5crypt", c3.newcamdPasswordEncoding());
        assertEquals("oscam_ncd525", c3.newcamdDialect());

        assertThrows(IllegalArgumentException.class, () ->
            new ConnectorConfig("c", "p", "newcamd", true, false, "h", 15000,
                null, null, null, 5000, 10000, null, null, null, null, null, null,
                false, null, null, null, null, null, null, null, 50, 0, 1L, null,
                false, null, "legacy", null, "sha256")
        );
    }

    @Test
    void relayWorkerConnectorViewPasswordEncoding() {
        RelayWorkerContract.ConnectorView v1 = new RelayWorkerContract.ConnectorView(
            "c", "newcamd", "p", true, "h", 15000, "u", "pwdRef");
        assertEquals("plaintext", v1.newcamdPasswordEncoding());

        RelayWorkerContract.ConnectorView v2 = new RelayWorkerContract.ConnectorView(
            "c", "newcamd", "p", true, "h", 15000, "u", "pwdRef",
            "desRef", "proxy", 5000, "2.3.0", 2, true, 1, "legacy", 0, "md5crypt");
        assertEquals("md5crypt", v2.newcamdPasswordEncoding());
    }

    @Test
    void clusterUdpDefaults() {
        assertEquals(8099, ClusterUdpConfig.DEFAULT.bindPort());
        assertEquals(4000L, ClusterUdpConfig.DEFAULT.pingIntervalMs());
        assertTrue(ClusterUdpConfig.DEFAULT.autoAddPeers());
    }

    @Test
    void webUiDefaults() {
        assertEquals(8080, WebUiConfig.DEFAULT.listenPort());
        assertFalse(WebUiConfig.DEFAULT.tlsEnabled());
    }

    @Test
    void listenerConfigPortValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileListenerConfig(1L, "p", "nc", 0, "0.0.0.0", true, null, null, null, 1L));
    }
}
