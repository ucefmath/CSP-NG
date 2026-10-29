import { useEffect, useState } from "react";
import { Link } from "react-router";
import { apiFetch, apiJson, cleanHex, describeError, formatDate, formatSid, parseInteger } from "../api";
import { Dialog, Empty, Field, Loading, useToast } from "../components/ui";
import { formatHexId, parseHexId, type HexKind } from "../hex";
import { useT, type MessageKey } from "../i18n";

type Rec = Record<string, unknown>;
const str = (v: unknown) => (v === null || v === undefined ? "" : String(v));
const hex = (n: number, kind: HexKind) => formatHexId(n, kind);

export function DcwPage() {
  const t = useT();
  const [tab, setTab] = useState<"politique" | "regles" | "mappings" | "observabilite">("politique");
  const tabs = [
    { id: "politique" as const, label: t("dcw.tabPolicy") },
    { id: "regles" as const, label: t("dcw.tabRules") },
    { id: "mappings" as const, label: t("dcw.tabMappings") },
    { id: "observabilite" as const, label: t("dcw.tabObservability") },
  ];
  return (
    <div className="card">
      <h1>{t("dcw.title")}</h1>
      <div className="tabs" role="tablist" aria-label={t("dcw.tabsAria")}>
        {tabs.map((tItem) => (
          <button
            key={tItem.id}
            type="button"
            role="tab"
            aria-selected={tab === tItem.id}
            className={tab === tItem.id ? "tab-btn active" : "tab-btn"}
            onClick={() => setTab(tItem.id)}
          >
            {tItem.label}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        {tab === "politique" && <PolicyTab />}
        {tab === "regles" && <RulesTab />}
        {tab === "mappings" && <MappingsTab />}
        {tab === "observabilite" && <ObsTab />}
      </div>
    </div>
  );
}

const checksumOpts = (t: (k: MessageKey) => string) => [
  { value: "inherit", label: t("dcw.checksumOptInherit") },
  { value: "require_valid", label: t("dcw.checksumOptRequireValid") },
  { value: "ignore", label: t("dcw.checksumOptIgnore") },
  { value: "reject_valid", label: t("dcw.checksumOptRejectValid") },
];
const repairOpts = (t: (k: MessageKey) => string) => [
  { value: "off", label: t("dcw.repairOptOff") },
  { value: "observe", label: t("dcw.repairOptObserve") },
  { value: "enforce", label: t("dcw.repairOptEnforce") },
];

function PolicyTab() {
  const t = useT();
  const { push } = useToast();
  const [policy, setPolicy] = useState<Rec | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [f, setF] = useState({
    enabled: true,
    adaptiveRoutingEnabled: false,
    autoObserveEnabled: false,
    zeroCountingEnabled: false,
    allowZeroHalf: false,
    defaultChecksumMode: "inherit",
    repairMode: "off",
    version: "1",
  });
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState("");
  const [conflict, setConflict] = useState(false);

  const load = async () => {
    setError(null);
    setConflict(false);
    try {
      const { body } = await apiJson<Rec>("/api/admin/dcw/policy");
      setPolicy(body);
      setF({
        enabled: Boolean(body.enabled),
        adaptiveRoutingEnabled: Boolean(body.adaptiveRoutingEnabled),
        autoObserveEnabled: Boolean(body.autoObserveEnabled),
        zeroCountingEnabled: Boolean(body.zeroCountingEnabled),
        allowZeroHalf: Boolean(body.allowZeroHalf),
        defaultChecksumMode: str(body.defaultChecksumMode) || "inherit",
        repairMode: str(body.repairMode) || "off",
        version: str(body.version ?? 1),
      });
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!policy && !error) return <Loading />;
  if (error) {
    return (
      <div role="alert">
        <p className="field-error">{t("dcw.policyLoadError", { error: describeError(error) })}</p>
        <button type="button" className="btn btn-sm" onClick={load}>{t("dcw.retryBtn")}</button>
      </div>
    );
  }

  const dangerous = f.repairMode === "enforce" || f.defaultChecksumMode === "reject_valid";

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg("");
    setConflict(false);
    const version = parseInteger(f.version, NaN);
    if (!(version >= 1)) {
      setMsg(t("dcw.policyErrVersion"));
      return;
    }
    setBusy(true);
    try {
      await apiJson("/api/admin/dcw/policy", {
        method: "PUT",
        body: {
          enabled: f.enabled,
          adaptiveRoutingEnabled: f.adaptiveRoutingEnabled,
          autoObserveEnabled: f.autoObserveEnabled,
          zeroCountingEnabled: f.zeroCountingEnabled,
          defaultChecksumMode: f.defaultChecksumMode,
          repairMode: f.repairMode,
          allowZeroHalf: f.allowZeroHalf,
          version,
        },
      });
      push(t("dcw.policyToastSaved"));
      load();
    } catch (e) {
      if ((e as { status?: number }).status === 409) {
        setConflict(true);
        setMsg(t("dcw.policyConflictMsg"));
        push(t("dcw.policyConflictToast"), "err");
      } else {
        const m = describeError(e);
        setMsg(m);
        push(m, "err");
      }
    } finally {
      setBusy(false);
    }
  }

  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  const checksumOptions = checksumOpts(t);
  const repairOptions = repairOpts(t);

  return (
    <form onSubmit={submit} noValidate>
      <div className="toolbar">
        <h2>{t("dcw.policyHeading")}</h2>
        <span className="badge badge-muted mono">v{str(policy?.version)}</span>
        <span className="muted small">{t("dcw.policyUpdated", { date: formatDate(Number(policy?.updatedAtMs ?? 0)) })}</span>
        <span className="muted small">{t("dcw.policyCounters", { rules: str(policy?.rulesCount ?? "—"), mappings: str(policy?.mappingsCount ?? "—") })}</span>
      </div>
      <p className="muted small">{t("dcw.policyFeaturesDesc")}</p>
      <div className="form-grid cols-2">
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>{t("dcw.policyFieldEnabled")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.adaptiveRoutingEnabled} onChange={set("adaptiveRoutingEnabled")} /><span>{t("dcw.policyFieldAdaptiveRouting")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.autoObserveEnabled} onChange={set("autoObserveEnabled")} /><span>{t("dcw.policyFieldAutoObserve")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.zeroCountingEnabled} onChange={set("zeroCountingEnabled")} /><span>{t("dcw.policyFieldZeroCounting")}</span></label>
        <label className="check-row"><input type="checkbox" checked={f.allowZeroHalf} onChange={set("allowZeroHalf")} /><span>{t("dcw.policyFieldAllowZeroHalf")}</span></label>
      </div>
      <div className="form-grid cols-2">
        <div className="field">
          <label htmlFor="dcw-checksum">{t("dcw.policyFieldChecksumMode")}</label>
          <select id="dcw-checksum" value={f.defaultChecksumMode} onChange={set("defaultChecksumMode")} required>
            {checksumOptions.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="dcw-repair">{t("dcw.policyFieldRepairMode")}</label>
          <select id="dcw-repair" value={f.repairMode} onChange={set("repairMode")} required>
            {repairOptions.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <Field label={t("dcw.policyFieldVersion")} htmlFor="dcw-ver">
          <input id="dcw-ver" type="number" value={f.version} onChange={set("version")} required />
        </Field>
      </div>
      {dangerous && (
        <div className="alert alert-warn" role="alert">
          <strong>{t("dcw.policyDangerousLabel")}</strong>
          {t("dcw.policyDangerousDesc")}
        </div>
      )}
      {msg && (
        <p className={conflict ? "alert alert-error" : "muted small"} role={conflict ? "alert" : "status"}>
          {msg}{" "}
          {conflict && (
            <button type="button" className="btn btn-sm" onClick={load}>
              {t("dcw.policyReloadConflictBtn")}
            </button>
          )}
        </p>
      )}
      <div className="toolbar">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>
          {busy ? "…" : t("dcw.policySaveBtn")}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={load}>
          {t("dcw.policyReloadBtn")}
        </button>
      </div>
    </form>
  );
}

interface DcwRule {
  id: number;
  profile?: string | null;
  caid?: number;
  provider?: number | null;
  sid?: number;
  sourceId?: string | null;
  digestSha256?: string | null;
  action?: string;
  checksumMode?: string;
  notes?: string | null;
  enabled?: boolean;
  version: number;
}

function RulesTab() {
  const t = useT();
  const { push } = useToast();
  const [rules, setRules] = useState<DcwRule[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [dlg, setDlg] = useState<DcwRule | null | "new">(null);

  const load = async () => {
    try {
      const { body } = await apiJson<DcwRule[]>("/api/admin/dcw/rules");
      setRules(Array.isArray(body) ? body : []);
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function remove(r: DcwRule) {
    try {
      const res = await apiFetch(`/api/admin/dcw/rules/${r.id}`, { method: "DELETE" });
      if (!res.ok) throw new Error(res.statusText);
      push(t("dcw.ruleToastDeleted", { id: r.id }));
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  if (error) {
    return (
      <div role="alert">
        <p className="field-error">{t("dcw.ruleLoadError", { error: describeError(error) })}</p>
        <button type="button" className="btn btn-sm" onClick={load}>{t("dcw.retryBtn")}</button>
      </div>
    );
  }
  const scopeAll = t("dcw.scopeAll");
  return (
    <div>
      <div className="toolbar">
        <h2>{t("dcw.rulesHeading")}</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setDlg("new")}>{t("dcw.ruleNewBtn")}</button>
      </div>
      {!rules.length ? (
        <Empty msg={t("dcw.rulesEmpty")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("dcw.thId")}</th>
                <th>{t("dcw.thScope")}</th>
                <th>{t("dcw.thSource")}</th>
                <th>{t("dcw.thAction")}</th>
                <th>{t("dcw.thChecksum")}</th>
                <th>{t("dcw.thDigest")}</th>
                <th>{t("dcw.thState")}</th>
                <th>{t("dcw.thVersion")}</th>
                <th>{t("dcw.thActions")}</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id}>
                  <td className="mono">#{r.id}</td>
                  <td className="mono small">
                    {r.profile || "*"} / {r.caid ? hex(r.caid, "caid") : scopeAll} /{" "}
                    {r.provider != null ? hex(r.provider, "provid") : scopeAll} / {r.sid ? formatSid(r.sid) : scopeAll}
                  </td>
                  <td>{r.sourceId || "—"}</td>
                  <td>
                    <span className={r.action === "block" ? "badge badge-danger" : r.action === "observe" ? "badge badge-warn" : "badge badge-muted"}>
                      {r.action}
                    </span>
                  </td>
                  <td><span className="badge badge-muted mono">{r.checksumMode}</span></td>
                  <td className="mono small" title={r.digestSha256 || ""}>
                    {r.digestSha256 ? `${r.digestSha256.substring(0, 10)}…${r.digestSha256.substring(58)}` : "—"}
                  </td>
                  <td>
                    <span className={r.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {r.enabled ? t("dcw.ruleActive") : t("dcw.ruleInactive")}
                    </span>
                  </td>
                  <td className="mono">v{r.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDlg(r)}>{t("dcw.ruleEditBtn")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(r)}>{t("dcw.ruleDeleteBtn")}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dlg && <RuleDialog existing={dlg === "new" ? null : dlg} onClose={() => setDlg(null)} onSaved={() => { setDlg(null); load(); }} />}
    </div>
  );
}

function RuleDialog({ existing, onClose, onSaved }: { existing: DcwRule | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    profile: existing?.profile ?? "",
    caid: formatHexId(existing?.caid ?? 0, 'caid', { zeroAsWildcard: true }),
    provider: existing?.provider != null ? formatHexId(existing.provider, 'provid') : '',
    sid: formatHexId(existing?.sid ?? 0, 'sid', { zeroAsWildcard: true }),
    sourceId: existing?.sourceId ?? "",
    notes: existing?.notes ?? "",
    action: existing?.action ?? "none",
    checksumMode: existing?.checksumMode ?? "ignore",
    digestSha256: existing?.digestSha256 ?? "",
    badDcwHex: "",
    enabled: existing?.enabled ?? true,
    version: String(existing?.version ?? 1),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const caid = parseHexId(f.caid, 'caid');
    if (Number.isNaN(caid)) return setErr(t("dcw.ruleErrCaid"));
    let provider: number | null = null;
    if (f.provider.trim()) {
      const p = parseHexId(f.provider, 'provid');
      if (Number.isNaN(p)) return setErr(t("dcw.ruleErrProvider"));
      provider = p;
    }
    const sid = f.sid.trim() ? parseHexId(f.sid, 'sid') : 0;
    if (Number.isNaN(sid)) return setErr(t("dcw.ruleErrSid"));
    if (f.notes && f.notes.length > 255) return setErr(t("dcw.ruleErrNotesMax"));
    const cleanDigest = cleanHex(f.digestSha256);
    const cleanBad = cleanHex(f.badDcwHex);
    if (f.action === "none") {
      if (cleanDigest || cleanBad) return setErr(t("dcw.ruleErrNoneNoDigest"));
      if (f.checksumMode === "inherit") return setErr(t("dcw.ruleErrNoneInheritNoEffect"));
    } else {
      if (!isEdit && !cleanDigest && !cleanBad)
        return setErr(t("dcw.ruleErrActionRequiresDigest", { action: f.action }));
      if (cleanDigest && cleanBad) return setErr(t("dcw.ruleErrDigestOrBadDcwExclusive"));
      if (cleanDigest && !/^[0-9a-fA-F]{64}$/.test(cleanDigest))
        return setErr(t("dcw.ruleErrDigestInvalid"));
      if (cleanBad && !/^[0-9a-fA-F]{32}$/.test(cleanBad))
        return setErr(t("dcw.ruleErrBadDcwInvalid"));
    }
    const version = parseInteger(f.version, NaN);
    if (isEdit && !(version >= 1)) return setErr(t("dcw.ruleErrVersion"));
    setBusy(true);
    try {
      const payload: Rec = {
        profile: f.profile.trim() || null,
        caid,
        provider,
        sid,
        sourceId: f.sourceId.trim() || null,
        digestSha256: cleanDigest || null,
        badDcwHex: cleanBad || null,
        action: f.action,
        checksumMode: f.checksumMode,
        notes: f.notes.trim() || null,
        enabled: f.enabled,
      };
      if (isEdit) {
        await apiJson(`/api/admin/dcw/rules/${existing.id}`, { method: "PUT", body: { ...payload, version } });
        push(t("dcw.ruleToastUpdated", { id: existing.id }));
      } else {
        await apiJson("/api/admin/dcw/rules", { method: "POST", body: payload });
        push(t("dcw.ruleToastCreated"));
      }
      onSaved();
    } catch (e) {
      const m =
        (e as { status?: number }).status === 409
          ? t("dcw.ruleConflictMsg")
          : describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? t("dcw.ruleDialogEditTitle", { id: existing.id }) : t("dcw.ruleDialogCreateTitle")} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <p className="muted small">
          {t("dcw.ruleDialogDesc")}
        </p>
        <section aria-label={t("dcw.ruleSectionScopeAria")}>
          <h3>{t("dcw.ruleSectionScopeHeading")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("dcw.ruleFieldProfile")} htmlFor="dr-profile"><input id="dr-profile" value={f.profile} onChange={set("profile")} /></Field>
            <Field label={t("dcw.ruleFieldCaid")} htmlFor="dr-caid"><input id="dr-caid" type="text" value={f.caid} onChange={set("caid")} placeholder={t("dcw.rulePlaceholderCaid")} required /></Field>
            <Field label={t("dcw.ruleFieldProvider")} htmlFor="dr-prov"><input id="dr-prov" type="text" value={f.provider} onChange={set("provider")} placeholder={t("dcw.rulePlaceholderProvider")} /></Field>
            <Field label={t("dcw.ruleFieldSid")} htmlFor="dr-sid"><input id="dr-sid" type="text" value={f.sid} onChange={set("sid")} placeholder={t("dcw.rulePlaceholderSid")} /></Field>
            <Field label={t("dcw.ruleFieldSourceId")} htmlFor="dr-src"><input id="dr-src" value={f.sourceId} onChange={set("sourceId")} /></Field>
            <Field label={t("dcw.ruleFieldNotes")} htmlFor="dr-notes"><input id="dr-notes" value={f.notes} onChange={set("notes")} /></Field>
          </div>
        </section>
        <section aria-label={t("dcw.ruleSectionActionAria")} className="section">
          <h3>{t("dcw.ruleSectionActionHeading")}</h3>
          <div className="form-grid cols-2">
            <div className="field">
              <label htmlFor="dr-action">{t("dcw.ruleFieldAction")}</label>
              <select id="dr-action" value={f.action} onChange={set("action")} required>
                <option value="none">{t("dcw.ruleActionOptNone")}</option>
                <option value="block">{t("dcw.ruleActionOptBlock")}</option>
                <option value="observe">{t("dcw.ruleActionOptObserve")}</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="dr-checksum">{t("dcw.ruleFieldChecksumMode")}</label>
              <select id="dr-checksum" value={f.checksumMode} onChange={set("checksumMode")} required>
                <option value="inherit">{t("dcw.ruleChecksumOptInherit")}</option>
                <option value="require_valid">{t("dcw.ruleChecksumOptRequireValid")}</option>
                <option value="ignore">{t("dcw.ruleChecksumOptIgnore")}</option>
                <option value="reject_valid">{t("dcw.ruleChecksumOptRejectValid")}</option>
              </select>
            </div>
            <Field label={t("dcw.ruleFieldDigest")} htmlFor="dr-digest">
              <input id="dr-digest" value={f.digestSha256} onChange={set("digestSha256")} autoComplete="off" spellCheck={false} placeholder={isEdit ? t("dcw.rulePlaceholderDigestKeep") : ""} />
            </Field>
            <Field label={t("dcw.ruleFieldBadDcw")} htmlFor="dr-bad">
              <input id="dr-bad" type="password" value={f.badDcwHex} onChange={set("badDcwHex")} autoComplete="new-password" spellCheck={false} />
            </Field>
            <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>{t("dcw.ruleFieldEnabled")}</span></label>
            {isEdit && (
              <Field label={t("dcw.ruleFieldVersion")} htmlFor="dr-ver"><input id="dr-ver" type="number" value={f.version} onChange={set("version")} required /></Field>
            )}
          </div>
        </section>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("dcw.ruleCancelBtn")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("dcw.ruleSaveBtn") : t("dcw.ruleCreateBtn")}</button>
        </div>
      </form>
    </Dialog>
  );
}

interface DcwMapping {
  id: number;
  profile?: string;
  caid?: number;
  provider?: number;
  targetCaid?: number;
  targetProvider?: number;
  sourceSid?: number;
  targetSid?: number;
  approved?: boolean;
  enabled?: boolean;
  expiresAtMs?: number;
  version: number;
}

function formatCaidProvider(caid?: number, provider?: number): string {
  const c = caid != null ? hex(caid, "caid") : "—";
  const p = provider != null ? hex(provider, "provid") : "—";
  return `${c}:${p}`;
}

function formatCaidProviderMapping(m: DcwMapping): string {
  const src = formatCaidProvider(m.caid, m.provider);
  const tgtCaid = m.targetCaid ?? m.caid;
  const tgtProv = m.targetProvider ?? m.provider;
  const hasDiff = (m.targetCaid != null && m.targetCaid !== m.caid) || (m.targetProvider != null && m.targetProvider !== m.provider);
  if (hasDiff) {
    const tgt = formatCaidProvider(tgtCaid, tgtProv);
    return `${src} → ${tgt}`;
  }
  return src;
}

function MappingsTab() {
  const t = useT();
  const { push } = useToast();
  const [maps, setMaps] = useState<DcwMapping[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [dlg, setDlg] = useState<DcwMapping | null | "new">(null);

  const load = async () => {
    try {
      const { body } = await apiJson<DcwMapping[]>("/api/admin/dcw/mappings");
      setMaps(Array.isArray(body) ? body : []);
    } catch (e) {
      setError(e);
    }
  };
  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function remove(m: DcwMapping) {
    try {
      const res = await apiFetch(`/api/admin/dcw/mappings/${m.id}`, { method: "DELETE" });
      if (!res.ok) throw new Error(res.statusText);
      push(t("dcw.mappingToastDeleted", { id: m.id }));
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  if (error) {
    return (
      <div role="alert">
        <p className="field-error">{t("dcw.mappingLoadError", { error: describeError(error) })}</p>
        <button type="button" className="btn btn-sm" onClick={load}>{t("dcw.retryBtn")}</button>
      </div>
    );
  }
  return (
    <div>
      <div className="toolbar">
        <h2>{t("dcw.mappingsHeading")}</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setDlg("new")}>{t("dcw.mappingNewBtn")}</button>
      </div>
      <p className="muted small">
        {t("dcw.mappingsDesc")}
      </p>
      {!maps.length ? (
        <Empty msg={t("dcw.mappingsEmpty")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("dcw.thId")}</th>
                <th>{t("dcw.thProfile")}</th>
                <th>{t("dcw.thCaidProviderTarget")}</th>
                <th>{t("dcw.thSourceTarget")}</th>
                <th>{t("dcw.thApproved")}</th>
                <th>{t("dcw.thState")}</th>
                <th>{t("dcw.thExpires")}</th>
                <th>{t("dcw.thVersion")}</th>
                <th>{t("dcw.thActions")}</th>
              </tr>
            </thead>
            <tbody>
              {maps.map((m) => (
                <tr key={m.id}>
                  <td className="mono">#{m.id}</td>
                  <td>{m.profile}</td>
                  <td className="mono small">{formatCaidProviderMapping(m)}</td>
                  <td className="mono small">
                    {m.sourceSid === 0 && m.targetSid === 0 ? t("dcw.mappingAllSids") : `${m.sourceSid != null ? formatSid(m.sourceSid) : "?"} → ${m.targetSid != null ? formatSid(m.targetSid) : "?"}`}
                  </td>
                  <td>{m.approved ? t("dcw.mappingApprovedYes") : t("dcw.mappingApprovedNo")}</td>
                  <td>
                    <span className={m.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {m.enabled ? t("dcw.mappingActive") : t("dcw.mappingInactive")}
                    </span>
                  </td>
                  <td className="small">{m.expiresAtMs ? formatDate(m.expiresAtMs) : t("dcw.mappingPermanent")}</td>
                  <td className="mono">v{m.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDlg(m)}>{t("dcw.mappingEditBtn")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(m)}>{t("dcw.mappingDeleteBtn")}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dlg && <MappingDialog existing={dlg === "new" ? null : dlg} onClose={() => setDlg(null)} onSaved={() => { setDlg(null); load(); }} />}
    </div>
  );
}

function epochToLocal(ms: number): string {
  if (!ms || ms <= 0) return "";
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function MappingDialog({ existing, onClose, onSaved }: { existing: DcwMapping | null; onClose: () => void; onSaved: () => void }) {
  const t = useT();
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    profile: existing?.profile ?? "",
    caid: existing?.caid != null ? formatHexId(existing.caid, "caid") : "",
    provider: existing?.provider != null ? formatHexId(existing.provider, "provid", { zeroAsWildcard: true }) : "0",
    targetCaid: existing?.targetCaid != null ? formatHexId(existing.targetCaid, "caid") : "",
    targetProvider: existing?.targetProvider != null ? formatHexId(existing.targetProvider, "provid") : "",
    sourceSid: existing?.sourceSid != null ? formatHexId(existing.sourceSid, "sid", { zeroAsWildcard: true }) : "",
    targetSid: existing?.targetSid != null ? formatHexId(existing.targetSid, "sid", { zeroAsWildcard: true }) : "",
    approved: existing?.approved ?? true,
    enabled: existing?.enabled ?? true,
    expiresAt: existing?.expiresAtMs ? epochToLocal(existing.expiresAtMs) : "",
    version: String(existing?.version ?? 1),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!f.profile.trim() || f.profile.length > 64) return setErr(t("dcw.mappingErrProfile"));
    const caid = parseHexId(f.caid, "caid");
    if (!(caid >= 1 && caid <= 65535)) return setErr(t("dcw.mappingErrCaid"));
    const provider = parseHexId(f.provider, "provid");
    if (!(provider >= 0 && provider <= 16777215)) return setErr(t("dcw.mappingErrProvider"));

    let targetCaid: number | undefined = undefined;
    if (f.targetCaid.trim()) {
      const parsed = parseHexId(f.targetCaid, "caid");
      if (!(parsed >= 1 && parsed <= 65535)) return setErr(t("dcw.mappingErrTargetCaid"));
      targetCaid = parsed;
    }

    let targetProvider: number | undefined = undefined;
    if (f.targetProvider.trim()) {
      const parsed = parseHexId(f.targetProvider, "provid");
      if (!(parsed >= 0 && parsed <= 16777215)) return setErr(t("dcw.mappingErrTargetProvider"));
      targetProvider = parsed;
    }

    // Un CAID cible différent change l'espace de providers : l'héritage silencieux du provider
    // source est interdit (bug TNTSAT : 0500:30B00 → 1883 stocké avec provider cible 000000).
    if (targetCaid !== undefined && targetCaid !== caid && targetProvider === undefined) {
      return setErr(t("dcw.mappingErrTargetProviderRequired"));
    }

    const sourceSid = parseHexId(f.sourceSid, "sid");
    if (isNaN(sourceSid) || sourceSid < 0 || sourceSid > 65535) return setErr(t("dcw.mappingErrSourceSid"));
    const targetSid = parseHexId(f.targetSid, "sid");
    if (isNaN(targetSid) || targetSid < 0 || targetSid > 65535) return setErr(t("dcw.mappingErrTargetSid"));

    const isGlobalSid = sourceSid === 0 && targetSid === 0;
    const isSpecificSid = sourceSid >= 1 && sourceSid <= 65535 && targetSid >= 1 && targetSid <= 65535;
    if (!isGlobalSid && !isSpecificSid) {
      return setErr(t("dcw.mappingErrSidGlobalOrSpecific"));
    }

    const effectiveTargetCaid = targetCaid ?? caid;
    const effectiveTargetProv = targetProvider ?? provider;
    if (caid === effectiveTargetCaid && provider === effectiveTargetProv && sourceSid === targetSid) {
      return setErr(t("dcw.mappingErrSidDiff"));
    }

    const version = parseInteger(f.version, NaN);
    if (isEdit && !(version >= 1)) return setErr(t("dcw.mappingErrVersion"));
    setBusy(true);
    try {
      const payload: Rec = {
        profile: f.profile.trim(),
        caid,
        provider,
        sourceSid,
        targetSid,
        approved: f.approved,
        enabled: f.enabled,
        expiresAtMs: f.expiresAt.trim() ? new Date(f.expiresAt).getTime() : 0,
      };
      if (targetCaid !== undefined && targetCaid !== caid) {
        payload.targetCaid = targetCaid;
      }
      if (targetProvider !== undefined && targetProvider !== provider) {
        payload.targetProvider = targetProvider;
      }
      if (isEdit) {
        await apiJson(`/api/admin/dcw/mappings/${existing.id}`, { method: "PUT", body: { ...payload, version } });
        push(t("dcw.mappingToastUpdated", { id: existing.id }));
      } else {
        await apiJson("/api/admin/dcw/mappings", { method: "POST", body: payload });
        push(t("dcw.mappingToastCreated"));
      }
      onSaved();
    } catch (e) {
      const m =
        (e as { status?: number }).status === 409
          ? t("dcw.mappingConflictMsg")
          : describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? t("dcw.mappingDialogEditTitle", { id: existing.id }) : t("dcw.mappingDialogCreateTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <div className="form-grid cols-2">
          <Field label={t("dcw.mappingFieldProfile")} htmlFor="dm-profile"><input id="dm-profile" value={f.profile} onChange={set("profile")} required /></Field>
          <Field label={t("dcw.mappingFieldCaid")} htmlFor="dm-caid"><input id="dm-caid" type="text" value={f.caid} onChange={set("caid")} required placeholder={t("dcw.mappingPlaceholderCaid")} /></Field>
          <Field label={t("dcw.mappingFieldProvider")} htmlFor="dm-prov"><input id="dm-prov" type="text" value={f.provider} onChange={set("provider")} required placeholder={t("dcw.mappingPlaceholderProvider")} /></Field>
          <Field label={t("dcw.mappingFieldExpires")} htmlFor="dm-exp"><input id="dm-exp" type="datetime-local" value={f.expiresAt} onChange={set("expiresAt")} /></Field>
          <div className="field">
            <label id="lbl-dm-tcaid">{t("dcw.mappingFieldTargetCaid")}</label>
            <input id="dm-tcaid" type="text" value={f.targetCaid} onChange={set("targetCaid")} placeholder={t("dcw.mappingPlaceholderTargetCaid")} />
          </div>
          <div className="field">
            <label id="lbl-dm-tprov">{t("dcw.mappingFieldTargetProvider")}</label>
            <input id="dm-tprov" type="text" value={f.targetProvider} onChange={set("targetProvider")} placeholder={t("dcw.mappingPlaceholderTargetProvider")} />
          </div>
          <Field label={t("dcw.mappingFieldSourceSid")} htmlFor="dm-src"><input id="dm-src" type="text" value={f.sourceSid} onChange={set("sourceSid")} required placeholder={t("dcw.mappingPlaceholderSidGlobal")} /></Field>
          <Field label={t("dcw.mappingFieldTargetSid")} htmlFor="dm-tgt"><input id="dm-tgt" type="text" value={f.targetSid} onChange={set("targetSid")} required placeholder={t("dcw.mappingPlaceholderSidGlobal")} /></Field>
          <label className="check-row"><input type="checkbox" checked={f.approved} onChange={set("approved")} /><span>{t("dcw.mappingFieldApproved")}</span></label>
          <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>{t("dcw.mappingFieldEnabled")}</span></label>
          {isEdit && (
            <Field label={t("dcw.mappingFieldVersion")} htmlFor="dm-ver"><input id="dm-ver" type="number" value={f.version} onChange={set("version")} required /></Field>
          )}
        </div>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("dcw.mappingCancelBtn")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("dcw.mappingSaveBtn") : t("dcw.mappingCreateBtn")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function ObsTab() {
  const t = useT();
  const [stats, setStats] = useState<Rec | null>(null);
  const [anomalies, setAnomalies] = useState<Rec[] | null>(null);
  const [candidates, setCandidates] = useState<Rec[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, a, c] = await Promise.allSettled([
        apiJson<Rec>("/api/admin/dcw/stats"),
        apiJson<Rec[]>("/api/admin/dcw/anomalies"),
        apiJson<Rec[]>("/api/admin/dcw/candidates"),
      ]);
      setStats(s.status === "fulfilled" ? (s.value.body as Rec) : null);
      setAnomalies(a.status === "fulfilled" ? (Array.isArray(a.value.body) ? a.value.body : []) : null);
      setCandidates(c.status === "fulfilled" ? (Array.isArray(c.value.body) ? c.value.body : []) : null);
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

  if (loading) return <p className="muted">{t("dcw.obsLoading")}</p>;
  if (error) {
    return (
      <div role="alert">
        <p className="field-error">{t("dcw.obsLoadError", { error: describeError(error) })}</p>
        <button type="button" className="btn btn-sm" onClick={load}>{t("dcw.retryBtn")}</button>
      </div>
    );
  }
  const anomVal = anomalies !== null ? String(anomalies.length) : stats?.anomalies !== undefined ? String(stats.anomalies) : "—";
  const candVal = candidates !== null ? String(candidates.length) : stats?.candidateLinks !== undefined ? String(stats.candidateLinks) : "—";
  return (
    <div>
      <h2>{t("dcw.obsHeading")}</h2>
      <p className="muted small">
        {t("dcw.obsPeerQualityPrefix")}{" "}
        <Link to="../cacheex">{t("dcw.obsCacheExLink")}</Link>
        {t("dcw.obsPeerQualitySuffix")}
      </p>
      <div className="kpi-grid">
        <div className="card kpi"><div className="kpi-label">{t("dcw.kpiAnomalies")}</div><div className="kpi-value mono">{anomVal}</div></div>
        <div className="card kpi"><div className="kpi-label">{t("dcw.kpiCandidates")}</div><div className="kpi-value mono">{candVal}</div></div>
        <div className="card kpi"><div className="kpi-label">{t("dcw.kpiMaxRules")}</div><div className="kpi-value mono">{stats?.maxRules !== undefined ? String(stats.maxRules) : "—"}</div></div>
        <div className="card kpi"><div className="kpi-label">{t("dcw.kpiMaxMappings")}</div><div className="kpi-value mono">{stats?.maxMappings !== undefined ? String(stats.maxMappings) : "—"}</div></div>
      </div>
      {stats && Boolean(stats.adaptiveScoresExposed) && stats.routing !== undefined && (
        <div className="card">
          <h3>{t("dcw.obsAdaptiveRoutingHeading")}</h3>
          <pre className="mono small patch-preview">{JSON.stringify(stats.routing, null, 2)}</pre>
        </div>
      )}
      <h3>{t("dcw.obsAnomaliesHeading", { count: anomalies?.length ?? "—" })}</h3>
      {anomalies === null ? (
        <Empty msg={t("dcw.obsAnomaliesUnavailable")} />
      ) : !anomalies.length ? (
        <Empty msg={t("dcw.obsAnomaliesEmpty")} />
      ) : (
        <ul className="small">
          {anomalies.map((a, i) => (
            <li key={str(a.id ?? i)}>
              {str(a.message ?? a.type ?? JSON.stringify(a))} — {formatDate(Number(a.atMs ?? a.timestamp ?? a.detectedAtMs ?? 0))}
            </li>
          ))}
        </ul>
      )}
      <h3>{t("dcw.obsCandidatesHeading", { count: candidates?.length ?? "—" })}</h3>
      {candidates === null ? (
        <Empty msg={t("dcw.obsCandidatesUnavailable")} />
      ) : !candidates.length ? (
        <Empty msg={t("dcw.obsCandidatesEmpty")} />
      ) : (
        <ul className="small">
          {candidates.map((c, i) => (
            <li key={str(c.id ?? i)}>
              <code className="mono">{str(c.connector ?? c.id ?? i)}</code> — {str(c.reason ?? "")}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
