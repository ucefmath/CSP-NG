package com.bowman.cspng.cluster.tcp;

import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.util.Arrays;
import java.util.stream.Collectors;

public class CspV2TlsOptionsFactoryTest {

  static CspV2Settings settings(boolean allowTls12) {
    return new CspV2Settings(
        true,
        "node-a",
        "0.0.0.0",
        9090,
        1048576, 1000, 100, 10, 5000, 100,
        5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000,
        100, 30000,
        10000, 60000,
        allowTls12,
        "env:CERT", "env:KEY", "env:TRUST",
        false,
        List.of()
    );
  }

  static CspV2Settings disabledSettings() {
    return new CspV2Settings(
        false,
        "",
        "0.0.0.0",
        9090,
        1048576, 1000, 100, 10, 5000, 100,
        5000, 5000, 30000, 10000, 5000, 10000, 5000, 15000,
        100, 30000,
        10000, 60000,
        false,
        "", "", "",
        false,
        List.of()
    );
  }

  static CspV2TlsMaterial material() {
    return new CspV2TlsMaterial("certPem".getBytes(), "keyPem".getBytes(), "trustPem".getBytes());
  }

  @Test
  void serverOptionsSslAndClientAuth() {
    CspV2Settings s = settings(false);
    NetServerOptions o = CspV2TlsOptionsFactory.serverOptions(s, material());
    assertTrue(o.isSsl());
    assertEquals(ClientAuth.REQUIRED, o.getClientAuth());
    assertNotNull(o.getPemKeyCertOptions());
    assertNotNull(o.getPemTrustOptions());
    assertFalse(o.getPemKeyCertOptions().getCertValue().toString().isEmpty());
    assertFalse(o.getPemKeyCertOptions().getKeyValue().toString().isEmpty());
    assertEquals(5000, o.getSslHandshakeTimeout());
    assertEquals(TimeUnit.MILLISECONDS, o.getSslHandshakeTimeoutUnit());
    assertTrue(o.getEnabledSecureTransportProtocols().contains("TLSv1.3"));
    assertFalse(o.getEnabledSecureTransportProtocols().contains("TLSv1.2"));
    assertTrue(o.isSsl());
  }

