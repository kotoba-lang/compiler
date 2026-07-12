import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";

const [root, artifactDir] = process.argv.slice(2);
if (!root || !artifactDir) throw new Error("safari webdriver requires root and artifact directory");
const fixturePort = 4174;
const driverPort = 4445;
const elementKey = "element-6066-11e4-a52e-4f735466cecf";
const children = [];
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function child(command, args, env = {}) {
  const process = spawn(command, args, {
    cwd: root,
    env: { ...globalThis.process.env, ...env },
    stdio: ["ignore", "pipe", "pipe"]
  });
  children.push(process);
  return process;
}

async function waitFor(url, attempts = 100) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    try { if ((await fetch(url)).ok) return; } catch {}
    await delay(200);
  }
  throw new Error("service readiness timeout");
}

async function webdriver(method, route, body) {
  const response = await fetch(`http://127.0.0.1:${driverPort}${route}`, {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const value = await response.json();
  if (!response.ok || value.value?.error) {
    const safe = input => String(input ?? "unknown").replace(/[^A-Za-z0-9 .:_-]/g, "?").slice(0, 240);
    throw new Error(`webdriver request failed: ${route}: ${safe(value.value?.error)}: ${safe(value.value?.message)}`);
  }
  return value.value;
}

async function element(session, selector) {
  const value = await webdriver("POST", `/session/${session}/element`, {
    using: "css selector", value: selector
  });
  if (!value?.[elementKey]) throw new Error("webdriver returned no element identity");
  return value[elementKey];
}

async function waitAttribute(session, elementId, name, expected, attempts = 100) {
  for (let attempt = 0; attempt < attempts; attempt++) {
    const value = await webdriver("GET", `/session/${session}/element/${elementId}/attribute/${name}`);
    if (value === expected) return;
    if (value === "failed") throw new Error("browser fixture reported failure");
    await delay(200);
  }
  throw new Error("browser fixture result timeout");
}

let session;
try {
  child(process.execPath,
    [path.join(root, "node_modules/nbb/cli.js"), path.join(root, "scripts/browser-fixture-server.cljs")],
    { KOTOBA_BROWSER_ARTIFACTS: artifactDir, KOTOBA_BROWSER_PORT: String(fixturePort) });
  await waitFor(`http://127.0.0.1:${fixturePort}/health`);
  child("safaridriver", ["--port", String(driverPort)]);
  await waitFor(`http://127.0.0.1:${driverPort}/status`);

  const created = await webdriver("POST", "/session", {
    capabilities: { alwaysMatch: { browserName: "safari" } }
  });
  session = created.sessionId;
  if (typeof session !== "string" || session.length === 0) throw new Error("missing Safari session id");
  const browserVersion = created.capabilities?.browserVersion;
  if (typeof browserVersion !== "string" || !/^[0-9]+(?:\.[0-9]+)+$/.test(browserVersion))
    throw new Error("invalid Safari version");

  await webdriver("POST", `/session/${session}/url`, { url: `http://127.0.0.1:${fixturePort}/index.html` });
  let status = await element(session, "#status");
  try {
    await waitAttribute(session, status, "data-result", "passed");
  } catch (error) {
    const failedReport = await element(session, "#report");
    const failed = JSON.parse(await webdriver("GET", `/session/${session}/element/${failedReport}/text`));
    const safeStage = String(failed.stage ?? "unknown").replace(/[^A-Za-z0-9_-]/g, "?").slice(0, 64);
    throw new Error(`Safari fixture failed at stage: ${safeStage}`, { cause: error });
  }
  const reportElement = await element(session, "#report");
  const report = JSON.parse(await webdriver("GET", `/session/${session}/element/${reportElement}/text`));
  if (report.format !== "kotoba.browser-conformance/v1" || report.forged !== "invalid-pair-handle")
    throw new Error("Safari conformance report mismatch");

  await webdriver("POST", `/session/${session}/url`, {
    url: `http://127.0.0.1:${fixturePort}/tests/browser/csp-blocked.html`
  });
  status = await element(session, "#status");
  try {
    await waitAttribute(session, status, "data-result", "passed");
  } catch (error) {
    const observed = await webdriver("GET", `/session/${session}/element/${status}/text`);
    const safeObserved = String(observed ?? "unknown").replace(/[^A-Za-z0-9_-]/g, "?").slice(0, 64);
    throw new Error(`Safari CSP observation: ${safeObserved}`, { cause: error });
  }
  const text = await webdriver("GET", `/session/${session}/element/${status}/text`);
  if (text !== "wasm-blocked") throw new Error("Safari CSP denial mismatch");

  const evidence = {
    format: "kotoba.browser-engine-evidence/v1",
    status: "passed",
    commit: process.env.GITHUB_SHA ?? "local",
    ciRunId: process.env.GITHUB_RUN_ID ?? "local",
    platform: process.platform,
    projects: [{ project: "safari-stable-macos", browserName: "safari",
      version: browserVersion, evidenceKind: "branded-browser" }]
  };
  const output = path.join(root, "test-results", "browser-evidence.json");
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
  console.log(`safari-conformance: Safari ${browserVersion} passed direct, Worker, capability, heap, and CSP vectors`);
} finally {
  if (session) {
    try { await webdriver("DELETE", `/session/${session}`); } catch {}
  }
  for (const process of children.reverse()) {
    try { process.kill("SIGTERM"); } catch {}
  }
}
