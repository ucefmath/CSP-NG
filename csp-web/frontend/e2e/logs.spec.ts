import { expect, test } from "@playwright/test";
import { login, mockApi } from "./fixtures";

const items = [
  { seq: 1, timestamp: 1793300000000, level: "INFO", logger: "com.bowman.cspng.cacheex", message: "CACHEEX PUSH proto=cs378x caid=0x1813 sid=0x3D55 parity=0x80" },
  { seq: 2, timestamp: 1793300001000, level: "INFO", logger: "com.bowman.cspng.cacheex", message: "CACHEEX PUSH proto=cs378x caid=0x1813 sid=0x3AA6 parity=0x81" },
  { seq: 3, timestamp: 1793300002000, level: "INFO", logger: "com.bowman.cspng.ecm", message: "CACHE HIT type=INSTANT sid=0x3D55" },
];

test.describe("logs filtre", () => {
  test("texte, exclusion, regex, catégorie, URL persistée", async ({ page }) => {
    let served = false;
    await mockApi(page, {
      overrides: [
        {
          match: (url) => new URL(url).pathname === "/api/admin/logs/sources",
          handle: (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ payload: ["main"] }) }),
        },
        {
          match: (url) => new URL(url).pathname === "/api/admin/logs",
          handle: (route) => {
            const body = { payload: { source: "main", items: served ? [] : items, nextAfter: 3 } };
            served = true;
            return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
          },
        },
      ],
    });
    await login(page);
    await page.goto("/admin/logs");
    const consoleRegion = page.getByRole("region", { name: "Console des logs" });
    await expect(consoleRegion.locator(".log-line")).toHaveCount(3);

    const filter = page.getByLabel("Filtre :");
    await filter.fill("sid=0x3d55 -cacheex");
    await expect(consoleRegion.locator(".log-line")).toHaveCount(1);
    await expect(consoleRegion.locator(".log-line")).toContainText("CACHE HIT");
    await expect(page.getByRole("status").filter({ hasText: "/ 3 lignes" })).toContainText("1 / 3 lignes");
    await expect(page).toHaveURL(/q=sid%3D0x3d55/);

    await filter.fill("sid=0x(3D55|3AA6) parity");
    await page.getByRole("checkbox", { name: "Regex" }).check();
    await expect(consoleRegion.locator(".log-line")).toHaveCount(2);
    await expect(consoleRegion.locator("mark.log-hit").first()).toBeVisible();

    await filter.fill("sid=(");
    await expect(page.getByRole("alert").filter({ hasText: "Filtre invalide" })).toBeVisible();

    await page.getByRole("button", { name: "Réinitialiser le filtre" }).click();
    await expect(consoleRegion.locator(".log-line")).toHaveCount(3);
    await page.getByLabel("Catégorie :").selectOption("com.bowman.cspng.ecm");
    await expect(consoleRegion.locator(".log-line")).toHaveCount(1);

    // Aide : un exemple cliquable applique le filtre et le mode regex
    await page.getByLabel("Catégorie :").selectOption("");
    await page.getByText("Aide et exemples de filtres").click();
    await page.getByRole("button", { name: "Appliquer le filtre caid=0x18(13|84)" }).click();
    await expect(page.getByLabel("Filtre :")).toHaveValue("caid=0x18(13|84)");
    await expect(page.getByRole("checkbox", { name: "Regex" })).toBeChecked();
    await expect(consoleRegion.locator(".log-line")).toHaveCount(2);

    // Filtre restauré depuis l'URL
    await page.goto("/admin/logs?q=parity%3D0x81");
    await expect(page.getByLabel("Filtre :")).toHaveValue("parity=0x81");
  });
});
