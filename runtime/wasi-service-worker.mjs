import { parentPort, workerData } from "node:worker_threads";
import { instantiateKotobaWasi } from "./wasi-host.mjs";

try {
  const bytes = new Uint8Array(workerData.bytes);
  const hosted = await instantiateKotobaWasi(bytes, { expectedSha256: workerData.sha256 });
  const fn = hosted.instance.exports[workerData.entry];
  if (typeof fn !== "function") throw new Error("entry is not exported");
  const result = fn(...workerData.args);
  if (typeof result !== "bigint") throw new Error("entry result is not i64");
  parentPort.postMessage({ ok: true, result: result.toString(), heap: hosted.report().heap });
} catch {
  parentPort.postMessage({ ok: false });
}
