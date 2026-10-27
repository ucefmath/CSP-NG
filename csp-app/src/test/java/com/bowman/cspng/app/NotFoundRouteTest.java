package com.bowman.cspng.app;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(NotFoundRouteTest.Profile.class)
class NotFoundRouteTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4);
    static volatile boolean usersAdded = false;

    @BeforeEach
    void setUp() {
        if (!usersAdded) {
            synchronized (NotFoundRouteTest.class) {
                if (!usersAdded) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
                            true, true, false, false, 0, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        usersAdded = true;
                    }
                }
            }
        }
    }

    @Test
    void getFaviconReturns404JsonErrorResponseNot500() {
        String body = given()
            .get("/favicon.ico")
            .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("code", equalTo("NOT_FOUND"))
            .body("correlationId", notNullValue())
            .extract().body().asString();

        assertFalse(body.toLowerCase().contains("stacktrace"));
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.toLowerCase().contains("internal_error"));
    }

    @Test
    void getInconnuReturns404JsonErrorResponseNot500() {
        String body = given()
            .get("/inconnu")
            .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .body("code", equalTo("NOT_FOUND"))
            .body("correlationId", notNullValue())
            .extract().body().asString();

        assertFalse(body.toLowerCase().contains("stacktrace"));
        assertFalse(body.toLowerCase().contains("exception"));
        assertFalse(body.toLowerCase().contains("internal_error"));
    }

    @Test
    void adminApiMeStillProtectedUnauthenticatedReturns401() {
        given()
            .get("/api/admin/me")
            .then()
            .statusCode(401)
            .header("WWW-Authenticate", containsString("basic"));
    }

    @Test
    void adminApiMeAuthenticatedReturns200() {
        given()
            .auth().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(200)
            .contentType(containsString("application/json"))
            .body("name", equalTo("admin"))
            .body("roles", hasItem("admin"));
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
