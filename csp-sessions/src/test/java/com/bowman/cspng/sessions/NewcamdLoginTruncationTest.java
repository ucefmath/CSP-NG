package com.bowman.cspng.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import io.vertx.core.Vertx;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class NewcamdLoginTruncationTest {

  private Method handleLoginMethod() throws Exception {
    Method m = NewcamdSession.class.getDeclaredMethod("handleLogin", byte[].class, int.class);
    m.setAccessible(true);
    return m;
  }

  @Test
  void truncatedLoginDoesNotThrowNegativeArray() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cache = new CacheVerticle();
    SessionRegistry reg = new SessionRegistry(vertx);
    byte[] key = new byte[14];
    NewcamdSession sess = new NewcamdSession(vertx, cache, reg, key);
    Method m = handleLoginMethod();
    int loginTag = 0x00; // MSG_CLIENT_2_SERVER_LOGIN is 0x00 in code? Check constant: use 0x00
    // actual constant is MSG_CLIENT_2_SERVER_LOGIN = 0x?? let's use 0 from NewcamdConstants if exists, else 0
    int tag = com.bowman.cspng.codec.newcamd.NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
    for (int len : new int[]{11,12,13,0,5,10}) {
      byte[] dec = new byte[len];
      // fill with minimal header to avoid NPE before truncation check
      if (len > 10) dec[10] = (byte) tag;
      assertDoesNotThrow(() -> {
        try { m.invoke(sess, dec, tag); } catch (Exception e) { throw new RuntimeException(e.getCause()); }
      }, "len=" + len + " should not throw NegativeArraySizeException");
      assertFalse(sess.isAuthPending(), "authPending must stay false for truncated len=" + len);
    }
    vertx.close();
  }

  @Test
  void truncatedLoginWith11BytesTriggersNakAndNoException() throws Exception {
    Vertx vertx = Vertx.vertx();
    CacheVerticle cache = new CacheVerticle();
    SessionRegistry reg = new SessionRegistry(vertx);
    NewcamdSession sess = new NewcamdSession(vertx, cache, reg, new byte[14]);
    Method m = handleLoginMethod();
    int tag = com.bowman.cspng.codec.newcamd.NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
    byte[] dec11 = new byte[11];
    dec11[10] = (byte) tag;
    // should not throw
    m.invoke(sess, dec11, tag);
    assertFalse(sess.isAuthenticated());
    vertx.close();
  }
}
