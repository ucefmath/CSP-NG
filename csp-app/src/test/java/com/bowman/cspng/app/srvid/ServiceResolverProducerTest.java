package com.bowman.cspng.app.srvid;

import com.bowman.cspng.domain.srvid.ServiceResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class ServiceResolverProducerTest {

  @Test
  @DisplayName("Charge les ressources embarquées oscam.srvid et oscam.provid")
  void loadsEmbeddedResources() {
    ServiceResolverProducer producer = new ServiceResolverProducer();
    producer.srvidFile = java.util.Optional.empty();
    producer.providersFile = java.util.Optional.empty();

    ServiceResolver resolver = producer.produceServiceResolver();
    assertNotNull(resolver);
    assertTrue(resolver.serviceCount() > 1000, "Doit charger >1000 chaînes depuis oscam.srvid embarqué: " + resolver.serviceCount());
    assertTrue(resolver.providerCount() > 500, "Doit charger >500 providers depuis oscam.provid embarqué: " + resolver.providerCount());

    // Vérifie un exemple connu de oscam.srvid (0x0B00, 0x1131 -> Canal+ 360)
    String ch = resolver.resolveChannelName(0x0B00, 0x1131);
    assertEquals("Canal+ 360", ch);

    // Vérifie un exemple connu de oscam.provid (0x0100, 0x000068 -> Cyfra+ (13E))
    String prov = resolver.resolveProviderName(0x0100, 0x000068);
    assertEquals("Cyfra+ (13E)", prov);
  }

  @Test
  @DisplayName("Charge depuis des fichiers externes si spécifiés")
  void loadsExternalFiles() throws Exception {
    File tempSrv = File.createTempFile("test-srv", ".srvid");
    File tempProv = File.createTempFile("test-prov", ".provid");
    tempSrv.deleteOnExit();
    tempProv.deleteOnExit();

    Files.writeString(tempSrv.toPath(), "0500@032830:2222|CanalSat|TF1 HD|TV|General\n");
    Files.writeString(tempProv.toPath(), "0500:032830|CanalSat France (19.2E)|19.2E|FR\n");

    ServiceResolverProducer producer = new ServiceResolverProducer();
    producer.srvidFile = java.util.Optional.of(tempSrv.getAbsolutePath());
    producer.providersFile = java.util.Optional.of(tempProv.getAbsolutePath());

    ServiceResolver resolver = producer.produceServiceResolver();
    assertEquals(1, resolver.serviceCount());
    assertEquals(1, resolver.providerCount());
    assertEquals("TF1 HD", resolver.resolveChannelName(0x0500, 0x2222));
    assertEquals("CanalSat France (19.2E)", resolver.resolveProviderName(0x0500, 0x032830));
  }

  @Test
  @DisplayName("B4 : Fallback sur ressource embarquée si fichier inexistant ou non lisible")
  void fallbackWhenExternalFileMissing() {
    ServiceResolverProducer producer = new ServiceResolverProducer();
    producer.srvidFile = java.util.Optional.of("/non/existent/path/oscam.srvid");
    producer.providersFile = java.util.Optional.of("/non/existent/path/oscam.provid");

    ServiceResolver resolver = producer.produceServiceResolver();
    assertNotNull(resolver);
    // Doit avoir fait un fallback propre sur les ressources embarquées
    assertTrue(resolver.serviceCount() > 1000);
    assertTrue(resolver.providerCount() > 500);
  }
}
