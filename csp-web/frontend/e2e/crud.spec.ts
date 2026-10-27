import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

// Mutations restantes : corps exacts prouvant la compatibilité API (DTO backend).
test.describe("crud générique", () => {
  test("profil : édition PUT corps complet + version", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/profiles");
    await page.getByRole("row", { name: /hdplus/ }).getByRole("button", { name: "Éditer" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("maxCwWaitMs").fill("6000");
    await dialog.getByRole("button", { name: "Enregistrer" }).click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/profiles/hdplus", "PUT");
    expect(put?.body).toEqual({
      enabled: true, debug: false, cacheOnly: false, caId: 1830, networkId: 0,
      requireProviderMatch: false, checkEcmLength: false, maxCwWaitMs: 6000,
      congestionLimitMs: null, mismatchedCards: false, serviceConflicts: 0,
      servicesFile: null, providerFilter: null, servicesFileFormat: null,
      blockCaidMismatch: false, version: 2,
    });
  });

  test("profil : suppression DELETE ?version", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/profiles");
    await page.getByRole("row", { name: /sky/ }).getByRole("button", { name: "Supprimer" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Confirmer" }).click();
    const del = api.last("/api/admin/config/profiles/sky", "DELETE");
    expect(del?.url).toContain("version=5");
  });

  test("listener : création PUT corps ListenerDto exact", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/listeners");
    await page.getByRole("button", { name: /Nouveau listener/ }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Port (1-65535)").fill("13000");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog).toBeHidden();
    const put = api.last("/api/admin/config/listeners/0", "PUT");
    const b = put?.body as Record<string, unknown>;
    expect(b).toMatchObject({
      profileName: "hdplus", protocol: "newcamd", port: 13000,
      bindAddress: "0.0.0.0", enabled: true, version: 1,
    });
    expect(Object.keys(b).sort()).toEqual([
      "allowList", "bindAddress", "cardMode", "denyList", "desKeyRef", "enabled",
      "idleTimeoutSec", "maxCards", "maxInflight", "maxPerIp", "nodeId", "port",
      "profileName", "protocol", "reshareCap", "strictPolicy", "version",
    ].sort());
  });

  test("utilisateur : création POST ACL (sets sérialisés en tableaux)", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/users");
    await page.getByRole("button", { name: "Créer utilisateur" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Nom").fill("bob");
    await dialog.getByLabel(/Mot de passe/).fill("mot-de-passe-tres-long");
    await dialog.getByLabel("hdplus", { exact: true }).check();
    await dialog.getByLabel("allowedServices (profil: sids — vide = inchangé en édition)").fill("hdplus: 0x1234, 100");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog).toBeHidden();
    const post = api.last("/api/admin/users", "POST");
    const b = post?.body as Record<string, unknown>;
    expect(b).toMatchObject({
      name: "bob", password: "mot-de-passe-tres-long",
      profiles: ["hdplus"],
      allowedServices: { hdplus: [100, 0x1234] },
      blockedServices: {},
    });
  });

  test("général + cache : PUT corps exacts", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/general");
    await page.getByLabel("Nom du proxy").fill("e2e-proxy-2");
    await page.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByText(/Enregistré/)).toBeVisible();
    expect(api.last("/api/admin/config/root", "PUT")?.body).toEqual({ proxyName: "e2e-proxy-2", version: 3 });
    await page.goto("/admin/cache");
    await page.getByLabel(/maximumSize/).fill("50000");
    await page.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByText(/Enregistré/)).toBeVisible();
    expect(api.last("/api/admin/config/cache", "PUT")?.body).toEqual({
      cwMaxAgeMs: 30000, maximumSize: 50000,
      maxCacheWaitMs: 500, maxCacheWaitPercent: -1, version: 2,
    });
  });

  test("cw-cycle : PUT caids triés + version", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/cw-cycle");
    await page.getByLabel(/CAID/).fill("0x500, 0x100");
    await page.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByText(/Enregistré/)).toBeVisible();
    const put = api.last("/api/admin/config/cw-cycle", "PUT");
    expect(put?.body).toMatchObject({
      enabled: false, caids: [256, 1280], maxList: 500, keepTimeSec: 15,
      onBad: "drop", dropOld: true, usePeerCycleMetadata: false, version: 1,
    });
  });

  test("catalogue : publication capacité PUT {state,version}", async ({ page }) => {
    const api = await mockApi(page, {
      overrides: [
        {
          match: (url) => /\/api\/admin\/catalog\/capabilities\/[^/]+$/.test(new URL(url).pathname),
          handle: async (route) => {
            await route.fulfill({
              status: 200, contentType: "application/json",
              body: JSON.stringify([
                { id: 10, profile: "hdplus", caid: 1830, provider: 0, state: "blocked", cardId: 424242, version: 1, updatedAt: "2024-01-01T00:00:00Z" },
              ]),
            });
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/catalog");
    await page.getByRole("button", { name: "Charger le catalogue" }).click();
    await page.getByRole("button", { name: "Publier" }).click();
    const put = api.last("/api/admin/catalog/capabilities/10/state", "PUT");
    expect(put?.body).toEqual({ state: "published", version: 1 });
    // Sources : colonnes alignées SourceResponse
    await page.getByRole("button", { name: "Sources" }).click();
    await expect(page.getByText("conn-a").first()).toBeVisible();
    await expect(page.getByText("direct").first()).toBeVisible();
  });
});
