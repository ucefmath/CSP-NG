package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UserProtocolSecretRepository.SecretCollisionException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Mappe {@link SecretCollisionException} vers un statut HTTP 409 CONFLICT.
 */
@Provider
public class SecretCollisionExceptionMapper implements ExceptionMapper<SecretCollisionException> {

    @Override
    public Response toResponse(SecretCollisionException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("SECRET_COLLISION", e.getMessage(), null, corrId))
            .build();
    }
}
