import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/browser",
  testMatch: "browser.spec.mjs",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: "line",
  use: { baseURL: "http://127.0.0.1:4173", trace: "retain-on-failure" },
  webServer: {
    command: "nbb scripts/browser-fixture-server.cljs",
    url: "http://127.0.0.1:4173/health",
    reuseExistingServer: false,
    stdout: "pipe",
    stderr: "pipe",
    env: { KOTOBA_BROWSER_ARTIFACTS: process.env.KOTOBA_BROWSER_ARTIFACTS }
  },
  projects: [
    { name: "chromium-desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "firefox-desktop", use: { ...devices["Desktop Firefox"] } },
    { name: "webkit-desktop", use: { ...devices["Desktop Safari"] } },
    { name: "chromium-mobile-emulation", use: { ...devices["Pixel 7"] } },
    { name: "webkit-mobile-emulation", use: { ...devices["iPhone 15"] } }
  ]
});
