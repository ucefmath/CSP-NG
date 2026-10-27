package com.bowman.cspng.web.admin;

import io.vertx.pgclient.PgException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PgExceptionMapperTest {

    private final PgExceptionMapper mapper = new PgExceptionMapper();

    private PgException pg(String sqlState, String message) {
        return new PgException(message, null, sqlState, null);
    }

    @Test
    void known23505MapsTo409() {
        PgException e = pg("23505", "ERROR: duplicate key violates unique constraint \"uq\" (23505)");
        Response r = mapper.toResponse(e);
        assertEquals(409, r.getStatus());
        String body = r.getEntity().toString();
        assertFalse(body.contains("23505"));
        assertFalse(body.contains("duplicate key"));
    }

    @Test
    void known23503MapsTo409() {
        PgException e = pg("23503", "ERROR: insert or update on table \"csp_connector_caid_profile\" violates foreign key (23503)");
        Response r = mapper.toResponse(e);
        assertEquals(409, r.getStatus());
        String body = r.getEntity().toString();
        assertFalse(body.contains("23503"));
    }

    @Test
    void known23514MapsTo400() {
        PgException e = pg("23514", "ERROR: check constraint violation (23514)");
        Response r = mapper.toResponse(e);
        assertEquals(400, r.getStatus());
    }

    @Test
    void knownP0001PortCollisionMapsTo400Sanitized() {
        PgException e = pg("P0001", "ERROR: Port collision between extended-newcamd and profile_listener on same/wildcard bind (P0001)");
        Response r = mapper.toResponse(e);
        assertEquals(400, r.getStatus());
        String body = r.getEntity().toString();
        assertTrue(body.contains("Port collision"));
        assertFalse(body.contains("extended-newcamd and profile_listener"));
    }

    @Test
    void knownP0001CaidMapMapsTo400() {
        PgException e = pg("P0001", "ERROR: Connector x has NULL profile_name but requires >=1 caid-profile-map entries (P0001)");
        Response r = mapper.toResponse(e);
        assertEquals(400, r.getStatus());
        assertTrue(r.getEntity().toString().contains("Dynamic connector requires"));
    }

    @Test
    void unknown28P01MapsTo500() {
        PgException e = pg("28P01", "ERROR: password authentication failed for user \"csp_app\" (28P01)");
        Response r = mapper.toResponse(e);
        assertEquals(500, r.getStatus());
        String body = r.getEntity().toString();
        assertFalse(body.contains("28P01"));
        assertFalse(body.contains("password"));
        assertTrue(body.contains("INTERNAL_ERROR"));
    }

    @Test
    void unknown08006MapsTo500() {
        PgException e = pg("08006", "ERROR: connection failure (08006)");
        Response r = mapper.toResponse(e);
        assertEquals(500, r.getStatus());
        assertFalse(r.getEntity().toString().contains("08006"));
    }

    @Test
    void unknownXX000MapsTo500() {
        PgException e = pg("XX000", "ERROR: internal error XX000");
        Response r = mapper.toResponse(e);
        assertEquals(500, r.getStatus());
    }

    @Test
    void nullSqlStateMapsTo500() {
        PgException e = pg(null, "ERROR: something without code");
        Response r = mapper.toResponse(e);
        assertEquals(500, r.getStatus());
        assertFalse(r.getEntity().toString().contains("something"));
    }

    @Test
    void unknownP0001MessageMapsToGenericConstraintViolation() {
        PgException e = pg("P0001", "ERROR: some unknown trigger message that is not allowlisted (P0001)");
        Response r = mapper.toResponse(e);
        assertEquals(400, r.getStatus());
        String body = r.getEntity().toString();
        assertTrue(body.contains("Constraint violation"));
        assertFalse(body.contains("unknown trigger"));
    }

    @Test
    void known40P01MapsTo409Retryable() {
        PgException e = pg("40P01", "ERROR: deadlock detected (40P01)");
        Response r = mapper.toResponse(e);
        assertEquals(409, r.getStatus());
        String body = r.getEntity().toString();
        assertFalse(body.contains("40P01"));
        assertFalse(body.contains("deadlock"));
        assertTrue(body.contains("CONFLICT"));
    }

    @Test
    void known40001MapsTo409Retryable() {
        PgException e = pg("40001", "ERROR: could not serialize access due to concurrent update (40001)");
        Response r = mapper.toResponse(e);
        assertEquals(409, r.getStatus());
        assertFalse(r.getEntity().toString().contains("40001"));
        assertFalse(r.getEntity().toString().contains("serialize"));
    }
}
