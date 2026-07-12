import { instantiateKotoba, normalizeKotobaTrap } from "/runtime/browser-host.mjs";

const status = document.querySelector("#status");
const reportNode = document.querySelector("#report");
let stage = "initialize";
const bytes = async name => new Uint8Array(await (await fetch(`/artifacts/${name}.wasm`)).arrayBuffer());
const digest = async value => Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", value)))
  .map(byte => byte.toString(16).padStart(2, "0")).join("");
const runWorker = (url, request) => new Promise((resolve, reject) => {
  const worker = new Worker(url, { type: "module", name: "kotoba-conformance" });
  const timeout = setTimeout(() => { worker.terminate(); reject(new Error("worker timeout")); }, 10000);
  worker.onmessage = event => { clearTimeout(timeout); worker.terminate(); resolve(event.data); };
  worker.onerror = event => { clearTimeout(timeout); worker.terminate(); reject(new Error(event.message)); };
  worker.postMessage(request);
});

try {
  stage = "direct-host";
  const program = await bytes("program");
  const programDigest = await digest(program);
  const direct = await instantiateKotoba(program, { expectedSha256: programDigest });
  if (direct.instance.exports.main() !== 42n) throw new Error("direct result mismatch");

  stage = "pure-worker";
  const worker = await runWorker("/runtime/worker-entry.mjs", {
    format: "kotoba.worker-request/v1", id: "pure", op: "run",
    wasm: program, expectedSha256: programDigest, allowCapabilities: [], args: []
  });
  if (worker.status !== "ok" || worker.result !== 42n) throw new Error("worker result mismatch");

  stage = "capability-allow";
  const capability = await bytes("capability");
  const capWorker = await runWorker("/tests/browser/worker-capability.mjs", {
    format: "kotoba.worker-request/v1", id: "cap", op: "run",
    wasm: capability, expectedSha256: await digest(capability), allowCapabilities: [7], args: []
  });
  if (capWorker.status !== "ok" || capWorker.result !== 42n) throw new Error("capability mismatch");

  stage = "capability-deny";
  const deniedWorker = await runWorker("/tests/browser/worker-capability.mjs", {
    format: "kotoba.worker-request/v1", id: "denied", op: "run",
    wasm: capability, expectedSha256: await digest(capability), allowCapabilities: [], args: []
  });
  if (deniedWorker.error?.code !== "capability-denied") throw new Error("capability denial mismatch");

  stage = "bounded-heap";
  const heap = await instantiateKotoba(await bytes("heap"));
  if (heap.instance.exports.main() !== 42n || heap.report().heap.used !== 2)
    throw new Error("heap result mismatch");
  stage = "forged-handle";
  let forged;
  try { heap.instance.exports.forged(4096n); } catch (error) { forged = normalizeKotobaTrap(error); }
  if (forged?.code !== "invalid-pair-handle") throw new Error("forged handle accepted");

  const report = { format: "kotoba.browser-conformance/v1", direct: "ok", worker: "ok",
    capability: "allowed-and-denied", heap: heap.report(), forged: forged.code };
  reportNode.textContent = JSON.stringify(report);
  status.textContent = "passed";
  status.dataset.result = "passed";
} catch (error) {
  reportNode.textContent = JSON.stringify({ format: "kotoba.browser-conformance/v1", error: "failed", stage });
  status.textContent = "failed";
  status.dataset.result = "failed";
}
