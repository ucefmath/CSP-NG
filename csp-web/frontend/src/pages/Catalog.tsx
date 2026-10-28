import { useState } from "react";
import { apiJson, describeError, formatDate } from "../api";
import { Empty, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { formatHexId } from "../hex";
import { useT } from "../i18n";

interface Capability {
  id: number;
  caid: number;
  provider: number;
  cardId: string | number;
  state: string;
  version: number;
}

interface CapSource {
  id?: number;
  capabilityId?: number;
  connectorName?: string;
  originCardId?: number | null;
  trust?: string;
  hop?: number;
  reshare?: number;
  updatedAt?: string | null;
}

export function CatalogPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [profile, setProfile] = useState<string | null>(null);
  const [caps, setCaps] = useState<Capability[] | null>(null);
  const [capError, setCapError] = useState<unknown>(null);
  const [loadingCaps, setLoadingCaps] = useState(false);
  const [sources, setSources] = useState<Record<number, CapSource[]>>({});
  const [openSources, setOpenSources] = useState<number | null>(null);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  const profiles = snap.data?.profiles ?? [];
  if (!profiles.length) {
    return (
      <div className="card">
        <h1>{t("catalog.title")}</h1>
        <Empty msg={t("catalog.noProfiles")} />
      </div>
    );
  }
  const current = profile ?? profiles[0].name;

  async function load(p: string) {
    setLoadingCaps(true);
    setCapError(null);
    try {
      const { body } = await apiJson<Capability[]>(`/api/admin/catalog/capabilities/${encodeURIComponent(p)}`);
      setCaps(Array.isArray(body) ? body : []);
      setSources({});
      setOpenSources(null);
    } catch (e) {
      setCapError(e);
      setCaps([]);
    } finally {
      setLoadingCaps(false);
    }
  }

  async function setState(id: number, state: string, version: number) {
    try {
      await apiJson(`/api/admin/catalog/capabilities/${id}/state`, { method: "PUT", body: { state, version } });
      push(state === "published" ? t("catalog.toastPublished") : t("catalog.toastBlocked"));
      load(current);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  async function toggleSources(id: number) {
    if (openSources === id) {
      setOpenSources(null);
      return;
    }
    setOpenSources(id);
    if (sources[id]) return;
    try {
      const { body } = await apiJson<CapSource[]>(`/api/admin/catalog/capabilities/${id}/sources`);
      setSources((s) => ({ ...s, [id]: Array.isArray(body) ? body : [] }));
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <h1>{t("catalog.title")}</h1>
      <p className="muted small">{t("catalog.subtitle")}</p>
      <div className="field">
        <label htmlFor="cat-prof">{t("catalog.profileLabel")}</label>
        <select
          id="cat-prof"
          value={current}
          onChange={(e) => {
            setProfile(e.target.value);
            setCaps(null);
            load(e.target.value);
          }}
        >
          {profiles.map((p) => (
            <option key={p.name} value={p.name}>
              {p.name} (caid: {formatHexId(p.caId ?? 0, "caid")})
            </option>
          ))}
        </select>
      </div>
      {caps === null && !loadingCaps ? (
        <button type="button" className="btn btn-primary btn-sm" onClick={() => load(current)}>
          {t("catalog.loadCatalog")}
        </button>
      ) : loadingCaps ? (
        <p className="muted">{t("catalog.loadingCatalog")}</p>
      ) : capError ? (
        <LoadError error={capError} onRetry={() => load(current)} />
      ) : !caps?.length ? (
        <Empty msg={t("catalog.emptyCatalog")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("catalog.thCaid")}</th>
                <th>{t("catalog.thProvider")}</th>
                <th>{t("catalog.thCardId")}</th>
                <th>{t("catalog.thStatus")}</th>
                <th>{t("catalog.thVersion")}</th>
                <th>{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {caps.map((c) => (
                <tr key={c.id}>
                  <td><code className="mono">{formatHexId(c.caid, "caid")}</code></td>
                  <td><code className="mono">{formatHexId(c.provider, "provid")}</code></td>
                  <td><code className="mono">{String(c.cardId)}</code></td>
                  <td>
                    <span className={c.state === "published" ? "badge badge-ok" : c.state === "blocked" ? "badge badge-danger" : "badge badge-muted"}>
                      {c.state}
                    </span>
                  </td>
                  <td className="mono">v{c.version}</td>
                  <td>
                    {c.state !== "published" && (
                      <button type="button" className="btn btn-primary btn-sm" onClick={() => setState(c.id, "published", c.version)}>
                        {t("catalog.publish")}
                      </button>
                    )}
                    {c.state !== "blocked" && (
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setState(c.id, "blocked", c.version)}>
                        {t("catalog.block")}
                      </button>
                    )}
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleSources(c.id)} aria-expanded={openSources === c.id}>
                      {openSources === c.id ? t("catalog.hideSources") : t("catalog.showSources")}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {openSources !== null && (
        <div className="card" aria-live="polite">
          <h2>{t("catalog.sourcesHeading", { id: openSources })}</h2>
          {!(sources[openSources] ?? []).length ? (
            <Empty msg={t("catalog.noSources")} />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t("catalog.thConnector")}</th>
                    <th>{t("catalog.thOriginCardId")}</th>
                    <th>{t("catalog.thTrust")}</th>
                    <th>{t("catalog.thHop")}</th>
                    <th>{t("catalog.thReshare")}</th>
                    <th>{t("catalog.thUpdated")}</th>
                  </tr>
                </thead>
                <tbody>
                  {(sources[openSources] ?? []).map((s) => (
                    <tr key={s.id ?? `${s.connectorName}-${s.hop}`}>
                      <td className="mono">{s.connectorName ?? "—"}</td>
                      <td className="mono">{s.originCardId ?? "—"}</td>
                      <td>{s.trust ?? "—"}</td>
                      <td className="mono">{s.hop ?? "—"}</td>
                      <td className="mono">{s.reshare ?? "—"}</td>
                      <td className="small">{s.updatedAt ? formatDate(new Date(s.updatedAt).getTime()) : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
