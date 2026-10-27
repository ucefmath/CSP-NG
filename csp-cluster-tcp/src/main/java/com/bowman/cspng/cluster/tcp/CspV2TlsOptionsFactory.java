package com.bowman.cspng.cluster.tcp;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fabrique Vert.x NetServer/NetClient options depuis settings+material.
 * Aucun socket démarré, pas de fallback plaintext, pas de system trust.
 */
public final class CspV2TlsOptionsFactory {

  private CspV2TlsOptionsFactory() {}

  static final List<String> TLS13_CIPHERS = List.of(
      "TLS_AES_128_GCM_SHA256",
      "TLS_AES_256_GCM_SHA384",
      "TLS_CHACHA20_POLY1305_SHA256"
  );

  /** Suites AEAD compatibles JDK pour TLS1.2 opt-in */
  static final List<String> TLS12_AEAD_CIPHERS = List.of(
      "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
      "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
      "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
      "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
      "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
      "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"
  );

  static final List<String> TLS12_PLUS_13_CIPHERS;
  static {
    var all = new java.util.ArrayList<>(TLS13_CIPHERS);
    all.addAll(TLS12_AEAD_CIPHERS);
    TLS12_PLUS_13_CIPHERS = List.copyOf(all);
  }

  public static NetServerOptions serverOptions(CspV2Settings settings, CspV2TlsMaterial material) {
    if (settings == null) throw new IllegalArgumentException("settings must not be null");
    if (!settings.enabled()) throw new IllegalStateException("settings disabled");
    if (material == null) throw new IllegalArgumentException("material must not be null");
    if (material.isDestroyed()) throw new IllegalStateException("material destroyed");

    PemKeyCertOptions keyCert = new PemKeyCertOptions()
        .setCertValue(Buffer.buffer(material.certPem()))
        .setKeyValue(Buffer.buffer(material.keyPem()));
    PemTrustOptions trust = new PemTrustOptions()
        .addCertValue(Buffer.buffer(material.trustPem()));

    NetServerOptions opts = new NetServerOptions()
        .setSsl(true)
        .setClientAuth(ClientAuth.REQUIRED)
        .setPemKeyCertOptions(keyCert)
        .setPemTrustOptions(trust)
        .setHost(settings.listenHost())
        .setPort(settings.listenPort());

    if (settings.allowTls12()) {
      opts.setEnabledSecureTransportProtocols(new HashSet<>(List.of("TLSv1.3", "TLSv1.2")));
      for (String c : TLS12_PLUS_13_CIPHERS) opts.addEnabledCipherSuite(c);
    } else {
      opts.setEnabledSecureTransportProtocols(new HashSet<>(List.of("TLSv1.3")));
    }

    opts.setSslHandshakeTimeout(settings.tlsHandshakeMs());
    opts.setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);

    return opts;
  }

  public static NetClientOptions clientOptions(CspV2Settings settings, CspV2TlsMaterial material) {
    if (settings == null) throw new IllegalArgumentException("settings must not be null");
    if (!settings.enabled()) throw new IllegalStateException("settings disabled");
    if (material == null) throw new IllegalArgumentException("material must not be null");
    if (material.isDestroyed()) throw new IllegalStateException("material destroyed");

    PemKeyCertOptions keyCert = new PemKeyCertOptions()
        .setCertValue(Buffer.buffer(material.certPem()))
        .setKeyValue(Buffer.buffer(material.keyPem()));
    PemTrustOptions trust = new PemTrustOptions()
        .addCertValue(Buffer.buffer(material.trustPem()));

    // Pas de verification hostname : les certificats pairs ne portent que des
    // SAN URI urn:csp:node:<id> (jamais de SAN DNS). L'identite est etablie
    // par SAN URI + allowlist (CspV2Identity + findEnabledPeer), strictement
    // plus forte qu'un matching DNS ici. HTTPS l'aurait casse en production.
    NetClientOptions opts = new NetClientOptions()
        .setSsl(true)
        .setPemKeyCertOptions(keyCert)
        .setPemTrustOptions(trust)
        .setTrustAll(false)
        .setHostnameVerificationAlgorithm("")
        .setConnectTimeout((int) settings.connectMs())
        .setSslHandshakeTimeout(settings.tlsHandshakeMs())
        .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);

    if (settings.allowTls12()) {
      opts.setEnabledSecureTransportProtocols(new HashSet<>(List.of("TLSv1.3", "TLSv1.2")));
      for (String c : TLS12_PLUS_13_CIPHERS) opts.addEnabledCipherSuite(c);
    } else {
      opts.setEnabledSecureTransportProtocols(new HashSet<>(List.of("TLSv1.3")));
    }

    return opts;
  }
}
