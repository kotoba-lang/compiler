import { test, expect } from "@playwright/test";

test("compiler-produced Wasm passes direct and Worker boundaries", async ({ page, browser, browserName }, testInfo) => {
  const pageErrors = [];
  page.on("pageerror", error => pageErrors.push(error.message));
  await page.goto("/index.html");
  await expect(page.locator("#status")).toHaveAttribute("data-result", "passed", { timeout: 15000 });
  const report = JSON.parse(await page.locator("#report").textContent());
  expect(report).toEqual({
    format: "kotoba.browser-conformance/v1",
    direct: "ok",
    worker: "ok",
    capability: "allowed-and-denied",
    heap: { heap: { capacity: 4096, used: 2 } },
    forged: "invalid-pair-handle"
  });
  expect(pageErrors).toEqual([]);
  const project = testInfo.project.name;
  // "-beta-" projects are the same vendor-branded Chrome/Edge builds as "-stable-", just
  // pinned to the beta release channel instead of stable (see playwright.config.mjs for
  // why this is forward-looking pre-stable signal, not previous-version compatibility).
  const evidenceKind = project.includes("-stable-") ? "branded-browser"
    : project.includes("-beta-") ? "branded-browser-beta"
    : project.includes("-emulation") ? "mobile-emulation" : "engine";
  testInfo.annotations.push({
    type: "kotoba-browser-identity",
    description: JSON.stringify({ project, browserName, version: browser.version(), evidenceKind })
  });
});

test("CSP without wasm-unsafe-eval blocks Wasm compilation", async ({ page }) => {
  await page.goto("/tests/browser/csp-blocked.html");
  await expect(page.locator("#status")).toHaveAttribute("data-result", "passed");
  await expect(page.locator("#status")).toHaveText("wasm-blocked");
});
