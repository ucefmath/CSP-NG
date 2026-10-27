package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotFoundExceptionMapperTest {

    private final NotFoundExceptionMapper mapper = new NotFoundExceptionMapper();

    @Test
    void mapsNotFoundExceptionTo404JsonErrorResponse() {
        NotFoundException ex = new NotFoundException("Not found");
        Response response = mapper.toResponse(ex);

        assertEquals(404, response.getStatus());
        assertEquals("application/json", response.getMediaType().toString());
        assertTrue(response.getEntity() instanceof ErrorResponse);
        ErrorResponse err = (ErrorResponse) response.getEntity();
        assertEquals("NOT_FOUND", err.code());
        assertEquals("Resource not found", err.message());
        assertNull(err.details());
        assertNotNull(err.correlationId());
        assertEquals(8, err.correlationId().length());
    }
}
