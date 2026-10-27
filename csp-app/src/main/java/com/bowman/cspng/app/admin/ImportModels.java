package com.bowman.cspng.app.admin;

import java.time.Instant;
import java.util.List;

public final class ImportModels {
  private ImportModels() {}

  public enum JobStatus { PENDING, RUNNING, COMPLETED }

  public enum LineStatus { IMPORTED, DUPLICATE, DNS_RESOLVE_FAILED, PROBE_FAILED, PARSE_ERROR, PERSIST_FAILED }

  public record LineResult(int lineNumber, String type, String host, int port, String username,
                           LineStatus status, String reason, String connectorName) {}

  public record ImportSummary(int total, int imported, int duplicate, int dnsFailed, int probeFailed, int persistFailed, int parseError) {}

  public record MissingCaidProvider(int caId, int providerIdent, boolean caIdKnown) {}

  public record ImportJob(String jobId, String actor, String targetProfile, JobStatus status,
                          Instant startedAt, Instant completedAt,
                          List<LineResult> results, ImportSummary summary,
                          List<MissingCaidProvider> missingCaidProviders) {
    public ImportJob {
      results = results == null ? List.of() : List.copyOf(results);
      missingCaidProviders = missingCaidProviders == null ? List.of() : List.copyOf(missingCaidProviders);
    }
    public ImportJob(String jobId, String actor, String targetProfile, JobStatus status,
                     Instant startedAt, Instant completedAt,
                     List<LineResult> results, ImportSummary summary) {
      this(jobId, actor, targetProfile, status, startedAt, completedAt, results, summary, List.of());
    }
  }
}
