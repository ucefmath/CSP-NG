package com.bowman.cspng.app;

import com.bowman.cspng.users.*;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestProfile(AdminApiHttpsEnforcementTest.HttpsProfile.class)
class AdminApiHttpsEnforcementTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4);
    static volatile boolean usersAdded = false;

    @BeforeEach
    void setUp() {
        if (!usersAdded) {
            synchronized (AdminApiHttpsEnforcementTest.class) {
                if (!usersAdded) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Admin", hasher.hash("adminPass123456"),
                            null, true, true, false, false, 0, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        usersAdded = true;
                    }
                }
            }
        }
    }

    @Test
    void httpsRequiredReturns426WhenHttp() {
        given()
            .auth().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(426)
            .body("code", equalTo("HTTPS_REQUIRED"))
            .header("Cache-Control", containsString("no-store"));
    }

    @Test
    void httpsRequiredResponseHasSecurityHeaders() {
        given()
            .auth().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(426)
            .header("X-Content-Type-Options", containsString("nosniff"));
    }

    public static class HttpsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.http.auth.basic", "true",
                "csp.config.pg.enabled", "false",
                "csp.admin.bootstrap-required", "false",
                "csp.admin.require-https", "true",
                "quarkus.flyway.enabled", "false",
                "quarkus.datasource.devservices.enabled", "false"
            );
        }
    }
}
