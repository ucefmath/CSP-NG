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
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminApiTest.Profile.class)
class AdminApiTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4); // low cost for test speed
    static volatile boolean usersAdded = false;

    @BeforeEach
    void setUp() {
        if (!usersAdded) {
            synchronized (AdminApiTest.class) {
                if (!usersAdded) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
                            true, true, false, false, 0, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        dir.add(new UserRecord("user", "Regular User", hasher.hash("userPass1234567"), null,
                            true, false, false, false, 1, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        usersAdded = true;
                    }
                }
            }
        }
    }

    @Test
    void noAuthReturns401WithChallenge() {
        given()
            .get("/api/admin/me")
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", containsString("basic"));
    }

    @Test
    void wrongPasswordReturns401() {
        given()
            .auth().basic("admin", "wrongPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(401);
    }

    @Test
    void nonAdminUserReturns403() {
        given()
            .auth().basic("user", "userPass1234567")
            .get("/api/admin/me")
            .then()
            .statusCode(403);
    }

    @Test
    void adminCanAccessMe() {
        given()
            .auth().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("name", equalTo("admin"))
            .body("roles", hasItem("admin"))
            .header("Cache-Control", containsString("no-store"))
            .header("X-Content-Type-Options", containsString("nosniff"))
            .header("Content-Security-Policy", notNullValue());
    }

    @Test
    void responseNeverContainsPasswordHash() {
        String body = given()
            .auth().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(200)
            .extract().body().asString();

        assertFalse(body.contains("passwordHash"));
        assertFalse(body.contains("$2a$"));
        assertFalse(body.contains("$2b$"));
        assertFalse(body.contains("$1$"));
    }

    @Test
    void mutationWithoutCsrfHeaderReturns403() {
        given()
            .auth().basic("admin", "adminPass123456")
            .contentType("application/json")
            .body("{\"name\":\"newuser\",\"password\":\"newPass12345678\",\"enabled\":true,\"admin\":false,\"maxConnections\":1,\"allowedEcmRate\":-1}")
            .post("/api/admin/users")
            .then()
            .statusCode(403)
            .body("code", equalTo("CSRF_INVALID"))
            .header("Cache-Control", containsString("no-store"));
    }

    @Test
    void mutationWithCsrfHeaderPassesFilter() {
        // With correct CSRF header, request must NOT be rejected with CSRF_INVALID (status !=403 CSRF)
        int status = given()
            .auth().basic("admin", "adminPass123456")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"name\":\"csrfokuser\",\"password\":\"newPass12345678\",\"enabled\":true,\"admin\":false,\"maxConnections\":1,\"allowedEcmRate\":-1}")
            .post("/api/admin/users")
            .then()
            .extract().statusCode();
        assertNotEquals(403, status, "CSRF filter should pass when header present");
        // Should be 201 created or 400 validation, but not 403 CSRF. If created, clean up not needed.
        // If already exists from previous run, may be 500 conflict? But not CSRF.
        // Additional assertion: body code != CSRF_INVALID if 403
        if (status == 403) {
            String body = given()
                .auth().basic("admin", "adminPass123456")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .contentType("application/json")
                .body("{\"name\":\"csrfokuser2\",\"password\":\"newPass12345678\",\"enabled\":true,\"admin\":false,\"maxConnections\":1,\"allowedEcmRate\":-1}")
                .post("/api/admin/users")
                .then()
                .extract().body().asString();
            assertFalse(body.contains("CSRF_INVALID"), "Should not be CSRF_INVALID when header present");
        }
    }

    @Test
    void securityHeadersPresentOn401() {
        given()
            .get("/api/admin/me")
            .then()
            .statusCode(401)
            .header("Cache-Control", containsString("no-store"));
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
