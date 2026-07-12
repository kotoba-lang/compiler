import assert from "node:assert/strict";
import {
  createKotobaWorkerHandler,
  installKotobaWorker,
  workerProfile
} from "../runtime/worker-host.mjs";

const main42 = Uint8Array.from([
  0,97,115,109,1,0,0,0,
  1,5,1,96,0,1,126,
  3,2,1,0,
  7,8,1,4,109,97,105,110,0,0,
  10,6,1,4,0,66,42,11
]);
const messages = [];
const target = { postMessage(message) { messages.push(message); } };
const request = (id, extra = {}) => ({
  format: "kotoba.worker-request/v1",
  id,
  op: "run",
  wasm: main42,
  ...extra
});
const handler = createKotobaWorkerHandler();

await handler({ target, data: request("ok") });
assert.equal(messages.pop().result, 42n);

await handler({ target, data: request("unknown", { ambientNetwork: true }) });
assert.deepEqual(messages.pop().error, { kind: "worker-protocol", code: "invalid-request" });

await handler({ target, data: request("args", { args: [1] }) });
assert.deepEqual(messages.pop().error, { kind: "worker-protocol", code: "invalid-arguments" });

await handler({ target, data: request("digest", { expectedSha256: "0".repeat(64) }) });
assert.deepEqual(messages.pop().error, { kind: "kotoba-host", code: "digest-mismatch" });

await Promise.all([
  handler({ target, data: request("first") }),
  handler({ target, data: request("second") })
]);
assert.equal(messages.length, 2);
assert.equal(messages.filter(message => message.status === "ok").length, 1);
assert.equal(messages.find(message => message.status === "error").error.code, "worker-busy");
messages.length = 0;

const listeners = [];
const scope = {
  addEventListener(type, callback) { listeners.push([type, callback]); },
  postMessage(message) { messages.push(message); }
};
assert.equal(installKotobaWorker(scope).format, "kotoba.worker-host/v1");
assert.equal(listeners.length, 1);
listeners[0][1]({ data: request("installed") });
while (messages.length === 0) await new Promise(resolve => setTimeout(resolve, 0));
assert.equal(messages.pop().status, "ok");

assert.equal(workerProfile.maxArguments, 5);
assert.ok(Object.isFrozen(workerProfile));
console.log("worker-host: closed protocol, lifecycle, digest, and denial vectors passed");
