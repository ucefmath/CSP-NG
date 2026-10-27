import { useState } from "react";
import { apiJson, describeError, parseInteger } from "../api";
import { Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";

export function GeneralPage() {
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [proxyName, setProxyName] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  if (!snap.data) return <Empty msg="Aucune donnée" />;
  const root = snap.data.root ?? {};
  const current = proxyName ?? (root.proxyName || "");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    if (!current.trim()) {
      setErr("Nom du proxy requis");
      return;
    }
    setSaving(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/root", {
        method: "PUT",
        body: { proxyName: current.trim(), version: root.version ?? 1 },
      });
      setMsg(`Enregistré — nouvelle version ${body.version}`);
      push("Général mis à jour");
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
      <h1>Général</h1>
      <p className="muted small">Nom du proxy. Version optimiste : rechargée après chaque enregistrement.</p>
      <form onSubmit={submit} noValidate>
        <section aria-label="Essentiel">
          <h2>Essentiel</h2>
          <Field label="Nom du proxy" htmlFor="f-proxyName">
            <input id="f-proxyName" type="text" value={current} onChange={(e) => setProxyName(e.target.value)} required />
          </Field>
        </section>
        <section aria-label="Version" className="section">
          <p className="muted small">
            Version actuelle : <code className="mono">v{root.version ?? "—"}</code> (envoyée automatiquement au PUT)
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
      <p className="muted small">Portée body ≤ 64 K — charge utile minimale.</p>
      {void parseInteger}
    </div>
  );
}
