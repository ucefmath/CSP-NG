import { Fragment, useState } from "react";
import { apiFetch, apiJson, describeError, formatDate } from "../api";
import { formatHexId, parseHexList } from "../hex";
import { translate, useT, type MessageKey } from "../i18n";
import type {
  CacheExStats,
  CacheHitsResponse,
  PeerQualityResponse,
  QuarantineResponse,
  QuorumResponse,
} from "../types";
import { Empty, Field, LoadError, Loading, useSnapshot, useToast } from "../components/ui";

// Stats CacheEx par pair. Sémantique modes OSCam asymétriques :
// connecteur sortant mode 2 = nous recevons ; mode 3 = nous émettons.
// Entrant user mode 2 = nous émettons vers le client ; mode 3 = nous recevons.

// Causes de rejet backend (CacheExStatsRegistry.REASONS, toujours pré-renseignées
// côté backend : les zéros affichés sont des compteurs réels, pas des valeurs fabriquées).
const REASON_KEY_MAP: Record<string, MessageKey> = {
  loop: "cacheex.reasonLoop",
  hop: "cacheex.reasonHop",
  checksum: "cacheex.reasonChecksum",
  filtered: "cacheex.reasonFiltered",
  invalid: "cacheex.reasonInvalid",
  queue: "cacheex.reasonQueue",
  flood: "cacheex.reasonFlood",
  quarantine: "cacheex.reasonQuarantine",
  quorum: "cacheex.reasonQuorum",
};

export function formatServiceLabel(s: { serviceKey?: string; caid?: number; provid?: number; srvid?: number; channelName?: string | null }): string {
  const key = s.serviceKey || `${(s.caid ?? 0).toString(16).toUpperCase().padStart(4, "0")}@${(s.provid ?? 0).toString(16).toUpperCase().padStart(6, "0")}:${(s.srvid ?? 0).toString(16).toUpperCase().padStart(4, "0")}`;
  return s.channelName ? `${key} — ${s.channelName}` : key;
}

export function formatReasons(reasons: Record<string, number> | null | undefined): string {
  if (!reasons) return translate("cacheex.unknown");
  const parts = Object.entries(reasons)
    .filter(([, v]) => (v ?? 0) > 0)
    .map(([k, v]) => {
      const key = REASON_KEY_MAP[k];
      const label = key ? translate(key) : k;
      return `${label}:${v}`;
    });
  return parts.length ? parts.join(" ") : "—";
}

function floodOf(reasons: Record<string, number> | null | undefined): string {
  if (!reasons) return translate("cacheex.unknown");
  if (!("flood" in reasons)) return "—";
  const v = reasons.flood ?? 0;
  return String(v);
}

function directionHint(mode: number | undefined, direction: string | undefined): string {
  if (direction === "outbound") {
    if (mode === 2) return translate("cacheex.dirOutMode2");
    if (mode === 3) return translate("cacheex.dirOutMode3");
  } else if (direction === "inbound") {
    if (mode === 2) return translate("cacheex.dirInMode2");
    if (mode === 3) return translate("cacheex.dirInMode3");
  }
  return "—";
}

