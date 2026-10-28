export type HexKind = "caid" | "provid" | "sid";
export const HEX_WIDTH: Record<HexKind, number> = { caid: 4, provid: 6, sid: 4 };
export const HEX_MAX: Record<HexKind, number> = { caid: 0xffff, provid: 0xffffff, sid: 0xffff };

/** Trim ; préfixe 0x/0X optionnel ; 1..HEX_WIDTH chiffres hex ; vide ou invalide => NaN. Aucune lecture décimale. */
export function parseHexId(raw: string, kind: HexKind): number {
  if (typeof raw !== "string") return NaN;
  const trimmed = raw.trim();
  if (!trimmed) return NaN;
  const stripped = trimmed.replace(/^0[xX]/, "");
  if (!stripped) return NaN;
  const maxWidth = HEX_WIDTH[kind];
  if (stripped.length > maxWidth) return NaN;
  if (!/^[0-9a-fA-F]+$/.test(stripped)) return NaN;
  const val = parseInt(stripped, 16);
  if (Number.isNaN(val) || val < 0 || val > HEX_MAX[kind]) return NaN;
  return val;
}

/** Majuscules, complété à HEX_WIDTH. Si opts?.zeroAsWildcard et n === 0 => '0'. */
export function formatHexId(n: number, kind: HexKind, opts?: { zeroAsWildcard?: boolean }): string {
  if (opts?.zeroAsWildcard && n === 0) {
    return "0";
  }
  return n.toString(16).toUpperCase().padStart(HEX_WIDTH[kind], "0");
}

export type HexListResult =
  | { ok: true; values: number[] }
  | { ok: false; code: "invalid" | "range" | "tooMany"; token: string };

/** Séparateurs /[,s]+/ ; strict : le moindre jeton invalide => ok:false (jamais de filtrage silencieux). */
export function parseHexList(raw: string, kind: HexKind, opts?: { min?: number; maxItems?: number }): HexListResult {
  if (!raw || !raw.trim()) {
    return { ok: true, values: [] };
  }
  const tokens = raw.split(/[,\s]+/).filter(Boolean);
  if (opts?.maxItems !== undefined && tokens.length > opts.maxItems) {
    return { ok: false, code: "tooMany", token: String(tokens.length) };
  }
  const values: number[] = [];
  for (const t of tokens) {
    if (!/^(?:0[xX])?[0-9a-fA-F]+$/.test(t)) {
      return { ok: false, code: "invalid", token: t };
    }
    const v = parseHexId(t, kind);
    if (Number.isNaN(v) || (opts?.min !== undefined && v < opts.min)) {
      return { ok: false, code: "range", token: t };
    }
    values.push(v);
  }
  return { ok: true, values };
}
