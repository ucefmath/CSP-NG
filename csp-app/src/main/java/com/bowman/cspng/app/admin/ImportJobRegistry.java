package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.admin.ImportModels.ImportJob;
import com.bowman.cspng.app.admin.ImportModels.JobStatus;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@ApplicationScoped
public class ImportJobRegistry {

  private static final Logger LOG = Logger.getLogger(ImportJobRegistry.class.getName());
  private static final int MAX_JOBS = 500;

  private final ConcurrentHashMap<String, ImportJob> jobs = new ConcurrentHashMap<>();

  public void put(ImportJob job) {
    if (jobs.size() >= MAX_JOBS) {
      LOG.warning("ImportJobRegistry at capacity (" + MAX_JOBS + "), job " + job.jobId() + " not stored");
      return;
    }
    jobs.put(job.jobId(), job);
  }

  public ImportJob get(String jobId) {
    return jobs.get(jobId);
  }

  public void update(ImportJob job) {
    if (!jobs.containsKey(job.jobId()) && jobs.size() >= MAX_JOBS) {
      LOG.warning("ImportJobRegistry at capacity (" + MAX_JOBS + "), update for unknown job " + job.jobId() + " dropped");
      return;
    }
    jobs.put(job.jobId(), job);
  }

  // For testing
  void clear() {
    jobs.clear();
  }

  int size() {
    return jobs.size();
  }
}
