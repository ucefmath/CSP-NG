package com.bowman.cspng.app.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import com.bowman.cspng.web.admin.AdminSessionManager;
import com.bowman.cspng.web.admin.LoginRateLimiter;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminSessionCookieIntegrationTest.Profile.class)
@DisplayName("AdminSessionCookieIntegrationTest — Validation intégrale session cookie REST")
class AdminSessionCookieIntegrationTest {

    @Inject
    UserDirectory userDirectory;

    @Inject
    AdminSessionManager sessionManager;

    @Inject
    LoginRateLimiter rateLimiter;

    static final PasswordHasher hasher = new PasswordHasher(4);
    static volatile boolean initialized = false;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
        if (!initialized) {
            synchronized (AdminSessionCookieIntegrationTest.class) {
                if (!initialized) {
                    if (userDirectory instanceof InMemoryUserDirectory dir) {
                        dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
                            true, true, false, false, 5, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        dir.add(new UserRecord("simpleuser", "Simple User", hasher.hash("userPass123456"), null,
                            true, false, false, false, 5, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        dir.add(new UserRecord("disabledadmin", "Disabled Admin", hasher.hash("adminPass123456"), null,
                            false, true, false, false, 5, null, -1,
                            Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
                        initialized = true;
                    }
                }
            }
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
    @DisplayName("Login REST succès avec validation des flags Set-Cookie exacts")
    void loginSuccessAndCookieFlags() {
        Response resp = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(200)
            .body("user", equalTo("admin"))
            .body("expiresAt", notNullValue())
            .header("Cache-Control", containsString("no-store"))
            .header("X-Content-Type-Options", equalTo("nosniff"))
            .extract().response();

        String setCookie = resp.header("Set-Cookie");
        assertNotNull(setCookie, "Set-Cookie header missing");
        assertTrue(setCookie.contains("CSPADMINSESSION="), "Cookie name CSPADMINSESSION missing");
        assertTrue(setCookie.contains("HttpOnly"), "HttpOnly flag missing: " + setCookie);
        assertTrue(setCookie.contains("Secure"), "Secure flag missing: " + setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), "SameSite=Strict missing: " + setCookie);
        assertTrue(setCookie.contains("Path=/"), "Path=/ missing: " + setCookie);
        long maxLifetimeSec = sessionManager.getMaxLifetimeSeconds();
        assertTrue(setCookie.contains("Max-Age=" + maxLifetimeSec), "Max-Age must match max-lifetime (" + maxLifetimeSec + "s): " + setCookie);
    }

    @Test
    @DisplayName("Authentification au cookie sur /api/admin/me puis logout => 401")
    void cookieAuthAndLogoutFlow() {
        Response loginResp = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(200)
            .extract().response();

        String cookie = extractCookieValue(loginResp);
        assertNotNull(cookie);

        // 1. Accès /api/admin/me avec le cookie
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/me")
            .then()
            .statusCode(200)
            .body("name", equalTo("admin"))
            .body("roles", hasItem("admin"));

        // 2. Déconnexion via DELETE /api/admin/session
        Response logoutResp = given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .delete("/api/admin/session")
            .then()
            .statusCode(200)
            .extract().response();

        String clearCookie = logoutResp.header("Set-Cookie");
        assertNotNull(clearCookie);
        assertTrue(clearCookie.contains("Max-Age=0"), "Clear cookie must have Max-Age=0");

        // 3. Nouvelle tentative d'accès avec l'ancien cookie => 401
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/me")
            .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("Mauvais mot de passe / utilisateur inconnu / non-admin => 401")
    void badCredentialsFailures() {
        // Mauvais mot de passe
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"wrongPassword\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401)
            .body("code", equalTo("INVALID_CREDENTIALS"))
            .header("Set-Cookie", nullValue());

        // Utilisateur inconnu
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"nonexistent\",\"password\":\"anyPassword\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401)
            .body("code", equalTo("INVALID_CREDENTIALS"))
            .header("Set-Cookie", nullValue());

        // Utilisateur sans rôle admin
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"simpleuser\",\"password\":\"userPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401)
            .body("code", equalTo("INVALID_CREDENTIALS"))
            .header("Set-Cookie", nullValue());

        // Utilisateur admin désactivé
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"disabledadmin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401)
            .body("code", equalTo("INVALID_CREDENTIALS"))
            .header("Set-Cookie", nullValue());
    }

    @Test
    @DisplayName("Header CSRF X-CSP-Requested-With requis sur POST et DELETE /api/admin/session")
    void csrfHeaderEnforcement() {
        // POST sans CSRF => 403
        given()
            .contentType("application/json")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(403)
            .body("code", equalTo("CSRF_INVALID"));

        // DELETE sans CSRF => 403
        given()
            .delete("/api/admin/session")
            .then()
            .statusCode(403)
            .body("code", equalTo("CSRF_INVALID"));
    }

    @Test
    @DisplayName("Rate-limiting per-IP (20 requêtes par minute => 429)")
    void rateLimitingOnLogin() {
        for (int i = 0; i < 20; i++) {
            given()
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"admin\",\"password\":\"badPass\"}")
                .post("/api/admin/session")
                .then()
                .statusCode(401);
        }

        // 21ème tentative => 429 Too Many Requests
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"badPass\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(429)
            .body("code", equalTo("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("Basic Auth reste toujours fonctionnel et prioritaire")
    void basicAuthCoexistence() {
        // Basic valide
        given()
            .auth().preemptive().basic("admin", "adminPass123456")
            .get("/api/admin/me")
            .then()
            .statusCode(200)
            .body("name", equalTo("admin"));

        // Basic avec mot de passe erroné mais avec cookie valide => Basic est prioritaire et échoue (401)
        Response loginResp = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then().statusCode(200).extract().response();
        String cookie = extractCookieValue(loginResp);

        given()
            .auth().preemptive().basic("admin", "wrongPassword")
            .header("Cookie", "CSPADMINSESSION=" + cookie)
            .get("/api/admin/me")
            .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("Éviction max-per-user : la 6ème connexion évince la première")
    void maxPerUserEvictionIntegration() throws Exception {
        List<String> cookies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Response r = given()
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
                .post("/api/admin/session")
                .then().statusCode(200).extract().response();
            cookies.add(extractCookieValue(r));
            Thread.sleep(5);
        }

        // Vérifier que la première session fonctionne
        given().header("Cookie", "CSPADMINSESSION=" + cookies.get(0)).get("/api/admin/me").then().statusCode(200);

        // 6ème session
        Response r6 = given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then().statusCode(200).extract().response();
        String cookie6 = extractCookieValue(r6);

        // La 1ère session a été évincée => 401
        given().header("Cookie", "CSPADMINSESSION=" + cookies.get(0)).get("/api/admin/me").then().statusCode(401);

        // La 6ème session fonctionne => 200
        given().header("Cookie", "CSPADMINSESSION=" + cookie6).get("/api/admin/me").then().statusCode(200);
    }

    @Test
    @DisplayName("Invalidation automatique si le compte utilisateur est modifié/désactivé")
    void userDisabledInvalidatesSession() throws Exception {
        // Créer un admin temporaire
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            dir.add(new UserRecord("tempadmin", "Temp Admin", hasher.hash("tempPass123456"), null,
                true, true, false, false, 5, null, -1,
                Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));

            Response r = given()
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"tempadmin\",\"password\":\"tempPass123456\"}")
                .post("/api/admin/session")
                .then().statusCode(200).extract().response();
            String cookie = extractCookieValue(r);

            // Valide
            given().header("Cookie", "CSPADMINSESSION=" + cookie).get("/api/admin/me").then().statusCode(200);

            // Désactiver l'utilisateur (mise à jour du record avec enabled=false ou version incrémentée)
            dir.add(new UserRecord("tempadmin", "Temp Admin", hasher.hash("tempPass123456"), null,
                false, true, false, false, 5, null, -1,
                Set.of(), Map.of(), Map.of(), Set.of(), 2L, null, null));

            // La session existante est instantanément révoquée et refusée
            given().header("Cookie", "CSPADMINSESSION=" + cookie).get("/api/admin/me").then().statusCode(401);
        }
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
            m.put("csp.web.login.rate-limit-per-min", "20");
            return m;
        }
    }
}
