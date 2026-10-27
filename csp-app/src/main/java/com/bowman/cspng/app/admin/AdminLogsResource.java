package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.logs.LogBufferManager;
import com.bowman.cspng.app.logs.LogEntry;
import com.bowman.cspng.app.logs.LogRingBuffer;
import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

@Path("/api/admin/logs")
@ApplicationScoped
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
@NoCache
public class AdminLogsResource {

  public static final String CACHE_CONTROL = "no-store, no-cache, must-revalidate";
  public static final String PRAGMA = "no-cache";

  @Inject
  RuntimeSnapshotService snapshotService;

  @Inject
  LogBufferManager logBufferManager;

  public record LogEntryDto(long seq, long timestamp, String level, String logger, String message) {
    public static LogEntryDto from(LogEntry e) {
      return new LogEntryDto(e.seq(), e.timestamp(), e.level(), e.logger(), e.message());
    }
  }

  public record LogsDto(String source, List<LogEntryDto> items, long nextAfter) {}

  public AdminLogsResource() {}

  public AdminLogsResource(RuntimeSnapshotService snapshotService, LogBufferManager logBufferManager) {
    this.snapshotService = snapshotService;
    this.logBufferManager = logBufferManager;
  }

  private Response ok(Object entity) {
    return Response.ok(entity).header("Cache-Control", CACHE_CONTROL).header("Pragma", PRAGMA).build();
  }

  private Response error(int status, String code, String msg) {
    return Response.status(status).entity(ErrorResponse.of(code, msg)).header("Cache-Control", CACHE_CONTROL).header("Pragma", PRAGMA).build();
  }

  @GET
  @Path("/sources")
  public Uni<Response> sources() {
    return Uni.createFrom().item(() -> {
      List<String> sources = logBufferManager != null ? logBufferManager.getSources() : List.of("main");
      return ok(snapshotService.envelope(sources));
    });
  }

  @GET
  public Uni<Response> logs(
      @QueryParam("source") @DefaultValue("main") String source,
      @QueryParam("after") String afterStr,
      @QueryParam("limit") @DefaultValue("200") int limit,
      @QueryParam("level") @DefaultValue("INFO") String level) {
    return Uni.createFrom().item(() -> {
      int boundedLimit = Math.max(1, Math.min(500, limit));
      long after = -1;
      if (afterStr != null && !afterStr.isBlank()) {
        try {
          after = Long.parseLong(afterStr.trim());
        } catch (NumberFormatException ignored) {}
      }

      String effSource = source != null && !source.isBlank() ? source.trim() : "main";
      LogRingBuffer buffer = logBufferManager != null ? logBufferManager.getBuffer(effSource) : null;
      if (buffer == null) {
        return ok(snapshotService.envelope(new LogsDto(effSource, List.of(), after > 0 ? after : 0)));
      }

      List<LogEntry> rawEntries;
      long nextAfter;
      if (after <= 0) {
        rawEntries = buffer.readLatest(boundedLimit, level);
        nextAfter = rawEntries.isEmpty() ? buffer.currentMaxSeq() : rawEntries.get(rawEntries.size() - 1).seq();
      } else {
        rawEntries = buffer.readAfter(after, boundedLimit, level);
        nextAfter = rawEntries.isEmpty() ? after : rawEntries.get(rawEntries.size() - 1).seq();
      }

      List<LogEntryDto> dtos = rawEntries.stream().map(LogEntryDto::from).toList();
      return ok(snapshotService.envelope(new LogsDto(effSource, dtos, nextAfter)));
    });
  }
}
