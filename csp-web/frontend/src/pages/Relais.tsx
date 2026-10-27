import { useEffect, useState } from "react";
import { apiFetch, apiJson, describeError } from "../api";
import { ConfirmDialog, Dialog, Empty, Field, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

type Rec = Record<string, unknown>;
const str = (v: unknown) => (v === null || v === undefined ? "" : String(v));
const num = (v: unknown) => (typeof v === "number" ? v : null);

async function get<T>(path: string): Promise<T> {
  try {
    const { body } = await apiJson<T>(path);
    return body;
  } catch (e) {
    if ((e as { status?: number }).status === 503) return [] as unknown as T;
    throw e;
  }
}

export function RelaisPage() {
  const { push } = useToast();
  const [nodes, setNodes] = useState<Rec[]>([]);
  const [caches, setCaches] = useState<Rec[]>([]);
  const [links, setLinks] = useState<Rec[]>([]);
  const [status, setStatus] = useState<Rec | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [nodeDlg, setNodeDlg] = useState<Rec | null | "new">(null);
  const [cacheDlg, setCacheDlg] = useState<Rec | null | "new">(null);
  const [linkDlg, setLinkDlg] = useState<Rec | null | "new">(null);
  const [deleting, setDeleting] = useState<{ kind: string; label: string; run: () => Promise<void> } | null>(null);
  const [peersCache, setPeersCache] = useState<string | null>(null);
  const [peers, setPeers] = useState<Rec[]>([]);
  const [assign, setAssign] = useState<{ node: string; kind: "listeners" | "connectors" } | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [n, c, l] = await Promise.all([
        get<Rec[]>("/api/admin/config/relay/nodes"),
        get<Rec[]>("/api/admin/config/relay/caches"),
        get<Rec[]>("/api/admin/config/relay/links"),
      ]);
      setNodes(Array.isArray(n) ? n : []);
      setCaches(Array.isArray(c) ? c : []);
      setLinks(Array.isArray(l) ? l : []);
      try {
        const { body } = await apiJson<Rec>("/api/admin/relay/status");
        setStatus(body);
      } catch {
        setStatus(null);
      }
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function loadPeers(cacheName: string) {
    setPeersCache(cacheName);
    try {
      const { body } = await apiJson<Rec[]>(`/api/admin/config/relay/caches/${encodeURIComponent(cacheName)}/peers`);
      setPeers(Array.isArray(body) ? body : []);
    } catch (e) {
      push(describeError(e), "err");
      setPeers([]);
    }
  }

  if (loading) return <Loading label="Chargement relais…" />;
  if (error) {
    return (
      <div className="card" role="alert">
        <h1>Relais</h1>
        <p className="field-error">Erreur relais : {describeError(error)}</p>
        <button type="button" className="btn btn-sm" onClick={load}>
          Réessayer
        </button>
      </div>
    );
  }

  const statusByNode: Record<string, Rec> = {};
  const statusNodes = status?.nodes as Rec[] | undefined;
  if (Array.isArray(statusNodes)) for (const n of statusNodes) statusByNode[str(n.nodeName)] = n;

  return (
    <div>
      <h1>Relais — nodes, caches, liens, pairs UDP</h1>
      <p className="muted small">
        Nodes relais sans accès PG (canal HTTPS mTLS). Caches distants débranchés par défaut. Kind local | node
        (CSPv2 authentifié) | udp (échange allowlisté, strict). TTL config worker :{" "}
        {str((status?.workerConfigTtlMs as number | undefined) ?? "—")} ms.
      </p>

      <div className="card">
        <div className="toolbar">
          <h2>Nodes relais (identité mTLS SAN)</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setNodeDlg("new")}>
            Nouveau Node
          </button>
        </div>
        {!nodes.length ? (
          <Empty msg="Aucun node — cliquez sur « Nouveau Node » pour autoriser un worker." />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Node</th>
                  <th>Display</th>
                  <th>Statut</th>
                  <th>Endpoint CSPv2</th>
                  <th>Desired / Applied</th>
                  <th>Version</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {nodes.map((n) => {
                  const name = str(n.nodeName);
                  const st = statusByNode[name];
                  const revoked = Boolean(n.revoked);
                  const enabled = Boolean(n.enabled);
                  const authorized = Boolean(n.authorized);
                  return (
                    <tr key={name}>
                      <td className="mono bold">{name}</td>
                      <td>{str(n.displayName) || "—"}</td>
                      <td>
                        <span className={revoked ? "badge badge-danger" : enabled && authorized ? "badge badge-ok" : "badge badge-warn"}>
                          {revoked ? "révoqué" : enabled ? (authorized ? "actif" : "non-autorisé") : "désactivé"}
                        </span>
                      </td>
                      <td className="mono small">{n.cspHost && n.cspPort ? `${n.cspHost}:${n.cspPort}` : "accept-only"}</td>
                      <td className="mono small">
                        des={str(n.desiredVersion)} / app={st && st.appliedVersion != null ? `v${st.appliedVersion}` : "jamais"}
                        {st ? `${st.expired ? " [expiré]" : ""}${st.stale ? " [stale]" : ""}` : ""}
                      </td>
                      <td className="mono small">v{str(n.version)}</td>
                      <td>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setNodeDlg(n)}>Éditer</button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAssign({ node: name, kind: "listeners" })}>
                          Listeners
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAssign({ node: name, kind: "connectors" })}>
                          Connecteurs
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <div className="toolbar">
          <h2>Caches relais (local | node CSPv2 | udp)</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setCacheDlg("new")}>
            Nouveau Cache
          </button>
        </div>
        {!caches.length ? (
          <Empty msg="Aucun cache relais." />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Type</th>
                  <th>État</th>
                  <th>Node</th>
                  <th>Version</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {caches.map((c) => (
                  <tr key={str(c.name)}>
                    <td className="mono">{str(c.name)}</td>
                    <td>{str(c.kind)}</td>
                    <td>
                      <span className={c.enabled ? "badge badge-ok" : "badge badge-muted"}>
                        {c.enabled ? "activé" : "désactivé"}
                      </span>
                    </td>
                    <td className="mono small">{str(c.nodeName) || "—"}</td>
                    <td className="mono small">v{str(c.version)}</td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setCacheDlg(c)}>Éditer</button>
                      {str(c.kind) === "udp" && (
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => loadPeers(str(c.name))}>
                          Pairs
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {peersCache && (
          <div className="section" aria-live="polite">
            <h3>Pairs UDP de « {peersCache} »</h3>
            {!peers.length ? (
              <Empty msg="Aucun pair." />
            ) : (
              <ul className="small">
                {peers.map((p) => (
                  <li key={str(p.id ?? `${p.host}:${p.port}`)}>
                    <code className="mono">{str(p.host)}:{str(p.port)}</code> — {p.enabled ? "actif" : "inactif"}{" "}
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => removePeer(p, peersCache, load, push)}>
                      Retirer
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <PeerForm cacheName={peersCache} onSaved={() => loadPeers(peersCache)} />
          </div>
        )}
      </div>

      <div className="card">
        <div className="toolbar">
          <h2>Liens listener → cache</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setLinkDlg("new")}>
            Nouveau lien
          </button>
        </div>
        {!links.length ? (
          <Empty msg="Aucun lien." />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Listener</th>
                  <th>Cache</th>
                  <th>Consultation</th>
                  <th>Alimentation</th>
                  <th>Export</th>
                  <th>Actif</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {links.map((l) => (
                  <tr key={`${str(l.listenerId)}/${str(l.cacheName)}`}>
                    <td className="mono">#{str(l.listenerId)}</td>
                    <td className="mono">{str(l.cacheName)}</td>
                    <td>{l.canConsult ? "oui" : "non"}</td>
                    <td>{l.canFeed ? "oui" : "non"}</td>
                    <td>{l.canExport ? "oui" : "non"}</td>
                    <td>
                      <span className={l.enabled ? "badge badge-ok" : "badge badge-muted"}>
                        {l.enabled ? "actif" : "inactif"}
                      </span>
                    </td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLinkDlg(l)}>Éditer</button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() =>
                          setDeleting({
                            kind: "lien",
                            label: `lien listener #${str(l.listenerId)} → ${str(l.cacheName)}`,
                            run: async () => {
                              const res = await apiFetch(
                                `/api/admin/config/relay/links/${str(l.listenerId)}/${encodeURIComponent(str(l.cacheName))}?version=${str((l as Rec).version ?? 1)}`,
                                { method: "DELETE" },
                              );
                              if (!res.ok) throw new Error(res.statusText);
                              push("Lien supprimé");
                              load();
                            },
                          })
                        }
                      >
                        Supprimer
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {nodeDlg && (
        <NodeDialog
          existing={nodeDlg === "new" ? null : nodeDlg}
          onClose={() => setNodeDlg(null)}
          onSaved={() => {
            setNodeDlg(null);
            load();
          }}
        />
      )}
      {cacheDlg && (
        <CacheDialog
          existing={cacheDlg === "new" ? null : cacheDlg}
          nodes={nodes.map((n) => str(n.nodeName))}
          onClose={() => setCacheDlg(null)}
          onSaved={() => {
            setCacheDlg(null);
            load();
          }}
        />
      )}
      {linkDlg && (
        <LinkDialog
          existing={linkDlg === "new" ? null : linkDlg}
          caches={caches.map((c) => str(c.name))}
          onClose={() => setLinkDlg(null)}
          onSaved={() => {
            setLinkDlg(null);
            load();
          }}
        />
      )}
      {assign && (
        <AssignDialog
          node={assign.node}
          kind={assign.kind}
          onClose={() => setAssign(null)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title="Supprimer"
          desc={`${deleting.label} — irréversible.`}
          onClose={() => setDeleting(null)}
          onConfirm={() => {
            deleting.run().catch((e) => push(describeError(e), "err"));
          }}
        />
      )}
      {void num}
    </div>
  );
}

async function removePeer(p: Rec, cacheName: string, reload: () => void, push: (m: string, k?: "ok" | "err") => void) {
  try {
    await apiFetch(`/api/admin/config/relay/peers/${str(p.id)}`, { method: "DELETE" });
    push("Pair retiré");
    reload();
  } catch (e) {
    push(describeError(e), "err");
  }
  void cacheName;
}

function PeerForm({ cacheName, onSaved }: { cacheName: string; onSaved: () => void }) {
  const { push } = useToast();
  const [host, setHost] = useState("");
  const [port, setPort] = useState("");
  const [err, setErr] = useState("");
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const p = Number(port);
    if (!host.trim()) return setErr("Hôte requis");
    if (!Number.isInteger(p) || p < 1 || p > 65535) return setErr("Port 1-65535");
    try {
      await apiJson(`/api/admin/config/relay/caches/${encodeURIComponent(cacheName)}/peers`, {
        method: "PUT",
        body: { host: host.trim(), port: p, enabled: true },
      });
      push("Pair ajouté");
      setHost("");
      setPort("");
      onSaved();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    }
  }
  return (
    <form onSubmit={submit} noValidate>
      <div className="form-grid cols-2">
        <Field label="Hôte" htmlFor="peer-host"><input id="peer-host" value={host} onChange={(e) => setHost(e.target.value)} required /></Field>
        <Field label="Port" htmlFor="peer-port"><input id="peer-port" type="number" value={port} onChange={(e) => setPort(e.target.value)} required /></Field>
      </div>
      {err && <p className="field-error" role="alert">{err}</p>}
      <button type="submit" className="btn btn-primary btn-sm">Ajouter le pair</button>
    </form>
  );
}

function NodeDialog({ existing, onClose, onSaved }: { existing: Rec | null; onClose: () => void; onSaved: () => void }) {
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    nodeName: str(existing?.nodeName),
    displayName: str(existing?.displayName),
    enabled: Boolean(existing?.enabled ?? true),
    authorized: Boolean(existing?.authorized ?? true),
    revoked: Boolean(existing?.revoked ?? false),
    cspHost: str(existing?.cspHost),
    cspPort: str(existing?.cspPort),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!f.nodeName.trim()) return setErr("Nom requis");
    setBusy(true);
    try {
      const { body } = await apiJson<{ version: number }>(
        `/api/admin/config/relay/nodes/${encodeURIComponent(f.nodeName.trim())}`,
        {
          method: "PUT",
          body: {
            nodeName: f.nodeName.trim(),
            displayName: f.displayName.trim() || null,
            enabled: f.enabled,
            authorized: f.authorized,
            revoked: f.revoked,
            cspHost: f.cspHost.trim() || null,
            cspPort: f.cspPort.trim() ? Number(f.cspPort) : null,
            version: num(existing?.version) ?? 1,
          },
        },
      );
      push(`Node enregistré (v${body.version})`);
      onSaved();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title={isEdit ? "Éditer node" : "Nouveau node"} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <Field label="Nom (SAN urn:csp:node:<id>)" htmlFor="nd-name">
          <input id="nd-name" value={f.nodeName} onChange={(e) => setF({ ...f, nodeName: e.target.value })} disabled={isEdit} required />
        </Field>
        <Field label="Display name" htmlFor="nd-disp"><input id="nd-disp" value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
        <div className="form-grid cols-2">
          <Field label="cspHost (vide = accept-only)" htmlFor="nd-host"><input id="nd-host" value={f.cspHost} onChange={(e) => setF({ ...f, cspHost: e.target.value })} /></Field>
          <Field label="cspPort" htmlFor="nd-port"><input id="nd-port" type="number" value={f.cspPort} onChange={(e) => setF({ ...f, cspPort: e.target.value })} /></Field>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>Activé</span></label>
        <label className="check-row"><input type="checkbox" checked={f.authorized} onChange={(e) => setF({ ...f, authorized: e.target.checked })} /><span>Autorisé</span></label>
        <label className="check-row"><input type="checkbox" checked={f.revoked} onChange={(e) => setF({ ...f, revoked: e.target.checked })} /><span>Révoqué</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function CacheDialog({ existing, nodes, onClose, onSaved }: { existing: Rec | null; nodes: string[]; onClose: () => void; onSaved: () => void }) {
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    name: str(existing?.name),
    kind: str(existing?.kind) || "local",
    enabled: Boolean(existing?.enabled ?? true),
    nodeName: str(existing?.nodeName),
    maxAgeMs: str(existing?.maxAgeMs ?? 30000),
    maxEntries: str(existing?.maxEntries ?? 5000),
    maxInflight: str(existing?.maxInflight ?? 100),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!f.name.trim()) return setErr("Nom requis");
    setBusy(true);
    try {
      const { body } = await apiJson<{ version: number }>(
        `/api/admin/config/relay/caches/${encodeURIComponent(f.name.trim())}`,
        {
          method: "PUT",
          body: {
            name: f.name.trim(),
            kind: f.kind,
            enabled: f.enabled,
            nodeName: f.kind === "node" ? f.nodeName.trim() || null : null,
            maxAgeMs: Number(f.maxAgeMs) || 30000,
            maxEntries: Number(f.maxEntries) || 5000,
            maxInflight: Number(f.maxInflight) || 100,
            version: num(existing?.version) ?? 1,
          },
        },
      );
      push(`Cache enregistré (v${body.version})`);
      onSaved();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title={isEdit ? "Éditer cache" : "Nouveau cache"} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <Field label="Nom" htmlFor="rc-name">
          <input id="rc-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} disabled={isEdit} required />
        </Field>
        <div className="field">
          <label htmlFor="rc-kind">Type (kind)</label>
          <select id="rc-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
            <option value="local">local</option>
            <option value="node">node (CSPv2 authentifié)</option>
            <option value="udp">udp (échange allowlisté)</option>
          </select>
        </div>
        {f.kind === "node" && (
          <div className="field">
            <label htmlFor="rc-node">Node distant</label>
            <select id="rc-node" value={f.nodeName} onChange={(e) => setF({ ...f, nodeName: e.target.value })}>
              <option value="">—</option>
              {nodes.map((n) => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </div>
        )}
        <div className="form-grid cols-2">
          <Field label="maxAgeMs" htmlFor="rc-age"><input id="rc-age" type="number" value={f.maxAgeMs} onChange={(e) => setF({ ...f, maxAgeMs: e.target.value })} /></Field>
          <Field label="maxEntries" htmlFor="rc-entries"><input id="rc-entries" type="number" value={f.maxEntries} onChange={(e) => setF({ ...f, maxEntries: e.target.value })} /></Field>
          <Field label="maxInflight" htmlFor="rc-infl"><input id="rc-infl" type="number" value={f.maxInflight} onChange={(e) => setF({ ...f, maxInflight: e.target.value })} /></Field>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>Activé</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function LinkDialog({ existing, caches, onClose, onSaved }: { existing: Rec | null; caches: string[]; onClose: () => void; onSaved: () => void }) {
  const { push } = useToast();
  const snap = useSnapshot(fetchSnapshot);
  const isEdit = !!existing;
  const [f, setF] = useState({
    listenerId: str(existing?.listenerId),
    cacheName: str(existing?.cacheName) || caches[0] || "",
    canConsult: Boolean(existing?.canConsult ?? true),
    canFeed: Boolean(existing?.canFeed ?? true),
    canExport: Boolean(existing?.canExport ?? false),
    enabled: Boolean(existing?.enabled ?? true),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const lid = Number(f.listenerId);
    if (!Number.isInteger(lid) || lid < 1) return setErr("listenerId ≥ 1");
    if (!f.cacheName) return setErr("Cache requis");
    if (!f.canConsult && !f.canFeed && !f.canExport)
      return setErr("Au moins un sens requis (consultation, alimentation ou export)");
    setBusy(true);
    try {
      await apiJson(`/api/admin/config/relay/links/${lid}/${encodeURIComponent(f.cacheName)}`, {
        method: "PUT",
        body: { listenerId: lid, cacheName: f.cacheName, canConsult: f.canConsult, canFeed: f.canFeed, canExport: f.canExport, enabled: f.enabled, version: num(existing?.version) ?? 1 },
      });
      push("Lien enregistré");
      onSaved();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Dialog title={isEdit ? "Éditer lien" : "Nouveau lien listener → cache"} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="rl-lid">Listener (référence labellisée)</label>
          <select
            id="rl-lid"
            value={f.listenerId}
            onChange={(e) => setF({ ...f, listenerId: e.target.value })}
            disabled={isEdit}
            required
          >
            <option value="">— Choisir un listener —</option>
            {(snap.data?.profileListeners ?? []).map((l) => (
              <option key={l.id} value={String(l.id)}>
                #{l.id} — {l.profileName} / {l.protocol} :{l.port}
              </option>
            ))}
          </select>
          {!snap.data?.profileListeners?.length && !snap.loading && (
            <p className="muted small">Aucun listener connu — créez d'abord un listener.</p>
          )}
        </div>
        <div className="field">
          <label htmlFor="rl-cache">Cache</label>
          <select id="rl-cache" value={f.cacheName} onChange={(e) => setF({ ...f, cacheName: e.target.value })} disabled={isEdit}>
            {caches.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.canConsult} onChange={(e) => setF({ ...f, canConsult: e.target.checked })} /><span>Consultation (canConsult)</span></label>
        <label className="check-row"><input type="checkbox" checked={f.canFeed} onChange={(e) => setF({ ...f, canFeed: e.target.checked })} /><span>Alimentation (canFeed)</span></label>
        <label className="check-row"><input type="checkbox" checked={f.canExport} onChange={(e) => setF({ ...f, canExport: e.target.checked })} /><span>Export (canExport)</span></label>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>Lien actif</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function AssignDialog({ node, kind, onClose }: { node: string; kind: "listeners" | "connectors"; onClose: () => void }) {
  const { push } = useToast();
  const [items, setItems] = useState<Rec[]>([]);
  const [target, setTarget] = useState("");
  const [err, setErr] = useState("");
  useEffect(() => {
    apiJson<Rec[]>(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}`)
      .then(({ body }) => setItems(Array.isArray(body) ? body : []))
      .catch((e) => {
        setErr(describeError(e));
      });
  }, [node, kind]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!target.trim()) return;
    if (kind === "listeners" && !/^\d+$/.test(target.trim())) {
      setErr("Listener : id numérique requis");
      return;
    }
    try {
      // AssignmentDto{enabled, version} — création : version 1.
      await apiJson(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}/${encodeURIComponent(target.trim())}`, {
        method: "PUT",
        body: { enabled: true, version: 1 },
      });
      push(kind === "listeners" ? "Listener affecté" : "Connecteur affecté");
      setTarget("");
      const { body } = await apiJson<Rec[]>(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}`);
      setItems(Array.isArray(body) ? body : []);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  async function remove(id: string, version: number) {
    try {
      await apiFetch(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}/${encodeURIComponent(id)}?version=${version}`, {
        method: "DELETE",
      });
      push("Affectation retirée");
      const { body } = await apiJson<Rec[]>(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}`);
      setItems(Array.isArray(body) ? body : []);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  async function toggle(it: Rec) {
    const id = str(kind === "listeners" ? it.listenerId : it.connectorName);
    const version = num(it.version) ?? 1;
    try {
      await apiJson(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}/${encodeURIComponent(id)}`, {
        method: "PUT",
        body: { enabled: !it.enabled, version },
      });
      push("Affectation mise à jour");
      const { body } = await apiJson<Rec[]>(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}`);
      setItems(Array.isArray(body) ? body : []);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <Dialog title={`Affectations ${kind} — ${node}`} onClose={onClose}>
      {err && <p className="field-error" role="alert">{err}</p>}
      {!items.length ? (
        <Empty msg="Aucune affectation." />
      ) : (
        <ul className="small">
          {items.map((it, i) => {
            const id = str(kind === "listeners" ? it.listenerId : it.connectorName) || String(i);
            const version = num(it.version) ?? 1;
            return (
              <li key={id}>
                <code className="mono">{id}</code>{" "}
                <span className={it.enabled ? "badge badge-ok" : "badge badge-muted"}>
                  {it.enabled ? "actif" : "inactif"}
                </span>{" "}
                (v{version}){" "}
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggle(it)}>
                  {it.enabled ? "Désactiver" : "Activer"}
                </button>{" "}
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(id, version)}>
                  Retirer
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <form onSubmit={add} noValidate>
        <Field label={kind === "listeners" ? "Listener entrant (id)" : "Connecteur (nom)"} htmlFor="as-target">
          <input id="as-target" value={target} onChange={(e) => setTarget(e.target.value)} required />
        </Field>
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Fermer</button>
          <button type="submit" className="btn btn-primary btn-sm">Affecter</button>
        </div>
      </form>
    </Dialog>
  );
}
