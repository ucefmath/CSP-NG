import { describe, expect, test } from "vitest";
import {
  cleanHex,
  describeError,
  formatSid,
  freshnessLabel,
  isValidDesKeyHex,
  parseCLine,
  parseInteger,
  parseSidList,
} from "../api";

describe("parseInteger", () => {
  test("entiers valides", () => {
    expect(parseInteger("42", 0)).toBe(42);
    expect(parseInteger("", 7)).toBe(7);
    expect(parseInteger(null, 3)).toBe(3);
  });
  test("non-entiers => NaN", () => {
    expect(parseInteger("12.5", 0)).toBeNaN();
    expect(parseInteger("abc", 0)).toBeNaN();
  });
});

describe("parseSidList", () => {
  test("vide => []", () => {
    expect(parseSidList("")).toEqual({ ok: true, values: [] });
  });
  test("hex direct (avec ou sans 0x), dedup + tri", () => {
    const r = parseSidList("0x1234, 100, 0x1234");
    expect(r).toEqual({ ok: true, values: [0x100, 0x1234] });
  });
  test("invalide => erreur", () => {
    const r = parseSidList("xyz");
    expect(r.ok).toBe(false);
  });
  test("hors plage => erreur", () => {
    expect(parseSidList("70000").ok).toBe(false);
  });
});

describe("formatSid", () => {
  test("pad 4 hex", () => {
    expect(formatSid(0x1a)).toBe("001A");
  });
});

describe("secrets hex", () => {
  test("cleanHex tolere espaces et :", () => {
    expect(cleanHex("ab cd:ef")).toBe("abcdef");
  });
  test("des key 28 hex", () => {
    expect(isValidDesKeyHex("01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E")).toBe(true);
    expect(isValidDesKeyHex("0102")).toBe(false);
    expect(isValidDesKeyHex(null)).toBe(false);
  });
});

describe("freshnessLabel — jamais de faux zéros", () => {
  test("jamais reçu => inconnu", () => {
    expect(freshnessLabel(null, false)).toContain("inconnu");
    expect(freshnessLabel(undefined, undefined)).toContain("inconnu");
  });
  test("périmé explicite", () => {
    expect(freshnessLabel(120000, false)).toContain("périmé");
  });
  test("frais explicite", () => {
    expect(freshnessLabel(5000, true)).toContain("frais");
  });
});

describe("parseCLine", () => {
  test("ligne valide", () => {
    const r = parseCLine("C: exemple.net 12000 user pass");
    expect(r).toEqual({ ok: true, value: { host: "exemple.net", port: 12000, username: "user", password: "pass" } });
  });
  test("format invalide", () => {
    expect(parseCLine("N: x").ok).toBe(false);
    expect(parseCLine("").ok).toBe(false);
  });
  test("port invalide", () => {
    expect(parseCLine("C: h 99999 u p").ok).toBe(false);
  });
});

describe("describeError", () => {
  test("409 => conflit version", () => {
    expect(describeError({ status: 409, message: "x" })).toContain("409");
  });
  test("426 => TLS", () => {
    expect(describeError({ status: 426, message: "x" })).toContain("TLS");
  });
  test("403 CSRF", () => {
    expect(describeError({ status: 403, code: "CSRF_INVALID", message: "x" })).toContain("CSRF");
  });
  test("correlationId ajoute", () => {
    expect(describeError({ status: 500, message: "boom", correlationId: "abc" })).toContain("[abc]");
  });
});
