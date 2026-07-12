import fs from "node:fs";

const [programPath, fuelPath, i64Path, capabilityPath, heapPath, listPath] = process.argv.slice(2);
const instantiate = (file, imports = {}) => WebAssembly.instantiate(fs.readFileSync(file), imports);

{
  const { instance } = await instantiate(programPath);
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
  const { instance } = await instantiate(fuelPath);
  if (instance.exports.fact(10n) !== 3628800n) throw new Error("finite recursion mismatch");
  let trapped = false;
  try { instance.exports.forever(0n); } catch (error) { trapped = error instanceof WebAssembly.RuntimeError; }
  if (!trapped) throw new Error("fuel-exhausted recursion did not trap");
}

{
  const { instance: { exports: e } } = await instantiate(i64Path);
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
  let result = await WebAssembly.instantiate(bytes, { "kotoba:cap": { call(cap, value) {
    if (cap !== 7n) throw new Error("capability denied");
    return value + 1n;
  } } });
  if (result.instance.exports.helper(41n) !== 42n) throw new Error("capability result mismatch");
  result = await WebAssembly.instantiate(bytes, { "kotoba:cap": { call() {
    throw new Error("runtime capability denied");
  } } });
  let denied = false;
  try { result.instance.exports.helper(41n); } catch (error) { denied = /runtime capability denied/.test(error.message); }
  if (!denied) throw new Error("runtime capability denial was bypassed");
}

function boundedHeap() {
  const cells = [];
  const checked = handle => {
    if (typeof handle !== "bigint" || handle <= 0n || handle > BigInt(cells.length))
      throw new WebAssembly.RuntimeError("invalid pair handle");
    return cells[Number(handle - 1n)];
  };
  return { cells, imports: { "kotoba:heap": {
    pair(first, second) {
      if (cells.length >= 4096) throw new WebAssembly.RuntimeError("heap exhausted");
      cells.push([first, second]);
      return BigInt(cells.length);
    },
    "pair-first": handle => checked(handle)[0],
    "pair-second": handle => checked(handle)[1]
  } } };
}

{
  const heap = boundedHeap();
  const { instance } = await instantiate(heapPath, heap.imports);
  if (instance.exports.main() !== 42n || heap.cells.length !== 2)
    throw new Error("bounded pair arena mismatch");
  let forged = false;
  try { instance.exports.forged(4096n); } catch (error) { forged = error instanceof WebAssembly.RuntimeError; }
  if (!forged) throw new Error("forged pair handle was accepted");
}

{
  const heap = boundedHeap();
  const { instance } = await instantiate(listPath, heap.imports);
  if (instance.exports.main() !== 42n || heap.cells.length !== 2)
    throw new Error("persistent list mismatch");
}

console.log("conformance: Wasm runtime, fuel, i64, capability, pair, and list vectors passed");
