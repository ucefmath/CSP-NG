package com.bowman.cspng.app.runtime;

import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ConfigWatcher;
import com.bowman.cspng.config.PluginConfig;
import com.bowman.cspng.config.ServiceMapEntryConfig;
import com.bowman.cspng.filters.*;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Runtime de câblage du filtre DCW.
 *
 * <p>Démarre avec {@code @Priority(225)} (entre {@link RuntimeCore} à 200
 * et {@link ConnectorLifecycleManager} à 250), shutdown {@code @Priority(175)}.
 *
 * <h3>Modèle de concurrence</h3>
 * L'état dirty/saveInFlight/timer est <b>strictement confiné au filter context</b>
 * (accédé uniquement via {@code CwsConnectorManager.runOnFilterContext}).
 * Aucun {@code synchronized} ni lock. Les champs {@code volatile} sont
 * une défense en profondeur ; les lectures cross-thread (shutdown, tests)
 * ne font pas partie du datapath normal.
 */
@ApplicationScoped
public class DcwFilterRuntime {

  private static final Logger LOG = Logger.getLogger(DcwFilterRuntime.class.getName());

  static final int MAX_CONFIG_JSON_BYTES = 65_536;
  static final int DEFAULT_MAX_FILE_SIZE = 1_048_576;
  static final int MIN_MAX_FILE_SIZE = 8_192;
  static final int MAX_MAX_FILE_SIZE = 10_485_760;
  static final int DEFAULT_SAVE_DEBOUNCE_MS = 1000;
  static final int MIN_DEBOUNCE_MS = 100;
  static final int MAX_DEBOUNCE_MS = 60_000;
  static final long STARTUP_TIMEOUT_SECONDS = 10;
  static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

  /**
   * Legacy TV service types exacts.
   * Source de référence : CardServProxy legacy (com.bowman.cardserv.tv.TvService).
   */
  private static final Set<Integer> TV_SERVICE_TYPES = Set.of(1, 2, 17, 25, 211);

  private static final String DCW_CLASS_NAME = "com.bowman.cspng.filters.DcwFilter";

  private final Vertx vertx;
  private final ConfigHolder holder;
  private final RuntimeCore runtimeCore;

  @Inject
  @ConfigProperty(name = "csp.runtime.enabled", defaultValue = "true")
  boolean runtimeEnabled;

  @Inject
  @ConfigProperty(name = "csp.dcw.links-file", defaultValue = "/var/lib/csp/links.json")
  String linksFile;

  @Inject
  @ConfigProperty(name = "csp.dcw.max-file-size-bytes", defaultValue = "" + DEFAULT_MAX_FILE_SIZE)
  int maxFileSizeBytes;

  @Inject
  @ConfigProperty(name = "csp.dcw.save-debounce-ms", defaultValue = "" + DEFAULT_SAVE_DEBOUNCE_MS)
  int saveDebounceMs;

  // --- volatile state (lecture cross-thread sûre) ---
  private volatile DcwFilter currentFilter;
  private volatile VersionedJsonLinkStore currentStore;
  private volatile FilterChain currentChain;
  private volatile boolean shutdown;

  // --- génération (AtomicInteger) ---
  private final AtomicInteger generation = new AtomicInteger(0);

  // --- état de persistance (confiné au filter context, volatile en défense) ---
  volatile boolean dirty;
  volatile boolean saveInFlight;
  volatile Future<Void> activeSaveFuture;
  long debounceTimerId = -1;

  // --- coalescing reload ---
  private final AtomicBoolean reloadInFlight = new AtomicBoolean(false);
  private final AtomicBoolean reloadDirty = new AtomicBoolean(false);

  private MessageConsumer<Object> reloadConsumer;

  @Inject
  public DcwFilterRuntime(Vertx vertx, ConfigHolder holder, RuntimeCore runtimeCore) {
    this.vertx = vertx;
    this.holder = holder;
    this.runtimeCore = runtimeCore;
  }

  // ==================== startup ====================

