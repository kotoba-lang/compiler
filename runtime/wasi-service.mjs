import fs from "node:fs";
import http from "node:http";
import { createHash } from "node:crypto";
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

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/healthz") {
    respond(response, 200, { format: "kotoba.service-health/v1", status: "ok",
                             target: "wasm32-wasi-kotoba-v1", sha256: actualSha256 });
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
  try {
    if (request.headers["content-type"] !== "application/json")
      throw new Error("content type rejected");
    const body = await readBody(request);
    const parsed = exactRequest(JSON.parse(body.toString("utf8")));
    const hosted = await instantiateKotobaWasi(bytes, { expectedSha256 });
    const fn = hosted.instance.exports[parsed.entry];
    if (typeof fn !== "function") throw new Error("entry is not exported");
    const result = fn(...parsed.args);
    if (typeof result !== "bigint") throw new Error("entry result is not i64");
    respond(response, 200, { format: "kotoba.service-result/v1", status: "ok",
                             result: result.toString(), heap: hosted.report().heap });
  } catch {
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
