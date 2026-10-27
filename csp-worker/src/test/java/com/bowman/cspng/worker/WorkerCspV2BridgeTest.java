package com.bowman.cspng.worker;

import com.bowman.cspng.cluster.tcp.CspV2Manager;
import com.bowman.cspng.codec.cspv2.CspV2Payload;
import com.bowman.cspng.domain.CwReply;
import com.bowman.cspng.domain.EcmKey;
import com.bowman.cspng.domain.EcmRequest;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkerCspV2BridgeTest — validation sémantique des réponses consultNode")
class WorkerCspV2BridgeTest {

  private static final int CMD_TAG = 0x80;
  private static final int SRV_ID = 0x1234;
  private static final int NET_ID = 0x0001;
  private static final int CA_ID = 0x0100;
  private static final int HASH = 0xABCD1234;

  private static byte[] validCw() {
    byte[] cw = new byte[16];
    for (int i = 0; i < 16; i++) cw[i] = (byte) (i + 1);
    cw[3] = (byte) ((cw[0] + cw[1] + cw[2]) & 0xFF);
    cw[7] = (byte) ((cw[4] + cw[5] + cw[6]) & 0xFF);
    cw[11] = (byte) ((cw[8] + cw[9] + cw[10]) & 0xFF);
    cw[15] = (byte) ((cw[12] + cw[13] + cw[14]) & 0xFF);
    return cw;
  }

  private static EcmRequest createRequest() {
    EcmKey key = new EcmKey(CMD_TAG, SRV_ID, NET_ID, CA_ID, HASH);
    byte[] body = new byte[] { (byte) CMD_TAG, 0x00, 0x01, 0x02, 0x03, 0x04 };
    return new EcmRequest(key, body, "default", "127.0.0.1", "origin", System.currentTimeMillis(), null, 5000L);
  }

  @Test
  @DisplayName("consultNode : reply OK avec CW valide -> succès, CW retournée et stats incrémentées")
  void consultNodeOkReturnsCw() throws Exception {
    byte[] cw = validCw();
    var dw = new CspV2Payload.DcwReply(CMD_TAG, SRV_ID, NET_ID, CA_ID, HASH, 0x80,
        CspV2Payload.DcwStatus.OK, cw, "conn1");
    var peerReply = new CspV2Manager.CspV2PeerReply("central", dw);

    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge((peer, req) -> Future.succeededFuture(peerReply));

    CwReply reply = bridge.consultNode("central", createRequest(), 5000)
        .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

    assertNotNull(reply);
    assertArrayEquals(cw, reply.cw());
    assertEquals("node:central", reply.connectorName());
    assertEquals(1, bridge.nodeStats().consultOk("central"));
  }

  @Test
  @DisplayName("consultNode : reply NOT_FOUND avec champs concordants -> échec 'node reply not found'")
  void consultNodeNotFoundReturnsDistinctMessage() {
    var dw = new CspV2Payload.DcwReply(CMD_TAG, SRV_ID, NET_ID, CA_ID, HASH, 0,
        CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    var peerReply = new CspV2Manager.CspV2PeerReply("central", dw);

    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge((peer, req) -> Future.succeededFuture(peerReply));

    ExecutionException ex = assertThrows(ExecutionException.class, () ->
        bridge.consultNode("central", createRequest(), 5000)
            .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));

    assertEquals("node reply not found", ex.getCause().getMessage());
    assertEquals(0, bridge.nodeStats().consultOk("central"));
  }

  @Test
  @DisplayName("consultNode : reply avec champs mismatch (CAID différent) -> échec 'invalid node reply'")
  void consultNodeCaidMismatchReturnsInvalidNodeReply() {
    byte[] cw = validCw();
    int wrongCaId = 0x0500;
    var dw = new CspV2Payload.DcwReply(CMD_TAG, SRV_ID, NET_ID, wrongCaId, HASH, 0x80,
        CspV2Payload.DcwStatus.OK, cw, "conn1");
    var peerReply = new CspV2Manager.CspV2PeerReply("central", dw);

    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge((peer, req) -> Future.succeededFuture(peerReply));

    ExecutionException ex = assertThrows(ExecutionException.class, () ->
        bridge.consultNode("central", createRequest(), 5000)
            .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));

    assertEquals("invalid node reply", ex.getCause().getMessage());
    assertEquals(0, bridge.nodeStats().consultOk("central"));
  }

  @Test
  @DisplayName("consultNode : reply NOT_FOUND avec champs mismatch -> échec 'invalid node reply'")
  void consultNodeNotFoundWithMismatchReturnsInvalidNodeReply() {
    int wrongServiceId = 0x9999;
    var dw = new CspV2Payload.DcwReply(CMD_TAG, wrongServiceId, NET_ID, CA_ID, HASH, 0,
        CspV2Payload.DcwStatus.NOT_FOUND, new byte[0], "");
    var peerReply = new CspV2Manager.CspV2PeerReply("central", dw);

    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge((peer, req) -> Future.succeededFuture(peerReply));

    ExecutionException ex = assertThrows(ExecutionException.class, () ->
        bridge.consultNode("central", createRequest(), 5000)
            .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));

    assertEquals("invalid node reply", ex.getCause().getMessage());
  }

  @Test
  @DisplayName("consultNode : reply OK avec CW invalide (mauvais checksum) -> échec 'invalid node reply'")
  void consultNodeInvalidCwChecksumReturnsInvalidNodeReply() {
    byte[] badCw = validCw();
    badCw[3] = (byte) 0xFF; // Invalide le checksum
    var dw = new CspV2Payload.DcwReply(CMD_TAG, SRV_ID, NET_ID, CA_ID, HASH, 0x80,
        CspV2Payload.DcwStatus.OK, badCw, "conn1");
    var peerReply = new CspV2Manager.CspV2PeerReply("central", dw);

    WorkerCspV2Bridge bridge = new WorkerCspV2Bridge((peer, req) -> Future.succeededFuture(peerReply));

    ExecutionException ex = assertThrows(ExecutionException.class, () ->
        bridge.consultNode("central", createRequest(), 5000)
            .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS));

    assertEquals("invalid node reply", ex.getCause().getMessage());
    assertEquals(0, bridge.nodeStats().consultOk("central"));
  }
}
