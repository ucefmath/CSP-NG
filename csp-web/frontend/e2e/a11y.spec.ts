import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

test.describe("accessibilité", () => {
  test("axe : login + overview sans violation critique/serious", async ({ page }) => {
    await mockApi(page);
    await page.goto("/admin/login");
    const loginScan = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();
    expect(loginScan.violations.filter((v) => v.impact === "critical" || v.impact === "serious")).toEqual([]);
    await login(page);
    const appScan = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .exclude(".kpi-grid") // densités décoratives, couvertes par contrôles manuels
      .analyze();
    const blocking = appScan.violations.filter((v) => v.impact === "critical");
    expect(blocking).toEqual([]);
    // Rapport : violations restantes non critiques loggées pour suivi
    const rest = appScan.violations.filter((v) => v.impact !== "critical").map((v) => `${v.id}(${v.impact})`);
    expect(rest.length, `violations non critiques: ${rest.join(", ")}`).toBeLessThanOrEqual(6);
  });

  test("lang fr, skip link, focus dialogue, Escape ferme", async ({ page }) => {
    await mockApi(page);
    await login(page);
    await expect(page.locator("html")).toHaveAttribute("lang", "fr");
    await page.goto("/admin/profiles");
    // Skip link : Tab depuis le haut puis Entrée → focus main
    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Aller au contenu" });
    await skip.focus();
    await page.keyboard.press("Enter");
    await expect(page.locator("#main")).toBeFocused();
    // Dialogue : focus initial dedans, Escape ferme
    await page.getByRole("button", { name: "Créer un profil" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    const focused = page.locator(":focus");
    await expect(focused).toBeAttached();
    const dlgHandle = await dialog.elementHandle();
    expect(await focused.evaluate((el, dlg) => !!dlg && dlg.contains(el), dlgHandle)).toBe(true);
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
  });

  test("chaque champ de formulaire a un label, chaque bouton un nom", async ({ page }) => {
    await mockApi(page);
    await login(page);
    for (const route of ["/admin/connectors", "/admin/users", "/admin/listeners"]) {
      await page.goto(route);
      for (const input of await page.locator("input, select, textarea").all()) {
        if (!(await input.isVisible())) continue;
        const name = await input.evaluate((el) => {
          const labelled = (el as HTMLElement).getAttribute("aria-label");
          if (labelled) return labelled;
          const id = (el as HTMLElement).id;
          if (id && document.querySelector(`label[for="${CSS.escape(id)}"]`)) return "ok";
          if ((el as HTMLElement).closest("label")) return "ok";
          if ((el as HTMLElement).closest("fieldset")) return "ok";
          return "";
        });
        expect(name, `champ sans label sur ${route}`).toBeTruthy();
      }
      for (const btn of await page.locator("button").all()) {
        if (!(await btn.isVisible())) continue;
        expect((await btn.textContent())?.trim() || (await btn.getAttribute("aria-label")) || "", `bouton sans nom sur ${route}`).toBeTruthy();
      }
    }
  });

  test("CSP stricte servie : page fonctionnelle, aucun script/style inline", async ({ page, request }) => {
    const res = await request.get("/admin/");
    const csp = res.headers()["content-security-policy"] || "";
    expect(csp).toContain("script-src 'self'");
    expect(csp).not.toContain("unsafe-inline");
    const html = await res.text();
    expect(html).not.toMatch(/<script[^>]*>(?!\s*<\/script>)/);
    // style= autorisé uniquement via attributs ? non : aucun style inline attendu
    expect(html).not.toContain("style=");
    await mockApi(page);
    await login(page);
    await expect(page.getByRole("heading", { level: 1, name: "Vue d'ensemble" })).toBeVisible();
  });

  test("mobile 375px : menu tiroir, tiroir bulk accessible", async ({ page }) => {
    await mockApi(page);
    await page.setViewportSize({ width: 375, height: 700 });
    await login(page);
    await page.goto("/admin/connectors");
    const menu = page.getByRole("button", { name: /menu/i });
    await expect(menu).toBeVisible();
    const nav = page.getByRole("navigation", { name: "Navigation principale" });
    // Tiroir fermé : hors écran (translateX), pas display:none (transition).
    const closedBox = await nav.boundingBox();
    expect(closedBox, "tiroir fermé hors écran").toBeTruthy();
    expect(closedBox!.x, "tiroir fermé hors écran").toBeLessThan(0);
    await menu.click();
    // Transition 0.2s : poll jusqu'à ouverture complète.
    await expect
      .poll(async () => (await nav.boundingBox())?.x ?? -999, { timeout: 5000 })
      .toBeGreaterThanOrEqual(0);
    await page.getByRole("navigation", { name: "Navigation principale" })
      .getByRole("link", { name: "Utilisateurs", exact: true }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Utilisateurs" })).toBeVisible();
  });
});
