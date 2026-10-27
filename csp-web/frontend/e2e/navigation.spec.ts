import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

const ROUTES: Array<[string, string]> = [
  ["overview", "Vue d'ensemble"],
  ["general", "Général"],
  ["cache", "Cache"],
  ["cache-etat", "État des caches"],
  ["cacheex", "Stats CacheEx par pair"],
  ["cluster", "Cluster UDP"],
  ["profiles", "Profils"],
  ["listeners", "Listeners"],
  ["catalog", "Catalogue de cartes"],
  ["connectors", "Connecteurs"],
  ["proxys", "Proxys SOCKS5"],
  ["relais", "Relais"],
  ["dcw", "Qualité DCW"],
  ["cw-cycle", "Cycle CW"],
  ["users", "Utilisateurs"],
];

const NAV_LABELS: Record<string, string> = {
  overview: "Vue d'ensemble",
  general: "Général",
  cache: "Cache",
  "cache-etat": "État des caches",
  cacheex: "Stats CacheEx",
  cluster: "Cluster UDP",
  profiles: "Profils",
  listeners: "Listeners",
  catalog: "Catalogue",
  connectors: "Connecteurs",
  proxys: "Proxys",
  relais: "Relais",
  dcw: "Qualité DCW",
  "cw-cycle": "Cycle CW",
  users: "Utilisateurs",
};

test.describe("navigation", () => {
  test("chaque lien de nav mène à sa page (URL + titre)", async ({ page }) => {
    await mockApi(page);
    await login(page);
    for (const [route, heading] of ROUTES) {
      await page.getByRole("navigation", { name: "Navigation principale" })
        .getByRole("link", { name: NAV_LABELS[route], exact: true }).first().click();
      await expect(page).toHaveURL(new RegExp(`/admin/${route}`));
      await expect(page.getByRole("heading", { level: 1, name: heading }).first()).toBeVisible();
    }
  });

  test("deep link + rechargement : rendu direct sans passer par overview", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await expect(page.getByRole("heading", { level: 1, name: "Connecteurs" })).toBeVisible();
    await expect(page.getByText("serveur-a.net").first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole("heading", { level: 1, name: "Connecteurs" })).toBeVisible();
    await page.goto("/admin/cache-etat");
    await expect(page.getByRole("heading", { level: 1, name: "État des caches" })).toBeVisible();
    await expect(page.getByText(/inconnu/i).first()).toBeVisible();
  });

  test("route inconnue → page introuvable (404 serveur, pas de masquage)", async ({ page, request }) => {
    const res = await request.get("/admin/route-qui-nexiste-pas");
    expect(res.status()).toBe(404);
    await mockApi(page);
    await login(page);
    await page.goto("/admin/route-qui-nexiste-pas").catch(() => {});
    // Le serveur renvoie 404 brut : le routeur SPA n'est même pas servi.
    expect(res.status()).toBe(404);
  });
});
