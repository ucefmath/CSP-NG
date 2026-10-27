import { useState } from "react";
import { apiJson, describeError, formatDate } from "../api";
import { Empty, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

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

function hex(n: number, pad: number) {
  return "0x" + Number(n).toString(16).toUpperCase().padStart(pad, "0");
}

export function CatalogPage() {
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
        <h1>Catalogue de cartes</h1>
        <Empty msg="Aucun profil disponible." />
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
      push(`Capacité ${state === "published" ? "publiée" : "bloquée"}`);
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
      <h1>Catalogue de cartes</h1>
      <p className="muted small">Capacités de cartes persistantes : publication / blocage, sources par connecteur.</p>
      <div className="field">
        <label htmlFor="cat-prof">Profil</label>
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
              {p.name} (caid: {hex(p.caId ?? 0, 4)})
            </option>
          ))}
        </select>
      </div>
      {caps === null && !loadingCaps ? (
        <button type="button" className="btn btn-primary btn-sm" onClick={() => load(current)}>
          Charger le catalogue
        </button>
      ) : loadingCaps ? (
        <p className="muted">Chargement du catalogue…</p>
      ) : capError ? (
        <LoadError error={capError} onRetry={() => load(current)} />
      ) : !caps?.length ? (
        <Empty msg="Aucune capacité découverte ou déclarée pour ce profil." />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>CAID</th>
                <th>Provider</th>
                <th>Card ID (persistant)</th>
                <th>Statut</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {caps.map((c) => (
                <tr key={c.id}>
                  <td><code className="mono">{hex(c.caid, 4)}</code></td>
                  <td><code className="mono">{hex(c.provider, 6)}</code></td>
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
                        Publier
                      </button>
                    )}
                    {c.state !== "blocked" && (
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setState(c.id, "blocked", c.version)}>
                        Bloquer
                      </button>
                    )}
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggleSources(c.id)} aria-expanded={openSources === c.id}>
                      {openSources === c.id ? "Masquer sources" : "Sources"}
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
          <h2>Sources de la capacité #{openSources}</h2>
          {!(sources[openSources] ?? []).length ? (
            <Empty msg="Aucune source connue." />
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Connecteur</th>
                    <th>Card ID d'origine</th>
                    <th>Confiance</th>
                    <th>Hop</th>
                    <th>Reshare</th>
                    <th>Màj</th>
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
