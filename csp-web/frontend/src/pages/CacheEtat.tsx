import { apiJson, formatDate, freshnessLabel } from "../api";
import type { CacheStatus } from "../types";
import { Empty, LoadError, Loading, useSnapshot } from "../components/ui";
import { useT } from "../i18n";

// Écran états de chaque cache : fraîcheur explicite, jamais de faux zéros.
export function CacheEtatPage() {
  const t = useT();
  const st = useSnapshot(async () => {
    const { body } = await apiJson<CacheStatus>("/api/admin/cache/status");
    return body;
  });

  if (st.loading) return <Loading label={t("cacheEtat.loading")} />;
  if (st.error) {
    const s = (st.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>{t("cacheEtat.title")}</h1>
          <p className="muted">{t("cacheEtat.endpointUnavailable")}</p>
          <button type="button" className="btn btn-sm" onClick={st.reload}>
            {t("common.retry")}
          </button>
        </div>
      );
    return <LoadError error={st.error} onRetry={st.reload} />;
  }
  const d = st.data;
  if (!d) return <Empty msg={t("common.noData")} />;

  return (
    <div>
      <div className="card">
        <div className="toolbar">
          <h1>{t("cacheEtat.title")}</h1>
          <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
            {t("cacheEtat.refresh")}
          </button>
        </div>
        <p className="muted small">
          {t("cacheEtat.summary", {
            generated: formatDate(d.generatedAtMs),
            ready: d.ready ? t("common.yes") : t("common.no"),
          })}
        </p>
      </div>

      <div className="card">
        <h2>{t("cacheEtat.localHeading")}</h2>
        {d.local ? (
          <dl className="kpi-grid">
            {Object.entries(d.local).map(([k, v]) => (
              <div key={k}>
                <dt className="kpi-label">{k}</dt>
                <dd className="kpi-value mono small">{String(v)}</dd>
              </div>
            ))}
          </dl>
        ) : (
          <Empty msg={t("cacheEtat.localUnavailable")} />
        )}
      </div>

      <div className="card">
        <h2>{t("cacheEtat.cachesHeading", { count: d.caches?.length ?? 0 })}</h2>
        {!d.caches?.length ? (
          <Empty msg={t("cacheEtat.noCaches")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("cacheEtat.thName")}</th>
                  <th>{t("cacheEtat.thType")}</th>
                  <th>{t("cacheEtat.thConfigState")}</th>
                  <th>{t("cacheEtat.thNode")}</th>
                  <th>{t("cacheEtat.thStats")}</th>
                  <th>{t("cacheEtat.thFreshness")}</th>
                  <th>{t("cacheEtat.thVersion")}</th>
                </tr>
              </thead>
              <tbody>
                {d.caches.map((c) => (
                  <tr key={c.name}>
                    <td className="mono">{c.name}</td>
                    <td>{c.kind ?? "—"}</td>
                    <td>
                      <span className={c.state === "enabled" ? "badge badge-ok" : "badge badge-muted"}>
                        {c.state === "enabled" ? t("common.enabled") : t("common.disabled")}
                      </span>
                    </td>
                    <td className="mono small">{c.nodeName ?? "—"}</td>
                    <td className="mono small">
                      {c.stats
                        ? t("cacheEtat.statsHitsPuts", {
                            hits: c.stats.consultHits ?? "—",
                            puts: c.stats.feedPuts ?? "—",
                          })
                        : t("cacheEtat.unknown")}
                    </td>
                    <td className="small">{freshnessLabel(c.statsAgeMs, c.statsFresh)}</td>
                    <td className="mono">v{c.version ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="muted small">{t("cacheEtat.stateNote")}</p>
      </div>

      <div className="card">
        <h2>{t("cacheEtat.nodesHeading", { count: d.nodes?.length ?? 0 })}</h2>
        {!d.nodes?.length ? (
          <Empty msg={t("cacheEtat.noNodes")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("cacheEtat.thNode")}</th>
                  <th>{t("cacheEtat.thUsable")}</th>
                  <th>{t("cacheEtat.thConsultOk")}</th>
                  <th>{t("cacheEtat.thAvgRtt")}</th>
                  <th>{t("cacheEtat.thFreshness")}</th>
                </tr>
              </thead>
              <tbody>
                {d.nodes.map((n) => (
                  <tr key={n.nodeName}>
                    <td className="mono">{n.nodeName}</td>
                    <td>
                      {n.usable === null || n.usable === undefined
                        ? t("cacheEtat.unknown")
                        : n.usable
                          ? t("common.yes")
                          : t("common.no")}
                    </td>
                    <td className="mono">{n.consultOk ?? t("cacheEtat.unknown")}</td>
                    <td className="mono">{n.avgRttMs ?? t("cacheEtat.unknown")}</td>
                    <td className="small">{freshnessLabel(n.statsAgeMs, n.statsFresh)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h2>{t("cacheEtat.udpHeading")}</h2>
        {!d.udp ? (
          <Empty msg={t("cacheEtat.udpUnknown")} />
        ) : (
          <>
            <p className="small">
              {t("cacheEtat.udpEnabled")} : {d.udp.enabled ? t("common.yes") : t("common.no")} —{" "}
              {t("cacheEtat.udpPort")} : <code className="mono">{d.udp.bindPort ?? "—"}</code> —{" "}
              {t("cacheEtat.udpFreshness")} : {freshnessLabel(d.udp.statsAgeMs, d.udp.statsFresh)}
            </p>
            {d.udp.stats ? (
              <dl className="kpi-grid">
                {Object.entries(d.udp.stats).map(([k, v]) => (
                  <div key={k}>
                    <dt className="kpi-label">{k}</dt>
                    <dd className="kpi-value mono small">{String(v)}</dd>
                  </div>
                ))}
              </dl>
            ) : (
              <Empty msg={t("cacheEtat.udpStatsMissing")} />
            )}
          </>
        )}
      </div>
    </div>
  );
}
