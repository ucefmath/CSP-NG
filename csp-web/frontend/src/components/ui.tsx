import "../app.css";
import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router";
import { apiFetch, apiJson, clearSession, describeError, getSessionUser, hasSession, onLogout, setSessionUser } from "../api";
import { translate, useI18n, useT, type MessageKey } from "../i18n";

// ---------- Toasts ----------

export interface Toast {
  id: number;
  msg: string;
  kind: "ok" | "err";
}

const ToastCtx = createContext<{ push: (msg: string, kind?: "ok" | "err") => void }>({ push: () => {} });
export const useToast = () => useContext(ToastCtx);

let toastSeq = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((msg: string, kind: "ok" | "err" = "ok") => {
    const id = toastSeq++;
    setToasts((t) => [...t.slice(-3), { id, msg, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 4500);
  }, []);
  return (
    <ToastCtx.Provider value={{ push }}>
      {children}
      <div className="toast-region" role="status" aria-live="polite" aria-relevant="additions">
        {toasts.map((t) => (
          <div key={t.id} className={t.kind === "err" ? "toast err" : "toast ok"}>
            {t.msg}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

// ---------- Session / shell ----------

const NAV_GROUPS: Array<{ titleKey: MessageKey; links: Array<{ to: string; labelKey: MessageKey }> }> = [
  {
    titleKey: "shell.groupMonitoring",
    links: [
      { to: "overview", labelKey: "shell.navOverview" },
      { to: "logs", labelKey: "shell.navLogs" },
      { to: "cache", labelKey: "shell.navCache" },
      { to: "cache-etat", labelKey: "shell.navCacheState" },
      { to: "cacheex", labelKey: "shell.navCacheEx" },
      { to: "dcw", labelKey: "shell.navDcw" },
    ],
  },
  {
    titleKey: "shell.groupConfig",
    links: [
      { to: "general", labelKey: "shell.navGeneral" },
      { to: "profiles", labelKey: "shell.navProfiles" },
      { to: "listeners", labelKey: "shell.navListeners" },
      { to: "connectors", labelKey: "shell.navConnectors" },
      { to: "proxys", labelKey: "shell.navProxies" },
      { to: "catalog", labelKey: "shell.navCatalog" },
      { to: "cluster", labelKey: "shell.navCluster" },
    ],
  },
  {
    titleKey: "shell.groupRelayAccess",
    links: [
      { to: "relais", labelKey: "shell.navRelays" },
      { to: "cw-cycle", labelKey: "shell.navCwCycle" },
      { to: "users", labelKey: "shell.navUsers" },
    ],
  },
];

export function Shell() {
  const [user, setUser] = useState<string | null>(getSessionUser());
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { push } = useToast();
  const { lang, setLang, t } = useI18n();

  useEffect(() => {
    onLogout(() => {
      clearSession();
      setUser(null);
      navigate("/login", { replace: true });
      push(translate("shell.sessionExpired"), "err");
    });
    return () => onLogout(null);
  }, [navigate, push]);

  useEffect(() => {
    setMenuOpen(false);
    setUser(getSessionUser());
  }, [location.pathname]);

  async function logout() {
    try {
      await apiFetch("/api/admin/session", { method: "DELETE" });
    } catch {
      // ignore
    }
    clearSession();
    setUser(null);
    navigate("/login", { replace: true });
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">
        {t("shell.skipToContent")}
      </a>
      <header className="topbar" role="banner">
        <button
          type="button"
          className="icon-btn menu-btn"
          aria-label={menuOpen ? t("shell.closeMenu") : t("shell.openMenu")}
          aria-expanded={menuOpen}
          aria-controls="sidebar"
          onClick={() => setMenuOpen((v) => !v)}
        >
          ☰
        </button>
        <div className="topbar-brand">
          <span className="brand-text">CSP-NG</span>
        </div>
        <div className="topbar-actions">
          <div role="group" aria-label={t("shell.language")} className="lang-switch">
            <button
              type="button"
              className={lang === "en" ? "btn btn-primary btn-sm" : "btn btn-ghost btn-sm"}
              aria-pressed={lang === "en"}
              aria-label={t("shell.langEn")}
              onClick={() => setLang("en")}
            >
              EN
            </button>
            <button
              type="button"
              className={lang === "fr" ? "btn btn-primary btn-sm" : "btn btn-ghost btn-sm"}
              aria-pressed={lang === "fr"}
              aria-label={t("shell.langFr")}
              onClick={() => setLang("fr")}
            >
              FR
            </button>
          </div>
          <span className="mono small" aria-label={t("shell.loggedInUser")}>
            {user ?? ""}
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={logout}>
            {t("shell.logout")}
          </button>
        </div>
      </header>
      <div className="layout">
        <nav id="sidebar" className={menuOpen ? "sidebar open" : "sidebar"} aria-label={t("shell.mainNav")}>
          {NAV_GROUPS.map((g) => {
            const title = t(g.titleKey);
            return (
              <section key={g.titleKey} aria-label={title}>
                <h2 className="nav-group">{title}</h2>
                <ul className="nav-list">
                  {g.links.map((l) => (
                    <li key={l.to}>
                      <NavLink to={l.to} className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}>
                        {t(l.labelKey)}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })}
          <div className="sidebar-promo">
            <a
              href="https://m3ubridge.com"
              target="_blank"
              rel="noopener noreferrer"
              className="sidebar-promo-link"
            >
              <span className="sidebar-promo-title">M3U Bridge ↗</span>
              <span className="sidebar-promo-desc">
                {t("shell.promoDesc")}
              </span>
            </a>
          </div>
        </nav>
        {menuOpen && (
          <div
            className="sidebar-backdrop"
            onClick={() => setMenuOpen(false)}
            aria-hidden="true"
          />
        )}
        <main id="main" className="main" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const restored = useSessionRestore();
  const t = useT();
  useEffect(() => {
    if (!restored.restoring && !hasSession()) navigate("/login", { replace: true });
  }, [navigate, restored.restoring]);
  if (restored.restoring) return <Loading label={t("shell.restoringSession")} />;
  if (!hasSession()) return null;
  return <>{children}</>;
}

// Restauration session au (re)chargement : le cookie HttpOnly survit au reload,
// l'état mémoire non. GET /me (sans CSRF, cookie même origine) rétablit la session.
export function useSessionRestore() {
  const [restoring, setRestoring] = useState(!hasSession());
  useEffect(() => {
    if (hasSession()) {
      setRestoring(false);
      return;
    }
    let cancelled = false;
    apiJson<{ name?: string }>("/api/admin/me")
      .then(({ body }) => {
        if (!cancelled && body.name) {
          setSessionUser(body.name);
        }
      })
      .catch(() => {
        // Pas de session : la garde RequireAuth redirige vers /login.
      })
      .finally(() => {
        if (!cancelled) setRestoring(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return { restoring };
}

// ---------- Etats ----------

export function Loading({ label }: { label?: string }) {
  const t = useT();
  return (
    <div className="card" role="status" aria-live="polite">
      <p className="muted">{label ?? t("common.loading")}</p>
    </div>
  );
}

export function Empty({ msg }: { msg: string }) {
  return <div className="empty">{msg}</div>;
}

export function LoadError({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const t = useT();
  return (
    <div className="alert alert-error" role="alert">
      <p>{describeError(error)}</p>
      {onRetry && (
        <button type="button" className="btn btn-sm" onClick={onRetry}>
          {t("common.retry")}
        </button>
      )}
    </div>
  );
}

// ---------- Formulaires ----------

export function Field({
  label,
  error,
  children,
  htmlFor,
}: {
  label: string;
  error?: string;
  children: ReactNode;
  htmlFor?: string;
}) {
  const id = htmlFor ?? `f-${label}`;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error && (
        <p className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export function useSnapshot<T>(loader: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    loader()
      .then((d) => {
        setData(d);
        setLoading(false);
      })
      .catch((e) => {
        setError(e);
        setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    load();
  }, [load]);
  return { data, error, loading, reload: load };
}

export async function fetchSnapshot() {
  const { body } = await apiJson<import("../types").ConfigSnapshot>("/api/admin/config");
  return body;
}

export async function fetchUsers() {
  const { body } = await apiJson<import("../types").UserEntry[]>("/api/admin/users").catch((e: unknown) => {
    if ((e as { status?: number }).status === 404) return { body: [] as import("../types").UserEntry[] };
    throw e;
  });
  return Array.isArray(body) ? body : [];
}

// ---------- Dialogue accessible ----------

export function Dialog({
  title,
  onClose,
  children,
  wide,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  // onClose passe par une ref : un re-render du parent (onClose inline) ne doit pas
  // relancer l'effet, sinon le <dialog> serait fermé puis rouvert à chaque rendu.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    const dlg = ref.current;
    if (!dlg) return;
    if (!dlg.open) dlg.showModal();
    const onCancel = (e: Event) => {
      e.preventDefault();
      onCloseRef.current();
    };
    const onClick = (e: MouseEvent) => {
      // Seul un vrai clic pointeur sur le backdrop ferme : on ignore les clics
      // remontés depuis les enfants et les clics synthétiques (détail 0).
      if (e.target !== dlg || e.detail === 0) return;
      const r = dlg.getBoundingClientRect();
      if (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom) {
        onCloseRef.current();
      }
    };
    dlg.addEventListener("cancel", onCancel);
    dlg.addEventListener("click", onClick);
    return () => {
      dlg.removeEventListener("cancel", onCancel);
      dlg.removeEventListener("click", onClick);
      if (dlg.open) dlg.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return (
    <dialog ref={ref} className={wide ? "drawer wide" : "drawer"} aria-label={title}>
      <h2 className="dialog-title">{title}</h2>
      {children}
    </dialog>
  );
}

export function ConfirmDialog({
  title,
  desc,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  title: string;
  desc: string;
  confirmLabel?: string;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const t = useT();
  const [summary] = useState(desc);
  return (
    <Dialog title={title} onClose={onClose}>
      <p className="muted">{summary}</p>
      <div className="dialog-actions">
        <button type="button" className="btn btn-ghost" onClick={onClose}>
          {t("common.cancel")}
        </button>
        <button
          type="button"
          className="btn btn-danger"
          onClick={() => {
            onConfirm();
            onClose();
          }}
          autoFocus
        >
          {confirmLabel ?? t("common.confirm")}
        </button>
      </div>
    </Dialog>
  );
}

export function BackLink() {
  const t = useT();
  return (
    <p>
      <Link to=".." className="btn btn-ghost btn-sm">
        {t("common.back")}
      </Link>
    </p>
  );
}
