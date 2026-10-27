import { useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, formatSid, parseInteger, parseSidList } from "../api";
import type { ProtocolSecret, UserEntry } from "../types";
import { BulkBar, SelectBox } from "../components/BulkBar";
import type { BulkFieldDef } from "../components/BulkBar";
import { SecretField, SecretRefView } from "../components/SecretField";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, fetchUsers, useSnapshot, useToast } from "../components/ui";

const BULK_SCALAR_FIELDS: BulkFieldDef[] = [
  { key: "displayName", label: "displayName (vide = inchangé)", type: "text", resettable: true, resetHint: "NULL" },
  { key: "email", label: "email (vide = inchangé)", type: "text", resettable: true, resetHint: "NULL" },
  { key: "enabled", label: "Activé", type: "boolean" },
  { key: "debug", label: "Debug", type: "boolean" },
  { key: "mapExcluded", label: "mapExcluded", type: "boolean" },
  { key: "maxConnections", label: "maxConnections", type: "number" },
  { key: "ipMask", label: "ipMask (vide = inchangé)", type: "text", resettable: true, resetHint: "NULL" },
  { key: "allowedEcmRate", label: "allowedEcmRate", type: "number" },
];

export function UsersPage() {
  const snap = useSnapshot(fetchSnapshot);
  const users = useSnapshot(fetchUsers);
  const { push } = useToast();
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<UserEntry | null | "new">(null);
  const [deleting, setDeleting] = useState<UserEntry | null>(null);
  const [secretsOf, setSecretsOf] = useState<UserEntry | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const list = useMemo(() => {
    const all = users.data ?? [];
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((u) => `${u.name} ${u.displayName ?? ""} ${u.email ?? ""}`.toLowerCase().includes(q));
  }, [users.data, filter]);

  if (users.loading) return <Loading />;
  if (users.error) return <LoadError error={users.error} onRetry={users.reload} />;

  const toggle = (name: string, v: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (v) n.add(name);
      else n.delete(name);
      return n;
    });
  const selectedItems = list.filter((u) => selected.has(u.name)).map((u) => ({ id: u.name, item: u }));

  async function doDelete(u: UserEntry) {
    try {
      const res = await apiFetch(`/api/admin/users/${encodeURIComponent(u.name)}?version=${u.version}`, {
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
      push("Utilisateur supprimé");
      users.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>Utilisateurs</h1>
        <input
          type="search"
          className="search-input"
          placeholder="Rechercher…"
          aria-label="Rechercher un utilisateur"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          Créer utilisateur
        </button>
      </div>
      <BulkBar
        resource="users"
        selected={selectedItems}
        fields={[
          ...BULK_SCALAR_FIELDS,
          {
            key: "profiles",
            label: "profiles (ensemble)",
            type: "multiselect",
            options: (snap.data?.profiles ?? []).map((p) => p.name),
            help: "Remplacement complet de l'ensemble des profils autorisés.",
          },
          {
            key: "allowedConnectors",
            label: "allowedConnectors (ensemble)",
            type: "multiselect",
            options: (snap.data?.connectors ?? []).map((c) => c.name),
            help: "Remplacement complet de l'ensemble des connecteurs autorisés.",
          },
        ]}
        labelOf={(id) => id}
        onDone={() => {
          setSelected(new Set());
          users.reload();
        }}
      />
      <p className="muted small">Bulk users : scalaires + remplacement complet des ensembles profiles/allowedConnectors (services via édition unitaire). Jamais admin/secrets en bulk.</p>
      {!list.length ? (
        <Empty msg={(users.data ?? []).length ? "Aucun résultat" : "Aucun utilisateur"} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th><span className="sr-only">Sélection</span></th>
                <th>Nom</th>
                <th>Display</th>
                <th>Email</th>
                <th>Rôle</th>
                <th>État</th>
                <th>Profils</th>
                <th>Version</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {list.map((u) => (
                <tr key={u.name}>
                  <td>
                    <SelectBox checked={selected.has(u.name)} onChange={(v) => toggle(u.name, v)} label={`Sélectionner ${u.name}`} />
                  </td>
                  <td className="mono">{u.name}</td>
                  <td>{u.displayName ?? "—"}</td>
                  <td>{u.email ?? "—"}</td>
                  <td>
                    <span className={u.admin ? "badge badge-ok" : ""}>{u.admin ? "admin" : "user"}</span>
                  </td>
                  <td>
                    <span className={u.enabled ? "badge badge-ok" : "badge badge-warn"}>
                      {u.enabled ? "enabled" : "disabled"}
                    </span>
                  </td>
                  <td className="mono small">{(u.profiles ?? []).join(", ") || "—"}</td>
                  <td className="mono">v{u.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(u)}>Éditer</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSecretsOf(u)}>Secrets</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(u)}>Supprimer</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {editing && (
        <UserDialog
          existing={editing === "new" ? null : editing}
          profiles={(snap.data?.profiles ?? []).map((p) => p.name)}
          connectors={(snap.data?.connectors ?? []).map((c) => c.name)}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            users.reload();
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title="Supprimer l'utilisateur"
          desc={`« ${deleting.name} » (v${deleting.version}) — irréversible.`}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
      {secretsOf && <UserSecretsDialog user={secretsOf} onClose={() => setSecretsOf(null)} />}
    </div>
  );
}

function mapToText(map?: Record<string, number[]>): string {
  if (!map || !Object.keys(map).length) return "";
  return Object.entries(map)
    .map(([k, vals]) => `${k}: ${(Array.isArray(vals) ? vals : []).map(formatSid).join(", ")}`)
    .join("\n");
}

function parseMapText(text: string): { ok: true; value: Record<string, number[]> } | { ok: false; error: string } {
  if (!text.trim()) return { ok: true, value: {} };
  const out: Record<string, number[]> = {};
  for (const line of text.split("\n")) {
    const t = line.trim();
    if (!t) continue;
    const idx = t.indexOf(":");
    if (idx === -1) return { ok: false, error: `Ligne invalide (attendu « profil: sids ») : ${t}` };
    const prof = t.slice(0, idx).trim();
    if (!prof) return { ok: false, error: `Profil vide : ${t}` };
    const parsed = parseSidList(t.slice(idx + 1).trim());
    if (!parsed.ok) return { ok: false, error: `${prof} : ${parsed.error}` };
    out[prof] = parsed.values;
  }
  return { ok: true, value: out };
}

function Chips({ id, legend, options, selected, onChange }: {
  id: string;
  legend: string;
  options: string[];
  selected: Set<string>;
  onChange: (s: Set<string>) => void;
}) {
  if (!options.length) {
    return (
      <fieldset className="field">
        <legend className="small">{legend}</legend>
        <p className="chip-empty">Aucune option disponible.</p>
      </fieldset>
    );
  }
  return (
    <fieldset className="field">
      <legend className="small">{legend}</legend>
      <div className="chip-group">
        {options.map((o) => (
          <label className="chip" key={o} htmlFor={`${id}-${o}`}>
            <input
              type="checkbox"
              id={`${id}-${o}`}
              checked={selected.has(o)}
              onChange={(e) => {
                const n = new Set(selected);
                if (e.target.checked) n.add(o);
                else n.delete(o);
                onChange(n);
              }}
            />
            {o}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function UserDialog({
  existing,
  profiles,
  connectors,
  onClose,
  onSaved,
}: {
  existing: UserEntry | null;
  profiles: string[];
  connectors: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const { push } = useToast();
  const isEdit = !!existing;
  const [name, setName] = useState(existing?.name ?? "");
  const [displayName, setDisplayName] = useState(existing?.displayName ?? "");
  const [email, setEmail] = useState(existing?.email ?? "");
  const [password, setPassword] = useState("");
  const [maxConnections, setMaxConnections] = useState(String(existing?.maxConnections ?? 1));
  const [allowedEcmRate, setAllowedEcmRate] = useState(String(existing?.allowedEcmRate ?? -1));
  const [ipMask, setIpMask] = useState(existing?.ipMask ?? "");
  const [enabled, setEnabled] = useState(existing?.enabled ?? true);
  const [admin, setAdmin] = useState(existing?.admin ?? false);
  const [debug, setDebug] = useState(existing?.debug ?? false);
  const [mapExcluded, setMapExcluded] = useState(existing?.mapExcluded ?? false);
  const [selProfiles, setSelProfiles] = useState<Set<string>>(new Set(existing?.profiles ?? []));
  const [selConnectors, setSelConnectors] = useState<Set<string>>(new Set(existing?.allowedConnectors ?? []));
  const [allowedText, setAllowedText] = useState(mapToText(existing?.allowedServices));
  const [blockedText, setBlockedText] = useState(mapToText(existing?.blockedServices));
  const [allowedCleared, setAllowedCleared] = useState(false);
  const [blockedCleared, setBlockedCleared] = useState(false);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const n = name.trim();
    if (!n) return setErr("Nom requis");
    if (!isEdit && password.length < 4) return setErr("Mot de passe ≥ 4 caractères");
    if (isEdit && password && password.length < 4) return setErr("Mot de passe ≥ 4 si renseigné");
    const mc = parseInteger(maxConnections, NaN);
    const rate = parseInteger(allowedEcmRate, NaN);
    if (Number.isNaN(mc)) return setErr("maxConnections : nombre requis");
    if (Number.isNaN(rate)) return setErr("allowedEcmRate : nombre requis");
    let allowedServices: Record<string, number[]> | null = null;
    let blockedServices: Record<string, number[]> | null = null;
    if (!isEdit) {
      const pa = parseMapText(allowedText);
      if (!pa.ok) return setErr(pa.error);
      allowedServices = pa.value;
      const pb = parseMapText(blockedText);
      if (!pb.ok) return setErr(pb.error);
      blockedServices = pb.value;
    } else {
      if (allowedCleared) allowedServices = {};
      else if (allowedText.trim()) {
        const pa = parseMapText(allowedText);
        if (!pa.ok) return setErr(pa.error);
        allowedServices = pa.value;
      }
      if (blockedCleared) blockedServices = {};
      else if (blockedText.trim()) {
        const pb = parseMapText(blockedText);
        if (!pb.ok) return setErr(pb.error);
        blockedServices = pb.value;
      }
    }
    setBusy(true);
    try {
      if (!isEdit) {
        const { body } = await apiJson<{ name: string }>("/api/admin/users", {
          method: "POST",
          body: {
            name: n,
            displayName: displayName.trim() || null,
            password,
            email: email.trim() || null,
            enabled,
            admin,
            debug,
            mapExcluded,
            maxConnections: mc,
            ipMask: ipMask.trim() || null,
            allowedEcmRate: rate,
            profiles: [...selProfiles],
            allowedServices: allowedServices ?? {},
            blockedServices: blockedServices ?? {},
            allowedConnectors: [...selConnectors],
          },
        });
        push(`Utilisateur créé : ${body.name}`);
        onSaved();
      } else {
        const version = existing.version;
        const bodyObj: Record<string, unknown> = {
          displayName: displayName.trim() || null,
          email: email.trim() || null,
          enabled,
          admin,
          debug,
          mapExcluded,
          maxConnections: mc,
          ipMask: ipMask.trim() || null,
          allowedEcmRate: rate,
          profiles: [...selProfiles],
          allowedConnectors: [...selConnectors],
        };
        if (password) bodyObj.password = password;
        if (allowedServices !== null) bodyObj.allowedServices = allowedServices;
        if (blockedServices !== null) bodyObj.blockedServices = blockedServices;
        const { body } = await apiJson<{ name: string }>(`/api/admin/users/${encodeURIComponent(n)}?version=${version}`, {
          method: "PUT",
          body: bodyObj,
        });
        push(`Utilisateur mis à jour : ${body.name}`);
        onSaved();
      }
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? "Éditer utilisateur" : "Créer utilisateur"} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label="Essentiel">
          <h3>Essentiel</h3>
          <div className="form-grid cols-2">
            <Field label="Nom" htmlFor="u-name">
              <input id="u-name" value={name} onChange={(e) => setName(e.target.value)} disabled={isEdit} required />
            </Field>
            <Field label="Display" htmlFor="u-disp"><input id="u-disp" value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
            <Field label="Email" htmlFor="u-email"><input id="u-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label={isEdit ? "Mot de passe (vide = inchangé, 4+ si renseigné)" : "Mot de passe (4+ requis)"} htmlFor="u-pass">
              <input id="u-pass" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required={!isEdit} />
            </Field>
            <Field label="maxConnections" htmlFor="u-mc"><input id="u-mc" type="number" value={maxConnections} onChange={(e) => setMaxConnections(e.target.value)} required /></Field>
            <Field label="allowedEcmRate" htmlFor="u-rate"><input id="u-rate" type="number" value={allowedEcmRate} onChange={(e) => setAllowedEcmRate(e.target.value)} required /></Field>
            <Field label="ipMask" htmlFor="u-ip"><input id="u-ip" value={ipMask} onChange={(e) => setIpMask(e.target.value)} /></Field>
          </div>
          <div className="form-grid cols-2">
            <label className="check-row"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /><span>Activé</span></label>
            <label className="check-row"><input type="checkbox" checked={admin} onChange={(e) => setAdmin(e.target.checked)} /><span>Admin (jamais en bulk)</span></label>
            <label className="check-row"><input type="checkbox" checked={debug} onChange={(e) => setDebug(e.target.checked)} /><span>Debug</span></label>
            <label className="check-row"><input type="checkbox" checked={mapExcluded} onChange={(e) => setMapExcluded(e.target.checked)} /><span>mapExcluded</span></label>
          </div>
        </section>
        <section aria-label="ACL" className="section">
          <h3>ACL — services et accès</h3>
          <Chips id="chip-prof" legend="Profils (sélection = autorisés)" options={profiles} selected={selProfiles} onChange={setSelProfiles} />
          <Chips id="chip-conn" legend="allowedConnectors" options={connectors} selected={selConnectors} onChange={setSelConnectors} />
          <div className="field">
            <label htmlFor="ta-allowed">allowedServices (profil: sids — vide = inchangé en édition)</label>
            <textarea id="ta-allowed" value={allowedText} disabled={allowedCleared} onChange={(e) => setAllowedText(e.target.value)} placeholder={"ex : hdplus: 0x1234, 0x5678\nsky: 100, 200"} />
            {isEdit && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setAllowedCleared(true); setAllowedText(""); }}>
                {allowedCleared ? "Vide (sera envoyé vide)" : "Vider allowedServices"}
              </button>
            )}
          </div>
          <div className="field">
            <label htmlFor="ta-blocked">blockedServices</label>
            <textarea id="ta-blocked" value={blockedText} disabled={blockedCleared} onChange={(e) => setBlockedText(e.target.value)} placeholder="ex : hdplus: 0x9999" />
            {isEdit && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setBlockedCleared(true); setBlockedText(""); }}>
                {blockedCleared ? "Vide (sera envoyé vide)" : "Vider blockedServices"}
              </button>
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

// Backend : seuls cccam et cs378x sont gérés (INVALID_PROTOCOL sinon).
const PROTOCOLS = ["cccam", "cs378x"];

function epochToLocal(v: string | number): string {
  const ms = typeof v === "number" ? v : Date.parse(v);
  if (Number.isNaN(ms) || ms <= 0) return "";
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function UserSecretsDialog({ user, onClose }: { user: UserEntry; onClose: () => void }) {
  const { push } = useToast();
  const [protocol, setProtocol] = useState("cccam");
  const [secret, setSecret] = useState<ProtocolSecret | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState("");
  const [secretRef, setSecretRef] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [validUntil, setValidUntil] = useState("");
  const [cxMode, setCxMode] = useState("");
  const [cxMaxHop, setCxMaxHop] = useState("");
  const [cxFilter, setCxFilter] = useState("");
  const [busy, setBusy] = useState(false);

  const load = async (p: string) => {
    setLoading(true);
    setErr("");
    try {
      const { body } = await apiJson<ProtocolSecret>(`/api/admin/users/${encodeURIComponent(user.name)}/secrets/${p}`);
      setSecret(body);
      setSecretRef(body.secretRef ?? "");
      setEnabled(body.enabled ?? true);
      setValidUntil(body.validUntil ? epochToLocal(body.validUntil) : "");
      setCxMode(body.cacheex?.mode != null ? String(body.cacheex.mode) : "");
      setCxMaxHop(body.cacheex?.maxHop != null ? String(body.cacheex.maxHop) : "");
      setCxFilter(body.cacheex?.filter ?? "");
    } catch (e) {
      if ((e as { status?: number }).status === 404) {
        setSecret(null);
        setSecretRef("");
        setEnabled(true);
        setValidUntil("");
        setCxMode("");
        setCxMaxHop("");
        setCxFilter("");
      } else {
        setErr(describeError(e));
      }
    } finally {
      setLoading(false);
    }
  };

  useMemo(() => {
    load(protocol);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [protocol]);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    if (!secretRef.trim()) return setErr("secretRef requis");
    if (!/^(env:|file:)/.test(secretRef.trim())) return setErr("secretRef : env: ou file: requis");
    const mode = cxMode.trim() === "" ? null : Number(cxMode);
    if (mode !== null && ![2, 3].includes(mode)) return setErr("cacheex.mode : 2 ou 3 (vide = off)");
    const maxHop = cxMaxHop.trim() === "" ? null : Number(cxMaxHop);
    if (maxHop !== null && (!Number.isInteger(maxHop) || maxHop < 0 || maxHop > 10)) return setErr("cacheex.maxHop : 0-10");
    let validUntilIso: string | null = null;
    if (validUntil.trim()) {
      const ms = new Date(validUntil).getTime();
      if (Number.isNaN(ms)) return setErr("validUntil : date invalide");
      validUntilIso = new Date(ms).toISOString();
    }
    setBusy(true);
    try {
      const body: Record<string, unknown> = {
        secretRef: secretRef.trim(),
        validUntil: validUntilIso,
        enabled,
      };
      if (mode !== null || maxHop !== null || cxFilter.trim()) {
        body.cacheex = {
          mode,
          maxHop: maxHop ?? 10,
          filter: cxFilter.trim() || null,
        };
      }
      // ?version : 0 = création (201), sinon mise à jour optimiste (200/409).
      const version = secret?.version ?? 0;
      await apiJson(`/api/admin/users/${encodeURIComponent(user.name)}/secrets/${protocol}?version=${version}`, {
        method: "PUT",
        body,
      });
      push(`Secret ${protocol} enregistré`);
      load(protocol);
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setBusy(false);
    }
  }

  async function revoke() {
    const version = secret?.version ?? 0;
    if (!(version >= 1)) {
      push("Aucun secret versionné à révoquer", "err");
      return;
    }
    try {
      await apiFetch(`/api/admin/users/${encodeURIComponent(user.name)}/secrets/${protocol}/revoke?version=${version}`, { method: "POST" });
      push(`Secret ${protocol} révoqué`);
      load(protocol);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <Dialog title={`Secrets protocoles — ${user.name}`} onClose={onClose} wide>
      <div className="field">
        <label htmlFor="us-proto">Protocole</label>
        <select id="us-proto" value={protocol} onChange={(e) => setProtocol(e.target.value)}>
          {PROTOCOLS.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </div>
      {loading ? (
        <p className="muted">Chargement…</p>
      ) : (
        <form onSubmit={save} noValidate>
          <p className="muted small">
            Métadonnées uniquement — la valeur n'est jamais lue. Ref actuelle :{" "}
            <SecretRefView value={secret?.secretRef} />
          </p>
          <SecretField
            label="secretRef"
            value={secretRef}
            onChange={setSecretRef}
            purpose="user-protocol-secret"
            owner={user.name}
            help="env:VAR ou file:/chemin. Valeur directe matérialisée serveur."
          />
          <div className="form-grid cols-2">
            <label className="check-row">
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              <span>Secret actif</span>
            </label>
            <Field label="validUntil (vide = permanent)" htmlFor="us-valid">
              <input id="us-valid" type="datetime-local" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
            </Field>
          </div>
          {secret && (
            <p className="muted small">
              Version : v{secret.version ?? "—"} (envoyée en ?version ; 0 = création) — màj par {secret.updatedBy ?? "—"}
            </p>
          )}
          <section aria-label="CacheEx entrant" className="section">
            <h3>CacheEx entrant (CCcam / CS378x)</h3>
            <p className="muted small">
              Mode 2 = csp-ng pousse vers les sessions du user (le client reçoit) ; mode 3 = csp-ng reçoit les push
              du user (le client émet). Vide = off.
            </p>
            <div className="form-grid cols-2">
              <div className="field">
                <label htmlFor="us-cxmode">cacheex.mode</label>
                <select id="us-cxmode" value={cxMode} onChange={(e) => setCxMode(e.target.value)}>
                  <option value="">off</option>
                  <option value="2">2 — nous émettons vers le client</option>
                  <option value="3">3 — nous recevons du client</option>
                </select>
              </div>
              <Field label="cacheex.maxHop (0-10, vide = 10)" htmlFor="us-cxhop">
                <input id="us-cxhop" type="number" value={cxMaxHop} onChange={(e) => setCxMaxHop(e.target.value)} />
              </Field>
              <Field label="cacheex.filter" htmlFor="us-cxfilter">
                <input id="us-cxfilter" value={cxFilter} onChange={(e) => setCxFilter(e.target.value)} placeholder="ex : 0100:00006A" />
              </Field>
            </div>
          </section>
          {err && <p className="field-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Fermer</button>
            {secret?.secretRef && (
              <button type="button" className="btn btn-ghost" onClick={revoke}>Révoquer</button>
            )}
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : "Enregistrer"}</button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
