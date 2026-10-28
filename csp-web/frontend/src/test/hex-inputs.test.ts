import { describe, expect, test } from "vitest";

/**
 * Garde-fou statique sur la saisie / l'affichage hexadécimal des pages admin.
 * Les pages ne doivent plus formater elles-mêmes les identifiants (`"0x" + ...`) :
 * tout passe par `src/hex.ts` (parseHexId / formatHexId / parseHexList).
 *
 * Les sources sont chargées en texte brut via `import.meta.glob` (Vite, typé par
 * `vite/client`) : aucun accès `node:fs`, donc aucun besoin de `@types/node`.
 */
const PAGE_MODULES = import.meta.glob<string>("../pages/*.tsx", {
  query: "?raw",
  import: "default",
  eager: true,
});

/** Identifiants des 12 champs hexadécimaux attendus dans l'UI. */
const HEX_INPUT_IDS = [
  "pf-caId",
  "dr-caid",
  "dr-prov",
  "dr-sid",
  "dm-caid",
  "dm-prov",
  "dm-tcaid",
  "dm-tprov",
  "dm-src",
  "dm-tgt",
  "f-caids",
  "qq-caids",
] as const;

interface PageSource {
  name: string;
  source: string;
}

function pageSources(): PageSource[] {
  return Object.entries(PAGE_MODULES)
    .map(([modulePath, source]) => ({ name: modulePath.replace(/^.*\//, ""), source }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** Balises `<input ...>` (mono- ou multilignes) avec leur attribut `id`. */
function inputTags(source: string): { tag: string; id: string | null }[] {
  const tags = source.match(/<input\b[^>]*>/g) ?? [];
  return tags.map((tag) => {
    const idMatch = tag.match(/\bid="([^"]+)"/);
    return { tag, id: idMatch ? idMatch[1] : null };
  });
}

describe("pages — formatage hexadécimal centralisé", () => {
  test("les pages sont bien lues depuis src/pages", () => {
    const pages = pageSources();
    expect(pages.length).toBeGreaterThan(0);
    expect(pages.map((p) => p.name)).toContain("Catalog.tsx");
  });

  test('aucun src/pages/*.tsx ne concatène "0x" (utiliser formatHexId)', () => {
    const offenders = pageSources()
      .filter((page) => /["']0x["']\s*\+/.test(page.source))
      .map((page) => page.name);
    expect(offenders).toEqual([]);
  });
});

describe("pages — champs hexadécimaux", () => {
  test("les 12 ids de saisie hex sont présents dans src/pages", () => {
    const inputs = pageSources().flatMap((page) => inputTags(page.source));
    const presentIds = new Set(inputs.map((input) => input.id).filter((id): id is string => id !== null));
    const missing = HEX_INPUT_IDS.filter((id) => !presentIds.has(id));
    expect(missing).toEqual([]);
  });

  test('aucun champ hex n\'est en type="number" (saisie hexadécimale en texte)', () => {
    const offenders: string[] = [];
    for (const page of pageSources()) {
      for (const input of inputTags(page.source)) {
        if (input.id === null || !(HEX_INPUT_IDS as readonly string[]).includes(input.id)) continue;
        const idPattern = new RegExp(`\\bid="${escapeRegExp(input.id)}"`, "g");
        expect(input.tag.match(idPattern)?.length).toBe(1);
        if (/\btype="number"/.test(input.tag)) {
          offenders.push(`${page.name}#${input.id}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
