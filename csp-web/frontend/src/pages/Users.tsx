import { useEffect, useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, formatSid, parseInteger, parseSidList } from "../api";
import type { ProtocolSecret, UserEntry } from "../types";
import { BulkBar, SelectBox } from "../components/BulkBar";
import type { BulkFieldDef } from "../components/BulkBar";
import { SecretField, SecretRefView } from "../components/SecretField";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, fetchUsers, useSnapshot, useToast } from "../components/ui";
import { useT, translate, type MessageKey } from "../i18n";

function getBulkScalarFields(t: (k: MessageKey, params?: Record<string, string | number>) => string): BulkFieldDef[] {
  return [
    { key: "displayName", label: t("users.bulkDisplayName"), type: "text", resettable: true, resetHint: "NULL" },
    { key: "email", label: t("users.bulkEmail"), type: "text", resettable: true, resetHint: "NULL" },
    { key: "enabled", label: t("users.bulkEnabled"), type: "boolean" },
    { key: "debug", label: t("users.bulkDebug"), type: "boolean" },
    { key: "mapExcluded", label: t("users.bulkMapExcluded"), type: "boolean" },
    { key: "maxConnections", label: t("users.bulkMaxConnections"), type: "number" },
    { key: "ipMask", label: t("users.bulkIpMask"), type: "text", resettable: true, resetHint: "NULL" },
    { key: "allowedEcmRate", label: t("users.bulkAllowedEcmRate"), type: "number" },
  ];
}

/** Session client sanitisée renvoyée par GET /api/admin/runtime/sessions (IP déjà anonymisée côté serveur). */
export interface LiveSession {
  id: string;
  username: string | null;
  remoteAddress: string;
  protocol: string;
  profile: string | null;
  authenticated: boolean;
  alive: boolean;
}

const LIVE_STORAGE_KEY = "csp.users.liveStatus";
export const LIVE_REFRESH_MS = 3000;
const LIVE_PAGE_SIZE = 100;
const LIVE_MAX_PAGES = 50;

type LivePage = { payload?: { items?: LiveSession[]; totalPages?: number } };

async function fetchAllSessions(): Promise<LiveSession[]> {
  const out: LiveSession[] = [];
  for (let page = 0; page < LIVE_MAX_PAGES; page++) {
    const { res, body } = await apiJson<LivePage>(`/api/admin/runtime/sessions?page=${page}&size=${LIVE_PAGE_SIZE}`);
    if (!res.ok) throw { status: res.status, ...((body ?? {}) as object) };
    const items = body?.payload?.items ?? [];
    out.push(...items);
    const totalPages = body?.payload?.totalPages ?? 0;
    if (page + 1 >= totalPages || items.length === 0) break;
  }
  return out;
}

function readLivePref(): boolean {
  try {
    return typeof localStorage !== "undefined" && localStorage.getItem(LIVE_STORAGE_KEY) === "1";
  } catch {
    return false;
  }
}

