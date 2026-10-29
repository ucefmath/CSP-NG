import { describe, expect, it } from "vitest";
import { compileLogFilter, splitHighlight } from "../logFilter";

const L1 = "[INFO] [com.bowman.cspng.cacheex] CACHEEX PUSH proto=cs378x caid=0x1813 sid=0x3D55 parity=0x80";
const L2 = "[INFO] [com.bowman.cspng.cacheex] CACHEEX PUSH proto=cs378x caid=0x1813 sid=0x3AA6 parity=0x81";
const L3 = "[INFO] [com.bowman.cspng.ecm] CACHE HIT type=INSTANT sid=0x3D55";

function run(query: string, o: Partial<{ regex: boolean; caseSensitive: boolean; invert: boolean }> = {}) {
  const f = compileLogFilter({ query, regex: false, caseSensitive: false, invert: false, ...o });
  if (!f.ok) throw new Error(f.error);
  return [L1, L2, L3].filter(f.test);
}

describe("compileLogFilter", () => {
  it("filtre vide : tout passe, inactif", () => {
    const f = compileLogFilter({ query: "  ", regex: false, caseSensitive: false, invert: false });
    expect(f.ok && f.active).toBe(false);
    expect(run("")).toHaveLength(3);
  });

  it("texte : termes en ET, insensible à la casse", () => {
    expect(run("cacheex SID=0x3d55")).toEqual([L1]);
  });

  it("texte : exclusion avec -terme et phrase entre guillemets", () => {
    expect(run("sid=0x3D55 -CACHEEX")).toEqual([L3]);
    expect(run('"type=INSTANT sid"')).toEqual([L3]);
  });

  it("respect de la casse", () => {
    expect(run("cacheex push", { caseSensitive: true })).toEqual([]);
    expect(run("CACHEEX PUSH", { caseSensitive: true })).toEqual([L1, L2]);
  });

  it("regex et inversion", () => {
    expect(run("sid=0x(3D55|3AA6) parity", { regex: true })).toEqual([L1, L2]);
    expect(run("cacheex", { regex: true, invert: true })).toEqual([L3]);
  });

  it("regex invalide : erreur, jamais d'exception", () => {
    const f = compileLogFilter({ query: "sid=(", regex: true, caseSensitive: false, invert: false });
    expect(f.ok).toBe(false);
  });
});

describe("splitHighlight", () => {
  it("découpe sans perte et marque les correspondances", () => {
    const f = compileLogFilter({ query: "sid=0x3d55", regex: false, caseSensitive: false, invert: false });
    const hl = f.ok ? f.highlight : null;
    const segs = splitHighlight("a sid=0x3D55 b", hl);
    expect(segs.map((s) => s.text).join("")).toBe("a sid=0x3D55 b");
    expect(segs.filter((s) => s.hit).map((s) => s.text)).toEqual(["sid=0x3D55"]);
  });

  it("regex vide (correspondance de longueur nulle) : pas de boucle infinie", () => {
    const segs = splitHighlight("abc", /x*/g);
    expect(segs.map((s) => s.text).join("")).toBe("abc");
  });
});
