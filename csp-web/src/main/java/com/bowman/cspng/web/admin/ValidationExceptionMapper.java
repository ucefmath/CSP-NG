package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Mapper pour ValidationException (Bean Validation).
 */
@Provider
public class ValidationExceptionMapper implements ExceptionMapper<jakarta.validation.ConstraintViolationException> {
    @Override
    public Response toResponse(jakarta.validation.ConstraintViolationException e) {
        StringBuilder details = new StringBuilder();
        e.getConstraintViolations().forEach(cv -> {
            if (details.length() > 0) details.append("; ");
            details.append(cv.getPropertyPath()).append(": ").append(cv.getMessage());
        });
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("VALIDATION_ERROR", "Validation failed", details.toString(), corrId))
            .build();
    }
}