export function CacheExPage() {
  const t = useT();
  const { push } = useToast();
  const [resetting, setResetting] = useState(false);
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const st = useSnapshot(async () => {
    const { body } = await apiJson<CacheExStats>("/api/admin/cacheex/stats");
    return body;
  });

  async function reset() {
    setResetting(true);
    try {
      const res = await apiFetch("/api/admin/cacheex/stats/reset", { method: "POST" });
      if (!res.ok) throw new Error(res.statusText);
      push(t("cacheex.toastResetOk"));
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setResetting(false);
    }
  }

  if (st.loading) return <Loading label={t("cacheex.loading")} />;
  if (st.error) {
    const s = (st.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>{t("cacheex.title404")}</h1>
          <p className="muted">{t("cacheex.endpointUnavailable")}</p>
          <button type="button" className="btn btn-sm" onClick={st.reload}>
            {t("common.retry")}
          </button>
        </div>
      );
    return <LoadError error={st.error} onRetry={st.reload} />;
  }
  const d = st.data;
  const pairs = d?.pairs ?? [];

  return (
    <>
    <div className="card">
      <div className="toolbar">
        <h1>{t("cacheex.title")}</h1>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          {t("cacheex.refresh")}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={resetting} onClick={reset}>
          {resetting ? "…" : t("cacheex.resetCounters")}
        </button>
      </div>
      <p className="muted small">
        {t("cacheex.summary", { generated: formatDate(d?.generatedAtMs) })}
      </p>
      {!pairs.length ? (
        <Empty msg={t("cacheex.noPairs")} />
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>{t("cacheex.thPeer")}</th>
                <th>{t("cacheex.thIpNodeId")}</th>
                <th>{t("cacheex.thDirection")}</th>
                <th>{t("cacheex.thProto")}</th>
                <th>{t("cacheex.thMode")}</th>
                <th>{t("cacheex.thSens")}</th>
                <th>{t("cacheex.thState")}</th>
                <th>{t("cacheex.thSids")}</th>
                <th>{t("cacheex.thPushed")}</th>
                <th>{t("cacheex.thReceived")}</th>
                <th>{t("cacheex.thHits")}</th>
                <th>{t("cacheex.thDropped")}</th>
                <th>{t("cacheex.thFlood")}</th>
                <th>{t("cacheex.thCauses")}</th>
                <th>{t("cacheex.thLastPush")}</th>
                <th>{t("cacheex.thLastReceive")}</th>
              </tr>
            </thead>
            <tbody>
              {pairs.map((p) => {
                const pairKey = `${p.direction}/${p.peerId}`;
                const sidCount = p.sids?.length ?? 0;
                const hasSids = sidCount > 0;
                const isExpanded = hasSids && !(collapsed[pairKey] ?? false);
                return (
                  <Fragment key={pairKey}>
                    <tr>
                      <td className="mono">{p.peerId}</td>
                      <td className="mono small">
                        <div className="peer-meta">
                          <span>{p.remoteIp || "—"}</span>
                          <span className="muted">{p.remoteNodeId || "—"}</span>
                        </div>
                      </td>
                      <td>{p.direction === "outbound" ? t("cacheex.dirOutbound") : t("cacheex.dirInbound")}</td>
                      <td className="mono">{p.protocol ?? "—"}</td>
                      <td className="mono">{p.mode ?? "—"}</td>
                      <td className="small">{directionHint(p.mode, p.direction)}</td>
                      <td>
                        <span className={p.online ? "badge badge-ok" : "badge badge-muted"}>
                          {p.state ?? (p.online ? t("cacheex.connected") : "—")}
                        </span>
                      </td>
                      <td>
                        {hasSids ? (
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm"
                            aria-expanded={isExpanded}
                            aria-label={t(isExpanded ? "cacheex.ariaCollapseSids" : "cacheex.ariaExpandSids", {
                              count: sidCount,
                              peer: p.peerId,
                            })}
                            onClick={() =>
                              setCollapsed((prev) => ({ ...prev, [pairKey]: isExpanded }))
                            }
                          >
                            <span aria-hidden="true">{isExpanded ? "▼ " : "► "}</span>
                            <span className="mono">{sidCount}</span>
                          </button>
                        ) : (
                          <span className="mono muted">0</span>
                        )}
                      </td>
                      <td className="mono">{p.pushedCw ?? "—"}</td>
                      <td className="mono">{p.receivedCw ?? "—"}</td>
                      <td className="mono">{p.hits ?? 0}</td>
                      <td className="mono">{p.droppedCw ?? "—"}</td>
                      <td className="mono small" title={t("cacheex.titleFloodDrop")}>
                        {floodOf(p.dropReasons)}
                      </td>
                      <td className="mono small">{formatReasons(p.dropReasons)}</td>
                      <td className="small">{formatDate(p.lastPushAtMs)}</td>
                      <td className="small">{formatDate(p.lastReceiveAtMs)}</td>
                    </tr>
                    {isExpanded &&
                      p.sids?.map((s) => {
                        const serviceKey =
                          s.serviceKey ||
                          formatServiceLabel({
                            serviceKey: s.serviceKey,
                            caid: s.caid,
                            provid: s.provid,
                            srvid: s.srvid,
                          });
                        return (
                          <tr
                            key={`${p.direction}/${p.peerId}/sid/${s.caid}:${s.provid}:${s.srvid}`}
                            className="cacheex-sid-row"
                          >
                            <td className="mono small" title={formatServiceLabel(s)}>
                              <span className="cacheex-arrow" aria-hidden="true">»»» </span>
                              <span>{serviceKey}</span>
                            </td>
                            <td className="mono small">
                              <div className="peer-meta">
                                <span>{p.remoteIp || "—"}</span>
                                <span className="muted">{p.remoteNodeId || "—"}</span>
                              </div>
                            </td>
                            <td colSpan={6} className="small">
                              {s.channelName || "—"}
                            </td>
                            <td className="mono">{s.pushedCw ?? 0}</td>
                            <td className="mono">{s.receivedCw ?? 0}</td>
                            <td className="mono">{s.hits ?? 0}</td>
                            <td className="mono">{s.droppedCw ?? 0}</td>
                            <td className="mono small">—</td>
                            <td className="mono small">—</td>
                            <td colSpan={2} className="small">
                              {formatDate(s.lastActivityAtMs)}
                            </td>
                          </tr>
                        );
                      })}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">
        {t("cacheex.queueFootnote")}
      </p>
    </div>
    <PeerQualitySection />
    <QuarantineSection />
    <QuorumSection />
    </>
  );
}

export function ratioLabel(bps: number | undefined): string {
  if (bps === undefined || bps === null) return "—";
  return `${(bps / 100).toFixed(2)} %`;
}

function PeerQualitySection() {
  const t = useT();
  const quality = useSnapshot(async () => {
    const { body } = await apiJson<PeerQualityResponse>("/api/admin/peer-quality/quality");
    return body;
  });
  const hits = useSnapshot(async () => {
    const { body } = await apiJson<CacheHitsResponse>("/api/admin/peer-quality/hits");
    return body;
  });
  const peers = quality.data?.peers ?? [];
  const origins = hits.data?.origins ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>{t("cacheex.qualityHeading")}</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => { quality.reload(); hits.reload(); }}>
          {t("cacheex.refresh")}
        </button>
      </div>
      <p className="muted small">
        {t("cacheex.qualityFootnote")}
      </p>
      {quality.loading ? (
        <Loading label={t("cacheex.loadingQuality")} />
      ) : quality.error ? (
        <LoadError error={quality.error} onRetry={quality.reload} />
      ) : !peers.length ? (
        <Empty msg={t("cacheex.noQualityData")} />
      ) : (
        <div className="table-wrap">
          <table aria-label={t("cacheex.ariaQualityTable")}>
            <thead>
              <tr>
                <th>{t("cacheex.thNode")}</th>
                <th>{t("cacheex.thPeer")}</th>
                <th>{t("cacheex.thAccepted")}</th>
                <th>{t("cacheex.thInvalid")}</th>
                <th>{t("cacheex.thEligibleTotal")}</th>
                <th>{t("cacheex.thInvalidRatio")}</th>
                <th>{t("cacheex.thQuarantined")}</th>
              </tr>
            </thead>
            <tbody>
              {peers.map((p) => (
                <tr key={`${p.nodeId ?? "central"}/${p.peerId}`}>
                  <td className="mono small">{p.nodeId ?? "central"}</td>
                  <td className="mono">{p.peerId}</td>
                  <td className="mono">{p.accepted ?? "—"}</td>
                  <td className="mono">{p.invalid ?? "—"}</td>
                  <td className="mono">{p.total ?? "—"}</td>
                  <td className="mono">{ratioLabel(p.invalidRatioBps)}</td>
                  <td>{p.quarantined ? <span className="badge badge-warn">{t("common.yes")}</span> : "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <h3>{t("cacheex.hitsHeading")}</h3>
      {hits.loading ? (
        <Loading label={t("cacheex.loadingHits")} />
      ) : hits.error ? (
        <LoadError error={hits.error} onRetry={hits.reload} />
      ) : !origins.length ? (
        <Empty msg={t("cacheex.noHitsData")} />
      ) : (
        <div className="table-wrap">
          <table aria-label={t("cacheex.ariaHitsTable")}>
            <thead>
              <tr>
                <th>{t("cacheex.thNode")}</th>
                <th>{t("cacheex.thOrigin")}</th>
                <th>{t("cacheex.thHits")}</th>
                <th>{t("cacheex.thLastHit")}</th>
              </tr>
            </thead>
            <tbody>
              {origins.map((o) => (
                <tr key={`${o.nodeId ?? "central"}/${o.origin}`}>
                  <td className="mono small">{o.nodeId ?? "central"}</td>
                  <td className="mono">{o.origin}</td>
                  <td className="mono">{o.hits ?? "—"}</td>
                  <td className="small">{formatDate(o.lastHitAtMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function QuarantineSection() {
  const t = useT();
  const { push } = useToast();
  const [saving, setSaving] = useState(false);
  const [releasing, setReleasing] = useState<string | null>(null);
  const [releaseId, setReleaseId] = useState("");
  const st = useSnapshot(async () => {
    const { body } = await apiJson<QuarantineResponse>("/api/admin/peer-quality/quarantine");
    return body;
  });
  const cfg = st.data?.config;
  const [form, setForm] = useState({ enabled: false, mode: "observe", minSamples: "50", invalidRatioBps: "5000", durationMs: "600000" });
  const [touched, setTouched] = useState(false);
  const shown = touched
    ? form
    : {
        enabled: cfg?.enabled ?? false,
        mode: cfg?.mode ?? "observe",
        minSamples: String(cfg?.minSamples ?? 50),
        invalidRatioBps: String(cfg?.invalidRatioBps ?? 5000),
        durationMs: String(cfg?.durationMs ?? 600000),
      };

  async function save() {
    setSaving(true);
    try {
      const { res } = await apiJson("/api/admin/peer-quality/quarantine", {
        method: "PUT",
        body: {
          enabled: shown.enabled,
          mode: shown.mode,
          minSamples: Number(shown.minSamples),
          invalidRatioBps: Number(shown.invalidRatioBps),
          windowMs: cfg?.windowMs ?? 300000,
          durationMs: Number(shown.durationMs),
          maxPeers: cfg?.maxPeers ?? 256,
          version: cfg?.version ?? 1,
        },
      });
      if (!res.ok) throw new Error(res.statusText);
      push(t("cacheex.toastQuarantineUpdated"));
      setTouched(false);
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setSaving(false);
    }
  }

  async function release(peerId: string, nodeId?: string) {
    const id = peerId.trim();
    if (!id) return;
    if (nodeId && nodeId !== "central") {
      push(t("cacheex.toastReleaseCentralOnly"), "err");
      return;
    }
    setReleasing(id);
    try {
      const { res, body } = await apiJson<{ released?: boolean; note?: string }>("/api/admin/peer-quality/quarantine/release", {
        method: "POST",
        body: { peerId: id, nodeId: "central" },
      });
      if (!res.ok) throw new Error(res.statusText);
      if (body?.released) {
        push(t("cacheex.toastQuarantineReleased", { id }));
      } else {
        push(body?.note ?? t("cacheex.toastPeerNotFoundInQuarantine", { id }), "err");
      }
      setReleaseId("");
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setReleasing(null);
    }
  }

  const entries = st.data?.entries ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>{t("cacheex.quarantineHeading")}</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          {t("cacheex.refresh")}
        </button>
      </div>
      <p className="muted small">
        {t("cacheex.quarantineFootnote")}
      </p>
      {st.loading ? (
        <Loading label={t("cacheex.loadingQuarantine")} />
      ) : st.error ? (
        <LoadError error={st.error} onRetry={st.reload} />
      ) : (
        <>
          <div className="form-grid">
            <Field label={t("cacheex.fieldEnabled")} htmlFor="q-enabled">
              <input
                id="q-enabled"
                type="checkbox"
                checked={shown.enabled}
                onChange={(e) => { setForm({ ...shown, enabled: e.target.checked }); setTouched(true); }}
              />
            </Field>
            <Field label={t("cacheex.fieldMode")} htmlFor="q-mode">
              <select
                id="q-mode"
                value={shown.mode}
                onChange={(e) => { setForm({ ...shown, mode: e.target.value }); setTouched(true); }}
              >
                <option value="observe">{t("cacheex.optObserveNoExclusion")}</option>
                <option value="enforce">{t("cacheex.optEnforceRoutingExclusion")}</option>
              </select>
            </Field>
            <Field label={t("cacheex.fieldMinSamples")} htmlFor="q-min">
              <input
                id="q-min"
                type="number"
                min={2}
                max={10000}
                value={shown.minSamples}
                onChange={(e) => { setForm({ ...shown, minSamples: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label={t("cacheex.fieldInvalidRatioBps")} htmlFor="q-ratio">
              <input
                id="q-ratio"
                type="number"
                min={0}
                max={10000}
                value={shown.invalidRatioBps}
                onChange={(e) => { setForm({ ...shown, invalidRatioBps: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label={t("cacheex.fieldDurationMs")} htmlFor="q-dur">
              <input
                id="q-dur"
                type="number"
                min={10000}
                max={86400000}
                step={1000}
                value={shown.durationMs}
                onChange={(e) => { setForm({ ...shown, durationMs: e.target.value }); setTouched(true); }}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-sm" disabled={saving} onClick={save}>
            {saving ? "…" : t("cacheex.saveVersion", { version: cfg?.version ?? "?" })}
          </button>
          <h3>{t("cacheex.activeEntriesHeading", { count: entries.length, observed: st.data?.observed ?? "—", enforced: st.data?.enforced ?? "—" })}</h3>
          {!entries.length ? (
            <Empty msg={t("cacheex.noActiveQuarantine")} />
          ) : (
            <div className="table-wrap">
              <table aria-label={t("cacheex.ariaQuarantinesTable")}>
                <thead>
                  <tr>
                    <th>{t("cacheex.thNode")}</th>
                    <th>{t("cacheex.thPeer")}</th>
                    <th>{t("cacheex.thReason")}</th>
                    <th>{t("cacheex.thExpires")}</th>
                    <th>{t("cacheex.fieldMode")}</th>
                    <th>{t("cacheex.thAction")}</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => {
                    const isWorker = e.nodeId && e.nodeId !== "central";
                    return (
                      <tr key={`${e.nodeId ?? "central"}/${e.peerId}`}>
                        <td className="mono small">{e.nodeId ?? "central"}</td>
                        <td className="mono">{e.peerId}</td>
                        <td className="mono small">{e.reason ?? "—"}</td>
                        <td className="small">{formatDate(e.expiresAtMs)}</td>
                        <td>{e.observedOnly ? t("cacheex.observedOnly") : t("cacheex.enforcedExclusion")}</td>
                        <td>
                          {isWorker ? (
                            <span
                              className="badge badge-muted small"
                              title={t("cacheex.workerBadgeTitle")}
                              aria-label={t("cacheex.workerBadgeAria")}
                            >
                              {t("cacheex.workerBadgeLabel")}
                            </span>
                          ) : (
                            <button
                              type="button"
                              className="btn btn-ghost btn-sm"
                              disabled={releasing === e.peerId}
                              onClick={() => release(e.peerId, e.nodeId)}
                            >
                              {releasing === e.peerId ? "…" : t("cacheex.releaseBtn")}
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          <div className="form-grid">
            <Field label={t("cacheex.fieldReleasePeerId")} htmlFor="q-release">
              <input
                id="q-release"
                type="text"
                value={releaseId}
                placeholder="connA"
                onChange={(e) => setReleaseId(e.target.value)}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-ghost btn-sm" disabled={!releaseId.trim() || releasing !== null} onClick={() => release(releaseId, "central")}>
            {t("cacheex.releaseCentralBtn")}
          </button>
        </>
      )}
    </div>
  );
}

function QuorumSection() {
  const t = useT();
  const { push } = useToast();
  const [saving, setSaving] = useState(false);
  const st = useSnapshot(async () => {
    const { body } = await apiJson<QuorumResponse>("/api/admin/peer-quality/quorum");
    return body;
  });
  const cfg = st.data?.config;
  const [form, setForm] = useState({ mode: "off", minOrigins: "2", profiles: "", caids: "" });
  const [touched, setTouched] = useState(false);
  const shown = touched
    ? form
    : {
        mode: cfg?.mode ?? "off",
        minOrigins: String(cfg?.minOrigins ?? 2),
        profiles: (cfg?.profiles ?? []).join(","),
        caids: (cfg?.caids ?? []).map((n) => formatHexId(n, "caid")).join(", "),
      };

  async function save() {
    const r = parseHexList(shown.caids, "caid", { min: 1 });
    if (!r.ok) {
      push(t("cacheex.toastInvalidCaid", { token: r.token }), "err");
      return;
    }
    setSaving(true);
    try {
      const { res } = await apiJson("/api/admin/peer-quality/quorum", {
        method: "PUT",
        body: {
          mode: shown.mode,
          minOrigins: Number(shown.minOrigins),
          windowMs: cfg?.windowMs ?? 30000,
          maxKeys: cfg?.maxKeys ?? 1024,
          profiles: shown.profiles.split(",").map((s) => s.trim()).filter(Boolean),
          caids: r.values,
          version: cfg?.version ?? 1,
        },
      });
      if (!res.ok) throw new Error(res.statusText);
      push(t("cacheex.toastQuorumUpdated"));
      setTouched(false);
      st.reload();
    } catch (e) {
      push(describeError(e), "err");
    } finally {
      setSaving(false);
    }
  }

  const stats = st.data?.stats;
  const keys = st.data?.keys ?? [];
  return (
    <div className="card">
      <div className="toolbar">
        <h2>{t("cacheex.quorumHeading")}</h2>
        <button type="button" className="btn btn-ghost btn-sm" onClick={st.reload}>
          {t("cacheex.refresh")}
        </button>
      </div>
      <p className="muted small">
        {t("cacheex.quorumFootnote")}
      </p>
      {st.loading ? (
        <Loading label={t("cacheex.loadingQuorum")} />
      ) : st.error ? (
        <LoadError error={st.error} onRetry={st.reload} />
      ) : (
        <>
          <div className="form-grid">
            <Field label={t("cacheex.fieldMode")} htmlFor="qq-mode">
              <select
                id="qq-mode"
                value={shown.mode}
                onChange={(e) => { setForm({ ...shown, mode: e.target.value }); setTouched(true); }}
              >
                <option value="off">{t("cacheex.optQuorumOff")}</option>
                <option value="observe">{t("cacheex.optQuorumObserve")}</option>
                <option value="enforce">{t("cacheex.optQuorumEnforce")}</option>
              </select>
            </Field>
            <Field label={t("cacheex.fieldMinOrigins")} htmlFor="qq-min">
              <input
                id="qq-min"
                type="number"
                min={2}
                max={8}
                value={shown.minOrigins}
                onChange={(e) => { setForm({ ...shown, minOrigins: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label={t("cacheex.fieldProfiles")} htmlFor="qq-prof">
              <input
                id="qq-prof"
                type="text"
                value={shown.profiles}
                onChange={(e) => { setForm({ ...shown, profiles: e.target.value }); setTouched(true); }}
              />
            </Field>
            <Field label={t("cacheex.fieldCaidsHex")} htmlFor="qq-caids">
              <input
                id="qq-caids"
                type="text"
                value={shown.caids}
                onChange={(e) => { setForm({ ...shown, caids: e.target.value }); setTouched(true); }}
                placeholder={t("cacheex.placeholderCaids")}
              />
            </Field>
          </div>
          <button type="button" className="btn btn-sm" disabled={saving} onClick={save}>
            {saving ? "…" : t("cacheex.saveVersion", { version: cfg?.version ?? "?" })}
          </button>
          <h3>{t("cacheex.countersHeading")}</h3>
          <p className="mono small">
            {t("cacheex.countersSummary", {
              keys: stats?.keys ?? "—",
              observed: stats?.observed ?? "—",
              quorum: stats?.quorum ?? "—",
              pending: stats?.pending ?? "—",
              mismatch: stats?.mismatch ?? "—",
              expired: stats?.expired ?? "—",
            })}
          </p>
          <h3>{t("cacheex.trackedKeysHeading", { count: keys.length })}</h3>
          {!keys.length ? (
            <Empty msg={t("cacheex.noTrackedKeys")} />
          ) : (
            <div className="table-wrap">
              <table aria-label={t("cacheex.ariaQuorumKeysTable")}>
                <thead>
                  <tr>
                    <th>{t("cacheex.thQuorumProfile")}</th>
                    <th>{t("cacheex.thQuorumCaid")}</th>
                    <th>{t("cacheex.thQuorumSid")}</th>
                    <th>{t("cacheex.thQuorumParity")}</th>
                    <th>{t("cacheex.thQuorumOrigins")}</th>
                    <th>{t("cacheex.thQuorumQuorum")}</th>
                  </tr>
                </thead>
                <tbody>
                  {keys.map((k, i) => (
                    <tr key={`${k.profile ?? ""}/${k.caid}/${k.sid}/${k.parity}/${k.dataHash}/${i}`}>
                      <td className="mono">{k.profile ?? "—"}</td>
                      <td className="mono">{k.caid != null ? formatHexId(k.caid, "caid") : "—"}</td>
                      <td className="mono">{k.sid != null ? formatHexId(k.sid, "sid") : "—"}</td>
                      <td className="mono">{k.parity ?? "—"}</td>
                      <td className="mono small">{(k.origins ?? []).join(" ") || "—"}</td>
                      <td>{k.quorumReached ? <span className="badge badge-ok">{t("cacheex.quorumReached")}</span> : t("cacheex.quorumPending")}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
