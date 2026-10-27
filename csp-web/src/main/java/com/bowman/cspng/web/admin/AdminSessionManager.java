package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserRecord;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Gestionnaire en mémoire des sessions administrateur pour l'UI Web.
 *
 * <p>Spécifications :
 * <ul>
 *   <li>Token cryptographiquement sûr (CSPRNG 256 bits = 32 octets encodés base64url sans padding)</li>
 *   <li>Store ConcurrentHashMap &lt;tokenHash (SHA-256), AdminSession&gt; (durcissement OWASP)</li>
 *   <li>Idle timeout (défaut 8h) &amp; Absolute lifetime (défaut 24h)</li>
 *   <li>Plafond par utilisateur (max 5 sessions, éviction de la plus ancienne)</li>
 *   <li>Plafond global (max 1000 sessions)</li>
 *   <li>Nettoyage périodique 60s via Vert.x timer + lazy à l'accès</li>
 *   <li>Révocation ciblée, par utilisateur ou globale</li>
 * </ul>
 */
@ApplicationScoped
public class AdminSessionManager {

    private static final Logger LOG = Logger.getLogger(AdminSessionManager.class.getName());

    private final SecureRandom secureRandom = new SecureRandom();
    // Clé du store = SHA-256 du token (durcissement mémoire OWASP)
    private final ConcurrentHashMap<String, AdminSession> sessions = new ConcurrentHashMap<>();

    private final Vertx vertx;
    private final Duration idleTimeout;
    private final Duration maxLifetime;
    private final int maxPerUser;
    private final int maxTotal;

    private Long timerId = null;

    @Inject
    public AdminSessionManager(Vertx vertx,
                               @ConfigProperty(name = "csp.web.session.idle-timeout", defaultValue = "8h") String idleTimeoutStr,
                               @ConfigProperty(name = "csp.web.session.max-lifetime", defaultValue = "24h") String maxLifetimeStr,
                               @ConfigProperty(name = "csp.web.session.max-per-user", defaultValue = "5") int maxPerUser,
                               @ConfigProperty(name = "csp.web.session.max-total", defaultValue = "1000") int maxTotal) {
        this.vertx = vertx;
        this.idleTimeout = parseDuration(idleTimeoutStr, Duration.ofHours(8));
        this.maxLifetime = parseDuration(maxLifetimeStr, Duration.ofHours(24));
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxTotal = Math.max(1, maxTotal);
    }

    // Constructeur pour tests unitaires hors CDI
    public AdminSessionManager(Vertx vertx, Duration idleTimeout, Duration maxLifetime, int maxPerUser, int maxTotal) {
        this.vertx = vertx;
        this.idleTimeout = Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.maxLifetime = Objects.requireNonNull(maxLifetime, "maxLifetime");
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxTotal = Math.max(1, maxTotal);
    }

    void onStart(@Observes StartupEvent ev) {
        startCleanupTimer();
    }

    void onStop(@Observes ShutdownEvent ev) {
        stopCleanupTimer();
    }

    @PostConstruct
    void init() {
        startCleanupTimer();
    }

    @PreDestroy
    void cleanup() {
        stopCleanupTimer();
    }

    private synchronized void startCleanupTimer() {
        if (timerId == null && vertx != null) {
            timerId = vertx.setPeriodic(60_000, id -> cleanupExpiredSessions());
        }
    }

    private synchronized void stopCleanupTimer() {
        if (timerId != null && vertx != null) {
            vertx.cancelTimer(timerId);
            timerId = null;
        }
    }

