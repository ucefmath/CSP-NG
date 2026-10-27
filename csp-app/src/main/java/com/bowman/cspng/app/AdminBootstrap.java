package com.bowman.cspng.app;

import com.bowman.cspng.users.UserRepository;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bootstrap de l'admin initial au démarrage si aucun admin actif n'existe.
 *
 * <p>Utilise {@link UserRepository#bootstrapAdminIfNeeded} avec pg_advisory_xact_lock
 * transaction-scoped sur la même connexion. Aucun check avant lock, hash offloadé
 * hors event-loop, timeout borné, guard Context.isOnEventLoopThread() fail-fast,
 * sans secret log.
 */
@ApplicationScoped
public class AdminBootstrap {

    private static final Logger LOG = Logger.getLogger(AdminBootstrap.class.getName());
    static final long LOCK_TIMEOUT_SECONDS = 10;

    private final Vertx vertx;
    private Pool pgPool;

    @Inject
    Instance<Pool> pgPoolInstance;

    @Inject
    Instance<UserRepository> userRepositoryInstance;

    @Inject
    @ConfigProperty(name = "csp.admin.bootstrap-required", defaultValue = "true")
    boolean bootstrapRequired;

    @Inject
    @ConfigProperty(name = "csp.admin.bcrypt-cost", defaultValue = "12")
    int bcryptCost;

    @Inject
    @ConfigProperty(name = "csp.config.pg.enabled", defaultValue = "true")
    boolean pgEnabled;

    @Inject
    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String quarkusProfile;

    static final String ENV_USER = "CSP_BOOTSTRAP_ADMIN_USER";
    static final String ENV_PASSWORD = "CSP_BOOTSTRAP_ADMIN_PASSWORD";

    @Inject
    public AdminBootstrap(Vertx vertx) {
        this.vertx = vertx;
    }

    public void onStart(@Observes StartupEvent event) {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("AdminBootstrap must not run on event-loop");
        }
        if (!bootstrapRequired) {
            LOG.info("Admin bootstrap disabled (csp.admin.bootstrap-required=false)");
            return;
        }
        if (!pgEnabled) {
            LOG.info("Admin bootstrap skipped (PG disabled)");
            return;
        }
        if (pgPoolInstance.isUnsatisfied() || pgPoolInstance.isAmbiguous()) {
            LOG.warning("Admin bootstrap skipped: no PG pool available");
            return;
        }
        pgPool = pgPoolInstance.get();
        if (pgPool == null) {
            LOG.warning("Admin bootstrap skipped: PG pool is null");
            return;
        }

        String bootstrapUser = System.getenv(ENV_USER);
        String bootstrapPassword = System.getenv(ENV_PASSWORD);

        checkAndBootstrap(bootstrapUser, bootstrapPassword);
    }

    void checkAndBootstrap(String bootstrapUser, String bootstrapPassword) {
        if (Context.isOnEventLoopThread()) {
            throw new IllegalStateException("AdminBootstrap.checkAndBootstrap must not run on event-loop");
        }
        UserRepository repo;
        if (!userRepositoryInstance.isUnsatisfied() && !userRepositoryInstance.isAmbiguous()) {
            repo = userRepositoryInstance.get();
        } else {
            repo = new UserRepository(pgPool, new com.bowman.cspng.users.crypto.PasswordHasher(bcryptCost), vertx);
        }

        try {
            var opt = repo.bootstrapAdminIfNeeded(bootstrapUser, bootstrapPassword, "bootstrap")
                .toCompletionStage().toCompletableFuture()
                .get(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (opt != null && opt.isPresent()) {
                LOG.info("Admin user '" + bootstrapUser + "' bootstrapped successfully (bcrypt, cost=" + bcryptCost + ")");
            } else {
                LOG.fine("Admin users already exist — bootstrap skipped");
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof com.bowman.cspng.users.BootstrapCredentialsMissingException) {
                if (!"test".equals(quarkusProfile)) {
                    throw new IllegalStateException(
                        "FAIL-FAST: No active admin users found and CSP_BOOTSTRAP_ADMIN_USER / "
                        + "CSP_BOOTSTRAP_ADMIN_PASSWORD environment variables are not set. "
                        + "Set these to bootstrap the initial admin, or set csp.admin.bootstrap-required=false "
                        + "to disable bootstrap.", e);
                }
                LOG.warning("Admin bootstrap skipped in test: no admin and no credentials");
                return;
            }
            LOG.log(Level.SEVERE, "Admin bootstrap failed", e);
            if (!"test".equals(quarkusProfile)) {
                throw new IllegalStateException("AdminBootstrap failed: " + e.getMessage(), e);
            }
        }
    }

    // Kept for backward compatibility if tests call directly
    void doBootstrap(String user, String password) {
        checkAndBootstrap(user, password);
    }
}
