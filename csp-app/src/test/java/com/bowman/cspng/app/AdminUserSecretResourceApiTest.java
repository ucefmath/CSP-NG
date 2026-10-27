package com.bowman.cspng.app;

import com.bowman.cspng.users.*;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(AdminUserSecretResourceApiTest.Profile.class)
@DisplayName("AdminUserSecretResource — API REST Admin HTTP / CSRF / Auth / Error codes")
class AdminUserSecretResourceApiTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4);
    static volatile boolean usersAdded = false;

    @BeforeEach
    void setUp() {
        if (!usersAdded) {
            synchronized (AdminUserSecretResourceApiTest.class) {
                if (!usersAdded) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
                            true, true, false, false, 0, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        dir.add(new UserRecord("regular", "Regular User", hasher.hash("userPass1234567"), null,
                            true, false, false, false, 1, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        dir.add(new UserRecord("testuser", "Test User", hasher.hash("testPass1234567"), null,
                            true, false, false, false, 1, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        usersAdded = true;
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("GET secrets sans auth => 401 Unauthorized")
    void noAuthReturns401() {
        given()
            .get("/api/admin/users/testuser/secrets")
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", containsString("basic"));
    }

    @Test
    @DisplayName("GET secrets avec non-admin => 403 Forbidden")
    void nonAdminReturns403() {
        given()
            .auth().basic("regular", "userPass1234567")
            .get("/api/admin/users/testuser/secrets")
            .then()
            .statusCode(403);
    }

    @Test
    @DisplayName("PUT sans header CSRF => 403 CSRF_INVALID")
    void putWithoutCsrfReturns403() {
        given()
            .auth().basic("admin", "adminPass123456")
            .contentType("application/json")
            .body("{\"secretRef\":\"file:/run/secrets/pass\"}")
            .put("/api/admin/users/testuser/secrets/cccam?version=0")
            .then()
            .statusCode(403)
            .body("code", equalTo("CSRF_INVALID"));
    }

    @Test
    @DisplayName("PUT avec protocole invalide => 400 INVALID_PROTOCOL")
    void putInvalidProtocolReturns400() {
        given()
            .auth().basic("admin", "adminPass123456")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"secretRef\":\"file:/run/secrets/pass\"}")
            .put("/api/admin/users/testuser/secrets/invalid_proto?version=0")
            .then()
            .statusCode(400)
            .body("code", equalTo("INVALID_PROTOCOL"));
    }

    @Test
    @DisplayName("PUT avec secretRef invalide (syntaxe) => 400 Bad Request")
    void putInvalidSecretRefReturns400() {
        given()
            .auth().basic("admin", "adminPass123456")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"secretRef\":\"invalid_syntax_without_prefix\"}")
            .put("/api/admin/users/testuser/secrets/cccam?version=0")
            .then()
            .statusCode(400);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "quarkus.http.auth.basic", "true",
                "csp.config.pg.enabled", "false",
                "csp.admin.bootstrap-required", "false",
                "csp.admin.require-https", "false",
                "quarkus.flyway.enabled", "false",
                "quarkus.datasource.devservices.enabled", "false"
            );
        }
    }
}
