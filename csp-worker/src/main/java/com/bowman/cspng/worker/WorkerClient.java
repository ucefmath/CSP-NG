package com.bowman.cspng.worker;

import com.bowman.cspng.config.RelayWorkerContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * Client canal controle HTTPS mTLS (aucun acces PG). Identite = SAN du
 * certificat client (jamais de query/header simules : le serveur verifie le
 * SAN, le nodeId query/body doit lui etre egal).
 */
public final class WorkerClient implements AutoCloseable {

  /**
   * SID-map : taille maximale (octets UTF-8) d'un ack sérialisé envoyé au central.
   * Garde-fou côté worker contre un ack pathologiquement volumineux (200 logs +
   * paires CacheEx + stats par SID) qui saturerait la requête HTTP.
   */
  public static final int MAX_ACK_BYTES = 262_144;

  private final Vertx vertx;
  private final WebClient client;
  private final WorkerConfig config;
  private final ObjectMapper mapper = new ObjectMapper();
  /**
   * SID-map : repli v1 temporaire activé après un refus HTTP 400 du central (ancien,
   * n'acceptant pas les champs CacheEx v2). TTL de 10 minutes (600_000 ms) après quoi
   * le worker retente le format v2 (détection de mise à niveau du central sans redémarrage).
   */
  private volatile long legacyAckV1UntilMs = 0L;

  public WorkerClient(Vertx vertx, WorkerConfig config) {
    this.vertx = vertx;
    this.config = config;
    WebClientOptions opts = new WebClientOptions()
        .setSsl(true)
        .setTrustAll(false)
        .setVerifyHost(true)
        .setPemKeyCertOptions(new PemKeyCertOptions()
            .setCertPath(config.certFile()).setKeyPath(config.keyFile()))
        .setPemTrustOptions(new PemTrustOptions().addCertPath(config.trustFile()));
    // baseUrl parsee a l'usage (host/port).
    this.client = WebClient.create(vertx, opts);
  }

  private String[] splitBase() {
    String u = config.centralUrl().replace("https://", "").replace("http://", "");
    int slash = u.indexOf('/');
    if (slash >= 0) u = u.substring(0, slash);
    int colon = u.lastIndexOf(':');
    if (colon < 0) return new String[]{u, "8444"};
    return new String[]{u.substring(0, colon), u.substring(colon + 1)};
  }

  private static String bodyCode(String body) {
    if (body == null) return "HTTP_ERROR";
    int i = body.indexOf("\"code\"");
    if (i < 0) return "HTTP_ERROR";
    int q1 = body.indexOf('"', i + 6);
    if (q1 < 0) return "HTTP_ERROR";
    int q2 = body.indexOf('"', q1 + 1);
    if (q2 < 0) return "HTTP_ERROR";
    return body.substring(q1 + 1, q2);
  }

  public Future<RelayWorkerContract.DesiredWorkerConfig> fetchDesired() {
    Promise<RelayWorkerContract.DesiredWorkerConfig> p = Promise.promise();
    String[] hp = splitBase();
    client.get(Integer.parseInt(hp[1]), hp[0], "/api/worker/desired")
        .addQueryParam("nodeId", config.nodeId())
        .send()
        .onSuccess(resp -> {
          try {
            if (resp.statusCode() != 200) {
              String b = resp.body() != null ? resp.body().toString() : null;
              p.fail(new WorkerHttpException(resp.statusCode(), bodyCode(b),
                  "desired failed status=" + resp.statusCode()));
              return;
            }
            Buffer b = resp.body();
            var d = mapper.readValue(b.toString(), RelayWorkerContract.DesiredWorkerConfig.class);
            // Garde locale : le desired doit designer ce node (pas de confusion).
            if (!config.nodeId().equals(d.nodeId())) {
              p.fail(new WorkerHttpException(200, "NODE_MISMATCH", "desired node mismatch"));
              return;
            }
            p.complete(d);
          } catch (Exception e) {
            p.fail(e);
          }
        })
        .onFailure(p::fail);
    return p.future();
  }

  public Future<Long> ack(long appliedVersion) {
    return ack(appliedVersion, null);
  }

  public Future<Long> ack(long appliedVersion, RelayWorkerContract.WorkerStatsView stats) {
    return ack(appliedVersion, stats, java.util.List.of());
  }

  public Future<Long> ack(long appliedVersion, RelayWorkerContract.WorkerStatsView stats,
                          java.util.List<RelayWorkerContract.WorkerLogEntry> logs) {
    Promise<Long> p = Promise.promise();
    String[] hp = splitBase();
    RelayWorkerContract.WorkerAck ack =
        new RelayWorkerContract.WorkerAck(config.nodeId(), appliedVersion, true, null, stats, logs);
    sendAck(hp, ack, p, false);
    return p.future();
  }

