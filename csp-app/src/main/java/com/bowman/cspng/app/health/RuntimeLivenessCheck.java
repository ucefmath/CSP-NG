package com.bowman.cspng.app.health;

import com.bowman.cspng.app.runtime.RuntimeCore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

/**
 * Health check de liveness vérifiant l'état opérationnel du runtime core CDI.
 * Discriminant : rapporte DOWN si le runtime est activé (csp.runtime.enabled=true) mais non démarré.
 */
@Liveness
@ApplicationScoped
public class RuntimeLivenessCheck implements HealthCheck {

    private static final String CHECK_NAME = "runtime-core";

    @Inject
    RuntimeCore runtimeCore;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(CHECK_NAME);

        if (runtimeCore == null) {
            return builder.down()
                .withData("reason", "RuntimeCore bean not injected")
                .build();
        }

        boolean enabled = runtimeCore.isEnabled();
        boolean started = runtimeCore.isStarted();
        builder.withData("enabled", enabled).withData("started", started);

        if (enabled && !started) {
            return builder.down()
                .withData("reason", "Runtime is enabled but failed to start")
                .build();
        }

        return builder.up().build();
    }
}
