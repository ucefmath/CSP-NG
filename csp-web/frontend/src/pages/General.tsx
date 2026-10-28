import { useState } from "react";
import { apiJson, describeError, parseInteger } from "../api";
import { Empty, Field, LoadError, Loading, fetchSnapshot, useSnapshot, useToast } from "../components/ui";
import { useT } from "../i18n";

export function GeneralPage() {
  const t = useT();
  const snap = useSnapshot(fetchSnapshot);
  const { push } = useToast();
  const [proxyName, setProxyName] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState("");
  const [err, setErr] = useState("");

  if (snap.loading) return <Loading />;
  if (snap.error) return <LoadError error={snap.error} onRetry={snap.reload} />;
  if (!snap.data) return <Empty msg={t("common.noData")} />;
  const root = snap.data.root ?? {};
  const current = proxyName ?? (root.proxyName || "");

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr("");
    setMsg("");
    if (!current.trim()) {
      setErr(t("general.proxyNameRequired"));
      return;
    }
    setSaving(true);
    try {
      const { body } = await apiJson<{ version: number }>("/api/admin/config/root", {
        method: "PUT",
        body: { proxyName: current.trim(), version: root.version ?? 1 },
      });
      setMsg(t("general.saved", { version: body.version }));
      push(t("general.toastUpdated"));
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
      <h1>{t("general.title")}</h1>
      <p className="muted small">{t("general.subtitle")}</p>
      <form onSubmit={submit} noValidate>
        <section aria-label={t("general.sectionEssential")}>
          <h2>{t("general.sectionEssential")}</h2>
          <Field label={t("general.proxyName")} htmlFor="f-proxyName">
            <input id="f-proxyName" type="text" value={current} onChange={(e) => setProxyName(e.target.value)} required />
          </Field>
        </section>
        <section aria-label={t("general.sectionVersion")} className="section">
          <p className="muted small">
            {t("general.currentVersion")} <code className="mono">v{root.version ?? "—"}</code>{t("general.sentAutomatically")}
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
            {saving ? t("general.saving") : t("common.save")}
          </button>
        </div>
      </form>
      <p className="muted small">{t("general.bodyScope")}</p>
      {void parseInteger}
    </div>
  );
}
