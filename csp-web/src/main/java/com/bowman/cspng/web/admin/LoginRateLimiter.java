package com.bowman.cspng.web.admin;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter en mémoire par adresse IP pour les tentatives de login admin.
 * Plafond par défaut : 20 requêtes par fenêtre glissante de 60 secondes.
 * Purge automatique des entrées expirées pour éviter toute fuite mémoire.
 */
@ApplicationScoped
public class LoginRateLimiter {

    private static final int MAX_MAP_ENTRIES = 10_000;
    private final int maxAttemptsPerMinute;
    private final ConcurrentHashMap<String, Deque<Long>> ipAttempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(@ConfigProperty(name = "csp.web.login.rate-limit-per-min", defaultValue = "20") int maxAttemptsPerMinute) {
        this.maxAttemptsPerMinute = Math.max(1, maxAttemptsPerMinute);
    }

    public LoginRateLimiter() {
        this(20);
    }

    /**
     * Tente d'enregistrer une tentative.
     *
     * @param ip adresse IP du client
     * @return true si la requête est autorisée, false si le quota est dépassé (429)
     */
    public boolean tryAcquire(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip.trim();
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;

        if (ipAttempts.size() > MAX_MAP_ENTRIES / 2) {
            cleanupExpired(now, windowStart);
        }

        Deque<Long> deque = ipAttempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= maxAttemptsPerMinute) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    public void cleanupExpired(long now, long windowStart) {
        ipAttempts.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                    deque.pollFirst();
                }
                return deque.isEmpty();
            }
        });
    }

    /**
     * Réinitialise les compteurs (utile pour les tests).
     */
    public void reset() {
        ipAttempts.clear();
    }
}