/** Sondage périodique des sessions, en pause quand l'onglet est masqué. */
function useLiveSessions(enabled: boolean) {
  const [byUser, setByUser] = useState<Map<string, LiveSession[]>>(new Map());
  const [error, setError] = useState<unknown>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);

  useEffect(() => {
    if (!enabled) {
      setByUser(new Map());
      setError(null);
      setUpdatedAt(null);
      return;
    }
    let cancelled = false;
    let inflight = false;
    const tick = async () => {
      if (inflight || (typeof document !== "undefined" && document.hidden)) return;
      inflight = true;
      try {
        const list = await fetchAllSessions();
        if (cancelled) return;
        const m = new Map<string, LiveSession[]>();
        for (const s of list) {
          if (!s.username || !s.authenticated || !s.alive) continue;
          const k = s.username.toLowerCase();
          const arr = m.get(k) ?? [];
          arr.push(s);
          m.set(k, arr);
        }
        setByUser(m);
        setError(null);
        setUpdatedAt(Date.now());
      } catch (e) {
        if (!cancelled) setError(e);
      } finally {
        inflight = false;
      }
    };
    void tick();
    const timer = setInterval(() => void tick(), LIVE_REFRESH_MS);
    const onVis = () => {
      if (!document.hidden) void tick();
    };
    document.addEventListener("visibilitychange", onVis);
    return () => {
      cancelled = true;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [enabled]);

  return { byUser, error, updatedAt };
}

type ConnFilter = "all" | "online" | "offline";

export function UsersPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const users = useSnapshot(fetchUsers);
  const { push } = useToast();
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<UserEntry | null | "new">(null);
  const [deleting, setDeleting] = useState<UserEntry | null>(null);
  const [secretsOf, setSecretsOf] = useState<UserEntry | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [live, setLive] = useState<boolean>(readLivePref);
  const [connFilter, setConnFilter] = useState<ConnFilter>("all");
  const liveData = useLiveSessions(live);

  const toggleLive = (v: boolean) => {
    setLive(v);
    if (!v) setConnFilter("all");
    try {
      localStorage.setItem(LIVE_STORAGE_KEY, v ? "1" : "0");
    } catch {
      /* stockage indisponible : préférence non persistée */
    }
  };

  const bulkScalarFields = useMemo(() => getBulkScalarFields(t), [t]);

  const list = useMemo(() => {
    let all = users.data ?? [];
    if (live && connFilter !== "all") {
      all = all.filter((u) => {
        const on = (liveData.byUser.get(u.name.toLowerCase())?.length ?? 0) > 0;
        return connFilter === "online" ? on : !on;
      });
    }
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((u) => `${u.name} ${u.displayName ?? ""} ${u.email ?? ""}`.toLowerCase().includes(q));
  }, [users.data, filter, live, connFilter, liveData.byUser]);
  const onlineCount = live ? (users.data ?? []).filter((u) => (liveData.byUser.get(u.name.toLowerCase())?.length ?? 0) > 0).length : 0;

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
      push(t("users.toastDeleted"));
      users.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>{t("users.title")}</h1>
        <input
          type="search"
          className="search-input"
          placeholder={t("users.searchPlaceholder")}
          aria-label={t("users.searchAria")}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          {t("users.createUserBtn")}
        </button>
      </div>
      <BulkBar
        resource="users"
        selected={selectedItems}
        fields={[
          ...bulkScalarFields,
          {
            key: "profiles",
            label: t("users.bulkProfiles"),
            type: "multiselect",
            options: (snap.data?.profiles ?? []).map((p) => p.name),
            help: t("users.bulkProfilesHelp"),
          },
          {
            key: "allowedConnectors",
            label: t("users.bulkAllowedConnectors"),
            type: "multiselect",
            options: (snap.data?.connectors ?? []).map((c) => c.name),
            help: t("users.bulkAllowedConnectorsHelp"),
          },
        ]}
        labelOf={(id) => id}
        onDone={() => {
          setSelected(new Set());
          users.reload();
        }}
      />
      <div className="toolbar live-toolbar">
        <label className="check-row">
          <input type="checkbox" checked={live} onChange={(e) => toggleLive(e.target.checked)} />
          <span>{t("users.liveToggle")}</span>
        </label>
        {live && (
          <>
            <label className="check-row" htmlFor="users-conn-filter">
              <span>{t("users.liveFilterLabel")}</span>
            </label>
            <select id="users-conn-filter" value={connFilter} onChange={(e) => setConnFilter(e.target.value as ConnFilter)}>
              <option value="all">{t("users.liveFilterAll")}</option>
              <option value="online">{t("users.liveFilterOnline")}</option>
              <option value="offline">{t("users.liveFilterOffline")}</option>
            </select>
            <span className="muted small" role="status" aria-live="polite">
              {liveData.error
                ? t("users.liveStale", { error: describeError(liveData.error) })
                : liveData.updatedAt
                  ? t("users.liveSummary", { online: onlineCount, total: (users.data ?? []).length, seconds: LIVE_REFRESH_MS / 1000 })
                  : t("users.liveLoading")}
            </span>
          </>
        )}
      </div>
      {live && <p className="muted small">{t("users.liveNotice")}</p>}
      <p className="muted small">{t("users.bulkNotice")}</p>
      {!list.length ? (
        <Empty msg={(users.data ?? []).length ? t("users.noResult") : t("users.emptyList")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th><span className="sr-only">{t("users.thSelect")}</span></th>
                <th>{t("users.thName")}</th>
                <th>{t("users.thDisplay")}</th>
                <th>{t("users.thEmail")}</th>
                <th>{t("users.thRole")}</th>
                <th>{t("users.thState")}</th>
                {live && <th>{t("users.thConnection")}</th>}
                <th>{t("users.thProfiles")}</th>
                <th>{t("users.thVersion")}</th>
                <th>{t("users.thActions")}</th>
              </tr>
            </thead>
            <tbody>
              {list.map((u) => (
                <tr key={u.name}>
                  <td>
                    <SelectBox checked={selected.has(u.name)} onChange={(v) => toggle(u.name, v)} label={t("users.selectUser", { name: u.name })} />
                  </td>
                  <td className="mono">{u.name}</td>
                  <td>{u.displayName ?? "—"}</td>
                  <td>{u.email ?? "—"}</td>
                  <td>
                    <span className={u.admin ? "badge badge-ok" : ""}>{u.admin ? t("users.badgeAdmin") : t("users.badgeUser")}</span>
                  </td>
                  <td>
                    <span className={u.enabled ? "badge badge-ok" : "badge badge-warn"}>
                      {u.enabled ? t("users.badgeEnabled") : t("users.badgeDisabled")}
                    </span>
                  </td>
                  {live && (
                    <td>
                      <ConnectionCell sessions={liveData.byUser.get(u.name.toLowerCase()) ?? []} maxConnections={u.maxConnections} stale={!!liveData.error || !liveData.updatedAt} />
                    </td>
                  )}
                  <td className="mono small">{(u.profiles ?? []).join(", ") || "—"}</td>
                  <td className="mono">v{u.version}</td>
                  <td>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(u)}>{t("users.btnEdit")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSecretsOf(u)}>{t("users.btnSecrets")}</button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(u)}>{t("users.btnDelete")}</button>
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
          title={t("users.dialogDeleteTitle")}
          desc={t("users.dialogDeleteDesc", { name: deleting.name, version: deleting.version })}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
      {secretsOf && <UserSecretsDialog user={secretsOf} onClose={() => setSecretsOf(null)} />}
    </div>
  );
}

function ConnectionCell({ sessions, maxConnections, stale }: { sessions: LiveSession[]; maxConnections?: number; stale: boolean }) {
  const t = useT();
  if (stale && !sessions.length) return <span className="badge badge-muted">{t("users.liveUnknown")}</span>;
  if (!sessions.length) return <span className="badge badge-muted" data-conn="offline">⚪ {t("users.liveOffline")}</span>;
  const limit = maxConnections && maxConnections > 0 ? `/${maxConnections}` : "";
  const kinds = Array.from(new Set(sessions.map((s) => `${s.protocol}${s.profile ? ` · ${s.profile}` : ""}`)));
  const detail = sessions.map((s) => `${s.protocol} · ${s.profile ?? "—"} · ${s.remoteAddress}`).join("\n");
  return (
    <span title={detail} data-conn="online">
      <span className="badge badge-ok">🟢 {t("users.liveOnline", { count: sessions.length, limit })}</span>{" "}
      <span className="mono small">{kinds.join(", ")}</span>
      <span className="sr-only"> — {detail}</span>
    </span>
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
    if (idx === -1) return { ok: false, error: translate("users.errMapInvalidLine", { line: t }) };
    const prof = t.slice(0, idx).trim();
    if (!prof) return { ok: false, error: translate("users.errMapEmptyProfile", { line: t }) };
    const parsed = parseSidList(t.slice(idx + 1).trim());
    if (!parsed.ok) return { ok: false, error: translate("users.errMapProfileError", { prof, error: parsed.error }) };
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
  const t = useT();
  if (!options.length) {
    return (
      <fieldset className="field">
        <legend className="small">{legend}</legend>
        <p className="chip-empty">{t("users.chipsEmpty")}</p>
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
  const t = useT();
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
    if (!n) return setErr(t("users.errNameRequired"));
    if (!isEdit && password.length < 4) return setErr(t("users.errPasswordMin4"));
    if (isEdit && password && password.length < 4) return setErr(t("users.errPasswordMin4Edit"));
    const mc = parseInteger(maxConnections, NaN);
    const rate = parseInteger(allowedEcmRate, NaN);
    if (Number.isNaN(mc)) return setErr(t("users.errMaxConnectionsRequired"));
    if (Number.isNaN(rate)) return setErr(t("users.errAllowedEcmRateRequired"));
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
        push(t("users.toastCreated", { name: body.name }));
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
        push(t("users.toastUpdated", { name: body.name }));
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
    <Dialog title={isEdit ? t("users.dialogEditTitle") : t("users.dialogCreateTitle")} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label={t("users.sectionEssential")}>
          <h3>{t("users.sectionEssential")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("users.fieldName")} htmlFor="u-name">
              <input id="u-name" value={name} onChange={(e) => setName(e.target.value)} disabled={isEdit} required />
            </Field>
            <Field label={t("users.fieldDisplayName")} htmlFor="u-disp"><input id="u-disp" value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
            <Field label={t("users.fieldEmail")} htmlFor="u-email"><input id="u-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
            <Field label={isEdit ? t("users.fieldPasswordEdit") : t("users.fieldPasswordCreate")} htmlFor="u-pass">
              <input id="u-pass" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required={!isEdit} />
            </Field>
            <Field label={t("users.fieldMaxConnections")} htmlFor="u-mc"><input id="u-mc" type="number" value={maxConnections} onChange={(e) => setMaxConnections(e.target.value)} required /></Field>
            <Field label={t("users.fieldAllowedEcmRate")} htmlFor="u-rate"><input id="u-rate" type="number" value={allowedEcmRate} onChange={(e) => setAllowedEcmRate(e.target.value)} required /></Field>
            <Field label={t("users.fieldIpMask")} htmlFor="u-ip"><input id="u-ip" value={ipMask} onChange={(e) => setIpMask(e.target.value)} /></Field>
          </div>
          <div className="form-grid cols-2">
            <label className="check-row"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /><span>{t("users.fieldEnabled")}</span></label>
            <label className="check-row"><input type="checkbox" checked={admin} onChange={(e) => setAdmin(e.target.checked)} /><span>{t("users.fieldAdmin")}</span></label>
            <label className="check-row"><input type="checkbox" checked={debug} onChange={(e) => setDebug(e.target.checked)} /><span>{t("users.fieldDebug")}</span></label>
            <label className="check-row"><input type="checkbox" checked={mapExcluded} onChange={(e) => setMapExcluded(e.target.checked)} /><span>{t("users.fieldMapExcluded")}</span></label>
          </div>
        </section>
        <section aria-label={t("users.sectionAclAria")} className="section">
          <h3>{t("users.sectionAclTitle")}</h3>
          <Chips id="chip-prof" legend={t("users.legendProfiles")} options={profiles} selected={selProfiles} onChange={setSelProfiles} />
          <Chips id="chip-conn" legend={t("users.legendAllowedConnectors")} options={connectors} selected={selConnectors} onChange={setSelConnectors} />
          <div className="field">
            <label htmlFor="ta-allowed">{t("users.fieldAllowedServices")}</label>
            <textarea id="ta-allowed" value={allowedText} disabled={allowedCleared} onChange={(e) => setAllowedText(e.target.value)} placeholder={t("users.placeholderAllowedServices")} />
            {isEdit && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setAllowedCleared(true); setAllowedText(""); }}>
                {allowedCleared ? t("users.btnAllowedCleared") : t("users.btnClearAllowed")}
              </button>
            )}
          </div>
          <div className="field">
            <label htmlFor="ta-blocked">{t("users.fieldBlockedServices")}</label>
            <textarea id="ta-blocked" value={blockedText} disabled={blockedCleared} onChange={(e) => setBlockedText(e.target.value)} placeholder={t("users.placeholderBlockedServices")} />
            {isEdit && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => { setBlockedCleared(true); setBlockedText(""); }}>
                {blockedCleared ? t("users.btnBlockedCleared") : t("users.btnClearBlocked")}
              </button>
            )}
          </div>
        </section>
        {err && <p className="field-error" role="alert">{err}</p>}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("users.btnCancel")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("users.btnSave") : t("users.btnCreate")}</button>
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
  const t = useT();
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
    if (!secretRef.trim()) return setErr(t("users.errSecretRefRequired"));
    if (!/^(env:|file:)/.test(secretRef.trim())) return setErr(t("users.errSecretRefFormat"));
    const mode = cxMode.trim() === "" ? null : Number(cxMode);
    if (mode !== null && ![2, 3].includes(mode)) return setErr(t("users.errCxModeRange"));
    const maxHop = cxMaxHop.trim() === "" ? null : Number(cxMaxHop);
    if (maxHop !== null && (!Number.isInteger(maxHop) || maxHop < 0 || maxHop > 10)) return setErr(t("users.errCxMaxHopRange"));
    let validUntilIso: string | null = null;
    if (validUntil.trim()) {
      const ms = new Date(validUntil).getTime();
      if (Number.isNaN(ms)) return setErr(t("users.errValidUntilInvalid"));
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
      push(t("users.toastSecretSaved", { protocol }));
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
      push(t("users.toastNoVersionedSecret"), "err");
      return;
    }
    try {
      await apiFetch(`/api/admin/users/${encodeURIComponent(user.name)}/secrets/${protocol}/revoke?version=${version}`, { method: "POST" });
      push(t("users.toastSecretRevoked", { protocol }));
      load(protocol);
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <Dialog title={t("users.dialogSecretsTitle", { name: user.name })} onClose={onClose} wide>
      <div className="field">
        <label htmlFor="us-proto">{t("users.fieldProtocol")}</label>
        <select id="us-proto" value={protocol} onChange={(e) => setProtocol(e.target.value)}>
          {PROTOCOLS.map((p) => (
            <option key={p} value={p}>{p}</option>
          ))}
        </select>
      </div>
      {loading ? (
        <p className="muted">{t("users.loading")}</p>
      ) : (
        <form onSubmit={save} noValidate>
          <p className="muted small">
            {t("users.secretsNotice")}
            <SecretRefView value={secret?.secretRef} />
          </p>
          <SecretField
            label={t("users.fieldSecretRef")}
            value={secretRef}
            onChange={setSecretRef}
            purpose="user-protocol-secret"
            owner={user.name}
            help={t("users.fieldSecretRefHelp")}
          />
          <div className="form-grid cols-2">
            <label className="check-row">
              <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
              <span>{t("users.fieldSecretActive")}</span>
            </label>
            <Field label={t("users.fieldValidUntil")} htmlFor="us-valid">
              <input id="us-valid" type="datetime-local" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} />
            </Field>
          </div>
          {secret && (
            <p className="muted small">
              {t("users.secretMeta", {
                version: secret.version ?? "—",
                updatedBy: secret.updatedBy ?? "—",
              })}
            </p>
          )}
          <section aria-label={t("users.sectionCacheexAria")} className="section">
            <h3>{t("users.sectionCacheexTitle")}</h3>
            <p className="muted small">{t("users.cacheexNotice")}</p>
            <div className="form-grid cols-2">
              <div className="field">
                <label htmlFor="us-cxmode">{t("users.fieldCxMode")}</label>
                <select id="us-cxmode" value={cxMode} onChange={(e) => setCxMode(e.target.value)}>
                  <option value="">{t("users.cxModeOff")}</option>
                  <option value="2">{t("users.cxMode2")}</option>
                  <option value="3">{t("users.cxMode3")}</option>
                </select>
              </div>
              <Field label={t("users.fieldCxMaxHop")} htmlFor="us-cxhop">
                <input id="us-cxhop" type="number" value={cxMaxHop} onChange={(e) => setCxMaxHop(e.target.value)} />
              </Field>
              <Field label={t("users.fieldCxFilter")} htmlFor="us-cxfilter">
                <input id="us-cxfilter" value={cxFilter} onChange={(e) => setCxFilter(e.target.value)} placeholder={t("users.placeholderCxFilter")} />
              </Field>
            </div>
          </section>
          {err && <p className="field-error" role="alert">{err}</p>}
          <div className="dialog-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>{t("users.btnClose")}</button>
            {secret?.secretRef && (
              <button type="button" className="btn btn-ghost" onClick={revoke}>{t("users.btnRevoke")}</button>
            )}
            <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : t("users.btnSaveSecret")}</button>
          </div>
        </form>
      )}
    </Dialog>
  );
}
