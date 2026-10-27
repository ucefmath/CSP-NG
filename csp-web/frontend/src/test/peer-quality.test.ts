import { describe, expect, test } from "vitest";
import { formatReasons, ratioLabel } from "../pages/CacheEx";

describe("CacheEx peer quality helpers", () => {
  test("formatReasons affiche quarantine et ignore les zéros", () => {
    expect(formatReasons({ quarantine: 2, invalid: 0, loop: 1 })).toBe("quarantaine:2 boucle:1");
    expect(formatReasons({})).toBe("—");
    expect(formatReasons(null)).toBe("inconnu");
  });

  test("ratioLabel convertit les points de base", () => {
    expect(ratioLabel(3333)).toBe("33.33 %");
    expect(ratioLabel(0)).toBe("0.00 %");
    expect(ratioLabel(undefined)).toBe("—");
  });
});
