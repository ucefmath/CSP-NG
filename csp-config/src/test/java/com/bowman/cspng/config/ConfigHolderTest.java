package com.bowman.cspng.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;

import org.junit.jupiter.api.Test;

class ConfigHolderTest {

    @Test
    void failFastWhenNotInitialized() {
        ConfigHolder holder = new ConfigHolder();
        assertFalse(holder.isInitialized());
        assertThrows(IllegalStateException.class, holder::get);
    }

    @Test
    void setAndGet() {
        ConfigHolder holder = new ConfigHolder();
        ConfigSnapshot snapshot = ConfigSnapshot.empty();
        holder.set(snapshot);

        assertTrue(holder.isInitialized());
        assertSame(snapshot, holder.get());
    }

    @Test
    void swapSnapshot() {
        ConfigHolder holder = new ConfigHolder();
        ConfigSnapshot a = ConfigSnapshot.empty();
        holder.set(a);

        ConfigSnapshot b = new ConfigSnapshot(
            new RootConfig("another", 42, 1L),
            LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            ConnectionManagerConfig.DEFAULT,
            Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
            CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
            Collections.emptyList(), Collections.emptyList(), System.currentTimeMillis());
        holder.set(b);

        assertNotSame(a, holder.get());
        assertEquals("another", holder.get().root().proxyName());
    }

    @Test
    void setNullThrows() {
        ConfigHolder holder = new ConfigHolder();
        assertThrows(NullPointerException.class, () -> holder.set(null));
    }

    @Test
    void updateDoesCasLoop() {
        ConfigHolder holder = new ConfigHolder();
        ConfigSnapshot original = ConfigSnapshot.empty();
        holder.set(original);

        holder.update(snapshot -> new ConfigSnapshot(
            new RootConfig("updated", 99, 1L),
            snapshot.logging(), snapshot.caProfilesGlobal(),
            snapshot.profiles(), snapshot.profileListeners(), snapshot.profileCards(),
            snapshot.connectionManager(), snapshot.serviceMap(),
            snapshot.connectors(), snapshot.connectorServices(),
            snapshot.cache(), snapshot.clusterUdp(), snapshot.webui(),
            snapshot.plugins(), snapshot.sidCacheLinks(),
            System.currentTimeMillis()
        ));

        assertEquals("updated", holder.get().root().proxyName());
    }
}
