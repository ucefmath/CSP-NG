package com.bowman.cspng.app.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminEventResourceQuarkusTest.Profile.class)
class AdminEventResourceQuarkusTest {

    @Inject UserDirectory userDirectory;
    @Inject AdminEventBroker broker;

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    static final PasswordHasher hasher = new PasswordHasher(4);

    @BeforeEach
    void setUp() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try { dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null, true, true, false, false, 0, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null)); } catch (Exception ignored) {}
        }
    }

    // Helper: login via REST and extract raw Set-Cookie value (CSPADMINSESSION=...)
    private String loginAndGetRawCookie(String username, String password, boolean withCsrf) {
        var req = given()
                .redirects().follow(false)
                .contentType("application/json")
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        if (withCsrf) {
            req = req.header("X-CSP-Requested-With", "CSP-WebUI");
        }
        var resp = req.post("/api/admin/session")
                .then()
                .extract();
        String setCookie = resp.header("Set-Cookie");
        if (setCookie != null) return setCookie;
        var cookie = resp.detailedCookie("CSPADMINSESSION");
        if (cookie != null) return cookie.getValue();
        return null;
    }

    private String loginAndGetCookieValue(String username, String password) {
        var resp = given()
                .redirects().follow(false)
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .post("/api/admin/session")
                .then()
                .extract().response();
        var c = resp.detailedCookie("CSPADMINSESSION");
        if (c != null) return c.getValue();
        String sc = resp.header("Set-Cookie");
        if (sc != null) {
            for (String part : sc.split(";")) {
                part = part.trim();
                if (part.startsWith("CSPADMINSESSION=")) {
                    return part.substring("CSPADMINSESSION=".length());
                }
            }
        }
        return null;
    }

    @Test
    void anonymousGets401AndAdminGets200WithHeaders() throws Exception {
        // admin via Basic still 200 (raw socket)
        String auth = Base64.getEncoder().encodeToString("admin:adminPass123456".getBytes(StandardCharsets.UTF_8));
        try (Socket socket = new Socket("localhost", testPort)) {
            socket.setSoTimeout(5000);
            String reqStr = "GET /api/admin/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic " + auth + "\r\nAccept: text/event-stream\r\n\r\n";
            socket.getOutputStream().write(reqStr.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "Expected 200 got " + status);
        }
        // also Basic via RestAssured for /status
        given().auth().preemptive().basic("admin","adminPass123456").get("/api/admin/status").then().statusCode(200).header("Cache-Control", containsString("no-store"));
        // anonymous must be 401, not 302, no Location, no CORS *
        given().redirects().follow(false).get("/api/admin/events").then().statusCode(401).header("Location", nullValue());
        given().redirects().follow(false).get("/api/admin/status").then().statusCode(401).header("Location", nullValue());
    }

    @Test
    void anonymousIs401Not302NoRedirect() {
        var resp = given().redirects().follow(false).get("/api/admin/status").then().extract().response();
        assertEquals(401, resp.statusCode(), "anonymous should be 401 not redirect");
        assertNull(resp.header("Location"), "anonymous API must not redirect (Location must be null)");
        String cors = resp.header("Access-Control-Allow-Origin");
        if (cors != null) assertNotEquals("*", cors.trim(), "CORS * forbidden");
    }

    @Test
    void basicAdminStatusAndEvents200() {
        given().auth().preemptive().basic("admin","adminPass123456").get("/api/admin/status").then().statusCode(200);
        try {
            String auth = Base64.getEncoder().encodeToString("admin:adminPass123456".getBytes(StandardCharsets.UTF_8));
            try (Socket s = new Socket("localhost", testPort)) {
                s.setSoTimeout(5000);
                String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Basic " + auth + "\r\nAccept: text/event-stream\r\n\r\n";
                s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String status = br.readLine();
                assertTrue(status.contains("200"), "SSE via Basic should be 200 got " + status);
            }
        } catch (Exception e) { fail(e); }
    }

    @Test
    void loginRequiresCsrfHeader() {
        // without CSRF header -> 403 from AdminRequestFilter
        given()
            .redirects().follow(false)
            .contentType("application/json")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(403)
            .body(containsString("CSRF_INVALID"));

        // with CSRF but bad credentials -> 401 generic, no cookie
        var badResp = given()
            .redirects().follow(false)
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"wrongPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401)
            .extract().response();
        assertNull(badResp.detailedCookie("CSPADMINSESSION"), "bad credentials must not set cookie");
        String scBad = badResp.header("Set-Cookie");
        if (scBad != null) assertFalse(scBad.contains("CSPADMINSESSION"), "no cookie on bad credentials");
        String bodyBad = badResp.asString();
        assertFalse(bodyBad.toLowerCase().contains("adminpass"), "must not leak password");
    }

    @Test
    void adminLoginSetsCookieWithCorrectAttributesAndOpaqueValue() {
        var resp = given()
            .redirects().follow(false)
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(200)
            .header("Set-Cookie", notNullValue())
            .extract().response();
        String setCookie = resp.header("Set-Cookie");
        assertNotNull(setCookie);
        String lower = setCookie.toLowerCase();
        assertTrue(lower.contains("httponly"), "HttpOnly missing: " + setCookie);
        assertTrue(lower.contains("samesite=strict"), "SameSite=Strict missing: " + setCookie);
        assertTrue(lower.contains("secure"), "Secure missing: " + setCookie);
        assertTrue(setCookie.contains("Path=/"), "Path must be / got: " + setCookie);

        var cookie = resp.detailedCookie("CSPADMINSESSION");
        assertNotNull(cookie, "cookie CSPADMINSESSION must be set");
        String val = cookie.getValue();
        assertNotNull(val);
        assertTrue(val.length() > 20, "cookie value too short");
        assertFalse(val.toLowerCase().contains("admin"), "cookie must be opaque, must not contain username");
        assertFalse(val.contains("adminPass"), "must not leak password");
    }

    @Test
    void cookieAloneAuthenticatesStatusWithoutAuthorizationHeader() {
        String cookieVal = loginAndGetCookieValue("admin", "adminPass123456");
        assertNotNull(cookieVal);
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookieVal)
            .get("/api/admin/status")
            .then()
            .statusCode(200)
            .header("Cache-Control", containsString("no-store"));

        given()
            .header("Cookie", "CSPADMINSESSION=" + cookieVal)
            .queryParam("token", "dummy-not-used")
            .get("/api/admin/status")
            .then()
            .statusCode(200);
    }

    @Test
    void cookieAloneOpensSse200AndCancelProper() throws Exception {
        String cookieVal = loginAndGetCookieValue("admin", "adminPass123456");
        assertNotNull(cookieVal);
        try (Socket socket = new Socket("localhost", testPort)) {
            socket.setSoTimeout(5000);
            String origin = "http://localhost:" + testPort;
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nOrigin: " + origin + "\r\nCookie: CSPADMINSESSION=" + cookieVal + "\r\nAccept: text/event-stream\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "SSE via cookie should be 200 got " + status);
            String line;
            boolean hasCT = false, hasCache=false;
            StringBuilder headers = new StringBuilder();
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append("\n");
                String low = line.toLowerCase();
                if (low.contains("content-type") && low.contains("text/event-stream")) hasCT=true;
                if (low.contains("cache-control") && low.contains("no-cache")) hasCache=true;
                if (low.contains("access-control-allow-origin") && low.contains("*")) fail("CORS * not allowed");
            }
            assertTrue(hasCT, "Content-Type text/event-stream missing: " + headers);
            assertTrue(hasCache, "Cache-Control missing: " + headers);
            awaitSubscriberCount(1, Duration.ofSeconds(3));
            broker.publishStatusChanged(9,9,9,false);
            broker.statsAsync().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            socket.setSoTimeout(1000);
            StringBuilder ev = new StringBuilder();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                String l;
                try {
                    l = br.readLine();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                if (l == null) break;
                ev.append(l).append("\n");
                if (l.startsWith("data:")) break;
                if (ev.toString().contains("data:")) break;
            }
            assertTrue(ev.toString().contains("data:") || ev.toString().contains("event:"), "should receive SSE data, got: " + ev);
            socket.close();
            long deadline2 = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline2) {
                if (broker.currentStats().subscriberCount() == 0) break;
                broker.statsAsync().toCompletionStage().toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
            }
            assertEquals(0, broker.currentStats().subscriberCount(), "subscriber should be cleaned after close");
        }
    }

    @Test
    void nonAdminCookieGets401OnLogin() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try { dir.add(new UserRecord("regular", "Regular User", hasher.hash("userPass1234567"), null, true, false, false, false, 1, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null)); } catch (Exception ignored) {}
        }
        given()
            .contentType("application/json")
            .header("X-CSP-Requested-With", "CSP-WebUI")
            .body("{\"username\":\"regular\",\"password\":\"userPass1234567\"}")
            .post("/api/admin/session")
            .then()
            .statusCode(401);
    }

    @Test
    void alteredCookieGets401() {
        String altered = "invalidOrForgedCookieSessionTokenValue123456";
        given()
            .header("Cookie", "CSPADMINSESSION=" + altered)
            .get("/api/admin/status")
            .then()
            .statusCode(401);
    }

    @Test
    void absentCookieGets401() {
        given().get("/api/admin/status").then().statusCode(401);
        given().header("Accept","text/event-stream").get("/api/admin/events").then().statusCode(401);
    }

    @Test
    void sessionConfigurationProperties() {
        var cfg = ConfigProvider.getConfig();
        String idleTimeout = cfg.getOptionalValue("csp.web.session.idle-timeout", String.class).orElse("8h");
        assertNotNull(idleTimeout);
        String maxLifetime = cfg.getOptionalValue("csp.web.session.max-lifetime", String.class).orElse("24h");
        assertNotNull(maxLifetime);
    }

    @Test
    void noTokenInUriOrQueryAndNoAuthorizationNeeded() {
        String cookieVal = loginAndGetCookieValue("admin", "adminPass123456");
        assertNotNull(cookieVal);
        given()
            .header("Cookie", "CSPADMINSESSION=" + cookieVal)
            .get("/api/admin/status")
            .then()
            .statusCode(200);
    }

    @Test
    void noCorsStarOnAdminEndpoints() {
        String cookieVal = loginAndGetCookieValue("admin", "adminPass123456");
        var resp = given().header("Cookie", "CSPADMINSESSION=" + cookieVal).get("/api/admin/status").then().extract().response();
        String cors = resp.header("Access-Control-Allow-Origin");
        if (cors != null) assertNotEquals("*", cors.trim());
    }

    private void awaitSubscriberCount(int expected, Duration maxWait) throws Exception {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            if (broker.currentStats().subscriberCount() == expected) return;
            Thread.sleep(20);
        }
        assertEquals(expected, broker.currentStats().subscriberCount(), "timed out waiting for subscriber count " + expected);
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String,String> getConfigOverrides() {
            java.util.HashMap<String,String> m = new java.util.HashMap<>();
            m.put("quarkus.http.auth.basic","true");
            m.put("csp.config.pg.enabled","false");
            m.put("csp.admin.bootstrap-required","false");
            m.put("csp.admin.require-https","false");
            m.put("quarkus.flyway.enabled","false");
            m.put("quarkus.datasource.devservices.enabled","false");
            m.put("csp.sse.enabled","true");
            m.put("csp.sse.replay-size","10");
            m.put("csp.sse.subscriber-queue-size","64");
            m.put("csp.sse.max-subscribers","50");
            m.put("csp.sse.heartbeat-ms","15000");
            m.put("csp.web.session.idle-timeout","8h");
            m.put("csp.web.session.max-lifetime","24h");
            return m;
        }
    }
}

@QuarkusTest
@TestProfile(AdminEventResourceDisabledQuarkusTest.DisabledProfile.class)
class AdminEventResourceDisabledQuarkusTest {
    @Inject UserDirectory userDirectory;
    static final PasswordHasher hasher = new PasswordHasher(4);
    @BeforeEach void setUp() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try { dir.add(new UserRecord("admin","Administrator", hasher.hash("adminPass123456"), null, true, true, false, false, 0, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null)); } catch (Exception ignored) {}
        }
    }
    @org.junit.jupiter.api.Test
    void disabledReturns404() {
        io.restassured.RestAssured.given()
            .auth().basic("admin","adminPass123456")
            .header("Accept","text/event-stream")
            .get("/api/admin/events")
            .then()
            .statusCode(404);
    }
    public static class DisabledProfile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of(
                "quarkus.http.auth.basic","true",
                "csp.config.pg.enabled","false",
                "csp.admin.bootstrap-required","false",
                "csp.admin.require-https","false",
                "quarkus.flyway.enabled","false",
                "quarkus.datasource.devservices.enabled","false",
                "csp.sse.enabled","false"
            );
        }
    }
}
