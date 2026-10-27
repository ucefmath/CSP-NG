package com.bowman.cspng.app.srvid;

import com.bowman.cspng.domain.srvid.DefaultServiceResolver;
import com.bowman.cspng.domain.srvid.OscamProvidParser;
import com.bowman.cspng.domain.srvid.OscamSrvidParser;
import com.bowman.cspng.domain.srvid.ProviderInfo;
import com.bowman.cspng.domain.srvid.ProviderKey;
import com.bowman.cspng.domain.srvid.ServiceInfo;
import com.bowman.cspng.domain.srvid.ServiceKey;
import com.bowman.cspng.domain.srvid.ServiceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Producer CDI pour le {@link ServiceResolver}.
 *
 * <p><b>Garantie de thread :</b> Cette méthode {@code @Produces} s'exécute de façon synchrone
 * sur le thread principal de démarrage Quarkus lors de l'initialisation des singletons CDI,
 * strictement AVANT l'ouverture des listeners HTTP/réseau. Aucun risque de blocage d'event-loop.
 */
@ApplicationScoped
public class ServiceResolverProducer {

  private static final Logger LOG = Logger.getLogger(ServiceResolverProducer.class.getName());
  private static final long MAX_FILE_SIZE_BYTES = 8 * 1024 * 1024L; // 8 Mo max

  @Inject
  @ConfigProperty(name = "csp.srvid.file")
  Optional<String> srvidFile;

  @Inject
  @ConfigProperty(name = "csp.providers.file")
  Optional<String> providersFile;

  private ServiceResolver cachedResolver;

  @Produces
  @ApplicationScoped
  public synchronized ServiceResolver produceServiceResolver() {
    if (cachedResolver != null) {
      return cachedResolver;
    }

    String srvidPath = srvidFile != null ? srvidFile.orElse("") : "";
    String providPath = providersFile != null ? providersFile.orElse("") : "";

    Map<ServiceKey, ServiceInfo> services = Map.of();
    String srvidSource = "none";
    try {
      boolean loadedExternal = false;
      if (!srvidPath.isBlank()) {
        File f = new File(srvidPath.trim());
        if (!f.exists() || !f.isFile()) {
          LOG.warning("External srvid file not found: " + srvidPath + " — falling back to embedded resource");
        } else if (f.length() > MAX_FILE_SIZE_BYTES) {
          LOG.warning("External srvid file exceeds 8 MB (" + f.length() + " bytes) — falling back to embedded resource");
        } else {
          try (InputStream is = new FileInputStream(f)) {
            services = OscamSrvidParser.parse(is);
            srvidSource = "external file: " + f.getAbsolutePath();
            loadedExternal = true;
          }
        }
      }
      if (!loadedExternal) {
        try (InputStream is = getClass().getResourceAsStream("/srvid/oscam.srvid")) {
          if (is != null) {
            services = OscamSrvidParser.parse(is);
            srvidSource = "embedded resource: /srvid/oscam.srvid";
          }
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to load oscam.srvid: " + e.getMessage(), e);
    }

    Map<ProviderKey, ProviderInfo> providers = Map.of();
    String providSource = "none";
    try {
      boolean loadedExternal = false;
      if (!providPath.isBlank()) {
        File f = new File(providPath.trim());
        if (!f.exists() || !f.isFile()) {
          LOG.warning("External provid file not found: " + providPath + " — falling back to embedded resource");
        } else if (f.length() > MAX_FILE_SIZE_BYTES) {
          LOG.warning("External provid file exceeds 8 MB (" + f.length() + " bytes) — falling back to embedded resource");
        } else {
          try (InputStream is = new FileInputStream(f)) {
            providers = OscamProvidParser.parse(is);
            providSource = "external file: " + f.getAbsolutePath();
            loadedExternal = true;
          }
        }
      }
      if (!loadedExternal) {
        try (InputStream is = getClass().getResourceAsStream("/srvid/oscam.provid")) {
          if (is != null) {
            providers = OscamProvidParser.parse(is);
            providSource = "embedded resource: /srvid/oscam.provid";
          }
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to load oscam.provid: " + e.getMessage(), e);
    }

    cachedResolver = new DefaultServiceResolver(services, providers);
    LOG.info(String.format("Loaded %d services (%s) and %d providers (%s)",
        cachedResolver.serviceCount(), srvidSource,
        cachedResolver.providerCount(), providSource));

    return cachedResolver;
  }
}
