export interface LogFilterOptions {
  query: string;
  regex: boolean;
  caseSensitive: boolean;
  invert: boolean;
}

export type CompiledLogFilter =
  | { ok: true; active: boolean; test: (line: string) => boolean; highlight: RegExp | null }
  | { ok: false; error: string };

const MAX_QUERY_LENGTH = 500;

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/**
 * Compile un filtre de logs.
 * - Mode texte : termes séparés par des espaces combinés en ET ; `-terme` exclut ;
 *   `"phrase exacte"` garde les espaces.
 * - Mode regex : expression JavaScript appliquée à la ligne complète (niveau, logger, message).
 * - `invert` inverse le résultat (lignes NE correspondant PAS).
 */
export function compileLogFilter(opts: LogFilterOptions): CompiledLogFilter {
  const raw = opts.query ?? "";
  if (raw.length > MAX_QUERY_LENGTH) return { ok: false, error: `> ${MAX_QUERY_LENGTH}` };
  const q = raw.trim();
  if (!q) return { ok: true, active: false, test: () => true, highlight: null };
  const flags = opts.caseSensitive ? "" : "i";

  if (opts.regex) {
    let re: RegExp;
    let hl: RegExp;
    try {
      re = new RegExp(q, flags);
      hl = new RegExp(q, flags + "g");
    } catch (e) {
      return { ok: false, error: (e as Error).message };
    }
    const base = (line: string) => re.test(line);
    return {
      ok: true,
      active: true,
      test: opts.invert ? (l) => !base(l) : base,
      highlight: opts.invert ? null : hl,
    };
  }

  const include: string[] = [];
  const exclude: string[] = [];
  const tokenRe = /(-?)"([^"]*)"|(\S+)/g;
  let m: RegExpExecArray | null;
  while ((m = tokenRe.exec(q)) !== null) {
    if (m[2] !== undefined) {
      if (!m[2]) continue;
      (m[1] === "-" ? exclude : include).push(m[2]);
    } else {
      const tok = m[3];
      if (tok.length > 1 && tok.startsWith("-")) exclude.push(tok.slice(1));
      else include.push(tok);
    }
  }
  const norm = (s: string) => (opts.caseSensitive ? s : s.toLowerCase());
  const inc = include.map(norm);
  const exc = exclude.map(norm);
  const base = (line: string) => {
    const l = norm(line);
    return inc.every((t) => l.includes(t)) && !exc.some((t) => l.includes(t));
  };
  const highlight = !opts.invert && include.length
    ? new RegExp(include.map(escapeRegex).join("|"), flags + "g")
    : null;
  return { ok: true, active: true, test: opts.invert ? (l) => !base(l) : base, highlight };
}

/** Découpe un texte en segments surlignés / non surlignés (jamais de HTML injecté). */
export function splitHighlight(text: string, re: RegExp | null): Array<{ text: string; hit: boolean }> {
  if (!re || !text) return [{ text, hit: false }];
  const out: Array<{ text: string; hit: boolean }> = [];
  re.lastIndex = 0;
  let last = 0;
  let m: RegExpExecArray | null;
  let guard = 0;
  while ((m = re.exec(text)) !== null && guard++ < 1000) {
    if (m[0].length === 0) {
      re.lastIndex++;
      continue;
    }
    if (m.index > last) out.push({ text: text.slice(last, m.index), hit: false });
    out.push({ text: m[0], hit: true });
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push({ text: text.slice(last), hit: false });
  re.lastIndex = 0;
  return out.length ? out : [{ text, hit: false }];
}
