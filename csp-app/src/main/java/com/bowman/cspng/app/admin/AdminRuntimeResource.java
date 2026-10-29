package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import com.bowman.cspng.cache.CacheStats;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;

@Path("/api/admin")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@NoCache
public class AdminRuntimeResource {

  public static final String CACHE_CONTROL = "no-store, no-cache, must-revalidate";
  public static final String PRAGMA = "no-cache";

  @Inject RuntimeSnapshotService snapshotService;

  // For tests direct injection
  public AdminRuntimeResource() {}
  public AdminRuntimeResource(RuntimeSnapshotService svc) { this.snapshotService = svc; }

  private Response ok(Object entity) {
    return Response.ok(entity).header("Cache-Control", CACHE_CONTROL).header("Pragma", PRAGMA).build();
  }
  private Response error(int status, String code, String msg) {
    return Response.status(status).entity(ErrorResponse.of(code, msg)).header("Cache-Control", CACHE_CONTROL).header("Pragma", PRAGMA).build();
  }

  @GET
  @Path("/status")
  public Uni<Response> status() {
    return Uni.createFrom().item(() -> snapshotService.statusPayload())
        .map(payload -> ok(snapshotService.envelope(payload)))
        .onFailure().recoverWithItem(e -> error(500, "INTERNAL_ERROR", "Internal error"));
  }

  @GET
  @Path("/profiles")
  public Uni<Response> profiles(@QueryParam("page") String pageStr, @QueryParam("size") String sizeStr) {
    Pagination p = parsePagination(pageStr, sizeStr);
    if (p.error != null) return Uni.createFrom().item(p.error);
    try {
      var full = snapshotService.profileSnapshots();
      var paged = RuntimeSnapshotService.paginate(full, p.page, p.size);
      var env = snapshotService.envelope(paged);
      return Uni.createFrom().item(ok(env));
    } catch (IllegalStateException e) {
      if (e.getMessage() != null && e.getMessage().contains("NOT_READY")) {
        return Uni.createFrom().item(error(503, "NOT_READY", "Config not yet loaded"));
      }
      return Uni.createFrom().item(error(500, "INTERNAL_ERROR", "Internal error"));
    } catch (Exception e) {
      return Uni.createFrom().item(error(500, "INTERNAL_ERROR", "Internal error"));
    }
  }

  @GET
  @Path("/connectors")
  public Uni<Response> connectors(@QueryParam("page") String pageStr, @QueryParam("size") String sizeStr) {
    Pagination p = parsePagination(pageStr, sizeStr);
    if (p.error != null) return Uni.createFrom().item(p.error);
    try {
      var full = snapshotService.connectorSnapshots();
      var paged = RuntimeSnapshotService.paginate(full, p.page, p.size);
      return Uni.createFrom().item(ok(snapshotService.envelope(paged)));
    } catch (Exception e) {
      return Uni.createFrom().item(error(500, "INTERNAL_ERROR", "Internal error"));
    }
  }

  /**
   * Alias sans ambiguïté de {@code /sessions} : en RESTEasy Reactive, la ressource de login
   * {@code @Path("/api/admin/session")} capture {@code /api/admin/sessions} (404). L'UI utilise ce chemin.
   */
  @GET
  @Path("/runtime/sessions")
  public Uni<Response> runtimeSessions(@QueryParam("page") String pageStr, @QueryParam("size") String sizeStr) {
    return sessions(pageStr, sizeStr);
  }

  @GET
  @Path("/sessions")
  public Uni<Response> sessions(@QueryParam("page") String pageStr, @QueryParam("size") String sizeStr) {
    Pagination p = parsePagination(pageStr, sizeStr);
    if (p.error != null) return Uni.createFrom().item(p.error);
    try {
      var full = snapshotService.sessionSnapshots();
      var paged = RuntimeSnapshotService.paginate(full, p.page, p.size);
      return Uni.createFrom().item(ok(snapshotService.envelope(paged)));
    } catch (Exception e) {
      return Uni.createFrom().item(error(500, "INTERNAL_ERROR", "Internal error"));
    }
  }

  @GET
  @Path("/cache")
  public Uni<Response> cache() {
    return Uni.createFrom().completionStage(
        snapshotService.cacheStatsFuture().toCompletionStage())
        .map(stats -> ok(snapshotService.envelope(stats)))
        .onFailure().recoverWithItem(e -> {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          if (cause instanceof RuntimeSnapshotService.NotReadyException || (e.getMessage() != null && e.getMessage().contains("NOT_READY")) || (cause.getMessage() != null && cause.getMessage().contains("NOT_READY"))) {
            return error(503, "NOT_READY", "Cache not ready");
          }
          return error(500, "INTERNAL_ERROR", "Internal error");
        });
  }

  private static class Pagination {
    int page;
    int size;
    Response error;
  }

  private Pagination parsePagination(String pageStr, String sizeStr) {
    Pagination r = new Pagination();
    int page = 0;
    int size = 20;
    try {
      if (pageStr != null && !pageStr.isBlank()) page = Integer.parseInt(pageStr.trim());
      if (sizeStr != null && !sizeStr.isBlank()) size = Integer.parseInt(sizeStr.trim());
    } catch (NumberFormatException e) {
      r.error = error(400, "INVALID_INPUT", "Invalid pagination parameters");
      return r;
    }
    if (page < 0 || page > 1_000_000 || size < 1 || size > 100) {
      r.error = error(400, "INVALID_INPUT", "Invalid pagination parameters");
      return r;
    }
    r.page = page;
    r.size = size;
    return r;
  }
}
