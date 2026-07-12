import fs from "node:fs";
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const [structuredPath, capabilityPath] = process.argv.slice(2);
const servicePath = fileURLToPath(new URL("../runtime/wasi-service.mjs", import.meta.url));
const digest = path => createHash("sha256").update(fs.readFileSync(path)).digest("hex");
const request = (entry, args = [], extra = {}) => ({
  format: "kotoba.service-request/v1", entry, args, ...extra
});

async function waitForHealth(port) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`);
      if (response.ok) return response.json();
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error("WASI service failed to become healthy");
}

async function withService(wasmPath, port, test) {
  const child = spawn(process.execPath, [servicePath], {
    stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, PORT: String(port), KOTOBA_WASM_PATH: wasmPath,
           KOTOBA_WASM_SHA256: digest(wasmPath) }
  });
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk; });
  const exited = new Promise(resolve => child.once("exit", resolve));
  try {
    const health = await waitForHealth(port);
    await test(health);
  } catch (error) {
    throw new Error(`${error.message}; service stderr: ${stderr}`, { cause: error });
  } finally {
    child.kill("SIGTERM");
    await exited;
  }
  if (stderr) throw new Error(`WASI service emitted stderr: ${stderr}`);
}

async function post(port, body, contentType = "application/json") {
  const response = await fetch(`http://127.0.0.1:${port}/v1/run`, {
    method: "POST", headers: { "content-type": contentType },
    body: typeof body === "string" ? body : JSON.stringify(body)
  });
  return { status: response.status, value: await response.json() };
}

await withService(structuredPath, 18081, async health => {
  if (health.sha256 !== digest(structuredPath) || health.target !== "wasm32-wasi-kotoba-v1")
    throw new Error("service health identity mismatch");
  for (let iteration = 0; iteration < 2; iteration += 1) {
    const main = await post(18081, request("main"));
    if (main.status !== 200 || main.value.result !== "42" || main.value.heap.used !== 0)
      throw new Error("service instance isolation/result mismatch");
  }
  const score = await post(18081, request("score", ["-7", "2"]));
  if (score.status !== 200 || score.value.result !== "12")
    throw new Error("service argument ABI mismatch");
  for (const [body, contentType] of [
    [request("main", [], { extra: true }), "application/json"],
    [request("main", ["9223372036854775808"]), "application/json"],
    ["x".repeat(5000), "application/json"],
    [JSON.stringify(request("main")), "text/plain"]
  ]) {
    const rejected = await post(18081, body, contentType);
    if (rejected.status !== 400 || rejected.value.error !== "request-rejected")
      throw new Error("service malformed request was accepted");
  }
});

await withService(capabilityPath, 18082, async () => {
  const denied = await post(18082, request("main"));
  if (denied.status !== 400 || denied.value.error !== "request-rejected")
    throw new Error("service ambient capability was granted");
});

const uleb = value => {
  const out = [];
  do { let byte = value & 0x7f; value >>>= 7; if (value) byte |= 0x80; out.push(byte); }
  while (value);
  return out;
};
const utf8 = value => Array.from(Buffer.from(value, "utf8"));
const name = value => [...uleb(utf8(value).length), ...utf8(value)];
const section = (id, value) => [id, ...uleb(value.length), ...value];
const custom = [...name("kotoba.target"), ...utf8("wasm32-wasi-kotoba-v1")];
const hostilePath = path.join(path.dirname(capabilityPath), "hostile-loop.wasm");
fs.writeFileSync(hostilePath, Uint8Array.from([
  0, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
  ...section(0, custom),
  ...section(1, [1, 0x60, 0, 1, 0x7e]),
  ...section(3, [1, 0]),
  ...section(7, [1, ...name("main"), 0, 0]),
  ...section(10, [1, 9, 0, 0x03, 0x40, 0x0c, 0, 0x0b, 0x42, 0, 0x0b])
]));
await withService(hostilePath, 18083, async health => {
  const started = Date.now();
  const cancelled = await post(18083, request("main"));
  if (cancelled.status !== 400 || cancelled.value.error !== "request-rejected" ||
      Date.now() - started > 2500)
    throw new Error("service guest deadline was not enforced");
  const after = await fetch("http://127.0.0.1:18083/healthz");
  if (!after.ok || health.sha256 !== digest(hostilePath))
    throw new Error("service did not survive cancelled guest");
  const metrics = await (await fetch("http://127.0.0.1:18083/metrics")).text();
  if (!metrics.includes("kotoba_service_guest_deadlines_total 1") ||
      !metrics.includes("kotoba_service_active_workers 0") ||
      !metrics.includes(digest(hostilePath)))
    throw new Error("service deadline metrics mismatch");
});

const mismatch = spawn(process.execPath, [servicePath], {
  stdio: "ignore",
  env: { ...process.env, PORT: "18085", KOTOBA_WASM_PATH: structuredPath,
         KOTOBA_WASM_SHA256: "0".repeat(64) }
});
const mismatchStatus = await new Promise(resolve => mismatch.once("exit", resolve));
if (mismatchStatus === 0) throw new Error("service module digest mismatch was accepted");

console.log("wasi-service: identity, isolated execution, deadline cancellation, bounds, and capability denial passed");
