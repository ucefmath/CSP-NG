package com.bowman.cspng.app.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contrats HTTP réels des nouveaux endpoints v2 (sans PG : parties déterministes).
 * Auth session cookie + CSRF vérifiés pour chaque verbe ; les endpoints dépendant
 * de la config PG retournent l'enveloppe 503 NOT_READY documentée.
 */
@QuarkusTest
@TestProfile(AdminCacheexContractsHttpTest.Profile.class)
@DisplayName("AdminCacheexContractsHttpTest — contrats HTTP réels v2")
class AdminCacheexContractsHttpTest {

    @Inject
    UserDirectory userDirectory;

    static final PasswordHasher hasher = new PasswordHasher(4);
    static volatile boolean initialized = false;

    @BeforeEach
    void setUp() {
        if (!initialized) {
            synchronized (AdminCacheexContractsHttpTest.class) {
                if (!initialized) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
                            true, true, false, false, 5, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        initialized = true;
                    }
                }
            }
        }
    }

    private String loginCookie() {
        var resp = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(200)
            .extract().response();
        String setCookie = resp.header("Set-Cookie");
        assertNotNull(setCookie);
        for (String part : setCookie.split(";")) {
            String t = part.trim();
            if (t.startsWith("CSPADMINSESSION=")) return t.substring("CSPADMINSESSION=".length()).trim();
        }
        throw new AssertionError("no session cookie");
    }

    @Test
    @DisplayName("Sans cookie => 401 sur les nouveaux endpoints")
    void noCookieIs401() {
        given().get("/api/admin/cacheex/stats").then().statusCode(401);
        given().get("/api/admin/cache/status").then().statusCode(401);
        given().get("/api/admin/config/cw-cycle").then().statusCode(401);
    }

    @Test
    @DisplayName("POST sans CSRF => 403 même authentifié")
    void postWithoutCsrfIs403() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .contentType("application/json")
            .body("{}")
            .post("/api/admin/cacheex/stats/reset")
            .then()
            .statusCode(403);
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .contentType("application/json")
            .body("{\"purpose\":\"connector-password\",\"owner\":\"x\",\"value\":\"y\"}")
            .post("/api/admin/secrets/materialize")
            .then()
            .statusCode(403);
    }

    @Test
    @DisplayName("GET /api/admin/cacheex/stats => 200 enveloppe {generatedAtMs, pairs[]}")
    void cacheexStatsShape() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/cacheex/stats")
            .then()
            .statusCode(200)
            .body("generatedAtMs", notNullValue())
            .body("pairs", notNullValue());
    }

    @Test
    @DisplayName("POST /api/admin/cacheex/stats/reset => 204 avec CSRF")
    void cacheexResetIs204() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .post("/api/admin/cacheex/stats/reset")
            .then()
            .statusCode(204);
    }

    @Test
    @DisplayName("GET /api/admin/cache/status => 200 avec shape ou 503 NOT_READY documenté")
    void cacheStatusShapeOrNotReady() {
        String cookie = loginCookie();
        var r = given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/cache/status")
            .then()
            .extract().response();
        assertTrue(r.statusCode() == 200 || r.statusCode() == 503, "200 ou 503, pas autre chose");
        if (r.statusCode() == 200) {
            r.then()
                .body("generatedAtMs", notNullValue())
                .body("ready", equalTo(true))
                .body("caches", notNullValue())
                .body("nodes", notNullValue())
                .body("udp", notNullValue());
        } else {
            r.then().body("code", equalTo("NOT_READY"));
        }
    }

    @Test
    @DisplayName("GET /api/admin/config/cw-cycle sans PG => 503 NOT_READY enveloppé")
    void cwCycleWithoutPgIsNotReady() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/config/cw-cycle")
            .then()
            .statusCode(503)
            .body("code", equalTo("NOT_READY"));
    }

    @Test
    @DisplayName("PUT bulk route inconnue => 404 JAX-RS (pas de shadow par /{name})")
    void bulkUnknownRouteIs404() {
        String cookie = loginCookie();
        var r = given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"items\":[{\"id\":\"x\",\"version\":1}],\"patch\":{\"enabled\":true}}")
            .put("/api/admin/config/nope/bulk")
            .then()
            .statusCode(404)
            .extract().response();
        assertEquals(404, r.statusCode());
    }

    @Test
    @DisplayName("PUT /connectors/bulk patch vide => 400 INVALID_INPUT (avant DB)")
    void bulkConnectorsEmptyPatchIs400() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"items\":[{\"id\":\"x\",\"version\":1}],\"patch\":{}}")
            .put("/api/admin/config/connectors/bulk")
            .then()
            .statusCode(400)
            .body("code", equalTo("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PUT /connectors/bulk clé interdite => 400 INVALID_PATCH_FIELD (avant DB)")
    void bulkConnectorsForbiddenKeyIs400() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"items\":[{\"id\":\"x\",\"version\":1}],\"patch\":{\"passwordRef\":\"file:/x\"}}")
            .put("/api/admin/config/connectors/bulk")
            .then()
            .statusCode(400)
            .body("code", equalTo("INVALID_PATCH_FIELD"));
    }

    @Test
    @DisplayName("Materialize purpose inconnu => 400 sans toucher au FS")
    void materializeUnknownPurposeIs400() {
        String cookie = loginCookie();
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .contentType("application/json")
            .body("{\"purpose\":\"nope\",\"owner\":\"x\",\"value\":\"y\"}")
            .post("/api/admin/secrets/materialize")
            .then()
            .statusCode(400)
            .body("code", equalTo("INVALID_INPUT"));
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
            m.put("csp.web.session.idle-timeout", "8h");
            m.put("csp.web.session.max-lifetime", "24h");
            m.put("csp.web.session.max-per-user", "5");
            m.put("csp.web.session.max-total", "1000");
            m.put("csp.web.login.rate-limit-per-min", "100");
            return m;
        }
    }
}
