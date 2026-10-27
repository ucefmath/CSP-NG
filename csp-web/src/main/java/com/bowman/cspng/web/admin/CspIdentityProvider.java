package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordVerifier;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Startup;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * IdentityProvider custom utilisant {@link UserDirectory} réactif.
 * Convertit le Future Vert.x de {@code UserDirectory.authenticate} en Uni Mutiny
 * sans blocage. Les rôles incluent toujours "user", plus "admin" si le flag est actif.
 *
 * <p>Message d'erreur générique "Invalid credentials" pour toutes les erreurs
 * d'authentification (utilisateur absent, désactivé, mauvais mot de passe)
 * afin d'éviter l'énumération.
 */
@ApplicationScoped
@Startup
public class CspIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger LOG = Logger.getLogger(CspIdentityProvider.class.getName());

    private final UserDirectory userDirectory;
    private final Vertx vertx;
    private final long minFailureMs;
    private final int bcryptCost;
    private volatile String dummyHash;

    // Fallback constant si init non appelé (tests)
    private static final String FALLBACK_DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Inject
    public CspIdentityProvider(UserDirectory userDirectory, Vertx vertx,
                               @ConfigProperty(name = "csp.admin.auth-min-failure-ms", defaultValue = "250") long minFailureMs,
                               @ConfigProperty(name = "csp.admin.bcrypt-cost", defaultValue = "12") int bcryptCost) {
        this.userDirectory = userDirectory;
        this.vertx = vertx;
        this.minFailureMs = minFailureMs;
        this.bcryptCost = Math.max(10, Math.min(14, bcryptCost));
    }

    // Constructor for tests without Vertx injection
    public CspIdentityProvider(UserDirectory userDirectory) {
        this(userDirectory, Vertx.vertx(), 250, 12);
    }

    public CspIdentityProvider(UserDirectory userDirectory, Vertx vertx, long minFailureMs) {
        this(userDirectory, vertx, minFailureMs, 12);
    }

    @jakarta.annotation.PostConstruct
    void initDummyHash() {
        if (io.vertx.core.Context.isOnEventLoopThread()) {
            throw new IllegalStateException("Dummy hash generation must not run on event-loop");
        }
        try {
            // Génération dummy au coût configuré, hors event-loop, hash non-secret
            this.dummyHash = io.quarkus.elytron.security.common.BcryptUtil.bcryptHash("dummy-not-secret", bcryptCost);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to generate dummy bcrypt hash, using fallback", e);
            this.dummyHash = FALLBACK_DUMMY_HASH;
        }
    }

    // Pour tests qui créent via constructeur sans CDI, initialisation lazy si besoin
    private String getDummyHash() {
        String h = dummyHash;
        if (h == null) {
            synchronized (this) {
                h = dummyHash;
                if (h == null) {
                    if (io.vertx.core.Context.isOnEventLoopThread()) {
                        // Ne pas bloquer event-loop, utiliser fallback
                        h = FALLBACK_DUMMY_HASH;
                    } else {
                        try {
                            h = io.quarkus.elytron.security.common.BcryptUtil.bcryptHash("dummy-not-secret", bcryptCost);
                        } catch (Exception e) {
                            h = FALLBACK_DUMMY_HASH;
                        }
                    }
                    dummyHash = h;
                }
            }
        }
        return h;
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
                                               AuthenticationRequestContext context) {
        String name = request.getUsername();
        String password = new String(request.getPassword().getPassword());
        long startNanos = System.nanoTime();

        Future<Optional<UserRecord>> lookupFuture = userDirectory.lookup(name);

        return Uni.createFrom().<Optional<UserRecord>>emitter(emitter -> {
            lookupFuture.onSuccess(emitter::complete)
                .onFailure(emitter::fail);
        }).onFailure().recoverWithUni(err -> {
            LOG.log(Level.WARNING, "User lookup failed during auth", err);
            return dummyBcryptUni()
                .chain(() -> delayIfNeeded(startNanos))
                .chain(() -> Uni.createFrom().failure(new AuthenticationFailedException("Invalid credentials")));
        }).chain(opt -> {
            if (opt.isEmpty()) {
                // absent -> dummy bcrypt + min delay
                return dummyBcryptUni()
                    .chain(() -> delayIfNeeded(startNanos))
                    .chain(() -> Uni.createFrom().failure(new AuthenticationFailedException("Invalid credentials")));
            }
            UserRecord rec = opt.get();
            if (!rec.enabled() || !rec.isEnabledEffective(Instant.now())) {
                // disabled / hors période -> dummy
                return dummyBcryptUni()
                    .chain(() -> delayIfNeeded(startNanos))
                    .chain(() -> Uni.createFrom().failure(new AuthenticationFailedException("Invalid credentials")));
            }

            Future<Boolean> authFuture = userDirectory.authenticate(name, password);
            return Uni.createFrom().<Boolean>emitter(emitter -> {
                authFuture.onSuccess(ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        emitter.complete(true);
                    } else {
                        emitter.fail(new AuthenticationFailedException("Invalid credentials"));
                    }
                }).onFailure(err -> {
                    LOG.log(Level.WARNING, "Authentication check failed", err);
                    emitter.fail(new AuthenticationFailedException("Invalid credentials"));
                });
            }).onFailure(AuthenticationFailedException.class).recoverWithUni(err -> {
                String hash = rec.passwordHash();
                boolean isBcrypt = hash != null && (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
                if (isBcrypt) {
                    // bcrypt wrong -> vrai bcrypt déjà effectué, seulement délai plancher
                    return delayIfNeeded(startNanos).chain(() -> Uni.createFrom().failure(err));
                } else {
                    // MD5 legacy rapide -> dummy bcrypt + délai
                    return dummyBcryptUni().chain(() -> delayIfNeeded(startNanos)).chain(() -> Uni.createFrom().failure(err));
                }
            }).map(ignored -> {
                QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
                builder.setPrincipal(new CspPrincipal(rec.name(), rec.displayName()));
                builder.addRole("user");
                if (rec.admin()) {
                    builder.addRole("admin");
                }
                return (SecurityIdentity) builder.build();
            });
        });
    }

    private Uni<Void> dummyBcryptUni() {
        if (vertx == null) {
            return Uni.createFrom().voidItem();
        }
        String hash = getDummyHash();
        return Uni.<Void>createFrom().completionStage(
            vertx.<Void>executeBlocking(() -> {
                // Dummy bcrypt non-secret, offload worker, au coût configuré
                try {
                    PasswordVerifier.verify("dummy-not-secret", hash);
                } catch (Exception ignored) {}
                return null;
            }, false).toCompletionStage()
        ).onFailure().recoverWithItem((Void) null);
    }

    private Uni<Void> delayIfNeeded(long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        long remaining = minFailureMs - elapsedMs;
        if (remaining <= 0) {
            return Uni.createFrom().voidItem();
        }
        if (vertx == null) {
            // Fallback: no vertx, just return (tests without vertx)
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().emitter(emitter -> {
            vertx.setTimer(remaining, id -> emitter.complete(null));
        });
    }

    /**
     * Principal personnalisé portant le nom canonique.
     */
    public record CspPrincipal(String name, String displayName) implements java.security.Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
