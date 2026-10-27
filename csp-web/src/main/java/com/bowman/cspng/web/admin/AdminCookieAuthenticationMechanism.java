package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Mécanisme d'authentification HTTP basé sur le cookie de session CSPADMINSESSION.
 * Priorité 1000 (Basic reste prioritaire à 2000 si en-tête Authorization présent).
 */
@ApplicationScoped
@Priority(1000)
public class AdminCookieAuthenticationMechanism implements HttpAuthenticationMechanism {

    public static final int PRIORITY = 1000;
    private static final String COOKIE_NAME = "CSPADMINSESSION";
    private static final String AUTH_HEADER = "Authorization";
    private static final String BASIC_PREFIX = "Basic ";

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(AdminCookieAuthenticationMechanism.class.getName());

    private final AdminSessionManager sessionManager;
    private final UserDirectory userDirectory;

    @Inject
    public AdminCookieAuthenticationMechanism(AdminSessionManager sessionManager, UserDirectory userDirectory) {
        this.sessionManager = sessionManager;
        this.userDirectory = userDirectory;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Si Authorization Basic explicite est présent, BasicAuthenticationMechanism est prioritaire
        String authHeader = context.request().getHeader(AUTH_HEADER);
        if (authHeader != null && authHeader.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return Uni.createFrom().nullItem();
        }

        String token = extractCookieValue(context);
        if (token == null || token.isBlank()) {
            return Uni.createFrom().nullItem();
        }

        Optional<AdminSession> optSession = sessionManager.getSession(token);
        if (optSession.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        AdminSession session = optSession.get();
        Future<Optional<UserRecord>> lookupFuture = userDirectory.lookupFresh(session.username());

        return Uni.createFrom().<Optional<UserRecord>>emitter(emitter -> {
            lookupFuture.onSuccess(emitter::complete).onFailure(emitter::fail);
        }).onFailure().recoverWithUni(err -> {
            // Erreur technique DB : fail-closed (401 transitoire) SANS révocation de la session
            LOG.warning("DB lookup failed during cookie auth check (session preserved for retry): " + err.getMessage());
            return Uni.createFrom().nullItem();
        }).chain(optUser -> {
            if (optUser == null) {
                // Erreur technique DB déjà traitée au-dessus
                return Uni.createFrom().nullItem();
            }
            if (optUser.isEmpty()) {
                sessionManager.revoke(token);
                return Uni.createFrom().nullItem();
            }

            UserRecord rec = optUser.get();
            // Invalidation si l'utilisateur est désactivé, hors validité, modifié (version différente) ou plus admin
            if (!rec.enabled() || !rec.isEnabledEffective(Instant.now())) {
                sessionManager.revoke(token);
                return Uni.createFrom().nullItem();
            }
            if (rec.version() != session.userVersion()) {
                sessionManager.revoke(token);
                return Uni.createFrom().nullItem();
            }
            if (!rec.admin()) {
                sessionManager.revoke(token);
                return Uni.createFrom().nullItem();
            }

            // Construction de la SecurityIdentity avec les rôles dynamiques du UserRecord
            QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
            builder.setPrincipal(new CspIdentityProvider.CspPrincipal(rec.name(), rec.displayName()));
            builder.addRole("user");
            if (rec.admin()) {
                builder.addRole("admin");
            }
            builder.addAttribute("adminSession", session);
            return Uni.createFrom().item((SecurityIdentity) builder.build());
        });
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        // Pas de challenge cookie spécifique : laisser le fallback Quarkus (401 Basic) s'appliquer
        return Uni.createFrom().nullItem();
    }

    @Override
    public Set<Class<? extends io.quarkus.security.identity.request.AuthenticationRequest>> getCredentialTypes() {
        return Collections.emptySet();
    }

    @Override
    public HttpCredentialTransport getCredentialTransport() {
        return new HttpCredentialTransport(HttpCredentialTransport.Type.COOKIE, COOKIE_NAME);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(getCredentialTransport());
    }

    private String extractCookieValue(RoutingContext context) {
        if (context == null || context.request() == null) return null;
        Cookie c = context.request().getCookie(COOKIE_NAME);
        if (c != null && c.getValue() != null && !c.getValue().isBlank()) {
            return c.getValue().trim();
        }
        String cookieHeader = context.request().getHeader("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE_NAME + "=")) {
                    return trimmed.substring(COOKIE_NAME.length() + 1).trim();
                }
            }
        }
        return null;
    }
}
