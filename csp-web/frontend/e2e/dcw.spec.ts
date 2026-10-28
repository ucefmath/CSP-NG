import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("dcw", () => {
  test("onglets clavier : Tab + Entrée, aria-selected, panneau change", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    const tablist = page.getByRole("tablist", { name: "Sections DCW" });
    await expect(tablist).toBeVisible();
    const regles = page.getByRole("tab", { name: "Règles" });
    await expect(regles).toHaveAttribute("aria-selected", "false");
    await regles.focus();
    await expect(regles).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(regles).toHaveAttribute("aria-selected", "true");
    await expect(page.getByRole("heading", { name: "Règles de filtrage" })).toBeVisible();
    // Retour clavier à la politique
    await page.getByRole("tab", { name: "Politique" }).focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { name: "Politique DCW globale" })).toBeVisible();
  });

  test("politique : PUT corps exact 8 clés + version, modes dangereux → alerte", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await expect(page.getByRole("heading", { name: "Politique DCW globale" })).toBeVisible();
    // Modes dangereux affichent l'avertissement
    await page.getByLabel("Mode Réparation").selectOption("enforce");
    await expect(page.getByRole("alert")).toContainText(/Option avancée/);
    await page.getByLabel("Mode Réparation").selectOption("observe");
    await expect(page.getByRole("alert")).toHaveCount(0);
    await page.getByLabel("Mode Checksum par défaut").selectOption("reject_valid");
    await expect(page.getByRole("alert")).toContainText(/Option avancée/);
    await page.getByLabel("Mode Checksum par défaut").selectOption("inherit");
    await page.getByRole("button", { name: "Enregistrer la politique" }).click();
    await expect(page.getByText("Politique DCW enregistrée")).toBeVisible();
    const put = api.last("/api/admin/dcw/policy", "PUT");
    expect(put?.body).toEqual({
      enabled: true,
      adaptiveRoutingEnabled: false,
      autoObserveEnabled: true,
      zeroCountingEnabled: false,
      defaultChecksumMode: "inherit",
      repairMode: "observe",
      allowZeroHalf: false,
      version: 4,
    });
  });

  test("règle block sans digest : erreur inline, aucun POST", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await page.getByRole("tab", { name: "Règles" }).click();
    await page.getByRole("button", { name: "Nouvelle règle" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Action", { exact: true }).selectOption("block");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog.getByRole("alert")).toContainText(/requiert un digestSha256 ou badDcwHex/);
    expect(api.captured.filter((c) => c.url.endsWith("/dcw/rules") && c.method === "POST")).toHaveLength(0);
  });

  test("règle observe + digest valide : POST corps exact", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await page.getByRole("tab", { name: "Règles" }).click();
    await page.getByRole("button", { name: "Nouvelle règle" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("CAID hex (0 = joker)").fill("1830");
    await dialog.getByLabel("Action", { exact: true }).selectOption("observe");
    await dialog.getByLabel(/Digest SHA-256/).fill("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog).toBeHidden();
    const post = api.last("/api/admin/dcw/rules", "POST");
    const b = post?.body as Record<string, unknown>;
    expect(b).toMatchObject({
      profile: null, caid: 0x1830, provider: null, sid: 0, sourceId: null,
      digestSha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      badDcwHex: null, action: "observe", checksumMode: "ignore", notes: null, enabled: true,
    });
    expect(b).not.toHaveProperty("version");
  });

  test("mapping source==cible : erreur inline ; XSS rendu en texte", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await page.getByRole("tab", { name: "Mappings" }).click();
    const xss = '<img src=x onerror=alert(1)>';
    // XSS : aucun dialogue JS ne doit s'ouvrir
    let dialogOpened = false;
    page.on("dialog", () => {
      dialogOpened = true;
    });
    await page.getByRole("button", { name: "Nouveau mapping" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Profil/).fill("hdplus");
    await dialog.getByLabel(/CAID/).fill("1830");
    await dialog.getByLabel(/Provider/).fill("0");
    await dialog.getByLabel(/SID Source/).fill("100");
    await dialog.getByLabel(/SID Cible/).fill("100");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog.getByRole("alert")).toContainText(/doit différer/);
    expect(api.captured.filter((c) => c.url.endsWith("/dcw/mappings") && c.method === "POST")).toHaveLength(0);
    // Ferme le dialogue (Escape) avant de changer d'onglet.
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    // Corrige avec payload XSS dans un champ texte libre (notes règle)
    await page.getByRole("tab", { name: "Règles" }).click();
    await page.getByRole("button", { name: "Nouvelle règle" }).click();
    const rdialog = page.getByRole("dialog");
    await rdialog.getByLabel(/Notes/).fill(xss);
    await rdialog.getByLabel("Action", { exact: true }).selectOption("none");
    await rdialog.getByLabel("Mode Checksum").selectOption("ignore");
    await rdialog.getByRole("button", { name: "Créer" }).click();
    await expect(rdialog).toBeHidden();
    const post = api.last("/api/admin/dcw/rules", "POST");
    expect((post?.body as Record<string, unknown>).notes).toBe(xss);
    expect(dialogOpened).toBe(false);
    expect(api.captured.filter((c) => c.url.endsWith("/dcw/mappings") && c.method === "POST")).toHaveLength(0);
  });

  test("mapping valide : POST avec expiresAtMs", async ({ page }) => {
    const api = await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await page.getByRole("tab", { name: "Mappings" }).click();
    await page.getByRole("button", { name: "Nouveau mapping" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByLabel(/Profil/).fill("hdplus");
    await dialog.getByLabel(/CAID/).fill("1830");
    await dialog.getByLabel(/Provider/).fill("0");
    await dialog.getByLabel(/SID Source/).fill("0064");
    await dialog.getByLabel(/SID Cible/).fill("0200");
    await dialog.getByRole("button", { name: "Créer" }).click();
    await expect(dialog).toBeHidden();
    const post = api.last("/api/admin/dcw/mappings", "POST");
    expect(post?.body).toMatchObject({
      profile: "hdplus", caid: 0x1830, provider: 0,
      sourceSid: 0x64, targetSid: 0x200, approved: true, enabled: true, expiresAtMs: 0,
    });
  });

  test("observabilité : compteurs + candidats, endpoints 404 → inconnu", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await page.goto("/admin/dcw");
    await page.getByRole("tab", { name: "Observabilité" }).click();
    await expect(page.getByText("Candidats").first()).toBeVisible();
    await expect(page.getByText("conn-a").first()).toBeVisible();
  });
});
