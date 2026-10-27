import { useState } from "react";
import { apiJson, describeError, parseInteger } from "../api";
import { Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

interface CacheForm {
  cwMaxAgeMs: string;
  maximumSize: string;
  maxCacheWaitMs: string;
  maxCacheWaitPercent: string;
}

export function CachePage() {
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [form, setForm] = useState<CacheForm | null>(null);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");
  const [msg, setMsg] = useState("");

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  if (!snap.data) return <Empty msg="Aucune donnée" />;
  const cache = (snap.data.cache ?? {}) as Record<string, number>;
  const f: CacheForm = form ?? {
    cwMaxAgeMs: String(cache.cwMaxAgeMs ?? 30000),
    maximumSize: String(cache.maximumSize ?? 100000),
    maxCacheWaitMs: String(cache.maxCacheWaitMs ?? 500),
    maxCacheWaitPercent: String(cache.maxCacheWaitPercent ?? -1),
  };
  const set = (k: keyof CacheForm) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm({ ...f, [k]: e.target.value });

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    const cwMaxAgeMs = parseInteger(f.cwMaxAgeMs, NaN);
    const maximumSize = parseInteger(f.maximumSize, NaN);
    const maxCacheWaitMs = parseInteger(f.maxCacheWaitMs, NaN);
    const maxCacheWaitPercent = parseInteger(f.maxCacheWaitPercent, NaN);
    if (!(cwMaxAgeMs >= 0)) return setErr("cwMaxAgeMs ≥ 0 requis");
    if (!(maximumSize >= 0)) return setErr("maximumSize ≥ 0 requis");
    if (Number.isNaN(maxCacheWaitMs) || Number.isNaN(maxCacheWaitPercent)) return setErr("maxCacheWait : nombres requis");
    if (maxCacheWaitMs === -1 && maxCacheWaitPercent === -1) return setErr("Un des deux maxCacheWait doit être ≠ -1 (exclusif)");
    if (maxCacheWaitPercent !== -1 && (maxCacheWaitPercent < 1 || maxCacheWaitPercent > 100))
      return setErr("maxCacheWaitPercent : 1-100 ou -1");
    setSaving(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/cache", {
        method: "PUT",
        body: { cwMaxAgeMs, maximumSize, maxCacheWaitMs, maxCacheWaitPercent, version: cache.version ?? 1 },
      });
      setMsg(`Enregistré — version ${body.version}`);
      push("Cache mis à jour");
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
      <h1>Cache</h1>
      <p className="muted small">Validation : cwMaxAgeMs/maximumSize ≥ 0, maxCacheWait absolu/pourcentage exclusif.</p>
      <form onSubmit={submit} noValidate>
        <section aria-label="Essentiel">
          <h2>Essentiel</h2>
          <div className="form-grid cols-2">
            <Field label="cwMaxAgeMs (ms)" htmlFor="f-cwMaxAgeMs">
              <input id="f-cwMaxAgeMs" type="number" min={0} value={f.cwMaxAgeMs} onChange={set("cwMaxAgeMs")} required />
            </Field>
            <Field label="maximumSize" htmlFor="f-maximumSize">
              <input id="f-maximumSize" type="number" min={0} value={f.maximumSize} onChange={set("maximumSize")} required />
            </Field>
          </div>
        </section>
        <section aria-label="Avancé" className="section section-advanced">
          <h3>Avancé — attente cache</h3>
          <div className="form-grid cols-2">
            <Field label="maxCacheWaitMs (-1 = pourcentage)" htmlFor="f-maxCacheWaitMs">
              <input id="f-maxCacheWaitMs" type="number" value={f.maxCacheWaitMs} onChange={set("maxCacheWaitMs")} required />
            </Field>
            <Field label="maxCacheWaitPercent (-1 = absolu, 1-100)" htmlFor="f-maxCacheWaitPercent">
              <input id="f-maxCacheWaitPercent" type="number" value={f.maxCacheWaitPercent} onChange={set("maxCacheWaitPercent")} required />
            </Field>
          </div>
          <p className="muted small">Version actuelle : v{cache.version ?? "—"}</p>
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
