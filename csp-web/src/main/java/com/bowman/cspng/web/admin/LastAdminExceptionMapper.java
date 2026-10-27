package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.LastAdminException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

@Provider
public class LastAdminExceptionMapper implements ExceptionMapper<LastAdminException> {
    @Override
    public Response toResponse(LastAdminException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.CONFLICT)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("LAST_ADMIN", "Cannot remove admin role from last active admin", null, corrId))
            .build();
    }
}
