import { instantiateKotoba, normalizeKotobaTrap } from "./browser-host.mjs";

const REQUEST_FORMAT = "kotoba.worker-request/v1";
const RESPONSE_FORMAT = "kotoba.worker-response/v1";
const MAX_ARGS = 5;
const REQUEST_KEYS = new Set([
  "format", "id", "op", "wasm", "expectedSha256", "allowCapabilities", "args"
]);

function fail(code) {
  const error = new Error(code);
  error.name = "KotobaWorkerProtocolError";
  error.kotobaCode = code;
  throw error;
}

function exactObject(value, keys, code) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) fail(code);
  for (const key of Object.keys(value)) if (!keys.has(key)) fail(code);
  return value;
}

function requestId(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(value))
    fail("invalid-request-id");
  return value;
}

function args(value = []) {
  if (!Array.isArray(value) || value.length > MAX_ARGS || value.some(item => typeof item !== "bigint"))
    fail("invalid-arguments");
  return value.map(item => BigInt.asIntN(64, item));
}

function capabilities(value) {
  if (value === undefined) return new Map();
  if (!(value instanceof Map) || value.size > 256) fail("invalid-worker-options");
  const copy = new Map();
  for (const [id, handler] of value) {
    if (!Number.isInteger(id) || id < 0 || id > 255 || typeof handler !== "function")
      fail("invalid-worker-options");
    copy.set(id, handler);
  }
  return copy;
}

function protocolTrap(error) {
  if (error?.name === "KotobaWorkerProtocolError")
    return Object.freeze({ kind: "worker-protocol", code: error.kotobaCode });
  return normalizeKotobaTrap(error);
}

export function createKotobaWorkerHandler(rawOptions = {}) {
  exactObject(rawOptions, new Set(["capabilities"]), "invalid-worker-options");
  const handlers = capabilities(rawOptions.capabilities);
  let busy = false;

  return async function handleKotobaWorkerMessage(event) {
    let id = null;
    try {
      const request = exactObject(event?.data, REQUEST_KEYS, "invalid-request");
      id = requestId(request.id);
      if (request.format !== REQUEST_FORMAT || request.op !== "run") fail("invalid-request");
      if (busy) fail("worker-busy");
      busy = true;
      try {
        const argv = args(request.args);
        const hosted = await instantiateKotoba(request.wasm, {
          expectedSha256: request.expectedSha256,
          allowCapabilities: request.allowCapabilities,
          capCall(capabilityId, value) {
            const handler = handlers.get(capabilityId);
            if (!handler) fail("capability-unimplemented");
            return handler(value);
          }
        });
        const result = hosted.instance.exports.main(...argv);
        if (typeof result !== "bigint") fail("invalid-result");
        event.target.postMessage(Object.freeze({
          format: RESPONSE_FORMAT,
          id,
          status: "ok",
          result: BigInt.asIntN(64, result),
          sha256: hosted.sha256,
          report: hosted.report()
        }));
      } finally {
        busy = false;
      }
    } catch (error) {
      event?.target?.postMessage(Object.freeze({
        format: RESPONSE_FORMAT,
        id,
        status: "error",
        error: protocolTrap(error)
      }));
    }
  };
}

export function installKotobaWorker(scope, options) {
  if (!scope || typeof scope.addEventListener !== "function" || typeof scope.postMessage !== "function")
    fail("invalid-worker-scope");
  const handler = createKotobaWorkerHandler(options);
  scope.addEventListener("message", event => handler({ data: event.data, target: scope }));
  return Object.freeze({ format: "kotoba.worker-host/v1" });
}

export const workerProfile = Object.freeze({
  format: "kotoba.worker-host/v1",
  requestFormat: REQUEST_FORMAT,
  responseFormat: RESPONSE_FORMAT,
  operation: "run",
  maxArguments: MAX_ARGS
});
