import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { json, login, mockApi } from "./fixtures";

test.describe("qualité pairs & quorum CacheEx", () => {
  test("rendu complet des 4 sections : stats paires, qualité, quarantaine, quorum", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/cacheex");
    await expect(page.getByRole("heading", { level: 1, name: "Stats CacheEx par pair" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Qualité des pairs (fenêtre temporelle)" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Quarantaine temporaire (opt-in)" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Quorum CacheEx ciblé" })).toBeVisible();

    // Vérification table qualité avec lignes locale + worker
    const qualityTable = page.getByRole("table", { name: "Qualité des pairs" });
    await expect(qualityTable).toBeVisible();
    await expect(qualityTable.getByText("conn-a")).toBeVisible();
    await expect(qualityTable.getByText("worker-1")).toBeVisible();
    await expect(qualityTable.getByText("conn-w")).toBeVisible();

    // Vérification table hits
    const hitsTable = page.getByRole("table", { name: "Hits servis par origine" });
    await expect(hitsTable).toBeVisible();
    await expect(hitsTable.getByText("10.0.0.1:12000")).toBeVisible();
    await expect(hitsTable.getByText("10.0.0.2:12000")).toBeVisible();
  });

  test("quarantaine : formulaire modification PUT corps complet + levée centrale et ligne worker désactivée", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/cacheex");

    // Formulaire quarantaine
    const qSection = page.locator(".card").filter({ hasText: "Quarantaine temporaire" });
    await qSection.getByLabel("Activée").check();
    await qSection.getByLabel("Mode").selectOption("enforce");
    await qSection.getByLabel("Échantillons min").fill("25");

    await qSection.getByRole("button", { name: /Enregistrer \(version 1\)/ }).click();
    await expect(page.getByText("Quarantaine mise à jour (relayée aux workers)")).toBeVisible();

    const lastPut = api.last("/api/admin/peer-quality/quarantine", "PUT");
    expect(lastPut).toBeDefined();
    expect(lastPut?.body).toMatchObject({
      enabled: true,
      mode: "enforce",
      minSamples: 25,
      version: 1,
    });

    // Table des quarantaines : ligne centrale a bouton 'Lever', ligne worker a bouton désactivé 'Auto-expire'
    const quarTable = qSection.getByRole("table", { name: "Quarantaines actives" });
    await expect(quarTable.getByText("conn-bad")).toBeVisible();
    await expect(quarTable.getByText("conn-w-bad")).toBeVisible();

    // Ligne worker : badge auto-expire explicite, pas d'action manuelle
    const workerRow = quarTable.locator("tr").filter({ hasText: "conn-w-bad" });
    await expect(workerRow.getByText("Auto-expire (worker)")).toBeVisible();
    await expect(workerRow.getByRole("button", { name: "Lever" })).toHaveCount(0);

    // Ligne centrale : levée active
    const centralRow = quarTable.locator("tr").filter({ hasText: "conn-bad" });
    await centralRow.getByRole("button", { name: "Lever" }).click();
    await expect(page.getByText("Quarantaine centrale levée : conn-bad")).toBeVisible();

    const lastPost = api.last("/api/admin/peer-quality/quarantine/release", "POST");
    expect(lastPost).toBeDefined();
    expect(lastPost?.body).toEqual({ peerId: "conn-bad", nodeId: "central" });
  });

  test("quarantaine : tentative de levée d'un pair absent affiche avertissement, pas de faux succès", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/cacheex");

    const qSection = page.locator(".card").filter({ hasText: "Quarantaine temporaire" });
    await qSection.getByLabel("Lever un pair (id exact)").fill("conn-absent");
    await qSection.getByRole("button", { name: "Lever (centrale)" }).click();

    // Doit afficher l'avertissement retourné par l'API (pas de succès trompeur)
    await expect(page.getByText(/Pair non trouvé dans la quarantaine centrale/)).toBeVisible();
  });

  test("quarantaine & quorum : gestion des conflits optimistes 409", async ({ page }) => {
    await mockApi(page, {
      overrides: [
        {
          match: (url, method) => url.includes("/api/admin/peer-quality/quarantine") && method === "PUT",
          handle: async (route) => {
            await route.fulfill(json(409, { code: "CONFLICT", message: "Conflit optimiste de version sur la quarantaine" }));
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/cacheex");

    const qSection = page.locator(".card").filter({ hasText: "Quarantaine temporaire" });
    await qSection.getByLabel("Échantillons min").fill("99");
    await qSection.getByRole("button", { name: /Enregistrer \(version 1\)/ }).click();

    // Toast d'erreur 409 attendu
    await expect(page.getByText(/Conflit de version/)).toBeVisible();
  });

  test("quorum : formulaire modification PUT avec profils et CAIDs", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/cacheex");

    const quorumSection = page.locator(".card").filter({ hasText: "Quorum CacheEx ciblé" });
    await quorumSection.getByLabel("Mode").selectOption("enforce");
    await quorumSection.getByLabel("Origines min").fill("3");
    await quorumSection.getByLabel("Profils (CSV, vide = tous)").fill("hdplus,skyde");
    await quorumSection.getByLabel("CAIDs (CSV, vide = tous)").fill("1830, 2434");

    await quorumSection.getByRole("button", { name: /Enregistrer \(version 1\)/ }).click();
    await expect(page.getByText("Quorum mis à jour (relayé aux workers)")).toBeVisible();

    const lastPut = api.last("/api/admin/peer-quality/quorum", "PUT");
    expect(lastPut).toBeDefined();
    expect(lastPut?.body).toMatchObject({
      mode: "enforce",
      minOrigins: 3,
      profiles: ["hdplus", "skyde"],
      caids: [1830, 2434],
      version: 1,
    });

    // Vérification de la table des clés quorum
    const keysTable = page.getByRole("table", { name: "Clés quorum suivies" });
    await expect(keysTable).toBeVisible();
    await expect(keysTable.getByText("node:0102030405060708")).toBeVisible();
    await expect(keysTable.getByText("atteint")).toBeVisible();
  });

  test("accessibilité axe-core : page CacheEx sans violation critique", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/cacheex");
    await expect(page.getByRole("heading", { level: 1, name: "Stats CacheEx par pair" })).toBeVisible();

    const scan = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();
    const blocking = scan.violations.filter((v) => v.impact === "critical");
    expect(blocking).toEqual([]);
  });
});
