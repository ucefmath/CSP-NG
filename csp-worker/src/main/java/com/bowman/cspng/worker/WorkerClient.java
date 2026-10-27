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

  private final Vertx vertx;
  private final WebClient client;
  private final WorkerConfig config;
  private final ObjectMapper mapper = new ObjectMapper();

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
    Buffer body;
    try {
      body = Buffer.buffer(mapper.writeValueAsString(ack));
    } catch (Exception e) {
      return Future.failedFuture(e);
    }
    client.post(Integer.parseInt(hp[1]), hp[0], "/api/worker/ack")
        .putHeader("Content-Type", "application/json")
        .sendBuffer(body)
        .onSuccess(resp -> {
          try {
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
    return p.future();
  }

  @Override
  public void close() {
    try {
      client.close();
    } catch (Exception ignored) {
    }
  }
}
