import { apiJson, formatDate, freshnessLabel } from "../api";
import type { CacheStatus } from "../types";
import { Empty, LoadError, Loading, useSnapshot } from "../components/ui";

// Écran états de chaque cache : fraîcheur explicite, jamais de faux zéros.
export function CacheEtatPage() {
  const st = useSnapshot(async () => {
    const { body } = await apiJson<CacheStatus>("/api/admin/cache/status");
    return body;
  });

  if (st.loading) return <Loading label="Chargement de l'état des caches…" />;
  if (st.error) {
    const s = (st.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>État des caches</h1>
          <p className="muted">Endpoint indisponible sur ce backend (404) — fonctionnalité livrée par l'agent backend.</p>
          <button type="button" className="btn btn-sm" onClick={st.reload}>
            Réessayer
          </button>
        </div>
      );
    return <LoadError error={st.error} onRetry={st.reload} />;
  }
  const d = st.data;
  if (!d) return <Empty msg="Aucune donnée" />;

  return (
    <div>
      <div className="card">
        <div className="toolbar">
          <h1>État des caches</h1>
          <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
            Actualiser
          </button>
        </div>
        <p className="muted small">
          Généré : {formatDate(d.generatedAtMs)} — prêt : {d.ready ? "oui" : "non"}. Absent ou périmé = inconnu
          (jamais de zéros fabriqués).
        </p>
      </div>

      <div className="card">
        <h2>Cache local</h2>
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
          <Empty msg="Local indisponible (verticle sans réponse) — inconnu, pas zéro." />
        )}
      </div>

      <div className="card">
        <h2>Caches ({d.caches?.length ?? 0})</h2>
        {!d.caches?.length ? (
          <Empty msg="Aucun cache configuré" />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Type</th>
                  <th>État config</th>
                  <th>Node</th>
                  <th>Stats</th>
                  <th>Fraîcheur</th>
                  <th>Version</th>
                </tr>
              </thead>
              <tbody>
                {d.caches.map((c) => (
                  <tr key={c.name}>
                    <td className="mono">{c.name}</td>
                    <td>{c.kind ?? "—"}</td>
                    <td>
                      <span className={c.state === "enabled" ? "badge badge-ok" : "badge badge-muted"}>
                        {c.state === "enabled" ? "activé" : "désactivé"}
                      </span>
                    </td>
                    <td className="mono small">{c.nodeName ?? "—"}</td>
                    <td className="mono small">
                      {c.stats ? `hits ${c.stats.consultHits ?? "—"} / puts ${c.stats.feedPuts ?? "—"}` : "inconnu"}
                    </td>
                    <td className="small">{freshnessLabel(c.statsAgeMs, c.statsFresh)}</td>
                    <td className="mono">v{c.version ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="muted small">L'état reflète la configuration uniquement, jamais déduit des stats.</p>
      </div>

      <div className="card">
        <h2>Nodes ({d.nodes?.length ?? 0})</h2>
        {!d.nodes?.length ? (
          <Empty msg="Aucun node avec stats reçues — nodes muets : inconnus (aucune entrée fabriquée)." />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Node</th>
                  <th>Utilisable</th>
                  <th>Consult OK</th>
                  <th>RTT moyen</th>
                  <th>Fraîcheur</th>
                </tr>
              </thead>
              <tbody>
                {d.nodes.map((n) => (
                  <tr key={n.nodeName}>
                    <td className="mono">{n.nodeName}</td>
                    <td>{n.usable === null || n.usable === undefined ? "inconnu" : n.usable ? "oui" : "non"}</td>
                    <td className="mono">{n.consultOk ?? "inconnu"}</td>
                    <td className="mono">{n.avgRttMs ?? "inconnu"}</td>
                    <td className="small">{freshnessLabel(n.statsAgeMs, n.statsFresh)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h2>UDP</h2>
        {!d.udp ? (
          <Empty msg="UDP : inconnu" />
        ) : (
          <>
            <p className="small">
              Activé : {d.udp.enabled ? "oui" : "non"} — port : <code className="mono">{d.udp.bindPort ?? "—"}</code> —
              fraîcheur : {freshnessLabel(d.udp.statsAgeMs, d.udp.statsFresh)}
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
              <Empty msg="Stats UDP absentes ou périmées — inconnu." />
            )}
          </>
        )}
      </div>
    </div>
  );
}