  public void onStart(@Observes @Priority(225) StartupEvent event) {
    if (!runtimeEnabled) {
      LOG.info("DcwFilterRuntime disabled (csp.runtime.enabled=false)");
      return;
    }
    if (!holder.isInitialized())
      throw new IllegalStateException("DcwFilterRuntime: ConfigHolder not initialized");
    if (runtimeCore != null && !runtimeCore.isStarted() && runtimeCore.isEnabled())
      throw new IllegalStateException("DcwFilterRuntime: RuntimeCore not started");
    if (Context.isOnEventLoopThread())
      LOG.warning("DcwFilterRuntime.onStart on event-loop — blocking calls may stall");

    validateConfig();
    ConfigSnapshot snap = holder.get();
    PluginConfig pc = selectPlugin(snap);
    if (pc == null) {
      LOG.info("DcwFilterRuntime: no DcwFilter plugin found — filter disabled");
      installReloadConsumer();
      return;
    }

    DcwFilterConfig fcfg = buildFilterConfig(pc);
    ServiceDirectory sd = buildServiceDirectory(snap);
    var filter = new DcwFilter(fcfg, sd);
    var store = new VersionedJsonLinkStore(vertx, linksFile, maxFileSizeBytes);
    FilterChain chain = new FilterChain(List.of(filter));

    try {
      Set<Set<String>> loaded = store.load()
          .toCompletionStage().toCompletableFuture()
          .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
        try {
          if (!loaded.isEmpty() && fcfg.detectLinks()) filter.restoreLinkGroups(loaded);
          installFilterChain(chain, filter, store);
          return null;
        } catch (Exception e) {
          throw new RuntimeException("startup install failed", e);
        }
      }).toCompletionStage().toCompletableFuture()
          .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      LOG.info(() -> "DcwFilterRuntime started: detectLinks=" + fcfg.detectLinks()
          + " profiles=" + fcfg.profiles().size());
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "DcwFilterRuntime startup failed", e);
      throw new IllegalStateException("DcwFilterRuntime startup failed", e);
    }
    installReloadConsumer();
  }

  private void validateConfig() {
    if (saveDebounceMs < MIN_DEBOUNCE_MS || saveDebounceMs > MAX_DEBOUNCE_MS) {
      LOG.warning(() -> "csp.dcw.save-debounce-ms=" + saveDebounceMs + " clamped");
      saveDebounceMs = DEFAULT_SAVE_DEBOUNCE_MS;
    }
    if (maxFileSizeBytes < MIN_MAX_FILE_SIZE || maxFileSizeBytes > MAX_MAX_FILE_SIZE) {
      LOG.warning(() -> "csp.dcw.max-file-size-bytes=" + maxFileSizeBytes + " clamped");
      maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;
    }
  }

  private void installReloadConsumer() {
    reloadConsumer = vertx.eventBus().consumer(
        ConfigWatcher.CONFIG_RELOADED_ADDRESS, msg -> handleReload());
  }

  // ==================== sélection plugin ====================

  PluginConfig selectPlugin(ConfigSnapshot snap) {
    List<PluginConfig> candidates = new ArrayList<>();
    for (PluginConfig pc : snap.plugins()) {
      if (!pc.enabled()) continue;
      if (DCW_CLASS_NAME.equals(pc.className())
          || "dcw".equalsIgnoreCase(pc.name())
          || "dcwfilterplugin".equalsIgnoreCase(pc.name())) {
        candidates.add(pc);
      }
    }
    if (candidates.isEmpty()) return null;
    if (candidates.size() > 1)
      throw new IllegalStateException("Multiple DcwFilter plugins enabled — only one allowed");
    return candidates.get(0);
  }

  // ==================== construction config ====================

  DcwFilterConfig buildFilterConfig(PluginConfig pc) {
    Set<String> profiles = parseProfiles(pc.profiles());
    boolean detectLinks = false, verifyReplies = false, forceContinuity = false;
    boolean zeroCounting = true, shadowMode = false;
    DcwFilterConfig.ContinuityMode cm = DcwFilterConfig.ContinuityMode.COMPATIBILITY;
    int maxEntries = 10_000;
    Set<byte[]> badDcws = null;

    String json = pc.configJson();
    if (json != null && !json.isBlank()) {
      JsonObject root = parseConfigJson(json);
      detectLinks = optBool(root, "detectLinks", false);
      verifyReplies = optBool(root, "verifyReplies", false);
      forceContinuity = optBool(root, "forceContinuity", false);
      zeroCounting = optBool(root, "zeroCounting", true);
      shadowMode = optBool(root, "shadowMode", false);
      if (root.containsKey("continuityMode")) {
        String s = root.getString("continuityMode");
        if ("STRICT".equals(s)) cm = DcwFilterConfig.ContinuityMode.STRICT;
        else if ("COMPATIBILITY".equals(s)) cm = DcwFilterConfig.ContinuityMode.COMPATIBILITY;
        else throw new IllegalArgumentException("Invalid continuityMode: " + s);
      }
      if (root.containsKey("maxEntries")) {
        maxEntries = root.getInteger("maxEntries");
        if (maxEntries < 100) throw new IllegalArgumentException("maxEntries must be >= 100");
      }
      if (root.containsKey("badDcws")) badDcws = parseBadDcws(root);
    }
    return new DcwFilterConfig(pc.enabled(), detectLinks, verifyReplies, forceContinuity,
        zeroCounting, shadowMode, cm, profiles, badDcws, maxEntries);
  }

  // ==================== parse configJson ====================

  JsonObject parseConfigJson(String json) {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_CONFIG_JSON_BYTES)
      throw new IllegalArgumentException("configJson exceeds max size " + MAX_CONFIG_JSON_BYTES + " bytes");
    JsonObject root;
    try { root = new JsonObject(json); }
    catch (Exception e) { throw new IllegalArgumentException("configJson is not valid JSON", e); }

    Set<String> allowed = Set.of("detectLinks", "verifyReplies", "forceContinuity",
        "zeroCounting", "shadowMode", "continuityMode", "maxEntries", "badDcws");
    for (String field : root.fieldNames())
      if (!allowed.contains(field))
        throw new IllegalArgumentException("configJson unknown field: " + field);

    for (String bf : List.of("detectLinks", "verifyReplies", "forceContinuity",
        "zeroCounting", "shadowMode")) {
      if (root.containsKey(bf) && !(root.getValue(bf) instanceof Boolean))
        throw new IllegalArgumentException("configJson field " + bf + " must be boolean");
    }
    if (root.containsKey("continuityMode")) {
      Object v = root.getValue("continuityMode");
      if (!(v instanceof String))
        throw new IllegalArgumentException("configJson continuityMode must be string");
      String cm = (String) v;
      if (!"COMPATIBILITY".equals(cm) && !"STRICT".equals(cm))
        throw new IllegalArgumentException("configJson continuityMode must be COMPATIBILITY or STRICT");
    }
    if (root.containsKey("maxEntries") && !(root.getValue("maxEntries") instanceof Integer))
      throw new IllegalArgumentException("configJson maxEntries must be integer");
    if (root.containsKey("badDcws")) {
      try { root.getJsonArray("badDcws"); }
      catch (ClassCastException e) { throw new IllegalArgumentException("configJson badDcws must be array"); }
    }
    return root;
  }

  static boolean optBool(JsonObject root, String key, boolean def) {
    return root.containsKey(key) ? root.getBoolean(key) : def;
  }

  // ==================== parse badDcws ====================

  Set<byte[]> parseBadDcws(JsonObject root) {
    JsonArray arr = root.getJsonArray("badDcws");
    if (arr == null || arr.size() == 0) return null;
    Set<byte[]> result = new LinkedHashSet<>();
    for (int i = 0; i < arr.size(); i++) {
      Object elem = arr.getValue(i);
      if (!(elem instanceof String))
        throw new IllegalArgumentException("badDcws entry " + i + " must be a string");
      String hex = (String) elem;
      if (hex.isBlank())
        throw new IllegalArgumentException("badDcws entry " + i + " is empty");
      String cleaned = hex.replaceAll("[\\s:]", "");
      if (cleaned.length() != 32)
        throw new IllegalArgumentException("badDcws entry " + i + " must be 16 bytes hex (32 hex chars)");
      result.add(hexToBytesValidated(cleaned));
    }
    return result;
  }

  static byte[] hexToBytesValidated(String hex) {
    int len = hex.length();
    if ((len & 1) != 0) throw new IllegalArgumentException("odd hex length");
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      if (hi < 0 || lo < 0)
        throw new IllegalArgumentException("invalid hex char at position " + i);
      data[i / 2] = (byte) ((hi << 4) | lo);
    }
    return data;
  }

  // ==================== parse profiles ====================

  static Set<String> parseProfiles(String s) {
    if (s == null || s.isBlank()) return Set.of();
    return Arrays.stream(s.split("[\\s,]+")).map(String::trim)
        .filter(x -> !x.isEmpty()).collect(Collectors.toUnmodifiableSet());
  }

  // ==================== ServiceDirectory ====================

  ServiceDirectory buildServiceDirectory(ConfigSnapshot snap) {
    Map<String, ServiceDirectory.ServiceDescriptor> map = new LinkedHashMap<>();
    for (ServiceMapEntryConfig e : snap.serviceMap()) {
      if (!TV_SERVICE_TYPES.contains(e.serviceType())) continue;
      String key = e.profileName() + ":" + e.serviceId();
      if (map.containsKey(key)) continue;
      String dn = e.serviceName() != null ? e.serviceName() : Integer.toHexString(e.serviceId());
      if (dn.length() > 128) dn = dn.substring(0, 128);
      map.put(key, new ServiceDirectory.ServiceDescriptor(e.profileName(), e.serviceId(), dn, true));
    }
    Map<String, ServiceDirectory.ServiceDescriptor> imm = Map.copyOf(map);
    return (profile, serviceId) -> Optional.ofNullable(imm.get(profile + ":" + serviceId));
  }

  // ==================== reload ====================

  void handleReload() {
    if (shutdown) return;
    if (reloadInFlight.compareAndSet(false, true)) {
      vertx.executeBlocking(() -> { doReload(); return null; }, false)
          .onComplete(ar -> {
            reloadInFlight.set(false);
            if (!shutdown && reloadDirty.compareAndSet(true, false))
              handleReload();
          });
    } else {
      reloadDirty.set(true);
    }
  }

  private void doReload() {
    try {
      ConfigSnapshot snap = holder.get();
      PluginConfig pc = selectPlugin(snap);

      DcwFilter newFilter = null;
      VersionedJsonLinkStore newStore = null;
      FilterChain newChain = null;
      if (pc != null) {
        DcwFilterConfig fcfg = buildFilterConfig(pc);
        newFilter = new DcwFilter(fcfg, buildServiceDirectory(snap));
        newStore = new VersionedJsonLinkStore(vertx, linksFile, maxFileSizeBytes);
        newChain = new FilterChain(List.of(newFilter));
      }

      DcwFilter oldFilter = currentFilter;
      VersionedJsonLinkStore oldStore = currentStore;

      // --- flush old via filter context ---
      if (oldFilter != null && oldStore != null) {
        try {
          flushViaFilterContext(oldFilter, oldStore)
              .toCompletionStage().toCompletableFuture()
              .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          LOG.warning("DcwFilterRuntime reload: old flush failed — keeping old filter");
          return;
        }
      }

      DcwFilter f = newFilter;
      VersionedJsonLinkStore s = newStore;
      FilterChain c = newChain;
      DcwFilter oldF = oldFilter;
      int myGen = generation.incrementAndGet();

      if (c == null) {
        runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
          runtimeCore.cwsConnectorManager().setFilterChain(null);
          currentFilter = null; currentStore = null; currentChain = null;
          return null;
        }).toCompletionStage().toCompletableFuture()
            .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        LOG.info("DcwFilterRuntime reloaded: no plugin → filter removed");
      } else {
        Set<Set<String>> loaded;
        try {
          loaded = s.load().toCompletionStage().toCompletableFuture()
              .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
          LOG.log(Level.WARNING, "DcwFilterRuntime reload: new load failed — keeping old filter", e);
          return;
        }

        runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
          try {
            Set<Set<String>> oldSnap = (oldF != null && oldF.config().detectLinks())
                ? oldF.linkGroupsSnapshot() : Set.of();
            Set<Set<String>> merged = unionLinkGroups(oldSnap, loaded);
            if (!merged.isEmpty() && f.config().detectLinks()) f.restoreLinkGroups(merged);
            installFilterChain(c, f, s, myGen);
            LOG.info("DcwFilterRuntime reloaded: merged=" + merged.size());
          } catch (Exception e) {
            LOG.log(Level.WARNING, "DcwFilterRuntime reload install failed — keeping old", e);
          }
          return null;
        }).toCompletionStage().toCompletableFuture()
            .get(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "DcwFilterRuntime reload failed — keeping old filter", e);
    }
  }

  static Set<Set<String>> unionLinkGroups(Set<Set<String>> a, Set<Set<String>> b) {
    if (a.isEmpty()) return b.isEmpty() ? Set.of() : Set.copyOf(b);
    if (b.isEmpty()) return Set.copyOf(a);
    Set<Set<String>> r = new LinkedHashSet<>(a); r.addAll(b);
    return Collections.unmodifiableSet(r);
  }

  void installFilterChain(FilterChain chain, DcwFilter filter,
                           VersionedJsonLinkStore store, int capturedGen) {
    filter.setLinkChangeListener(() -> onLinkChanged(capturedGen));
    runtimeCore.cwsConnectorManager().setFilterChain(chain);
    currentFilter = filter; currentStore = store; currentChain = chain;
  }

  private void installFilterChain(FilterChain chain, DcwFilter filter,
                                   VersionedJsonLinkStore store) {
    installFilterChain(chain, filter, store, generation.incrementAndGet());
  }

  // ==================== flush primitive (sur filter context) ====================

  /**
   * Flush complet sur le filter context. Appelable depuis worker/shutdown.
   * Retourne un Future qui complète quand tout le flush est terminé.
   */
  Future<Void> flushViaFilterContext(DcwFilter filter, VersionedJsonLinkStore store) {
    return runtimeCore.cwsConnectorManager().runOnFilterContext(() ->
        doFlushOnContext(filter, store)
    ).compose(f -> f);
  }

  /**
   * Exécute le flush sur le filter context.
   * Attend save en vol, resnapshot, sauve si dirty.
   * DOIT être appelé sur le filter context.
   */
  private Future<Void> doFlushOnContext(DcwFilter filter, VersionedJsonLinkStore store) {
    if (filter == null || store == null) return Future.succeededFuture();

    // Si un save est en vol, se chaîner après lui puis resnapshotter
    if (saveInFlight && activeSaveFuture != null) {
      return activeSaveFuture.compose(v -> doFlushOnContext(filter, store));
    }

    if (!dirty) return Future.succeededFuture();

    dirty = false;
    saveInFlight = true;
    Set<Set<String>> groups = filter.linkGroupsSnapshot();
    Future<Void> sf = store.save(groups);
    activeSaveFuture = sf;

    return sf.onComplete(ar ->
      runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
        saveInFlight = false;
        if (ar.failed()) dirty = true;
        activeSaveFuture = null;
        return null;
      })
    ).mapEmpty();
  }

  // ==================== debounce persistence ====================

  void onLinkChanged(int capturedGen) {
    if (shutdown) return;
    if (capturedGen != generation.get()) return;
    dirty = true;
    scheduleDebounce(capturedGen);
  }

  private void scheduleDebounce(int capturedGen) {
    if (debounceTimerId >= 0) { vertx.cancelTimer(debounceTimerId); debounceTimerId = -1; }
    debounceTimerId = vertx.setTimer(saveDebounceMs, id ->
      runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
        executeSaveFromTimer(capturedGen); return null;
      })
    );
  }

  private void executeSaveFromTimer(int capturedGen) {
    if (shutdown || capturedGen != generation.get()) return;
    if (saveInFlight) { dirty = true; return; }
    if (!dirty) return;
    dirty = false;
    saveInFlight = true;
    DcwFilter f = currentFilter;
    VersionedJsonLinkStore s = currentStore;
    if (f == null || s == null) { saveInFlight = false; return; }
    doPersist(f.linkGroupsSnapshot(), s, capturedGen);
  }

  private void doPersist(Set<Set<String>> groups, VersionedJsonLinkStore store, int capturedGen) {
    Future<Void> f = store.save(groups);
    activeSaveFuture = f;
    f.onComplete(ar -> runtimeCore.cwsConnectorManager().runOnFilterContext(() -> {
      onSaveComplete(capturedGen); return null;
    }));
  }

  void onSaveComplete(int capturedGen) {
    if (shutdown || capturedGen != generation.get()) {
      saveInFlight = false; activeSaveFuture = null; return;
    }
    saveInFlight = false;
    if (dirty) {
      dirty = false; saveInFlight = true;
      DcwFilter f = currentFilter;
      VersionedJsonLinkStore s = currentStore;
      if (f == null || s == null) { saveInFlight = false; activeSaveFuture = null; return; }
      doPersist(f.linkGroupsSnapshot(), s, capturedGen);
    } else {
      activeSaveFuture = null;
    }
  }

  // ==================== shutdown ====================

  public void onStop(@Observes @Priority(175) ShutdownEvent event) {
    shutdown = true;
    cancelTimers();
    if (reloadConsumer != null) { reloadConsumer.unregister(); reloadConsumer = null; }

    DcwFilter f = currentFilter;
    VersionedJsonLinkStore s = currentStore;
    if (f == null || s == null) { uninstallChain(); return; }

    if (Context.isOnEventLoopThread()) {
      flushViaFilterContext(f, s).onComplete(ar -> {
        uninstallChain();
        LOG.info("DcwFilterRuntime stopped (async)");
      });
    } else {
      try {
        flushViaFilterContext(f, s).toCompletionStage().toCompletableFuture()
            .get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        LOG.info("DcwFilterRuntime stopped");
      } catch (Exception e) {
        LOG.log(Level.WARNING, "DcwFilterRuntime shutdown flush failed", e);
      } finally {
        uninstallChain();
      }
    }
  }

  private void cancelTimers() {
    if (debounceTimerId >= 0) { vertx.cancelTimer(debounceTimerId); debounceTimerId = -1; }
  }

  private void uninstallChain() {
    try { runtimeCore.cwsConnectorManager().setFilterChain(null); }
    catch (Exception e) { LOG.log(Level.WARNING, "uninstall failed", e); }
    currentFilter = null; currentStore = null; currentChain = null;
    dirty = false; saveInFlight = false; activeSaveFuture = null;
  }

  // ==================== visible for testing ====================

  DcwFilter currentFilter() { return currentFilter; }
  VersionedJsonLinkStore currentStore() { return currentStore; }
  FilterChain currentChain() { return currentChain; }
  int generation() { return generation.get(); }
  boolean isDirty() { return dirty; }
  boolean isSaveInFlight() { return saveInFlight; }

  // Test hooks — package-private
  void setDirtyForTest(boolean v) { dirty = v; }
  void setSaveInFlightForTest(boolean v) { saveInFlight = v; }
  void setActiveSaveFutureForTest(Future<Void> f) { activeSaveFuture = f; }
  void setCurrentFilterForTest(DcwFilter f) { currentFilter = f; }
  void setCurrentStoreForTest(VersionedJsonLinkStore s) { currentStore = s; }
  void setGenerationForTest(int g) { generation.set(g); }
  void resetShutdownForTest() { shutdown = false; }
  void invokeReloadForTest() { handleReload(); }
}
