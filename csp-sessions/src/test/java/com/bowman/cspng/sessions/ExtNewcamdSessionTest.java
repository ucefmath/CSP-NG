package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.config.*;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.MD5Crypt;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExtNewcamdSessionTest {

  Vertx vertx;

  @AfterEach
  void close() { if (vertx != null) vertx.close(); }

  private void setField(Object t, String n, Object v) throws Exception {
    Field f = null;
    Class<?> c = t.getClass();
    while (c != null) {
      try { f = c.getDeclaredField(n); break; } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
    }
    assertNotNull(f, "field " + n);
    f.setAccessible(true); f.set(t, v);
  }
  private Object getField(Object t, String n) throws Exception {
    Field f = null;
    Class<?> c = t.getClass();
    while (c != null) {
      try { f = c.getDeclaredField(n); break; } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
    }
    assertNotNull(f, "field " + n);
    f.setAccessible(true); return f.get(t);
  }

  private ConfigSnapshot snapshot(boolean catchAll, List<ProfileConfig> profiles, List<ProfileProviderIdentConfig> idents) {
    var ext = new ExtendedNewcamdConfig(true, 2233, "127.0.0.1", catchAll, false, "env:DES", null, null, 2048, 1);
    return new ConfigSnapshot(RootConfig.DEFAULT, LoggingConfig.DEFAULT, CaProfilesGlobalConfig.DEFAULT,
        profiles, List.of(), List.of(), ConnectionManagerConfig.DEFAULT, List.of(), List.of(), List.of(),
        CacheConfig.DEFAULT, ClusterUdpConfig.DEFAULT, WebUiConfig.DEFAULT, List.of(), List.of(), ext, List.of(), idents, System.currentTimeMillis());
  }

  @Test
  void mappingUserAclEmptyAllAndRestrict() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var p1 = new ProfileConfig("p1", true, false, false, 0x0100, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var p2 = new ProfileConfig("p2", true, false, false, 0x0200, 0x002, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i1 = new ProfileProviderIdentConfig(1, "p1", 0x111111, null, 1);
    var i2 = new ProfileProviderIdentConfig(2, "p2", 0x222222, null, 1);
    holder.set(snapshot(false, List.of(p1,p2), List.of(i1,i2)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("uAll", "uAll", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    dir.add(new UserRecord("uRest", "uRest", MD5Crypt.crypt("p","s"), true, false, Set.of("p1"), 5));
    var reg = new SessionRegistry(vertx, dir);
    var cache = new CacheVerticle();
    var sAll = new ExtNewcamdSession(vertx, cache, reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    var sRest = new ExtNewcamdSession(vertx, cache, reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(sAll, "username", "uAll"); setField(sAll, "authenticated", true); setField(sAll, "alive", true);
    setField(sRest, "username", "uRest"); setField(sRest, "authenticated", true); setField(sRest, "alive", true);
    Method m = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); m.setAccessible(true);
    var mapAll = (java.util.Map<?,?>) m.invoke(sAll);
    var mapRest = (java.util.Map<?,?>) m.invoke(sRest);
    assertEquals(2, mapAll.size());
    assertEquals(1, mapRest.size());
  }

  @Test
  void disabledCaid0OnId0ProviderNoneFiltered() throws Exception {
    vertx = Vertx.vertx();
    var holder = new ConfigHolder();
    var pDisabled = new ProfileConfig("pD", false, false, false, 0x0100, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var pZeroCaid = new ProfileConfig("pZ", true, false, false, 0, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var pZeroOn = new ProfileConfig("pO", true, false, false, 0x0100, 0, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var pNoProv = new ProfileConfig("pN", true, false, false, 0x0100, 0x001, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i = new ProfileProviderIdentConfig(1, "pN", 0x111111, null, 1);
    // pN has provider but we will not include? Actually pN has provider, but others filtered
    holder.set(snapshot(false, List.of(pDisabled, pZeroCaid, pZeroOn, pNoProv), List.of(i)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var s = new ExtNewcamdSession(vertx, cache(), reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(s, "username", "u"); setField(s, "authenticated", true); setField(s, "alive", true);
    Method m = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); m.setAccessible(true);
    var map = (java.util.Map<?,?>) m.invoke(s);
    assertEquals(1, map.size()); // only pN qualifies, others filtered
  }

  private CacheVerticle cache() {
    // simple cache not deployed
    return new CacheVerticle();
  }

  @Test
  void collisionCatchAllVsNonCatch() throws Exception {
    vertx = Vertx.vertx();
    var p1 = new ProfileConfig("p1", true, false, false, 0x1000, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var p2 = new ProfileConfig("p2", true, false, false, 0x1000, 2, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i1 = new ProfileProviderIdentConfig(1, "p1", 0xAAAAAA, null, 1);
    var i2 = new ProfileProviderIdentConfig(2, "p2", 0xAAAAAA, null, 1);
    var holderCatch = new ConfigHolder(); holderCatch.set(snapshot(true, List.of(p1,p2), List.of(i1,i2)));
    var holderNoCatch = new ConfigHolder(); holderNoCatch.set(snapshot(false, List.of(p1,p2), List.of(i1,i2)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var sCatch = new ExtNewcamdSession(vertx, cache(), reg, new byte[14], holderCatch, 2233, holderCatch.get().extendedNewcamd());
    var sNoCatch = new ExtNewcamdSession(vertx, cache(), reg, new byte[14], holderNoCatch, 2233, holderNoCatch.get().extendedNewcamd());
    setField(sCatch, "username", "u"); setField(sCatch, "authenticated", true); setField(sCatch, "alive", true);
    setField(sNoCatch, "username", "u"); setField(sNoCatch, "authenticated", true); setField(sNoCatch, "alive", true);
    Method m = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); m.setAccessible(true);
    var mapCatch = (java.util.Map<?,?>) m.invoke(sCatch);
    var mapNoCatch = m.invoke(sNoCatch);
    assertEquals(1, mapCatch.size(), "catchAll keeps first");
    assertNull(mapNoCatch, "non-catch collision should return null fail-closed");
  }

  @Test
  void cardDataFormatMainCaidProvidersOnly() throws Exception {
    vertx = Vertx.vertx();
    var p1 = new ProfileConfig("p1", true, false, false, 0x1000, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var p2 = new ProfileConfig("p2", true, false, false, 0x2000, 2, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i1a = new ProfileProviderIdentConfig(1, "p1", 0x111111, null, 1);
    var i1b = new ProfileProviderIdentConfig(2, "p1", 0x111112, null, 1);
    var i2 = new ProfileProviderIdentConfig(3, "p2", 0x222222, null, 1);
    var holder = new ConfigHolder(); holder.set(snapshot(false, List.of(p1,p2), List.of(i1a,i1b,i2)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var s = new ExtNewcamdSession(vertx, cache(), reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(s, "username", "u"); setField(s, "authenticated", true); setField(s, "alive", true);
    Method m = ExtNewcamdSession.class.getDeclaredMethod("buildAnonymizedCardData", java.util.Map.class); m.setAccessible(true);
    Method bm = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); bm.setAccessible(true);
    var map = (java.util.Map) bm.invoke(s);
    byte[] cardData = (byte[]) m.invoke(s, map);
    // smallest caId is 0x1000, should have 2 providers only
    assertEquals(12 + 11*2, cardData.length);
    assertEquals(0x02, cardData[0] & 0xFF);
    assertEquals(0x10, cardData[1] & 0xFF); assertEquals(0x00, cardData[2] & 0xFF);
    assertEquals(2, cardData[11] & 0xFF);
    // provider bytes
    assertEquals(0x11, cardData[12] & 0xFF);
  }

  @Test
  void framesOffsetsAndSequence() throws Exception {
    vertx = Vertx.vertx();
    var s = new ExtNewcamdSession(vertx, cache(), new SessionRegistry(vertx), new byte[14], 2233);
    Method bCard = ExtNewcamdSession.class.getDeclaredMethod("buildCardDataMessage", byte[].class, int.class); bCard.setAccessible(true);
    byte[] cardData = new byte[23]; cardData[0]=2;
    byte[] msg = (byte[]) bCard.invoke(s, cardData, 0x1234);
    assertEquals(0x12, msg[0] & 0xFF); assertEquals(0x34, msg[1] & 0xFF);
    assertEquals(0xE4, msg[10] & 0xFF);
    assertEquals(cardData.length, ((msg[11]&0xFF)<<8)|(msg[12]&0xFF));
    Method bExt = ExtNewcamdSession.class.getDeclaredMethod("buildExtendedMessage", int.class, int.class, int.class, int.class, int.class); bExt.setAccessible(true);
    byte[] ext = (byte[]) bExt.invoke(s, 0xD3, 0xABCD, 2233, 0x1000, 0x112233);
    assertEquals(0xAB, ext[0] & 0xFF); assertEquals(0xCD, ext[1] & 0xFF);
    assertEquals(2233>>8, ext[2]&0xFF); assertEquals(2233 &0xFF, ext[3]&0xFF);
    assertEquals(0x10, ext[4]&0xFF); assertEquals(0x00, ext[5]&0xFF);
    assertEquals(0x11, ext[6]&0xFF); assertEquals(0x22, ext[7]&0xFF); assertEquals(0x33, ext[8]&0xFF);
    assertEquals(0xD3, ext[10]&0xFF);
  }

  @Test
  void ecmKnownAndUnknownCatchAll() throws Exception {
    vertx = Vertx.vertx();
    var p1 = new ProfileConfig("p1", true, false, false, 0x1000, 5, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i = new ProfileProviderIdentConfig(1, "p1", 0x010203, null, 1);
    var holderCatch = new ConfigHolder(); holderCatch.set(snapshot(true, List.of(p1), List.of(i)));
    var holderNoCatch = new ConfigHolder(); holderNoCatch.set(snapshot(false, List.of(p1), List.of(i)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var cache = new CacheVerticle(); Vertx v = Vertx.vertx(); v.deployVerticle(cache).toCompletionStage().toCompletableFuture().get();
    var sCatch = new ExtNewcamdSession(v, cache, reg, new byte[14], holderCatch, 2233, holderCatch.get().extendedNewcamd());
    var sNoCatch = new ExtNewcamdSession(v, cache, reg, new byte[14], holderNoCatch, 2233, holderNoCatch.get().extendedNewcamd());
    setField(sCatch, "username", "u"); setField(sCatch, "authenticated", true); setField(sCatch, "alive", true);
    setField(sNoCatch, "username", "u"); setField(sNoCatch, "authenticated", true); setField(sNoCatch, "alive", true);
    // need sentData populated via build
    Method bm = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); bm.setAccessible(true);
    var mapCatch = (java.util.Map) bm.invoke(sCatch); var mapNo = (java.util.Map) bm.invoke(sNoCatch);
    setField(sCatch, "sentData", new java.util.LinkedHashMap<>(mapCatch));
    setField(sNoCatch, "sentData", new java.util.LinkedHashMap<>(mapNo));
    setField(sCatch, "cardDataRequested", true); setField(sNoCatch, "cardDataRequested", true);
    // Create ECM for known pair: caid 0x1000 provider 0x010203 -> should route to p1
    byte[] decKnown = new byte[13+5]; decKnown[0]=0; decKnown[1]=1; decKnown[4]=0x10; decKnown[5]=0x00; decKnown[6]=0x01; decKnown[7]=0x02; decKnown[8]=0x03; decKnown[10]=(byte)0x80; decKnown[11]=0; decKnown[12]=5;
    System.arraycopy(new byte[]{1,2,3,4,5},0,decKnown,13,5);
    // For catchAll, unknown provider
    byte[] decUnknown = decKnown.clone(); decUnknown[6]=0x09; decUnknown[7]=0x09; decUnknown[8]=0x09;
    // invoke handleExtendedEcm via reflection and check not throwing
    Method he = ExtNewcamdSession.class.getDeclaredMethod("handleExtendedEcm", byte[].class, int.class); he.setAccessible(true);
    assertDoesNotThrow(() -> he.invoke(sCatch, decKnown, 0x80));
    assertDoesNotThrow(() -> he.invoke(sCatch, decUnknown, 0x80)); // catchAll should handle
    assertDoesNotThrow(() -> he.invoke(sNoCatch, decUnknown, 0x80)); // no catch should send negative not crash
    v.close();
  }

  @Test
  void refreshMappingEmitsDiffSerialized() throws Exception {
    vertx = Vertx.vertx();
    var p1 = new ProfileConfig("p1", true, false, false, 0x1000, 1, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i1 = new ProfileProviderIdentConfig(1, "p1", 0x111111, null, 1);
    var holder = new ConfigHolder(); holder.set(snapshot(false, List.of(p1), List.of(i1)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u","u", MD5Crypt.crypt("p","s"), true, false, Set.of(),5));
    var reg = new SessionRegistry(vertx, dir);
    var s = new ExtNewcamdSession(vertx, new CacheVerticle(), reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(s, "username", "u"); setField(s, "authenticated", true); setField(s, "alive", true);
    // simulate card request to populate sentData
    byte[] decReq = new byte[13]; decReq[10]=(byte)0xE3;
    Method hcr = ExtNewcamdSession.class.getDeclaredMethod("handleCardDataReq", byte[].class); hcr.setAccessible(true);
    // mock socket to avoid NPE in sendEncrypted: set desKey16 and mock socket that discards writes
    setField(s, "desKey16", new byte[16]); // dummy to allow sendEncrypted to not NPE? but sendEncrypted checks socket null -> return, so no need
    hcr.invoke(s, decReq);
    assertTrue(s.isCardDataRequested());
    assertEquals(1, s.getSentData().size());
    // now add new provider to snapshot
    var i2 = new ProfileProviderIdentConfig(2, "p1", 0x222222, null, 1);
    holder.set(snapshot(false, List.of(p1), List.of(i1,i2)));
    CountDownLatch latch = new CountDownLatch(1);
    s.refreshMappingAsync();
    vertx.setTimer(200, id -> latch.countDown());
    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertEquals(2, s.getSentData().size());
  }

  @Test
  void truncatedLengthsNoAIOOBE() throws Exception {
    vertx = Vertx.vertx();
    var s = new ExtNewcamdSession(vertx, cache(), new SessionRegistry(vertx), new byte[14], 2233);
    setField(s, "authenticated", true); setField(s, "alive", true);
    Method hm = ExtNewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class); hm.setAccessible(true);
    for (int len=0; len<=13; len++) {
      byte[] dec = new byte[len];
      if (len>=11) dec[10]=(byte)0x80;
      assertDoesNotThrow(() -> hm.invoke(s, (Object)dec));
      setField(s, "alive", true); setField(s, "authenticated", true);
    }
    // declared length > available should close
    byte[] dec = new byte[13+2]; dec[10]=(byte)0x80; dec[11]=0; dec[12]=5; // declared 5 but only 2 available
    hm.invoke(s, (Object)dec);
    assertFalse((Boolean) getField(s, "alive"));
  }

  @Test
  void profileNamePropagatedInEcmRequest() throws Exception {
    var s = new NewcamdSession(Vertx.vertx(), cache(), new SessionRegistry(Vertx.vertx()), new byte[14]);
    s.setProfileName("myProfile");
    var key = new EcmKey(0x80, 100, 1, 0x1000, 123);
    var req = s.buildEcmRequest(key, new byte[]{1,2}, 5000);
    // buildEcmRequest is protected, use reflection
    Method m = com.bowman.cspng.sessions.AbstractSession.class.getDeclaredMethod("buildEcmRequest", EcmKey.class, byte[].class, long.class); m.setAccessible(true);
    var req2 = (EcmRequest) m.invoke(s, key, new byte[]{1,2}, 5000L);
    assertEquals("myProfile", req2.profileName());
    var r = new RadegastSession(Vertx.vertx(), cache(), new SessionRegistry(Vertx.vertx()));
    r.setProfileName("radegastProf");
    var req3 = (EcmRequest) m.invoke(r, key, new byte[]{1,2}, 5000L);
    assertEquals("radegastProf", req3.profileName());
  }

  @Test
  void serviceIdFromBytes2_3NotSeq() throws Exception {
    vertx = Vertx.vertx();
    var p = new ProfileConfig("p1", true, false, false, 0x1000, 5, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i = new ProfileProviderIdentConfig(1, "p1", 0x010203, null, 1);
    var holder = new ConfigHolder(); holder.set(snapshot(false, List.of(p), List.of(i)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var cache = new CacheVerticle();
    class Cap extends ExtNewcamdSession {
      EcmRequest captured;
      Cap(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k, ConfigHolder h, int port, ExtendedNewcamdConfig ec) { super(v,c,r,k,h,port,ec); }
      @Override protected void processEcm(EcmRequest req, NewcamdSession.ReplyHeader h) { captured = req; }
      @Override protected void processEcm(EcmRequest req) { captured = req; }
      @Override protected void sendEncrypted(byte[] msg) {}
      @Override protected void sendCwReply(EcmRequest req, com.bowman.cspng.domain.CwReply rep, NewcamdSession.ReplyHeader h) {}
      @Override protected void sendCwReply(EcmRequest req, com.bowman.cspng.domain.CwReply rep) {}
    }
    var cap = new Cap(vertx, cache, reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(cap, "username", "u"); setField(cap, "authenticated", true); setField(cap, "alive", true);
    // need sentData
    Method bm = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); bm.setAccessible(true);
    var map = (java.util.Map) bm.invoke(cap);
    setField(cap, "sentData", new java.util.LinkedHashMap<>(map));
    setField(cap, "cardDataRequested", true);
    // seq 0xAAAA, sid 0xBBBB, caId 0x1000 provider 0x010203
    byte[] dec = new byte[13+4]; dec[0]= (byte)0xAA; dec[1]=(byte)0xAA; dec[2]=(byte)0xBB; dec[3]=(byte)0xBB; dec[4]=0x10; dec[5]=0x00; dec[6]=0x01; dec[7]=0x02; dec[8]=0x03; dec[10]=(byte)0x80; dec[11]=0; dec[12]=4;
    System.arraycopy(new byte[]{9,9,9,9},0,dec,13,4);
    Method he = ExtNewcamdSession.class.getDeclaredMethod("handleExtendedEcm", byte[].class, int.class); he.setAccessible(true);
    he.invoke(cap, dec, 0x80);
    assertNotNull(cap.captured);
    assertEquals(0xBBBB, cap.captured.key().serviceId(), "serviceId must be bytes 2..3 not seq");
    assertEquals(5, cap.captured.key().networkId());
    assertEquals("p1", cap.captured.profileName());
  }

  @Test
  void ecmCaptureFramesAndRequests() throws Exception {
    vertx = Vertx.vertx();
    var p1 = new ProfileConfig("p1", true, false, false, 0x1000, 7, null, null, 5000, null, false, 0, null, null, null, null, 1);
    var i = new ProfileProviderIdentConfig(1, "p1", 0xAABBCC, null, 1);
    var holder = new ConfigHolder(); holder.set(snapshot(false, List.of(p1), List.of(i)));
    var dir = new InMemoryUserDirectory(vertx);
    dir.add(new UserRecord("u", "u", MD5Crypt.crypt("p","s"), true, false, Set.of(), 5));
    var reg = new SessionRegistry(vertx, dir);
    var cache = new CacheVerticle();
    vertx.deployVerticle(cache).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    class Cap extends ExtNewcamdSession {
      java.util.List<byte[]> frames = new java.util.ArrayList<>();
      java.util.List<EcmRequest> reqs = new java.util.ArrayList<>();
      Cap(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k, ConfigHolder h, int port, ExtendedNewcamdConfig ec) { super(v,c,r,k,h,port,ec); }
      @Override protected void sendEncrypted(byte[] msg) { frames.add(msg.clone()); }
      @Override protected void processEcm(EcmRequest req, NewcamdSession.ReplyHeader h) { reqs.add(req); }
      @Override protected void processEcm(EcmRequest req) { reqs.add(req); }
      @Override protected void sendCwReply(EcmRequest req, com.bowman.cspng.domain.CwReply rep, NewcamdSession.ReplyHeader h) {}
      @Override protected void sendCwReply(EcmRequest req, com.bowman.cspng.domain.CwReply rep) {}
    }
    var cap = new Cap(vertx, cache, reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(cap, "username", "u"); setField(cap, "authenticated", true); setField(cap, "alive", true);
    Method bm = ExtNewcamdSession.class.getDeclaredMethod("buildProfileMap"); bm.setAccessible(true);
    var map = (java.util.Map) bm.invoke(cap);
    setField(cap, "sentData", new java.util.LinkedHashMap<>(map));
    setField(cap, "cardDataRequested", true);
    // known
    byte[] decKnown = new byte[13+3]; decKnown[0]=0; decKnown[1]=2; decKnown[2]=0; decKnown[3]=0x10; decKnown[4]=0x10; decKnown[5]=0x00; decKnown[6]=(byte)0xAA; decKnown[7]=(byte)0xBB; decKnown[8]=(byte)0xCC; decKnown[10]=(byte)0x80; decKnown[11]=0; decKnown[12]=3;
    Method he = ExtNewcamdSession.class.getDeclaredMethod("handleExtendedEcm", byte[].class, int.class); he.setAccessible(true);
    he.invoke(cap, decKnown, 0x80);
    assertEquals(1, cap.reqs.size());
    assertEquals("p1", cap.reqs.get(0).profileName());
    assertEquals(7, cap.reqs.get(0).key().networkId());
    // unknown catchAll -> cache-only, no processEcm
    var holderCatch = new ConfigHolder(); holderCatch.set(snapshot(true, List.of(p1), List.of(i)));
    var cap2 = new Cap(vertx, cache, reg, new byte[14], holderCatch, 2233, holderCatch.get().extendedNewcamd());
    setField(cap2, "username", "u"); setField(cap2, "authenticated", true); setField(cap2, "alive", true);
    setField(cap2, "sentData", new java.util.LinkedHashMap<>(map));
    setField(cap2, "cardDataRequested", true);
    byte[] decUnknown = decKnown.clone(); decUnknown[6]=0x11; decUnknown[7]=0x22; decUnknown[8]=0x33;
    he.invoke(cap2, decUnknown, 0x80);
    assertEquals(0, cap2.reqs.size()); // cache-only, no connector
    // unknown non-catch -> negative, no processEcm, but sendCwReply would be called (we override to no-op, so still 0 reqs)
    var cap3 = new Cap(vertx, cache, reg, new byte[14], holder, 2233, holder.get().extendedNewcamd());
    setField(cap3, "username", "u"); setField(cap3, "authenticated", true); setField(cap3, "alive", true);
    setField(cap3, "sentData", new java.util.LinkedHashMap<>(map));
    setField(cap3, "cardDataRequested", true);
    he.invoke(cap3, decUnknown, 0x80);
    assertEquals(0, cap3.reqs.size());
  }
}
