import { defineConfig, devices } from "@playwright/test";

const platform = process.platform === "win32" ? "windows" : process.platform;
const brandedProjects = process.env.KOTOBA_BRANDED_BROWSERS === "1" ? [
  { name: `chrome-stable-${platform}`, use: { ...devices["Desktop Chrome"], channel: "chrome" } },
  { name: `edge-stable-${platform}`, use: { ...devices["Desktop Edge"], channel: "msedge" } }
] : [];
// Playwright's `channel` option only selects a named, currently-installable release
// channel (stable/beta/dev/canary for the Chromium family); it has no mechanism to pin
// an arbitrary *historical* version number, and there is no upstream "chrome-N-1" channel
// to select. So this is NOT a backward previous-version compatibility lane. What it does
// buy is a forward-looking signal: does the Wasm/CSP contract still hold against the
// pre-stable build each vendor is about to ship next. Firefox and WebKit have no
// beta/dev/nightly `channel` value in Playwright (only Chromium-family browsers expose
// this), so there is no equivalent lane for them here.
const betaProjects = process.env.KOTOBA_BETA_BROWSERS === "1" ? [
  { name: `chrome-beta-${platform}`, use: { ...devices["Desktop Chrome"], channel: "chrome-beta" } },
  { name: `edge-beta-${platform}`, use: { ...devices["Desktop Edge"], channel: "msedge-beta" } }
] : [];

export default defineConfig({
  testDir: "./tests/browser",
  testMatch: "browser.spec.mjs",
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ["line"],
    ["./scripts/browser-evidence-reporter.mjs", { outputFile: "test-results/browser-evidence.json" }]
  ],
  use: { baseURL: "http://127.0.0.1:4173", trace: "retain-on-failure" },
  webServer: {
    command: "node node_modules/nbb/cli.js scripts/browser-fixture-server.cljs",
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
    { name: "webkit-mobile-emulation", use: { ...devices["iPhone 15"] } },
    ...brandedProjects,
    ...betaProjects
  ]
});
