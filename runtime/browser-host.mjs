const MAX_MODULE_BYTES = 1024 * 1024;
const PAIR_CAPACITY = 4096;
const TYPED_SECTION = "kotoba.typed";
const TYPED_ABI_VERSION = 1;
const MAX_TYPED_DESCRIPTORS = 64;
const ALLOWED_IMPORTS = new Set([
  "kotoba:cap/call/function",
  "kotoba:heap/pair/function",
  "kotoba:heap/pair-first/function",
  "kotoba:heap/pair-second/function"
]);

export class KotobaHostError extends Error {
  constructor(code, message, cause) {
    super(message, cause === undefined ? undefined : { cause });
    this.name = "KotobaHostError";
    this.code = code;
  }
}

function reject(code, message, cause) {
  throw new KotobaHostError(code, message, cause);
}

function exactOptions(options) {
  if (options === undefined) return {};
  if (options === null || typeof options !== "object" || Array.isArray(options))
    reject("invalid-options", "browser host options must be an object");
  const allowed = new Set(["allowCapabilities", "capCall", "expectedSha256"]);
  for (const key of Object.keys(options))
    if (!allowed.has(key)) reject("invalid-options", `unknown browser host option: ${key}`);
  return options;
}

function admittedCapabilities(value = []) {
  if (!Array.isArray(value) || value.length > 256)
    reject("invalid-policy", "allowCapabilities must be a bounded array");
  const result = new Set();
  for (const id of value) {
    if (!Number.isInteger(id) || id < 0 || id > 255 || result.has(id))
      reject("invalid-policy", "capability ids must be unique integers in [0,255]");
    result.add(id);
  }
  return result;
}

function copiedBytes(source) {
  let view;
  if (source instanceof ArrayBuffer) view = new Uint8Array(source);
  else if (ArrayBuffer.isView(source)) view = new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
  else reject("invalid-module", "Wasm source must be an ArrayBuffer or typed-array view");
  if (view.byteLength < 8 || view.byteLength > MAX_MODULE_BYTES)
    reject("invalid-module", "Wasm source is outside the admitted byte limit");
  return new Uint8Array(view);
}

async function sha256(bytes) {
  if (!globalThis.crypto?.subtle) reject("crypto-unavailable", "Web Crypto SHA-256 is required");
  const digest = new Uint8Array(await globalThis.crypto.subtle.digest("SHA-256", bytes));
  return Array.from(digest, byte => byte.toString(16).padStart(2, "0")).join("");
}

function validateDigest(value) {
  if (value !== undefined && (typeof value !== "string" || !/^[0-9a-f]{64}$/.test(value)))
    reject("invalid-digest", "expectedSha256 must be a lowercase SHA-256 digest");
}

function parseTypedMetadata(module) {
  const sections = WebAssembly.Module.customSections(module, TYPED_SECTION);
  if (sections.length === 0) return null;
  if (sections.length !== 1) reject("invalid-typed-metadata", "typed ABI section must be unique");
  const bytes = new Uint8Array(sections[0]);
  let offset = 0;
  let nodes = 0;
  const byte = () => {
    if (offset >= bytes.length) reject("invalid-typed-metadata", "truncated typed ABI section");
    return bytes[offset++];
  };
  const uleb = () => {
    let value = 0;
    let shift = 0;
    for (let index = 0; index < 5; index += 1) {
      const current = byte();
      value += (current & 0x7f) * (2 ** shift);
      if ((current & 0x80) === 0) return value;
      shift += 7;
    }
    reject("invalid-typed-metadata", "oversized typed ABI integer");
  };
  const text = () => {
    const length = uleb();
    if (length > 4096 || offset + length > bytes.length)
      reject("invalid-typed-metadata", "invalid typed ABI text length");
    let decoded;
    try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(offset, offset + length)); }
    catch (error) { reject("invalid-typed-metadata", "typed ABI text is not UTF-8", error); }
    offset += length;
    if (decoded.length === 0) reject("invalid-typed-metadata", "typed ABI names must be non-empty");
    return decoded;
  };
  const descriptor = depth => {
    nodes += 1;
    if (depth > 8 || nodes > 64)
      reject("invalid-typed-metadata", "typed ABI descriptor budget exceeded");
    const tag = byte();
    if (tag <= 3) return Object.freeze(["i64", "string", "keyword", "bool"][tag]);
    if (tag === 4) return Object.freeze(["option", descriptor(depth + 1)]);
    if (tag === 5) return Object.freeze(["result", descriptor(depth + 1), descriptor(depth + 1)]);
    if (tag === 7) {
      const count = uleb();
      if (count > 32) reject("invalid-typed-metadata", "typed vector descriptor is oversized");
      return Object.freeze(["vector", Object.freeze(Array.from({ length: count }, () => descriptor(depth + 1)))]);
    }
    if (tag === 8) return Object.freeze(["set", descriptor(depth + 1)]);
    if (tag === 6 || tag === 9) {
      const name = text();
      const count = uleb();
      if (count < 1 || count > 32)
        reject("invalid-typed-metadata", "named typed descriptor member count is invalid");
      const seen = new Set();
      const members = [];
      for (let index = 0; index < count; index += 1) {
        const member = text();
        if (seen.has(member)) reject("invalid-typed-metadata", "typed descriptor members must be unique");
        seen.add(member);
        members.push(Object.freeze([member, descriptor(depth + 1)]));
      }
      return Object.freeze([tag === 6 ? "variant" : "record", name, Object.freeze(members)]);
    }
    reject("invalid-typed-metadata", "unknown typed ABI descriptor tag");
  };
  if (byte() !== TYPED_ABI_VERSION)
    reject("unsupported-typed-abi", "unsupported Wasm typed ABI version");
  const count = uleb();
  if (count > MAX_TYPED_DESCRIPTORS)
    reject("invalid-typed-metadata", "too many typed ABI descriptors");
  const descriptors = Array.from({ length: count }, () => descriptor(1));
  const literalCount = uleb();
  if (literalCount > 256) reject("invalid-typed-metadata", "too many typed ABI literals");
  const literals = [];
  for (let index = 0; index < literalCount; index += 1) {
    const tag = byte();
    if (tag === 0) literals.push(Object.freeze(["string", text()]));
    else if (tag === 1) literals.push(Object.freeze(["keyword", text()]));
    else if (tag === 2 || tag === 3) literals.push(Object.freeze(["bool", tag === 3]));
    else reject("invalid-typed-metadata", "unknown typed ABI literal tag");
  }
  if (offset !== bytes.length) reject("invalid-typed-metadata", "trailing typed ABI metadata rejected");
  return Object.freeze({
    version: TYPED_ABI_VERSION,
    descriptors: Object.freeze(descriptors),
    literals: Object.freeze(literals)
  });
}

