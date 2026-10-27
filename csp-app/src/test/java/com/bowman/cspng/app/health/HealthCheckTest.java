package com.bowman.cspng.app.health;

import com.bowman.cspng.app.runtime.RuntimeCore;
import com.bowman.cspng.users.InMemoryUserDirectory;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class HealthCheckTest {

    @Test
    void livenessEndpointReturnsUp() {
        given()
            .when().get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("checks.name", hasItem("runtime-core"));
    }

    @Test
    void livenessDiscriminatesEnabledButNotStarted() {
        Vertx v = Vertx.vertx();
        try {
            var rc = new RuntimeCore(v, new InMemoryUserDirectory(v));
            setRuntimeEnabled(rc, true); // activé mais pas encore démarré -> DOWN
            var check = new RuntimeLivenessCheck();
            check.runtimeCore = rc;

            var responseDown = check.call();
            assertEquals(HealthCheckResponse.Status.DOWN, responseDown.getStatus());
            assertEquals("Runtime is enabled but failed to start", responseDown.getData().orElseThrow().get("reason"));

            // Démarrage effectif -> UP
            rc.onStart(null);
            var responseUp = check.call();
            assertEquals(HealthCheckResponse.Status.UP, responseUp.getStatus());

            // Désactivé (ex: profil test) -> UP (état valide)
            rc.onStop(null);
            setRuntimeEnabled(rc, false);
            var responseDisabled = check.call();
            assertEquals(HealthCheckResponse.Status.UP, responseDisabled.getStatus());
        } finally {
            v.close();
        }
    }

    private static void setRuntimeEnabled(RuntimeCore rc, boolean enabled) {
        try {
            var f = RuntimeCore.class.getDeclaredField("runtimeEnabled");
            f.setAccessible(true);
            f.set(rc, enabled);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void readinessEndpointContainsDatabaseCheck() {
        // En profil test sans DB, le statut global ready peut être UP ou DOWN selon la DB,
        // mais le check Database connections (reactive) doit être présent dans la réponse
        given()
            .when().get("/q/health/ready")
            .then()
            .body("checks.name", hasItem("Database connections (reactive)"));
    }

    @Test
    void metricsEndpointExposesPrometheusMetrics() {
        given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("jvm_"));
    }
}
