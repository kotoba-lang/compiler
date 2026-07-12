import fs from "node:fs";
import http from "node:http";
import { createHash } from "node:crypto";
import { Worker } from "node:worker_threads";
import { instantiateKotobaWasi } from "./wasi-host.mjs";

const MAX_BODY_BYTES = 4096;
const MAX_CONCURRENCY = 8;
const wasmPath = process.env.KOTOBA_WASM_PATH ?? "/app/program.wasm";
const expectedSha256 = process.env.KOTOBA_WASM_SHA256;
const port = Number(process.env.PORT ?? "8080");

if (!/^[0-9a-f]{64}$/.test(expectedSha256 ?? ""))
  throw new Error("KOTOBA_WASM_SHA256 must be a sealed lowercase digest");
if (!Number.isInteger(port) || port < 1 || port > 65535)
  throw new Error("PORT is outside [1,65535]");

const bytes = fs.readFileSync(wasmPath);
const actualSha256 = createHash("sha256").update(bytes).digest("hex");
if (actualSha256 !== expectedSha256) throw new Error("sealed service module digest mismatch");
await instantiateKotobaWasi(bytes, { expectedSha256 });

let active = 0;
const metrics = { requests: 0, success: 0, rejected: 0, deadlines: 0 };
const exactRequest = value => {
  if (value === null || typeof value !== "object" || Array.isArray(value) ||
      Object.getPrototypeOf(value) !== Object.prototype)
    throw new Error("request must be a plain object");
  if (Object.keys(value).sort().join(",") !== "args,entry,format" ||
      value.format !== "kotoba.service-request/v1" ||
      typeof value.entry !== "string" || !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/.test(value.entry) ||
      !Array.isArray(value.args) || value.args.length > 5)
    throw new Error("request schema rejected");
  const args = value.args.map(item => {
    if (typeof item !== "string" || !/^-?(0|[1-9][0-9]{0,18})$/.test(item))
      throw new Error("argument is not canonical decimal i64");
    const parsed = BigInt(item);
    if (parsed < -9223372036854775808n || parsed > 9223372036854775807n)
      throw new Error("argument is outside i64");
    return parsed;
  });
  return { entry: value.entry, args };
};

const respond = (response, status, value) => {
  const body = Buffer.from(JSON.stringify(value));
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": body.length,
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  });
  response.end(body);
};

const readBody = request => new Promise((resolve, reject) => {
  const chunks = [];
  let size = 0;
  const timer = setTimeout(() => reject(new Error("request body timeout")), 2000);
  request.on("data", chunk => {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      clearTimeout(timer);
      reject(new Error("request body exceeds limit"));
    } else chunks.push(chunk);
  });
  request.on("end", () => { clearTimeout(timer); resolve(Buffer.concat(chunks)); });
  request.on("error", error => { clearTimeout(timer); reject(error); });
});

const runGuest = parsed => new Promise((resolve, reject) => {
  const worker = new Worker(new URL("./wasi-service-worker.mjs", import.meta.url), {
    workerData: { bytes, sha256: expectedSha256, entry: parsed.entry, args: parsed.args }
  });
  let settled = false;
  const finish = (callback, value) => {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    callback(value);
  };
  const timer = setTimeout(() => {
    const error = new Error("guest deadline exceeded");
    error.code = "guest-deadline";
    finish(reject, error);
    void worker.terminate();
  }, 1000);
  worker.once("message", value => {
    if (value?.ok === true) finish(resolve, value);
    else finish(reject, new Error("guest execution rejected"));
    void worker.terminate();
  });
  worker.once("error", error => finish(reject, error));
  worker.once("exit", code => {
    if (code !== 0) finish(reject, new Error("guest worker exited"));
  });
});

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/healthz") {
    respond(response, 200, { format: "kotoba.service-health/v1", status: "ok",
                             target: "wasm32-wasi-kotoba-v1", sha256: actualSha256 });
    return;
  }
  if (request.method === "GET" && request.url === "/metrics") {
    const body = Buffer.from(
      `# TYPE kotoba_service_requests_total counter\n` +
      `kotoba_service_requests_total ${metrics.requests}\n` +
      `# TYPE kotoba_service_success_total counter\n` +
      `kotoba_service_success_total ${metrics.success}\n` +
      `# TYPE kotoba_service_rejected_total counter\n` +
      `kotoba_service_rejected_total ${metrics.rejected}\n` +
      `# TYPE kotoba_service_guest_deadlines_total counter\n` +
      `kotoba_service_guest_deadlines_total ${metrics.deadlines}\n` +
      `# TYPE kotoba_service_active_workers gauge\n` +
      `kotoba_service_active_workers ${active}\n` +
      `# TYPE kotoba_service_module_info gauge\n` +
      `kotoba_service_module_info{target="wasm32-wasi-kotoba-v1",sha256="${actualSha256}"} 1\n`
    );
    response.writeHead(200, { "content-type": "text/plain; version=0.0.4; charset=utf-8",
                              "content-length": body.length, "cache-control": "no-store" });
    response.end(body);
    return;
  }
  if (request.method !== "POST" || request.url !== "/v1/run") {
    respond(response, 404, { format: "kotoba.service-error/v1", error: "not-found" });
    return;
  }
  if (active >= MAX_CONCURRENCY) {
    respond(response, 503, { format: "kotoba.service-error/v1", error: "busy" });
    return;
  }
  active += 1;
  metrics.requests += 1;
  try {
    if (request.headers["content-type"] !== "application/json")
      throw new Error("content type rejected");
    const body = await readBody(request);
    const parsed = exactRequest(JSON.parse(body.toString("utf8")));
    const executed = await runGuest(parsed);
    metrics.success += 1;
    respond(response, 200, { format: "kotoba.service-result/v1", status: "ok",
                             result: executed.result, heap: executed.heap });
  } catch (error) {
    metrics.rejected += 1;
    if (error?.code === "guest-deadline") metrics.deadlines += 1;
    respond(response, 400, { format: "kotoba.service-error/v1", error: "request-rejected" });
  } finally {
    active -= 1;
  }
});

server.requestTimeout = 3000;
server.headersTimeout = 3000;
server.keepAliveTimeout = 1000;
server.listen(port, "0.0.0.0");
const shutdown = () => server.close(() => process.exit(0));
process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
