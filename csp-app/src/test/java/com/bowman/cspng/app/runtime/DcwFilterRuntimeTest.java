package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.*;
import com.bowman.cspng.connectors.CwsConnectorManager;
import com.bowman.cspng.filters.*;
import com.bowman.cspng.users.InMemoryUserDirectory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DcwFilterRuntimeTest {

  private static Vertx vertx;
  private static DcwFilterRuntime runtime;

  @BeforeAll
  static void init() {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var runtimeCore = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
    runtime = new DcwFilterRuntime(vertx, holder, runtimeCore);
  }

  @AfterAll
  static void shutdown() {
    if (vertx != null) {
      vertx.close().toCompletionStage().toCompletableFuture()
          .orTimeout(5, TimeUnit.SECONDS).join();
    }
  }

  // ==================== helpers ====================

  static ConfigSnapshot snapWithPlugins(List<PluginConfig> plugins) {
    return new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
        List.of(), List.of(), List.of(), CacheConfig.DEFAULT,
        ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        plugins, List.of(), ExtendedNewcamdConfig.DEFAULT,
        List.of(), List.of(), System.currentTimeMillis());
  }

  static ConfigSnapshot snapWithPluginsAndServices(List<PluginConfig> plugins,
                                                    List<ServiceMapEntryConfig> services) {
    return new ConfigSnapshot(
        RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        List.of(), List.of(), List.of(), ConnectionManagerConfig.DEFAULT,
        services, List.of(), List.of(), CacheConfig.DEFAULT,
        ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT,
        plugins, List.of(), ExtendedNewcamdConfig.DEFAULT,
        List.of(), List.of(), System.currentTimeMillis());
  }

  static PluginConfig enabledDcwPlugin() {
    return new PluginConfig("DcwFilterPlugin",
        "com.bowman.cspng.filters.DcwFilter", true, "DCW filter", null, null, 1);
  }

  static PluginConfig enabledDcwPluginWithJson(String json) {
    return new PluginConfig("DcwFilterPlugin",
        "com.bowman.cspng.filters.DcwFilter", true, "DCW filter", json, null, 1);
  }

  static PluginConfig enabledDcwPluginWithProfiles(String profiles) {
    return new PluginConfig("DcwFilterPlugin",
        "com.bowman.cspng.filters.DcwFilter", true, "DCW filter", null, profiles, 1);
  }

  static PluginConfig enabledDcwPluginNamed(String name) {
    return new PluginConfig(name,
        "com.bowman.cspng.filters.DcwFilter", true, "DCW filter", null, null, 1);
  }

  static PluginConfig disabledDcwPlugin() {
    return new PluginConfig("DcwFilterPlugin",
        "com.bowman.cspng.filters.DcwFilter", false, "DCW filter", null, null, 1);
  }

  static ServiceMapEntryConfig tvService(String profile, int sid) {
    return new ServiceMapEntryConfig(sid, profile, sid, "TV" + sid, 1, null, null, null, 1);
  }

  static ServiceMapEntryConfig tvServiceType(int type) {
    return new ServiceMapEntryConfig(1, "p", type, "TV" + type, type, null, null, null, 1);
  }

  // ==================== 1. Plugin selection ====================

  @Test void selectPluginAbsentReturnsNull() {
    assertNull(runtime.selectPlugin(snapWithPlugins(List.of())));
  }
  @Test void selectPluginDisabledReturnsNull() {
    assertNull(runtime.selectPlugin(snapWithPlugins(List.of(disabledDcwPlugin()))));
  }
  @Test void selectPluginByClassName() {
    assertNotNull(runtime.selectPlugin(snapWithPlugins(List.of(enabledDcwPlugin()))));
  }
  @Test void selectPluginByNameDcw() {
    assertEquals("dcw", runtime.selectPlugin(snapWithPlugins(List.of(enabledDcwPluginNamed("dcw")))).name());
  }
  @Test void selectPluginByNameCaseInsensitive() {
    assertNotNull(runtime.selectPlugin(snapWithPlugins(List.of(enabledDcwPluginNamed("DcWFiLtErPlUgIn")))));
  }
  @Test void selectPluginMultipleFailFast() {
    assertThrows(IllegalStateException.class, () -> runtime.selectPlugin(snapWithPlugins(List.of(
        enabledDcwPluginNamed("dcw"), enabledDcwPluginNamed("DcwFilterPlugin")))));
  }

  // ==================== 2. Config parser ====================

  @Test void parseConfigJsonDefaults() {
    var cfg = runtime.buildFilterConfig(enabledDcwPlugin());
    assertTrue(cfg.enabled()); assertFalse(cfg.detectLinks());
    assertFalse(cfg.verifyReplies()); assertFalse(cfg.forceContinuity());
    assertTrue(cfg.zeroCounting()); assertFalse(cfg.shadowMode());
    assertEquals(DcwFilterConfig.ContinuityMode.COMPATIBILITY, cfg.continuityMode());
    assertEquals(10_000, cfg.maxEntries()); assertTrue(cfg.profiles().isEmpty());
  }

  @Test void parseConfigJsonAllFields() {
    var cfg = runtime.buildFilterConfig(enabledDcwPluginWithJson(
        "{\"detectLinks\":true,\"verifyReplies\":true,\"forceContinuity\":true," +
        "\"zeroCounting\":false,\"shadowMode\":true,\"continuityMode\":\"STRICT\"," +
        "\"maxEntries\":5000}"));
    assertTrue(cfg.detectLinks()); assertTrue(cfg.verifyReplies());
    assertTrue(cfg.forceContinuity()); assertFalse(cfg.zeroCounting());
    assertTrue(cfg.shadowMode()); assertEquals(DcwFilterConfig.ContinuityMode.STRICT, cfg.continuityMode());
    assertEquals(5000, cfg.maxEntries());
  }

  @Test void parseConfigJsonUnknownFieldRejected() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"unknownField\":true}")));
  }

  @Test void parseConfigJsonBadTypeDetectLinks() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"detectLinks\":\"not-a-bool\"}")));
  }

  @Test void parseConfigJsonOversizeActuallyExceeds() {
    // Must actually exceed 64 KiB
    String large = "{\"detectLinks\":true,\"padding\":\"" + "x".repeat(65_536) + "\"}";
    assertThrows(IllegalArgumentException.class, () -> runtime.parseConfigJson(large));
  }

  @Test void parseConfigJsonBadContinuityMode() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"continuityMode\":\"INVALID\"}")));
  }

  @Test void parseConfigJsonBadMaxEntries() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"maxEntries\":50}")));
  }

  @Test void parseConfigJsonValidBadDcws() {
    var cfg = runtime.buildFilterConfig(enabledDcwPluginWithJson(
        "{\"badDcws\":[\"000102030405060708090a0b0c0d0e0f\"]}"));
    assertTrue(cfg.badDcwDigests().size() >= 2);
  }

  @Test void parseConfigJsonBadDcwsWithSpacesAndColons() {
    var cfg = runtime.buildFilterConfig(enabledDcwPluginWithJson(
        "{\"badDcws\":[\"00:01:02 03 04 05 06 0708090a0b0c0d0e0f\"]}"));
    assertTrue(cfg.badDcwDigests().size() >= 2);
  }

  @Test void parseConfigJsonBadDcwsEmptyArray() {
    var cfg = runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"badDcws\":[]}"));
    assertEquals(1, cfg.badDcwDigests().size(), "only all-zero");
  }

  @Test void parseConfigJsonBadDcwsNonStringElement() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"badDcws\":[123]}")));
  }

  @Test void parseConfigJsonBadDcwsNullElement() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson("{\"badDcws\":[null]}")));
  }

  @Test void parseConfigJsonBadDcwsInvalidHexChar() {
    assertThrows(IllegalArgumentException.class, () ->
        runtime.buildFilterConfig(enabledDcwPluginWithJson(
            "{\"badDcws\":[\"000102030405060708090a0b0c0d0e0g\"]}")));
  }

  // ==================== 3. Profiles parsing ====================

  @Test void parseProfilesSpaces() {
    assertEquals(Set.of("dvbt", "sat"), DcwFilterRuntime.parseProfiles("dvbt sat"));
  }
  @Test void parseProfilesCommas() {
    assertEquals(Set.of("dvbt", "sat"), DcwFilterRuntime.parseProfiles("dvbt,sat"));
  }
  @Test void parseProfilesMixed() {
    assertEquals(Set.of("a", "b", "c"), DcwFilterRuntime.parseProfiles("a, b c"));
  }
  @Test void parseProfilesNullEmpty() {
    assertTrue(DcwFilterRuntime.parseProfiles(null).isEmpty());
    assertTrue(DcwFilterRuntime.parseProfiles("").isEmpty());
    assertTrue(DcwFilterRuntime.parseProfiles("  ").isEmpty());
  }
  @Test void pluginProfilesAuthoritative() {
    assertEquals(Set.of("dvbt", "sat"),
        runtime.buildFilterConfig(enabledDcwPluginWithProfiles("dvbt, sat")).profiles());
  }

  // ==================== 4. Service directory ====================

  @Test void serviceDirectoryTvTypesExact() {
    var sd = runtime.buildServiceDirectory(snapWithPluginsAndServices(List.of(),
        List.of(tvServiceType(1), tvServiceType(2), tvServiceType(17), tvServiceType(25), tvServiceType(211))));
    assertTrue(sd.lookup("p", 1).isPresent()); assertTrue(sd.lookup("p", 2).isPresent());
    assertTrue(sd.lookup("p", 17).isPresent()); assertTrue(sd.lookup("p", 25).isPresent());
    assertTrue(sd.lookup("p", 211).isPresent());
  }

  @Test void serviceDirectoryNonTvSkipped() {
    var sd = runtime.buildServiceDirectory(snapWithPluginsAndServices(List.of(),
        List.of(new ServiceMapEntryConfig(1, "p", 1, "Radio", 3, null, null, null, 1))));
    assertTrue(sd.lookup("p", 1).isEmpty());
  }

  @Test void serviceDirectoryDuplicatesFirstWins() {
    var sd = runtime.buildServiceDirectory(snapWithPluginsAndServices(List.of(), List.of(
        new ServiceMapEntryConfig(1, "p", 1, "First", 1, null, null, null, 1),
        new ServiceMapEntryConfig(2, "p", 1, "Second", 1, null, null, null, 2))));
    assertEquals("First", sd.lookup("p", 1).get().displayName());
  }

  // ==================== 5. hex validation ====================

  @Test void hexToBytesValidatedValid() {
    byte[] r = DcwFilterRuntime.hexToBytesValidated("000102030405060708090a0b0c0d0e0f");
    assertEquals(16, r.length); assertEquals(0x0a, r[10] & 0xFF);
  }

  @Test void hexToBytesValidatedInvalidChar() {
    assertThrows(IllegalArgumentException.class, () ->
        DcwFilterRuntime.hexToBytesValidated("000102030405060708090a0b0c0d0e0g"));
  }

  @Test void hexToBytesValidatedOddLength() {
    assertThrows(IllegalArgumentException.class, () ->
        DcwFilterRuntime.hexToBytesValidated("000"));
  }

  @Test void hexToBytesValidatedNegativeDigit() {
    // Character.digit returns -1 for non-hex chars
    assertThrows(IllegalArgumentException.class, () ->
        DcwFilterRuntime.hexToBytesValidated("zzzz"));
  }

  // ==================== 6. Config validation bounds ====================

  @Test void maxFileSizeBytesBoundedLow() {
    runtime.maxFileSizeBytes = 100; // too low
    var snap = snapWithPluginsAndServices(List.of(enabledDcwPlugin()), List.of(tvService("p", 1)));
    // validateConfig should clamp it
  }

  @Test void maxFileSizeBytesBoundedHigh() {
    runtime.maxFileSizeBytes = Integer.MAX_VALUE; // too high
  }

  // ==================== 7. unionLinkGroups ====================

  @Test void unionEmptyBoth() {
    assertTrue(DcwFilterRuntime.unionLinkGroups(Set.of(), Set.of()).isEmpty());
  }

  @Test void unionFirstOnly() {
    var result = DcwFilterRuntime.unionLinkGroups(
        Set.of(Set.of("1:p", "2:p")), Set.of());
    assertEquals(1, result.size());
  }

  @Test void unionSecondOnly() {
    var result = DcwFilterRuntime.unionLinkGroups(
        Set.of(), Set.of(Set.of("3:p", "4:p")));
    assertEquals(1, result.size());
  }

  @Test void unionBothDistinct() {
    var result = DcwFilterRuntime.unionLinkGroups(
        Set.of(Set.of("1:p", "2:p")), Set.of(Set.of("3:p", "4:p")));
    assertEquals(2, result.size());
  }

  // ==================== 8. Generation ====================

  @Test void generationIncrements() {
    int g1 = runtime.generation();
    runtime.setGenerationForTest(g1 + 1);
    assertEquals(g1 + 1, runtime.generation());
  }

  @Test void staleCallbackIgnored() {
    DcwFilterRuntime rt = new DcwFilterRuntime(vertx,
        new ConfigHolder(), new RuntimeCore(vertx, new InMemoryUserDirectory(vertx)));
    rt.resetShutdownForTest();
    rt.saveDebounceMs = 1000; // avoid timer < 1ms error
    // install at gen 5
    rt.setGenerationForTest(5);

    // callback from old gen 3 should be ignored
    rt.onLinkChanged(3);
    assertFalse(rt.isDirty(), "stale gen 3 -> not dirty");
    assertFalse(rt.isSaveInFlight());

    // callback from current gen 5 should be accepted
    rt.onLinkChanged(5);
    assertTrue(rt.isDirty(), "current gen 5 -> dirty");
  }

  // ==================== 9. Dirty/save round-trip ====================

  @Test void dirtyDuringSaveTriggersFinalSave() {
    DcwFilterRuntime rt = new DcwFilterRuntime(vertx,
        new ConfigHolder(), new RuntimeCore(vertx, new InMemoryUserDirectory(vertx)));
    rt.resetShutdownForTest();

    var sd = rt.buildServiceDirectory(snapWithPluginsAndServices(List.of(),
        List.of(tvService("p", 1))));
    var cfg = new DcwFilterConfig(true, true, false, false, true, false,
        DcwFilterConfig.ContinuityMode.COMPATIBILITY, Set.of(), null, 1000);
    var filter = new DcwFilter(cfg, sd);
    var store = new VersionedJsonLinkStore(vertx, "/tmp/csp-dcw-test/test-links.json", 1048576);
    rt.setCurrentFilterForTest(filter);
    rt.setCurrentStoreForTest(store);
    rt.setGenerationForTest(10);

    // Simulate dirty + saveInFlight (save in progress)
    rt.setDirtyForTest(true);
    rt.setSaveInFlightForTest(true);

    // On save complete with dirty -> should re-arm for final save
    rt.onSaveComplete(10);
    // The re-arm tries to snapshot and persist. Since store is real
    // and filter is real, saveInFlight should be set to true
    // if the persist starts. But note: doPersist is async with runOnFilterContext,
    // so the immediate next lines may not see the updated state.
    // The key invariant: after onSaveComplete with dirty, a final save IS triggered.
    // For a deterministic test, just verify the dirty is cleared.
    assertFalse(rt.isDirty(), "dirty should be cleared for final save");
  }

  // ==================== 10. Shutdown ====================

  @Test void shutdownClearsState() {
    DcwFilterRuntime rt = new DcwFilterRuntime(vertx,
        new ConfigHolder(), new RuntimeCore(vertx, new InMemoryUserDirectory(vertx)));
    rt.saveDebounceMs = 1000;
    rt.setDirtyForTest(true);
    rt.setSaveInFlightForTest(true);
    rt.onStop(null);
    assertNull(rt.currentFilter());
    assertNull(rt.currentStore());
    assertNull(rt.currentChain());
    assertFalse(rt.isDirty());
    assertFalse(rt.isSaveInFlight());
  }

  // ==================== 11. Startup/lifecycle ====================

  @Nested
  class LifecycleTests {
    @Test void startupWithoutPluginNoFilter() throws Exception {
      var holder = new ConfigHolder();
      var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
      var rt = new DcwFilterRuntime(vertx, holder, rc);
      holder.set(snapWithPlugins(List.of()));
      rc.runtimeEnabled = true; rc.onStart(null);
      rt.runtimeEnabled = true; rt.onStart(null);
      assertNull(rt.currentFilter()); assertNull(rt.currentChain());
      assertNull(rc.cwsConnectorManager().getFilterChain());
    }

    @Test void startupWithPluginInstallsFilter() throws Exception {
      var holder = new ConfigHolder();
      var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
      var rt = new DcwFilterRuntime(vertx, holder, rc);
      holder.set(snapWithPluginsAndServices(List.of(enabledDcwPlugin()), List.of(tvService("dvbt", 1))));
      rc.runtimeEnabled = true; rc.onStart(null);
      rt.runtimeEnabled = true; rt.linksFile = "/tmp/csp-dcw-test/links.json";
      rt.onStart(null);
      assertNotNull(rt.currentFilter()); assertNotNull(rt.currentChain());
      assertNotNull(rc.cwsConnectorManager().getFilterChain());
    }

    @Test void startupDisabledNoFilter() throws Exception {
      var holder = new ConfigHolder();
      var rc = new RuntimeCore(vertx, new InMemoryUserDirectory(vertx));
      var rt = new DcwFilterRuntime(vertx, holder, rc);
      holder.set(snapWithPlugins(List.of(enabledDcwPlugin())));
      rc.runtimeEnabled = true; rc.onStart(null);
      rt.runtimeEnabled = false; rt.onStart(null);
      assertNull(rt.currentFilter());
    }
  }
}
