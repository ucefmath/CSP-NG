package com.bowman.cspng.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TlsProdConfigTest {

    @Inject
    Config config;

    @Inject
    @ConfigProperty(name = "csp.admin.require-https", defaultValue = "true")
    boolean requireHttps;

    @Test
    void testProfileDoesNotRequireHttpsAndCerts() {
        // En profil test, require-https doit être false (désactivable) et les certs prod ne sont pas requis
        assertFalse(requireHttps, "En test, csp.admin.require-https doit être false");
        // Les propriétés prod SSL ne doivent pas être obligatoires en test (pas d'exception au démarrage)
        // On vérifie qu'on peut démarrer sans CSP_TLS_CERT_FILE
        assertDoesNotThrow(() -> config.getOptionalValue("quarkus.http.ssl.certificate.files", String.class));
        // En test, la valeur est absente ou vide (pas de cert requis)
        var certFiles = config.getOptionalValue("quarkus.http.ssl.certificate.files", String.class);
        // En test, soit absent, soit vide, mais pas bloquant
        assertTrue(certFiles.isEmpty() || certFiles.get().isEmpty() || certFiles.get().contains("${"), "Cert files should be optional in test");
    }

    @Test
    void httpPortIsConfigured() {
        var port = config.getOptionalValue("quarkus.http.port", Integer.class);
        assertTrue(port.isPresent());
    }
}
