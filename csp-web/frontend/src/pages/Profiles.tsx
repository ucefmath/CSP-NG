import { useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseInteger } from "../api";
import { formatHexId, parseHexId } from "../hex";
import type { Profile } from "../types";
import { BulkBar, SelectBox, type BulkFieldDef } from "../components/BulkBar";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { useT, type MessageKey } from "../i18n";

function getBulkFields(t: (k: MessageKey, params?: Record<string, string | number>) => string): BulkFieldDef[] {
  return [
    { key: "enabled", label: t("profiles.bulkEnabled"), type: "boolean" as const },
    { key: "debug", label: t("profiles.bulkDebug"), type: "boolean" as const },
    { key: "cacheOnly", label: t("profiles.bulkCacheOnly"), type: "boolean" as const },
    { key: "requireProviderMatch", label: t("profiles.bulkRequireProviderMatch"), type: "boolean" as const, resettable: true, resetHint: "NULL" },
    { key: "checkEcmLength", label: t("profiles.bulkCheckEcmLength"), type: "boolean" as const, resettable: true, resetHint: "NULL" },
    { key: "maxCwWaitMs", label: t("profiles.bulkMaxCwWaitMs"), type: "number" as const },
    { key: "congestionLimitMs", label: t("profiles.bulkCongestionLimitMs"), type: "number" as const, resettable: true, resetHint: t("profiles.bulkResetHintInherited") },
    { key: "mismatchedCards", label: t("profiles.bulkMismatchedCards"), type: "boolean" as const },
    { key: "serviceConflicts", label: t("profiles.bulkServiceConflicts"), type: "number" as const },
    { key: "blockCaidMismatch", label: t("profiles.bulkBlockCaidMismatch"), type: "boolean" as const, resettable: true, resetHint: "NULL" },
    { key: "providerFilter", label: t("profiles.bulkProviderFilter"), type: "text" as const, resettable: true, resetHint: "NULL" },
    { key: "servicesFile", label: t("profiles.bulkServicesFile"), type: "text" as const, resettable: true, resetHint: "NULL" },
    { key: "servicesFileFormat", label: t("profiles.bulkServicesFileFormat"), type: "text" as const, resettable: true, resetHint: "NULL" },
  ];
}

