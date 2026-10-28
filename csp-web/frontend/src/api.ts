// Client API : cookie de session meme origine, CSRF X-CSP-Requested-With sur
// mutations, timeout 15 s, erreurs normalisees. Aucun secret en stockage web.

import { formatHexId, parseHexList } from "./hex";
import { translate } from "./i18n";

export interface ApiError {
  status: number;
  code?: string;
  message: string;
  details?: string;
  correlationId?: string;
}

let logoutCb: ((reason?: string) => void) | null = null;
let sessionUser: string | null = null;

export function setSessionUser(u: string | null) {
  sessionUser = u;
}
export function getSessionUser() {
  return sessionUser;
}
export function hasSession() {
  return sessionUser !== null;
}
export function clearSession() {
  sessionUser = null;
}
export function onLogout(cb: ((reason?: string) => void) | null) {
  logoutCb = cb;
}

export function normalizeError(status: number, body: unknown, fallback: string): ApiError {
  const b = (body ?? {}) as Record<string, unknown>;
  return {
    status,
    code: typeof b.code === "string" ? b.code : undefined,
    message: typeof b.message === "string" ? b.message : fallback,
    details: typeof b.details === "string" ? b.details : undefined,
    correlationId: typeof b.correlationId === "string" ? b.correlationId : undefined,
  };
}

export function describeError(err: unknown): string {
  const e = err as Partial<ApiError>;
  let m = e.message || translate("api.defaultError");
  if (e.status === 409) m = translate("api.conflict409");
  else if (e.status === 426) m = translate("api.tls426");
  else if (e.status === 403) m = e.code === "CSRF_INVALID"
    ? translate("api.csrf403")
    : translate("api.forbidden403");
  else if (e.status === 404) {
    const defErr = translate("api.defaultError");
    m = `${translate("api.notFound404")}${e.message && e.message !== defErr && e.message !== "Erreur" ? " — " + e.message : ""}`;
  }
  if (e.correlationId) m += ` [${e.correlationId}]`;
  return m;
}

function timeoutSignal(ms: number) {
  const ac = new AbortController();
  const id = setTimeout(() => ac.abort(new DOMException("Timeout", "TimeoutError")), ms);
  return { signal: ac.signal, cancel: () => clearTimeout(id) };
}

export interface FetchOpts {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
}

export async function apiFetch(path: string, opts: FetchOpts = {}): Promise<Response> {
  const method = (opts.method || "GET").toUpperCase();
  const headers = new Headers({ ...(opts.headers || {}) });
  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    headers.set("X-CSP-Requested-With", "CSP-WebUI");
  }
  if (opts.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const { signal, cancel } = timeoutSignal(15000);
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      credentials: "same-origin",
      signal,
    });
  } catch (e) {
    cancel();
    if (e instanceof DOMException && e.name === "TimeoutError") {
      throw { status: 0, code: "TIMEOUT", message: translate("api.timeout") } satisfies ApiError;
    }
    throw { status: 0, code: "NETWORK", message: translate("api.network") } satisfies ApiError;
  }
  cancel();
  if (res.status === 401) {
    const cb = logoutCb;
    logoutCb = null;
    try {
      if (cb) cb("401");
    } finally {
      logoutCb = cb;
    }
    let body: unknown = null;
    try {
      body = await res.clone().json();
    } catch {
      body = null;
    }
    throw normalizeError(401, body, translate("api.unauthenticated"));
  }
  return res;
}

export async function apiJson<T>(path: string, opts: FetchOpts = {}): Promise<{ res: Response; body: T }> {
  const res = await apiFetch(path, opts);
  const ct = res.headers.get("Content-Type") || "";
  let body: unknown = null;
  if (ct.includes("application/json")) {
    try {
      body = await res.json();
    } catch {
      body = null;
    }
  } else {
    const txt = await res.text();
    if (txt) {
      try {
        body = JSON.parse(txt) as unknown;
      } catch {
        body = { message: txt };
      }
    }
  }
  if (!res.ok) throw normalizeError(res.status, body, res.statusText || translate("api.defaultError"));
  return { res, body: body as T };
}

// --- Helpers purs (testes) ---

export function parseInteger(value: unknown, def: number): number {
  if (value === null || value === undefined || value === "") return def;
  const n = Number(String(value).trim());
  if (Number.isNaN(n) || !Number.isInteger(n)) return NaN;
  return n;
}

export function cleanHex(s: string | null | undefined): string {
  if (!s) return "";
  return String(s).replace(/[\s:]/g, "");
}

export function isValidDesKeyHex(s: string | null | undefined): boolean {
  const c = cleanHex(s);
  return c.length === 28 && /^[0-9a-fA-F]{28}$/.test(c);
}

export function parseSidList(raw: string): { ok: true; values: number[] } | { ok: false; error: string } {
  const r = parseHexList(raw, "sid");
  if (!r.ok) {
    return {
      ok: false,
      error: r.code === "range"
        ? translate("api.sidOutOfRange", { token: r.token })
        : translate("api.sidInvalid", { token: r.token }),
    };
  }
  return { ok: true, values: [...new Set(r.values)].sort((a, b) => a - b) };
}

export function formatSid(n: number): string {
  return formatHexId(n, "sid");
}

export function formatDate(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return "—";
  const d = new Date(Number(ms));
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

/** Décrit la fraîcheur d'une stat : absent/périmé => "inconnu", jamais de faux zéro. */
export function freshnessLabel(ageMs: number | null | undefined, fresh: boolean | undefined): string {
  if (ageMs === null || ageMs === undefined) return translate("api.freshnessUnknown");
  if (fresh === false) return translate("api.freshnessStale", { s: Math.round(ageMs / 1000) });
  return translate("api.freshnessFresh", { s: Math.round(ageMs / 1000) });
}

/** Parse une C-line CCcam "C: host port user pass [deskey]" en payload connecteur partiel. */
export function parseCLine(line: string): {
  ok: true;
  value: { host: string; port: number; username: string; password: string };
} | { ok: false; error: string } {
  const t = line.trim();
  if (!t) return { ok: false, error: translate("api.clineEmpty") };
  const parts = t.split(/\s+/);
  if (parts.length < 5 || (parts[0] !== "C:" && parts[0] !== "C")) {
    return { ok: false, error: translate("api.clineFormat") };
  }
  const [, host, portRaw, username, password] = parts;
  const port = parseInteger(portRaw, NaN);
  if (!host) return { ok: false, error: translate("api.clineHost") };
  if (Number.isNaN(port) || port < 1 || port > 65535) return { ok: false, error: translate("api.clinePort") };
  if (!username) return { ok: false, error: translate("api.clineUser") };
  if (!password) return { ok: false, error: translate("api.clinePass") };
  return { ok: true, value: { host, port, username, password } };
}
