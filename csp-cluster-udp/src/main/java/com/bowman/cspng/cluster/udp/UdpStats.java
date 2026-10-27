package com.bowman.cspng.cluster.udp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compteurs de transport UDP (legacy {@code ClusteredCache} + compteurs de sécurité §2.6).
 *
 * <p>Tous les compteurs sont atomiques car ils peuvent être incrémentés depuis
 * l'event-loop Vert.x (réception UDP) et lus par un thread externe (stats HTTP).
 */
public class UdpStats {

  // --- Legacy counters (ClusteredCache) ---
  private final AtomicLong sentPending = new AtomicLong();
  private final AtomicLong sentEntries = new AtomicLong();
  private final AtomicLong reSentEntries = new AtomicLong();
  private final AtomicLong receivedEntries = new AtomicLong();
  private final AtomicLong receivedPending = new AtomicLong();
  private final AtomicLong receivedDiscarded = new AtomicLong();
  private final AtomicLong receivedBadChecksum = new AtomicLong();
  private final AtomicLong receivedZeroCw = new AtomicLong();
  private final AtomicLong receivedBadCw = new AtomicLong();
  private final AtomicLong receivedBadId = new AtomicLong();
  /** Replies rejetées par la policy DCW AVANT injection au cache (lot 1, chemin central). */
  private final AtomicLong receivedRejectedDcw = new AtomicLong();
  private final AtomicLong receivedResendReqs = new AtomicLong();
  private final AtomicLong sentResendReqs = new AtomicLong();

  // --- Security counters (plan §2.6) ---
  private final AtomicLong rateLimited = new AtomicLong();
  private final AtomicLong invalidSize = new AtomicLong();
  private final AtomicLong unknownType = new AtomicLong();
  private final AtomicLong antiAmplificationRejected = new AtomicLong();
  private final AtomicLong parseErrors = new AtomicLong();
  private final AtomicLong notAllowlisted = new AtomicLong();

  // --- Ping counters ---
  private final AtomicLong pingSent = new AtomicLong();
  private final AtomicLong pingReceived = new AtomicLong();
  private final AtomicLong pongReceived = new AtomicLong();

  // --- Convenience increment methods ---
  public void incSentPending() { sentPending.incrementAndGet(); }
  public void incSentEntries() { sentEntries.incrementAndGet(); }
  public void incReSentEntries() { reSentEntries.incrementAndGet(); }
  public void incReceivedEntries() { receivedEntries.incrementAndGet(); }
  public void incReceivedPending() { receivedPending.incrementAndGet(); }
  public void incReceivedDiscarded() { receivedDiscarded.incrementAndGet(); }
  public void incReceivedBadChecksum() { receivedBadChecksum.incrementAndGet(); }
  public void incReceivedZeroCw() { receivedZeroCw.incrementAndGet(); }
  public void incReceivedBadCw() { receivedBadCw.incrementAndGet(); }
  public void incReceivedBadId() { receivedBadId.incrementAndGet(); }
  public void incReceivedRejectedDcw() { receivedRejectedDcw.incrementAndGet(); }
  public void incReceivedResendReqs() { receivedResendReqs.incrementAndGet(); }
  public void incSentResendReqs() { sentResendReqs.incrementAndGet(); }
  public void incRateLimited() { rateLimited.incrementAndGet(); }
  public void incInvalidSize() { invalidSize.incrementAndGet(); }
  public void incUnknownType() { unknownType.incrementAndGet(); }
  public void incAntiAmplificationRejected() { antiAmplificationRejected.incrementAndGet(); }
  public void incParseErrors() { parseErrors.incrementAndGet(); }
  public void incNotAllowlisted() { notAllowlisted.incrementAndGet(); }
  public void incPingSent() { pingSent.incrementAndGet(); }
  public void incPingReceived() { pingReceived.incrementAndGet(); }
  public void incPongReceived() { pongReceived.incrementAndGet(); }

