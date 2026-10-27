import { useState } from "react";
import { apiJson, describeError, formatDate, parseInteger } from "../api";
import type { CwCycleConfig, DcwStats } from "../types";
import { Empty, Field, LoadError, Loading, useSnapshot, useToast } from "../components/ui";

export function CwCyclePage() {
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

  if (cfg.loading) return <Loading label="Chargement du cycle CW…" />;
  if (cfg.error) {
    const s = (cfg.error as { status?: number }).status;
    if (s === 404)
      return (
        <div className="card">
          <h1>Cycle CW</h1>
          <p className="muted">Endpoint indisponible sur ce backend (404) — fonctionnalité livrée par l'agent backend.</p>
        </div>
      );
    return <LoadError error={cfg.error} onRetry={cfg.reload} />;
  }
  if (cfg.data === null) return <Empty msg="Aucune donnée" />;
  const c: CwCycleConfig = cfg.data;
  const f: Record<string, string | boolean> = form ?? {
    enabled: Boolean(c.enabled),
    caids: (c.caids ?? []).join(", "),
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
      const parts = caidRaw.split(/[,\s]+/).filter(Boolean);
      if (parts.length > 32) return setErr("32 CAID maximum");
      for (const p of parts) {
        const v = /^0x/i.test(p) ? parseInt(p, 16) : parseInteger(p, NaN);
        if (Number.isNaN(v) || v < 1 || v > 65535) return setErr(`CAID invalide : ${p} (1-65535)`);
        caids.push(v);
      }
      if (new Set(caids).size !== caids.length) return setErr("CAID en double interdit");
      caids = [...new Set(caids)].sort((a, b) => a - b);
    }
    if (!(maxList >= 16 && maxList <= 4000)) return setErr("maxList : 16-4000");
    if (!(keepTimeSec >= 5 && keepTimeSec <= 240)) return setErr("keepTimeSec : 5-240");
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
      setMsg(`Enregistré — version ${body.version}`);
      push("Cycle CW mis à jour");
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
        <h1>Cycle CW</h1>
        <p className="muted small">
          Contrôle de cycle CW (miroir comportemental OSCam). CAID vides = tous. Métadonnées de cycle transportées par
          les push CacheEx CCcam/CS378x quand « exploiter les métadonnées pair » est actif.
        </p>
        <form onSubmit={submit} noValidate>
          <section aria-label="Essentiel">
            <h2>Essentiel</h2>
            <div className="form-grid cols-2">
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.enabled)} onChange={set("enabled")} />
                <span>Contrôle activé</span>
              </label>
              <Field label="CAID (csv, vide = tous, ≤ 32)" htmlFor="f-caids">
                <input id="f-caids" type="text" value={String(f.caids)} onChange={set("caids")} placeholder="ex : 0100, 0500" />
              </Field>
              <div className="field">
                <label htmlFor="f-onBad">Sur mauvais cycle</label>
                <select id="f-onBad" value={String(f.onBad)} onChange={set("onBad")}>
                  <option value="drop">Rejeter (drop)</option>
                  <option value="log">Journaliser (log)</option>
                </select>
              </div>
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.dropOld)} onChange={set("dropOld")} />
                <span>Rejeter les anciens (dropOld)</span>
              </label>
            </div>
          </section>
          <section aria-label="Avancé" className="section section-advanced">
            <h3>Avancé</h3>
            <div className="form-grid cols-2">
              <Field label="maxList (16-4000)" htmlFor="f-maxList">
                <input id="f-maxList" type="number" value={String(f.maxList)} onChange={set("maxList")} required />
              </Field>
              <Field label="keepTimeSec (5-240)" htmlFor="f-keep">
                <input id="f-keep" type="number" value={String(f.keepTimeSec)} onChange={set("keepTimeSec")} required />
              </Field>
              <label className="check-row">
                <input type="checkbox" checked={Boolean(f.usePeerCycleMetadata)} onChange={set("usePeerCycleMetadata")} />
                <span>Exploiter les métadonnées de cycle des pairs (CCcam/CS378x)</span>
              </label>
            </div>
            <p className="muted small">
              Version : v{c.version} — màj : {formatDate(c.updatedAtMs)} par {c.updatedBy ?? "—"}
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
              {saving ? "Enregistrement…" : "Enregistrer"}
            </button>
          </div>
        </form>
      </div>
      <div className="card">
        <h2>Observabilité (GET /dcw/stats .cwCycle)</h2>
        {stats.loading ? (
          <p className="muted">Chargement…</p>
        ) : cw ? (
          <dl className="kpi-grid">
            <div><dt className="kpi-label">Activé</dt><dd className="mono">{cw.enabled ? "oui" : "non"}</dd></div>
            <div><dt className="kpi-label">Entrées suivies</dt><dd className="mono">{cw.trackedEntries ?? "—"}</dd></div>
            <div><dt className="kpi-label">Contrôles</dt><dd className="mono">{cw.checks ?? "—"}</dd></div>
            <div><dt className="kpi-label">Mauvais cycles</dt><dd className="mono">{cw.badCycles ?? "—"}</dd></div>
            <div><dt className="kpi-label">Rejetés</dt><dd className="mono">{cw.dropped ?? "—"}</dd></div>
            <div><dt className="kpi-label">Journalisés</dt><dd className="mono">{cw.logged ?? "—"}</dd></div>
          </dl>
        ) : (
          <Empty msg="Pas de clé cwCycle dans /dcw/stats — inconnu (moteur non déployé ou sans activité)." />
        )}
      </div>
    </div>
  );
}