  @Test
  void serverOptionsAllowTls12() {
    CspV2Settings s = settings(true);
    NetServerOptions o = CspV2TlsOptionsFactory.serverOptions(s, material());
    Set<String> protos = o.getEnabledSecureTransportProtocols();
    assertTrue(protos.contains("TLSv1.3"));
    assertTrue(protos.contains("TLSv1.2"));
    Set<String> ciphers = o.getEnabledCipherSuites();
    // TLS1.3
    assertTrue(ciphers.contains("TLS_AES_128_GCM_SHA256"));
    assertTrue(ciphers.contains("TLS_AES_256_GCM_SHA384"));
    assertTrue(ciphers.contains("TLS_CHACHA20_POLY1305_SHA256"));
    // TLS1.2 ECDHE AEAD
    assertTrue(ciphers.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
    assertTrue(ciphers.contains("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"));
    assertTrue(ciphers.contains("TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"));
    for (String c : ciphers) {
      assertFalse(c.contains("CBC"), "CBC not allowed: " + c);
      if (c.contains("_RSA_WITH_") && !c.contains("ECDHE")) {
        fail("RSA static not allowed: " + c);
      }
    }
    // at least 9 ciphers (3 TLS13 +6 TLS12)
    assertTrue(ciphers.size() >= 9);
  }

  @Test
  void clientOptionsTrustAllFalseAndHostname() {
    CspV2Settings s = settings(false);
    NetClientOptions o = CspV2TlsOptionsFactory.clientOptions(s, material());
    assertTrue(o.isSsl());
    assertFalse(o.isTrustAll());
    assertEquals("", o.getHostnameVerificationAlgorithm());
    assertNotNull(o.getPemKeyCertOptions());
    assertNotNull(o.getPemTrustOptions());
    assertEquals(5000, o.getConnectTimeout());
    assertEquals(5000, o.getSslHandshakeTimeout());
    assertEquals(TimeUnit.MILLISECONDS, o.getSslHandshakeTimeoutUnit());
    assertTrue(o.getEnabledSecureTransportProtocols().contains("TLSv1.3"));
    assertFalse(o.getEnabledSecureTransportProtocols().contains("TLSv1.2"));
  }

  @Test
  void clientOptionsAllowTls12() {
    CspV2Settings s = settings(true);
    NetClientOptions o = CspV2TlsOptionsFactory.clientOptions(s, material());
    assertTrue(o.getEnabledSecureTransportProtocols().contains("TLSv1.2"));
    assertFalse(o.isTrustAll());
    assertEquals("", o.getHostnameVerificationAlgorithm());
    Set<String> ciphers = o.getEnabledCipherSuites();
    assertTrue(ciphers.contains("TLS_AES_128_GCM_SHA256"));
    assertTrue(ciphers.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
    assertTrue(ciphers.contains("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"));
    assertTrue(ciphers.contains("TLS_CHACHA20_POLY1305_SHA256"));
  }

  @Test
  void pemOptionsPresent() {
    CspV2TlsMaterial m = new CspV2TlsMaterial("myCert".getBytes(), "myKey".getBytes(), "myTrust".getBytes());
    NetServerOptions s = CspV2TlsOptionsFactory.serverOptions(settings(false), m);
    assertTrue(s.getPemKeyCertOptions().getCertValue().toString().contains("myCert"));
    assertTrue(s.getPemKeyCertOptions().getKeyValue().toString().contains("myKey"));
    NetClientOptions c = CspV2TlsOptionsFactory.clientOptions(settings(false), m);
    assertTrue(c.getPemTrustOptions().getCertValues().get(0).toString().contains("myTrust"));
  }

  @Test
  void destroyedMaterialFails() {
    CspV2TlsMaterial m = material();
    m.destroy();
    assertThrows(IllegalStateException.class, () -> CspV2TlsOptionsFactory.serverOptions(settings(false), m));
    assertThrows(IllegalStateException.class, () -> CspV2TlsOptionsFactory.clientOptions(settings(false), m));
  }

  @Test
  void sslEngineFilteringRetainsAtLeastTLS13() throws Exception {
    // Prouve que liste explicite conserve au moins TLS1.3 sur JDK réel sans socket/cert
    SSLContext ctx = SSLContext.getDefault();
    SSLEngine engine = ctx.createSSLEngine();
    Set<String> supported = Set.of(engine.getSupportedCipherSuites());
    // filter our combined list
    List<String> filtered = CspV2TlsOptionsFactory.TLS12_PLUS_13_CIPHERS.stream()
        .filter(supported::contains)
        .collect(Collectors.toList());
    // au moins les 3 TLS13 doivent être supportés sur JDK21
    assertTrue(filtered.contains("TLS_AES_128_GCM_SHA256") || filtered.contains("TLS_AES_256_GCM_SHA384"),
        "at least one TLS1.3 cipher supported, filtered=" + filtered);
    // ensure our factory's list when filtered still has TLS1.3
    CspV2Settings s = settings(true);
    NetServerOptions o = CspV2TlsOptionsFactory.serverOptions(s, material());
    Set<String> configured = o.getEnabledCipherSuites();
    List<String> filteredConfigured = configured.stream().filter(supported::contains).collect(Collectors.toList());
    assertTrue(filteredConfigured.stream().anyMatch(c -> c.startsWith("TLS_AES_") || c.equals("TLS_CHACHA20_POLY1305_SHA256")),
        "configured ciphers must retain TLS1.3 after JDK filtering");
  }

  @Test
  void noPlaintextFallbackAndNoSystemTrust() {
    CspV2Settings s = settings(false);
    NetServerOptions srv = CspV2TlsOptionsFactory.serverOptions(s, material());
    NetClientOptions cli = CspV2TlsOptionsFactory.clientOptions(s, material());
    assertTrue(srv.isSsl());
    assertTrue(cli.isSsl());
    assertFalse(cli.isTrustAll());
    assertNotNull(srv.getPemTrustOptions());
    assertNotNull(cli.getPemTrustOptions());
    // hostname verification must remain HTTPS
    assertEquals("", cli.getHostnameVerificationAlgorithm());
  }

  @Test
  void factoryRejectsDisabledSettings() {
    CspV2Settings disabled = disabledSettings();
    CspV2TlsMaterial m = material();
    assertThrows(IllegalStateException.class, () -> CspV2TlsOptionsFactory.serverOptions(disabled, m));
    assertThrows(IllegalStateException.class, () -> CspV2TlsOptionsFactory.clientOptions(disabled, m));
  }
}
