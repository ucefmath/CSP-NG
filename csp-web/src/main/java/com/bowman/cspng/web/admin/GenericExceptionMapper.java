package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Mapper générique contrôlé : 500 sans stack/SQL.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GenericExceptionMapper.class.getName());

    @Override
    public Response toResponse(Exception e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        LOG.log(Level.WARNING, "Unhandled exception in admin API [corrId=" + corrId + "]", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("INTERNAL_ERROR", "An internal error occurred", null, corrId))
            .build();
    }
}
