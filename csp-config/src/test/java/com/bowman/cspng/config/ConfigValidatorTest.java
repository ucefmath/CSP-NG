package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    @Test
    void emptySnapshotIsValid() {
        ConfigSnapshot s = ConfigSnapshot.empty();
        List<String> errors = ConfigValidator.validate(s);
        assertTrue(errors.isEmpty());
    }

    @Test
    void duplicateProfileNames() {
        ProfileConfig p1 = new ProfileConfig("dup", true, false, false, 0, 0,
            null, null, 5000L, null, false, 0, null, null, null, null, 1L);
        ProfileConfig p2 = new ProfileConfig("dup", true, false, false, 0, 0,
            null, null, 5000L, null, false, 0, null, null, null, null, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p1, p2), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(), List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        List<String> errors = ConfigValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate profile name")));
    }

    @Test
    void duplicateConnectorNames() {
        ConnectorConfig c1 = new ConnectorConfig("dup", "default", "newcamd", true, false,
            "10.0.0.1", 15000, "u", null, null, 5000, 10000, null, null, null,
            null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        ConnectorConfig c2 = new ConnectorConfig("dup", "default", "newcamd", true, false,
            "10.0.0.1", 15001, "u", null, null, 5000, 10000, null, null, null,
            null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(c1, c2), List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        List<String> errors = ConfigValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate connector name")));
    }

    @Test
    void connectorReferencesUnknownProfile() {
        ConnectorConfig c = new ConnectorConfig("c1", "nonexistent", "newcamd", true, false,
            "10.0.0.1", 15000, "u", null, null, 5000, 10000, null, null, null,
            null, null, null, false, null, null, null, null, null, null, null, 50, 0, 1L);
        ConfigSnapshot s = new ConfigSnapshot(
            RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
            List.of(), List.of(c), List.of(), CacheConfig.DEFAULT,
            ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            List.of(), List.of(), System.currentTimeMillis());

        List<String> errors = ConfigValidator.validate(s);
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown profile")));
    }

    @Test
    void invalidCacheexMode() {
        // Constructor validates cacheexMode first — test via assertThrows
        assertThrows(IllegalArgumentException.class, () ->
            new ConnectorConfig("c1", "default", "cccam", true, false,
            "10.0.0.1", 15000, "u", null, null, 5000, 10000, null, null, null,
            null, null, null, false, null, null, 5 /* invalid */, null, null,
            null, null, 50, 0, 1L)
        );
    }

    @Test
    void portValidation() {
        // Constructor validates port range first — test via assertThrows
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileListenerConfig(1L, "default", "newcamd",
                99999, "0.0.0.0", true, null, null, null, 1L)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new ProfileListenerConfig(1L, "default", "newcamd",
                0, "0.0.0.0", true, null, null, null, 1L)
        );
    }

    @Test
    void ipLiteralHelpers() {
        // IPv4 valide
        assertTrue(ConfigValidator.isValidInet("0.0.0.0"));
        assertTrue(ConfigValidator.isValidInet("192.168.1.1"));
        assertTrue(ConfigValidator.isValidInet("255.255.255.255"));
        // IPv4 invalide
        assertFalse(ConfigValidator.isValidInet("999.999.999.999"));
        assertFalse(ConfigValidator.isValidInet("256.1.2.3"));
        assertFalse(ConfigValidator.isValidInet("1.2.3"));
        assertFalse(ConfigValidator.isValidInet("1.2.3.4.5"));
        assertFalse(ConfigValidator.isValidInet("1.2.3."));
        assertFalse(ConfigValidator.isValidInet("a.b.c.d"));
        // IPv6 valide (formes complète, abrégée et IPv4-mapped)
        assertTrue(ConfigValidator.isValidInet("::"));
        assertTrue(ConfigValidator.isValidInet("::1"));
        assertTrue(ConfigValidator.isValidInet("fe80::1"));
        assertTrue(ConfigValidator.isValidInet("2001:db8::1"));
        assertTrue(ConfigValidator.isValidInet("0:0:0:0:0:0:0:0"));
        assertTrue(ConfigValidator.isValidInet("2001:0DB8:0000:0000:0000:0000:0000:0001"));
        assertTrue(ConfigValidator.isValidInet("::ffff:192.0.2.1"));
        // IPv6 invalide
        assertFalse(ConfigValidator.isValidInet(":::1"));
        assertFalse(ConfigValidator.isValidInet("1:2:3:4:5:6:7:8:9"));
        assertFalse(ConfigValidator.isValidInet("1::2::3"));
        assertFalse(ConfigValidator.isValidInet("::gggg"));
        assertFalse(ConfigValidator.isValidInet(":1"));
        // hostname / entier rejeté
        assertFalse(ConfigValidator.isValidInet("localhost"));
        assertFalse(ConfigValidator.isValidInet("myhost.local"));
        assertFalse(ConfigValidator.isValidInet("0"));
        assertFalse(ConfigValidator.isValidInet("10"));
        // CIDR rejeté
        assertFalse(ConfigValidator.isValidInet("192.168.1.1/24"));
        assertFalse(ConfigValidator.isValidInet("::/64"));
        // wildcard
        assertTrue(ConfigValidator.isWildcard("0.0.0.0"));
        assertTrue(ConfigValidator.isWildcard("::"));
        assertTrue(ConfigValidator.isWildcard("0:0:0:0:0:0:0:0"));
        assertTrue(ConfigValidator.isWildcard("::0"));
        assertFalse(ConfigValidator.isWildcard("127.0.0.1"));
        assertFalse(ConfigValidator.isWildcard("2001:db8::1"));
        // bind conflicts
        assertTrue(ConfigValidator.bindConflicts("0.0.0.0", "192.168.1.1"));
        assertTrue(ConfigValidator.bindConflicts("::", "2001:db8::1"));
        assertTrue(ConfigValidator.bindConflicts("::", "0:0:0:0:0:0:0:0"));
        assertTrue(ConfigValidator.bindConflicts("2001:db8::1", "2001:0db8:0000:0000:0000:0000:0000:0001"));
        assertFalse(ConfigValidator.bindConflicts("0.0.0.0", "::"));
        assertFalse(ConfigValidator.bindConflicts("::", "192.168.1.1"));
        assertFalse(ConfigValidator.bindConflicts("0.0.0.0", "2001:db8::1"));
    }
}
