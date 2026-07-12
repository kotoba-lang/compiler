import { test, expect } from "@playwright/test";

test("compiler-produced Wasm passes direct and Worker boundaries", async ({ page }, testInfo) => {
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
  testInfo.annotations.push({ type: "engine", description: testInfo.project.name });
});

test("CSP without wasm-unsafe-eval blocks Wasm compilation", async ({ page }) => {
  await page.goto("/tests/browser/csp-blocked.html");
  await expect(page.locator("#status")).toHaveAttribute("data-result", "passed");
  await expect(page.locator("#status")).toHaveText("wasm-blocked");
});
