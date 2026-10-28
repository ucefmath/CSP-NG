import { Link } from "react-router";
import { Empty, Loading, LoadError, fetchSnapshot, fetchUsers, useSnapshot } from "../components/ui";
import { formatDate } from "../api";
import { useT } from "../i18n";

export function OverviewPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const users = useSnapshot(fetchUsers);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  const s = snap.data;
  if (!s) return <Empty msg={t("common.noData")} />;

  const cards = [
    { label: t("overview.profiles"), value: String(s.profiles?.length ?? "—"), to: "../profiles" },
    { label: t("overview.connectors"), value: String(s.connectors?.length ?? "—"), to: "../connectors" },
    { label: t("overview.listeners"), value: String(s.profileListeners?.length ?? "—"), to: "../listeners" },
    { label: t("overview.users"), value: users.data ? String(users.data.length) : "—", to: "../users" },
    { label: t("overview.proxies"), value: String(s.proxies?.length ?? "—"), to: "../proxys" },
    { label: t("overview.snapshot"), value: formatDate(s.loadedAtMs), to: "../cache-etat" },
  ];

  return (
    <div>
      <h1>{t("overview.title")}</h1>
      <div className="kpi-grid">
        {cards.map((c) => (
          <div className="card kpi" key={c.label}>
            <div className="kpi-label">{c.label}</div>
            <div className="kpi-value mono">{c.value}</div>
            <Link to={c.to} className="btn btn-ghost btn-sm">
              {t("overview.open")}
            </Link>
          </div>
        ))}
      </div>
      <div className="card">
        <h2>{t("overview.status")}</h2>
        <p className="muted small">
          {t("overview.api")} <span className="badge badge-ok">{t("overview.operational")}</span> — {t("overview.proxyRoot")}{" "}
          <code className="mono">{s.root?.proxyName ?? "—"}</code> (v{s.root?.version ?? "—"})
        </p>
        <div className="toolbar">
          <Link to="../cacheex" className="btn btn-ghost btn-sm">
            {t("overview.cacheExStats")}
          </Link>
          <Link to="../dcw" className="btn btn-ghost btn-sm">
            {t("overview.dcwQuality")}
          </Link>
          <Link to="../relais" className="btn btn-ghost btn-sm">
            {t("overview.relays")}
          </Link>
        </div>
      </div>
    </div>
  );
}