  /**
   * Envoie l'ack (budgete a {@link #MAX_ACK_BYTES}). Si le central refuse avec HTTP 400 ET
   * qu'aucun repli n'a encore ete tente, bascule en mode v1 (sans champs CacheEx v2) et
   * effectue EXACTEMENT un seul retry immediat — jamais de boucle sur refus repetes.
   */
  private void sendAck(String[] hp, RelayWorkerContract.WorkerAck ack, Promise<Long> p, boolean retry) {
    boolean isLegacy = System.currentTimeMillis() < legacyAckV1UntilMs;
    RelayWorkerContract.WorkerAck effective = isLegacy ? stripCacheExV2Fields(ack) : ack;
    Buffer body;
    try {
      body = Buffer.buffer(serializeAckWithBudget(mapper, effective, MAX_ACK_BYTES));
    } catch (Exception e) {
      p.fail(e);
      return;
    }
    client.post(Integer.parseInt(hp[1]), hp[0], "/api/worker/ack")
        .putHeader("Content-Type", "application/json")
        .sendBuffer(body)
        .onSuccess(resp -> {
          try {
            if (resp.statusCode() == 400 && !isLegacy && !retry) {
              legacyAckV1UntilMs = System.currentTimeMillis() + 600_000L;
              sendAck(hp, ack, p, true);
              return;
            }
            if (resp.statusCode() != 200) {
              String b = resp.body() != null ? resp.body().toString() : null;
              p.fail(new WorkerHttpException(resp.statusCode(), bodyCode(b),
                  "ack failed status=" + resp.statusCode()));
              return;
            }
            var r = mapper.readValue(resp.body().toString(),
                RelayWorkerContract.WorkerAckResult.class);
            p.complete(r.appliedVersion());
          } catch (Exception e) {
            p.fail(e);
          }
        })
        .onFailure(p::fail);
  }

  /**
   * SID-map : retire les champs additifs v2 (remoteIp/remoteNodeId/hits/sids) de chaque
   * paire CacheEx d'un ack, pour repli vers un central ancien (forme JSON v1.0.0 stricte).
   */
  public static RelayWorkerContract.WorkerAck stripCacheExV2Fields(RelayWorkerContract.WorkerAck ack) {
    if (ack == null || ack.stats() == null) return ack;
    var pairs = ack.stats().cacheex();
    if (pairs == null || pairs.isEmpty()) return ack;
    java.util.List<RelayWorkerContract.CacheExPairStatsView> stripped = new java.util.ArrayList<>();
    for (var pair : pairs) {
      stripped.add(new RelayWorkerContract.CacheExPairStatsView(
          pair.peerId(), pair.direction(), pair.protocol(), pair.mode(), pair.profile(),
          pair.online(), pair.state(), pair.maxHop(), pair.maxSendQueue(), pair.filterActive(),
          pair.pushedCw(), pair.receivedCw(), pair.droppedCw(), pair.dropReasons(),
          pair.lastPushAtMs(), pair.lastReceiveAtMs()));
    }
    var s = ack.stats();
    RelayWorkerContract.WorkerStatsView strippedStats = new RelayWorkerContract.WorkerStatsView(
        s.connectors(), s.udp(), s.caches(), s.nodes(), stripped, s.cwCycle(),
        s.peerQuality(), s.cacheHits(), s.quarantine(), s.quorum(), s.collectedAtMs());
    return new RelayWorkerContract.WorkerAck(
        ack.nodeId(), ack.appliedVersion(), ack.ok(), ack.error(), strippedStats, ack.logs());
  }

  /**
   * SID-map : sérialise l'ack en respectant une borne {@code maxBytes} (UTF-8), par paliers
   * de dégradation (les moins coûteux côté supervision en premier) : 1) tel quel ; 2) sans
   * champs CacheEx v2 ; 3) sans logs ; 4) sans paires CacheEx ; 5) sans stats du tout — ce
   * dernier palier tient toujours dans le budget pour un nodeId/erreur raisonnables.
   */
  public static String serializeAckWithBudget(ObjectMapper mapper, RelayWorkerContract.WorkerAck ack, int maxBytes)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    String json = mapper.writeValueAsString(ack);
    if (utf8Bytes(json) <= maxBytes) return json;

    RelayWorkerContract.WorkerAck noV2 = stripCacheExV2Fields(ack);
    json = mapper.writeValueAsString(noV2);
    if (utf8Bytes(json) <= maxBytes) return json;

    var logs = noV2.logs();
    int keep = logs.size();
    while (keep > 0) {
      keep = keep / 2;
      RelayWorkerContract.WorkerAck withFewerLogs = new RelayWorkerContract.WorkerAck(
          noV2.nodeId(), noV2.appliedVersion(), noV2.ok(), noV2.error(), noV2.stats(),
          logs.subList(0, keep));
      json = mapper.writeValueAsString(withFewerLogs);
      if (utf8Bytes(json) <= maxBytes) return json;
    }

    RelayWorkerContract.WorkerAck noLogs = new RelayWorkerContract.WorkerAck(
        noV2.nodeId(), noV2.appliedVersion(), noV2.ok(), noV2.error(), noV2.stats(), java.util.List.of());
    json = mapper.writeValueAsString(noLogs);
    if (utf8Bytes(json) <= maxBytes) return json;

    RelayWorkerContract.WorkerStatsView statsNoCacheex = noV2.stats() == null ? null
        : new RelayWorkerContract.WorkerStatsView(
            noV2.stats().connectors(), noV2.stats().udp(), noV2.stats().caches(), noV2.stats().nodes(),
            java.util.List.of(), noV2.stats().cwCycle(), noV2.stats().peerQuality(),
            noV2.stats().cacheHits(), noV2.stats().quarantine(), noV2.stats().quorum(),
            noV2.stats().collectedAtMs());
    RelayWorkerContract.WorkerAck noCacheex = new RelayWorkerContract.WorkerAck(
        noV2.nodeId(), noV2.appliedVersion(), noV2.ok(), noV2.error(), statsNoCacheex, java.util.List.of());
    json = mapper.writeValueAsString(noCacheex);
    if (utf8Bytes(json) <= maxBytes) return json;

    RelayWorkerContract.WorkerAck minimal = new RelayWorkerContract.WorkerAck(
        ack.nodeId(), ack.appliedVersion(), ack.ok(), ack.error(), null, java.util.List.of());
    return mapper.writeValueAsString(minimal);
  }

  private static int utf8Bytes(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }

  @Override
  public void close() {
    try {
      client.close();
    } catch (Exception ignored) {
    }
  }
}