    /**
     * Crée une nouvelle session pour l'utilisateur donné.
     */
    public AdminSession createSession(UserRecord user) {
        Objects.requireNonNull(user, "user");
        Instant now = Instant.now();

        // 1. Éviction si le total atteint le plafond
        if (sessions.size() >= maxTotal) {
            cleanupExpiredSessions();
            if (sessions.size() >= maxTotal) {
                // Éviction de la session la plus ancienne
                sessions.values().stream()
                    .min(Comparator.comparing(AdminSession::created))
                    .ifPresent(oldest -> sessions.remove(oldest.tokenHash()));
            }
        }

        // 2. Éviction max-per-user
        String uname = user.name().toLowerCase(Locale.ROOT);
        List<AdminSession> userSessions = sessions.values().stream()
            .filter(s -> s.username().toLowerCase(Locale.ROOT).equals(uname))
            .sorted(Comparator.comparing(AdminSession::created))
            .toList();

        if (userSessions.size() >= maxPerUser) {
            int toEvict = userSessions.size() - maxPerUser + 1;
            for (int i = 0; i < toEvict && i < userSessions.size(); i++) {
                sessions.remove(userSessions.get(i).tokenHash());
            }
        }

        // 3. Génération du token CSPRNG 32 bytes (256 bits) base64url sans padding
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hashToken(token);

        Instant expiresIdle = now.plus(idleTimeout);
        Instant expiresAbsolute = now.plus(maxLifetime);
        if (expiresIdle.isAfter(expiresAbsolute)) {
            expiresIdle = expiresAbsolute;
        }

        AdminSession session = new AdminSession(token, tokenHash, user.name(), now, expiresIdle, expiresAbsolute, user.version());
        sessions.put(tokenHash, session);
        return session;
    }

    /**
     * Valide et rafraîchit le timeout idle d'une session.
     */
    public Optional<AdminSession> getSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hashToken(token);
        AdminSession session = sessions.get(tokenHash);
        if (session == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        if (session.isExpired(now)) {
            sessions.remove(tokenHash);
            return Optional.empty();
        }

        Instant newExpiresIdle = now.plus(idleTimeout);
        session.touch(now, newExpiresIdle);
        return Optional.of(session);
    }

    /**
     * Révoque une session par son token.
     */
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            String tokenHash = hashToken(token);
            sessions.remove(tokenHash);
        }
    }

    /**
     * Révoque toutes les sessions d'un utilisateur.
     */
    public void revokeAllForUser(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String uname = username.toLowerCase(Locale.ROOT);
        sessions.values().stream()
            .filter(s -> s.username().toLowerCase(Locale.ROOT).equals(uname))
            .forEach(s -> sessions.remove(s.tokenHash()));
    }

    /**
     * Supprime toutes les sessions.
     */
    public void invalidateAll() {
        sessions.clear();
    }

    /**
     * Nettoyage des sessions expirées.
     */
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    public int activeSessionsCount() {
        return sessions.size();
    }

    public long getIdleTimeoutSeconds() {
        return idleTimeout.toSeconds();
    }

    public long getMaxLifetimeSeconds() {
        return maxLifetime.toSeconds();
    }

    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public static String hashToken(String token) {
        if (token == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.startsWith("pt") || s.startsWith("p")) {
                return Duration.parse(raw.trim());
            }
            if (s.endsWith("ms")) {
                long ms = Long.parseLong(s.substring(0, s.length() - 2).trim());
                return Duration.ofMillis(ms);
            }
            if (s.endsWith("h")) {
                long h = Long.parseLong(s.substring(0, s.length() - 1).trim());
                return Duration.ofHours(h);
            }
            if (s.endsWith("d")) {
                long d = Long.parseLong(s.substring(0, s.length() - 1).trim());
                return Duration.ofDays(d);
            }
            if (s.endsWith("min")) {
                long m = Long.parseLong(s.substring(0, s.length() - 3).trim());
                return Duration.ofMinutes(m);
            }
            if (s.endsWith("m")) {
                long m = Long.parseLong(s.substring(0, s.length() - 1).trim());
                return Duration.ofMinutes(m);
            }
            if (s.endsWith("s")) {
                long sec = Long.parseLong(s.substring(0, s.length() - 1).trim());
                return Duration.ofSeconds(sec);
            }
            long sec = Long.parseLong(s);
            return Duration.ofSeconds(sec);
        } catch (Exception e) {
            LOG.warning("Invalid duration '" + raw + "', using fallback " + fallback);
            return fallback;
        }
    }
}
