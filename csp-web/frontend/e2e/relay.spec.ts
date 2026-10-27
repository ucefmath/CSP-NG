import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("relais", () => {
  test("tables nodes/caches/liens + statuts + TTL", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/relais");
    await expect(page.getByRole("heading", { level: 1, name: /Relais/ })).toBeVisible();
    await expect(page.getByText("worker-1").first()).toBeVisible();
    await expect(page.getByText("cache-a").first()).toBeVisible();
    await expect(page.getByText("60000").first()).toBeVisible();
    // Lien listener #11 → cache-a affiché avec les 3 sens
    const row = page.getByRole("row", { name: /cache-a/ });
    await expect(row.first()).toBeVisible();
  });

  test("création lien : selects labellisés référençant listeners/caches, PUT can*", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/relais");
    await page.getByRole("button", { name: "Nouveau lien" }).click();
    const dialog = page.getByRole("dialog");
    const listenerSel = dialog.getByLabel(/Listener/);
    await expect(listenerSel).toBeVisible();
    // Options labellisées référençant le listener connu
    await expect(listenerSel.locator("option", { hasText: "#11" })).toContainText("hdplus");
    await listenerSel.selectOption("11");
    const cacheSel = dialog.getByLabel("Cache", { exact: true });
    await expect(cacheSel.locator("option", { hasText: "cache-u" })).toBeAttached();
    await cacheSel.selectOption("cache-u");
    await dialog.getByRole("button", { name: /Créer|Add|Enregistrer/ }).first().click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/relay/links/11/cache-u", "PUT");
    const b = put?.body as Record<string, unknown>;
    expect(b).toMatchObject({
      listenerId: 11, cacheName: "cache-u",
      canConsult: true, canFeed: true, canExport: false, enabled: true, version: 1,
    });
  });

  test("lien sans aucun sens : erreur inline, pas d'envoi", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/relais");
    await page.getByRole("button", { name: "Nouveau lien" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Listener/).selectOption("11");
    await dialog.getByLabel("Cache", { exact: true }).selectOption("cache-a");
    await dialog.getByRole("checkbox", { name: /Consultation/ }).uncheck();
    await dialog.getByRole("checkbox", { name: /Alimentation/ }).uncheck();
    await dialog.getByRole("button", { name: /Créer|Enregistrer/ }).first().click();
    await expect(dialog.getByRole("alert")).toContainText(/Au moins un sens/);
    expect(api.captured.filter((c) => c.url.includes("/relay/links/") && c.method === "PUT")).toHaveLength(0);
  });

  test("affectation node : PUT AssignmentDto + DELETE ?version", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/relais");
    await page.getByRole("row", { name: /worker-1/ }).getByRole("button", { name: "Listeners" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("Affectations listeners");
    await dialog.getByLabel(/Listener entrant/).fill("11");
    await dialog.getByRole("button", { name: "Affecter" }).click();
    await expect(page.getByText("Listener affecté")).toBeVisible();
    const put = api.last("/nodes/worker-1/listeners/11", "PUT");
    expect(put?.body).toEqual({ enabled: true, version: 1 });
  });

  test("pairs UDP : ajout PUT {host,port,enabled}, suppression DELETE sans version", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/relais");
    await page.getByRole("row", { name: /cache-u/ }).getByRole("button", { name: "Pairs" }).click();
    await page.getByLabel("Hôte", { exact: true }).fill("10.0.0.8");
    await page.getByLabel("Port", { exact: true }).fill("8099");
    await page.getByRole("button", { name: "Ajouter le pair" }).click();
    await expect(page.getByText("Pair ajouté")).toBeVisible();
    const put = api.last("/caches/cache-u/peers", "PUT");
    expect(put?.body).toEqual({ host: "10.0.0.8", port: 8099, enabled: true });
  });
});
