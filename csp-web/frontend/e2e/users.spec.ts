import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("utilisateurs bulk", () => {
  test("remplacement ensemble profiles : chips, résumé tableau, PUT", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/users");
    await page.getByRole("checkbox", { name: "Sélectionner alice" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner root" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    // Options issues du snapshot, labellisées.
    await dialog.getByRole("checkbox", { name: /Remplacer l'ensemble/ }).first().check();
    await dialog.getByRole("checkbox", { name: "sky", exact: true }).check();
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await expect(dialog.locator(".patch-preview")).toContainText('"profiles"');
    await expect(dialog.locator(".patch-preview")).toContainText('"sky"');
    await dialog.getByRole("button", { name: /Appliquer/ }).click();
    await expect(dialog).toBeHidden();
    const bulk = api.last("/api/admin/config/users/bulk", "PUT");
    expect(bulk?.url).toContain("/api/admin/config/users/bulk");
    const b = bulk?.body as { items: Array<{ id: string; version: number }>; patch: Record<string, unknown> };
    expect(b.items).toEqual([
      { id: "alice", version: 2 },
      { id: "root", version: 1 },
    ]);
    expect(b.patch).toEqual({ profiles: ["sky"] });
  });

  test("reset scalaire nullable displayName envoie null", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/users");
    await page.getByRole("checkbox", { name: "Sélectionner alice" }).check();
    await page.getByRole("checkbox", { name: "Sélectionner root" }).check();
    await page.getByRole("button", { name: /Édition groupée/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("displayName (vide = inchangé) — mode").selectOption("reset");
    await dialog.getByRole("button", { name: /Relire/ }).click();
    await expect(dialog.locator(".patch-preview")).toContainText('"displayName": null');
    await dialog.getByRole("button", { name: /Appliquer/ }).click();
    await expect(dialog).toBeHidden();
    const bulk = api.last("/api/admin/config/users/bulk", "PUT");
    expect((bulk?.body as { patch: Record<string, unknown> }).patch).toEqual({ displayName: null });
  });
});

test.describe("utilisateurs statut temps réel", () => {
  const sessionsPayload = {
    instanceId: "i", timestamp: "t", schemaVersion: "1",
    payload: {
      items: [
        { id: "session-1", username: "Alice", remoteAddress: "10.1.2.0", protocol: "newcamd", profile: "sky", authenticated: true, alive: true },
        { id: "session-2", username: "alice", remoteAddress: "10.1.3.0", protocol: "cs378x", profile: null, authenticated: true, alive: true },
        { id: "session-3", username: "root", remoteAddress: "10.9.9.0", protocol: "cccam", profile: null, authenticated: false, alive: true },
      ],
      page: 0, size: 100, totalElements: 3, totalPages: 1,
    },
  };

  test("désactivé par défaut : aucun appel /sessions ; activé : colonne, filtre, persistance", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [{
        match: (url) => new URL(url).pathname === "/api/admin/sessions",
        handle: (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(sessionsPayload) }),
      }],
    });
    await login(page);
    await page.goto("/admin/users");
    await expect(page.getByRole("columnheader", { name: "Connexion" })).toHaveCount(0);
    expect(api.captured.filter((c) => c.url.includes("/api/admin/sessions"))).toHaveLength(0);

    await page.getByRole("checkbox", { name: "Statut de connexion en temps réel" }).check();
    await expect(page.getByRole("columnheader", { name: "Connexion" })).toBeVisible();
    const aliceRow = page.getByRole("row").filter({ hasText: "alice" });
    await expect(aliceRow.locator('[data-conn="online"]')).toContainText("connecté 2");
    await expect(aliceRow).toContainText("newcamd · sky");
    // root : session non authentifiée => hors ligne
    const rootRow = page.getByRole("row").filter({ hasText: "root" });
    await expect(rootRow.locator('[data-conn="offline"]')).toBeVisible();
    await expect(page.getByRole("status").filter({ hasText: "connectés" })).toContainText("1/");

    await page.getByLabel("Filtre connexion").selectOption("online");
    await expect(page.getByRole("row").filter({ hasText: "root" })).toHaveCount(0);
    await expect(page.getByRole("row").filter({ hasText: "alice" })).toHaveCount(1);
    await page.getByLabel("Filtre connexion").selectOption("offline");
    await expect(page.getByRole("row").filter({ hasText: "alice" })).toHaveCount(0);

    // Préférence persistée au rechargement
    await page.reload();
    await expect(page.getByRole("checkbox", { name: "Statut de connexion en temps réel" })).toBeChecked();
    await expect(page.getByRole("columnheader", { name: "Connexion" })).toBeVisible();
  });
});
