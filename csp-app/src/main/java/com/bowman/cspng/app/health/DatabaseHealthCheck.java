package com.bowman.cspng.app.health;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import io.vertx.sqlclient.Pool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;

/**
 * Health check de readiness vérifiant la connectivité PostgreSQL via le client réactif non-bloquant.
 * Ne fait aucun appel JDBC bloquant.
 */
@Readiness
@ApplicationScoped
public class DatabaseHealthCheck implements AsyncHealthCheck {

    private static final String CHECK_NAME = "Database connections (reactive)";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Inject
    Instance<Pool> pgPoolInstance;

    @Override
    public Uni<HealthCheckResponse> call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(CHECK_NAME);

        if (pgPoolInstance.isUnsatisfied() || pgPoolInstance.isAmbiguous()) {
            return Uni.createFrom().item(
                builder.down()
                    .withData("status", "Reactive PG pool unavailable or not configured")
                    .build()
            );
        }

        Pool pool;
        try {
            pool = pgPoolInstance.get();
        } catch (Exception e) {
            return Uni.createFrom().item(
                builder.down()
                    .withData("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .build()
            );
        }

        if (pool == null) {
            return Uni.createFrom().item(
                builder.down()
                    .withData("status", "Reactive PG pool is null")
                    .build()
            );
        }

        return Uni.createFrom().completionStage(() -> pool.query("SELECT 1").execute().toCompletionStage())
            .ifNoItem().after(TIMEOUT).fail()
            .map(rows -> builder.up().withData("status", "PostgreSQL reactive client reachable").build())
            .onFailure().recoverWithItem(err -> builder.down()
                .withData("error", err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName())
                .build()
            );
    }
}
