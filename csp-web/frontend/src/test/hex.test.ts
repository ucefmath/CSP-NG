import { describe, expect, test } from "vitest";
import { formatHexId, HEX_MAX, HEX_WIDTH, parseHexId, parseHexList } from "../hex";

describe("HEX_WIDTH / HEX_MAX", () => {
  test("largeurs et bornes par type d'identifiant", () => {
    expect(HEX_WIDTH).toEqual({ caid: 4, provid: 6, sid: 4 });
    expect(HEX_MAX).toEqual({ caid: 0xffff, provid: 0xffffff, sid: 0xffff });
  });
});

describe("parseHexId", () => {
  test("caid : 4 chiffres hex, avec ou sans préfixe 0x", () => {
    expect(parseHexId("1830", "caid")).toBe(0x1830);
    expect(parseHexId("0x1830", "caid")).toBe(0x1830);
    expect(parseHexId("0X1830", "caid")).toBe(0x1830);
    expect(parseHexId("0100", "caid")).toBe(0x0100);
    expect(parseHexId("0100", "caid")).toBe(256);
  });

  test("provid : 6 chiffres hex, insensible à la casse", () => {
    expect(parseHexId("6a", "provid")).toBe(0x6a);
    expect(parseHexId("00006A", "provid")).toBe(0x6a);
    expect(parseHexId("FFFFFF", "provid")).toBe(0xffffff);
  });

  test("sid : 4 chiffres hex", () => {
    expect(parseHexId("1f40", "sid")).toBe(0x1f40);
    expect(parseHexId("0000", "sid")).toBe(0);
  });

  test("trop de chiffres => NaN", () => {
    expect(parseHexId("10000", "caid")).toBeNaN();
    expect(parseHexId("1234567", "provid")).toBeNaN();
  });

  test("caractères non hexadécimaux => NaN", () => {
    expect(parseHexId("g1", "sid")).toBeNaN();
  });

  test("chaîne vide ou espaces => NaN", () => {
    expect(parseHexId("", "caid")).toBeNaN();
    expect(parseHexId("   ", "caid")).toBeNaN();
  });
});

describe("formatHexId", () => {
  test("caid : 4 chiffres hex majuscules, zéro-paddé", () => {
    expect(formatHexId(0x1830, "caid")).toBe("1830");
    expect(formatHexId(0x100, "caid")).toBe("0100");
  });

  test("provid : 6 chiffres hex majuscules, zéro-paddé", () => {
    expect(formatHexId(0x6a, "provid")).toBe("00006A");
  });

  test("sid : 4 chiffres hex majuscules", () => {
    expect(formatHexId(0x1f40, "sid")).toBe("1F40");
  });

  test("zéro sans wildcard => zéro-paddé", () => {
    expect(formatHexId(0, "sid")).toBe("0000");
  });

  test("zéroAsWildcard : 0 => '0', valeur non nulle inchangée", () => {
    expect(formatHexId(0, "sid", { zeroAsWildcard: true })).toBe("0");
    expect(formatHexId(0x10, "sid", { zeroAsWildcard: true })).toBe("0010");
  });
});

describe("parseHexList", () => {
  test("vide => liste vide valide", () => {
    expect(parseHexList("", "caid")).toEqual({ ok: true, values: [] });
  });

  test("hex direct, espaces tolérés, ordre préservé", () => {
    expect(parseHexList("0500, 0100", "caid")).toEqual({ ok: true, values: [0x0500, 0x0100] });
  });

  test("token invalide => code invalid et token fautif", () => {
    expect(parseHexList("0100, zz", "caid")).toEqual({ ok: false, code: "invalid", token: "zz" });
  });

  test("sous le minimum => code range et token fautif", () => {
    expect(parseHexList("0", "caid", { min: 1 })).toEqual({ ok: false, code: "range", token: "0" });
  });

  test("trop d'items => code tooMany", () => {
    const items = Array.from({ length: 33 }, (_, i) => i.toString(16).padStart(4, "0"));
    const r = parseHexList(items.join(", "), "caid", { maxItems: 32 });
    expect(r.ok).toBe(false);
    if (!r.ok) {
      expect(r.code).toBe("tooMany");
      expect(typeof r.token).toBe("string");
      expect(r.token.length).toBeGreaterThan(0);
    }
  });

  test("exactement maxItems => accepté", () => {
    const items = Array.from({ length: 32 }, (_, i) => i.toString(16).padStart(4, "0"));
    const r = parseHexList(items.join(","), "caid", { maxItems: 32 });
    expect(r.ok).toBe(true);
  });
});
