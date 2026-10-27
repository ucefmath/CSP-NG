package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigSnapshotTest {

    @Test
    void emptyHasSafeDefaults() {
        ConfigSnapshot s = ConfigSnapshot.empty();
        assertNotNull(s.root());
        assertEquals("CSP-NG", s.root().proxyName());
        assertTrue(s.profiles().isEmpty());
        assertTrue(s.connectors().isEmpty());
        assertTrue(s.profileListeners().isEmpty());
    }

    @Test
    void defensiveCopyOnConstruction() {
        var profiles = new java.util.ArrayList<>(List.of(
            new ProfileConfig("p1", true, false, false, 0, 0, null, null,
                5000L, null, false, 0, null, null, null, null, 1L)
        ));
        var connectors = new java.util.ArrayList<>(List.of(
            new ConnectorConfig("c1", "p1", "newcamd", true, false,
                "10.0.0.1", 15000, "user", null, null, 5000, 10000,
                null, null, null, null, null, null, false, null, null,
                null, null, null, null, null, 50, 0, 1L)
        ));
        var listeners = new java.util.ArrayList<>(List.of(
            new ProfileListenerConfig(1L, "p1", "newcamd", 15000, "0.0.0.0",
                true, null, null, null, 1L)
        ));

        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            profiles, listeners, List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), connectors, List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        // Mutate the original lists — snapshot must not be affected
        profiles.clear();
        connectors.clear();
        listeners.clear();

        assertEquals(1, s.profiles().size());
        assertEquals(1, s.connectors().size());
        assertEquals(1, s.profileListeners().size());
    }

    @Test
    void profileByNameIndex() {
        ProfileConfig p = new ProfileConfig("default", true, false, false, 0x1702, 0x0001,
            null, null, 5000L, null, false, 0, null, null, null, null, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(), List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        assertEquals(p, s.profileByName().get("default"));
        assertNull(s.profileByName().get("nonexistent"));
    }

    @Test
    void listenersForProfile() {
        ProfileListenerConfig l = new ProfileListenerConfig(1L, "default", "newcamd",
            15000, "0.0.0.0", true, null, null, null, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(), List.of(l), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(), List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        assertEquals(1, s.listenersForProfile("default").size());
        assertTrue(s.listenersForProfile("other").isEmpty());
    }
}
