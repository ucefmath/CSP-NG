package com.bowman.cspng.web.admin;

import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.vertx.pgclient.PgException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
public class PgExceptionMapper implements ExceptionMapper<PgException> {
    private static final Logger LOG = Logger.getLogger(PgExceptionMapper.class.getName());

    @Override
    public Response toResponse(PgException e) {
        String sqlState = e.getSqlState();
        String corrId = UUID.randomUUID().toString().substring(0, 8);
        String code;
        Response.Status status;
        String message;
        if ("23505".equals(sqlState)) {
            code = "CONFLICT";
            status = Response.Status.CONFLICT;
            message = "Duplicate entry violates unique constraint";
        } else if ("23503".equals(sqlState)) {
            code = "CONFLICT";
            status = Response.Status.CONFLICT;
            message = "Referenced entity still in use or not found";
        } else if ("23514".equals(sqlState)) {
            code = "INVALID_INPUT";
            status = Response.Status.BAD_REQUEST;
            message = "Check constraint violation";
        } else if ("P0001".equals(sqlState)) {
            code = "INVALID_INPUT";
            status = Response.Status.BAD_REQUEST;
            message = sanitizeRaiseMessage(e.getMessage());
        } else if ("40P01".equals(sqlState) || "40001".equals(sqlState)) {
            code = "CONFLICT";
            status = Response.Status.CONFLICT;
            message = "Transaction conflict, please retry";
        } else {
            // Tout autre SQLSTATE (28P01, 08xxx, 40xxx, 53xxx, 57xxx, XX000, null…) -> 500
            LOG.log(Level.SEVERE, "PgException unmapped sqlState=" + sqlState + " [corrId=" + corrId + "]", e);
            code = "INTERNAL_ERROR";
            status = Response.Status.INTERNAL_SERVER_ERROR;
            message = "An internal error occurred";
        }
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(new ErrorResponse(code, message, null, corrId))
            .build();
    }

    private String sanitizeRaiseMessage(String msg) {
        if (msg == null) return "Constraint violation";
        String s = msg.trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl);
        if (s.length() > 200) s = s.substring(0, 200);
        // Allowlist stricte des messages triggers V3 - spécifiques d'abord
        if (s.contains("NULL profile_name")) return "Dynamic connector requires CAID map";
        if (s.contains("is dynamic") && s.contains("no caid-profile-map")) return "Dynamic connector requires CAID map";
        if (s.contains("has fixed profile")) return "Fixed connector cannot have CAID map";
        if (s.contains("Port collision")) return "Port collision on same/wildcard bind";
        if (s.contains("Cannot disable profile")) return "Cannot disable profile referenced by CAID map";
        if (s.contains("profile disabled") || s.contains("profile is disabled")) return "CAID map profile is disabled";
        if (s.contains("caid-profile-map")) return "CAID profile map constraint violation";
        // Tout autre message P0001 inconnu -> générique
        return "Constraint violation";
    }
}
