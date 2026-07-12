import fs from "node:fs";
import {
  browserProfile,
  instantiateKotoba,
  normalizeKotobaTrap
} from "../runtime/browser-host.mjs";

const [programPath, fuelPath, i64Path, capabilityPath, heapPath, listPath] = process.argv.slice(2);
const instantiate = async (file, options) =>
  (await instantiateKotoba(fs.readFileSync(file), options)).instance;

{
  const instance = await instantiate(programPath);
  const e = instance.exports;
  if (e.main() !== 42n || e.score(-7n, 2n) !== 12n || e.calc(20n, 4n) !== 21n)
    throw new Error("structured Wasm result mismatch");
  if (e.relations(7n, 3n) !== 10n || e.relations(3n, 3n) !== 13n)
    throw new Error("Wasm relation mismatch");
  for (const args of [[20n, 0n], [-9223372036854775808n, -1n]]) {
    let trapped = false;
    try { e.calc(...args); } catch (error) { trapped = error instanceof WebAssembly.RuntimeError; }
    if (!trapped) throw new Error("invalid Wasm division did not trap");
  }
}

{
  const instance = await instantiate(fuelPath);
  if (instance.exports.fact(10n) !== 3628800n) throw new Error("finite recursion mismatch");
  let trapped = false;
  try { instance.exports.forever(0n); } catch (error) { trapped = error instanceof WebAssembly.RuntimeError; }
  if (!trapped) throw new Error("fuel-exhausted recursion did not trap");
}

{
  const { exports: e } = await instantiate(i64Path);
  const vectors = [
    ["add", [9223372036854775807n, 1n], -9223372036854775808n],
    ["subtract", [-9223372036854775808n, 1n], 9223372036854775807n],
    ["multiply", [9223372036854775807n, 2n], -2n],
    ["negate", [-9223372036854775808n], -9223372036854775808n],
    ["choose", [0n, 11n, 22n], 22n],
    ["choose", [-1n, 11n, 22n], 11n]
  ];
  for (const [name, args, expected] of vectors)
    if (e[name](...args) !== expected) throw new Error(`i64 vector failed: ${name}`);
}

{
  const bytes = fs.readFileSync(capabilityPath);
  let result = await instantiateKotoba(bytes, {
    allowCapabilities: [7],
    capCall(id, value) {
      if (id !== 7) throw new Error("unexpected capability id");
      return value + 1n;
    }
  });
  if (result.instance.exports.helper(41n) !== 42n) throw new Error("capability result mismatch");
  result = await instantiateKotoba(bytes, { allowCapabilities: [], capCall: () => 42n });
  let denied;
  try { result.instance.exports.helper(41n); } catch (error) { denied = normalizeKotobaTrap(error); }
  if (denied?.code !== "capability-denied") throw new Error("runtime capability denial was bypassed");
}

{
  const hosted = await instantiateKotoba(fs.readFileSync(heapPath));
  if (hosted.instance.exports.main() !== 42n || hosted.report().heap.used !== 2)
    throw new Error("bounded pair arena mismatch");
  let forged;
  try { hosted.instance.exports.forged(4096n); } catch (error) { forged = normalizeKotobaTrap(error); }
  if (forged?.code !== "invalid-pair-handle") throw new Error("forged pair handle was accepted");
}

{
  const hosted = await instantiateKotoba(fs.readFileSync(listPath));
  if (hosted.instance.exports.main() !== 42n || hosted.report().heap.used !== 2)
    throw new Error("persistent list mismatch");
}

{
  const bytes = fs.readFileSync(programPath);
  const hosted = await instantiateKotoba(bytes);
  await instantiateKotoba(bytes, { expectedSha256: hosted.sha256 });
  let mismatch;
  try { await instantiateKotoba(bytes, { expectedSha256: "0".repeat(64) }); }
  catch (error) { mismatch = normalizeKotobaTrap(error); }
  if (mismatch?.code !== "digest-mismatch") throw new Error("module digest mismatch was accepted");
  const forbiddenImport = Uint8Array.from([
    0,97,115,109,1,0,0,0,
    1,4,1,96,0,0,
    2,13,1,4,101,118,105,108,4,99,97,108,108,0,0
  ]);
  let forbidden;
  try { await instantiateKotoba(forbiddenImport); }
  catch (error) { forbidden = normalizeKotobaTrap(error); }
  if (forbidden?.code !== "forbidden-import") throw new Error("forbidden Wasm import was accepted");
  if (browserProfile.pairCapacity !== 4096 || browserProfile.maxModuleBytes !== 1048576)
    throw new Error("browser host profile limits changed");
}

console.log("conformance: Wasm runtime, fuel, i64, capability, pair, and list vectors passed");
