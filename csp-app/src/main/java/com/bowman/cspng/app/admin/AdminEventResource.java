package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import org.jboss.resteasy.reactive.NoCache;

@Path("/api/admin/events")
@ApplicationScoped
@RolesAllowed("admin")
@NoCache
public class AdminEventResource {

    @Inject AdminEventBroker broker;
    @Inject RuntimeSnapshotService snapshotService;
    @Inject Sse sse;

    public AdminEventResource() {}

    // for tests
    public AdminEventResource(AdminEventBroker broker, RuntimeSnapshotService snapshotService, Sse sse) {
        this.broker = broker;
        this.snapshotService = snapshotService;
        this.sse = sse;
    }

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<OutboundSseEvent> events(@HeaderParam("Last-Event-ID") String lastEventId) {
        if (broker == null || !broker.isEnabled()) {
            throw new NotFoundException("SSE disabled");
        }
        Multi<AdminEventBroker.AdminEvent> source = broker.subscribe(lastEventId);
        return source.map(ev -> {
            // Build envelope with stable instanceId and timestamp from event
            RuntimeSnapshotService.Envelope<AdminEventBroker.AdminPayload> envelope =
                    new RuntimeSnapshotService.Envelope<>(
                            snapshotService != null ? snapshotService.instanceId() : broker.instanceId(),
                            ev.timestamp().toString(),
                            RuntimeSnapshotService.SCHEMA_VERSION,
                            ev.payload()
                    );
            return sse.newEventBuilder()
                    .id(Long.toString(ev.id()))
                    .name(ev.type().name())
                    .mediaType(MediaType.APPLICATION_JSON_TYPE)
                    .data(RuntimeSnapshotService.Envelope.class, envelope)
                    .build();
        });
    }
}
