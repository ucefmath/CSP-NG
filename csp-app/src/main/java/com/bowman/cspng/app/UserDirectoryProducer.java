package com.bowman.cspng.app;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.PostgresUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.logging.Logger;

/**
 * Producer / lifecycle pour UserDirectory (L5b).
 * Quand PG actif, expose un PostgresUserDirectory unique et démarre son LISTEN.
 * Sinon, expose un InMemoryUserDirectory vide (tests/dev sans PG).
 */
@ApplicationScoped
public class UserDirectoryProducer {

  private static final Logger LOG = Logger.getLogger(UserDirectoryProducer.class.getName());

  private final Vertx vertx;
  private UserDirectory directory;

  @Inject Instance<Pool> pgPoolInstance;
  @Inject @ConfigProperty(name = "csp.config.pg.enabled", defaultValue = "true") boolean pgEnabled;
  @Inject @ConfigProperty(name = "quarkus.profile", defaultValue = "prod") String quarkusProfile;
  @Inject @ConfigProperty(name = "csp.users.cache.max-size", defaultValue = "1000") int cacheMaxSize;

  @Inject
  public UserDirectoryProducer(Vertx vertx) {
    this.vertx = vertx;
  }

  @Produces
  @Singleton
  public UserDirectory userDirectory() {
    if (directory != null) return directory;
    if (!pgEnabled) {
      LOG.info("UserDirectory: using InMemory (PG disabled, Vertx offload)");
      directory = new InMemoryUserDirectory(vertx);
      return directory;
    }
    if (pgPoolInstance.isUnsatisfied() || pgPoolInstance.isAmbiguous()) {
      throw new IllegalStateException("csp.config.pg.enabled=true but PG Pool unsatisfied/ambiguous — failing fast");
    }
    Pool pool = pgPoolInstance.get();
    if (pool == null) {
      throw new IllegalStateException("csp.config.pg.enabled=true but PG Pool is null — failing fast");
    }
    directory = new PostgresUserDirectory(pool, vertx, cacheMaxSize);
    return directory;
  }

  void onStart(@Observes StartupEvent event) {
    UserDirectory dir = userDirectory();
    if (dir instanceof PostgresUserDirectory pg) {
      if (!pgEnabled) {
        LOG.info("PostgresUserDirectory start skipped (pg disabled)");
        return;
      }
      // start LISTEN async — non bloquant, mais on log
      pg.start().onComplete(ar -> {
        if (ar.succeeded()) LOG.info("PostgresUserDirectory LISTEN started");
        else LOG.warning("PostgresUserDirectory LISTEN failed: " + ar.cause());
      });
    }
  }

  void onStop(@Observes ShutdownEvent event) {
    if (directory instanceof PostgresUserDirectory pg) {
      pg.stop().onComplete(ar -> LOG.info("PostgresUserDirectory stopped"));
    }
  }
}
