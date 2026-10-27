import { useState } from "react";
import { apiJson, describeError, parseInteger } from "../api";
import { Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

export function ClusterPage() {
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [form, setForm] = useState<Record<string, string | boolean> | null>(null);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  if (!snap.data) return <Empty msg="Aucune donnée" />;
  const c = (snap.data.clusterUdp ?? {}) as Record<string, string | number | boolean | null>;
  const f: Record<string, string | boolean> = form ?? {
    enabled: Boolean(c.enabled ?? true),
    autoAddPeers: Boolean(c.autoAddPeers ?? true),
    bindPort: String(c.bindPort ?? 8099),
    bindAddress: String(c.bindAddress ?? "0.0.0.0"),
    remoteHosts: String(c.remoteHosts ?? ""),
    multicastGroup: String(c.multicastGroup ?? ""),
    multicastPort: c.multicastPort != null ? String(c.multicastPort) : "",
    multicastTtl: c.multicastTtl != null ? String(c.multicastTtl) : "",
    trackerUrlRef: String(c.trackerUrlRef ?? ""),
    pingIntervalMs: String(c.pingIntervalMs ?? 4000),
    resendThrottleMs: String(c.resendThrottleMs ?? 50),
    maxPacketsPerSec: String(c.maxPacketsPerSec ?? 50),
    blacklist: String(c.blacklist ?? ""),
    syncPeriodMs: String(c.syncPeriodMs ?? 100),
  };
  const set = (k: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm({ ...f, [k]: e.target.value });
  const setBool = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...f, [k]: e.target.checked });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    const str = (k: string) => String(f[k] ?? "").trim();
    const opt = (k: string): number | null => (str(k) === "" ? null : parseInteger(str(k), NaN));
    const num = (k: string, d: number) => {
      const v = str(k) === "" ? d : parseInteger(str(k), NaN);
      return Number.isNaN(v) ? NaN : v;
    };
    const payload = {
      enabled: Boolean(f.enabled),
      bindPort: num("bindPort", 8099),
      bindAddress: str("bindAddress") || "0.0.0.0",
      remoteHosts: str("remoteHosts") || null,
      multicastGroup: str("multicastGroup") || null,
      multicastPort: opt("multicastPort"),
      multicastTtl: opt("multicastTtl"),
      autoAddPeers: Boolean(f.autoAddPeers),
      trackerUrlRef: str("trackerUrlRef") || null,
      pingIntervalMs: num("pingIntervalMs", 4000),
      resendThrottleMs: num("resendThrottleMs", 50),
      maxPacketsPerSec: num("maxPacketsPerSec", 50),
      blacklist: str("blacklist") || null,
      syncPeriodMs: num("syncPeriodMs", 100),
      version: Number(c.version ?? 1),
    };
    if (!(payload.version >= 1)) return setErr("version ≥ 1");
    setSaving(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/cluster-udp", {
        method: "PUT",
        body: payload,
      });
      setMsg(`Enregistré — version ${body.version}`);
      push("Cluster mis à jour");
      setForm(null);
      snap.reload();
    } catch (e) {
      const m = describeError(e);
      setErr(m);
      push(m, "err");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="card">
      <h1>Cluster UDP</h1>
      <p className="muted small">Secrets affichés comme références uniquement (jamais de valeur).</p>
      <form onSubmit={submit} noValidate>
        <section aria-label="Essentiel">
          <h2>Essentiel</h2>
          <div className="form-grid cols-2">
            <label className="check-row">
              <input type="checkbox" checked={Boolean(f.enabled)} onChange={setBool("enabled")} />
              <span>Activé</span>
            </label>
            <label className="check-row">
              <input type="checkbox" checked={Boolean(f.autoAddPeers)} onChange={setBool("autoAddPeers")} />
              <span>Ajout auto des pairs</span>
            </label>
            <Field label="bindPort" htmlFor="f-bindPort">
              <input id="f-bindPort" type="number" value={String(f.bindPort)} onChange={set("bindPort")} required />
            </Field>
            <Field label="bindAddress" htmlFor="f-bindAddress">
              <input id="f-bindAddress" type="text" value={String(f.bindAddress)} onChange={set("bindAddress")} required />
            </Field>
            <div className="field">
              <label htmlFor="f-remoteHosts">remoteHosts (csv)</label>
              <textarea id="f-remoteHosts" value={String(f.remoteHosts)} onChange={set("remoteHosts")} />
            </div>
            <div className="field">
              <label htmlFor="f-blacklist">blacklist (csv)</label>
              <textarea id="f-blacklist" value={String(f.blacklist)} onChange={set("blacklist")} />
            </div>
          </div>
        </section>
        <section aria-label="Avancé" className="section section-advanced">
          <h3>Avancé</h3>
          <div className="form-grid cols-2">
            <Field label="multicastGroup" htmlFor="f-mcGroup">
              <input id="f-mcGroup" type="text" value={String(f.multicastGroup)} onChange={set("multicastGroup")} />
            </Field>
            <Field label="multicastPort" htmlFor="f-mcPort">
              <input id="f-mcPort" type="number" value={String(f.multicastPort)} onChange={set("multicastPort")} />
            </Field>
            <Field label="multicastTtl" htmlFor="f-mcTtl">
              <input id="f-mcTtl" type="number" value={String(f.multicastTtl)} onChange={set("multicastTtl")} />
            </Field>
            <Field label="trackerUrlRef (référence secret)" htmlFor="f-tracker">
              <input id="f-tracker" type="text" value={String(f.trackerUrlRef)} onChange={set("trackerUrlRef")} autoComplete="off" spellCheck={false} />
            </Field>
            <Field label="pingIntervalMs" htmlFor="f-ping">
              <input id="f-ping" type="number" value={String(f.pingIntervalMs)} onChange={set("pingIntervalMs")} required />
            </Field>
            <Field label="resendThrottleMs" htmlFor="f-resend">
              <input id="f-resend" type="number" value={String(f.resendThrottleMs)} onChange={set("resendThrottleMs")} required />
            </Field>
            <Field label="maxPacketsPerSec" htmlFor="f-maxpps">
              <input id="f-maxpps" type="number" value={String(f.maxPacketsPerSec)} onChange={set("maxPacketsPerSec")} required />
            </Field>
            <Field label="syncPeriodMs" htmlFor="f-sync">
              <input id="f-sync" type="number" value={String(f.syncPeriodMs)} onChange={set("syncPeriodMs")} required />
            </Field>
          </div>
          <p className="muted small">Version : v{c.version ?? "—"}</p>
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
  );
}
