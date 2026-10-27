package com.bowman.cspng.sessions;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.cache.CacheVerticle;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.MD5Crypt;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NewcamdSessionHardeningTest {

  private void setField(Object target, String name, Object value) throws Exception {
    Field f;
    try {
      f = target.getClass().getSuperclass().getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      f = target.getClass().getDeclaredField(name);
    }
    f.setAccessible(true);
    f.set(target, value);
  }

  private Object getField(Object target, String name) throws Exception {
    Field f;
    try {
      f = target.getClass().getSuperclass().getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      f = target.getClass().getDeclaredField(name);
    }
    f.setAccessible(true);
    return f.get(target);
  }

  @Test
  void authFutureAfterCloseDoesNotRegisterOrAuth() throws Exception {
    Vertx vertx = Vertx.vertx();
    Promise<Boolean> authPromise = Promise.promise();
    UserRecord rec = new UserRecord("Alice", "Alice", MD5Crypt.crypt("pass","s1234567"), true, false, Set.of("default"), 10);
    UserDirectory delayed = new UserDirectory() {
      public Future<Optional<UserRecord>> lookup(String n) { return Future.succeededFuture(Optional.of(rec)); }
      public UserRecord lookupCached(String n) { return rec; }
      public Future<Boolean> authenticate(String n, String p) { return authPromise.future(); }
      public Future<Void> reload() { return Future.succeededFuture(); }
    };
    SessionRegistry reg = new SessionRegistry(vertx, delayed);
    CacheVerticle cache = new CacheVerticle();
    NewcamdSession sess = new NewcamdSession(vertx, cache, reg, new byte[14]);
    // simulate accepted socket: set alive true and remoteAddress
    setField(sess, "alive", true);
    setField(sess, "remoteAddress", "127.0.0.1");
    // also need socket non-null for guard? we leave null and rely on alive false later
    // Create valid login decrypted (14+ bytes: header 13 + user\0pass\0)
    String user = "Alice";
    String pass = "pass";
    byte[] custom = (user + "\0" + pass + "\0").getBytes();
    byte[] dec = new byte[13 + custom.length];
    dec[10] = (byte) com.bowman.cspng.codec.newcamd.NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN;
    System.arraycopy(custom, 0, dec, 13, custom.length);
    Method m = NewcamdSession.class.getDeclaredMethod("handleLogin", byte[].class, int.class);
    m.setAccessible(true);
    m.invoke(sess, dec, (int)com.bowman.cspng.codec.newcamd.NewcamdConstants.MSG_CLIENT_2_SERVER_LOGIN);
    assertTrue(sess.isAuthPending());
    // simulate close before auth completes: set alive false (closeHandler would do)
    setField(sess, "alive", false);
    // complete auth after close
    authPromise.complete(true);
    // wait for vertx context to run completeLogin
    Thread.sleep(400);
    assertFalse(sess.isAuthenticated(), "should not become authenticated after close");
    assertFalse(sess.isAuthPending(), "authPending must be cleared");
    assertEquals(0, reg.countByUser("Alice"));
    assertEquals(0, reg.countByUser("alice"));
    assertEquals(0, reg.size(), "no session orphan");
    vertx.close();
  }

  @Test
  void authenticatedMessageTruncatedDoesNotThrowAIOOBE() throws Exception {
    Vertx vertx = Vertx.vertx();
    SessionRegistry reg = new SessionRegistry(vertx);
    CacheVerticle cache = new CacheVerticle();
    NewcamdSession sess = new NewcamdSession(vertx, cache, reg, new byte[14]);
    setField(sess, "alive", true);
    setField(sess, "authenticated", true);
    setField(sess, "remoteAddress", "127.0.0.1");
    // need a fake socket to avoid NPE in close? cleanup calls registry.unregister which is idempotent
    // we can leave socket null, close will still set alive false
    Method hm = NewcamdSession.class.getDeclaredMethod("handleMessage", byte[].class);
    hm.setAccessible(true);
    for (int len : new int[]{11,12}) {
      byte[] dec = new byte[len];
      // ensure at least header for commandTag if len>=11
      if (len >= 11) dec[10] = (byte) 0x80;
      assertDoesNotThrow(() -> {
        try { hm.invoke(sess, (Object) dec); } catch (Exception e) { throw new RuntimeException(e.getCause()); }
      }, "handleMessage len=" + len + " must not throw AIOOBE");
      // after first close, alive becomes false, next iteration will early return; reset for second
      setField(sess, "alive", true);
      setField(sess, "authenticated", true);
    }
    // also test 13 should not close (valid) – use unknown tag to avoid cacheVerticle NPE
    byte[] dec13 = new byte[13];
    dec13[10] = (byte) 0xFF; // unknown, goes to default, no ECM
    hm.invoke(sess, (Object) dec13);
    // 13 is valid for authenticated (needs >=13), should not close immediately
    assertTrue((Boolean)getField(sess, "alive"));
    vertx.close();
  }
}
