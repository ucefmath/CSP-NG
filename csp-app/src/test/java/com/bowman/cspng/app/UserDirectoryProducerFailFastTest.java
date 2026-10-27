package com.bowman.cspng.app;

import static org.junit.jupiter.api.Assertions.*;

import com.bowman.cspng.users.InMemoryUserDirectory;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class UserDirectoryProducerFailFastTest {

  @SuppressWarnings("unchecked")
  private Instance<Pool> unsatisfiedInstance() {
    return (Instance<Pool>) java.lang.reflect.Proxy.newProxyInstance(
        Instance.class.getClassLoader(),
        new Class[]{Instance.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "isUnsatisfied": return true;
            case "isAmbiguous": return false;
            case "get": throw new IllegalStateException("unsatisfied");
            default: return null;
          }
        });
  }

  @SuppressWarnings("unchecked")
  private Instance<Pool> nullPoolInstance() {
    return (Instance<Pool>) java.lang.reflect.Proxy.newProxyInstance(
        Instance.class.getClassLoader(),
        new Class[]{Instance.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "isUnsatisfied": return false;
            case "isAmbiguous": return false;
            case "get": return null;
            default: return null;
          }
        });
  }

  private void inject(UserDirectoryProducer prod, Instance<Pool> inst, boolean pgEnabled) throws Exception {
    Field f = UserDirectoryProducer.class.getDeclaredField("pgPoolInstance");
    f.setAccessible(true);
    f.set(prod, inst);
    Field f2 = UserDirectoryProducer.class.getDeclaredField("pgEnabled");
    f2.setAccessible(true);
    f2.set(prod, pgEnabled);
  }

  @Test
  void pgEnabledTrueUnsatisfiedThrows() throws Exception {
    Vertx vertx = Vertx.vertx();
    UserDirectoryProducer prod = new UserDirectoryProducer(vertx);
    inject(prod, unsatisfiedInstance(), true);
    assertThrows(IllegalStateException.class, prod::userDirectory);
    vertx.close();
  }

  @Test
  void pgEnabledTrueNullPoolThrows() throws Exception {
    Vertx vertx = Vertx.vertx();
    UserDirectoryProducer prod = new UserDirectoryProducer(vertx);
    inject(prod, nullPoolInstance(), true);
    assertThrows(IllegalStateException.class, prod::userDirectory);
    vertx.close();
  }

  @Test
  void pgDisabledReturnsInMemory() throws Exception {
    Vertx vertx = Vertx.vertx();
    UserDirectoryProducer prod = new UserDirectoryProducer(vertx);
    inject(prod, unsatisfiedInstance(), false);
    var dir = prod.userDirectory();
    assertTrue(dir instanceof InMemoryUserDirectory);
    vertx.close();
  }

  @Test
  void canonicalNameUsedAfterAuth() {
    // This is also verified via SessionRegistry case test, but quick check via InMemory
    var dir = new com.bowman.cspng.users.InMemoryUserDirectory();
    dir.add(new com.bowman.cspng.users.UserRecord("Alice", "Alice", com.bowman.cspng.users.crypto.MD5Crypt.crypt("pass","salt1234"), true, false, java.util.Set.of("default"), 5));
    var rec = dir.lookupCached("alice");
    assertNotNull(rec);
    // lookup with different case returns same canonical
    assertEquals("Alice", dir.lookupCached("ALICE").name());
  }
}
