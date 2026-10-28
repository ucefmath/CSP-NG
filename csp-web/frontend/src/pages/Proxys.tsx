import { useEffect, useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseInteger } from "../api";
import type { ProxyEntry } from "../types";
import { SecretField, SecretRefView } from "../components/SecretField";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, useToast } from "../components/ui";
import { useT } from "../i18n";

export function ProxysPage() {
  const t = useT();
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
      push(p.enabled ? t("proxys.toastDisabled", { version: body.version }) : t("proxys.toastEnabled", { version: body.version }));
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
          push(t("proxys.toastDeleteRefused"), "err");
          return;
        }
        throw { status: res.status, ...(bb as object) };
      }
      push(t("proxys.toastDeleted"));
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>{t("proxys.title")}</h1>
        <input
          type="search"
          className="search-input"
          placeholder={t("proxys.searchPlaceholder")}
          aria-label={t("proxys.searchAria")}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          {t("common.create")}
        </button>
      </div>
      {!filtered.length ? (
        <Empty msg={(list ?? []).length ? t("proxys.noResult") : t("proxys.emptyList")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("proxys.thName")}</th>
                <th>{t("proxys.thEnabled")}</th>
                <th>{t("proxys.thHostPort")}</th>
                <th>{t("proxys.thAuth")}</th>
                <th>{t("proxys.thConnectors")}</th>
                <th>{t("proxys.thVersion")}</th>
                <th>{t("common.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((p) => (
                <tr key={p.name}>
                  <td className="mono">{p.name}</td>
                  <td>
                    <span className={p.enabled ? "badge badge-ok" : "badge badge-danger"}>
                      {p.enabled ? t("proxys.badgeActive") : t("proxys.badgeDisabled")}
                    </span>
                  </td>
                  <td className="mono small">{p.host}:{p.port}</td>
                  <td className="small">{p.username ? t("proxys.authYes", { user: p.username }) : t("common.no")}</td>
                  <td className="mono">{p.connectorCount ?? 0}</td>
                  <td className="mono">v{p.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(p)}>{t("common.edit")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setToggling(p)}>
                      {p.enabled ? t("proxys.disableBtn") : t("proxys.enableBtn")}
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(p)}>{t("common.delete")}</button>
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
          title={toggling.enabled ? t("proxys.dialogDisableTitle") : t("proxys.dialogEnableTitle")}
          desc={
            toggling.enabled && (toggling.connectorCount ?? 0) > 0
              ? t("proxys.dialogDisableDescInUse", { count: toggling.connectorCount ?? 0 })
              : toggling.enabled
                ? t("proxys.dialogDisableDescUnused")
                : t("proxys.dialogEnableDesc", { name: toggling.name })
          }
          confirmLabel={toggling.enabled ? t("proxys.disableBtn") : t("proxys.enableBtn")}
          onClose={() => setToggling(null)}
          onConfirm={() => doToggle(toggling)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t("proxys.dialogDeleteTitle")}
          desc={
            (deleting.connectorCount ?? 0) > 0
              ? t("proxys.dialogDeleteDescInUse", { count: deleting.connectorCount ?? 0 })
              : t("proxys.dialogDeleteDescUnused", { name: deleting.name, version: deleting.version })
          }
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
    </div>
  );
}

function ProxyDialog({ existing, onClose, onSaved }: { existing: ProxyEntry | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
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
    if (!n) return setErr(t("proxys.errNameRequired"));
    if (!host.trim()) return setErr(t("proxys.errHostRequired"));
    const p = parseInteger(port, NaN);
    if (!(p >= 1 && p <= 65535)) return setErr(t("proxys.errPortRange"));
    const u = username.trim() || null;
    const pr = passwordRef.trim() || null;
    if ((u !== null) !== (pr !== null)) return setErr(t("proxys.errUserPassTogether"));
    if (pr !== null && !/^(env:|file:)/.test(pr)) return setErr(t("proxys.errPassRefFormat"));
    setBusy(true);
    try {
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/proxies/${encodeURIComponent(n)}`, {
        method: "PUT",
        body: { name: n, enabled, host: host.trim(), port: p, username: u, passwordRef: pr, description: description.trim() || null, version: existing?.version ?? 1 },
      });
      push(isEdit ? t("proxys.toastUpdated", { version: body.version }) : t("proxys.toastCreated", { version: body.version }));
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
    <Dialog title={isEdit ? t("proxys.dialogEditTitle") : t("proxys.dialogCreateTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className="muted small">{t("proxys.dialogNotice")}</p>
        <Field label={t("proxys.fieldName")} htmlFor="px-name">
          <input id="px-name" value={name} onChange={(e) => setName(e.target.value)} disabled={isEdit} required />
        </Field>
        <Field label={t("proxys.fieldHost")} htmlFor="px-host">
          <input id="px-host" value={host} onChange={(e) => setHost(e.target.value)} required />
        </Field>
        <Field label={t("proxys.fieldPort")} htmlFor="px-port">
          <input id="px-port" type="number" value={port} onChange={(e) => setPort(e.target.value)} required />
        </Field>
        <Field label={t("proxys.fieldUsername")} htmlFor="px-user">
          <input id="px-user" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        </Field>
        <SecretField
          label={t("proxys.fieldPasswordRef")}
          value={passwordRef}
          onChange={setPasswordRef}
          purpose="proxy-password"
          owner={name || "proxy"}
          help={t("proxys.fieldPasswordRefHelp")}
        />
        {isEdit && (
          <p className="muted small">{t("proxys.currentRef")} <SecretRefView value={existing.passwordRef} /></p>
        )}
        <Field label={t("proxys.fieldDescription")} htmlFor="px-desc">
          <input id="px-desc" value={description} onChange={(e) => setDescription(e.target.value)} />
        </Field>
        <label className="check-row">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span>{t("proxys.fieldEnabled")}</span>
        </label>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("common.cancel")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("common.save") : t("common.create")}</button>
        </div>
      </form>
    </Dialog>
  );
}
