package com.bowman.cspng.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class V3ValidatorSnapshotTest {

    @Test
    void enabledProfileRefs() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ProfileConfig disabled = new ProfileConfig("pDis", false,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig fixed = new ConnectorConfig("c1","p1","newcamd",true,false,"127.0.0.1",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        ConnectorConfig bad = new ConnectorConfig("c2","pDis","newcamd",true,false,"127.0.0.1",15001,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        ConfigSnapshot s = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p, disabled), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(fixed,bad), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        var e = ConfigValidator.validate(s);
        assertTrue(e.stream().anyMatch(m->m.contains("disabled")));
    }

    @Test
    void dynamicFixedValidation() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig dynNoMap = new ConnectorConfig("dyn","pNull","newcamd",true,false,"127.0.0.1",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        // dyn without map should be valid? Actually we test dynamic with null profile and no map
        ConnectorConfig dyn = new ConnectorConfig("dyn2",null,"newcamd",true,false,"127.0.0.1",15001,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        ConfigSnapshot s = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(dyn), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        var e = ConfigValidator.validate(s);
        assertTrue(e.stream().anyMatch(m->m.contains("no CAID")));
    }

    @Test
    void caidOrdinalChecks() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig dyn = new ConnectorConfig("dyn",null,"newcamd",true,false,"127.0.0.1",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        var entriesDupCa = List.of(
            new ConnectorCaidProfileConfig(1,"dyn",0,100,"p1",1L),
            new ConnectorCaidProfileConfig(2,"dyn",1,100,"p1",1L)
        );
        var s1 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(dyn), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, entriesDupCa, List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s1).stream().anyMatch(m->m.contains("duplicate CAID")));
        var entriesNonCont = List.of(
            new ConnectorCaidProfileConfig(1,"dyn",0,100,"p1",1L),
            new ConnectorCaidProfileConfig(2,"dyn",2,101,"p1",1L)
        );
        var s2 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(dyn), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, entriesNonCont, List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s2).stream().anyMatch(m->m.contains("missing ordinal")));
    }

    @Test
    void providerChecks() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        var provDup = List.of(
            new ProfileProviderIdentConfig(1,"p1",100,"a",1L),
            new ProfileProviderIdentConfig(2,"p1",100,"b",1L)
        );
        var s = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), provDup, System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s).stream().anyMatch(m->m.contains("Duplicate provider")));
    }

    @Test
    void catchAllCases() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ExtendedNewcamdConfig extCatchTrue = new ExtendedNewcamdConfig(true,2233,"0.0.0.0",true,false,"env:DES",null,null,2048,1L);
        ConfigSnapshot s1 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            extCatchTrue, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s1).isEmpty());
        ExtendedNewcamdConfig extNoCatchNoRouting = new ExtendedNewcamdConfig(true,2233,"0.0.0.0",false,false,"env:DES",null,null,2048,1L);
        ConfigSnapshot s2 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            extNoCatchNoRouting, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s2).stream().anyMatch(m->m.contains("without catchAll")));
    }

    @Test
    void portWildcards() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ExtendedNewcamdConfig ext = new ExtendedNewcamdConfig(true,15000,"0.0.0.0",true,false,"env:DES",null,null,2048,1L);
        ProfileListenerConfig l = new ProfileListenerConfig(1,"p1","newcamd",15000,"192.168.1.1",true,null,null,null,1L);
        ConfigSnapshot s = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(l), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ext, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s).stream().anyMatch(m->m.contains("Port collision")));
        // listener vs listener
        ProfileListenerConfig l2 = new ProfileListenerConfig(2,"p1","newcamd",15001,"0.0.0.0",true,null,null,null,1L);
        ProfileListenerConfig l3 = new ProfileListenerConfig(3,"p1","newcamd",15001,"10.0.0.1",true,null,null,null,1L);
        ConfigSnapshot s2 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(l2,l3), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s2).stream().anyMatch(m->m.contains("listeners")));
        // ipv6 wildcard vs ipv6 specific same family -> collision
        ProfileListenerConfig l4 = new ProfileListenerConfig(4,"p1","newcamd",15002,"::",true,null,null,null,1L);
        ProfileListenerConfig l5 = new ProfileListenerConfig(5,"p1","newcamd",15002,"2001:db8::1",true,null,null,null,1L);
        ConfigSnapshot s3 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(l4,l5), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s3).stream().anyMatch(m->m.contains("Port collision")));
    }

    @Test
    void ctorCompatAndImmutable() {
        var snap = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertNotNull(snap.extendedNewcamd());
        assertTrue(snap.connectorCaidProfiles().isEmpty());
        // defensive copy
        var list = new java.util.ArrayList<ProfileConfig>();
        list.add(new ProfileConfig("p", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L));
        var snap2 = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            list, List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        list.clear();
        assertEquals(1, snap2.profiles().size());
        try { snap2.profiles().add(new ProfileConfig("x", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L)); fail(); } catch (UnsupportedOperationException ok){}
    }

    @Test
    void helpersSortedAndNoNPE() {
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ConnectorConfig dyn = new ConnectorConfig("dyn",null,"newcamd",true,false,"127.0.0.1",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L);
        var entries = List.of(
            new ConnectorCaidProfileConfig(2,"dyn",1,200,"p1",1L),
            new ConnectorCaidProfileConfig(1,"dyn",0,100,"p1",1L)
        );
        var snap = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(dyn), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, entries, List.of(new ProfileProviderIdentConfig(1,"p1",1,"a",1L)), System.currentTimeMillis());
        assertEquals(100, snap.caidProfilesForConnector("dyn").get(0).caId());
        assertEquals(List.of(100,200), List.copyOf(snap.caidProfileMapFor("dyn").keySet()));
        assertTrue(snap.caidProfileMapFor(null).isEmpty());
        assertTrue(snap.providerIdentsFor(null).isEmpty());
        assertTrue(snap.listenersForProfile(null).isEmpty());
        assertEquals(1, snap.connectorsWithoutFixedProfile().size());
        assertEquals(0, new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(),
            List.of(new ConnectorConfig("c","p1","newcamd",true,false,"h",15000,null,null,null,5000,10000,null,null,null,null,null,null,false,null,null,null,null,null,null,null,50,0,1L)), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis()).connectorsWithoutFixedProfile().size());
    }

    @Test
    void extendedSentinel() {
        assertEquals("**", ExtendedNewcamdConfig.CATCH_ALL_SENTINEL);
    }

    @Test
    void ipLiteralValidationAndBindConflicts() {
        // isValidInet
        assertTrue(ConfigValidator.isValidInet("0.0.0.0"));
        assertTrue(ConfigValidator.isValidInet("192.168.1.1"));
        assertTrue(ConfigValidator.isValidInet("::"));
        assertTrue(ConfigValidator.isValidInet("0:0:0:0:0:0:0:0"));
        assertTrue(ConfigValidator.isValidInet("::0"));
        assertTrue(ConfigValidator.isValidInet("2001:db8::1"));
        assertFalse(ConfigValidator.isValidInet("myhost.local"));
        assertFalse(ConfigValidator.isValidInet("localhost"));
        assertFalse(ConfigValidator.isValidInet(""));
        assertFalse(ConfigValidator.isValidInet("999.999.999.999"));
        assertFalse(ConfigValidator.isValidInet("192.168.1.1/24"));
        assertFalse(ConfigValidator.isValidInet("::/64"));
        assertFalse(ConfigValidator.isValidInet("0"));
        assertFalse(ConfigValidator.isValidInet("10"));
        // isWildcard
        assertTrue(ConfigValidator.isWildcard("0.0.0.0"));
        assertTrue(ConfigValidator.isWildcard("::"));
        assertTrue(ConfigValidator.isWildcard("0:0:0:0:0:0:0:0"));
        assertTrue(ConfigValidator.isWildcard("::0"));
        assertFalse(ConfigValidator.isWildcard("127.0.0.1"));
        assertFalse(ConfigValidator.isWildcard("2001:db8::1"));
        // bindConflicts same family
        assertTrue(ConfigValidator.bindConflicts("0.0.0.0", "192.168.1.1")); // IPv4 wildcard vs specific same family
        assertTrue(ConfigValidator.bindConflicts("::", "2001:db8::1")); // IPv6 wildcard vs specific
        assertTrue(ConfigValidator.bindConflicts("192.168.1.1", "192.168.1.1")); // exact
        assertTrue(ConfigValidator.bindConflicts("::", "0:0:0:0:0:0:0:0")); // both wildcard same family normalized
        assertFalse(ConfigValidator.bindConflicts("0.0.0.0", "2001:db8::1")); // IPv4 wildcard vs IPv6 specific -> PASS
        assertFalse(ConfigValidator.bindConflicts("::", "192.168.1.1")); // IPv6 wildcard vs IPv4 specific -> PASS
        assertFalse(ConfigValidator.bindConflicts("0.0.0.0", "::")); // different families wildcards -> PASS
        // validator errors for invalid bind
        ProfileConfig p = new ProfileConfig("p1", true,false,false,0,0,null,null,5000L,null,false,0,null,null,null,null,1L);
        ProfileListenerConfig bad = new ProfileListenerConfig(1,"p1","newcamd",15000,"myhost.local",true,null,null,null,1L);
        ConfigSnapshot s = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(bad), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(s).stream().anyMatch(m->m.contains("IP literal")));
        // cross family no collision
        ProfileListenerConfig l4 = new ProfileListenerConfig(4,"p1","newcamd",15002,"::",true,null,null,null,1L);
        ProfileListenerConfig l5 = new ProfileListenerConfig(5,"p1","newcamd",15002,"192.168.1.1",true,null,null,null,1L);
        ConfigSnapshot sCross = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(l4,l5), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(sCross).isEmpty(), "IPv6 wildcard vs IPv4 specific must PASS");
        // same family wildcard must fail
        ProfileListenerConfig l6 = new ProfileListenerConfig(6,"p1","newcamd",15003,"::",true,null,null,null,1L);
        ProfileListenerConfig l7 = new ProfileListenerConfig(7,"p1","newcamd",15003,"2001:db8::1",true,null,null,null,1L);
        ConfigSnapshot sFail = new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            List.of(p), List.of(l6,l7), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(),
            ExtendedNewcamdConfig.DEFAULT, List.of(), List.of(), System.currentTimeMillis());
        assertTrue(ConfigValidator.validate(sFail).stream().anyMatch(m->m.contains("Port collision")));
    }

    @Test
    void secretsFileRefsHardened() {
        assertTrue(ConfigValidator.isValidSecretRef("file:/run/secrets/pw"));
        assertTrue(ConfigValidator.isValidSecretRef("file:/etc/csp/des.key"));
        assertTrue(ConfigValidator.isValidSecretRef("env:MY_VAR"));
        assertTrue(ConfigValidator.isValidSecretRef("vault:secret/path"));
        assertTrue(ConfigValidator.isValidSecretRef("${file:/run/secrets/pw}"));
        assertFalse(ConfigValidator.isValidSecretRef("file:relative/path"));
        assertFalse(ConfigValidator.isValidSecretRef("file:/etc/../etc/passwd"));
        assertFalse(ConfigValidator.isValidSecretRef("file:/etc/csp/.."));
        assertFalse(ConfigValidator.isValidSecretRef("file:/run/\0secrets"));
        assertFalse(ConfigValidator.isValidSecretRef("file:/run/secrets\n"));
        assertFalse(ConfigValidator.isValidSecretRef("file:"));
        // control char
        assertFalse(ConfigValidator.isValidSecretRef("file:/run/secrets\u0001"));
        // valid env still
        assertTrue(ConfigValidator.isValidSecretRef("env:CSP_DES_KEY"));
        assertFalse(ConfigValidator.isValidSecretRef("file:/run/secrets with space"));
    }

}
