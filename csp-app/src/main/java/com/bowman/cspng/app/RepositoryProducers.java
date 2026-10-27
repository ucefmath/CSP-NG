package com.bowman.cspng.app;

import com.bowman.cspng.config.ConfigRepository;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardDiscoveryService;
import com.bowman.cspng.users.PgProtocolAuthResolver;
import com.bowman.cspng.users.ProtocolAuthResolver;
import com.bowman.cspng.users.UserProtocolSecretRepository;
import com.bowman.cspng.users.UserRepository;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Producer CDI pour les repositories et services partagés de CSP-NG.
 */
@ApplicationScoped
public class RepositoryProducers {

    @Inject
    Instance<Pool> pgPoolInstance;

    @Inject
    Vertx vertx;

    @Inject
    @ConfigProperty(name = "csp.admin.bcrypt-cost", defaultValue = "12")
    int bcryptCost;

    @Produces
    @Singleton
    ConfigRepository configRepository() {
        Pool pool = getPool("ConfigRepository");
        return new ConfigRepository(pool);
    }

    @Produces
    @Singleton
    UserRepository userRepository() {
        Pool pool = getPool("UserRepository");
        return new UserRepository(pool, new PasswordHasher(bcryptCost), vertx);
    }

    @Produces
    @Singleton
    UserProtocolSecretRepository userProtocolSecretRepository() {
        Pool pool = getPool("UserProtocolSecretRepository");
        return new UserProtocolSecretRepository(pool);
    }

    @Produces
    @Singleton
    ProtocolAuthResolver protocolAuthResolver() {
        Pool pool = getPool("ProtocolAuthResolver");
        return new PgProtocolAuthResolver(pool);
    }

    @Produces
    @Singleton
    CardCatalogRepository cardCatalogRepository() {
        Pool pool = getPool("CardCatalogRepository");
        return new CardCatalogRepository(pool);
    }

    @Produces
    @Singleton
    CardDiscoveryService cardDiscoveryService(CardCatalogRepository catalogRepo) {
        return new CardDiscoveryService(vertx, catalogRepo);
    }

    private Pool getPool(String component) {
        if (pgPoolInstance.isUnsatisfied() || pgPoolInstance.isAmbiguous()) {
            throw new IllegalStateException(component + ": PG Pool unsatisfied/ambiguous — failing fast");
        }
        Pool pool = pgPoolInstance.get();
        if (pool == null) {
            throw new IllegalStateException(component + ": PG Pool is null — failing fast");
        }
        return pool;
    }
}
