package com.bowman.cspng.web.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Représentation d'une session administrateur active en mémoire.
 * Le tokenHash stocké est l'empreinte SHA-256 du token (durcissement OWASP).
 */
public final class AdminSession {

    private final String token;
    private final String tokenHash;
    private final String username;
    private final Instant created;
    private final AtomicReference<Instant> lastSeenRef;
    private final AtomicReference<Instant> expiresIdleRef;
    private final Instant expiresAbsolute;
    private final long userVersion;

    public AdminSession(String token, String tokenHash, String username, Instant created, Instant expiresIdle, Instant expiresAbsolute, long userVersion) {
        this.token = Objects.requireNonNull(token, "token");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        this.username = Objects.requireNonNull(username, "username");
        this.created = Objects.requireNonNull(created, "created");
        this.lastSeenRef = new AtomicReference<>(created);
        this.expiresIdleRef = new AtomicReference<>(Objects.requireNonNull(expiresIdle, "expiresIdle"));
        this.expiresAbsolute = Objects.requireNonNull(expiresAbsolute, "expiresAbsolute");
        this.userVersion = userVersion;
    }

    public String token() {
        return token;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public String username() {
        return username;
    }

    public Instant created() {
        return created;
    }

    public Instant lastSeen() {
        return lastSeenRef.get();
    }

    public Instant expiresIdle() {
        return expiresIdleRef.get();
    }

    public Instant expiresAbsolute() {
        return expiresAbsolute;
    }

    public long userVersion() {
        return userVersion;
    }

    public void touch(Instant now, Instant newExpiresIdle) {
        this.lastSeenRef.set(now);
        // Ne jamais dépasser l'expiration absolue
        if (newExpiresIdle.isAfter(expiresAbsolute)) {
            this.expiresIdleRef.set(expiresAbsolute);
        } else {
            this.expiresIdleRef.set(newExpiresIdle);
        }
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAbsolute) || now.isAfter(expiresIdleRef.get());
    }
}
