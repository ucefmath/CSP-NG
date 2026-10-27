// Client API : cookie de session meme origine, CSRF X-CSP-Requested-With sur
// mutations, timeout 15 s, erreurs normalisees. Aucun secret en stockage web.

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
  let m = e.message || "Erreur";
  if (e.status === 409) m = "Conflit de version — rechargez (409)";
  else if (e.status === 426) m = "TLS requis — utilisez HTTPS (426)";
  else if (e.status === 403) m = e.code === "CSRF_INVALID"
    ? "Session/CSRF invalide — reconnectez-vous (403)"
    : "Interdit — rôle admin requis (403)";
  else if (e.status === 404) m = `Ressource introuvable (404)${e.message && e.message !== "Erreur" ? " — " + e.message : ""}`;
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
      throw { status: 0, code: "TIMEOUT", message: "Délai dépassé (15 s)" } satisfies ApiError;
    }
    throw { status: 0, code: "NETWORK", message: "Réseau indisponible" } satisfies ApiError;
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
    throw normalizeError(401, body, "Non authentifié");
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
  if (!res.ok) throw normalizeError(res.status, body, res.statusText || "Erreur");
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
  if (!raw || !raw.trim()) return { ok: true, values: [] };
  const parts = raw.split(",").map((s) => s.trim()).filter(Boolean);
  const out: number[] = [];
  for (const p of parts) {
    let v: number;
    if (/^0x[0-9a-fA-F]+$/.test(p)) v = parseInt(p, 16);
    else if (/^\d+$/.test(p)) v = parseInt(p, 10);
    else return { ok: false, error: `SID invalide : ${p} (hex 0x… ou décimal)` };
    if (Number.isNaN(v) || v < 0 || v > 65535) return { ok: false, error: `SID hors plage 0-65535 : ${p}` };
    out.push(v);
  }
  return { ok: true, values: [...new Set(out)].sort((a, b) => a - b) };
}

export function formatSid(n: number): string {
  return "0x" + n.toString(16).padStart(4, "0");
}

export function formatDate(ms: number | null | undefined): string {
  if (!ms || ms <= 0) return "—";
  const d = new Date(Number(ms));
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

/** Décrit la fraîcheur d'une stat : absent/périmé => "inconnu", jamais de faux zéro. */
export function freshnessLabel(ageMs: number | null | undefined, fresh: boolean | undefined): string {
  if (ageMs === null || ageMs === undefined) return "inconnu (jamais reçu)";
  if (fresh === false) return `périmé (${Math.round(ageMs / 1000)} s)`;
  return `frais (${Math.round(ageMs / 1000)} s)`;
}

/** Parse une C-line CCcam "C: host port user pass [deskey]" en payload connecteur partiel. */
export function parseCLine(line: string): {
  ok: true;
  value: { host: string; port: number; username: string; password: string };
} | { ok: false; error: string } {
  const t = line.trim();
  if (!t) return { ok: false, error: "Ligne vide" };
  const parts = t.split(/\s+/);
  if (parts.length < 5 || (parts[0] !== "C:" && parts[0] !== "C")) {
    return { ok: false, error: "Format attendu : C: host port user pass" };
  }
  const [, host, portRaw, username, password] = parts;
  const port = parseInteger(portRaw, NaN);
  if (!host) return { ok: false, error: "Hôte manquant" };
  if (Number.isNaN(port) || port < 1 || port > 65535) return { ok: false, error: "Port 1-65535 requis" };
  if (!username) return { ok: false, error: "Identifiant manquant" };
  if (!password) return { ok: false, error: "Mot de passe manquant (saisi ensuite comme secret)" };
  return { ok: true, value: { host, port, username, password } };
}
