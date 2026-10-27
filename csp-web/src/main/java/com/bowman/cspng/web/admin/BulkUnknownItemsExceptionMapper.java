package com.bowman.cspng.web.admin;

import com.bowman.cspng.config.BulkUnknownItemsException;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Items bulk inexistants ⇒ 400 UNKNOWN_ITEMS, la transaction a été roulée back
 * intégralement (rien d'appliqué).
 */
@Provider
public class BulkUnknownItemsExceptionMapper implements ExceptionMapper<BulkUnknownItemsException> {
    @Override
    public Response toResponse(BulkUnknownItemsException e) {
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        return Response.status(Response.Status.BAD_REQUEST)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse("UNKNOWN_ITEMS",
                "unknown item(s), nothing applied",
                String.join(", ", e.unknownIds()), corrId))
            .build();
    }
}
