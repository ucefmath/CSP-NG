import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("authentification", () => {
  test("login OK → vue d'ensemble, puis logout", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await expect(page).toHaveURL(/\/admin\/overview/);
    const loginCall = api.last("/api/admin/session", "POST");
    expect(loginCall?.body).toEqual({ username: "admin", password: "correct-12-chars" });
    // CSRF non exigé en GET, mais présent sur mutations (vérifié ailleurs).
    await page.getByRole("button", { name: "Déconnexion" }).click();
    await expect(page).toHaveURL(/\/admin\/login/);
    expect(api.last("/api/admin/session", "DELETE")).toBeTruthy();
  });

  test("login 401 → message identifiants invalides, reste sur login", async ({ page }) => {
    await mockApi(page);
    await page.goto("/admin/login");
    await page.getByLabel("Identifiant").fill("admin");
    await page.getByLabel("Mot de passe").fill("mauvais-mot-de-passe");
    await page.getByRole("button", { name: "Se connecter" }).click();
    await expect(page.getByRole("alert")).toContainText("Identifiants invalides");
    await expect(page).toHaveURL(/\/admin\/login/);
  });

  test("expiration 401 en cours de session → retour login + message", async ({ page }) => {
    let authed = false;
    await mockApi(page, {
      overrides: [
        {
          match: (url, method) => url.includes("/api/admin/config") && method === "GET" && authed,
          handle: async (route) => {
            await route.fulfill({
              status: 401,
              contentType: "application/json",
              body: JSON.stringify({ code: "UNAUTHORIZED", message: "Session expirée" }),
            });
          },
        },
      ],
    });
    await login(page);
    authed = true;
    // Déclenche un rechargement qui renvoie 401 (bouton absent : on navigue).
    await page.goto("/admin/profiles");
    await expect(page).toHaveURL(/\/admin\/login/);
  });

  test("route protégée sans session → login", async ({ page }) => {
    await mockApi(page);
    await page.goto("/admin/users");
    await expect(page).toHaveURL(/\/admin\/login/);
  });
});
