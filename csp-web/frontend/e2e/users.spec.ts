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
