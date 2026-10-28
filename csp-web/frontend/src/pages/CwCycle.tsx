import { useState } from "react";
import { apiJson, describeError, formatDate, parseInteger } from "../api";
import { formatHexId, parseHexList } from "../hex";
import type { CwCycleConfig, DcwStats } from "../types";
import { Empty, Field, LoadError, Loading, useSnapshot, useToast } from "../components/ui";
import { useT } from "../i18n";

export function CwCyclePage() {
  const t = useT();
  const { push } = useToast();
  const cfg = useSnapshot(async () => {
    const { body } = await apiJson<CwCycleConfig>("/api/admin/config/cw-cycle");
    return body;
  });
  const stats = useSnapshot(async () => {
    const { body } = await apiJson<DcwStats>("/api/admin/dcw/stats");
    return body;
  });
  const [form, setForm] = useState<Record<string, string | boolean> | null>(null);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  if (cfg.loading) return <Loading label={t("cwCycle.loading")} />;
  if (cfg.error) {
    const s = (cfg.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>{t("cwCycle.title")}</h1>
          <p className="muted">{t("cwCycle.endpointUnavailable")}</p>
        </div>
      );
    return <LoadError error={cfg.error} onRetry={cfg.reload} />;
  }
  if (cfg.data === null) return <Empty msg={t("common.noData")} />;
  const c: CwCycleConfig = cfg.data;
  const f: Record<string, string | boolean> = form ?? {
    enabled: Boolean(c.enabled),
    caids: (c.caids ?? []).map((n) => formatHexId(n, "caid")).join(", "),
    maxList: String(c.maxList ?? 500),
    keepTimeSec: String(c.keepTimeSec ?? 15),
    onBad: c.onBad ?? "drop",
    dropOld: c.dropOld ?? true,
    usePeerCycleMetadata: Boolean(c.usePeerCycleMetadata),
  };
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm({ ...f, [k]: e.target.type === "checkbox" ? (e.target as HTMLInputElement).checked : e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    const maxList = parseInteger(String(f.maxList), NaN);
    const keepTimeSec = parseInteger(String(f.keepTimeSec), NaN);
    const caidRaw = String(f.caids ?? "").trim();
    let caids: number[] = [];
    if (caidRaw) {
      const r = parseHexList(caidRaw, "caid", { min: 1, maxItems: 32 });
      if (!r.ok) {
        if (r.code === "tooMany") return setErr(t("cwCycle.errMaxCaids"));
        return setErr(t("cwCycle.errInvalidCaid", { token: r.token }));
      }
      if (new Set(r.values).size !== r.values.length) return setErr(t("cwCycle.errDuplicateCaid"));
      caids = [...new Set(r.values)].sort((a, b) => a - b);
    }
    if (!(maxList >= 16 && maxList <= 4000)) return setErr(t("cwCycle.errMaxListRange"));
    if (!(keepTimeSec >= 5 && keepTimeSec <= 240)) return setErr(t("cwCycle.errKeepTimeSecRange"));
    setSaving(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/cw-cycle", {
        method: "PUT",
        body: {
          enabled: Boolean(f.enabled),
          caids,
          maxList,
          keepTimeSec,
          onBad: f.onBad,
          dropOld: Boolean(f.dropOld),
          usePeerCycleMetadata: Boolean(f.usePeerCycleMetadata),
          version: c.version,
        },
      });
      setMsg(t("cwCycle.saved", { version: body.version }));
      push(t("cwCycle.toastUpdated"));
      setForm(null);
      cfg.reload();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setSaving(false);
    }
  }

  const cw = stats.data?.cwCycle;

  return (
    <div>
      <div className="card">
        <h1>{t("cwCycle.title")}</h1>
        <p className="muted small">
          {t("cwCycle.subtitle")}
        </p>
        <form onSubmit={submit} noValidate>
          <section aria-label={t("cwCycle.sectionEssential")}>
            <h2>{t("cwCycle.sectionEssential")}</h2>
            <div className="form-grid cols-2">
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.enabled)} onChange={set("enabled")} />
                <span>{t("cwCycle.enabled")}</span>
              </label>
              <Field label={t("cwCycle.caidsLabel")} htmlFor="f-caids">
                <input id="f-caids" type="text" value={String(f.caids)} onChange={set("caids")} placeholder={t("cwCycle.caidsPlaceholder")} />
              </Field>
              <div className="field">
                <label htmlFor="f-onBad">{t("cwCycle.onBadLabel")}</label>
                <select id="f-onBad" value={String(f.onBad)} onChange={set("onBad")}>
                  <option value="drop">{t("cwCycle.onBadDrop")}</option>
                  <option value="log">{t("cwCycle.onBadLog")}</option>
                </select>
              </div>
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.dropOld)} onChange={set("dropOld")} />
                <span>{t("cwCycle.dropOld")}</span>
              </label>
            </div>
          </section>
          <section aria-label={t("cwCycle.sectionAdvanced")} className="section section-advanced">
            <h3>{t("cwCycle.sectionAdvanced")}</h3>
            <div className="form-grid cols-2">
              <Field label={t("cwCycle.maxListLabel")} htmlFor="f-maxList">
                <input id="f-maxList" type="number" value={String(f.maxList)} onChange={set("maxList")} required />
              </Field>
              <Field label={t("cwCycle.keepTimeSecLabel")} htmlFor="f-keep">
                <input id="f-keep" type="number" value={String(f.keepTimeSec)} onChange={set("keepTimeSec")} required />
              </Field>
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.usePeerCycleMetadata)} onChange={set("usePeerCycleMetadata")} />
                <span>{t("cwCycle.usePeerCycleMetadata")}</span>
              </label>
            </div>
            <p className="muted small">
              {t("cwCycle.meta", {
                version: c.version,
                updatedAt: formatDate(c.updatedAtMs),
                updatedBy: c.updatedBy ?? "—",
              })}
            </p>
          </section>
          {err && (
            <p className="field-error" role="alert">
              {err}
            </p>
          )}
          {msg && (
            <p className="alert alert-ok" role="status">
              {msg}
            </p>
          )}
          <div className="toolbar">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? t("cwCycle.saving") : t("common.save")}
            </button>
          </div>
        </form>
      </div>
      <div className="card">
        <h2>{t("cwCycle.observabilityHeading")}</h2>
        {stats.loading ? (
          <p className="muted">{t("common.loading")}</p>
        ) : cw ? (
          <dl className="kpi-grid">
            <div><dt className="kpi-label">{t("cwCycle.kpiEnabled")}</dt><dd className="mono">{cw.enabled ? t("common.yes") : t("common.no")}</dd></div>
            <div><dt className="kpi-label">{t("cwCycle.kpiTrackedEntries")}</dt><dd className="mono">{cw.trackedEntries ?? "—"}</dd></div>
            <div><dt className="kpi-label">{t("cwCycle.kpiChecks")}</dt><dd className="mono">{cw.checks ?? "—"}</dd></div>
            <div><dt className="kpi-label">{t("cwCycle.kpiBadCycles")}</dt><dd className="mono">{cw.badCycles ?? "—"}</dd></div>
            <div><dt className="kpi-label">{t("cwCycle.kpiDropped")}</dt><dd className="mono">{cw.dropped ?? "—"}</dd></div>
            <div><dt className="kpi-label">{t("cwCycle.kpiLogged")}</dt><dd className="mono">{cw.logged ?? "—"}</dd></div>
          </dl>
        ) : (
          <Empty msg={t("cwCycle.emptyStats")} />
        )}
      </div>
    </div>
  );
}
