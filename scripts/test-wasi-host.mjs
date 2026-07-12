import fs from "node:fs";
import { instantiateKotobaWasi, wasiProfile } from "../runtime/wasi-host.mjs";

const [programPath, capabilityPath] = process.argv.slice(2);
const target = Buffer.from("wasm32-wasi-kotoba-v1", "utf8");

const program = fs.readFileSync(programPath);
const hosted = await instantiateKotobaWasi(program);
if (hosted.instance.exports.main() !== 42n)
  throw new Error("WASI pure result mismatch");
if (wasiProfile.ambientWasi !== false || wasiProfile.target !== target.toString())
  throw new Error("WASI host profile contract changed");

const capability = fs.readFileSync(capabilityPath);
const allowed = await instantiateKotobaWasi(capability, {
  allowCapabilities: [7],
  capCall(id, value) {
    if (id !== 7) throw new Error("unexpected capability id");
    return value + 1n;
  }
});
if (allowed.instance.exports.main() !== 42n)
  throw new Error("WASI capability adapter mismatch");
let denied = false;
try { (await instantiateKotobaWasi(capability)).instance.exports.main(); }
catch (error) { denied = error?.code === "capability-denied"; }
if (!denied) throw new Error("WASI capability denial was bypassed");

const substituted = Buffer.from(program);
const targetOffset = substituted.indexOf(target);
if (targetOffset < 0) throw new Error("sealed WASI target section is absent");
substituted[targetOffset] ^= 1;
let targetRejected = false;
try { await instantiateKotobaWasi(substituted); }
catch (error) { targetRejected = error?.code === "target-mismatch"; }
if (!targetRejected) throw new Error("WASI target substitution was accepted");

const uleb = value => {
  const out = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value) byte |= 0x80;
    out.push(byte);
  } while (value);
  return out;
};
const utf8 = value => Array.from(Buffer.from(value, "utf8"));
const name = value => [...uleb(utf8(value).length), ...utf8(value)];
const section = (id, bytes) => [id, ...uleb(bytes.length), ...bytes];
const custom = [...name("kotoba.target"), ...utf8(wasiProfile.target)];
const type = [1, 0x60, 0, 0];
const imp = [1, ...name("wasi_snapshot_preview1"), ...name("fd_write"), 0, 0];
const ambient = Uint8Array.from([
  0, 0x61, 0x73, 0x6d, 1, 0, 0, 0,
  ...section(0, custom), ...section(1, type), ...section(2, imp)
]);
let ambientRejected = false;
try { await instantiateKotobaWasi(ambient); }
catch (error) { ambientRejected = error?.code === "forbidden-import"; }
if (!ambientRejected) throw new Error("ambient WASI import was accepted");

console.log("wasi-host: sealed target, pure execution, capability policy, and ambient denial passed");
