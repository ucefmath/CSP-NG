package com.bowman.cspng.web.admin;

import com.bowman.cspng.users.UnknownUsersException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Users bulk inexistants ⇒ 400 UNKNOWN_ITEMS, la transaction a été roulée back
 * intégralement (rien d'appliqué).
 */
@Provider
public class UnknownUsersExceptionMapper implements ExceptionMapper<UnknownUsersException> {
    @Override
    public Response toResponse(UnknownUsersException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("UNKNOWN_ITEMS",
                "unknown user(s), nothing applied",
                String.join(", ", e.unknownNames()), corrId))
            .build();
    }
}
