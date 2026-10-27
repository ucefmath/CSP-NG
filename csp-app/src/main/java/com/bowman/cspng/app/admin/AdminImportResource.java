package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.admin.ImportModels.ImportJob;
import com.bowman.cspng.config.ConfigHolder;
import com.bowman.cspng.config.ProfileConfig;
import com.bowman.cspng.config.importer.CccamCfgParser;
import com.bowman.cspng.web.admin.dto.ErrorResponse;
import io.vertx.core.Vertx;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Path("/api/admin/connectors/import")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class AdminImportResource {

  @Inject Vertx vertx;
  @Inject ConfigHolder configHolder;
  @Inject ImportJobRegistry registry;
  @Inject CccamImportService importService;

  @ConfigProperty(name = "csp.import.max-file-size-kb", defaultValue = "64")
  int maxFileSizeKb;

  @ConfigProperty(name = "csp.import.max-lines", defaultValue = "100")
  int maxLines;

  @ConfigProperty(name = "csp.import.cooldown-seconds", defaultValue = "30")
  int cooldownSeconds;

  // In-memory rate limiting per actor
  private final ConcurrentHashMap<String, Instant> lastImportByActor = new ConcurrentHashMap<>();

  @Context
  SecurityContext securityContext;

  public AdminImportResource() {}

  // Visible for testing
  void clearCooldown() { lastImportByActor.clear(); }

  @POST
  @RolesAllowed("admin")
  @Consumes(MediaType.TEXT_PLAIN)
  public Response importCccam(@QueryParam("profile") String profile,
                               @QueryParam("proxy") String proxy,
                               String body) {
    String actor = actor();

    // Rate limiting check first (cooldown)
    if (isRateLimited(actor)) {
      return Response.status(429)
          .entity(ErrorResponse.of("RATE_LIMITED", "Import cooldown active, try later"))
          .build();
    }

    // profile mandatory
    if (profile == null || profile.isBlank()) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "query param profile is required")).build();
    }
    String targetProfile = profile.trim();

    if (!targetProfile.matches("[a-zA-Z0-9_-]{1,64}")) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profile name contains invalid characters")).build();
    }

    // Validate profile exists and enabled
    if (!configHolder.isInitialized()) {
      return Response.status(503).entity(ErrorResponse.of("NOT_READY", "Config not yet loaded")).build();
    }
    ProfileConfig pc = configHolder.get().profileByName().get(targetProfile);
    if (pc == null) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profile not found: " + targetProfile)).build();
    }
    if (!Boolean.TRUE.equals(pc.enabled())) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "profile disabled: " + targetProfile)).build();
    }

    // Proxy optionnel : si fourni, doit exister et être activé (fail-closed, jamais de direct implicite)
    String proxyName = null;
    if (proxy != null && !proxy.isBlank()) {
      proxyName = proxy.trim();
      if (!proxyName.matches("[A-Za-z0-9_.\\-]{1,64}")) {
        return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "proxy name invalid")).build();
      }
      var p = configHolder.get().proxyByName().get(proxyName);
      if (p == null) {
        return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "proxy not found: " + proxyName)).build();
      }
      if (!p.enabled()) {
        return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "proxy disabled: " + proxyName)).build();
      }
    }

    // Body empty
    if (body == null || body.isBlank()) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "request body empty")).build();
    }

    // File size limit (bytes)
    int maxBytes = maxFileSizeKb * 1024;
    int bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
    if (bodyBytes > maxBytes) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "file too large, max " + maxFileSizeKb + "KB")).build();
    }

    // Parse to count lines C/N
    CccamCfgParser.ParseResult pr = CccamCfgParser.parse(body);
    int parsedCount = pr.parsedLines().size();
    if (parsedCount > maxLines) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "too many lines: " + parsedCount + " > max " + maxLines)).build();
    }

    // Create job
    String jobId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    ImportJob pending = new ImportModels.ImportJob(jobId, actor, targetProfile, ImportModels.JobStatus.PENDING,
        now, null, java.util.List.of(), null);
    registry.put(pending);

    // Update cooldown
    lastImportByActor.put(actor, Instant.now());

    String location = "/api/admin/connectors/import/jobs/" + jobId;

    // Trigger async processing on worker thread
    String contentCopy = body;
    String proxyNameCopy = proxyName;
    vertx.executeBlocking(() -> {
      importService.processAsync(jobId, contentCopy, targetProfile, actor, proxyNameCopy);
      return null;
    }, false);

    return Response.status(202)
        .header("Location", location)
        .entity(Map.of("jobId", jobId, "location", location))
        .build();
  }

  @GET
  @Path("/jobs/{jobId}")
  @RolesAllowed("admin")
  public Response getJob(@PathParam("jobId") String jobId) {
    if (jobId == null || jobId.isBlank()) {
      return Response.status(400).entity(ErrorResponse.of("INVALID_INPUT", "jobId required")).build();
    }
    ImportJob job = registry.get(jobId);
    if (job == null) {
      return Response.status(404).entity(ErrorResponse.of("NOT_FOUND", "job not found")).build();
    }
    return Response.ok(job).build();
  }

  private boolean isRateLimited(String actor) {
    Instant last = lastImportByActor.get(actor);
    if (last == null) return false;
    Instant now = Instant.now();
    long elapsed = now.getEpochSecond() - last.getEpochSecond();
    return elapsed < cooldownSeconds;
  }

  private String actor() {
    Principal p = securityContext != null ? securityContext.getUserPrincipal() : null;
    return p != null ? p.getName() : "unknown";
  }
}
