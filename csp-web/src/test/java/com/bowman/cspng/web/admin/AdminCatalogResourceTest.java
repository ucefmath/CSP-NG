package com.bowman.cspng.web.admin;

import com.bowman.cspng.connectors.catalog.CardCapability;
import com.bowman.cspng.connectors.catalog.CardCatalogRepository;
import com.bowman.cspng.connectors.catalog.CardSource;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AdminCatalogResource — REST Admin Catalogue de cartes (P6/P8)")
class AdminCatalogResourceTest {

    @Test
    @DisplayName("GET capabilities — retourne liste vide si repository indisponible ou vide")
    void listCapabilitiesReturnsList() {
        AdminCatalogResource res = new AdminCatalogResource();
        res.catalogRepository = null;
        Response r = res.listCapabilities("default").await().indefinitely();
        assertEquals(200, r.getStatus());
        assertEquals(List.of(), r.getEntity());
    }

    @Test
    @DisplayName("PUT state — validation format (rejet si invalide)")
    void putStateInvalidInput() {
        AdminCatalogResource res = new AdminCatalogResource();
        Response r1 = res.setCapabilityState(1L, null).await().indefinitely();
        assertEquals(400, r1.getStatus());

        Response r2 = res.setCapabilityState(1L, new AdminCatalogResource.StateUpdateRequest("invalid_state", 1L)).await().indefinitely();
        assertEquals(400, r2.getStatus());
    }

    @Test
    @DisplayName("PUT state — succès transition (published / blocked)")
    void putStateSuccess() {
        CardCapability cap = new CardCapability(1L, "default", 0x0100, 0, CardCapability.State.PUBLISHED, 1001L, 2L, Instant.now());
        CardCatalogRepository repo = new CardCatalogRepository(null) {
            @Override
            public Future<CardCapability> setCapabilityState(long id, CardCapability.State newState, long expectedVersion) {
                assertEquals(1L, id);
                assertEquals(CardCapability.State.PUBLISHED, newState);
                assertEquals(1L, expectedVersion);
                return Future.succeededFuture(cap);
            }
        };

        AdminCatalogResource res = new AdminCatalogResource();
        res.catalogRepository = repo;

        Response r = res.setCapabilityState(1L, new AdminCatalogResource.StateUpdateRequest("published", 1L)).await().indefinitely();
        assertEquals(200, r.getStatus());
        AdminCatalogResource.CapabilityResponse body = (AdminCatalogResource.CapabilityResponse) r.getEntity();
        assertEquals("published", body.state());
        assertEquals(1001L, body.cardId());
    }
}