  // --- Getters ---
  public long sentPending() { return sentPending.get(); }
  public long sentEntries() { return sentEntries.get(); }
  public long reSentEntries() { return reSentEntries.get(); }
  public long receivedEntries() { return receivedEntries.get(); }
  public long receivedPending() { return receivedPending.get(); }
  public long receivedDiscarded() { return receivedDiscarded.get(); }
  public long receivedBadChecksum() { return receivedBadChecksum.get(); }
  public long receivedZeroCw() { return receivedZeroCw.get(); }
  public long receivedBadCw() { return receivedBadCw.get(); }
  public long receivedBadId() { return receivedBadId.get(); }
  public long receivedRejectedDcw() { return receivedRejectedDcw.get(); }
  public long receivedResendReqs() { return receivedResendReqs.get(); }
  public long sentResendReqs() { return sentResendReqs.get(); }
  public long rateLimited() { return rateLimited.get(); }
  public long invalidSize() { return invalidSize.get(); }
  public long unknownType() { return unknownType.get(); }
  public long antiAmplificationRejected() { return antiAmplificationRejected.get(); }
  public long parseErrors() { return parseErrors.get(); }
  public long notAllowlisted() { return notAllowlisted.get(); }
  public long pingSent() { return pingSent.get(); }
  public long pingReceived() { return pingReceived.get(); }
  public long pongReceived() { return pongReceived.get(); }

  /**
   * Retourne tous les compteurs sous forme de map (compatible WebUI).
   */
  public Map<String, String> asMap() {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("sent-pending", String.valueOf(sentPending.get()));
    p.put("sent-entries", String.valueOf(sentEntries.get()));
    p.put("re-sent-entries", String.valueOf(reSentEntries.get()));
    p.put("received-entries", String.valueOf(receivedEntries.get()));
    p.put("received-pending", String.valueOf(receivedPending.get()));
    p.put("received-discarded", String.valueOf(receivedDiscarded.get()));
    p.put("received-bad-checksum", String.valueOf(receivedBadChecksum.get()));
    p.put("received-zero-cw", String.valueOf(receivedZeroCw.get()));
    p.put("received-bad-cw", String.valueOf(receivedBadCw.get()));
    p.put("received-bad-id", String.valueOf(receivedBadId.get()));
    p.put("received-rejected-dcw", String.valueOf(receivedRejectedDcw.get()));
    p.put("received-resend-reqs", String.valueOf(receivedResendReqs.get()));
    p.put("sent-resend-reqs", String.valueOf(sentResendReqs.get()));
    p.put("rate-limited", String.valueOf(rateLimited.get()));
    p.put("invalid-size", String.valueOf(invalidSize.get()));
    p.put("unknown-type", String.valueOf(unknownType.get()));
    p.put("anti-amplification-rejected", String.valueOf(antiAmplificationRejected.get()));
    p.put("parse-errors", String.valueOf(parseErrors.get()));
    p.put("ping-sent", String.valueOf(pingSent.get()));
    p.put("ping-received", String.valueOf(pingReceived.get()));
    p.put("pong-received", String.valueOf(pongReceived.get()));
    return p;
  }

  /**
   * Réinitialise tous les compteurs (pour les tests).
   */
  public void reset() {
    sentPending.set(0);
    sentEntries.set(0);
    reSentEntries.set(0);
    receivedEntries.set(0);
    receivedPending.set(0);
    receivedDiscarded.set(0);
    receivedBadChecksum.set(0);
    receivedZeroCw.set(0);
    receivedBadCw.set(0);
    receivedBadId.set(0);
    receivedRejectedDcw.set(0);
    receivedResendReqs.set(0);
    sentResendReqs.set(0);
    rateLimited.set(0);
    invalidSize.set(0);
    unknownType.set(0);
    antiAmplificationRejected.set(0);
    parseErrors.set(0);
    pingSent.set(0);
    pingReceived.set(0);
    pongReceived.set(0);
  }
}
