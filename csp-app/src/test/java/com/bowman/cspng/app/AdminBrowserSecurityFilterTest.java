package com.bowman.cspng.app;

import com.bowman.cspng.app.admin.AdminEventBroker;
import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminBrowserSecurityFilterTest.Profile.class)
class AdminBrowserSecurityFilterTest {

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

    private String loginCookie(String user, String pass) {
        var resp = given()
                .redirects().follow(false)
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}")
                .post("/api/admin/session")
                .then()
                .extract().response();
        var c = resp.detailedCookie("CSPADMINSESSION");
        if (c != null) return c.getValue();
        String sc = resp.header("Set-Cookie");
        if (sc != null) {
            for (String part : sc.split(";")) {
                part = part.trim();
                if (part.startsWith("CSPADMINSESSION=")) return part.substring("CSPADMINSESSION=".length());
            }
        }
        return null;
    }

    @Test
    void loginWithoutCsrfHeaderIs403AndNoCookie() {
        var resp = given()
                .redirects().follow(false)
                .contentType("application/json")
                .body("{\"username\":\"admin\",\"password\":\"adminPass123456\"}")
                .post("/api/admin/session")
                .then()
                .statusCode(403)
                .header("Cache-Control", containsString("no-store"))
                .header("X-Content-Type-Options", containsString("nosniff"))
                .extract().response();
        assertNull(resp.detailedCookie("CSPADMINSESSION"), "login without CSRF must not set cookie");
        String sc = resp.header("Set-Cookie");
        assertTrue(sc == null || !sc.contains("CSPADMINSESSION="), "Set-Cookie must not contain session");
    }

    @Test
    void loginPathVariantsWithoutCsrfAreNotAuthenticated() throws Exception {
        String[] variants = {
                "//api/admin/session",
                "/api/admin/./session",
                "/api/admin/%73ession"
        };
        for (String path : variants) {
            try (Socket s = new Socket("localhost", testPort)) {
                s.setSoTimeout(3000);
                String body = "{\"username\":\"admin\",\"password\":\"adminPass123456\"}";
                String raw = "POST " + path + " HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String status = br.readLine();
                assertNotNull(status, "status must not be null for " + path);
                int code = extractStatus(status);
                assertTrue(code == 403 || code == 404, "variant " + path + " must be 403 or 404, got " + code);
                assertFalse(readsSetCookieSession(br), "variant " + path + " must not set CSPADMINSESSION cookie");
            }
        }
    }

    private int extractStatus(String statusLine) {
        String[] parts = statusLine.split(" ");
        return Integer.parseInt(parts[1]);
    }

    private boolean readsSetCookieSession(BufferedReader br) throws Exception {
        String line;
        boolean found = false;
        while ((line = br.readLine()) != null && !line.isEmpty()) {
            String low = line.toLowerCase();
            if (low.startsWith("set-cookie:") && low.contains("cspadminsession=")) {
                found = true;
            }
        }
        return found;
    }

    @Test
    void loginWithCsrfHeaderReachesAuth() {
        // bad password => 401, not 403
        given()
                .redirects().follow(false)
                .contentType("application/json")
                .header("X-CSP-Requested-With", "CSP-WebUI")
                .body("{\"username\":\"admin\",\"password\":\"wrong\"}")
                .post("/api/admin/session")
                .then()
                .statusCode(401);
        // good password => 200 + cookie
        String cookie = loginCookie("admin", "adminPass123456");
        assertNotNull(cookie);
    }

    @Test
    void sseAbsentOriginWithBasicIs200() throws Exception {
        String auth = Base64.getEncoder().encodeToString("admin:adminPass123456".getBytes(StandardCharsets.UTF_8));
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nAuthorization: Basic " + auth + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "Absent Origin + Basic should be 200, got " + status);
        }
    }

    @Test
    void sseCookieWithoutOriginIs403() throws Exception {
        String cookie = loginCookie("admin", "adminPass123456");
        assertNotNull(cookie);
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nCookie: CSPADMINSESSION=" + cookie + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("403"), "Absent Origin + Cookie without Basic must be 403, got " + status);
        }
    }

    @Test
    void sseCookieWithBasicOverridesAntiDosAndAuthenticates() throws Exception {
        String cookie = loginCookie("admin", "adminPass123456");
        assertNotNull(cookie);
        String auth = Base64.getEncoder().encodeToString("admin:adminPass123456".getBytes(StandardCharsets.UTF_8));
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nAuthorization: Basic " + auth + "\r\nCookie: CSPADMINSESSION=" + cookie + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "Basic + Cookie without Origin should authenticate via Basic (200), got " + status);
        }

        // Basic invalide + Cookie -> 401
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nAuthorization: Basic bWFsb3JtZWk=\r\nCookie: CSPADMINSESSION=" + cookie + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("401"), "Invalid Basic + Cookie should be 401, got " + status);
        }
    }

    @Test
    void sseCookieExtraNameDoesNotTriggerAntiDos() throws Exception {
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nCookie: CSPADMINSESSION-extra=dummy\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("401"), "Cookie with prefix match must not trigger anti-DoS (should be 401 auth), got " + status);
        }
    }

    @Test
    void sseValidSameOriginIs200() throws Exception {
        String cookie = loginCookie("admin", "adminPass123456");
        assertNotNull(cookie);
        String origin = "http://localhost:" + testPort;
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nOrigin: " + origin + "\r\nCookie: CSPADMINSESSION=" + cookie + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "Valid same-origin SSE must be 200, got " + status);
        }
    }

    @Test
    void sseInvalidOriginHostMismatchIs403() throws Exception {
        String cookie = loginCookie("admin", "adminPass123456");
        assertNotNull(cookie);
        String evilOrigin = "http://attacker.com:" + testPort;
        try (Socket s = new Socket("localhost", testPort)) {
            s.setSoTimeout(3000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nOrigin: " + evilOrigin + "\r\nCookie: CSPADMINSESSION=" + cookie + "\r\nAccept: text/event-stream\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("403"), "Evil origin must be 403, got " + status);
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            java.util.HashMap<String, String> m = new java.util.HashMap<>();
            m.put("quarkus.http.auth.basic", "true");
            m.put("csp.config.pg.enabled", "false");
            m.put("csp.admin.bootstrap-required", "false");
            m.put("csp.admin.require-https", "false");
            m.put("quarkus.flyway.enabled", "false");
            m.put("quarkus.datasource.devservices.enabled", "false");
            m.put("csp.sse.enabled", "true");
            m.put("csp.sse.replay-size", "10");
            m.put("csp.sse.subscriber-queue-size", "64");
            m.put("csp.sse.max-subscribers", "50");
            m.put("csp.sse.heartbeat-ms", "15000");
            return m;
        }
    }
}
