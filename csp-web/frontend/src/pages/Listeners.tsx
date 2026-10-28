import { useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseInteger } from "../api";
import type { ListenerEntry } from "../types";
import { BulkBar, SelectBox, type BulkFieldDef } from "../components/BulkBar";
import { SecretField } from "../components/SecretField";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { useT, type MessageKey } from "../i18n";

function getBulkFields(t: (k: MessageKey, params?: Record<string, string | number>) => string): BulkFieldDef[] {
  return [
    { key: "enabled", label: t("listeners.bulkEnabled"), type: "boolean" as const },
    { key: "cardMode", label: t("listeners.bulkCardMode"), type: "text" as const, resettable: true, resetHint: t("listeners.bulkResetHintManual") },
    { key: "strictPolicy", label: t("listeners.bulkStrictPolicy"), type: "boolean" as const },
    { key: "reshareCap", label: t("listeners.bulkReshareCap"), type: "number" as const, resettable: true, resetHint: t("listeners.bulkResetHintZero") },
    { key: "maxCards", label: t("listeners.bulkMaxCards"), type: "number" as const },
    { key: "maxInflight", label: t("listeners.bulkMaxInflight"), type: "number" as const },
    { key: "maxPerIp", label: t("listeners.bulkMaxPerIp"), type: "number" as const },
    { key: "idleTimeoutSec", label: t("listeners.bulkIdleTimeoutSec"), type: "number" as const },
    { key: "allowList", label: t("listeners.bulkAllowList"), type: "text" as const, resettable: true, resetHint: "NULL" },
    { key: "denyList", label: t("listeners.bulkDenyList"), type: "text" as const, resettable: true, resetHint: "NULL" },
  ];
}

