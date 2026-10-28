import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { apiJson, hasSession, setSessionUser } from "../api";
import { Field, useSessionRestore, useToast } from "../components/ui";
import { useT } from "../i18n";

export function LoginPage() {
  const t = useT();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const { push } = useToast();
  const restored = useSessionRestore();

  useEffect(() => {
    if (!restored.restoring && hasSession()) navigate("/overview", { replace: true });
  }, [navigate, restored.restoring]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError("");
    if (!username.trim()) {
      setError(t("login.usernameRequired"));
      return;
    }
    if (!password) {
      setError(t("login.passwordRequired"));
      return;
    }
    setBusy(true);
    try {
      await apiJson("/api/admin/session", { method: "POST", body: { username: username.trim(), password } });
      const { body } = await apiJson<{ name?: string }>("/api/admin/me");
      setSessionUser(body.name || username.trim());
      setPassword("");
      push(t("login.connectedAs", { user: body.name || username.trim() }));
      navigate("/overview", { replace: true });
    } catch (err: unknown) {
      const s = (err as { status?: number }).status;
      let msg = (err as { message?: string }).message || t("login.loginFailed");
      if (s === 401) msg = t("login.invalidCredentials");
      else if (s === 426) msg = t("login.tlsRequired");
      else if (s === 429) msg = t("login.rateLimited");
      else if (s === 0) msg = t("login.networkUnavailable");
      const cid = (err as { correlationId?: string }).correlationId;
      if (cid) msg += ` [${cid}]`;
      setError(msg);
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-view">
      <a className="skip-link" href="#login-form">
        {t("login.skipToForm")}
      </a>
      <div className="login-card" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <h1 id="login-title" className="login-title">
          CSP-NG
        </h1>
        <p className="login-subtitle">{t("login.subtitle")}</p>
        <form id="login-form" onSubmit={submit} noValidate>
          <Field label={t("login.username")} htmlFor="login-user">
            <input
              id="login-user"
              name="username"
              type="text"
              autoComplete="username"
              required
              spellCheck={false}
              autoCapitalize="off"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </Field>
          <Field label={t("login.password")} htmlFor="login-pass">
            <input
              id="login-pass"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </Field>
          {error && (
            <p className="field-error" role="alert">
              {error}
            </p>
          )}
          <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
            {busy ? t("login.submitting") : t("login.submit")}
          </button>
        </form>
        <div className="login-promo">
          <a
            href="https://m3ubridge.com"
            target="_blank"
            rel="noopener noreferrer"
            className="login-promo-link"
          >
            <strong>M3U Bridge ↗</strong> — {t("login.promoDesc")}
          </a>
        </div>
      </div>
    </div>
  );
}
