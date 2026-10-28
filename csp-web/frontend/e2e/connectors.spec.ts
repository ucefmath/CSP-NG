import { expect, test } from "@playwright/test";
import { json, login, mockApi } from "./fixtures";

test.describe("connecteurs", () => {
  test("édition : PUT préserve tous les champs + version, header CSRF", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("row", { name: /conn-a/ }).getByRole("button", { name: "Éditer" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Éditer connecteur");
    // Nom immuable
    await expect(dialog.getByLabel("Nom")).toBeDisabled();
    // Modifie uniquement la priorité
    await dialog.getByLabel(/priority/).fill("60");
    await dialog.getByRole("button", { name: "Enregistrer" }).click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/connectors/conn-a", "PUT");
    expect(put).toBeTruthy();
    expect(put?.headers["x-csp-requested-with"]).toBe("CSP-WebUI");
    const b = put?.body as Record<string, unknown>;
    // Préservation : champs non touchés identiques au snapshot
    expect(b).toMatchObject({
      profileName: "hdplus",
      protocol: "cccam",
      enabled: true,
      host: "serveur-a.net",
      port: 12000,
      username: "usera",
      passwordRef: "env:CONN_A_PW",
      cccamVersion: "2.3.0",
      cacheexMode: 2,
      cacheexFilter: "1830:000000",
      priority: 60,
      maxCapacity: 4,
      version: 3,
      proxyName: null,
      newcamdPasswordEncoding: "plaintext",
    });
  });

  test("création cs378x + cacheex mode 3", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("button", { name: "Créer", exact: true }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Nom").fill("conn-cs");
    await dialog.getByLabel("Hôte").fill("cs.example.net");
    await dialog.getByLabel(/Port/).fill("37800");
    // Régression : changer de protocole ne doit pas fermer le dialogue.
    await dialog.getByLabel("Protocole").selectOption("newcamd");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Protocole").selectOption("cs378x");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("cacheexMode").selectOption("3");
    await dialog.getByLabel(/cacheexMaxHop/).fill("5");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/connectors/conn-cs", "PUT");
    const b = put?.body as Record<string, unknown>;
    expect(b).toMatchObject({ protocol: "cs378x", cacheexMode: 3, cacheexMaxHop: 5, version: 1 });
  });

  test("import C-line : materialize(password) puis PUT avec secretRef", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [
        {
          match: (url, method) => url.includes("/api/admin/secrets/materialize") && method === "POST",
          handle: async (route) => {
            await route.fulfill(json(201, {
              secretRef: "file:/s/csp-connector-imp-ab12.pwd",
              purpose: "connector-password",
              owner: "imp",
            }));
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("button", { name: /Importer C-line/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("C-line").fill("C: imp.example.net 12000 cliuser clipass");
    await dialog.getByLabel("Nom du connecteur").fill("imp");
    await dialog.getByRole("button", { name: "Importer", exact: true }).click();
    await expect(dialog).toBeHidden();
    const mat = api.last("/api/admin/secrets/materialize", "POST");
    expect(mat?.body).toEqual({ purpose: "connector-password", owner: "imp", value: "clipass" });
    const put = api.last("/api/admin/config/connectors/imp", "PUT");
    const b = put?.body as Record<string, unknown>;
    expect(b).toMatchObject({
      protocol: "cccam", host: "imp.example.net", port: 12000,
      username: "cliuser", passwordRef: "file:/s/csp-connector-imp-ab12.pwd",
    });
    expect(JSON.stringify(b)).not.toContain("clipass");
  });

  test("bulk : résumé avec versions + patch, PUT atomique", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("checkbox", { name: "Sélectionner conn-a" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner conn-b" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("priority 0-100 — mode").selectOption("set");
    await dialog.getByLabel("priority 0-100 — valeur").fill("80");
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await expect(dialog.getByText("Résumé de confirmation")).toBeVisible();
    await expect(dialog.locator("li", { hasText: "conn-a" })).toContainText("v3");
    await expect(dialog.locator("li", { hasText: "conn-b" })).toContainText("v7");
    await expect(dialog.locator(".patch-preview")).toContainText('"priority": 80');
    await dialog.getByRole("button", { name: /Appliquer/ }).click();
    await expect(dialog).toBeHidden();
    const bulk = api.last("/api/admin/config/connectors/bulk", "PUT");
    expect(bulk?.body).toEqual({
      items: [{ id: "conn-a", version: 3 }, { id: "conn-b", version: 7 }],
      patch: { priority: 80 },
    });
    expect(bulk?.headers["x-csp-requested-with"]).toBe("CSP-WebUI");
  });

  test("bulk reset nullable envoie null, non-nullable sans option reset", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("checkbox", { name: "Sélectionner conn-a" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner conn-b" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    // Champ non-nullable : 2 modes seulement.
    const prioOpts = await dialog.getByLabel("priority 0-100 — mode").locator("option").allTextContents();
    expect(prioOpts).toHaveLength(2);
    // Champ nullable : 3 modes dont Réinitialiser (NULL).
    const kaOpts = await dialog.getByLabel(/keepAliveSec.*mode/).locator("option").allTextContents();
    expect(kaOpts).toHaveLength(3);
    expect(kaOpts[2]).toContain("Réinitialiser");
    await dialog.getByLabel("priority 0-100 — mode").selectOption("set");
    await dialog.getByLabel("priority 0-100 — valeur").fill("80");
    await dialog.getByLabel(/keepAliveSec.*mode/).selectOption("reset");
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await expect(dialog.locator(".patch-preview")).toContainText('"priority": 80');
    await expect(dialog.locator(".patch-preview")).toContainText('"keepAliveSec": null');
    await dialog.getByRole("button", { name: /Appliquer/ }).click();
    await expect(dialog).toBeHidden();
    const bulk = api.last("/api/admin/config/connectors/bulk", "PUT");
    const b = bulk?.body as { patch: Record<string, unknown> };
    expect(b.patch).toEqual({ priority: 80, keepAliveSec: null });
  });

  test("bulk tout inchangé : erreur inline, aucun envoi", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("checkbox", { name: "Sélectionner conn-a" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner conn-b" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await expect(dialog.getByRole("alert")).toContainText("inchangé");
    expect(api.last("/api/admin/config/connectors/bulk", "PUT")).toBeUndefined();
  });

  test("bulk 409 : message rollback, dialogue conservé", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [
        {
          match: (url, method) => url.includes("/connectors/bulk") && method === "PUT",
          handle: async (route) => {
            await route.fulfill(json(409, {
              code: "CONFLICT",
              message: "Version mismatch",
              details: "id 'conn-a': expected 3, current 4",
            }));
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("checkbox", { name: "Sélectionner conn-a" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner conn-b" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Activé").selectOption("true");
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await dialog.getByRole("button", { name: /Appliquer/ }).click();
    await expect(dialog.getByRole("alert")).toContainText(/409/);
    // Dialogue toujours ouvert : rien d'appliqué, l'utilisateur peut corriger/recharger
    await expect(dialog).toBeVisible();
    expect(api.captured.filter((c) => c.url.includes("/connectors/bulk"))).toHaveLength(1);
  });
});
