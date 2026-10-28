import { describe, expect, test } from "vitest";
import { formatReasons, formatServiceLabel, ratioLabel } from "../pages/CacheEx";
import { setLang } from "../i18n";

describe("CacheEx peer quality helpers", () => {
  test("formatReasons affiche quarantine et ignore les zéros", () => {
    expect(formatReasons({ quarantine: 2, invalid: 0, loop: 1 })).toBe("quarantaine:2 boucle:1");
    expect(formatReasons({})).toBe("—");
    expect(formatReasons(null)).toBe("inconnu");
  });

  test("formatReasons affiche en anglais quand lang='en'", () => {
    setLang("en");
    expect(formatReasons({ quarantine: 2, invalid: 0, loop: 1 })).toBe("quarantine:2 loop:1");
    expect(formatReasons({})).toBe("—");
    expect(formatReasons(null)).toBe("unknown");
    setLang("fr");
  });

  test("ratioLabel convertit les points de base", () => {
    expect(ratioLabel(3333)).toBe("33.33 %");
    expect(ratioLabel(0)).toBe("0.00 %");
    expect(ratioLabel(undefined)).toBe("—");
  });

  describe("formatServiceLabel", () => {
    test("avec serviceKey et channelName", () => {
      expect(
        formatServiceLabel({
          serviceKey: "1884@000000:32DC",
          channelName: "Canal + Premium",
        }),
      ).toBe("1884@000000:32DC — Canal + Premium");
    });

    test("sans channelName", () => {
      expect(
        formatServiceLabel({
          serviceKey: "1884@000000:32DC",
          channelName: null,
        }),
      ).toBe("1884@000000:32DC");
      expect(
        formatServiceLabel({
          serviceKey: "1884@000000:32DC",
        }),
      ).toBe("1884@000000:32DC");
    });

    test("fallback hex CAID@PROVID:SID", () => {
      // caid: 0x1884 = 6276, provid: 0, srvid: 0x32DC = 13020
      expect(
        formatServiceLabel({
          caid: 0x1884,
          provid: 0,
          srvid: 0x32dc,
        }),
      ).toBe("1884@000000:32DC");

      // fallback hex avec channelName
      expect(
        formatServiceLabel({
          caid: 0x0500,
          provid: 0x032830,
          srvid: 0x189d,
          channelName: "BeIN Sports 1",
        }),
      ).toBe("0500@032830:189D — BeIN Sports 1");

      // fallback hex avec valeurs manquantes / zéros
      expect(formatServiceLabel({})).toBe("0000@000000:0000");
    });
  });
});
