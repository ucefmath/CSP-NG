package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.OptimisticLockException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

@Provider
public class ConfigOptimisticLockExceptionMapper implements ExceptionMapper<OptimisticLockException> {
    @Override
    public Response toResponse(OptimisticLockException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("CONFLICT", "Optimistic lock conflict: the resource was modified by another request",
                "Expected version " + e.expectedVersion(), corrId))
            .build();
    }
}
