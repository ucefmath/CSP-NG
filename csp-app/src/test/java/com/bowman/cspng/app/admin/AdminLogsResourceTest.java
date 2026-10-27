package com.bowman.cspng.app.admin;

import com.bowman.cspng.app.logs.LogBufferManager;
import com.bowman.cspng.app.runtime.RuntimeSnapshotService;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdminLogsResourceTest {

  @Test
  @DisplayName("GET /api/admin/logs/sources retourne sources disponibles")
  void testSources() {
    LogBufferManager logManager = new LogBufferManager(100, 100, 64);
    logManager.getMainBuffer().add(System.currentTimeMillis(), "INFO", "main", "hello");
    logManager.recordWorkerLogs("worker-1", List.of(
        new com.bowman.cspng.config.RelayWorkerContract.WorkerLogEntry(1, System.currentTimeMillis(), "INFO", "w", "msg")
    ));

    RuntimeSnapshotService snapshotService = new RuntimeSnapshotService();
    AdminLogsResource resource = new AdminLogsResource(snapshotService, logManager);

    Response resp = resource.sources().await().indefinitely();
    assertEquals(200, resp.getStatus());
    RuntimeSnapshotService.Envelope<?> env = (RuntimeSnapshotService.Envelope<?>) resp.getEntity();
    assertNotNull(env);
    @SuppressWarnings("unchecked")
    List<String> list = (List<String>) env.payload();
    assertEquals(List.of("main", "worker:worker-1"), list);
  }

  @Test
  @DisplayName("GET /api/admin/logs retourne les entrées et nextAfter")
  void testLogsIncremental() {
    LogBufferManager logManager = new LogBufferManager(100, 100, 64);
    logManager.getMainBuffer().add(System.currentTimeMillis(), "INFO", "main", "msg 1");
    logManager.getMainBuffer().add(System.currentTimeMillis(), "WARN", "main", "msg 2");

    RuntimeSnapshotService snapshotService = new RuntimeSnapshotService();
    AdminLogsResource resource = new AdminLogsResource(snapshotService, logManager);

    // Initial load: after <= 0
    Response resp1 = resource.logs("main", "0", 200, "INFO").await().indefinitely();
    assertEquals(200, resp1.getStatus());
    RuntimeSnapshotService.Envelope<?> env1 = (RuntimeSnapshotService.Envelope<?>) resp1.getEntity();
    AdminLogsResource.LogsDto dto1 = (AdminLogsResource.LogsDto) env1.payload();
    assertEquals(2, dto1.items().size());
    assertEquals(2, dto1.nextAfter());

    // Incremental load: after = 2 (pas de nouvelles lignes)
    Response resp2 = resource.logs("main", "2", 200, "INFO").await().indefinitely();
    AdminLogsResource.LogsDto dto2 = (AdminLogsResource.LogsDto) ((RuntimeSnapshotService.Envelope<?>) resp2.getEntity()).payload();
    assertEquals(0, dto2.items().size());
    assertEquals(2, dto2.nextAfter());

    // Nouvelle ligne ajoutée
    logManager.getMainBuffer().add(System.currentTimeMillis(), "ERROR", "main", "msg 3");
    Response resp3 = resource.logs("main", "2", 200, "INFO").await().indefinitely();
    AdminLogsResource.LogsDto dto3 = (AdminLogsResource.LogsDto) ((RuntimeSnapshotService.Envelope<?>) resp3.getEntity()).payload();
    assertEquals(1, dto3.items().size());
    assertEquals("msg 3", dto3.items().get(0).message());
    assertEquals(3, dto3.nextAfter());
  }
}
