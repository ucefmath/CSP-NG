package com.bowman.cspng.web.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LoginRateLimiter & AdminAuthAudit — Tests unitaires")
class LoginRateLimiterTest {

    @Test
    @DisplayName("Rate limit : 20 requêtes autorisées par minute, la 21ème est refusée")
    void rateLimiterPerMinute() {
        LoginRateLimiter limiter = new LoginRateLimiter(20);
        String ip = "192.168.1.50";

        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire(ip), "Attempt " + (i + 1) + " should be allowed");
        }

        assertFalse(limiter.tryAcquire(ip), "21st attempt should be rejected (429)");

        // Autre IP indépendante
        assertTrue(limiter.tryAcquire("10.0.0.1"), "Different IP should be allowed");
    }

    @Test
    @DisplayName("AdminAuthAudit : sanitization des entrées")
    void auditSanitization() {
        assertEquals("?", AdminAuthAudit.sanitizeUser(null));
        assertEquals("?", AdminAuthAudit.sanitizeUser(""));
        assertEquals("admin??test", AdminAuthAudit.sanitizeUser("admin\r\ntest"));
        assertEquals("user?name", AdminAuthAudit.sanitizeUser("user=name"));

        assertEquals("?", AdminAuthAudit.sanitizeIp(null));
        assertEquals("127.0.0.1", AdminAuthAudit.sanitizeIp("127.0.0.1:8080"));
        assertEquals("::1", AdminAuthAudit.sanitizeIp("[::1]:443"));

        assertEquals("webadmin", AdminAuthAudit.sanitizeProtocol("webadmin"));
        assertEquals("bad-password", AdminAuthAudit.sanitizeReason("bad-password"));
    }
}
