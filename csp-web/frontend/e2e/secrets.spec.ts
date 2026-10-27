import { expect, test } from "@playwright/test";
import { json, login, mockApi } from "./fixtures";

test.describe("secrets", () => {
  test("inline write-only : materialize puis PUT avec ref (valeur jamais réutilisée)", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [
        {
          match: (url, method) => url.includes("/api/admin/secrets/materialize") && method === "POST",
          handle: async (route) => {
            await route.fulfill(json(201, {
              secretRef: "file:/s/csp-connector-conn-a-ff00.pwd",
              purpose: "connector-password",
              owner: "conn-a",
            }));
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("row", { name: /conn-a/ }).getByRole("button", { name: "Éditer" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Valeur directe" }).first().click();
    const secretInput = dialog.getByLabel(/valeur directe/i).first();
    await expect(secretInput).toHaveAttribute("type", "password");
    await secretInput.fill("valeur-ultra-secrete-123");
    // Révélation volontaire
    await dialog.getByRole("button", { name: "Révéler" }).first().click();
    await expect(dialog.getByLabel(/valeur directe/i).first()).toHaveAttribute("type", "text");
    await dialog.getByRole("button", { name: "Matérialiser" }).first().click();
    const mat = api.last("/api/admin/secrets/materialize", "POST");
    expect(mat?.body).toEqual({ purpose: "connector-password", owner: "conn-a", value: "valeur-ultra-secrete-123" });
    // Le champ repasse en référence file:
    await expect(dialog.locator('input[value="file:/s/csp-connector-conn-a-ff00.pwd"]')).toBeVisible();
    await dialog.getByRole("button", { name: "Enregistrer" }).click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/connectors/conn-a", "PUT");
    const raw = JSON.stringify(put?.body);
    expect(raw).toContain("file:/s/csp-connector-conn-a-ff00.pwd");
    expect(raw).not.toContain("valeur-ultra-secrete-123");
  });

  test("mode référence : aucune matérialisation, ref conservée telle quelle", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/connectors");
    await page.getByRole("row", { name: /conn-b/ }).getByRole("button", { name: "Éditer" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.locator('input[value="file:/s/conn-b.pwd"]')).toBeVisible();
    await dialog.getByRole("button", { name: "Enregistrer" }).click();
    await expect(dialog).toBeHidden();
    expect(api.last("/api/admin/secrets/materialize", "POST")).toBeUndefined();
    const put = api.last("/api/admin/config/connectors/conn-b", "PUT");
    expect((put?.body as Record<string, unknown>).passwordRef).toBe("file:/s/conn-b.pwd");
  });

  test("desKey invalide : erreur inline, aucun appel réseau", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/listeners");
    await page.getByRole("button", { name: /Nouveau listener/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Valeur directe" }).click();
    await dialog.getByLabel(/valeur directe/i).fill("trop-court");
    await dialog.getByRole("button", { name: "Matérialiser" }).click();
    await expect(dialog.getByRole("alert")).toContainText("28 caractères hex");
    expect(api.last("/api/admin/secrets/materialize", "POST")).toBeUndefined();
  });

  test("secret protocole user : PUT ?version + validUntil + enabled, revoke ?version", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [
        {
          match: (url) => /\/api\/admin\/users\/alice\/secrets\/cccam$/.test(new URL(url).pathname),
          handle: async (route) => {
            if (route.request().method() === "GET") {
              await route.fulfill(json(200, {
                userName: "alice", protocol: "cccam", secretRef: "env:ALICE_CCCAM",
                secretVersion: 3, enabled: true, validUntil: null, version: 6, updatedBy: "admin",
              }));
            } else {
              await route.fulfill(json(200, { version: 7 }));
            }
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/users");
    await page.getByRole("row", { name: /alice/ }).getByRole("button", { name: "Secrets" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.locator('input[value="env:ALICE_CCCAM"]')).toBeVisible();
    await dialog.getByLabel(/validUntil/).fill("2030-01-02T03:04");
    await dialog.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByText("Secret cccam enregistré")).toBeVisible();
    const put = api.last("/users/alice/secrets/cccam", "PUT");
    expect(put?.url).toContain("version=6");
    const b = put?.body as Record<string, unknown>;
    expect(b.secretRef).toBe("env:ALICE_CCCAM");
    expect(b.enabled).toBe(true);
    expect(typeof b.validUntil).toBe("string");
    expect(String(b.validUntil)).toContain("2030-01-02");
    // Protocoles supportés uniquement : cccam + cs378x
    await expect(dialog.getByLabel("Protocole")).toContainText("cccam");
    const options = await dialog.getByLabel("Protocole").locator("option").allTextContents();
    expect(options).toEqual(["cccam", "cs378x"]);
  });
});
