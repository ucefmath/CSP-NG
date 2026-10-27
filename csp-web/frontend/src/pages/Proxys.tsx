import { useEffect, useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseInteger } from "../api";
import type { ProxyEntry } from "../types";
import { SecretField, SecretRefView } from "../components/SecretField";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, useToast } from "../components/ui";

export function ProxysPage() {
  const { push } = useToast();
  const [list, setList] = useState<ProxyEntry[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<ProxyEntry | null | "new">(null);
  const [deleting, setDeleting] = useState<ProxyEntry | null>(null);
  const [toggling, setToggling] = useState<ProxyEntry | null>(null);

  const load = async () => {
    setError(null);
    try {
      const { body } = await apiJson<ProxyEntry[]>("/api/admin/config/proxies");
      setList(Array.isArray(body) ? body : []);
    } catch (e) {
      setError(e);
      setList([]);
    }
  };
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filtered = useMemo(() => {
    const all = list ?? [];
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((p) => `${p.name} ${p.host}:${p.port}`.toLowerCase().includes(q));
  }, [list, filter]);

  if (list === null && !error) return <Loading />;
  if (error && !list?.length) return <LoadError error={error} onRetry={load} />;

  async function doToggle(p: ProxyEntry) {
    try {
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/proxies/${encodeURIComponent(p.name)}`, {
        method: "PUT",
        body: {
          name: p.name,
          enabled: !p.enabled,
          host: p.host,
          port: p.port,
          username: p.username ?? null,
          passwordRef: p.passwordRef ?? null,
          description: p.description ?? null,
          version: p.version,
        },
      });
      push(`Proxy ${p.enabled ? "désactivé" : "activé"} (v${body.version})`);
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  async function doDelete(p: ProxyEntry) {
    try {
      const res = await apiFetch(`/api/admin/config/proxies/${encodeURIComponent(p.name)}?version=${p.version}`, {
        method: "DELETE",
      });
      if (!res.ok) {
        let b: unknown = null;
        try {
          b = await res.json();
        } catch {
          b = null;
        }
        const bb = (b ?? {}) as { code?: string };
        if (res.status === 409 && bb.code === "PROXY_IN_USE") {
          push("Suppression refusée : proxy référencé par des connecteurs", "err");
          return;
        }
        throw { status: res.status, ...(bb as object) };
      }
      push("Proxy supprimé");
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>Proxys SOCKS5</h1>
        <input
          type="search"
          className="search-input"
          placeholder="Rechercher…"
          aria-label="Rechercher un proxy"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          Créer
        </button>
      </div>
      {!filtered.length ? (
        <Empty msg={(list ?? []).length ? "Aucun résultat" : "Aucun proxy — créez un proxy SOCKS5 puis associez-le à un connecteur"} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Nom</th>
                <th>Activé</th>
                <th>Hôte:port</th>
                <th>Auth</th>
                <th>Connecteurs</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((p) => (
                <tr key={p.name}>
                  <td className="mono">{p.name}</td>
                  <td>
                    <span className={p.enabled ? "badge badge-ok" : "badge badge-danger"}>
                      {p.enabled ? "actif" : "désactivé"}
                    </span>
                  </td>
                  <td className="mono small">{p.host}:{p.port}</td>
                  <td className="small">{p.username ? `oui (${p.username})` : "non"}</td>
                  <td className="mono">{p.connectorCount ?? 0}</td>
                  <td className="mono">v{p.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(p)}>Éditer</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setToggling(p)}>
                      {p.enabled ? "Désactiver" : "Activer"}
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(p)}>Supprimer</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <ProxyDialog
          existing={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            load();
          }}
        />
      )}
      {toggling && (
        <ConfirmDialog
          title={toggling.enabled ? "Désactiver le proxy" : "Activer le proxy"}
          desc={
            toggling.enabled && (toggling.connectorCount ?? 0) > 0
              ? `Ce proxy est utilisé par ${toggling.connectorCount} connecteur(s). Les désactiver coupe leurs sessions et bloque leur reconnexion — AUCUN repli en connexion directe.`
              : toggling.enabled
                ? "Aucun connecteur n'utilise ce proxy. La désactivation empêche toute nouvelle association active."
                : `Activer « ${toggling.name} ».`
          }
          confirmLabel={toggling.enabled ? "Désactiver" : "Activer"}
          onClose={() => setToggling(null)}
          onConfirm={() => doToggle(toggling)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title="Supprimer le proxy"
          desc={
            (deleting.connectorCount ?? 0) > 0
              ? `Refusé côté serveur tant que ${deleting.connectorCount} connecteur(s) référencent ce proxy — détachez-les d'abord.`
              : `« ${deleting.name} » (v${deleting.version}) — irréversible.`
          }
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
    </div>
  );
}

function ProxyDialog({ existing, onClose, onSaved }: { existing: ProxyEntry | null; onClose: () => void; onSaved: () => void }) {
  const { push } = useToast();
  const isEdit = !!existing;
  const [name, setName] = useState(existing?.name ?? "");
  const [host, setHost] = useState(existing?.host ?? "");
  const [port, setPort] = useState(String(existing?.port ?? 1080));
  const [username, setUsername] = useState(existing?.username ?? "");
  const [passwordRef, setPasswordRef] = useState(existing?.passwordRef ?? "");
  const [description, setDescription] = useState(existing?.description ?? "");
  const [enabled, setEnabled] = useState(existing?.enabled ?? true);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const n = name.trim();
    if (!n) return setErr("Nom requis");
    if (!host.trim()) return setErr("Hôte requis");
    const p = parseInteger(port, NaN);
    if (!(p >= 1 && p <= 65535)) return setErr("Port 1-65535");
    const u = username.trim() || null;
    const pr = passwordRef.trim() || null;
    if ((u !== null) !== (pr !== null)) return setErr("username et passwordRef vont ensemble");
    if (pr !== null && !/^(env:|file:)/.test(pr)) return setErr("passwordRef : env: ou file: requis");
    setBusy(true);
    try {
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/proxies/${encodeURIComponent(n)}`, {
        method: "PUT",
        body: { name: n, enabled, host: host.trim(), port: p, username: u, passwordRef: pr, description: description.trim() || null, version: existing?.version ?? 1 },
      });
      push(`Proxy ${isEdit ? "mis à jour" : "créé"} (v${body.version})`);
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
    <Dialog title={isEdit ? "Éditer proxy" : "Créer proxy SOCKS5"} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className="muted small">Nom immuable. Auth : références env:/file: uniquement — jamais de mot de passe en clair.</p>
        <Field label="Nom (immuable)" htmlFor="px-name">
          <input id="px-name" value={name} onChange={(e) => setName(e.target.value)} disabled={isEdit} required />
        </Field>
        <Field label="Hôte (IP ou DNS du proxy)" htmlFor="px-host">
          <input id="px-host" value={host} onChange={(e) => setHost(e.target.value)} required />
        </Field>
        <Field label="Port" htmlFor="px-port">
          <input id="px-port" type="number" value={port} onChange={(e) => setPort(e.target.value)} required />
        </Field>
        <Field label="username (optionnel)" htmlFor="px-user">
          <input id="px-user" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        </Field>
        <SecretField
          label="passwordRef (env: ou file:)"
          value={passwordRef}
          onChange={setPasswordRef}
          purpose="proxy-password"
          owner={name || "proxy"}
          help="Va avec username. Valeur directe matérialisée serveur, ou ref existante conservée."
        />
        {isEdit && (
          <p className="muted small">Ref actuelle : <SecretRefView value={existing.passwordRef} /></p>
        )}
        <Field label="Description" htmlFor="px-desc">
          <input id="px-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <label className="check-row">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span>Activé (kill switch : désactiver coupe les connecteurs attachés)</span>
        </label>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
        </div>
      </form>
    </Dialog>
  );
}