export function ProfilesPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<Profile | null | "new">(null);
  const [deleting, setDeleting] = useState<Profile | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const bulkFields = useMemo(() => getBulkFields(t), [t]);

  const list = useMemo(() => {
    const all = snap.data?.profiles ?? [];
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((p) => p.name.toLowerCase().includes(q));
  }, [snap.data, filter]);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;

  const toggle = (name: string, v: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (v) n.add(name);
      else n.delete(name);
      return n;
    });

  const selectedItems = list.filter((p) => selected.has(p.name)).map((p) => ({ id: p.name, item: p }));

  async function doDelete(p: Profile) {
    try {
      const res = await apiFetch(`/api/admin/config/profiles/${encodeURIComponent(p.name)}?version=${p.version}`, {
        method: "DELETE",
      });
      if (!res.ok) {
        let b: unknown = null;
        try {
          b = await res.json();
        } catch {
          b = null;
        }
        throw { status: res.status, ...((b ?? {}) as object) };
      }
      push(t("profiles.toastDeleted"));
      snap.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>{t("profiles.title")}</h1>
        <input
          type="search"
          className="search-input"
          placeholder={t("profiles.searchPlaceholder")}
          aria-label={t("profiles.searchAria")}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          {t("profiles.createBtn")}
        </button>
      </div>
      <BulkBar
        resource="profiles"
        selected={selectedItems}
        fields={bulkFields}
        labelOf={(id) => id}
        onDone={() => {
          setSelected(new Set());
          snap.reload();
        }}
      />
      {!list.length ? (
        <Empty msg={snap.data?.profiles?.length ? t("profiles.emptyNoResult") : t("profiles.emptyNoProfiles")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>
                  <span className="sr-only">{t("profiles.thSelect")}</span>
                </th>
                <th>{t("profiles.thName")}</th>
                <th>{t("profiles.thState")}</th>
                <th>{t("profiles.thCaid")}</th>
                <th>{t("profiles.thNetwork")}</th>
                <th>{t("profiles.thMaxCwWait")}</th>
                <th>{t("profiles.thVersion")}</th>
                <th>{t("profiles.thActions")}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((p) => (
                <tr key={p.name}>
                  <td>
                    <SelectBox checked={selected.has(p.name)} onChange={(v) => toggle(p.name, v)} label={t("profiles.selectProfileAria", { name: p.name })} />
                  </td>
                  <td className="mono">{p.name}</td>
                  <td>
                    <span className={p.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {p.enabled ? t("profiles.badgeActive") : t("profiles.badgeDisabled")}
                    </span>
                  </td>
                  <td className="mono">{p.caId != null ? formatHexId(p.caId, "caid") : "—"}</td>
                  <td className="mono">{p.networkId ?? "—"}</td>
                  <td className="mono">{p.maxCwWaitMs ?? "—"}</td>
                  <td className="mono">v{p.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(p)}>
                      {t("profiles.editBtn")}
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(p)}>
                      {t("profiles.deleteBtn")}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <ProfileDialog
          existing={editing === "new" ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            snap.reload();
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t("profiles.dialogDeleteTitle")}
          desc={t("profiles.dialogDeleteDesc", { name: deleting.name, version: deleting.version })}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
    </div>
  );
}

function ProfileDialog({ existing, onClose, onSaved }: { existing: Profile | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    name: existing?.name ?? "",
    caId: formatHexId(existing?.caId ?? 0, "caid"),
    networkId: String(existing?.networkId ?? 0),
    maxCwWaitMs: String(existing?.maxCwWaitMs ?? 5000),
    congestionLimitMs: existing?.congestionLimitMs != null ? String(existing.congestionLimitMs) : "",
    serviceConflicts: String(existing?.serviceConflicts ?? 0),
    servicesFile: existing?.servicesFile ?? "",
    providerFilter: existing?.providerFilter ?? "",
    servicesFileFormat: existing?.servicesFileFormat ?? "",
    enabled: existing?.enabled ?? true,
    debug: existing?.debug ?? false,
    cacheOnly: existing?.cacheOnly ?? false,
    mismatchedCards: existing?.mismatchedCards ?? false,
    requireProviderMatch: existing?.requireProviderMatch ?? false,
    checkEcmLength: existing?.checkEcmLength ?? false,
    blockCaidMismatch: existing?.blockCaidMismatch ?? false,
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const name = f.name.trim();
    if (!name) return setErr(t("profiles.errNameRequired"));
    const caId = parseHexId(f.caId, "caid");
    const networkId = parseInteger(f.networkId, NaN);
    const maxCwWaitMs = parseInteger(f.maxCwWaitMs, NaN);
    const serviceConflicts = parseInteger(f.serviceConflicts, NaN);
    if (Number.isNaN(caId)) return setErr(t("profiles.errCaIdFormat"));
    if (Number.isNaN(networkId)) return setErr(t("profiles.errNetworkIdFormat"));
    if (!(maxCwWaitMs > 100)) return setErr(t("profiles.errMaxCwWaitRange"));
    if (Number.isNaN(serviceConflicts)) return setErr(t("profiles.errServiceConflictsFormat"));
    const congStr = f.congestionLimitMs.trim();
    const congestionLimitMs = congStr === "" ? null : parseInteger(congStr, NaN);
    if (congStr !== "" && Number.isNaN(congestionLimitMs)) return setErr(t("profiles.errCongestionLimitFormat"));
    setBusy(true);
    try {
      const payload = {
        enabled: f.enabled,
        debug: f.debug,
        cacheOnly: f.cacheOnly,
        caId,
        networkId,
        requireProviderMatch: f.requireProviderMatch,
        checkEcmLength: f.checkEcmLength,
        maxCwWaitMs,
        congestionLimitMs,
        mismatchedCards: f.mismatchedCards,
        serviceConflicts,
        servicesFile: f.servicesFile.trim() || null,
        providerFilter: f.providerFilter.trim() || null,
        servicesFileFormat: f.servicesFileFormat.trim() || null,
        blockCaidMismatch: f.blockCaidMismatch,
        version: existing?.version ?? 1,
      };
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/profiles/${encodeURIComponent(name)}`, {
        method: "PUT",
        body: payload,
      });
      push(isEdit ? t("profiles.toastUpdated", { version: body.version }) : t("profiles.toastCreated", { version: body.version }));
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
    <Dialog title={isEdit ? t("profiles.dialogEditTitle") : t("profiles.dialogCreateTitle")} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label={t("profiles.sectionEssential")}>
          <h3>{t("profiles.sectionEssential")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("profiles.fieldName")} htmlFor="pf-name">
              <input id="pf-name" value={f.name} onChange={set("name")} disabled={isEdit} required />
            </Field>
            <Field label={t("profiles.fieldCaId")} htmlFor="pf-caId">
              <input id="pf-caId" type="text" value={f.caId} onChange={set("caId")} placeholder={t("profiles.placeholderCaId")} required />
            </Field>
            <Field label={t("profiles.fieldNetworkId")} htmlFor="pf-net">
              <input id="pf-net" type="number" value={f.networkId} onChange={set("networkId")} required />
            </Field>
            <Field label={t("profiles.fieldMaxCwWaitMs")} htmlFor="pf-wait">
              <input id="pf-wait" type="number" value={f.maxCwWaitMs} onChange={set("maxCwWaitMs")} required />
            </Field>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.enabled)} onChange={set("enabled")} /><span>{t("profiles.fieldEnabled")}</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.cacheOnly)} onChange={set("cacheOnly")} /><span>{t("profiles.fieldCacheOnly")}</span></label>
          </div>
        </section>
        <section aria-label={t("profiles.sectionAdvanced")} className="section section-advanced">
          <h3>{t("profiles.sectionAdvanced")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("profiles.fieldCongestionLimit")} htmlFor="pf-cong">
              <input id="pf-cong" type="number" value={String(f.congestionLimitMs)} onChange={set("congestionLimitMs")} />
            </Field>
            <Field label={t("profiles.fieldServiceConflicts")} htmlFor="pf-sc">
              <input id="pf-sc" type="number" value={f.serviceConflicts} onChange={set("serviceConflicts")} required />
            </Field>
            <Field label={t("profiles.fieldServicesFile")} htmlFor="pf-sf">
              <input id="pf-sf" value={f.servicesFile} onChange={set("servicesFile")} />
            </Field>
            <Field label={t("profiles.fieldProviderFilter")} htmlFor="pf-pf">
              <input id="pf-pf" value={f.providerFilter} onChange={set("providerFilter")} />
            </Field>
            <Field label={t("profiles.fieldServicesFileFormat")} htmlFor="pf-sff">
              <input id="pf-sff" value={f.servicesFileFormat} onChange={set("servicesFileFormat")} />
            </Field>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.debug)} onChange={set("debug")} /><span>{t("profiles.fieldDebug")}</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.mismatchedCards)} onChange={set("mismatchedCards")} /><span>{t("profiles.fieldMismatchedCards")}</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.requireProviderMatch)} onChange={set("requireProviderMatch")} /><span>{t("profiles.fieldRequireProviderMatch")}</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.checkEcmLength)} onChange={set("checkEcmLength")} /><span>{t("profiles.fieldCheckEcmLength")}</span></label>
            <label className="check-row"><input type="checkbox" checked={Boolean(f.blockCaidMismatch)} onChange={set("blockCaidMismatch")} /><span>{t("profiles.fieldBlockCaidMismatch")}</span></label>
          </div>
          {isEdit && <p className="muted small">{t("profiles.versionNotice", { version: existing.version })}</p>}
        </section>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            {t("profiles.cancelBtn")}
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "…" : isEdit ? t("profiles.saveBtn") : t("profiles.createSubmitBtn")}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
