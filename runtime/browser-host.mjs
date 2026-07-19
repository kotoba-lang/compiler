const MAX_MODULE_BYTES = 1024 * 1024;
const PAIR_CAPACITY = 4096;
const TYPED_SECTION = "kotoba.typed";
const TYPED_ABI_VERSION = 1;
const COMPATIBILITY_SECTION = "kotoba.compatibility";
const COMPATIBILITY_VERSION = 1;
const MAX_TYPED_DESCRIPTORS = 64;
const ALLOWED_IMPORTS = new Set([
  "kotoba:cap/call/function",
  "kotoba:heap/pair/function",
  "kotoba:heap/pair-first/function",
  "kotoba:heap/pair-second/function",
  "kotoba:typed/literal/function",
  "kotoba:typed/new/function",
  "kotoba:typed/push-i64/function",
  "kotoba:typed/push-ref/function",
  "kotoba:typed/seal/function",
  "kotoba:typed/assert-ref/function",
  "kotoba:typed/tag/function",
  "kotoba:typed/get-i64/function",
  "kotoba:typed/get-ref/function",
  "kotoba:typed/count/function",
  "kotoba:typed/bool/function",
  "kotoba:typed/equal/function",
  "kotoba:typed/assoc-i64/function",
  "kotoba:typed/assoc-ref/function",
  "kotoba:typed/set-op-i64/function",
  "kotoba:typed/set-op-ref/function",
  "kotoba:typed/set-contains-i64/function",
  "kotoba:typed/set-contains-ref/function",
  "kotoba:typed/map-contains-i64/function",
  "kotoba:typed/map-contains-ref/function",
  "kotoba:typed/map-get-i64/function",
  "kotoba:typed/map-get-ref/function",
  "kotoba:typed/map-entry-at/function",
  "kotoba:typed/map-assoc-ii/function",
  "kotoba:typed/map-assoc-ir/function",
  "kotoba:typed/map-assoc-ri/function",
  "kotoba:typed/map-assoc-rr/function",
  "kotoba:typed/map-dissoc-i64/function",
  "kotoba:typed/map-dissoc-ref/function"
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
    if (tag === 10) return Object.freeze(["map", descriptor(depth + 1), descriptor(depth + 1)]);
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
  const descriptors = Array.from({ length: count }, () => {
    nodes = 0;
    return descriptor(1);
  });
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

function parseCompatibility(module) {
  const sections = WebAssembly.Module.customSections(module, COMPATIBILITY_SECTION);
  if (sections.length === 0)
    reject("missing-compatibility", "Kotoba compatibility metadata is required");
  if (sections.length !== 1)
    reject("invalid-compatibility", "compatibility section must be unique");
  const bytes = new Uint8Array(sections[0]);
  let offset = 0;
  const byte = () => {
    if (offset >= bytes.length) reject("invalid-compatibility", "truncated compatibility section");
    return bytes[offset++];
  };
  const uleb = () => {
    let value = 0;
    for (let shift = 0; shift <= 28; shift += 7) {
      const part = byte();
      value |= (part & 0x7f) << shift;
      if ((part & 0x80) === 0) return value;
    }
    reject("invalid-compatibility", "oversized compatibility length");
  };
  const text = () => {
    const length = uleb();
    if (length === 0 || length > 256 || offset + length > bytes.length)
      reject("invalid-compatibility", "invalid compatibility text length");
    let value;
    try { value = new TextDecoder("utf-8", { fatal: true }).decode(bytes.slice(offset, offset + length)); }
    catch (error) { reject("invalid-compatibility", "compatibility text is not UTF-8", error); }
    offset += length;
    return value;
  };
  if (byte() !== COMPATIBILITY_VERSION)
    reject("unsupported-compatibility", "unsupported compatibility version");
  const names = ["compiler", "language", "kir", "target", "runtime", "valueAbi", "tenderRole", "tenderContract"];
  const result = Object.fromEntries(names.map(name => [name, text()]));
  if (offset !== bytes.length)
    reject("invalid-compatibility", "trailing compatibility metadata rejected");
  if (result.compiler !== "kotoba-compiler/1" || result.language !== "kotoba.language/safe-v1" ||
      result.tenderRole !== "kototama/component-tender-v1" ||
      result.tenderContract !== "kotoba.capability-host/v1")
    reject("compatibility-mismatch", "compiler, language, or tender contract is unsupported");
  if (!["kotoba-capability-host-v1", "kotoba-browser-host-v1", "kotoba-wasi-host-v1"].includes(result.runtime))
    reject("compatibility-mismatch", "runtime compatibility identity is unsupported");
  return Object.freeze({ version: COMPATIBILITY_VERSION, ...result });
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
  return Object.freeze({ typedAbi: parseTypedMetadata(module), compatibility: parseCompatibility(module) });
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

function createTypedRuntime(abi) {
  if (abi === null) return null;
  const builders = new WeakSet();
  const trustedDescriptors = new WeakSet();
  const trustDescriptor = descriptor => {
    if (!Array.isArray(descriptor) || trustedDescriptors.has(descriptor)) return;
    trustedDescriptors.add(descriptor);
    for (const item of descriptor) {
      if (Array.isArray(item) && item.length === 2 && typeof item[0] === "string")
        trustDescriptor(item[1]);
      else trustDescriptor(item);
    }
  };
  abi.descriptors.forEach(trustDescriptor);
  const descriptorAt = id => {
    if (!Number.isInteger(id) || id < 0 || id >= abi.descriptors.length)
      reject("invalid-typed-descriptor", "typed descriptor index is out of range");
    return abi.descriptors[id];
  };
  const literalAt = id => {
    if (!Number.isInteger(id) || id < 0 || id >= abi.literals.length)
      reject("invalid-typed-literal", "typed literal index is out of range");
    return abi.literals[id][1];
  };
  const i64 = value => {
    if (typeof value !== "bigint" || BigInt.asIntN(64, value) !== value)
      reject("invalid-typed-value", "typed i64 value is invalid");
    return value;
  };
  const utf8Length = value => {
    if (typeof value !== "string") reject("invalid-typed-value", "typed string is invalid");
    for (let index = 0; index < value.length; index += 1) {
      const unit = value.charCodeAt(index);
      if (unit >= 0xd800 && unit <= 0xdbff) {
        const next = value.charCodeAt(index + 1);
        if (!(next >= 0xdc00 && next <= 0xdfff))
          reject("invalid-typed-value", "typed string has an unpaired high surrogate");
        index += 1;
      } else if (unit >= 0xdc00 && unit <= 0xdfff) {
        reject("invalid-typed-value", "typed string has an unpaired low surrogate");
      }
    }
    return new TextEncoder().encode(value).byteLength;
  };
  const compareSequence = (types, left, right) => {
    const length = Math.min(left.length, right.length);
    for (let index = 0; index < length; index += 1) {
      const compared = compareValue(types[index], left[index], right[index]);
      if (compared !== 0) return compared;
    }
    return left.length < right.length ? -1 : left.length > right.length ? 1 : 0;
  };
  const compareValue = (descriptor, left, right) => {
    if (descriptor === "i64" || descriptor === "string" || descriptor === "keyword")
      return left < right ? -1 : left > right ? 1 : 0;
    if (descriptor === "bool") return left === right ? 0 : left ? 1 : -1;
    const kind = descriptor[0];
    if (kind === "option") {
      if (left[1] !== right[1]) return left[1] ? 1 : -1;
      return left[1] ? compareValue(descriptor[1], left[2], right[2]) : 0;
    }
    if (kind === "result") {
      if (left[0] !== right[0]) return left[0] ? 1 : -1;
      return compareValue(left[0] ? descriptor[1] : descriptor[2], left[1], right[1]);
    }
    if (kind === "variant") {
      const leftIndex = descriptor[2].findIndex(([name]) => name === left[1]);
      const rightIndex = descriptor[2].findIndex(([name]) => name === right[1]);
      return leftIndex === rightIndex
        ? compareValue(descriptor[2][leftIndex][1], left[2], right[2])
        : leftIndex < rightIndex ? -1 : 1;
    }
    if (kind === "vector") return compareSequence(descriptor[1], left.slice(1), right.slice(1));
    if (kind === "set") {
      const length = Math.max(left[1].length, right[1].length);
      return compareSequence(Array.from({ length }, () => descriptor[1]), left[1], right[1]);
    }
    if (kind === "map") {
      const length = Math.min(left[1].length, right[1].length);
      for (let index = 0; index < length; index += 1) {
        let compared = compareValue(descriptor[1], left[1][index][0], right[1][index][0]);
        if (compared !== 0) return compared;
        compared = compareValue(descriptor[2], left[1][index][1], right[1][index][1]);
        if (compared !== 0) return compared;
      }
      return left[1].length < right[1].length ? -1 : left[1].length > right[1].length ? 1 : 0;
    }
    if (kind === "record")
      return compareSequence(descriptor[2].map(([, type]) => type), left.slice(1), right.slice(1));
    reject("invalid-typed-value", "typed value has no canonical order");
  };
  const sameDescriptor = (left, right) => {
    if (left === right) return true;
    if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length)
      return false;
    for (let index = 0; index < left.length; index += 1)
      if (!sameDescriptor(left[index], right[index])) return false;
    return true;
  };
  const sameTrustedDescriptor = (left, right) =>
    left === right || (Array.isArray(left) && trustedDescriptors.has(left) &&
                       sameDescriptor(left, right));
  const assertValue = (descriptor, value, state = { depth: 0, nodes: 0 }) => {
    state.nodes += 1;
    state.depth += 1;
    if (state.depth > 8 || state.nodes > 64)
      reject("invalid-typed-value", "typed runtime value budget exceeded");
    try {
      if (descriptor === "i64") return i64(value);
      if (descriptor === "string") {
        if (utf8Length(value) > 65536) reject("invalid-typed-value", "typed string is oversized");
        return value;
      }
      if (descriptor === "keyword") {
        if (typeof value !== "string" || !value.startsWith(":") || utf8Length(value) > 512)
          reject("invalid-typed-value", "typed keyword is invalid");
        return value;
      }
      if (descriptor === "bool") {
        if (typeof value !== "boolean") reject("invalid-typed-value", "typed boolean is invalid");
        return value;
      }
      const kind = descriptor[0];
      if (!Array.isArray(value) || !Object.isFrozen(value))
        reject("invalid-typed-value", "compound typed value must be a frozen array");
      if (kind === "option") {
        if (!sameTrustedDescriptor(value[0], descriptor) || typeof value[1] !== "boolean" ||
            value.length !== (value[1] ? 3 : 2))
          reject("invalid-typed-value", "generic option shape or descriptor is invalid");
        if (value[1]) assertValue(descriptor[1], value[2], state);
      } else if (kind === "result") {
        if (typeof value[0] !== "boolean" || value.length !== 2)
          reject("invalid-typed-value", "parametric result shape is invalid");
        assertValue(value[0] ? descriptor[1] : descriptor[2], value[1], state);
      } else if (kind === "variant") {
        const member = descriptor[2].find(([name]) => name === value[1]);
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== 3 || member === undefined)
          reject("invalid-typed-value", "variant shape, descriptor, or tag is invalid");
        assertValue(member[1], value[2], state);
      } else if (kind === "vector") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== descriptor[1].length + 1)
          reject("invalid-typed-value", "heterogeneous vector shape is invalid");
        descriptor[1].forEach((item, index) => assertValue(item, value[index + 1], state));
      } else if (kind === "set") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== 2 || !Array.isArray(value[1]) ||
            !Object.isFrozen(value[1]) || value[1].length > 32)
          reject("invalid-typed-value", "typed set shape is invalid");
        value[1].forEach(item => assertValue(descriptor[1], item, state));
        for (let index = 1; index < value[1].length; index += 1)
          if (compareValue(descriptor[1], value[1][index - 1], value[1][index]) >= 0)
            reject("invalid-typed-value", "typed set is duplicated or non-canonical");
      } else if (kind === "map") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== 2 ||
            !Array.isArray(value[1]) || !Object.isFrozen(value[1]) || value[1].length > 31)
          reject("invalid-typed-value", "typed map shape is invalid");
        value[1].forEach(entry => {
          if (!Array.isArray(entry) || !Object.isFrozen(entry) || entry.length !== 2)
            reject("invalid-typed-value", "typed map entry shape is invalid");
          assertValue(descriptor[1], entry[0], state);
          assertValue(descriptor[2], entry[1], state);
        });
        for (let index = 1; index < value[1].length; index += 1)
          if (compareValue(descriptor[1], value[1][index - 1][0], value[1][index][0]) >= 0)
            reject("invalid-typed-value", "typed map keys are duplicated or non-canonical");
      } else if (kind === "record") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== descriptor[2].length + 1)
          reject("invalid-typed-value", "record shape or nominal descriptor is invalid");
        descriptor[2].forEach(([, type], index) => assertValue(type, value[index + 1], state));
      } else reject("invalid-typed-value", "unknown compound typed value");
      return value;
    } finally { state.depth -= 1; }
  };
  const checkedBuilder = value => {
    if ((typeof value !== "object" && typeof value !== "function") || value === null ||
        !builders.has(value)) reject("invalid-typed-builder", "forged typed builder rejected");
    return value;
  };
  const imports = Object.freeze({
    literal: literalAt,
    new(descriptorId, tag) {
      descriptorAt(descriptorId);
      const builder = Object.freeze({ descriptorId, tag, slots: Object.freeze([]) });
      builders.add(builder);
      return builder;
    },
    "push-i64"(rawBuilder, item) {
      const builder = checkedBuilder(rawBuilder);
      const next = Object.freeze({ ...builder, slots: Object.freeze([...builder.slots, i64(item)]) });
      builders.add(next);
      return next;
    },
    "push-ref"(rawBuilder, item) {
      const builder = checkedBuilder(rawBuilder);
      const next = Object.freeze({ ...builder, slots: Object.freeze([...builder.slots, item]) });
      builders.add(next);
      return next;
    },
    seal(descriptorId, rawBuilder) {
      const descriptor = descriptorAt(descriptorId);
      const builder = checkedBuilder(rawBuilder);
      if (builder.descriptorId !== descriptorId)
        reject("invalid-typed-builder", "typed builder descriptor substitution rejected");
      const kind = Array.isArray(descriptor) ? descriptor[0] : descriptor;
      let result;
      if (kind === "option") result = builder.tag === 0
        ? [descriptor, false] : [descriptor, true, ...builder.slots];
      else if (kind === "result") result = [builder.tag === 1, ...builder.slots];
      else if (kind === "variant") {
        const member = descriptor[2][builder.tag];
        if (member === undefined) reject("invalid-typed-builder", "variant tag is out of range");
        result = [descriptor, member[0], ...builder.slots];
      } else if (kind === "vector" || kind === "record") result = [descriptor, ...builder.slots];
      else if (kind === "set") {
        const sorted = [...builder.slots].sort((left, right) => compareValue(descriptor[1], left, right));
        for (let index = 1; index < sorted.length; index += 1)
          if (compareValue(descriptor[1], sorted[index - 1], sorted[index]) === 0)
            reject("invalid-typed-set", "duplicate typed set item rejected");
        result = [descriptor, Object.freeze(sorted)];
      } else if (kind === "map") {
        if (builder.slots.length % 2 !== 0)
          reject("invalid-typed-builder", "typed map builder has an unmatched key");
        const entries = [];
        for (let index = 0; index < builder.slots.length; index += 2)
          entries.push(Object.freeze([builder.slots[index], builder.slots[index + 1]]));
        entries.sort((left, right) => compareValue(descriptor[1], left[0], right[0]));
        for (let index = 1; index < entries.length; index += 1)
          if (compareValue(descriptor[1], entries[index - 1][0], entries[index][0]) === 0)
            reject("invalid-typed-map", "duplicate typed map key rejected");
        result = [descriptor, Object.freeze(entries)];
      } else reject("invalid-typed-builder", "primitive descriptor cannot be constructed");
      return assertValue(descriptor, Object.freeze(result));
    },
    "assert-ref"(descriptorId, value) { return assertValue(descriptorAt(descriptorId), value); },
    tag(descriptorId, value) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      if (descriptor === "bool") return checked ? 1 : 0;
      if (descriptor[0] === "option") return checked[1] ? 1 : 0;
      if (descriptor[0] === "result") return checked[0] ? 1 : 0;
      if (descriptor[0] === "variant") return descriptor[2].findIndex(([name]) => name === checked[1]);
      reject("invalid-typed-operation", "tag is not defined for this descriptor");
    },
    "get-i64"(descriptorId, value, index) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      const kind = descriptor[0];
      const item = kind === "option" ? checked[2]
        : kind === "result" ? checked[1]
        : kind === "variant" ? checked[2]
        : checked[index + 1];
      return i64(item);
    },
    "get-ref"(descriptorId, value, index) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      const kind = descriptor[0];
      return kind === "option" ? checked[2]
        : kind === "result" ? checked[1]
        : kind === "variant" ? checked[2]
        : checked[index + 1];
    },
    count(descriptorId, value) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      if (descriptor === "string") return BigInt(utf8Length(checked));
      if (descriptor[0] === "vector" || descriptor[0] === "record") return BigInt(checked.length - 1);
      if (descriptor[0] === "set") return BigInt(checked[1].length);
      if (descriptor[0] === "map") return BigInt(checked[1].length);
      reject("invalid-typed-operation", "count is not defined for this descriptor");
    },
    bool(value) { return value !== 0; },
    equal(descriptorId, left, right) {
      const descriptor = descriptorAt(descriptorId);
      return compareValue(descriptor, assertValue(descriptor, left), assertValue(descriptor, right)) === 0 ? 1 : 0;
    },
    "assoc-i64"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, i64(replacement));
    },
    "assoc-ref"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, replacement);
    },
    "set-op-i64"(descriptorId, value, operation, item) {
      return setOperation(descriptorId, value, operation, i64(item));
    },
    "set-op-ref"(descriptorId, value, operation, item) {
      return setOperation(descriptorId, value, operation, item);
    },
    "set-contains-i64"(descriptorId, value, item) {
      return setContains(descriptorId, value, i64(item)) ? 1 : 0;
    },
    "set-contains-ref"(descriptorId, value, item) {
      return setContains(descriptorId, value, item) ? 1 : 0;
    },
    "map-contains-i64"(descriptorId, value, key) {
      return mapContains(descriptorId, value, i64(key)) ? 1 : 0;
    },
    "map-contains-ref"(descriptorId, value, key) {
      return mapContains(descriptorId, value, key) ? 1 : 0;
    },
    "map-get-i64"(descriptorId, value, key) { return mapGet(descriptorId, value, i64(key)); },
    "map-get-ref"(descriptorId, value, key) { return mapGet(descriptorId, value, key); },
    "map-entry-at"(descriptorId, value, index) { return mapEntryAt(descriptorId, value, i64(index)); },
    "map-assoc-ii"(descriptorId, value, key, item) {
      return mapAssoc(descriptorId, value, i64(key), i64(item));
    },
    "map-assoc-ir"(descriptorId, value, key, item) {
      return mapAssoc(descriptorId, value, i64(key), item);
    },
    "map-assoc-ri"(descriptorId, value, key, item) {
      return mapAssoc(descriptorId, value, key, i64(item));
    },
    "map-assoc-rr"(descriptorId, value, key, item) {
      return mapAssoc(descriptorId, value, key, item);
    },
    "map-dissoc-i64"(descriptorId, value, key) {
      return mapDissoc(descriptorId, value, i64(key));
    },
    "map-dissoc-ref"(descriptorId, value, key) { return mapDissoc(descriptorId, value, key); }
  });
  const assoc = (descriptorId, value, index, replacement) => {
    const descriptor = descriptorAt(descriptorId);
    const checked = assertValue(descriptor, value);
    const types = descriptor[0] === "vector" ? descriptor[1]
      : descriptor[0] === "record" ? descriptor[2].map(([, type]) => type) : null;
    if (types === null || !Number.isInteger(index) || index < 0 || index >= types.length)
      reject("invalid-typed-operation", "typed assoc index is invalid");
    assertValue(types[index], replacement);
    const result = [...checked];
    result[index + 1] = replacement;
    return assertValue(descriptor, Object.freeze(result));
  };
  const setOperation = (descriptorId, value, operation, item) => {
    const descriptor = descriptorAt(descriptorId);
    if (!Array.isArray(descriptor) || descriptor[0] !== "set")
      reject("invalid-typed-operation", "set operation requires a set descriptor");
    const checked = assertValue(descriptor, value);
    assertValue(descriptor[1], item);
    const found = checked[1].some(existing => compareValue(descriptor[1], existing, item) === 0);
    if (operation === 0) return found;
    let items;
    if (operation === 1) {
      if (found) return checked;
      if (checked[1].length >= 32) reject("invalid-typed-set", "typed set item budget exceeded");
      items = [...checked[1], item].sort((left, right) => compareValue(descriptor[1], left, right));
    } else if (operation === 2) {
      items = checked[1].filter(existing => compareValue(descriptor[1], existing, item) !== 0);
    } else reject("invalid-typed-operation", "unknown typed set operation");
    return assertValue(descriptor, Object.freeze([descriptor, Object.freeze(items)]));
  };
  const setContains = (descriptorId, value, item) => {
    const descriptor = descriptorAt(descriptorId);
    if (!Array.isArray(descriptor) || descriptor[0] !== "set")
      reject("invalid-typed-operation", "set contains requires a set descriptor");
    const checked = assertValue(descriptor, value);
    assertValue(descriptor[1], item);
    return checked[1].some(existing => compareValue(descriptor[1], existing, item) === 0);
  };
  const checkedMap = (descriptorId, value) => {
    const descriptor = descriptorAt(descriptorId);
    if (!Array.isArray(descriptor) || descriptor[0] !== "map")
      reject("invalid-typed-operation", "map operation requires a map descriptor");
    return [descriptor, assertValue(descriptor, value)];
  };
  const mapIndex = (descriptorId, value, key) => {
    const [descriptor, checked] = checkedMap(descriptorId, value);
    assertValue(descriptor[1], key);
    return [descriptor, checked,
            checked[1].findIndex(entry => compareValue(descriptor[1], entry[0], key) === 0)];
  };
  const mapContains = (descriptorId, value, key) => mapIndex(descriptorId, value, key)[2] >= 0;
  const mapGet = (descriptorId, value, key) => {
    const [descriptor, checked, index] = mapIndex(descriptorId, value, key);
    const optionDescriptor = abi.descriptors.find(candidate =>
      Array.isArray(candidate) && candidate[0] === "option" &&
      sameDescriptor(candidate[1], descriptor[2]));
    if (optionDescriptor === undefined)
      reject("invalid-typed-operation", "typed map lookup option descriptor is absent");
    return assertValue(optionDescriptor, Object.freeze(index < 0
      ? [optionDescriptor, false]
      : [optionDescriptor, true, checked[1][index][1]]));
  };
  const mapEntryAt = (descriptorId, value, index) => {
    const [descriptor, checked] = checkedMap(descriptorId, value);
    const entryDescriptor = abi.descriptors.find(candidate =>
      Array.isArray(candidate) && candidate[0] === "vector" && candidate[1].length === 2 &&
      sameDescriptor(candidate[1][0], descriptor[1]) && sameDescriptor(candidate[1][1], descriptor[2]));
    const optionDescriptor = abi.descriptors.find(candidate =>
      Array.isArray(candidate) && candidate[0] === "option" &&
      sameDescriptor(candidate[1], entryDescriptor));
    if (entryDescriptor === undefined || optionDescriptor === undefined)
      reject("invalid-typed-operation", "typed map entry option descriptor is absent");
    if (index < 0n || index >= BigInt(checked[1].length))
      return assertValue(optionDescriptor, Object.freeze([optionDescriptor, false]));
    const entry = checked[1][Number(index)];
    return assertValue(optionDescriptor, Object.freeze([
      optionDescriptor, true, Object.freeze([entryDescriptor, entry[0], entry[1]])]));
  };
  const mapAssoc = (descriptorId, value, key, item) => {
    const [descriptor, checked, index] = mapIndex(descriptorId, value, key);
    assertValue(descriptor[2], item);
    if (index < 0 && checked[1].length >= 31)
      reject("invalid-typed-map", "typed map entry budget exceeded");
    const entries = [...checked[1]];
    const entry = Object.freeze([key, item]);
    if (index < 0) entries.push(entry); else entries[index] = entry;
    entries.sort((left, right) => compareValue(descriptor[1], left[0], right[0]));
    return assertValue(descriptor, Object.freeze([descriptor, Object.freeze(entries)]));
  };
  const mapDissoc = (descriptorId, value, key) => {
    const [descriptor, checked, index] = mapIndex(descriptorId, value, key);
    if (index < 0) return checked;
    return assertValue(descriptor, Object.freeze([
      descriptor, Object.freeze(checked[1].filter((_, itemIndex) => itemIndex !== index))]));
  };
  return Object.freeze({ imports });
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
  const admission = validateModule(module);
  const typedAbi = admission.typedAbi;
  const compatibility = admission.compatibility;
  const typed = createTypedRuntime(typedAbi);
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
      "kotoba:heap": heap.imports,
      "kotoba:typed": typed?.imports ?? Object.freeze({})
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
    compatibility,
    report: () => Object.freeze({ heap: heap.report() })
  });
}

export const browserProfile = Object.freeze({
  format: "kotoba.browser-host/v1",
  maxModuleBytes: MAX_MODULE_BYTES,
  pairCapacity: PAIR_CAPACITY,
  typedAbiVersion: TYPED_ABI_VERSION,
  compatibilityVersion: COMPATIBILITY_VERSION,
  imports: Object.freeze(Array.from(ALLOWED_IMPORTS).sort())
});
