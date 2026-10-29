package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.codec.newcamd.NewcamdConstants;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ConfigSnapshot;
import com.bowman.cspng.config.ExtendedNewcamdConfig;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Vertx;

import java.util.*;
import java.util.function.Supplier;

/**
 * Session Extended Newcamd — sous-classe de NewcamdSession sans duplication handshake/auth/quota.
 * Dynamic config via holder.currentConfig(), deterministic main CAID, sequence preservation, fail-close.
 */
public class ExtNewcamdSession extends NewcamdSession {

  public static final int EXT_ADD_CARD = NewcamdConstants.EXT_ADD_CARD;
  public static final int EXT_REMOVE_CARD = NewcamdConstants.EXT_REMOVE_CARD;
  public static final int EXT_GET_VERSION = NewcamdConstants.EXT_GET_VERSION;

  private final ConfigHolder holder;
  private final int listenPort;
  private final ExtendedNewcamdConfig initialExtConfig;
  private Supplier<ConfigSnapshot> snapshotSupplier;
  private long lastWarningMs = 0;
  private int warningCount = 0;

  private final Map<CaidProviderPair, ProfileConfig> sentData = new LinkedHashMap<>();
  private boolean cardDataRequested = false;

  public ExtNewcamdSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                           byte[] configKey14, ConfigHolder holder, int listenPort,
                           ExtendedNewcamdConfig extConfig) {
    super(vertx, cacheVerticle, registry, configKey14);
    this.holder = holder;
    this.listenPort = listenPort;
    this.initialExtConfig = extConfig;
    ExtendedNewcamdConfig cur = currentConfig();
    if (cur != null) setMaxMsgSize(cur.maxMsgSize());
  }

  public ExtNewcamdSession(Vertx vertx, CacheVerticle cacheVerticle, SessionRegistry registry,
                           byte[] configKey14, int listenPort) {
    this(vertx, cacheVerticle, registry, configKey14, null, listenPort, null);
  }

  public void setSnapshotSupplier(Supplier<ConfigSnapshot> sup) { this.snapshotSupplier = sup; }
  private ExtendedNewcamdConfig currentConfig() {
    ConfigSnapshot snap = null;
    if (snapshotSupplier != null) {
      try { snap = snapshotSupplier.get(); } catch (Exception ignored) {}
    }
    if (snap != null && snap.extendedNewcamd() != null) return snap.extendedNewcamd();
    if (holder != null && holder.isInitialized()) {
      try { return holder.get().extendedNewcamd(); } catch (Exception e) { return initialExtConfig; }
    }
    return initialExtConfig;
  }
  private ConfigSnapshot currentSnapshot() {
    if (snapshotSupplier != null) {
      try { ConfigSnapshot s = snapshotSupplier.get(); if (s != null) return s; } catch (Exception ignored) {}
    }
    if (holder != null && holder.isInitialized()) return holder.get();
    return null;
  }

  @Override
  protected void handleMessage(byte[] decrypted) {
    if (decrypted.length < 11) {
      logger.warning("Truncated message: " + decrypted.length);
      close();
      return;
    }
    int commandTag = decrypted[10] & 0xFF;
    if (!isAuthenticated()) {
      super.handleMessage(decrypted);
      return;
    }
    switch (commandTag) {
      case NewcamdConstants.MSG_CARD_DATA_REQ:
        handleCardDataReq(decrypted);
        return;
      case EXT_GET_VERSION:
        handleGetVersion(decrypted);
        return;
      case NewcamdConstants.MSG_KEEPALIVE: {
        int seq = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
        sendKeepAlive(new ReplyHeader(seq, 0, 0));
        return;
      }
      case 0x80: case 0x81: case 0x82:
        handleExtendedEcm(decrypted, commandTag);
        return;
      default:
        logger.fine("Unknown command tag: 0x" + Integer.toHexString(commandTag));
    }
  }

  private void handleCardDataReq(byte[] decrypted) {
    if (!isAuthenticated()) { close(); return; }
    int seq = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
    Map<CaidProviderPair, ProfileConfig> map = buildProfileMap();
    if (map == null) {
      // collision without catchAll -> fail-closed: close session, no partial state
      logger.warning("CardDataReq collision fail-closed for user '" + getUsername() + "' — closing");
      close();
      return;
    }
    byte[] cardData = buildAnonymizedCardData(map);
    byte[] cardMsg = buildCardDataMessage(cardData, seq);
    sendEncrypted(cardMsg);
    sentData.clear();
    for (Map.Entry<CaidProviderPair, ProfileConfig> e : map.entrySet()) {
      sendAddCard(e.getKey(), seq);
      sentData.put(e.getKey(), e.getValue());
    }
    cardDataRequested = true;
    ExtendedNewcamdConfig cc = currentConfig();
    if (sentData.isEmpty() && (cc == null || !cc.catchAll())) {
      logger.warning("No extended data available for user '" + getUsername() + "'");
    }
  }

  private void handleGetVersion(byte[] decrypted) {
    int seq = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
    byte[] version = "1.67".getBytes();
    byte[] msg = buildSimpleMessageWithSeq(EXT_GET_VERSION, version, seq);
    sendEncrypted(msg);
  }

  private void handleExtendedEcm(byte[] decrypted, int commandTag) {
    if (decrypted.length < 13) {
      logger.warning("Truncated extended ECM: " + decrypted.length);
      close();
      return;
    }
    int declaredLen = ((decrypted[11] & 0x0F) << 8) | (decrypted[12] & 0xFF);
    if (declaredLen > decrypted.length - 13) {
      // fail-close if declared length exceeds available
      logger.warning("Truncated ECM declaredLen " + declaredLen + " > available " + (decrypted.length - 13));
      close();
      return;
    }
    byte[] customData = new byte[declaredLen];
    if (declaredLen > 0) System.arraycopy(decrypted, 13, customData, 0, declaredLen);
    int sequence = ((decrypted[0] & 0xFF) << 8) | (decrypted[1] & 0xFF);
    int serviceId = ((decrypted[2] & 0xFF) << 8) | (decrypted[3] & 0xFF);
    int caId = ((decrypted[4] & 0xFF) << 8) | (decrypted[5] & 0xFF);
    int provider = ((decrypted[6] & 0xFF) << 16) | ((decrypted[7] & 0xFF) << 8) | (decrypted[8] & 0xFF);
    int dataHash = computeHash(customData);
    ReplyHeader header = new ReplyHeader(sequence, serviceId, caId);
    CaidProviderPair pair = new CaidProviderPair(caId, provider);
    ProfileConfig profile = sentData.get(pair);
    String profileName;
    int networkId;
    boolean cacheOnly = false;
    long maxCwWait = 5000L;
    if (profile != null) {
      profileName = profile.name();
      networkId = profile.networkId();
      cacheOnly = profile.cacheOnly();
      if (profile.maxCwWaitMs() > 0) {
        maxCwWait = profile.maxCwWaitMs();
      }
    } else {
      ExtendedNewcamdConfig cc = currentConfig();
      boolean catchAll = cc != null && cc.catchAll();
      if (catchAll) {
        profileName = ExtendedNewcamdConfig.CATCH_ALL_SENTINEL;
        networkId = -1;
        cacheOnly = true;
      } else {
        long now = System.currentTimeMillis();
        if (now - lastWarningMs > 5000) {
          warningCount = 0; lastWarningMs = now;
        }
        if (warningCount < 3) {
          warningCount++;
          logger.warning("Unknown CAID/provider pair spoof attempt (rate-limited) caId=" + String.format("%04X", caId));
        }
        EcmKey key = new EcmKey(commandTag, serviceId, -1, caId, dataHash);
        EcmRequest req = new EcmRequest(key, customData, null, getRemoteAddress(), null, System.currentTimeMillis(), null, 5000);
        sendCwReply(req, new CwReply(key, commandTag, new byte[0], null, null, System.currentTimeMillis()), header);
        return;
      }
    }
    EcmKey key = new EcmKey(commandTag, serviceId, networkId, caId, dataHash);
    EcmRequest request = new EcmRequest(key, customData, profileName, getRemoteAddress(), null, System.currentTimeMillis(), null, maxCwWait, provider);
    if (cacheOnly) {
      cacheVerticle.processRequest(0, request, true, maxCwWait).onComplete(ar -> {
        if (ar.succeeded() && ar.result().isHit()) sendCwReply(request, ar.result().reply(), header);
        else sendCwReply(request, new CwReply(key, commandTag, new byte[0], null, null, System.currentTimeMillis()), header);
      });
    } else {
      processEcm(request, header);
    }
  }

  // -- mapping --

  /**
   * Build mapping, returns null on collision without catchAll (fail-closed), empty map if no eligible profiles.
   */
  private Map<CaidProviderPair, ProfileConfig> buildProfileMap() {
    ConfigSnapshot snap = currentSnapshot();
    if (snap == null) return Collections.emptyMap();
    var rec = getUsername() != null ? registry.userDirectory().lookupCached(getUsername()) : null;
    Set<String> allowedProfiles = rec != null ? rec.profiles() : null;
    Map<CaidProviderPair, ProfileConfig> map = new LinkedHashMap<>();
    ExtendedNewcamdConfig cc = currentConfig();
    boolean catchAll = cc != null && cc.catchAll();
    for (ProfileConfig p : snap.profiles()) {
      if (!p.enabled()) continue;
      if (p.caId() <= 0 || p.networkId() <= 0) continue;
      if (allowedProfiles != null && !allowedProfiles.isEmpty() && !allowedProfiles.contains(p.name())) continue;
      var idents = snap.providerIdentsFor(p.name());
      if (idents.isEmpty()) continue;
      for (var pi : idents) {
        CaidProviderPair pair = new CaidProviderPair(p.caId(), pi.providerIdent());
        ProfileConfig existing = map.get(pair);
        if (existing != null) {
          if (!catchAll) {
            logger.warning("Collision without catchAll for pair " + pair + " profiles " + existing.name() + " vs " + p.name() + " — fail-closed");
            return null;
          } else {
            continue;
          }
        }
        map.put(pair, p);
      }
    }
    return map;
  }

  /** Serialized via exact session context (accept context) */
  public void refreshMappingAsync() {
    var ctx = getSessionContext();
    if (ctx != null) {
      ctx.runOnContext(v -> refreshMapping());
    } else if (vertx != null) {
      vertx.runOnContext(v -> refreshMapping());
    } else {
      refreshMapping();
    }
  }

  public void refreshMapping() {
    if (!isAuthenticated() || !cardDataRequested) return;
    // Ensure we run on event-loop (caller should use refreshMappingAsync)
    Map<CaidProviderPair, ProfileConfig> newMap = buildProfileMap();
    if (newMap == null) {
      // collision now -> close session to avoid partial state
      logger.warning("Refresh collision fail-closed for " + getUsername());
      close();
      return;
    }
    // Diff adds/updates
    for (Map.Entry<CaidProviderPair, ProfileConfig> e : newMap.entrySet()) {
      CaidProviderPair k = e.getKey();
      ProfileConfig v = e.getValue();
      ProfileConfig old = sentData.get(k);
      if (old == null) {
        sendAddCard(k, 0xFFFF);
        sentData.put(k, v);
      } else if (!old.name().equals(v.name())) {
        sentData.put(k, v);
      }
    }
    List<CaidProviderPair> toRemove = new ArrayList<>();
    for (CaidProviderPair k : sentData.keySet()) if (!newMap.containsKey(k)) toRemove.add(k);
    for (CaidProviderPair k : toRemove) {
      sendRemoveCard(k, 0xFFFF);
      sentData.remove(k);
    }
  }

  private void sendAddCard(CaidProviderPair pair, int seq) {
    byte[] msg = buildExtendedMessage(EXT_ADD_CARD, seq, listenPort, pair.caId, pair.providerIdent);
    sendEncrypted(msg);
  }
  private void sendRemoveCard(CaidProviderPair pair, int seq) {
    byte[] msg = buildExtendedMessage(EXT_REMOVE_CARD, seq, listenPort, pair.caId, pair.providerIdent);
    sendEncrypted(msg);
  }

  private byte[] buildCardDataMessage(byte[] cardData, int seq) {
    byte[] buf = new byte[13 + cardData.length];
    buf[0] = (byte) ((seq >> 8) & 0xFF);
    buf[1] = (byte) (seq & 0xFF);
    // bytes 2-9 remain 0 for card data
    buf[10] = (byte) NewcamdConstants.MSG_CARD_DATA;
    buf[11] = (byte) ((cardData.length >> 8) & 0xFF);
    buf[12] = (byte) (cardData.length & 0xFF);
    System.arraycopy(cardData, 0, buf, 13, cardData.length);
    return buf;
  }

  private byte[] buildSimpleMessageWithSeq(int commandTag, byte[] customData, int seq) {
    byte[] buf = new byte[13 + customData.length];
    buf[0] = (byte) ((seq >> 8) & 0xFF);
    buf[1] = (byte) (seq & 0xFF);
    buf[10] = (byte) commandTag;
    buf[11] = (byte) ((customData.length >> 8) & 0xFF);
    buf[12] = (byte) (customData.length & 0xFF);
    if (customData.length > 0) System.arraycopy(customData, 0, buf, 13, customData.length);
    return buf;
  }

  private byte[] buildExtendedMessage(int commandTag, int seq, int serviceId, int caId, int provider) {
    byte[] buf = new byte[13];
    buf[0] = (byte) ((seq >> 8) & 0xFF);
    buf[1] = (byte) (seq & 0xFF);
    buf[2] = (byte) ((serviceId >> 8) & 0xFF);
    buf[3] = (byte) (serviceId & 0xFF);
    buf[4] = (byte) ((caId >> 8) & 0xFF);
    buf[5] = (byte) (caId & 0xFF);
    buf[6] = (byte) ((provider >> 16) & 0xFF);
    buf[7] = (byte) ((provider >> 8) & 0xFF);
    buf[8] = (byte) (provider & 0xFF);
    buf[10] = (byte) commandTag;
    buf[11] = 0; buf[12] = 0;
    return buf;
  }

  private byte[] buildAnonymizedCardData(Map<CaidProviderPair, ProfileConfig> map) {
    if (map.isEmpty()) {
      byte[] buf = new byte[12 + 11];
      buf[0] = 0x02;
      buf[1] = 0; buf[2] = 0;
      buf[11] = 1;
      return buf;
    }
    // Deterministic main CAID: smallest caId sorted
    TreeMap<Integer, List<CaidProviderPair>> byCaid = new TreeMap<>();
    for (CaidProviderPair p : map.keySet()) byCaid.computeIfAbsent(p.caId, k -> new ArrayList<>()).add(p);
    int mainCaid = byCaid.firstKey();
    List<CaidProviderPair> providersForMain = byCaid.get(mainCaid);
    // Sort providers for determinism
    providersForMain.sort(Comparator.comparingInt(CaidProviderPair::providerIdent));
    int n = providersForMain.size();
    byte[] buf = new byte[12 + 11 * n];
    buf[0] = 0x02;
    buf[1] = (byte) ((mainCaid >> 8) & 0xFF);
    buf[2] = (byte) (mainCaid & 0xFF);
    buf[11] = (byte) (n & 0xFF);
    for (int i = 0; i < n; i++) {
      int off = 12 + i * 11;
      int prov = providersForMain.get(i).providerIdent;
      buf[off] = (byte) ((prov >> 16) & 0xFF);
      buf[off+1] = (byte) ((prov >> 8) & 0xFF);
      buf[off+2] = (byte) (prov & 0xFF);
    }
    return buf;
  }

  Map<CaidProviderPair, ProfileConfig> getSentData() { return Collections.unmodifiableMap(sentData); }
  boolean isCardDataRequested() { return cardDataRequested; }
  public static record CaidProviderPair(int caId, int providerIdent) {}

  @Override
  protected void cleanup() {
    super.cleanup();
    sentData.clear();
    cardDataRequested = false;
  }
}
