import { Link } from "react-router";
import { Empty, Loading, LoadError, fetchSnapshot, fetchUsers, useSnapshot } from "../components/ui";
import { formatDate } from "../api";

export function OverviewPage() {
  const snap = useSnapshot(fetchSnapshot);
  const users = useSnapshot(fetchUsers);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  const s = snap.data;
  if (!s) return <Empty msg="Aucune donnée" />;

  const cards = [
    { label: "Profils", value: String(s.profiles?.length ?? "—"), to: "../profiles" },
    { label: "Connecteurs", value: String(s.connectors?.length ?? "—"), to: "../connectors" },
    { label: "Listeners", value: String(s.profileListeners?.length ?? "—"), to: "../listeners" },
    { label: "Utilisateurs", value: users.data ? String(users.data.length) : "—", to: "../users" },
    { label: "Proxys", value: String(s.proxies?.length ?? "—"), to: "../proxys" },
    { label: "Snapshot", value: formatDate(s.loadedAtMs), to: "../cache-etat" },
  ];

  return (
    <div>
      <h1>Vue d'ensemble</h1>
      <div className="kpi-grid">
        {cards.map((c) => (
          <div className="card kpi" key={c.label}>
            <div className="kpi-label">{c.label}</div>
            <div className="kpi-value mono">{c.value}</div>
            <Link to={c.to} className="btn btn-ghost btn-sm">
              Ouvrir
            </Link>
          </div>
        ))}
      </div>
      <div className="card">
        <h2>État</h2>
        <p className="muted small">
          API : <span className="badge badge-ok">opérationnelle</span> — racine proxy :{" "}
          <code className="mono">{s.root?.proxyName ?? "—"}</code> (v{s.root?.version ?? "—"})
        </p>
        <div className="toolbar">
          <Link to="../cacheex" className="btn btn-ghost btn-sm">
            Stats CacheEx
          </Link>
          <Link to="../dcw" className="btn btn-ghost btn-sm">
            Qualité DCW
          </Link>
          <Link to="../relais" className="btn btn-ghost btn-sm">
            Relais
          </Link>
        </div>
      </div>
    </div>
  );
}
