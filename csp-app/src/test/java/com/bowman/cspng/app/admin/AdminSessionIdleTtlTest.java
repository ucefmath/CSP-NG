package com.bowman.cspng.app.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminSessionIdleTtlTest.Profile.class)
@DisplayName("AdminSessionIdleTtlTest — Expiration idle TTL court")
class AdminSessionIdleTtlTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4);

    @BeforeEach
    void setUp() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try {
                dir.add(new UserRecord("ttladmin", "TTL Admin", hasher.hash("adminPass123456"), null,
                    true, true, false, false, 5, null, -1,
                    Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
            } catch (Exception ignored) {}
        }
    }

    private String extractCookieValue(Response resp) {
        String setCookie = resp.header("Set-Cookie");
        if (setCookie == null) return null;
        for (String part : setCookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("CSPADMINSESSION=")) {
                return trimmed.substring("CSPADMINSESSION=".length()).trim();
            }
        }
        return null;
    }

    @Test
    @DisplayName("La session expire après le délai idle court (1 seconde)")
    void sessionExpiresAfterIdleTimeout() throws Exception {
        Response r = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"ttladmin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then().statusCode(200).extract().response();

        String cookie = extractCookieValue(r);
        assertNotNull(cookie);

        // Immédiatement : session valide => 200
        given().header("Cookie", "CSPADMINSESSION=" + cookie).get("/api/admin/me").then().statusCode(200);

        // Attente 1200ms (> 1s idle timeout)
        Thread.sleep(1200);

        // Après expiration : 401
        given().header("Cookie", "CSPADMINSESSION=" + cookie).get("/api/admin/me").then().statusCode(401);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = new HashMap<>();
            m.put("quarkus.http.auth.basic", "true");
            m.put("csp.config.pg.enabled", "false");
            m.put("csp.admin.bootstrap-required", "false");
            m.put("csp.admin.require-https", "false");
            m.put("quarkus.flyway.enabled", "false");
            m.put("quarkus.datasource.devservices.enabled", "false");
            m.put("csp.web.session.idle-timeout", "1s");
            m.put("csp.web.session.max-lifetime", "10s");
            m.put("csp.web.session.max-per-user", "5");
            m.put("csp.web.session.max-total", "1000");
            m.put("csp.web.login.rate-limit-per-min", "20");
            return m;
        }
    }
}