function validateModule(module) {
  for (const entry of WebAssembly.Module.imports(module)) {
    const identity = `${entry.module}/${entry.name}/${entry.kind}`;
    if (!ALLOWED_IMPORTS.has(identity))
      reject("forbidden-import", `Wasm import is outside the Kotoba browser profile: ${identity}`);
  }
  const exports = WebAssembly.Module.exports(module);
  if (!exports.some(entry => entry.name === "main" && entry.kind === "function"))
    reject("invalid-module", "Kotoba browser module must export a main function");
  if (exports.some(entry => entry.kind === "memory" || entry.kind === "table" || entry.kind === "global"))
    reject("forbidden-export", "Kotoba browser module may export functions only");
  return parseTypedMetadata(module);
}

function createHeap() {
  const cells = [];
  const checked = handle => {
    if (typeof handle !== "bigint" || handle <= 0n || handle > BigInt(cells.length))
      reject("invalid-pair-handle", "invalid immutable pair handle");
    return cells[Number(handle - 1n)];
  };
  return {
    imports: Object.freeze({
      pair(first, second) {
        if (cells.length >= PAIR_CAPACITY) reject("heap-exhausted", "immutable pair arena exhausted");
        cells.push(Object.freeze([BigInt.asIntN(64, first), BigInt.asIntN(64, second)]));
        return BigInt(cells.length);
      },
      "pair-first"(handle) { return checked(handle)[0]; },
      "pair-second"(handle) { return checked(handle)[1]; }
    }),
    report() { return Object.freeze({ capacity: PAIR_CAPACITY, used: cells.length }); }
  };
}

export function normalizeKotobaTrap(error) {
  if (error instanceof KotobaHostError)
    return Object.freeze({ kind: "kotoba-host", code: error.code });
  if (error instanceof WebAssembly.RuntimeError)
    return Object.freeze({ kind: "wasm-runtime", code: "guest-trap" });
  return Object.freeze({ kind: "host", code: "unexpected-host-error" });
}

export async function instantiateKotoba(source, rawOptions) {
  const options = exactOptions(rawOptions);
  const allow = admittedCapabilities(options.allowCapabilities);
  validateDigest(options.expectedSha256);
  if (options.capCall !== undefined && typeof options.capCall !== "function")
    reject("invalid-policy", "capCall must be a function");
  const bytes = copiedBytes(source);
  const digest = await sha256(bytes);
  if (options.expectedSha256 !== undefined && options.expectedSha256 !== digest)
    reject("digest-mismatch", "Wasm module SHA-256 does not match expectedSha256");
  let module;
  try { module = await WebAssembly.compile(bytes); }
  catch (error) { reject("invalid-module", "Wasm compilation failed", error); }
  const typedAbi = validateModule(module);
  const heap = createHeap();
  const cap = Object.freeze({
    call(id, value) {
      if (typeof id !== "bigint" || id < 0n || id > 255n || !allow.has(Number(id)))
        reject("capability-denied", "runtime capability policy denied the call");
      if (typeof options.capCall !== "function")
        reject("capability-unimplemented", "no host implementation exists for the capability");
      const result = options.capCall(Number(id), BigInt.asIntN(64, value));
      if (typeof result !== "bigint")
        reject("invalid-capability-result", "capability implementation must return bigint");
      return BigInt.asIntN(64, result);
    }
  });
  let instance;
  try {
    instance = await WebAssembly.instantiate(module, {
      "kotoba:cap": cap,
      "kotoba:heap": heap.imports
    });
  } catch (error) {
    if (error instanceof KotobaHostError) throw error;
    reject("instantiation-failed", "Kotoba Wasm instantiation failed", error);
  }
  return Object.freeze({
    module,
    instance,
    sha256: digest,
    typedAbi,
    report: () => Object.freeze({ heap: heap.report() })
  });
}

export const browserProfile = Object.freeze({
  format: "kotoba.browser-host/v1",
  maxModuleBytes: MAX_MODULE_BYTES,
  pairCapacity: PAIR_CAPACITY,
  typedAbiVersion: TYPED_ABI_VERSION,
  imports: Object.freeze(Array.from(ALLOWED_IMPORTS).sort())
});
