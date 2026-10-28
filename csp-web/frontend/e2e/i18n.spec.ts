import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("internationalisation (i18n)", () => {
  test("langue par défaut EN puis bascule FR persistée au reload", async ({ page }) => {
    await mockApi(page, { lang: null });
    await page.goto("/admin/login");
    await expect(page.locator("html")).toHaveAttribute("lang", "en");

    await expect(page.getByLabel("Username")).toBeVisible();
    await expect(page.getByLabel("Password")).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();

    await login(page);

    await expect(page.getByRole("heading", { level: 1, name: "Overview" })).toBeVisible();

    const mainNav = page.getByRole("navigation", { name: "Main navigation" });
    await expect(mainNav).toBeVisible();
    await expect(mainNav.getByRole("link", { name: "Overview" })).toBeVisible();
    await expect(mainNav.getByRole("link", { name: "Connectors" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();

    const enBtn = page.getByRole("button", { name: "English" });
    await expect(enBtn).toHaveAttribute("aria-pressed", "true");

    const frBtn = page.getByRole("button", { name: "French" });
    await expect(frBtn).toHaveAttribute("aria-pressed", "false");

    await frBtn.click();

    await expect(page.locator("html")).toHaveAttribute("lang", "fr");
    await expect(page.getByRole("heading", { level: 1, name: "Vue d'ensemble" })).toBeVisible();

    const frNav = page.getByRole("navigation", { name: "Navigation principale" });
    await expect(frNav).toBeVisible();
    await expect(frNav.getByRole("link", { name: "Vue d'ensemble" })).toBeVisible();
    await expect(frNav.getByRole("link", { name: "Connecteurs" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Déconnexion" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Français" })).toHaveAttribute("aria-pressed", "true");

    await page.reload();

    await expect(page.locator("html")).toHaveAttribute("lang", "fr");
    await expect(page.getByRole("heading", { level: 1, name: "Vue d'ensemble" })).toBeVisible();
    await expect(frNav.getByRole("link", { name: "Vue d'ensemble" })).toBeVisible();
    await expect(frNav.getByRole("link", { name: "Connecteurs" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Déconnexion" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Français" })).toHaveAttribute("aria-pressed", "true");
  });

  test("pages moyennes traduites en EN et en FR", async ({ page }) => {
    const pagesToCheck: Array<{ path: string; enTitle: string; frTitle: string; isLink?: boolean }> = [
      { path: "logs", enTitle: "Event Logs", frTitle: "Journaux d'événements (Logs)", isLink: true },
      { path: "/admin/cache-etat", enTitle: "Cache State", frTitle: "État des caches" },
      { path: "/admin/catalog", enTitle: "Card Catalog", frTitle: "Catalogue de cartes" },
      { path: "/admin/cw-cycle", enTitle: "CW Cycle", frTitle: "Cycle CW" },
      { path: "/admin/proxys", enTitle: "SOCKS5 Proxies", frTitle: "Proxys SOCKS5" },
      { path: "/admin/profiles", enTitle: "Profiles", frTitle: "Profils" },
      { path: "/admin/listeners", enTitle: "Listeners", frTitle: "Listeners" },
      { path: "/admin/cacheex", enTitle: "CacheEx Stats by Peer", frTitle: "Stats CacheEx par pair" },
    ];

    // Mode EN (défaut applicatif)
    await mockApi(page, { lang: null });
    await login(page);

    for (const p of pagesToCheck) {
      if (p.isLink) {
        await page.getByRole("navigation").getByRole("link", { name: "Logs" }).click();
      } else {
        await page.goto(p.path);
      }
      await expect(page.getByRole("heading", { level: 1, name: p.enTitle }).first()).toBeVisible();
    }

    // Bascule en FR
    await page.getByRole("button", { name: "French" }).click();
    await expect(page.locator("html")).toHaveAttribute("lang", "fr");

    for (const p of pagesToCheck) {
      if (p.isLink) {
        await page.getByRole("navigation").getByRole("link", { name: "Logs" }).click();
      } else {
        await page.goto(p.path);
      }
      await expect(page.getByRole("heading", { level: 1, name: p.frTitle }).first()).toBeVisible();
    }
  });

  test("toutes les routes principales affichent un titre anglais (garde anti-français)", async ({ page }) => {
    // `navLink` : route non servie en deep-link par le fallback SPA (404 HTML),
    // on y accède donc par le lien de navigation comme le fait l'utilisateur.
    const routesEn: Array<{ path: string; title: string; navLink?: string }> = [
      { path: "/admin/overview", title: "Overview" },
      { path: "/admin/logs", title: "Event Logs", navLink: "Logs" },
      { path: "/admin/cache", title: "Cache" },
      { path: "/admin/cache-etat", title: "Cache State" },
      { path: "/admin/cacheex", title: "CacheEx Stats by Peer" },
      { path: "/admin/dcw", title: "DCW Quality" },
      { path: "/admin/general", title: "General" },
      { path: "/admin/profiles", title: "Profiles" },
      { path: "/admin/listeners", title: "Listeners" },
      { path: "/admin/connectors", title: "Connectors" },
      { path: "/admin/proxys", title: "SOCKS5 Proxies" },
      { path: "/admin/catalog", title: "Card Catalog" },
      { path: "/admin/cluster", title: "UDP Cluster" },
      { path: "/admin/relais", title: "Relays — nodes, caches, links, UDP peers" },
      { path: "/admin/cw-cycle", title: "CW Cycle" },
      { path: "/admin/users", title: "Users" },
    ];

    // Mode EN strict : aucune langue pré-persistée, `en` est le défaut applicatif.
    await mockApi(page, { lang: null });
    await login(page);

    for (const route of routesEn) {
      if (route.navLink) {
        await page
          .getByRole("navigation", { name: "Main navigation" })
          .getByRole("link", { name: route.navLink, exact: true })
          .click();
      } else {
        await page.goto(route.path);
      }
      await page.waitForURL(`**${route.path}`);
      await expect(page.locator("html")).toHaveAttribute("lang", "en");
      const h1 = page.getByRole("heading", { level: 1, name: route.title, exact: true }).first();
      await expect(h1).toBeVisible();
      // Garde anti-français : aucun diacritique français dans le titre affiché.
      await expect(h1).not.toContainText(/[éèêëàâäçùûüôöîïÉÈÊËÀÂÄÇÙÛÜÔÖÎÏ]/);
    }
  });
});
