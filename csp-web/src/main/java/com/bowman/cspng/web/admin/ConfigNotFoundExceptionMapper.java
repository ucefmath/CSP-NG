package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.ConfigNotFoundException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

@Provider
public class ConfigNotFoundExceptionMapper implements ExceptionMapper<ConfigNotFoundException> {
    @Override
    public Response toResponse(ConfigNotFoundException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.NOT_FOUND)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("NOT_FOUND", "Config resource not found", null, corrId))
            .build();
    }
}
