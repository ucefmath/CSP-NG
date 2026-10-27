package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {
    @Override
    public Response toResponse(IllegalArgumentException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            .entity(new ErrorResponse("INVALID_INPUT", e.getMessage(), null, corrId))
            .build();
    }
}