export function ListenersPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [editing, setEditing] = useState<ListenerEntry | null | "new">(null);
  const [deleting, setDeleting] = useState<ListenerEntry | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const bulkFields = useMemo(() => getBulkFields(t), [t]);

  const list = useMemo(() => snap.data?.profileListeners ?? [], [snap.data]);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;

  const toggle = (id: string, v: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (v) n.add(id);
      else n.delete(id);
      return n;
    });
  const selectedItems = list.filter((l) => selected.has(String(l.id))).map((l) => ({ id: String(l.id), item: l }));

  async function doDelete(l: ListenerEntry) {
    try {
      const res = await apiFetch(`/api/admin/config/listeners/${l.id}?version=${l.version}`, { method: "DELETE" });
      if (!res.ok) {
        let b: unknown = null;
        try {
          b = await res.json();
        } catch {
          b = null;
        }
        throw { status: res.status, ...((b ?? {}) as object) };
      }
      push(t("listeners.toastDeleted"));
      snap.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div>
    <div className="card">
      <div className="toolbar">
        <h1>{t("listeners.title")}</h1>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          {t("listeners.newListenerBtn")}
        </button>
      </div>
      <p className="muted small">{t("listeners.subtitle")}</p>
      <BulkBar
        resource="listeners"
        selected={selectedItems}
        fields={bulkFields}
        labelOf={(id) => `#${id}`}
        onDone={() => {
          setSelected(new Set());
          snap.reload();
        }}
      />
      {!list.length ? (
        <Empty msg={t("listeners.emptyMsg")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th><span className="sr-only">{t("listeners.thSelect")}</span></th>
                <th>{t("listeners.thId")}</th>
                <th>{t("listeners.thProfile")}</th>
                <th>{t("listeners.thProtocol")}</th>
                <th>{t("listeners.thPort")}</th>
                <th>{t("listeners.thCardMode")}</th>
                <th>{t("listeners.thStrict")}</th>
                <th>{t("listeners.thReshare")}</th>
                <th>{t("listeners.thStatus")}</th>
                <th>{t("listeners.thActions")}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((l) => (
                <tr key={l.id}>
                  <td>
                    <SelectBox checked={selected.has(String(l.id))} onChange={(v) => toggle(String(l.id), v)} label={t("listeners.selectListenerAria", { id: l.id })} />
                  </td>
                  <td className="mono">{l.id}</td>
                  <td><strong>{l.profileName}</strong></td>
                  <td><code className="mono">{l.protocol}</code></td>
                  <td className="mono">{l.port}</td>
                  <td>{l.cardMode ?? "manual"}</td>
                  <td>{l.strictPolicy !== false ? t("listeners.yes") : t("listeners.no")}</td>
                  <td className="mono">{l.reshareCap ?? 0}</td>
                  <td>
                    <span className={l.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {l.enabled ? t("listeners.badgeActive") : t("listeners.badgeDisabled")}
                    </span>
                  </td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(l)}>{t("listeners.editBtn")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(l)}>{t("listeners.deleteBtn")}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <ListenerDialog
          existing={editing === "new" ? null : editing}
          profiles={(snap.data?.profiles ?? []).map((p) => p.name)}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            snap.reload();
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t("listeners.dialogDeleteTitle")}
          desc={t("listeners.dialogDeleteDesc", { id: deleting.id, port: deleting.port ?? "" })}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
    </div>
    <ExtendedNewcamdCard />
    </div>
  );
}

// Newcamd étendu : port partagé, desKeyRef secret, allow/deny listes.
function ExtendedNewcamdCard() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [f, setF] = useState<Record<string, string | boolean> | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  if (snap.loading) return <Loading label={t("listeners.extLoading")} />;
  if (snap.error) return null;
  const ext = (snap.data?.extendedNewcamd ?? {}) as Record<string, string | number | boolean | null>;
  const v: Record<string, string | boolean> = f ?? {
    enabled: Boolean(ext.enabled ?? false),
    listenPort: String(ext.listenPort ?? 0),
    bindAddress: String(ext.bindAddress ?? "0.0.0.0"),
    catchAll: Boolean(ext.catchAll ?? false),
    debug: Boolean(ext.debug ?? false),
    desKeyRef: String(ext.desKeyRef ?? ""),
    allowList: String(ext.allowList ?? ""),
    denyList: String(ext.denyList ?? ""),
    maxMsgSize: String(ext.maxMsgSize ?? 1024),
  };
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...v, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    const port = parseInteger(String(v.listenPort), NaN);
    if (!(port >= 1 && port <= 65535)) return setErr(t("listeners.extErrPortRange"));
    const maxMsgSize = parseInteger(String(v.maxMsgSize), NaN);
    if (!(maxMsgSize >= 128 && maxMsgSize <= 65536)) return setErr(t("listeners.extErrMaxMsgSize"));
    if (!String(v.bindAddress).trim()) return setErr(t("listeners.extErrBindRequired"));
    if (Boolean(v.enabled) && !String(v.desKeyRef).trim()) return setErr(t("listeners.extErrDesKeyRequiredWhenEnabled"));
    if (String(v.desKeyRef).trim() && !/^(env:|file:)/.test(String(v.desKeyRef).trim()))
      return setErr(t("listeners.extErrDesKeyFormat"));
    setBusy(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/extended-newcamd", {
        method: "PUT",
        body: {
          enabled: Boolean(v.enabled),
          listenPort: port,
          bindAddress: String(v.bindAddress).trim(),
          catchAll: Boolean(v.catchAll),
          debug: Boolean(v.debug),
          desKeyRef: String(v.desKeyRef).trim() || null,
          allowList: String(v.allowList).trim() || null,
          denyList: String(v.denyList).trim() || null,
          maxMsgSize,
          version: Number(ext.version ?? 1),
        },
      });
      setMsg(t("listeners.extSavedMsg", { version: body.version }));
      push(t("listeners.extToastUpdated"));
      setF(null);
      snap.reload();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <h2>{t("listeners.extTitle")}</h2>
      <p className="muted small">{t("listeners.extSubtitle")}</p>
      <form onSubmit={submit} noValidate>
        <div className="form-grid cols-2">
          <label className="check-row"><input type="checkbox" checked={Boolean(v.enabled)} onChange={set("enabled")} /><span>{t("listeners.extFieldEnabled")}</span></label>
          <label className="check-row"><input type="checkbox" checked={Boolean(v.catchAll)} onChange={set("catchAll")} /><span>{t("listeners.extFieldCatchAll")}</span></label>
          <label className="check-row"><input type="checkbox" checked={Boolean(v.debug)} onChange={set("debug")} /><span>{t("listeners.extFieldDebug")}</span></label>
          <Field label={t("listeners.extFieldPort")} htmlFor="ext-port"><input id="ext-port" type="number" value={String(v.listenPort)} onChange={set("listenPort")} required /></Field>
          <Field label={t("listeners.extFieldBind")} htmlFor="ext-bind"><input id="ext-bind" value={String(v.bindAddress)} onChange={set("bindAddress")} required /></Field>
          <Field label={t("listeners.extFieldMaxSize")} htmlFor="ext-max"><input id="ext-max" type="number" value={String(v.maxMsgSize)} onChange={set("maxMsgSize")} required /></Field>
        </div>
        <SecretField
          label={t("listeners.extFieldDesKey")}
          value={String(v.desKeyRef)}
          onChange={(ref) => setF({ ...v, desKeyRef: ref })}
          purpose="extended-newcamd-des-key"
          owner="extended-newcamd"
          kind="deskey"
        />
        <div className="form-grid cols-2">
          <Field label={t("listeners.extFieldAllow")} htmlFor="ext-allow"><input id="ext-allow" value={String(v.allowList)} onChange={set("allowList")} /></Field>
          <Field label={t("listeners.extFieldDeny")} htmlFor="ext-deny"><input id="ext-deny" value={String(v.denyList)} onChange={set("denyList")} /></Field>
        </div>
        {err && <p className="field-error" role="alert">{err}</p>}
        {msg && <p className="alert alert-ok" role="status">{msg}</p>}
        <div className="toolbar">
          <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>{busy ? "…" : t("listeners.extSaveBtn")}</button>
        </div>
      </form>
    </div>
  );
}

function ListenerDialog({
  existing,
  profiles,
  onClose,
  onSaved,
}: {
  existing: ListenerEntry | null;
  profiles: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    profileName: existing?.profileName ?? profiles[0] ?? "",
    protocol: existing?.protocol ?? "newcamd",
    port: String(existing?.port ?? 12000),
    bindAddress: existing?.bindAddress ?? "0.0.0.0",
    desKeyRef: existing?.desKeyRef ?? "",
    allowList: existing?.allowList ?? "",
    denyList: existing?.denyList ?? "",
    nodeId: existing?.nodeId ?? "",
    cardMode: existing?.cardMode ?? "manual",
    reshareCap: String(existing?.reshareCap ?? 0),
    maxCards: String(existing?.maxCards ?? 512),
    maxInflight: String(existing?.maxInflight ?? 32),
    maxPerIp: String(existing?.maxPerIp ?? 5),
    idleTimeoutSec: String(existing?.idleTimeoutSec ?? 60),
    enabled: existing?.enabled ?? true,
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!f.profileName.trim()) return setErr(t("listeners.errProfileRequired"));
    const port = parseInteger(f.port, NaN);
    if (!(port >= 1 && port <= 65535)) return setErr(t("listeners.errPortRange"));
    setBusy(true);
    try {
      const payload = {
        profileName: f.profileName.trim(),
        protocol: f.protocol,
        port,
        bindAddress: f.bindAddress.trim() || "0.0.0.0",
        enabled: Boolean(f.enabled),
        desKeyRef: f.desKeyRef.trim() || null,
        allowList: f.allowList.trim() || null,
        denyList: f.denyList.trim() || null,
        nodeId: f.nodeId.trim() || null,
        cardMode: f.cardMode,
        strictPolicy: f.protocol === "cccam" || f.protocol === "cs378x" ? true : (existing?.strictPolicy ?? true),
        reshareCap: parseInteger(f.reshareCap, 0),
        maxCards: parseInteger(f.maxCards, 512),
        maxInflight: parseInteger(f.maxInflight, 32),
        maxPerIp: parseInteger(f.maxPerIp, 5),
        idleTimeoutSec: parseInteger(f.idleTimeoutSec, 60),
        version: existing?.version ?? 1,
      };
      await apiJson(`/api/admin/config/listeners/${existing?.id ?? 0}`, { method: "PUT", body: payload });
      push(t("listeners.toastSaved"));
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
    <Dialog title={isEdit ? t("listeners.dialogEditTitle", { id: existing.id }) : t("listeners.dialogCreateTitle")} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label={t("listeners.sectionEssential")}>
          <h3>{t("listeners.sectionEssential")}</h3>
          <div className="form-grid cols-2">
            <div className="field">
              <label htmlFor="li-prof">{t("listeners.fieldProfile")}</label>
              <select id="li-prof" value={f.profileName} onChange={set("profileName")} required>
                {profiles.map((p) => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="li-proto">{t("listeners.fieldProtocol")}</label>
              <select id="li-proto" value={f.protocol} onChange={set("protocol")}>
                <option value="newcamd">Newcamd</option>
                <option value="radegast">Radegast</option>
                <option value="cccam">CCcam</option>
                <option value="cs378x">CS378x</option>
              </select>
            </div>
            <Field label={t("listeners.fieldPort")} htmlFor="li-port">
              <input id="li-port" type="number" value={f.port} onChange={set("port")} required />
            </Field>
            <Field label={t("listeners.fieldBind")} htmlFor="li-bind">
              <input id="li-bind" value={f.bindAddress} onChange={set("bindAddress")} />
            </Field>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.enabled)} onChange={set("enabled")} /><span>{t("listeners.fieldActive")}</span></label>
          </div>
        </section>
        <section aria-label={t("listeners.sectionSecrets")} className="section">
          <h3>{t("listeners.sectionSecrets")}</h3>
          <SecretField
            label={t("listeners.fieldDesKeyRef")}
            value={f.desKeyRef}
            onChange={(v) => setF({ ...f, desKeyRef: v })}
            purpose="listener-des-key"
            owner={f.profileName ? `listener-${f.profileName}-${f.port}` : "listener"}
            kind="deskey"
            help={t("listeners.fieldDesKeyRefHelp")}
          />
          <div className="form-grid cols-2">
            <Field label={t("listeners.fieldAllowList")} htmlFor="li-allow">
              <input id="li-allow" value={f.allowList} onChange={set("allowList")} />
            </Field>
            <Field label={t("listeners.fieldDenyList")} htmlFor="li-deny">
              <input id="li-deny" value={f.denyList} onChange={set("denyList")} />
            </Field>
            <Field label={t("listeners.fieldNodeId")} htmlFor="li-node">
              <input id="li-node" value={f.nodeId} onChange={set("nodeId")} />
            </Field>
          </div>
        </section>
        <section aria-label={t("listeners.sectionAdvanced")} className="section section-advanced">
          <h3>{t("listeners.sectionAdvanced")}</h3>
          <div className="form-grid cols-2">
            <div className="field">
              <label htmlFor="li-mode">{t("listeners.fieldCardMode")}</label>
              <select id="li-mode" value={f.cardMode} onChange={set("cardMode")}>
                <option value="manual">{t("listeners.optCardModeManual")}</option>
                <option value="wildcard">{t("listeners.optCardModeWildcard")}</option>
              </select>
            </div>
            <Field label={t("listeners.fieldReshareCap")} htmlFor="li-reshare"><input id="li-reshare" type="number" value={f.reshareCap} onChange={set("reshareCap")} /></Field>
            <Field label={t("listeners.fieldMaxCards")} htmlFor="li-maxc"><input id="li-maxc" type="number" value={f.maxCards} onChange={set("maxCards")} /></Field>
            <Field label={t("listeners.fieldMaxInflight")} htmlFor="li-infl"><input id="li-infl" type="number" value={f.maxInflight} onChange={set("maxInflight")} /></Field>
            <Field label={t("listeners.fieldMaxPerIp")} htmlFor="li-perip"><input id="li-perip" type="number" value={f.maxPerIp} onChange={set("maxPerIp")} /></Field>
            <Field label={t("listeners.fieldIdleTimeoutSec")} htmlFor="li-idle"><input id="li-idle" type="number" value={f.idleTimeoutSec} onChange={set("idleTimeoutSec")} /></Field>
          </div>
        </section>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("listeners.cancelBtn")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("listeners.saveBtn") : t("listeners.createBtn")}</button>
        </div>
      </form>
    </Dialog>
  );
}
