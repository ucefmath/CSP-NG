import { defineConfig } from "@playwright/test";

const CHROMIUM_BIN = process.env.CSP_CHROMIUM_BIN || "/usr/bin/chromium";

export default defineConfig({
  testDir: "./e2e",
  testMatch: ["**/*.spec.ts"],
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: "http://127.0.0.1:4179",
    headless: true,
    launchOptions: { executablePath: CHROMIUM_BIN, args: ["--no-sandbox", "--disable-gpu"] },
    viewport: { width: 1280, height: 800 },
  },
  webServer: {
    command: "node e2e/server.mjs",
    url: "http://127.0.0.1:4179/admin/",
    reuseExistingServer: true,
    timeout: 15_000,
    env: { E2E_PORT: "4179" },
  },
});
