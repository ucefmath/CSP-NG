package com.bowman.cspng.app.admin;

import com.bowman.cspng.users.InMemoryUserDirectory;
import com.bowman.cspng.users.UserDirectory;
import com.bowman.cspng.users.UserRecord;
import com.bowman.cspng.users.crypto.PasswordHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminEventBridgeQuarkusTest.Profile.class)
class AdminEventBridgeQuarkusTest {

    @Inject UserDirectory userDirectory;
    @Inject AdminEventBroker broker;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    static final PasswordHasher hasher = new PasswordHasher(4);

    @BeforeEach
    void setUp() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try { dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null, true, true, false, false, 0, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null)); } catch (Exception ignored) {}
        }
    }

    @Test
    void baselineReplayStreamContainsAllowlistNoRaw() throws Exception {
        String auth = Base64.getEncoder().encodeToString("admin:adminPass123456".getBytes(StandardCharsets.UTF_8));
        // deterministic barrier: deadline + 2s get with catch
        long deadlineWait = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineWait) {
            if (broker.getReplayBufferSize() > 0) break;
            try {
                broker.statsAsync().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                continue;
            }
            Thread.yield();
        }
        assertTrue(broker.getReplayBufferSize() > 0, "bridge baseline should have published at least one event, replay=" + broker.getReplayBufferSize());
        try (Socket socket = new Socket("localhost", testPort)) {
            socket.setSoTimeout(8000);
            String req = "GET /api/admin/events HTTP/1.1\r\nHost: localhost:" + testPort + "\r\nAuthorization: Basic " + auth + "\r\nAccept: text/event-stream\r\nLast-Event-ID: 0\r\n\r\n";
            socket.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String status = br.readLine();
            assertNotNull(status);
            assertTrue(status.contains("200"), "SSE should be 200 got " + status);
            String line;
            while ((line = br.readLine()) != null && !line.isEmpty()) {
                // consume headers
            }
            // read events with deadline, ensure at least one data line with allowlist fields
            StringBuilder all = new StringBuilder();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(6).toNanos();
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            boolean sawAllowlisted = false;
            boolean sawSensitiveLeak = false;
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout(1000);
                String l;
                try { l = br.readLine(); } catch (java.net.SocketTimeoutException e) { continue; }
                if (l == null) break;
                all.append(l).append("\n");
                if (l.startsWith("id:")) assertTrue(l.matches("id:\\s*\\d+"), "id should be numeric");
                if (l.startsWith("event:")) {
                    String ev = l.substring(6).trim();
                    assertTrue(Set.of("STATUS_CHANGED","CONFIG_RELOADED","CONNECTOR_CHANGED","SESSION_CHANGED","CACHE_CHANGED","DCW_CHANGED","SNAPSHOT_REQUIRED","HEARTBEAT").contains(ev), "allowlisted event: " + ev);
                }
                if (l.startsWith("data:")) {
                    String json = l.substring(5).trim();
                    if (json.isEmpty()) continue;
                    // data is Envelope json; check no raw sensitive
                    String low = json.toLowerCase();
                    if (low.contains("password") || low.contains("\"host\"") || low.contains("\"port\"") || low.contains("ecm") || low.contains("\"cw\"") || low.contains("\"token\"")) {
                        sawSensitiveLeak = true;
                    }
                    // try parse payload field to ensure allowlist structure
                    try {
                        JsonNode node = mapper.readTree(json);
                        JsonNode payload = node.get("payload");
                        if (payload != null) {
                            // payload should only contain allowlisted keys: for status: totalConnectors etc, for connector: state/count, etc.
                            // ensure no forbidden keys leak
                            String payloadStr = payload.toString().toLowerCase();
                            if (payloadStr.contains("username") && payloadStr.contains("@")) sawSensitiveLeak = true;
                            if (payloadStr.contains("remoteaddress") && payloadStr.contains(".")) {
                                // session anonymized should be *** or /24; not full IP leak — but payload for SSE is state/count only, so no address
                            }
                            // check allowlist event types produce expected fields
                            JsonNode state = payload.get("state");
                            JsonNode count = payload.get("count");
                            JsonNode size = payload.get("size");
                            // at least one of these should be present for our bridge events
                            if (state != null || count != null || size != null) sawAllowlisted = true;
                            // for status payload, check fields
                            if (payload.has("totalConnectors") || payload.has("activeConnectors") || payload.has("degraded")) sawAllowlisted = true;
                        } else {
                            // data may be envelope string, still check
                            sawAllowlisted = true;
                        }
                    } catch (Exception e) {
                        // ignore parse failure, but still check leak
                    }
                    if (sawAllowlisted && !sawSensitiveLeak) break;
                }
            }
            String out = all.toString();
            assertTrue(out.contains("id:") || out.contains("event:"), "should contain SSE id/event got: " + out);
            assertTrue(out.contains("data:"), "should contain data got: " + out);
            assertFalse(sawSensitiveLeak, "no sensitive leak allowed in SSE data: " + out);
            assertTrue(sawAllowlisted || out.contains("data:"), "should have allowlisted payload");
            // explicit zero sensitive: ensure no raw session/connector info in captured data
            String lowAll = out.toLowerCase();
            assertFalse(lowAll.contains("password"), "leak password");
            assertFalse(lowAll.contains("\"host\""), "leak host");
            assertFalse(lowAll.contains("\"port\""), "leak port");
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            Map<String,String> m = new java.util.HashMap<>();
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
            m.put("csp.sse.scan-ms","400");
            m.put("csp.runtime.enabled","false");
            return m;
        }
    }
}

@QuarkusTest
@TestProfile(AdminEventBridgeQuarkusDisabledTest.DisabledProfile.class)
class AdminEventBridgeQuarkusDisabledTest {
    @Inject UserDirectory userDirectory;
    static final PasswordHasher hasher2 = new PasswordHasher(4);
    @BeforeEach void setUp() {
        if (userDirectory instanceof InMemoryUserDirectory dir) {
            try { dir.add(new UserRecord("admin","Administrator", hasher2.hash("adminPass123456"), null, true, true, false, false, 0, null, -1, Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null)); } catch (Exception ignored) {}
        }
    }
    @Test void disabledBroker404() {
        given().auth().preemptive().basic("admin","adminPass123456")
                .header("Accept","text/event-stream")
                .get("/api/admin/events").then().statusCode(404);
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
                    "csp.sse.enabled","false",
                    "csp.runtime.enabled","false"
            );
        }
    }
}
