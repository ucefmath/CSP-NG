package com.bowman.cspng.app.admin;

import com.bowman.cspng.config.*;
import com.bowman.cspng.users.*;
import com.bowman.cspng.users.crypto.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AdminImportResourceTest.Profile.class)
class AdminImportResourceTest {

  @Inject UserDirectory userDirectory;
  @Inject ConfigHolder configHolder;
  @Inject ImportJobRegistry registry;
  @Inject AdminImportResource importResource;

  static final PasswordHasher hasher = new PasswordHasher(4);
  static volatile boolean usersAdded = false;
  static volatile boolean profileSeeded = false;

  @BeforeEach
  void setup() {
    if (!usersAdded) {
      synchronized (AdminImportResourceTest.class) {
        if (!usersAdded && userDirectory instanceof InMemoryUserDirectory dir) {
          dir.add(new UserRecord("admin", "Administrator", hasher.hash("adminPass123456"), null,
              true, true, false, false, 0, null, -1,
              Set.of(), Map.of(), Map.of(), Set.of(), 1L, null, null));
          usersAdded = true;
        }
      }
    }
    // Seed ConfigHolder with profiles
    if (!profileSeeded) {
      synchronized (AdminImportResourceTest.class) {
        if (!profileSeeded) {
          ConfigSnapshot snap = ConfigSnapshot.empty();
          // Add enabled profile "testprof" and disabled "disabledProf"
          ProfileConfig enabled = new ProfileConfig("testprof", true, false, false, 0, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
          ProfileConfig disabled = new ProfileConfig("disabledProf", false, false, false, 0, 0, null, null, 5000L, null, false, 0, null, null, null, null, 1L);
          ConfigSnapshot newSnap = new ConfigSnapshot(
              snap.root(), snap.logging(), snap.caProfilesGlobal(),
              List.of(enabled, disabled), snap.profileListeners(), snap.profileCards(),
              snap.connectionManager(), snap.serviceMap(), snap.connectors(), snap.connectorServices(),
              snap.cache(), snap.clusterUdp(), snap.webui(), snap.plugins(), snap.sidCacheLinks(),
              snap.extendedNewcamd(), snap.connectorCaidProfiles(), snap.profileProviderIdents(), System.currentTimeMillis()
          );
          configHolder.set(newSnap);
          profileSeeded = true;
        }
      }
    }
    // clear registry and cooldown between tests? Need to reset importResource cooldown map via reflection or method
    importResource.clearCooldown();
  }

  @Test
  void postReturns202WithLocation() {
    String body = "C: host1.example.com 12000 user1 pass1";
    var resp = given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .queryParam("profile", "testprof")
        .contentType("text/plain")
        .body(body)
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(202)
        .header("Location", containsString("/api/admin/connectors/import/jobs/"))
        .body("jobId", notNullValue())
        .body("location", notNullValue())
        .extract().response();
    String jobId = resp.jsonPath().getString("jobId");
    // GET job
    given()
        .auth().basic("admin", "adminPass123456")
        .get("/api/admin/connectors/import/jobs/" + jobId)
        .then()
        .statusCode(200)
        .body("jobId", equalTo(jobId));
  }

  @Test
  void postMissingProfileReturns400() {
    given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .contentType("text/plain")
        .body("C: host 12000 u p")
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(400)
        .body("code", equalTo("INVALID_INPUT"));
  }

  @Test
  void getUnknownJobReturns404() {
    given()
        .auth().basic("admin", "adminPass123456")
        .get("/api/admin/connectors/import/jobs/00000000-0000-0000-0000-000000000000")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
  }

  @Test
  void rateLimitReturns429() {
    String body = "C: host2.example.com 12000 user1 pass1";
    // first succeeds
    given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .queryParam("profile", "testprof")
        .contentType("text/plain")
        .body(body)
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(202);
    // second immediate should be rate limited
    given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .queryParam("profile", "testprof")
        .contentType("text/plain")
        .body(body)
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(429)
        .body("code", equalTo("RATE_LIMITED"));
  }

  @Test
  void disabledProfileReturns400() {
    importResource.clearCooldown();
    given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .queryParam("profile", "disabledProf")
        .contentType("text/plain")
        .body("C: host 12000 u p")
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(400)
        .body("code", equalTo("INVALID_INPUT"));
  }

  @Test
  void jobContainsMissingCaidProvidersField() throws Exception {
    importResource.clearCooldown();
    String body = "C: host-missing.example.com 12000 user1 pass1 yes { 0100:000000 }";
    var resp = given()
        .auth().basic("admin", "adminPass123456")
        .header("X-CSP-Requested-With", "CSP-WebUI")
        .queryParam("profile", "testprof")
        .contentType("text/plain")
        .body(body)
        .post("/api/admin/connectors/import")
        .then()
        .statusCode(202)
        .extract().response();
    String jobId = resp.jsonPath().getString("jobId");
    // Poll until COMPLETED or timeout
    long deadline = System.currentTimeMillis() + 8000;
    String json = null;
    while (System.currentTimeMillis() < deadline) {
      var get = given()
          .auth().basic("admin", "adminPass123456")
          .get("/api/admin/connectors/import/jobs/" + jobId)
          .then()
          .statusCode(200)
          .body("jobId", equalTo(jobId))
          .body("missingCaidProviders", notNullValue())
          .extract().response();
      json = get.asString();
      String status = get.jsonPath().getString("status");
      if ("COMPLETED".equals(status)) break;
      Thread.sleep(200);
    }
    // final assertion: field exists and is a list
    given()
        .auth().basic("admin", "adminPass123456")
        .get("/api/admin/connectors/import/jobs/" + jobId)
        .then()
        .statusCode(200)
        .body("missingCaidProviders", notNullValue());
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
          "quarkus.datasource.devservices.enabled", "false",
          "csp.runtime.enabled", "false",
          "csp.import.cooldown-seconds", "30",
          "csp.import.max-lines", "100",
          "csp.import.max-file-size-kb", "64"
      );
    }
  }
}
