import { useCallback, useEffect, useMemo, useState } from "react";
import { apiFetch, apiJson, describeError, parseCLine, parseInteger } from "../api";
import type { Connector, ConnectorRuntimeSnapshot } from "../types";
import { BulkBar, SelectBox, type BulkFieldDef } from "../components/BulkBar";
import { SecretField, SecretRefView } from "../components/SecretField";
import { Pagination } from "../components/Pagination";
import { ConfirmDialog, Dialog, Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { useT, type MessageKey } from "../i18n";

function getBulkFields(t: (k: MessageKey, params?: Record<string, string | number>) => string): BulkFieldDef[] {
  return [
    { key: "enabled", label: t("connectors.bulkEnabled"), type: "boolean" as const },
    { key: "debug", label: t("connectors.bulkDebug"), type: "boolean" as const },
    { key: "connectTimeoutMs", label: t("connectors.bulkConnectTimeoutMs"), type: "number" as const },
    { key: "reconnectDelayMs", label: t("connectors.bulkReconnectDelayMs"), type: "number" as const },
    { key: "keepAliveSec", label: t("connectors.bulkKeepAliveSec"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "minDelayMs", label: t("connectors.bulkMinDelayMs"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "maxQueue", label: t("connectors.bulkMaxQueue"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "newcamdMaxMsgSize", label: t("connectors.bulkNewcamdMaxMsgSize"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "radegastResponseTimeoutMs", label: t("connectors.bulkRadegastResponseTimeoutMs"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "cccamEnabled", label: t("connectors.bulkCccamEnabled"), type: "boolean" as const },
    { key: "cccamVersion", label: t("connectors.bulkCccamVersion"), type: "text" as const, resettable: true, resetHint: "NULL" },
    { key: "priority", label: t("connectors.bulkPriority"), type: "number" as const },
    { key: "maxCapacity", label: t("connectors.bulkMaxCapacity"), type: "number" as const },
    { key: "proxyName", label: t("connectors.bulkProxyName"), type: "text" as const, resettable: true, resetHint: t("connectors.bulkResetHintDirect") },
    { key: "cardDiscovery", label: t("connectors.bulkCardDiscovery"), type: "boolean" as const },
    { key: "reshareGrant", label: t("connectors.bulkReshareGrant"), type: "number" as const, resettable: true, resetHint: "NULL" },
    { key: "newcamdDialect", label: t("connectors.bulkNewcamdDialect"), type: "text" as const, resettable: true, resetHint: t("connectors.bulkResetHintLegacy") },
    { key: "reshareCap", label: t("connectors.bulkReshareCap"), type: "number" as const, resettable: true, resetHint: t("connectors.bulkResetHintZero") },
    { key: "newcamdPasswordEncoding", label: t("connectors.bulkNewcamdPasswordEncoding"), type: "text" as const, resettable: true, resetHint: t("connectors.bulkResetHintPlaintext") },
    { key: "cacheexMode", label: t("connectors.bulkCacheexMode"), type: "number" as const, resettable: true, resetHint: t("connectors.bulkResetHintOff") },
    { key: "cacheexFilter", label: t("connectors.bulkCacheexFilter"), type: "text" as const, resettable: true, resetHint: "NULL" },
    { key: "cacheexMaxSend", label: t("connectors.bulkCacheexMaxSend"), type: "number" as const, resettable: true, resetHint: t("connectors.bulkResetHint2000") },
    { key: "cacheexMaxHop", label: t("connectors.bulkCacheexMaxHop"), type: "number" as const, resettable: true, resetHint: t("connectors.bulkResetHint10") },
  ];
}

const PROTOCOLS = ["newcamd", "radegast", "cccam", "cs378x"];

export function ConnectorsPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<Connector | null | "new">(null);
  const [deleting, setDeleting] = useState<Connector | null>(null);
  const [importing, setImporting] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [runtimeList, setRuntimeList] = useState<ConnectorRuntimeSnapshot[]>([]);
  const [runtimeError, setRuntimeError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);

  const bulkFields = useMemo(() => getBulkFields(t), [t]);

  const loadRuntime = useCallback(async () => {
    try {
      let allItems: ConnectorRuntimeSnapshot[] = [];
      let currentPage = 0;
      let totalPages = 1;
      const MAX_PAGES = 50;

      while (currentPage < totalPages && currentPage < MAX_PAGES) {
        const { body } = await apiJson<{
          payload?: {
            items?: ConnectorRuntimeSnapshot[];
            totalPages?: number;
            page?: number;
          } | ConnectorRuntimeSnapshot[];
        }>(`/api/admin/connectors?page=${currentPage}&size=100`);

        if (Array.isArray(body?.payload)) {
          allItems = body.payload;
          break;
        } else if (body?.payload && typeof body.payload === "object") {
          const p = body.payload;
          if (p.items && Array.isArray(p.items)) {
            allItems = allItems.concat(p.items);
          }
          totalPages = typeof p.totalPages === "number" ? p.totalPages : 1;
          currentPage++;
        } else {
          break;
        }
      }
      setRuntimeList(allItems);
      setRuntimeError(null);
    } catch {
      setRuntimeError(t("connectors.runtimeError"));
    }
  }, [t]);

  useEffect(() => {
    loadRuntime();
  }, [loadRuntime, snap.data]);

  const runtimeMap = useMemo(() => {
    const m = new Map<string, ConnectorRuntimeSnapshot>();
    for (const r of runtimeList) {
      if (r && r.name) m.set(r.name, r);
    }
    return m;
  }, [runtimeList]);

  const list = useMemo(() => {
    const all = snap.data?.connectors ?? [];
    if (!filter.trim()) return all;
    const q = filter.toLowerCase();
    return all.filter((c) => {
      const r = runtimeMap.get(c.name);
      const stateStr = r?.state || "";
      const caidsStr = r?.caids?.join(" ") || "";
      const provStr = r?.providers?.join(" ") || "";
      const full = `${c.name} ${c.profileName ?? ""} ${c.protocol ?? ""} ${c.host ?? ""} ${stateStr} ${caidsStr} ${provStr}`.toLowerCase();
      return full.includes(q);
    });
  }, [snap.data, filter, runtimeMap]);

  // Réinitialiser la page quand le filtre change
  useEffect(() => {
    setPage(1);
  }, [filter, pageSize]);

  const totalPages = Math.ceil(list.length / pageSize) || 1;
  const safePage = Math.max(1, Math.min(page, totalPages));
  const pagedList = useMemo(() => {
    const start = (safePage - 1) * pageSize;
    return list.slice(start, start + pageSize);
  }, [list, safePage, pageSize]);

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;

  const toggle = (name: string, v: boolean) =>
    setSelected((s) => {
      const n = new Set(s);
      if (v) n.add(name);
      else n.delete(name);
      return n;
    });
  const selectedItems = list.filter((c) => selected.has(c.name)).map((c) => ({ id: c.name, item: c }));

  async function doDelete(c: Connector) {
    try {
      const res = await apiFetch(`/api/admin/config/connectors/${encodeURIComponent(c.name)}?version=${c.version}`, {
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
      push(t("connectors.toastDeleted"));
      snap.reload();
    } catch (e) {
      push(describeError(e), "err");
    }
  }

  return (
    <div className="card">
      <div className="toolbar">
        <h1>{t("connectors.title")}</h1>
        <input
          type="search"
          className="search-input"
          placeholder={t("connectors.searchPlaceholder")}
          aria-label={t("connectors.searchAria")}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setImporting(true)}>
          {t("connectors.importCLineBtn")}
        </button>
        <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing("new")}>
          {t("connectors.createBtn")}
        </button>
      </div>
      {runtimeError && (
        <div className="small muted" style={{ marginBottom: 8, color: "var(--warn)" }}>
          ⚠ {runtimeError}
        </div>
      )}
      <p className="muted small">
        {t("connectors.cacheexNotice")}
      </p>
      <BulkBar
        resource="connectors"
        selected={selectedItems}
        fields={bulkFields}
        labelOf={(id) => id}
        onDone={() => {
          setSelected(new Set());
          snap.reload();
        }}
      />
      {!list.length ? (
        <Empty msg={snap.data?.connectors?.length ? t("connectors.noResult") : t("connectors.emptyList")} />
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th><span className="sr-only">{t("connectors.thSelect")}</span></th>
                  <th>{t("connectors.thName")}</th>
                  <th>{t("connectors.thState")}</th>
                  <th>{t("connectors.thProfile")}</th>
                  <th>{t("connectors.thProtocol")}</th>
                  <th>{t("connectors.thHostPort")}</th>
                  <th>{t("connectors.thCaids")}</th>
                  <th>{t("connectors.thProviders")}</th>
                  <th>{t("connectors.thHits")}</th>
                  <th>{t("connectors.thCacheex")}</th>
                  <th>{t("connectors.thPriority")}</th>
                  <th>{t("connectors.thVersion")}</th>
                  <th>{t("connectors.thActions")}</th>
                </tr>
              </thead>
              <tbody>
                {pagedList.map((c) => {
                  const r = runtimeMap.get(c.name);
                  let st = "UNKNOWN";
                  let badgeClass = "badge-muted";
                  if (c.enabled === false) {
                    st = "DISABLED";
                    badgeClass = "badge-muted";
                  } else if (r) {
                    st = r.state || (r.connected ? "CONNECTED" : "DEAD");
                    if (st === "CONNECTED") badgeClass = "badge-ok";
                    else if (st === "UNSTABLE") badgeClass = "badge-warn";
                    else if (st === "DEAD") badgeClass = "badge-danger";
                  } else {
                    st = "—";
                    badgeClass = "badge-muted";
                  }

                  return (
                    <tr key={c.name}>
                      <td>
                        <SelectBox checked={selected.has(c.name)} onChange={(v) => toggle(c.name, v)} label={t("connectors.selectAria", { name: c.name })} />
                      </td>
                      <td className="mono">{c.name}</td>
                      <td>
                        <span className={`badge ${badgeClass}`}>{st}</span>
                      </td>
                      <td>{c.profileName ?? "—"}</td>
                      <td><code className="mono">{c.protocol}</code></td>
                      <td className="mono small">{c.host}:{c.port}</td>
                      <td className="small">
                        {r?.caids && r.caids.length > 0 ? (
                          <div className="chip-group">
                            {r.caids.map((caid) => (
                              <span key={caid} className="chip mono">{caid}</span>
                            ))}
                          </div>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td className="small">
                        {r?.providers && r.providers.length > 0 ? (
                          <div className="chip-group">
                            {r.providers.map((prov) => (
                              <span key={prov} className="chip mono">{prov}</span>
                            ))}
                          </div>
                        ) : (
                          <span className="muted">—</span>
                        )}
                      </td>
                      <td className="mono">{r?.hits !== undefined ? r.hits.toLocaleString() : "—"}</td>
                      <td className="small">
                        {c.cacheexMode === 2 ? t("connectors.cacheexMode2") : c.cacheexMode === 3 ? t("connectors.cacheexMode3") : t("connectors.cacheexOff")}
                      </td>
                      <td className="mono">{c.priority ?? "—"}</td>
                      <td className="mono">v{c.version}</td>
                      <td>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditing(c)}>{t("connectors.editBtn")}</button>
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDeleting(c)}>{t("connectors.deleteBtn")}</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination
            page={safePage}
            totalPages={totalPages}
            totalElements={list.length}
            pageSize={pageSize}
            pageSizeOptions={[10, 25, 50, 100]}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            label={t("connectors.paginationLabel")}
          />
        </>
      )}
      {editing && (
        <ConnectorDialog
          existing={editing === "new" ? null : editing}
          profiles={(snap.data?.profiles ?? []).map((p) => p.name)}
          proxies={(snap.data?.proxies ?? []).map((p) => p.name)}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            snap.reload();
          }}
        />
      )}
      {deleting && (
        <ConfirmDialog
          title={t("connectors.dialogDeleteTitle")}
          desc={t("connectors.dialogDeleteDesc", { name: deleting.name, version: deleting.version })}
          onClose={() => setDeleting(null)}
          onConfirm={() => doDelete(deleting)}
        />
      )}
      {importing && (
        <CLineImportDialog
          profiles={(snap.data?.profiles ?? []).map((p) => p.name)}
          onClose={() => setImporting(false)}
          onSaved={() => {
            setImporting(false);
            snap.reload();
          }}
        />
      )}
    </div>
  );
}

interface ConnForm {
  name: string;
  profileName: string;
  protocol: string;
  host: string;
  port: string;
  username: string;
  passwordRef: string;
  desKeyRef: string;
  newcamdClientIdRef: string;
  connectTimeoutMs: string;
  reconnectDelayMs: string;
  keepAliveSec: string;
  minDelayMs: string;
  maxQueue: string;
  newcamdMaxMsgSize: string;
  radegastResponseTimeoutMs: string;
  cccamNodeId: string;
  cccamVersion: string;
  cacheexMode: string;
  cacheexFilter: string;
  cacheexMaxSend: string;
  cacheexMaxHop: string;
  chameleonProfiles: string;
  conaxReaderIndex: string;
  newcamdDialect: string;
  newcamdPasswordEncoding: string;
  reshareGrant: string;
  reshareCap: string;
  priority: string;
  maxCapacity: string;
  proxyName: string;
  enabled: boolean;
  debug: boolean;
  cccamEnabled: boolean;
  cardDiscovery: boolean;
}

function toForm(c: Connector | null, profiles: string[]): ConnForm {
  return {
    name: c?.name ?? "",
    profileName: c?.profileName ?? profiles[0] ?? "",
    protocol: c?.protocol ?? "newcamd",
    host: c?.host ?? "",
    port: String(c?.port ?? ""),
    username: c?.username ?? "",
    passwordRef: c?.passwordRef ?? "",
    desKeyRef: c?.desKeyRef ?? "",
    newcamdClientIdRef: c?.newcamdClientIdRef ?? "",
    connectTimeoutMs: String(c?.connectTimeoutMs ?? 5000),
    reconnectDelayMs: String(c?.reconnectDelayMs ?? 5000),
    keepAliveSec: c?.keepAliveSec != null ? String(c.keepAliveSec) : "",
    minDelayMs: c?.minDelayMs != null ? String(c.minDelayMs) : "",
    maxQueue: c?.maxQueue != null ? String(c.maxQueue) : "",
    newcamdMaxMsgSize: c?.newcamdMaxMsgSize != null ? String(c.newcamdMaxMsgSize) : "",
    radegastResponseTimeoutMs: c?.radegastResponseTimeoutMs != null ? String(c.radegastResponseTimeoutMs) : "",
    cccamNodeId: c?.cccamNodeId ?? "",
    cccamVersion: c?.cccamVersion ?? "",
    cacheexMode: c?.cacheexMode != null ? String(c.cacheexMode) : "",
    cacheexFilter: c?.cacheexFilter ?? "",
    cacheexMaxSend: c?.cacheexMaxSend != null ? String(c.cacheexMaxSend) : "",
    cacheexMaxHop: c?.cacheexMaxHop != null ? String(c.cacheexMaxHop) : "",
    chameleonProfiles: c?.chameleonProfiles ?? "",
    conaxReaderIndex: c?.conaxReaderIndex != null ? String(c.conaxReaderIndex) : "",
    newcamdDialect: c?.newcamdDialect ?? "legacy",
    newcamdPasswordEncoding: c?.newcamdPasswordEncoding ?? "plaintext",
    reshareGrant: c?.reshareGrant != null ? String(c.reshareGrant) : "",
    reshareCap: c?.reshareCap != null ? String(c.reshareCap) : "",
    priority: String(c?.priority ?? 50),
    maxCapacity: String(c?.maxCapacity ?? 0),
    proxyName: c?.proxyName ?? "",
    enabled: c?.enabled ?? true,
    debug: c?.debug ?? false,
    cccamEnabled: c?.cccamEnabled ?? false,
    cardDiscovery: c?.cardDiscovery ?? false,
  };
}

function ConnectorDialog({
  existing,
  profiles,
  proxies,
  onClose,
  onSaved,
}: {
  existing: Connector | null;
  profiles: string[];
  proxies: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const t = useT();
  const { push } = useToast();
  const isEdit = !!existing;
  const [f, setF] = useState<ConnForm>(() => toForm(existing, profiles));
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const set = (k: keyof ConnForm) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setF({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    const name = f.name.trim();
    if (!name) return setErr(t("connectors.errNameRequired"));
    if (!f.profileName.trim()) return setErr(t("connectors.errProfileRequired"));
    if (!f.host.trim()) return setErr(t("connectors.errHostRequired"));
    const port = parseInteger(f.port, NaN);
    if (!(port >= 1 && port <= 65535)) return setErr(t("connectors.errPortRange"));
    const opt = (v: string) => (v.trim() === "" ? null : parseInteger(v.trim(), NaN));
    const num = (v: string, d: number) => (v.trim() === "" ? d : parseInteger(v.trim(), NaN));
    const cacheexMode = f.cacheexMode.trim() === "" ? null : parseInteger(f.cacheexMode, NaN);
    if (cacheexMode !== null && ![0, 2, 3].includes(cacheexMode)) return setErr(t("connectors.errCacheexMode"));
    const cacheexMaxHop = opt(f.cacheexMaxHop);
    if (cacheexMaxHop !== null && (cacheexMaxHop < 0 || cacheexMaxHop > 10)) return setErr(t("connectors.errCacheexMaxHop"));
    const cacheexMaxSend = opt(f.cacheexMaxSend);
    if (cacheexMaxSend !== null && (cacheexMaxSend < 0 || cacheexMaxSend > 10000))
      return setErr(t("connectors.errCacheexMaxSend"));
    if (f.passwordRef && !/^(env:|file:)/.test(f.passwordRef)) return setErr(t("connectors.errPasswordRef"));
    if (f.desKeyRef && !/^(env:|file:)/.test(f.desKeyRef)) return setErr(t("connectors.errDesKeyRef"));
    setBusy(true);
    try {
      const payload = {
        profileName: f.profileName.trim(),
        protocol: f.protocol,
        enabled: f.enabled,
        debug: f.debug,
        host: f.host.trim(),
        port,
        username: f.username.trim() || null,
        passwordRef: f.passwordRef.trim() || null,
        desKeyRef: f.desKeyRef.trim() || null,
        connectTimeoutMs: num(f.connectTimeoutMs, 5000),
        reconnectDelayMs: num(f.reconnectDelayMs, 5000),
        keepAliveSec: opt(f.keepAliveSec),
        minDelayMs: opt(f.minDelayMs),
        maxQueue: opt(f.maxQueue),
        newcamdMaxMsgSize: opt(f.newcamdMaxMsgSize),
        newcamdClientIdRef: f.newcamdClientIdRef.trim() || null,
        radegastResponseTimeoutMs: opt(f.radegastResponseTimeoutMs),
        cccamEnabled: f.cccamEnabled,
        cccamNodeId: f.cccamNodeId.trim() || null,
        cccamVersion: f.cccamVersion.trim() || null,
        cacheexMode,
        cacheexFilter: f.cacheexFilter.trim() || null,
        cacheexMaxSend,
        cacheexMaxHop,
        chameleonProfiles: f.chameleonProfiles.trim() || null,
        conaxReaderIndex: opt(f.conaxReaderIndex),
        cardDiscovery: f.cardDiscovery,
        reshareGrant: opt(f.reshareGrant),
        newcamdDialect: f.protocol === "newcamd" ? f.newcamdDialect : "legacy",
        newcamdPasswordEncoding: f.protocol === "newcamd" ? f.newcamdPasswordEncoding : "plaintext",
        reshareCap: opt(f.reshareCap),
        priority: num(f.priority, 50),
        maxCapacity: num(f.maxCapacity, 0),
        proxyName: f.proxyName.trim() || null,
        version: existing?.version ?? 1,
      };
      const { body } = await apiJson<{ version: number }>(`/api/admin/config/connectors/${encodeURIComponent(name)}`, {
        method: "PUT",
        body: payload,
      });
      push(isEdit ? t("connectors.toastUpdated", { version: body.version }) : t("connectors.toastCreated", { version: body.version }));
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
    <Dialog title={isEdit ? t("connectors.dialogEditTitle") : t("connectors.dialogCreateTitle")} onClose={onClose} wide>
      <form onSubmit={submit} noValidate>
        <section aria-label={t("connectors.sectionEssential")}>
          <h3>{t("connectors.sectionEssential")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("connectors.fieldName")} htmlFor="co-name">
              <input id="co-name" value={f.name} onChange={set("name")} disabled={isEdit} required />
            </Field>
            <div className="field">
              <label htmlFor="co-prof">{t("connectors.fieldProfile")}</label>
              <select id="co-prof" value={f.profileName} onChange={set("profileName")} required>
                {profiles.map((p) => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="co-proto">{t("connectors.fieldProtocol")}</label>
              <select id="co-proto" value={f.protocol} onChange={set("protocol")}>
                {PROTOCOLS.map((p) => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            </div>
            <Field label={t("connectors.fieldHost")} htmlFor="co-host">
              <input id="co-host" value={f.host} onChange={set("host")} required />
            </Field>
            <Field label={t("connectors.fieldPort")} htmlFor="co-port">
              <input id="co-port" type="number" value={f.port} onChange={set("port")} required />
            </Field>
            <Field label={t("connectors.fieldUsername")} htmlFor="co-user">
              <input id="co-user" value={f.username} onChange={set("username")} autoComplete="off" />
            </Field>
            <label className="check-row"><input type="checkbox" checked={f.enabled} onChange={set("enabled")} /><span>{t("connectors.fieldEnabled")}</span></label>
            <label className="check-row"><input type="checkbox" checked={f.debug} onChange={set("debug")} /><span>{t("connectors.fieldDebug")}</span></label>
          </div>
        </section>
        <section aria-label={t("connectors.sectionSecretsAria")} className="section">
          <h3>{t("connectors.sectionSecretsTitle")}</h3>
          <SecretField
            label="passwordRef"
            value={f.passwordRef}
            onChange={(v) => setF({ ...f, passwordRef: v })}
            purpose="connector-password"
            owner={f.name || t("connectors.ownerDefault")}
            help={t("connectors.passwordRefHelp")}
          />
          <SecretField
            label="desKeyRef"
            value={f.desKeyRef}
            onChange={(v) => setF({ ...f, desKeyRef: v })}
            purpose="connector-des-key"
            owner={f.name || t("connectors.ownerDefault")}
            kind="deskey"
          />
          <Field label={t("connectors.fieldNewcamdClientIdRef")} htmlFor="co-ncid">
            <input id="co-ncid" value={f.newcamdClientIdRef} onChange={set("newcamdClientIdRef")} autoComplete="off" spellCheck={false} />
          </Field>
          {isEdit && (
            <p className="muted small">
              {t("connectors.currentRefs")} <SecretRefView value={existing.passwordRef} /> <SecretRefView value={existing.desKeyRef} />
            </p>
          )}
        </section>
        <section aria-label={t("connectors.sectionCacheexAria")} className="section">
          <h3>{t("connectors.sectionCacheexTitle")}</h3>
          <div className="form-grid cols-2">
            <div className="field">
              <label htmlFor="co-cxmode">{t("connectors.fieldCacheexMode")}</label>
              <select id="co-cxmode" value={f.cacheexMode} onChange={set("cacheexMode")}>
                <option value="">{t("connectors.optCacheexOff")}</option>
                <option value="2">{t("connectors.optCacheexRecv")}</option>
                <option value="3">{t("connectors.optCacheexSend")}</option>
                <option value="0">{t("connectors.optCacheexOffExplicit")}</option>
              </select>
            </div>
            <Field label={t("connectors.fieldCacheexFilter")} htmlFor="co-cxfilter" >
              <input id="co-cxfilter" value={f.cacheexFilter} onChange={set("cacheexFilter")} placeholder={t("connectors.placeholderCacheexFilter")} />
            </Field>
            <Field label={t("connectors.fieldCacheexMaxSend")} htmlFor="co-cxsend">
              <input id="co-cxsend" type="number" value={f.cacheexMaxSend} onChange={set("cacheexMaxSend")} />
            </Field>
            <Field label={t("connectors.fieldCacheexMaxHop")} htmlFor="co-cxhop">
              <input id="co-cxhop" type="number" value={f.cacheexMaxHop} onChange={set("cacheexMaxHop")} />
            </Field>
          </div>
          <p className="muted small">{t("connectors.cacheexGateNotice")}</p>
        </section>
        <section aria-label={t("connectors.sectionAdvanced")} className="section section-advanced">
          <h3>{t("connectors.sectionAdvanced")}</h3>
          <div className="form-grid cols-2">
            <Field label={t("connectors.fieldConnectTimeoutMs")} htmlFor="co-cto"><input id="co-cto" type="number" value={f.connectTimeoutMs} onChange={set("connectTimeoutMs")} required /></Field>
            <Field label={t("connectors.fieldReconnectDelayMs")} htmlFor="co-rd"><input id="co-rd" type="number" value={f.reconnectDelayMs} onChange={set("reconnectDelayMs")} required /></Field>
            <Field label={t("connectors.fieldKeepAliveSec")} htmlFor="co-ka"><input id="co-ka" type="number" value={f.keepAliveSec} onChange={set("keepAliveSec")} /></Field>
            <Field label={t("connectors.fieldMinDelayMs")} htmlFor="co-md"><input id="co-md" type="number" value={f.minDelayMs} onChange={set("minDelayMs")} /></Field>
            <Field label={t("connectors.fieldMaxQueue")} htmlFor="co-mq"><input id="co-mq" type="number" value={f.maxQueue} onChange={set("maxQueue")} /></Field>
            <Field label={t("connectors.fieldNewcamdMaxMsgSize")} htmlFor="co-nmms"><input id="co-nmms" type="number" value={f.newcamdMaxMsgSize} onChange={set("newcamdMaxMsgSize")} /></Field>
            <Field label={t("connectors.fieldRadegastResponseTimeoutMs")} htmlFor="co-rrt"><input id="co-rrt" type="number" value={f.radegastResponseTimeoutMs} onChange={set("radegastResponseTimeoutMs")} /></Field>
            <Field label={t("connectors.fieldCccamNodeId")} htmlFor="co-node"><input id="co-node" value={f.cccamNodeId} onChange={set("cccamNodeId")} /></Field>
            <Field label={t("connectors.fieldCccamVersion")} htmlFor="co-ver"><input id="co-ver" value={f.cccamVersion} onChange={set("cccamVersion")} /></Field>
            <Field label={t("connectors.fieldChameleonProfiles")} htmlFor="co-cham"><input id="co-cham" value={f.chameleonProfiles} onChange={set("chameleonProfiles")} /></Field>
            <Field label={t("connectors.fieldConaxReaderIndex")} htmlFor="co-conax"><input id="co-conax" type="number" value={f.conaxReaderIndex} onChange={set("conaxReaderIndex")} /></Field>
            {f.protocol === "newcamd" && (
              <>
                <div className="field">
                  <label htmlFor="co-dialect">{t("connectors.fieldNewcamdDialect")}</label>
                  <select id="co-dialect" value={f.newcamdDialect} onChange={set("newcamdDialect")}>
                    <option value="legacy">legacy</option>
                    <option value="oscam_ncd525">oscam_ncd525</option>
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="co-enc">{t("connectors.fieldPasswordEncoding")}</label>
                  <select id="co-enc" value={f.newcamdPasswordEncoding} onChange={set("newcamdPasswordEncoding")}>
                    <option value="plaintext">{t("connectors.optPlaintext")}</option>
                    <option value="md5crypt">{t("connectors.optMd5crypt")}</option>
                  </select>
                </div>
              </>
            )}
            <Field label={t("connectors.reshareGrant")} htmlFor="co-rg"><input id="co-rg" type="number" value={f.reshareGrant} onChange={set("reshareGrant")} /></Field>
            <Field label={t("connectors.reshareCap")} htmlFor="co-rc"><input id="co-rc" type="number" value={f.reshareCap} onChange={set("reshareCap")} /></Field>
            <Field label={t("connectors.fieldPriority")} htmlFor="co-prio"><input id="co-prio" type="number" value={f.priority} onChange={set("priority")} required /></Field>
            <Field label={t("connectors.fieldMaxCapacity")} htmlFor="co-cap"><input id="co-cap" type="number" value={f.maxCapacity} onChange={set("maxCapacity")} required /></Field>
            <div className="field">
              <label htmlFor="co-proxy">{t("connectors.fieldProxy")}</label>
              <select id="co-proxy" value={f.proxyName} onChange={set("proxyName")}>
                <option value="">{t("connectors.optDirectConnection")}</option>
                {proxies.map((p) => (
                  <option key={p} value={p}>{p}</option>
                ))}
              </select>
            </div>
            <label className="check-row"><input type="checkbox" checked={f.cccamEnabled} onChange={set("cccamEnabled")} /><span>{t("connectors.fieldCccamEnabled")}</span></label>
            <label className="check-row"><input type="checkbox" checked={f.cardDiscovery} onChange={set("cardDiscovery")} /><span>{t("connectors.fieldCardDiscovery")}</span></label>
          </div>
          {isEdit && <p className="muted small">{t("connectors.versionNotice", { version: existing.version })}</p>}
        </section>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("connectors.cancelBtn")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : isEdit ? t("connectors.saveBtn") : t("connectors.createSubmitBtn")}</button>
        </div>
      </form>
    </Dialog>
  );
}

function CLineImportDialog({ profiles, onClose, onSaved }: { profiles: string[]; onClose: () => void; onSaved: () => void }) {
  const t = useT();
  const { push } = useToast();
  const [line, setLine] = useState("");
  const [name, setName] = useState("");
  const [profileName, setProfileName] = useState(profiles[0] ?? "");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState("");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setDone("");
    const parsed = parseCLine(line);
    if (!parsed.ok) return setErr(parsed.error);
    if (!name.trim()) return setErr(t("connectors.errImportNameRequired"));
    if (!profileName) return setErr(t("connectors.errImportProfileRequired"));
    setBusy(true);
    try {
      // Le mot de passe C-line est matérialisé serveur (jamais stocké en clair).
      const slug = name.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-");
      const { body: sec } = await apiJson<{ secretRef: string }>("/api/admin/secrets/materialize", {
        method: "POST",
        body: { purpose: "connector-password", owner: slug, value: parsed.value.password },
      });
      const { body } = await apiJson<{ version: number }>(
        `/api/admin/config/connectors/${encodeURIComponent(name.trim())}`,
        {
          method: "PUT",
          body: {
            profileName,
            protocol: "cccam",
            enabled: true,
            debug: false,
            host: parsed.value.host,
            port: parsed.value.port,
            username: parsed.value.username,
            passwordRef: sec.secretRef,
            connectTimeoutMs: 5000,
            reconnectDelayMs: 5000,
            cccamEnabled: true,
            priority: 50,
            maxCapacity: 0,
            version: 1,
          },
        },
      );
      setDone(t("connectors.importSuccess", { name: name.trim(), version: body.version }));
      push(t("connectors.toastImported"));
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
    <Dialog title={t("connectors.dialogImportTitle")} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p className="muted small">{t("connectors.importHelpPrefix")} <code className="mono">C: host port user pass</code>. {t("connectors.importHelpSuffix")}</p>
        <Field label={t("connectors.fieldCLine")} htmlFor="cl-line">
          <input id="cl-line" value={line} onChange={(e) => setLine(e.target.value)} placeholder={t("connectors.placeholderCLine")} autoComplete="off" spellCheck={false} required />
        </Field>
        <Field label={t("connectors.fieldImportName")} htmlFor="cl-name">
          <input id="cl-name" value={name} onChange={(e) => setName(e.target.value)} required />
        </Field>
        <div className="field">
          <label htmlFor="cl-prof">{t("connectors.fieldImportProfile")}</label>
          <select id="cl-prof" value={profileName} onChange={(e) => setProfileName(e.target.value)} required>
            {profiles.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </div>
        {err && (
          <p className="field-error" role="alert">
            {err}
          </p>
        )}
        {done && (
          <p className="alert alert-ok" role="status">
            {done}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>{t("connectors.closeBtn")}</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>{busy ? "…" : t("connectors.importSubmitBtn")}</button>
        </div>
      </form>
    </Dialog>
  );
}
