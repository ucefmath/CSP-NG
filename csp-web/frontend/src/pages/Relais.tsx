import { useEffect, useState } from "react";
import { apiFetch, apiJson, describeError } from "../api";
import { ConfirmDialog, Dialog, Empty, Field, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { translate, useT } from "../i18n";

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
  const t = useT();
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

  if (loading) return <Loading label={t("relais.loading")} />;
  if (error) {
    return (
      <div className="card" role="alert">
        <h1>{t("relais.errorTitle")}</h1>
        <p className="field-error">{t("relais.errorMsg", { error: describeError(error) })}</p>
        <button type="button" className="btn btn-sm" onClick={load}>
          {t("relais.retryBtn")}
        </button>
      </div>
    );
  }

  const statusByNode: Record<string, Rec> = {};
  const statusNodes = status?.nodes as Rec[] | undefined;
  if (Array.isArray(statusNodes)) for (const n of statusNodes) statusByNode[str(n.nodeName)] = n;

  return (
    <div>
      <h1>{t("relais.title")}</h1>
      <p className="muted small">
        {t("relais.subtitle", { ttl: str((status?.workerConfigTtlMs as number | undefined) ?? "—") })}
      </p>

      <div className="card">
        <div className="toolbar">
          <h2>{t("relais.nodesCardTitle")}</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setNodeDlg("new")}>
            {t("relais.newNodeBtn")}
          </button>
        </div>
        {!nodes.length ? (
          <Empty msg={t("relais.noNodes")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("relais.thNode")}</th>
                  <th>{t("relais.thDisplay")}</th>
                  <th>{t("relais.thStatus")}</th>
                  <th>{t("relais.thEndpoint")}</th>
                  <th>{t("relais.thDesiredApplied")}</th>
                  <th>{t("relais.thVersion")}</th>
                  <th>{t("relais.thActions")}</th>
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
                          {revoked ? t("relais.badgeRevoked") : enabled ? (authorized ? t("relais.badgeActive") : t("relais.badgeUnauthorized")) : t("relais.badgeDisabled")}
                        </span>
                      </td>
                      <td className="mono small">{n.cspHost && n.cspPort ? `${n.cspHost}:${n.cspPort}` : t("relais.acceptOnly")}</td>
                      <td className="mono small">
                        {t("relais.desAppVersions", {
                          des: str(n.desiredVersion),
                          app: st && st.appliedVersion != null ? `v${st.appliedVersion}` : t("relais.never"),
                        })}
                        {st ? `${st.expired ? t("relais.tagExpired") : ""}${st.stale ? t("relais.tagStale") : ""}` : ""}
                      </td>
                      <td className="mono small">v{str(n.version)}</td>
                      <td>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setNodeDlg(n)}>
                          {t("relais.btnEdit")}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAssign({ node: name, kind: "listeners" })}>
                          {t("relais.btnListeners")}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setAssign({ node: name, kind: "connectors" })}>
                          {t("relais.btnConnectors")}
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
          <h2>{t("relais.cachesCardTitle")}</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setCacheDlg("new")}>
            {t("relais.newCacheBtn")}
          </button>
        </div>
        {!caches.length ? (
          <Empty msg={t("relais.noCaches")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("relais.thName")}</th>
                  <th>{t("relais.thType")}</th>
                  <th>{t("relais.thState")}</th>
                  <th>{t("relais.thNode")}</th>
                  <th>{t("relais.thVersion")}</th>
                  <th>{t("relais.thActions")}</th>
                </tr>
              </thead>
              <tbody>
                {caches.map((c) => (
                  <tr key={str(c.name)}>
                    <td className="mono">{str(c.name)}</td>
                    <td>{str(c.kind)}</td>
                    <td>
                      <span className={c.enabled ? "badge badge-ok" : "badge badge-muted"}>
                        {c.enabled ? t("relais.badgeEnabled") : t("relais.badgeCacheDisabled")}
                      </span>
                    </td>
                    <td className="mono small">{str(c.nodeName) || "—"}</td>
                    <td className="mono small">v{str(c.version)}</td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setCacheDlg(c)}>
                        {t("relais.btnEdit")}
                      </button>
                      {str(c.kind) === "udp" && (
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => loadPeers(str(c.name))}>
                          {t("relais.btnPeers")}
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
            <h3>{t("relais.peersTitle", { name: peersCache })}</h3>
            {!peers.length ? (
              <Empty msg={t("relais.noPeers")} />
            ) : (
              <ul className="small">
                {peers.map((p) => (
                  <li key={str(p.id ?? `${p.host}:${p.port}`)}>
                    <code className="mono">{str(p.host)}:{str(p.port)}</code> — {p.enabled ? t("relais.peerActive") : t("relais.peerInactive")}{" "}
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => removePeer(p, peersCache, load, push)}>
                      {t("relais.btnRemove")}
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
          <h2>{t("relais.linksCardTitle")}</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => setLinkDlg("new")}>
            {t("relais.newLinkBtn")}
          </button>
        </div>
        {!links.length ? (
          <Empty msg={t("relais.noLinks")} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t("relais.thListener")}</th>
                  <th>{t("relais.thCache")}</th>
                  <th>{t("relais.thConsult")}</th>
                  <th>{t("relais.thFeed")}</th>
                  <th>{t("relais.thExport")}</th>
                  <th>{t("relais.thLinkActive")}</th>
                  <th>{t("relais.thActions")}</th>
                </tr>
              </thead>
              <tbody>
                {links.map((l) => (
                  <tr key={`${str(l.listenerId)}/${str(l.cacheName)}`}>
                    <td className="mono">#{str(l.listenerId)}</td>
                    <td className="mono">{str(l.cacheName)}</td>
                    <td>{l.canConsult ? t("relais.yes") : t("relais.no")}</td>
                    <td>{l.canFeed ? t("relais.yes") : t("relais.no")}</td>
                    <td>{l.canExport ? t("relais.yes") : t("relais.no")}</td>
                    <td>
                      <span className={l.enabled ? "badge badge-ok" : "badge badge-muted"}>
                        {l.enabled ? t("relais.linkActive") : t("relais.linkInactive")}
                      </span>
                    </td>
                    <td>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLinkDlg(l)}>
                        {t("relais.btnEdit")}
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() =>
                          setDeleting({
                            kind: "lien",
                            label: t("relais.linkDeleteLabel", { listenerId: str(l.listenerId), cacheName: str(l.cacheName) }),
                            run: async () => {
                              const res = await apiFetch(
                                `/api/admin/config/relay/links/${str(l.listenerId)}/${encodeURIComponent(str(l.cacheName))}?version=${str((l as Rec).version ?? 1)}`,
                                { method: "DELETE" },
                              );
                              if (!res.ok) throw new Error(res.statusText);
                              push(t("relais.toastLinkDeleted"));
                              load();
                            },
                          })
                        }
                      >
                        {t("relais.btnDelete")}
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
          title={t("relais.confirmDeleteTitle")}
          desc={t("relais.confirmDeleteDesc", { label: deleting.label })}
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
    push(translate("relais.toastPeerRemoved"));
    reload();
  } catch (e) {
    push(describeError(e), "err");
  }
  void cacheName;
}

function PeerForm({ cacheName, onSaved }: { cacheName: string; onSaved: () => void }) {
  const t = useT();
  const { push } = useToast();
  const [host, setHost] = useState("");
  const [port, setPort] = useState("");
  const [err, setErr] = useState("");
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const p = Number(port);
    if (!host.trim()) return setErr(t("relais.errHostRequired"));
    if (!Number.isInteger(p) || p < 1 || p > 65535) return setErr(t("relais.errPortRange"));
    try {
      await apiJson(`/api/admin/config/relay/caches/${encodeURIComponent(cacheName)}/peers`, {
        method: "PUT",
        body: { host: host.trim(), port: p, enabled: true },
      });
      push(t("relais.toastPeerAdded"));
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
        <Field label={t("relais.fieldHost")} htmlFor="peer-host"><input id="peer-host" value={host} onChange={(e) => setHost(e.target.value)} required /></Field>
        <Field label={t("relais.fieldPort")} htmlFor="peer-port"><input id="peer-port" type="number" value={port} onChange={(e) => setPort(e.target.value)} required /></Field>
      </div>
      {err && <p className="field-error" role="alert">{err}</p>}
      <button type="submit" className="btn btn-primary btn-sm">{t("relais.btnAddPeer")}</button>
    </form>
  );
}

function NodeDialog({ existing, onClose, onSaved }: { existing: Rec | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
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
    if (!f.nodeName.trim()) return setErr(t("relais.errNodeNameRequired"));
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
      push(t("relais.toastNodeSaved", { version: body.version }));
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
    <Dialog title={isEdit ? t("relais.nodeDialogEditTitle") : t("relais.nodeDialogNewTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <Field label={t("relais.fieldNodeName")} htmlFor="nd-name">
          <input id="nd-name" value={f.nodeName} onChange={(e) => setF({ ...f, nodeName: e.target.value })} disabled={isEdit} required />
        </Field>
        <Field label={t("relais.fieldDisplayName")} htmlFor="nd-disp"><input id="nd-disp" value={f.displayName} onChange={(e) => setF({ ...f, displayName: e.target.value })} /></Field>
        <div className="form-grid cols-2">
          <Field label={t("relais.fieldCspHost")} htmlFor="nd-host"><input id="nd-host" value={f.cspHost} onChange={(e) => setF({ ...f, cspHost: e.target.value })} /></Field>
          <Field label={t("relais.fieldCspPort")} htmlFor="nd-port"><input id="nd-port" type="number" value={f.cspPort} onChange={(e) => setF({ ...f, cspPort: e.target.value })} /></Field>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>{t("relais.checkEnabled")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.authorized} onChange={(e) => setF({ ...f, authorized: e.target.checked })} /><span>{t("relais.checkAuthorized")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.revoked} onChange={(e) => setF({ ...f, revoked: e.target.checked })} /><span>{t("relais.checkRevoked")}</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("relais.btnCancel")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("relais.btnSave") : t("relais.btnCreate")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function CacheDialog({ existing, nodes, onClose, onSaved }: { existing: Rec | null; nodes: string[]; onClose: () => void; onSaved: () => void }) {
  const t = useT();
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
    if (!f.name.trim()) return setErr(t("relais.errCacheNameRequired"));
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
      push(t("relais.toastCacheSaved", { version: body.version }));
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
    <Dialog title={isEdit ? t("relais.cacheDialogEditTitle") : t("relais.cacheDialogNewTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <Field label={t("relais.fieldCacheName")} htmlFor="rc-name">
          <input id="rc-name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} disabled={isEdit} required />
        </Field>
        <div className="field">
          <label htmlFor="rc-kind">{t("relais.fieldCacheKind")}</label>
          <select id="rc-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
            <option value="local">{t("relais.kindLocal")}</option>
            <option value="node">{t("relais.kindNode")}</option>
            <option value="udp">{t("relais.kindUdp")}</option>
          </select>
        </div>
        {f.kind === "node" && (
          <div className="field">
            <label htmlFor="rc-node">{t("relais.fieldRemoteNode")}</label>
            <select id="rc-node" value={f.nodeName} onChange={(e) => setF({ ...f, nodeName: e.target.value })}>
              <option value="">—</option>
              {nodes.map((n) => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </div>
        )}
        <div className="form-grid cols-2">
          <Field label={t("relais.fieldMaxAgeMs")} htmlFor="rc-age"><input id="rc-age" type="number" value={f.maxAgeMs} onChange={(e) => setF({ ...f, maxAgeMs: e.target.value })} /></Field>
          <Field label={t("relais.fieldMaxEntries")} htmlFor="rc-entries"><input id="rc-entries" type="number" value={f.maxEntries} onChange={(e) => setF({ ...f, maxEntries: e.target.value })} /></Field>
          <Field label={t("relais.fieldMaxInflight")} htmlFor="rc-infl"><input id="rc-infl" type="number" value={f.maxInflight} onChange={(e) => setF({ ...f, maxInflight: e.target.value })} /></Field>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>{t("relais.checkEnabled")}</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("relais.btnCancel")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("relais.btnSave") : t("relais.btnCreate")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function LinkDialog({ existing, caches, onClose, onSaved }: { existing: Rec | null; caches: string[]; onClose: () => void; onSaved: () => void }) {
  const t = useT();
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
    if (!Number.isInteger(lid) || lid < 1) return setErr(t("relais.errListenerIdMin"));
    if (!f.cacheName) return setErr(t("relais.errCacheRequired"));
    if (!f.canConsult && !f.canFeed && !f.canExport)
      return setErr(t("relais.errAtLeastOneDirection"));
    setBusy(true);
    try {
      await apiJson(`/api/admin/config/relay/links/${lid}/${encodeURIComponent(f.cacheName)}`, {
        method: "PUT",
        body: { listenerId: lid, cacheName: f.cacheName, canConsult: f.canConsult, canFeed: f.canFeed, canExport: f.canExport, enabled: f.enabled, version: num(existing?.version) ?? 1 },
      });
      push(t("relais.toastLinkSaved"));
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
    <Dialog title={isEdit ? t("relais.linkDialogEditTitle") : t("relais.linkDialogNewTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="rl-lid">{t("relais.fieldListenerSelect")}</label>
          <select
            id="rl-lid"
            value={f.listenerId}
            onChange={(e) => setF({ ...f, listenerId: e.target.value })}
            disabled={isEdit}
            required
          >
            <option value="">{t("relais.chooseListenerOption")}</option>
            {(snap.data?.profileListeners ?? []).map((l) => (
              <option key={l.id} value={String(l.id)}>
                #{l.id} — {l.profileName} / {l.protocol} :{l.port}
              </option>
            ))}
          </select>
          {!snap.data?.profileListeners?.length && !snap.loading && (
            <p className="muted small">{t("relais.noKnownListeners")}</p>
          )}
        </div>
        <div className="field">
          <label htmlFor="rl-cache">{t("relais.fieldCacheSelect")}</label>
          <select id="rl-cache" value={f.cacheName} onChange={(e) => setF({ ...f, cacheName: e.target.value })} disabled={isEdit}>
            {caches.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </div>
        <label className="check-row"><input type="checkbox" checked={f.canConsult} onChange={(e) => setF({ ...f, canConsult: e.target.checked })} /><span>{t("relais.checkCanConsult")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.canFeed} onChange={(e) => setF({ ...f, canFeed: e.target.checked })} /><span>{t("relais.checkCanFeed")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.canExport} onChange={(e) => setF({ ...f, canExport: e.target.checked })} /><span>{t("relais.checkCanExport")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={(e) => setF({ ...f, enabled: e.target.checked })} /><span>{t("relais.checkLinkEnabled")}</span></label>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("relais.btnCancel")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("relais.btnSave") : t("relais.btnCreate")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function AssignDialog({ node, kind, onClose }: { node: string; kind: "listeners" | "connectors"; onClose: () => void }) {
  const t = useT();
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
      setErr(t("relais.errListenerIdNumeric"));
      return;
    }
    try {
      // AssignmentDto{enabled, version} — création : version 1.
      await apiJson(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}/${encodeURIComponent(target.trim())}`, {
        method: "PUT",
        body: { enabled: true, version: 1 },
      });
      push(kind === "listeners" ? t("relais.toastListenerAssigned") : t("relais.toastConnectorAssigned"));
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
      push(t("relais.toastAssignmentRemoved"));
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
      push(t("relais.toastAssignmentUpdated"));
      const { body } = await apiJson<Rec[]>(`/api/admin/config/relay/nodes/${encodeURIComponent(node)}/${kind}`);
      setItems(Array.isArray(body) ? body : []);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <Dialog
      title={t("relais.assignDialogTitle", {
        kind: kind === "listeners" ? t("relais.kindListeners") : t("relais.kindConnectors"),
        node,
      })}
      onClose={onClose}
    >
      {err && <p className="field-error" role="alert">{err}</p>}
      {!items.length ? (
        <Empty msg={t("relais.noAssignments")} />
      ) : (
        <ul className="small">
          {items.map((it, i) => {
            const id = str(kind === "listeners" ? it.listenerId : it.connectorName) || String(i);
            const version = num(it.version) ?? 1;
            return (
              <li key={id}>
                <code className="mono">{id}</code>{" "}
                <span className={it.enabled ? "badge badge-ok" : "badge badge-muted"}>
                  {it.enabled ? t("relais.assignmentActive") : t("relais.assignmentInactive")}
                </span>{" "}
                (v{version}){" "}
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => toggle(it)}>
                  {it.enabled ? t("relais.btnDisable") : t("relais.btnEnable")}
                </button>{" "}
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(id, version)}>
                  {t("relais.btnRemove")}
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <form onSubmit={add} noValidate>
        <Field label={kind === "listeners" ? t("relais.fieldInboundListenerId") : t("relais.fieldConnectorName")} htmlFor="as-target">
          <input id="as-target" value={target} onChange={(e) => setTarget(e.target.value)} required />
        </Field>
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("relais.btnClose")}</button>
          <button type="submit" className="btn btn-primary btn-sm">{t("relais.btnAssign")}</button>
        </div>
      </form>
    </Dialog>
  );
}
