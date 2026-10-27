package com.bowman.cspng.app.runtime;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkerControlServerTest {

  @Test
  @DisplayName("Clamping de csp.relay.ack-max-body dans [4096..1048576]")
  void testAckMaxBodyClamping() {
    WorkerControlServer server = new WorkerControlServer();

    server.ackMaxBody = 500; // < 4096
    assertEquals(4096, server.effectiveAckMaxBody());

    server.ackMaxBody = 2_000_000; // > 1048576
    assertEquals(1048576, server.effectiveAckMaxBody());

    server.ackMaxBody = 65536; // dans la borne
    assertEquals(65536, server.effectiveAckMaxBody());

    server.ackMaxBody = 0; // défaut
    assertEquals(524288, server.effectiveAckMaxBody());
  }

  static X509Certificate fakeCert(String nodeId) {
    return new X509Certificate() {
      public Collection<List<?>> getSubjectAlternativeNames() {
        return List.of(List.of(6, "urn:csp:node:" + nodeId));
      }
      public void checkValidity() {}
      public void checkValidity(Date date) {}
      public int getVersion() { return 3; }
      public BigInteger getSerialNumber() { return BigInteger.ONE; }
      public Principal getIssuerDN() { return new X500Principal("CN=issuer"); }
      public Principal getSubjectDN() { return new X500Principal("CN=subject"); }
      public Date getNotBefore() { return new Date(); }
      public Date getNotAfter() { return new Date(System.currentTimeMillis() + 86400000); }
      public byte[] getTBSCertificate() { return new byte[0]; }
      public byte[] getSignature() { return new byte[0]; }
      public String getSigAlgName() { return "SHA256withRSA"; }
      public String getSigAlgOID() { return "1.2.3"; }
      public byte[] getSigAlgParams() { return null; }
      public boolean[] getIssuerUniqueID() { return null; }
      public boolean[] getSubjectUniqueID() { return null; }
      public boolean[] getKeyUsage() { return null; }
      public int getBasicConstraints() { return -1; }
      public byte[] getEncoded() { return new byte[0]; }
      public void verify(PublicKey key) {}
      public void verify(PublicKey key, String sigProvider) {}
      public String toString() { return "fakeCert"; }
      public PublicKey getPublicKey() { return null; }
      public boolean hasUnsupportedCriticalExtension() { return false; }
      public Set<String> getCriticalExtensionOIDs() { return null; }
      public Set<String> getNonCriticalExtensionOIDs() { return null; }
      public byte[] getExtensionValue(String oid) { return null; }
    };
  }

  @Test
  @DisplayName("Fermeture de connexion sur 413 : endHandler enregistré AVANT end() pour éviter IllegalStateException")
  void testCloseResponseOrdering() throws Exception {
    WorkerControlServer server = new WorkerControlServer();
    server.ackMaxBody = 10000;

    List<String> callOrder = new ArrayList<>();
    AtomicBoolean responseEnded = new AtomicBoolean(false);
    AtomicBoolean connectionClosed = new AtomicBoolean(false);
    Handler<Void>[] capturedEndHandler = new Handler[1];

    HttpConnection fakeConn = (HttpConnection) Proxy.newProxyInstance(
        HttpConnection.class.getClassLoader(),
        new Class<?>[]{HttpConnection.class},
        (proxy, method, args) -> {
          if ("close".equals(method.getName())) {
            callOrder.add("connection.close");
            connectionClosed.set(true);
            return null;
          }
          if ("peerCertificates".equals(method.getName())) {
            return List.<Certificate>of(fakeCert("w1"));
          }
          return null;
        }
    );

    HttpServerResponse fakeResp = (HttpServerResponse) Proxy.newProxyInstance(
        HttpServerResponse.class.getClassLoader(),
        new Class<?>[]{HttpServerResponse.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("putHeader".equals(name) && args != null && args.length >= 2) {
            callOrder.add("putHeader:" + args[0] + "=" + args[1]);
            return proxy;
          }
          if ("setStatusCode".equals(name) && args != null && args.length >= 1) {
            callOrder.add("setStatusCode:" + args[0]);
            return proxy;
          }
          if ("endHandler".equals(name)) {
            if (responseEnded.get()) {
              // Invariant strict Vert.x : endHandler après end() lève IllegalStateException
              throw new IllegalStateException("Response has already been written (RESPONSE_WRITTEN)");
            }
            callOrder.add("endHandler");
            capturedEndHandler[0] = (Handler<Void>) args[0];
            return proxy;
          }
          if ("end".equals(name)) {
            callOrder.add("end");
            responseEnded.set(true);
            // Simuler la notification de fin par Vert.x
            if (capturedEndHandler[0] != null) {
              capturedEndHandler[0].handle(null);
            }
            return null;
          }
          return null;
        }
    );

    HttpServerRequest fakeReq = (HttpServerRequest) Proxy.newProxyInstance(
        HttpServerRequest.class.getClassLoader(),
        new Class<?>[]{HttpServerRequest.class},
        (proxy, method, args) -> {
          String name = method.getName();
          if ("response".equals(name)) return fakeResp;
          if ("connection".equals(name)) return fakeConn;
          if ("getHeader".equals(name) && args != null && "Content-Length".equals(args[0])) {
            return "50000"; // > 10000 -> 413 immédiat
          }
          if ("exceptionHandler".equals(name)) {
            return proxy;
          }
          return null;
        }
    );

    // Invoquer handleAck via réflexion
    Method handleAckMethod = WorkerControlServer.class.getDeclaredMethod("handleAck", HttpServerRequest.class);
    handleAckMethod.setAccessible(true);
    handleAckMethod.invoke(server, fakeReq);

    // Vérifier l'ordre strict des opérations :
    // 1. putHeader Connection=close
    // 2. setStatusCode 413
    // 3. endHandler (enregistré AVANT end)
    // 4. end
    // 5. connection.close (déclenché par endHandler lors de la disposition)
    assertTrue(callOrder.contains("putHeader:Connection=close"));
    assertTrue(callOrder.contains("setStatusCode:413"));
    assertTrue(callOrder.contains("endHandler"));
    assertTrue(callOrder.contains("end"));

    int endHandlerIdx = callOrder.indexOf("endHandler");
    int endIdx = callOrder.indexOf("end");
    assertTrue(endHandlerIdx < endIdx, "endHandler DOIT être appelé avant end() : " + callOrder);
    assertTrue(connectionClosed.get(), "La connexion TCP doit être fermée");
  }
}
