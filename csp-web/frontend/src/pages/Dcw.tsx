import { useEffect, useState } from "react";
import { Link } from "react-router";
import { apiFetch, apiJson, cleanHex, describeError, formatDate, formatSid, parseInteger } from "../api";
import { Dialog, Empty, Field, Loading, useToast } from "../components/ui";

type Rec = Record<string, unknown>;
const str = (v: unknown) => (v === null || v === undefined ? "" : String(v));
const hex = (n: number, pad: number) => "0x" + Number(n).toString(16).toUpperCase().padStart(pad, "0");

export function DcwPage() {
  const [tab, setTab] = useState<"politique" | "regles" | "mappings" | "observabilite">("politique");
  const tabs = [
    { id: "politique" as const, label: "Politique" },
    { id: "regles" as const, label: "Règles" },
    { id: "mappings" as const, label: "Mappings" },
    { id: "observabilite" as const, label: "Observabilité" },
  ];
  return (
    <div className="card">
      <h1>Qualité DCW</h1>
      <div className="tabs" role="tablist" aria-label="Sections DCW">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={tab === t.id}
            className={tab === t.id ? "tab-btn active" : "tab-btn"}
            onClick={() => setTab(t.id)}
          >
            {t.label}
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

const CHECKSUM_OPTS = [
  { value: "inherit", label: "inherit (hérite de la session / connecteur)" },
  { value: "require_valid", label: "require_valid (exige sommes modulo 256 valides)" },
  { value: "ignore", label: "ignore (ignore les sommes de contrôle)" },
  { value: "reject_valid", label: "reject_valid (refuse les CW avec checksum valide — avancé)" },
];
const REPAIR_OPTS = [
  { value: "off", label: "off (désactivé)" },
  { value: "observe", label: "observe (enregistre opportunités sans altérer)" },
  { value: "enforce", label: "enforce (sert référence cache fraîche — avancé)" },
];

function PolicyTab() {
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
        <p className="field-error">Erreur chargement politique DCW : {describeError(error)}</p>
        <button type="button" className="btn btn-sm" onClick={load}>Réessayer</button>
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
      setMsg("Version requise (>= 1)");
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
      push("Politique DCW enregistrée");
      load();
    } catch (e) {
      if ((e as { status?: number }).status === 409) {
        setConflict(true);
        setMsg("Conflit de version (409) — la politique a été modifiée par un autre administrateur.");
        push("Conflit de version (409)", "err");
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

  return (
    <form onSubmit={submit} noValidate>
      <div className="toolbar">
        <h2>Politique DCW globale</h2>
        <span className="badge badge-muted mono">v{str(policy?.version)}</span>
        <span className="muted small">Màj : {formatDate(Number(policy?.updatedAtMs ?? 0))}</span>
        <span className="muted small">Règles : {str(policy?.rulesCount ?? "—")} — Mappings : {str(policy?.mappingsCount ?? "—")}</span>
      </div>
      <p className="muted small">Fonctionnalités et filtrages :</p>
      <div className="form-grid cols-2">
        <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>Activer le filtrage DCW (enabled)</span></label>
        <label className="check-row"><input type="checkbox" checked={f.adaptiveRoutingEnabled} onChange={set("adaptiveRoutingEnabled")} /><span>Routage adaptatif basé sur la qualité</span></label>
        <label className="check-row"><input type="checkbox" checked={f.autoObserveEnabled} onChange={set("autoObserveEnabled")} /><span>Auto-observation des anomalies</span></label>
        <label className="check-row"><input type="checkbox" checked={f.zeroCountingEnabled} onChange={set("zeroCountingEnabled")} /><span>Comptage d'octets nuls suspect 5-7</span></label>
        <label className="check-row"><input type="checkbox" checked={f.allowZeroHalf} onChange={set("allowZeroHalf")} /><span>Autoriser demi-CW nulle (allowZeroHalf)</span></label>
      </div>
      <div className="form-grid cols-2">
        <div className="field">
          <label htmlFor="dcw-checksum">Mode Checksum par défaut</label>
          <select id="dcw-checksum" value={f.defaultChecksumMode} onChange={set("defaultChecksumMode")} required>
            {CHECKSUM_OPTS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="dcw-repair">Mode Réparation</label>
          <select id="dcw-repair" value={f.repairMode} onChange={set("repairMode")} required>
            {REPAIR_OPTS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
        <Field label="Version (verrou optimiste)" htmlFor="dcw-ver">
          <input id="dcw-ver" type="number" value={f.version} onChange={set("version")} required />
        </Field>
      </div>
      {dangerous && (
        <div className="alert alert-warn" role="alert">
          <strong>Option avancée : </strong>
          Option avancée, valider l'impact avant activation (repairMode « enforce » ou defaultChecksumMode « reject_valid »).
        </div>
      )}
      {msg && (
        <p className={conflict ? "alert alert-error" : "muted small"} role={conflict ? "alert" : "status"}>
          {msg}{" "}
          {conflict && (
            <button type="button" className="btn btn-sm" onClick={load}>
              Recharger la politique
            </button>
          )}
        </p>
      )}
      <div className="toolbar">
        <button type="submit" className="btn btn-primary btn-sm" disabled={busy}>
          {busy ? "…" : "Enregistrer la politique"}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={load}>
          Recharger
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
      push(`Règle #${r.id} supprimée`);
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  if (error) {
    return (
      <div role="alert">
        <p className="field-error">Erreur : {describeError(error)}</p>
        <button type="button" className="btn btn-sm" onClick={load}>Réessayer</button>
      </div>
    );
  }
  return (
    <div>
      <div className="toolbar">
        <h2>Règles de filtrage</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setDlg("new")}>Nouvelle règle</button>
      </div>
      {!rules.length ? (
        <Empty msg="Aucune règle." />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Scope (profil/CAID/provider/SID)</th>
                <th>Source</th>
                <th>Action</th>
                <th>Checksum</th>
                <th>Digest</th>
                <th>État</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr key={r.id}>
                  <td className="mono">#{r.id}</td>
                  <td className="mono small">
                    {r.profile || "*"} / {r.caid ? hex(r.caid, 4) : "— (tous)"} /{" "}
                    {r.provider != null ? hex(r.provider, 6) : "— (tous)"} / {r.sid ? formatSid(r.sid) : "— (tous)"}
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
                      {r.enabled ? "actif" : "inactif"}
                    </span>
                  </td>
                  <td className="mono">v{r.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDlg(r)}>Éditer</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(r)}>Supprimer</button>
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
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    profile: existing?.profile ?? "",
    caid: String(existing?.caid ?? 0),
    provider: existing?.provider != null ? String(existing.provider) : "",
    sid: existing?.sid ? formatSid(existing.sid) : "0",
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

  function parseSidLike(raw: string): number {
    const t = raw.trim();
    if (!t) return 0;
    if (/^0x[0-9a-fA-F]+$/.test(t)) return parseInt(t, 16);
    return parseInteger(t, NaN);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const caid = parseInteger(f.caid.trim(), NaN);
    if (Number.isNaN(caid) || caid < 0 || caid > 65535) return setErr("CAID : 0..65535 (0 = joker)");
    let provider: number | null = null;
    if (f.provider.trim()) {
      const p = /^0x/i.test(f.provider.trim()) ? parseInt(f.provider.trim(), 16) : parseInteger(f.provider.trim(), NaN);
      if (Number.isNaN(p) || p < 0 || p > 16777215) return setErr("Provider : 0..16777215 ou vide (joker)");
      provider = p;
    }
    const sid = parseSidLike(f.sid);
    if (Number.isNaN(sid) || sid < 0 || sid > 65535) return setErr("SID : 0..65535 (0 = joker)");
    if (f.notes && f.notes.length > 255) return setErr("Notes : 255 caractères max");
    const cleanDigest = cleanHex(f.digestSha256);
    const cleanBad = cleanHex(f.badDcwHex);
    if (f.action === "none") {
      if (cleanDigest || cleanBad) return setErr('Action « none » ne doit pas avoir de digest ni hex');
      if (f.checksumMode === "inherit") return setErr('Action « none » avec checksumMode « inherit » sans effet');
    } else {
      if (!isEdit && !cleanDigest && !cleanBad)
        return setErr(`Action « ${f.action} » requiert un digestSha256 ou badDcwHex`);
      if (cleanDigest && cleanBad) return setErr("Fournir digestSha256 OU badDcwHex, pas les deux");
      if (cleanDigest && !/^[0-9a-fA-F]{64}$/.test(cleanDigest))
        return setErr("Digest invalide : 64 caractères hexadécimaux requis");
      if (cleanBad && !/^[0-9a-fA-F]{32}$/.test(cleanBad))
        return setErr("badDcwHex invalide : 16 octets (32 hex) requis");
    }
    const version = parseInteger(f.version, NaN);
    if (isEdit && !(version >= 1)) return setErr("Version requise (>= 1)");
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
        push(`Règle #${existing.id} mise à jour`);
      } else {
        await apiJson("/api/admin/dcw/rules", { method: "POST", body: payload });
        push("Règle DCW créée");
      }
      onSaved();
    } catch (e) {
      const m =
        (e as { status?: number }).status === 409
          ? "Conflit de version (409) — la règle a été modifiée par un autre administrateur."
          : describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? `Éditer la règle DCW #${existing.id}` : "Créer une règle DCW"} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <p className="muted small">
          Règle scopée pour filtrage ou contrôle checksum. Pour block/observe, fournissez un digest SHA-256 ou une
          CW 16 octets hex (convertie en digest, jamais stockée/réaffichée en clair).
        </p>
        <section aria-label="Scope">
          <h3>Scope</h3>
          <div className="form-grid cols-2">
            <Field label="Profil (vide = joker)" htmlFor="dr-profile"><input id="dr-profile" value={f.profile} onChange={set("profile")} /></Field>
            <Field label="CAID (0 = joker, 0..65535)" htmlFor="dr-caid"><input id="dr-caid" type="number" value={f.caid} onChange={set("caid")} required /></Field>
            <Field label="Provider (vide = joker, 0..16777215)" htmlFor="dr-prov"><input id="dr-prov" value={f.provider} onChange={set("provider")} placeholder="décimal ou 0x…" /></Field>
            <Field label="SID (0 = joker, 0..65535 ou hex 0x…)" htmlFor="dr-sid"><input id="dr-sid" value={f.sid} onChange={set("sid")} /></Field>
            <Field label="SourceId (optionnel)" htmlFor="dr-src"><input id="dr-src" value={f.sourceId} onChange={set("sourceId")} /></Field>
            <Field label="Notes (max 255)" htmlFor="dr-notes"><input id="dr-notes" value={f.notes} onChange={set("notes")} /></Field>
          </div>
        </section>
        <section aria-label="Action de la règle" className="section">
          <h3>Action</h3>
          <div className="form-grid cols-2">
            <div className="field">
              <label htmlFor="dr-action">Action</label>
              <select id="dr-action" value={f.action} onChange={set("action")} required>
                <option value="none">none (contrôle checksum seul)</option>
                <option value="block">block (bloquer le CW identifié)</option>
                <option value="observe">observe (observer sans bloquer)</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="dr-checksum">Mode Checksum</label>
              <select id="dr-checksum" value={f.checksumMode} onChange={set("checksumMode")} required>
                <option value="inherit">inherit (hérite de la politique)</option>
                <option value="require_valid">require_valid</option>
                <option value="ignore">ignore</option>
                <option value="reject_valid">reject_valid</option>
              </select>
            </div>
            <Field label="Digest SHA-256 (64 hex)" htmlFor="dr-digest">
              <input id="dr-digest" value={f.digestSha256} onChange={set("digestSha256")} autoComplete="off" spellCheck={false} placeholder={isEdit ? "(vide = conservé)" : ""} />
            </Field>
            <Field label="OU badDcwHex (16 octets = 32 hex, jamais réaffiché)" htmlFor="dr-bad">
              <input id="dr-bad" type="password" value={f.badDcwHex} onChange={set("badDcwHex")} autoComplete="new-password" spellCheck={false} />
            </Field>
            <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>Activée</span></label>
            {isEdit && (
              <Field label="Version" htmlFor="dr-ver"><input id="dr-ver" type="number" value={f.version} onChange={set("version")} required /></Field>
            )}
          </div>
        </section>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
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
  sourceSid?: number;
  targetSid?: number;
  approved?: boolean;
  enabled?: boolean;
  expiresAtMs?: number;
  version: number;
}

function MappingsTab() {
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
      push(`Mapping #${m.id} supprimé`);
      load();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  if (error) {
    return (
      <div role="alert">
        <p className="field-error">Erreur : {describeError(error)}</p>
        <button type="button" className="btn btn-sm" onClick={load}>Réessayer</button>
      </div>
    );
  }
  return (
    <div>
      <div className="toolbar">
        <h2>Mappings de service (même profil/CAID/provider)</h2>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setDlg("new")}>Nouveau mapping</button>
      </div>
      <p className="muted small">
        Associe deux chaînes (SIDs) équivalentes. Seuls les mappings explicitement approuvés et non expirés sont
        appliqués par le routeur.
      </p>
      {!maps.length ? (
        <Empty msg="Aucun mapping service." />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Profil</th>
                <th>CAID</th>
                <th>Provider</th>
                <th>Source → Cible</th>
                <th>Approuvé</th>
                <th>État</th>
                <th>Expire</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {maps.map((m) => (
                <tr key={m.id}>
                  <td className="mono">#{m.id}</td>
                  <td>{m.profile}</td>
                  <td className="mono">{m.caid != null ? hex(m.caid, 4) : "—"}</td>
                  <td className="mono">{m.provider != null ? hex(m.provider, 6) : "—"}</td>
                  <td className="mono small">{m.sourceSid != null ? formatSid(m.sourceSid) : "?"} → {m.targetSid != null ? formatSid(m.targetSid) : "?"}</td>
                  <td>{m.approved ? "oui" : "non"}</td>
                  <td>
                    <span className={m.enabled ? "badge badge-ok" : "badge badge-muted"}>
                      {m.enabled ? "actif" : "inactif"}
                    </span>
                  </td>
                  <td className="small">{m.expiresAtMs ? formatDate(m.expiresAtMs) : "permanent"}</td>
                  <td className="mono">v{m.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDlg(m)}>Éditer</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove(m)}>Supprimer</button>
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
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState({
    profile: existing?.profile ?? "",
    caid: existing?.caid != null ? String(existing.caid) : "",
    provider: existing?.provider != null ? String(existing.provider) : "0",
    sourceSid: existing?.sourceSid != null ? formatSid(existing.sourceSid) : "",
    targetSid: existing?.targetSid != null ? formatSid(existing.targetSid) : "",
    approved: existing?.approved ?? true,
    enabled: existing?.enabled ?? true,
    expiresAt: existing?.expiresAtMs ? epochToLocal(existing.expiresAtMs) : "",
    version: String(existing?.version ?? 1),
  });
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  function parseSidLike(raw: string): number {
    const t = raw.trim();
    if (!t) return NaN;
    if (/^0x[0-9a-fA-F]+$/.test(t)) return parseInt(t, 16);
    return parseInteger(t, NaN);
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!f.profile.trim() || f.profile.length > 64) return setErr("Profil requis (1..64)");
    const caid = parseInteger(f.caid.trim(), NaN);
    if (!(caid >= 1 && caid <= 65535)) return setErr("CAID requis (1..65535)");
    const provider = /^0x/i.test(f.provider.trim()) ? parseInt(f.provider.trim(), 16) : parseInteger(f.provider.trim(), NaN);
    if (!(provider >= 0 && provider <= 16777215)) return setErr("Provider requis (0..16777215)");
    const sourceSid = parseSidLike(f.sourceSid);
    if (!(sourceSid >= 1 && sourceSid <= 65535)) return setErr("SID Source requis (1..65535)");
    const targetSid = parseSidLike(f.targetSid);
    if (!(targetSid >= 1 && targetSid <= 65535)) return setErr("SID Cible requis (1..65535)");
    if (sourceSid === targetSid) return setErr("sourceSid doit différer de targetSid");
    const version = parseInteger(f.version, NaN);
    if (isEdit && !(version >= 1)) return setErr("Version requise (>= 1)");
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
      if (isEdit) {
        await apiJson(`/api/admin/dcw/mappings/${existing.id}`, { method: "PUT", body: { ...payload, version } });
        push(`Mapping #${existing.id} mis à jour`);
      } else {
        await apiJson("/api/admin/dcw/mappings", { method: "POST", body: payload });
        push("Mapping créé");
      }
      onSaved();
    } catch (e) {
      const m =
        (e as { status?: number }).status === 409
          ? "Conflit de version (409) — le mapping a été modifié par un autre administrateur."
          : describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? `Éditer mapping #${existing.id}` : "Nouveau mapping"} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <div className="form-grid cols-2">
          <Field label="Profil (requis, 1..64)" htmlFor="dm-profile"><input id="dm-profile" value={f.profile} onChange={set("profile")} required /></Field>
          <Field label="CAID (requis, 1..65535)" htmlFor="dm-caid"><input id="dm-caid" type="number" value={f.caid} onChange={set("caid")} required /></Field>
          <Field label="Provider (requis, 0..16777215)" htmlFor="dm-prov"><input id="dm-prov" value={f.provider} onChange={set("provider")} required /></Field>
          <Field label="Expiration (vide = permanente)" htmlFor="dm-exp"><input id="dm-exp" type="datetime-local" value={f.expiresAt} onChange={set("expiresAt")} /></Field>
          <Field label="SID Source (1..65535 ou hex 0x…)" htmlFor="dm-src"><input id="dm-src" value={f.sourceSid} onChange={set("sourceSid")} required /></Field>
          <Field label="SID Cible (≠ source)" htmlFor="dm-tgt"><input id="dm-tgt" value={f.targetSid} onChange={set("targetSid")} required /></Field>
          <label className="check-row"><input type="checkbox" checked={f.approved} onChange={set("approved")} /><span>Approuvé explicitement</span></label>
          <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>Activé</span></label>
          {isEdit && (
            <Field label="Version" htmlFor="dm-ver"><input id="dm-ver" type="number" value={f.version} onChange={set("version")} required /></Field>
          )}
        </div>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Annuler</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? "Enregistrer" : "Créer"}</button>
        </div>
      </form>
    </Dialog>
  );
}

function ObsTab() {
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

  if (loading) return <p className="muted">Chargement observabilité…</p>;
  if (error) {
    return (
      <div role="alert">
        <p className="field-error">Erreur : {describeError(error)}</p>
        <button type="button" className="btn btn-sm" onClick={load}>Réessayer</button>
      </div>
    );
  }
  const anomVal = anomalies !== null ? String(anomalies.length) : stats?.anomalies !== undefined ? String(stats.anomalies) : "—";
  const candVal = candidates !== null ? String(candidates.length) : stats?.candidateLinks !== undefined ? String(stats.candidateLinks) : "—";
  return (
    <div>
      <h2>Observabilité (observation seule)</h2>
      <p className="muted small">
        Qualité des pairs (fenêtre temporelle, hits servis, quarantaine, quorum) :{" "}
        <Link to="../cacheex">page CacheEx</Link> — réglage des politiques et relevés par pair.
      </p>
      <div className="kpi-grid">
        <div className="card kpi"><div className="kpi-label">Anomalies</div><div className="kpi-value mono">{anomVal}</div></div>
        <div className="card kpi"><div className="kpi-label">Candidats</div><div className="kpi-value mono">{candVal}</div></div>
        <div className="card kpi"><div className="kpi-label">maxRules</div><div className="kpi-value mono">{stats?.maxRules !== undefined ? String(stats.maxRules) : "—"}</div></div>
        <div className="card kpi"><div className="kpi-label">maxMappings</div><div className="kpi-value mono">{stats?.maxMappings !== undefined ? String(stats.maxMappings) : "—"}</div></div>
      </div>
      {stats && Boolean(stats.adaptiveScoresExposed) && stats.routing !== undefined && (
        <div className="card">
          <h3>Routage adaptatif</h3>
          <pre className="mono small patch-preview">{JSON.stringify(stats.routing, null, 2)}</pre>
        </div>
      )}
      <h3>Anomalies ({anomalies?.length ?? "—"})</h3>
      {anomalies === null ? (
        <Empty msg="Endpoint anomalies indisponible — inconnu." />
      ) : !anomalies.length ? (
        <Empty msg="Aucune anomalie." />
      ) : (
        <ul className="small">
          {anomalies.map((a, i) => (
            <li key={str(a.id ?? i)}>
              {str(a.message ?? a.type ?? JSON.stringify(a))} — {formatDate(Number(a.atMs ?? a.timestamp ?? a.detectedAtMs ?? 0))}
            </li>
          ))}
        </ul>
      )}
      <h3>Candidats ({candidates?.length ?? "—"})</h3>
      {candidates === null ? (
        <Empty msg="Endpoint candidats indisponible — inconnu." />
      ) : !candidates.length ? (
        <Empty msg="Aucun candidat." />
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
