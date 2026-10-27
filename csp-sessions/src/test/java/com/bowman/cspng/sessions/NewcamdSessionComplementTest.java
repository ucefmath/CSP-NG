package com.bowman.cspng.sessions;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.cache.CacheResult;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NewcamdSessionComplementTest {

  Vertx vertx;

  @AfterEach
  void close() { if (vertx != null) vertx.close(); }

  private void setField(Object t, String n, Object v) throws Exception {
    Field f=null; Class<?> c=t.getClass(); while(c!=null){ try{f=c.getDeclaredField(n);break;}catch(NoSuchFieldException e){c=c.getSuperclass();}} assertNotNull(f); f.setAccessible(true); f.set(t,v);
  }
  private Object getField(Object t, String n) throws Exception {
    Field f=null; Class<?> c=t.getClass(); while(c!=null){ try{f=c.getDeclaredField(n);break;}catch(NoSuchFieldException e){c=c.getSuperclass();}} assertNotNull(f); f.setAccessible(true); return f.get(t);
  }

  @Test
  void newcamdSeqNotSidAndNetworkIdFromProfile() throws Exception {
    vertx = Vertx.vertx();
    var cache = new CacheVerticle();
    vertx.deployVerticle(cache).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    var reg = new SessionRegistry(vertx);
    var sess = new NewcamdSession(vertx, cache, reg, new byte[14]);
    sess.setProfileNetworkId(0x1234);
    setField(sess, "authenticated", true);
    setField(sess, "alive", true);
    // capture via subclass
    class Cap extends NewcamdSession {
      EcmRequest capReq; ReplyHeader capHeader;
      Cap(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k){ super(v,c,r,k);}
      @Override protected void processEcm(EcmRequest req, ReplyHeader h){ capReq=req; capHeader=h; }
    }
    var cap = new Cap(vertx, cache, reg, new byte[14]);
    cap.setProfileNetworkId(0x55AA);
    setField(cap, "authenticated", true); setField(cap, "alive", true);
    // seq 0x1111, SID 0x2222, CAID 0x3333
    byte[] dec = new byte[13+4];
    dec[0]=0x11; dec[1]=0x11; dec[2]=0x22; dec[3]=0x22; dec[4]=0x33; dec[5]=0x33; dec[10]=(byte)0x80; dec[11]=0; dec[12]=4;
    System.arraycopy(new byte[]{1,2,3,4},0,dec,13,4);
    Method hm = NewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class); hm.setAccessible(true);
    hm.invoke(cap, (Object)dec);
    assertNotNull(cap.capReq);
    assertEquals(0x2222, cap.capReq.key().serviceId(), "SID must be bytes 2..3");
    assertEquals(0x55AA, cap.capReq.key().networkId(), "networkId from profile, not bytes");
    assertEquals(0x1111, cap.capHeader.sequence(), "sequence must be bytes 0..1");
    assertEquals(0x3333, cap.capReq.key().caId());
  }

  @Test
  void concurrentEcmEachKeepsItsSequence() throws Exception {
    vertx = Vertx.vertx();
    var cache = new CacheVerticle();
    vertx.deployVerticle(cache).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    var reg = new SessionRegistry(vertx);
    class Cap extends NewcamdSession {
      List<Integer> seqs = new ArrayList<>();
      List<byte[]> replies = new ArrayList<>();
      Cap(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k){ super(v,c,r,k);}
      @Override protected void sendEncrypted(byte[] msg){ replies.add(msg.clone()); }
      @Override protected void processEcm(EcmRequest req, ReplyHeader h){
        seqs.add(h.sequence());
        // simulate async completion in reverse order: schedule reply for h, but second ECM's reply will be scheduled earlier
        // we just immediately send reply with correct header to verify capture
        sendCwReply(req, new CwReply(req.key(), req.key().commandTag(), new byte[]{0x01}, null, null, System.currentTimeMillis()), h);
      }
    }
    var cap = new Cap(vertx, cache, reg, new byte[14]);
    cap.setProfileNetworkId(0);
    setField(cap, "authenticated", true); setField(cap, "alive", true);
    setField(cap, "desKey16", new byte[16]);
    cap.setNoEncrypt(true);
    byte[] dec1 = new byte[13+2]; dec1[0]=0x00; dec1[1]=0x01; dec1[2]=0x00; dec1[3]=0x10; dec1[4]=0x00; dec1[5]=0x01; dec1[10]=(byte)0x80; dec1[11]=0; dec1[12]=2;
    dec1[13]=1; dec1[14]=2;
    byte[] dec2 = new byte[13+2]; dec2[0]=0x00; dec2[1]=0x02; dec2[2]=0x00; dec2[3]=0x20; dec2[4]=0x00; dec2[5]=0x01; dec2[10]=(byte)0x80; dec2[11]=0; dec2[12]=2;
    dec2[13]=3; dec2[14]=4;
    Method hm = NewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class); hm.setAccessible(true);
    hm.invoke(cap, (Object)dec1);
    hm.invoke(cap, (Object)dec2);
    // wait a bit for async
    CountDownLatch latch = new CountDownLatch(1);
    vertx.setTimer(50, id-> latch.countDown());
    latch.await(1, TimeUnit.SECONDS);
    assertEquals(2, cap.replies.size());
    // replies must preserve their own sequence, not global mutable
    int seq1 = ((cap.replies.get(0)[0]&0xFF)<<8)|(cap.replies.get(0)[1]&0xFF);
    int seq2 = ((cap.replies.get(1)[0]&0xFF)<<8)|(cap.replies.get(1)[1]&0xFF);
    assertEquals(0x0001, seq1);
    assertEquals(0x0002, seq2);
    assertTrue(cap.seqs.contains(0x0001));
    assertTrue(cap.seqs.contains(0x0002));
  }

  @Test
  void keepaliveAndVersionAndCardDataPreserveSeq() throws Exception {
    vertx = Vertx.vertx();
    var cache = new CacheVerticle();
    var reg = new SessionRegistry(vertx);
    class Cap extends NewcamdSession {
      List<byte[]> out = new ArrayList<>();
      Cap(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k){ super(v,c,r,k);}
      @Override protected void sendEncrypted(byte[] msg){ out.add(msg.clone()); }
    }
    var cap = new Cap(vertx, cache, reg, new byte[14]);
    setField(cap, "authenticated", true); setField(cap, "alive", true);
    setField(cap, "desKey16", new byte[16]);
    cap.setNoEncrypt(true);
    Method hm = NewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class); hm.setAccessible(true);
    // keepalive with seq 0x1234
    byte[] ka = new byte[13]; ka[0]=0x12; ka[1]=0x34; ka[10]=(byte)com.bowman.cspng.codec.newcamd.NewcamdConstants.MSG_KEEPALIVE; ka[11]=0; ka[12]=0;
    hm.invoke(cap, (Object)ka);
    assertEquals(1, cap.out.size());
    assertEquals(0x12, cap.out.get(0)[0] &0xFF); assertEquals(0x34, cap.out.get(0)[1]&0xFF);
    // For Ext, test version/card
    var ext = new ExtNewcamdSession(vertx, cache, reg, new byte[14], 2233);
    setField(ext, "authenticated", true); setField(ext, "alive", true); setField(ext, "desKey16", new byte[16]);
    ext.setNoEncrypt(true);
    Method hmExt = ExtNewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class); hmExt.setAccessible(true);
    class CapExt extends ExtNewcamdSession {
      List<byte[]> out2 = new ArrayList<>();
      CapExt(Vertx v, CacheVerticle c, SessionRegistry r, byte[] k, int port){ super(v,c,r,k,port); }
      @Override protected void sendEncrypted(byte[] msg){ out2.add(msg.clone()); }
    }
    var capExt = new CapExt(vertx, cache, reg, new byte[14], 2233);
    setField(capExt, "authenticated", true); setField(capExt, "alive", true); setField(capExt, "desKey16", new byte[16]);
    capExt.setNoEncrypt(true);
    byte[] verReq = new byte[13]; verReq[0]=0x55; verReq[1]=0x66; verReq[10]=(byte)0xD6; verReq[11]=0; verReq[12]=0;
    hmExt.invoke(capExt, (Object)verReq);
    assertEquals(1, capExt.out2.size());
    assertEquals(0x55, capExt.out2.get(0)[0]&0xFF); assertEquals(0x66, capExt.out2.get(0)[1]&0xFF);
  }
}
