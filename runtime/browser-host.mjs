const MAX_MODULE_BYTES = 1024 * 1024;
const PAIR_CAPACITY = 4096;
const TYPED_SECTION = "kotoba.typed";
const TYPED_ABI_VERSION = 11;
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
  "kotoba:typed/push-f64/function",
  "kotoba:typed/push-f32/function",
  "kotoba:typed/push-ref/function",
  "kotoba:typed/seal/function",
  "kotoba:typed/assert-ref/function",
  "kotoba:typed/tag/function",
  "kotoba:typed/get-i64/function",
  "kotoba:typed/get-f64/function",
  "kotoba:typed/get-f32/function",
  "kotoba:typed/get-ref/function",
  "kotoba:typed/count/function",
  "kotoba:typed/bool/function",
  "kotoba:typed/equal/function",
  "kotoba:typed/assoc-i64/function",
  "kotoba:typed/assoc-f64/function",
  "kotoba:typed/assoc-f32/function",
  "kotoba:typed/assoc-ref/function",
  "kotoba:typed/vector-drop/function",
  "kotoba:typed/vector-at-i64/function",
  "kotoba:typed/vector-assoc-i64/function",
  "kotoba:typed/vector-conj-i64/function",
  "kotoba:typed/vector-at-f64/function",
  "kotoba:typed/vector-assoc-f64/function",
  "kotoba:typed/vector-conj-f64/function",
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
  "kotoba:typed/map-dissoc-ref/function",
  "kotoba:typed/string-concat/function",
  "kotoba:typed/string-replace-all/function",
  "kotoba:typed/string-index-new/function",
  "kotoba:typed/string-index-contains/function",
  "kotoba:typed/string-index-get/function",
  "kotoba:typed/string-index-assoc/function",
  "kotoba:typed/disjoint-set-i64-new/function",
  "kotoba:typed/disjoint-set-i64-union/function",
  "kotoba:typed/document-null/function",
  "kotoba:typed/document-bool/function",
  "kotoba:typed/document-i64/function",
  "kotoba:typed/document-f64/function",
  "kotoba:typed/document-string/function",
  "kotoba:typed/document-keyword/function",
  "kotoba:typed/document-contains/function",
  "kotoba:typed/document-vector-at/function",
  "kotoba:typed/document-vector-assoc/function",
  "kotoba:typed/document-vector-conj/function",
  "kotoba:typed/document-vector-drop/function",
  "kotoba:typed/document-vector-remove/function",
  "kotoba:typed/document-get/function",
  "kotoba:typed/document-assoc/function",
  "kotoba:typed/document-dissoc/function",
  "kotoba:typed/document-merge/function",
  "kotoba:typed/document-string-value/function",
  "kotoba:typed/document-keyword-value/function",
  "kotoba:typed/document-bool-value/function",
  "kotoba:typed/document-i64-value/function",
  "kotoba:typed/document-f64-value/function",
  "kotoba:typed/keyword-from-string/function",
  "kotoba:typed/cap-call/function",
  "kotoba:typed/xml-path-count/function",
  "kotoba:typed/xml-path-attr/function",
  "kotoba:typed/decimal-f64-parse/function",
  "kotoba:typed/decimal-f64x3-parse/function"
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
  const allowed = new Set(["allowCapabilities", "capCall", "typedCapCall", "expectedSha256"]);
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
  const text = (allowEmpty = false) => {
    const length = uleb();
    if (length > 4096 || offset + length > bytes.length)
      reject("invalid-typed-metadata", "invalid typed ABI text length");
    let decoded;
    try { decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(offset, offset + length)); }
    catch (error) { reject("invalid-typed-metadata", "typed ABI text is not UTF-8", error); }
    offset += length;
    if (!allowEmpty && decoded.length === 0)
      reject("invalid-typed-metadata", "typed ABI names must be non-empty");
    return decoded;
  };
  const descriptor = depth => {
    nodes += 1;
    if (depth > 8 || nodes > 64)
      reject("invalid-typed-metadata", "typed ABI descriptor budget exceeded");
    const tag = byte();
    if (tag <= 3) return Object.freeze(["i64", "string", "keyword", "bool"][tag]);
    if (tag === 11) return Object.freeze(["vector-i64"]);
    if (tag === 12) return "f64";
    if (tag === 13) return "f32";
    if (tag === 14) return Object.freeze(["vector-f64"]);
    if (tag === 15) return Object.freeze(["ref", text()]);
    if (tag === 16 && version >= 10) return Object.freeze(["string-index"]);
    if (tag === 17 && version >= 10) return Object.freeze(["disjoint-set-i64"]);
    if (tag === 18 && version === 11) return Object.freeze(["document"]);
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
  const version = byte();
  if (version !== 5 && version !== 6 && version !== 7 && version !== 8 && version !== 9 && version !== 10 && version !== TYPED_ABI_VERSION)
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
    if (tag === 0) literals.push(Object.freeze(["string", text(true)]));
    else if (tag === 1) literals.push(Object.freeze(["keyword", text()]));
    else if (tag === 2 || tag === 3) literals.push(Object.freeze(["bool", tag === 3]));
    else reject("invalid-typed-metadata", "unknown typed ABI literal tag");
  }
  const schemas = new Map();
  const contracts = new Map();
  if (version >= 9) {
    const schemaCount = uleb();
    if (schemaCount > 32) reject("invalid-typed-metadata", "too many application schemas");
    for (let index = 0; index < schemaCount; index += 1) {
      const name = text();
      const digest = text();
      if (!/^:[^/\s]+\/[^\s]+$/u.test(name) || !/^[0-9a-f]{64}$/u.test(digest) || schemas.has(name))
        reject("invalid-typed-metadata", "invalid or duplicate application schema identity");
      nodes = 0;
      schemas.set(name, Object.freeze({ digest, descriptor: descriptor(1) }));
    }
    const contractCount = uleb();
    if (contractCount > 256) reject("invalid-typed-metadata", "too many typed capability contracts");
    for (let index = 0; index < contractCount; index += 1) {
      const id = uleb();
      const request = uleb();
      const result = uleb();
      if (id > 255 || request >= descriptors.length || result >= descriptors.length || contracts.has(id))
        reject("invalid-typed-metadata", "invalid or duplicate typed capability contract");
      contracts.set(id, Object.freeze({ request, result }));
    }
  }
  if (offset !== bytes.length) reject("invalid-typed-metadata", "trailing typed ABI metadata rejected");
  return Object.freeze({
    version,
    descriptors: Object.freeze(descriptors),
    literals: Object.freeze(literals),
    schemas,
    contracts
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

function createTypedRuntime(abi, typedCapCall, allow) {
  if (abi === null) return null;
  // Copy mutable Map containers before exposing parsed metadata to callers.
  // Runtime authority must remain sealed even if a consumer mutates the
  // diagnostic `typedAbi.schemas` or `typedAbi.contracts` maps.
  const schemaTable = new Map(abi.schemas ?? []);
  const contractTable = new Map(abi.contracts ?? []);
  const builders = new WeakSet();
  const trustedDescriptors = new WeakSet();
  const trustedValues = new WeakSet();
  const trustDescriptor = descriptor => {
    if (!Array.isArray(descriptor) || trustedDescriptors.has(descriptor)) return;
    trustedDescriptors.add(descriptor);
    for (const item of descriptor) trustDescriptor(item);
  };
  abi.descriptors.forEach(trustDescriptor);
  for (const schema of schemaTable.values()) trustDescriptor(schema.descriptor);
  const resolveDescriptor = descriptor => {
    if (!Array.isArray(descriptor) || descriptor[0] !== "ref") return descriptor;
    const schema = schemaTable.get(descriptor[1]);
    if (schema === undefined)
      reject("invalid-typed-descriptor", "schema reference is outside the sealed table");
    return schema.descriptor;
  };
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
  const f64 = value => {
    if (typeof value !== "number")
      reject("invalid-typed-value", "typed f64 value is invalid");
    return Number.isNaN(value) ? Number.NaN : value;
  };
  const f32 = value => {
    if (typeof value !== "number" || !Object.is(Math.fround(value), value))
      reject("invalid-typed-value", "typed f32 value is invalid");
    return Number.isNaN(value) ? Number.NaN : value;
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
  const documentDescriptor = abi.descriptors.find(descriptor =>
    Array.isArray(descriptor) && descriptor[0] === "document");
  const copyDocument = source => {
    const state = { nodes: 0, bytes: 0, seen: new WeakSet() };
    const text = (value, keyword = false) => {
      if (typeof value !== "string" || (keyword && !value.startsWith(":")))
        reject("invalid-typed-value", keyword ? "document keyword is invalid" : "document string is invalid");
      const size = utf8Length(value);
      if (size > (keyword ? 512 : 65536))
        reject("invalid-typed-value", "document scalar text is oversized");
      state.bytes += size;
      if (state.bytes > 65536) reject("invalid-typed-value", "document UTF-8 budget exceeded");
      return value;
    };
    const walk = (node, depth) => {
      state.nodes += 1;
      if (depth > 8 || state.nodes > 256)
        reject("invalid-typed-value", "document depth or node budget exceeded");
      if (!Array.isArray(node) || Object.getPrototypeOf(node) !== Array.prototype || state.seen.has(node))
        reject("invalid-typed-value", "document must be an acyclic unshared array tree");
      state.seen.add(node);
      const tag = node[0];
      if (tag === "null" && node.length === 1) return Object.freeze(["null"]);
      if (node.length !== 2 || typeof tag !== "string")
        reject("invalid-typed-value", "document node shape is invalid");
      const payload = node[1];
      if (tag === "bool") {
        if (typeof payload !== "boolean") reject("invalid-typed-value", "document bool is invalid");
        return Object.freeze([tag, payload]);
      }
      if (tag === "i64") return Object.freeze([tag, i64(payload)]);
      if (tag === "f64") {
        if (typeof payload !== "number" || !Number.isFinite(payload))
          reject("invalid-typed-value", "document f64 must be finite");
        return Object.freeze([tag, payload]);
      }
      if (tag === "string") return Object.freeze([tag, text(payload)]);
      if (tag === "keyword") return Object.freeze([tag, text(payload, true)]);
      if (tag === "vector") {
        if (!Array.isArray(payload) || Object.getPrototypeOf(payload) !== Array.prototype || payload.length > 32)
          reject("invalid-typed-value", "document vector is invalid or oversized");
        if (state.seen.has(payload)) reject("invalid-typed-value", "document arrays cannot be shared");
        state.seen.add(payload);
        return Object.freeze([tag, Object.freeze(payload.map(item => walk(item, depth + 1)))]);
      }
      if (tag === "map") {
        if (!Array.isArray(payload) || Object.getPrototypeOf(payload) !== Array.prototype || payload.length > 32)
          reject("invalid-typed-value", "document map is invalid or oversized");
        if (state.seen.has(payload)) reject("invalid-typed-value", "document arrays cannot be shared");
        state.seen.add(payload);
        let previous = null;
        const entries = payload.map(entry => {
          if (!Array.isArray(entry) || Object.getPrototypeOf(entry) !== Array.prototype ||
              entry.length !== 2 || state.seen.has(entry))
            reject("invalid-typed-value", "document map entry is invalid or shared");
          state.seen.add(entry);
          const key = text(entry[0], true);
          if (previous !== null && previous >= key)
            reject("invalid-typed-value", "document map keys are duplicate or noncanonical");
          previous = key;
          return Object.freeze([key, walk(entry[1], depth + 1)]);
        });
        return Object.freeze([tag, Object.freeze(entries)]);
      }
      reject("invalid-typed-value", "unknown document tag");
    };
    return walk(source, 0);
  };
  const admitDocument = source => {
    if (documentDescriptor === undefined)
      reject("invalid-typed-operation", "document descriptor is absent");
    const result = copyDocument(source);
    const trust = node => {
      trustedValues.add(node);
      if (node[0] === "vector") node[1].forEach(trust);
      else if (node[0] === "map") node[1].forEach(entry => trust(entry[1]));
    };
    trust(result);
    return result;
  };
  const assertDocument = value => {
    if (!Array.isArray(value) || !Object.isFrozen(value) || !trustedValues.has(value))
      reject("invalid-typed-value", "forged document value rejected");
    return value;
  };
  const xmlName = /^[A-Za-z_][A-Za-z0-9_.:-]{0,127}$/u;
  const xmlString = value => {
    if (typeof value !== "string" || utf8Length(value) > 65536)
      reject("invalid-xml", "XML string is invalid or oversized");
    return value;
  };
  const xmlWhitespace = character =>
    character === " " || character === "\t" || character === "\n" || character === "\r";
  const parseBoundedXml = input => {
    const text = xmlString(input);
    let cursor = 0, nodes = 0;
    const output = [];
    const skip = () => { while (cursor < text.length && xmlWhitespace(text[cursor])) cursor += 1; };
    const comment = () => {
      if (!text.startsWith("<!--", cursor)) return false;
      const end = text.indexOf("-->", cursor + 4);
      if (end < 0 || text.slice(cursor + 4, end).includes("--"))
        reject("invalid-xml", "XML comment is invalid");
      cursor = end + 3;
      return true;
    };
    const comments = () => { for (;;) { skip(); if (!comment()) return; } };
    const name = () => {
      const begin = cursor;
      while (cursor < text.length && /[A-Za-z0-9_.:-]/u.test(text[cursor])) cursor += 1;
      const result = text.slice(begin, cursor);
      if (!xmlName.test(result)) reject("invalid-xml", "XML name is invalid");
      return result;
    };
    const element = (depth, parent) => {
      if (depth > 32) reject("invalid-xml", "XML depth limit exceeded");
      if (++nodes > 2048) reject("invalid-xml", "XML node limit exceeded");
      if (text[cursor] !== "<" || ["/", "!", "?"].includes(text[cursor + 1]))
        reject("invalid-xml", "XML element is invalid");
      cursor += 1;
      const tag = name();
      const path = parent ? `${parent}/${tag}` : tag;
      const attributes = Object.create(null);
      let attributeCount = 0, empty = false;
      for (;;) {
        skip();
        if (text.startsWith("/>", cursor)) { cursor += 2; empty = true; break; }
        if (text[cursor] === ">") { cursor += 1; break; }
        const attribute = name();
        if (Object.prototype.hasOwnProperty.call(attributes, attribute))
          reject("invalid-xml", "duplicate XML attribute rejected");
        if (++attributeCount > 32) reject("invalid-xml", "XML attribute limit exceeded");
        skip();
        if (text[cursor++] !== "=") reject("invalid-xml", "XML attribute is invalid");
        skip();
        const quote = text[cursor++];
        if (quote !== "\"" && quote !== "'") reject("invalid-xml", "XML attribute quote is invalid");
        const begin = cursor;
        while (cursor < text.length && text[cursor] !== quote) {
          if (text[cursor] === "<" || text[cursor] === "&")
            reject("invalid-xml", "XML entities and attribute markup are rejected");
          cursor += 1;
        }
        if (cursor >= text.length) reject("invalid-xml", "XML attribute is unterminated");
        attributes[attribute] = xmlString(text.slice(begin, cursor++));
      }
      output.push(Object.freeze({ path, attributes: Object.freeze(attributes) }));
      if (empty) return;
      for (;;) {
        comments(); skip();
        if (text.startsWith("</", cursor)) {
          cursor += 2;
          const closing = name();
          skip();
          if (closing !== tag || text[cursor++] !== ">")
            reject("invalid-xml", "XML closing tag mismatch");
          return;
        }
        if (text[cursor] === "<") { element(depth + 1, path); continue; }
        reject("invalid-xml", "XML text content is rejected");
      }
    };
    comments();
    if (text.startsWith("<?xml", cursor)) {
      const end = text.indexOf("?>", cursor + 5);
      if (end < 0 || !/^<\?xml\s+version=(?:"1\.[01]"|'1\.[01]')(?:\s+encoding=(?:"(?:UTF-8|utf-8)"|'(?:UTF-8|utf-8)'))?\s*\?>$/u.test(text.slice(cursor, end + 2)))
        reject("invalid-xml", "XML declaration is invalid");
      cursor = end + 2;
    }
    comments(); element(1, ""); comments(); skip();
    if (cursor !== text.length) reject("invalid-xml", "XML trailing content is rejected");
    return Object.freeze(output);
  };
  const xmlPath = path => {
    path = xmlString(path);
    const segments = path.split("/");
    if (segments.length < 1 || segments.length > 32 || segments.some(segment => !xmlName.test(segment)))
      reject("invalid-xml", "XML path is invalid");
    return path;
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
    if (descriptor === "f64" || descriptor === "f32") {
      if (Number.isNaN(left) || Number.isNaN(right))
        return Number.isNaN(left) && Number.isNaN(right) ? 0 : Number.isNaN(left) ? 1 : -1;
      return left < right ? -1 : left > right ? 1 : 0;
    }
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
    if (kind === "vector-i64")
      return compareSequence(Array.from({ length: Math.max(left.length, right.length) - 1 }, () => "i64"),
                             left.slice(1), right.slice(1));
    if (kind === "vector-f64")
      return compareSequence(Array.from({ length: Math.max(left.length, right.length) - 1 }, () => "f64"),
                             left.slice(1), right.slice(1));
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
    descriptor = resolveDescriptor(descriptor);
    state.nodes += 1;
    state.depth += 1;
    if (state.depth > 8 || state.nodes > 64)
      reject("invalid-typed-value", "typed runtime value budget exceeded");
    try {
      if (descriptor === "i64") return i64(value);
      if (descriptor === "f64") return f64(value);
      if (descriptor === "f32") return f32(value);
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
      if (kind === "document") return assertDocument(value);
      if (!Array.isArray(value) || !Object.isFrozen(value))
        reject("invalid-typed-value", "compound typed value must be a frozen array");
      if (!trustedValues.has(value))
        reject("invalid-typed-value", "forged compound typed value rejected");
      if (kind === "vector-i64") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length > 16385)
          reject("invalid-typed-value", "vector-i64 shape or item budget is invalid");
        for (let index = 1; index < value.length; index += 1) i64(value[index]);
      } else if (kind === "vector-f64") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length > 16385)
          reject("invalid-typed-value", "vector-f64 shape or item budget is invalid");
        for (let index = 1; index < value.length; index += 1) f64(value[index]);
      } else if (kind === "string-index") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== 2 ||
            !Array.isArray(value[1]) || !Object.isFrozen(value[1]) || value[1].length > 128)
          reject("invalid-typed-value", "string-index shape or item budget is invalid");
        let keyBytes = 0;
        let previous = null;
        value[1].forEach(entry => {
          if (!Array.isArray(entry) || !Object.isFrozen(entry) || entry.length !== 2 ||
              typeof entry[0] !== "string")
            reject("invalid-typed-value", "string-index entry is invalid");
          keyBytes += utf8Length(entry[0]); i64(entry[1]);
          if (previous !== null && previous >= entry[0])
            reject("invalid-typed-value", "string-index keys are duplicated or non-canonical");
          previous = entry[0];
        });
        if (keyBytes > 65536)
          reject("invalid-typed-value", "string-index aggregate key budget exceeded");
      } else if (kind === "disjoint-set-i64") {
        if (!sameTrustedDescriptor(value[0], descriptor) || value.length !== 3 ||
            !Array.isArray(value[1]) || !Object.isFrozen(value[1]) ||
            !Array.isArray(value[2]) || !Object.isFrozen(value[2]) ||
            value[1].length !== value[2].length || value[1].length > 128)
          reject("invalid-typed-value", "disjoint-set-i64 shape or item budget is invalid");
        const size = value[1].length;
        value[1].forEach(parent => { parent = i64(parent); if (parent < 0n || parent >= BigInt(size))
          reject("invalid-typed-value", "disjoint-set-i64 parent is out of range"); });
        value[2].forEach(rank => { rank = i64(rank); if (rank < 0n || rank > BigInt(size))
          reject("invalid-typed-value", "disjoint-set-i64 rank is out of range"); });
        for (let start = 0; start < size; start += 1) {
          let current = start;
          let remaining = size + 1;
          while (Number(value[1][current]) !== current) {
            if (--remaining === 0)
              reject("invalid-typed-value", "disjoint-set-i64 parent cycle rejected");
            current = Number(value[1][current]);
          }
        }
      } else if (kind === "option") {
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
  const admitValue = (descriptor, value) => {
    trustedValues.add(value);
    try { return assertValue(descriptor, value); }
    catch (error) { trustedValues.delete(value); throw error; }
  };
  const documentEntries = value => {
    value = assertDocument(value);
    if (value[0] !== "map") reject("invalid-typed-operation", "document map required");
    return value[1];
  };
  const documentKey = value => {
    if (typeof value !== "string" || !value.startsWith(":") || utf8Length(value) > 512)
      reject("invalid-typed-value", "document map key is invalid");
    return value;
  };
  const documentPosition = (value, key) => {
    const entries = documentEntries(value);
    key = documentKey(key);
    return [entries, key, entries.findIndex(entry => entry[0] === key)];
  };
  const documentOption = (type, present, value) => {
    const descriptor = abi.descriptors.find(candidate => Array.isArray(candidate) &&
      candidate[0] === "option" && sameDescriptor(candidate[1], type));
    if (descriptor === undefined) reject("invalid-typed-operation", "document result option descriptor is absent");
    return admitValue(descriptor, Object.freeze(present
      ? [descriptor, true, value] : [descriptor, false]));
  };
  const imports = Object.freeze({
    "cap-call"(id, request) {
      if (!Number.isInteger(id) || id < 0 || id > 255 || !allow.has(id))
        reject("capability-denied", "runtime capability policy denied the typed call");
      const contract = contractTable.get(id);
      if (contract === undefined)
        reject("capability-contract-missing", "typed capability has no sealed contract");
      if (typeof typedCapCall !== "function")
        reject("capability-unimplemented", "no typed host implementation exists for the capability");
      const checkedRequest = assertValue(descriptorAt(contract.request), request);
      const result = typedCapCall(id, checkedRequest, Object.freeze({
        request: descriptorAt(contract.request), result: descriptorAt(contract.result)
      }));
      return assertValue(descriptorAt(contract.result), result);
    },
    literal: literalAt,
    "keyword-from-string"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== "keyword" || typeof value !== "string" ||
          value.length === 0 || value[0] === ":" || /[\s\[\]{}()"',;`~^\\]/u.test(value))
        reject("invalid-typed-value", "keyword source text is invalid");
      const result = `:${value}`;
      if (utf8Length(result) > 512) reject("invalid-typed-value", "keyword is oversized");
      return result;
    },
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
    "push-f64"(rawBuilder, item) {
      const builder = checkedBuilder(rawBuilder);
      const next = Object.freeze({ ...builder, slots: Object.freeze([...builder.slots, f64(item)]) });
      builders.add(next);
      return next;
    },
    "push-f32"(rawBuilder, item) {
      const builder = checkedBuilder(rawBuilder);
      const next = Object.freeze({ ...builder, slots: Object.freeze([...builder.slots, f32(item)]) });
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
      } else if (kind === "vector-i64" || kind === "vector-f64" || kind === "vector" || kind === "record")
        result = [descriptor, ...builder.slots];
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
      } else if (kind === "document") {
        if (builder.tag === -1) result = ["vector", builder.slots];
        else if (builder.tag === -2) {
          if (builder.slots.length % 2 !== 0)
            reject("invalid-typed-builder", "document map builder has an unmatched key");
          const entries = [];
          for (let index = 0; index < builder.slots.length; index += 2)
            entries.push([builder.slots[index], builder.slots[index + 1]]);
          entries.sort((left, right) => left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0);
          result = ["map", entries];
        } else reject("invalid-typed-builder", "document builder tag is invalid");
        return admitDocument(result);
      } else reject("invalid-typed-builder", "primitive descriptor cannot be constructed");
      return admitValue(descriptor, Object.freeze(result));
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
    "get-f64"(descriptorId, value, index) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      const kind = descriptor[0];
      const item = kind === "option" ? checked[2]
        : kind === "result" ? checked[1]
        : kind === "variant" ? checked[2]
        : checked[index + 1];
      return f64(item);
    },
    "get-f32"(descriptorId, value, index) {
      const descriptor = descriptorAt(descriptorId);
      const checked = assertValue(descriptor, value);
      const kind = descriptor[0];
      const item = kind === "option" ? checked[2]
        : kind === "result" ? checked[1]
        : kind === "variant" ? checked[2]
        : checked[index + 1];
      return f32(item);
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
      if (descriptor[0] === "vector-i64" || descriptor[0] === "vector-f64" || descriptor[0] === "vector" || descriptor[0] === "record")
        return BigInt(checked.length - 1);
      if (descriptor[0] === "set") return BigInt(checked[1].length);
      if (descriptor[0] === "map") return BigInt(checked[1].length);
      if (descriptor[0] === "string-index") return BigInt(checked[1].length);
      if (descriptor[0] === "disjoint-set-i64") return BigInt(checked[1].length);
      if (descriptor[0] === "document") {
        if (checked[0] !== "map" && checked[0] !== "vector")
          reject("invalid-typed-operation", "document container required");
        return BigInt(checked[1].length);
      }
      reject("invalid-typed-operation", "count is not defined for this descriptor");
    },
    bool(value) { return value !== 0; },
    equal(descriptorId, left, right) {
      const descriptor = descriptorAt(descriptorId);
      return compareValue(descriptor, assertValue(descriptor, left), assertValue(descriptor, right)) === 0 ? 1 : 0;
    },
    "string-concat"(descriptorId, left, right) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor !== "string") reject("invalid-typed-operation", "string descriptor required");
      const result = assertValue(descriptor, left) + assertValue(descriptor, right);
      if (utf8Length(result) > 65536) reject("invalid-typed-value", "typed string is oversized");
      return result;
    },
    "string-replace-all"(descriptorId, value, needle, replacement) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor !== "string") reject("invalid-typed-operation", "string descriptor required");
      value = assertValue(descriptor, value);
      needle = assertValue(descriptor, needle);
      replacement = assertValue(descriptor, replacement);
      if (needle.length === 0)
        reject("invalid-typed-operation", "empty string replacement needle rejected");
      const result = value.split(needle).join(replacement);
      if (utf8Length(result) > 65536) reject("invalid-typed-value", "typed string is oversized");
      return result;
    },
    "assoc-i64"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, i64(replacement));
    },
    "assoc-f64"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, f64(replacement));
    },
    "assoc-f32"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, f32(replacement));
    },
    "assoc-ref"(descriptorId, value, index, replacement) {
      return assoc(descriptorId, value, index, replacement);
    },
    "vector-drop"(descriptorId, value, rawCount) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-i64" && descriptor[0] !== "vector-f64")
        reject("invalid-typed-operation", "vector-drop requires a homogeneous vector");
      const checked = assertValue(descriptor, value);
      const count = i64(rawCount);
      if (count < 0n || count > BigInt(checked.length - 1))
        reject("invalid-typed-operation", "vector-drop count is out of range");
      const start = Number(count) + 1;
      return admitValue(descriptor, Object.freeze([descriptor, ...checked.slice(start)]));
    },
    "vector-at-i64"(descriptorId, value, rawIndex) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-i64")
        reject("invalid-typed-operation", "vector-at requires vector-i64");
      const checked = assertValue(descriptor, value);
      const index = i64(rawIndex);
      if (index < 0n || index >= BigInt(checked.length - 1))
        reject("invalid-typed-operation", "vector-i64 index is out of range");
      return i64(checked[Number(index) + 1]);
    },
    "vector-assoc-i64"(descriptorId, value, rawIndex, replacement) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-i64")
        reject("invalid-typed-operation", "vector-assoc requires vector-i64");
      const checked = assertValue(descriptor, value);
      const index = i64(rawIndex);
      if (index < 0n || index >= BigInt(checked.length - 1))
        reject("invalid-typed-operation", "vector-i64 assoc index is out of range");
      const result = [...checked];
      result[Number(index) + 1] = i64(replacement);
      return admitValue(descriptor, Object.freeze(result));
    },
    "vector-conj-i64"(descriptorId, value, item) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-i64")
        reject("invalid-typed-operation", "vector-conj requires vector-i64");
      const checked = assertValue(descriptor, value);
      if (checked.length >= 16385) reject("invalid-typed-value", "vector-i64 item budget exceeded");
      return admitValue(descriptor, Object.freeze([...checked, i64(item)]));
    },
    "vector-at-f64"(descriptorId, value, rawIndex) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-f64")
        reject("invalid-typed-operation", "vector-at-f64 requires vector-f64");
      const checked = assertValue(descriptor, value);
      const index = i64(rawIndex);
      if (index < 0n || index >= BigInt(checked.length - 1))
        reject("invalid-typed-operation", "vector-f64 index is out of range");
      return f64(checked[Number(index) + 1]);
    },
    "vector-assoc-f64"(descriptorId, value, rawIndex, replacement) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-f64")
        reject("invalid-typed-operation", "vector-assoc-f64 requires vector-f64");
      const checked = assertValue(descriptor, value);
      const index = i64(rawIndex);
      if (index < 0n || index >= BigInt(checked.length - 1))
        reject("invalid-typed-operation", "vector-f64 assoc index is out of range");
      const result = [...checked];
      result[Number(index) + 1] = f64(replacement);
      return admitValue(descriptor, Object.freeze(result));
    },
    "vector-conj-f64"(descriptorId, value, item) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "vector-f64")
        reject("invalid-typed-operation", "vector-conj-f64 requires vector-f64");
      const checked = assertValue(descriptor, value);
      if (checked.length >= 16385) reject("invalid-typed-value", "vector-f64 item budget exceeded");
      return admitValue(descriptor, Object.freeze([...checked, f64(item)]));
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
    "map-dissoc-ref"(descriptorId, value, key) { return mapDissoc(descriptorId, value, key); },
    "string-index-new"(descriptorId) {
      const descriptor = descriptorAt(descriptorId);
      if (descriptor[0] !== "string-index") reject("invalid-typed-operation", "string-index descriptor required");
      return admitValue(descriptor, Object.freeze([descriptor, Object.freeze([])]));
    },
    "string-index-contains"(descriptorId, value, key) {
      const [,, index] = stringIndexEntry(descriptorId, value, key); return index < 0 ? 0 : 1;
    },
    "string-index-get"(descriptorId, value, key) {
      const [, checked, index] = stringIndexEntry(descriptorId, value, key);
      const optionDescriptor = abi.descriptors.find(candidate =>
        Array.isArray(candidate) && candidate[0] === "option" && candidate[1] === "i64");
      if (optionDescriptor === undefined) reject("invalid-typed-operation", "i64 option descriptor is absent");
      return admitValue(optionDescriptor, Object.freeze(index < 0
        ? [optionDescriptor, false] : [optionDescriptor, true, checked[1][index][1]]));
    },
    "string-index-assoc"(descriptorId, value, key, item) {
      const [descriptor, checked, index] = stringIndexEntry(descriptorId, value, key);
      item = i64(item);
      if (index < 0 && checked[1].length >= 128)
        reject("invalid-typed-value", "string-index item budget exceeded");
      const entries = [...checked[1]];
      const entry = Object.freeze([key, item]);
      if (index < 0) entries.push(entry); else entries[index] = entry;
      entries.sort((left, right) => left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0);
      return admitValue(descriptor, Object.freeze([descriptor, Object.freeze(entries)]));
    },
    "disjoint-set-i64-new"(descriptorId, rawSize) {
      const descriptor = descriptorAt(descriptorId), size = i64(rawSize);
      if (descriptor[0] !== "disjoint-set-i64" || size < 0n || size > 128n)
        reject("invalid-typed-operation", "disjoint-set-i64 size is out of range");
      const parents = Object.freeze(Array.from({length: Number(size)}, (_, index) => BigInt(index)));
      const ranks = Object.freeze(Array.from({length: Number(size)}, () => 0n));
      return admitValue(descriptor, Object.freeze([descriptor, parents, ranks]));
    },
    "disjoint-set-i64-union"(descriptorId, value, rawLeft, rawRight) {
      const descriptor = descriptorAt(descriptorId), checked = assertValue(descriptor, value);
      if (descriptor[0] !== "disjoint-set-i64") reject("invalid-typed-operation", "disjoint-set-i64 descriptor required");
      const size = checked[1].length, left = i64(rawLeft), right = i64(rawRight);
      if (left < 0n || right < 0n || left >= BigInt(size) || right >= BigInt(size))
        reject("invalid-typed-operation", "disjoint-set-i64 index is out of range");
      const root = raw => { let current = Number(raw); for (let fuel = size + 1; fuel > 0; fuel -= 1) {
        const parent = Number(checked[1][current]); if (parent === current) return current; current = parent;
      } reject("invalid-typed-value", "disjoint-set-i64 parent cycle rejected"); };
      const leftRoot = root(left), rightRoot = root(right);
      const optionDescriptor = abi.descriptors.find(candidate => Array.isArray(candidate) &&
        candidate[0] === "option" && sameDescriptor(candidate[1], descriptor));
      if (optionDescriptor === undefined) reject("invalid-typed-operation", "disjoint-set option descriptor is absent");
      if (leftRoot === rightRoot)
        return admitValue(optionDescriptor, Object.freeze([optionDescriptor, false]));
      let child = rightRoot, parent = leftRoot;
      if (checked[2][leftRoot] < checked[2][rightRoot]) { child = leftRoot; parent = rightRoot; }
      const parents = [...checked[1]], ranks = [...checked[2]];
      parents[child] = BigInt(parent);
      if (checked[2][leftRoot] === checked[2][rightRoot]) ranks[parent] += 1n;
      const result = admitValue(descriptor, Object.freeze([descriptor, Object.freeze(parents), Object.freeze(ranks)]));
      return admitValue(optionDescriptor, Object.freeze([optionDescriptor, true, result]));
    },
    "document-null"(descriptorId) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      return admitDocument(["null"]);
    },
    "document-bool"(descriptorId, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor || typeof item !== "boolean")
        reject("invalid-typed-operation", "document bool requires its descriptor and boolean");
      return admitDocument(["bool", item]);
    },
    "document-i64"(descriptorId, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      return admitDocument(["i64", i64(item)]);
    },
    "document-f64"(descriptorId, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor || !Number.isFinite(item))
        reject("invalid-typed-operation", "document f64 must be finite");
      return admitDocument(["f64", item]);
    },
    "document-string"(descriptorId, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      return admitDocument(["string", item]);
    },
    "document-keyword"(descriptorId, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      return admitDocument(["keyword", item]);
    },
    "document-contains"(descriptorId, value, key) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      return documentPosition(value, key)[2] < 0 ? 0 : 1;
    },
    "document-vector-at"(descriptorId, value, rawIndex) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); const index = i64(rawIndex);
      if (value[0] !== "vector") reject("invalid-typed-operation", "document vector required");
      const ok = index >= 0n && index < BigInt(value[1].length);
      return documentOption(documentDescriptor, ok, ok ? value[1][Number(index)] : undefined);
    },
    "document-vector-assoc"(descriptorId, value, rawIndex, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); const index = i64(rawIndex); item = assertDocument(item);
      if (value[0] !== "vector") reject("invalid-typed-operation", "document vector required");
      if (index < 0n || index >= BigInt(value[1].length))
        reject("invalid-typed-operation", "document vector index out of range");
      const output = [...value[1]]; output[Number(index)] = item;
      return admitDocument(["vector", output]);
    },
    "document-vector-conj"(descriptorId, value, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); item = assertDocument(item);
      if (value[0] !== "vector") reject("invalid-typed-operation", "document vector required");
      if (value[1].length >= 32) reject("invalid-typed-value", "document vector item budget exceeded");
      return admitDocument(["vector", [...value[1], item]]);
    },
    "document-vector-drop"(descriptorId, value, rawCount) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); const count = i64(rawCount);
      if (value[0] !== "vector") reject("invalid-typed-operation", "document vector required");
      if (count < 0n || count > BigInt(value[1].length))
        reject("invalid-typed-operation", "document vector drop out of range");
      return admitDocument(["vector", value[1].slice(Number(count))]);
    },
    "document-vector-remove"(descriptorId, value, rawIndex) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); const index = i64(rawIndex);
      if (value[0] !== "vector") reject("invalid-typed-operation", "document vector required");
      if (index < 0n || index >= BigInt(value[1].length))
        reject("invalid-typed-operation", "document vector index out of range");
      return admitDocument(["vector", value[1].filter((_, itemIndex) => itemIndex !== Number(index))]);
    },
    "document-get"(descriptorId, value, key) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      const [entries,, index] = documentPosition(value, key);
      return documentOption(documentDescriptor, index >= 0, index >= 0 ? entries[index][1] : undefined);
    },
    "document-assoc"(descriptorId, value, key, item) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      const [entries, checkedKey, index] = documentPosition(value, key);
      item = assertDocument(item);
      if (index < 0 && entries.length >= 32)
        reject("invalid-typed-value", "document map item budget exceeded");
      const output = entries.map(entry => [entry[0], entry[1]]);
      if (index < 0) output.push([checkedKey, item]); else output[index] = [checkedKey, item];
      output.sort((left, right) => left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0);
      return admitDocument(["map", output]);
    },
    "document-dissoc"(descriptorId, value, key) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      const [entries,, index] = documentPosition(value, key);
      if (index < 0) return assertDocument(value);
      return admitDocument(["map", entries.filter((_, itemIndex) => itemIndex !== index)
        .map(entry => [entry[0], entry[1]])]);
    },
    "document-merge"(descriptorId, left, right) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      const merged = new Map(documentEntries(left).map(entry => [entry[0], entry[1]]));
      for (const entry of documentEntries(right)) merged.set(entry[0], entry[1]);
      if (merged.size > 32) reject("invalid-typed-value", "document map item budget exceeded");
      return admitDocument(["map", [...merged].sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0)]);
    },
    "document-string-value"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); return documentOption("string", value[0] === "string", value[1]);
    },
    "document-keyword-value"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); return documentOption("keyword", value[0] === "keyword", value[1]);
    },
    "document-bool-value"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); return documentOption("bool", value[0] === "bool", value[1]);
    },
    "document-i64-value"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); return documentOption("i64", value[0] === "i64", value[1]);
    },
    "document-f64-value"(descriptorId, value) {
      if (descriptorAt(descriptorId) !== documentDescriptor)
        reject("invalid-typed-operation", "document descriptor required");
      value = assertDocument(value); return documentOption("f64", value[0] === "f64", value[1]);
    },
    "xml-path-count"(xml, path) {
      const wanted = xmlPath(path);
      return BigInt(parseBoundedXml(xml).filter(node => node.path === wanted).length);
    },
    "xml-path-attr"(xml, path, index, attribute) {
      const wanted = xmlPath(path);
      if (typeof index !== "bigint" || index < 0n || BigInt.asIntN(64, index) !== index)
        reject("invalid-xml", "XML element index is invalid");
      attribute = xmlString(attribute);
      if (!xmlName.test(attribute)) reject("invalid-xml", "XML attribute name is invalid");
      const optionDescriptor = abi.descriptors.find(candidate =>
        Array.isArray(candidate) && candidate[0] === "option" && candidate[1] === "string");
      if (optionDescriptor === undefined)
        reject("invalid-typed-operation", "XML string option descriptor is absent");
      const matches = parseBoundedXml(xml).filter(node => node.path === wanted);
      const present = index < BigInt(matches.length) &&
        Object.prototype.hasOwnProperty.call(matches[Number(index)].attributes, attribute);
      return admitValue(optionDescriptor, Object.freeze(present
        ? [optionDescriptor, true, matches[Number(index)].attributes[attribute]]
        : [optionDescriptor, false]));
    },
    "decimal-f64-parse"(input) {
      if (typeof input !== "string")
        reject("invalid-typed-operation", "decimal input must be a string");
      const optionDescriptor = abi.descriptors.find(candidate =>
        Array.isArray(candidate) && candidate[0] === "option" && candidate[1] === "f64");
      if (optionDescriptor === undefined)
        reject("invalid-typed-operation", "decimal f64 option descriptor is absent");
      const valid = new TextEncoder().encode(input).length <= 64 &&
        /^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?)|(?:\.[0-9]+))(?:[eE][+-]?[0-9]{1,3})?$/u.test(input);
      const parsed = valid ? Number(input) : Number.NaN;
      return admitValue(optionDescriptor, Object.freeze(Number.isFinite(parsed)
        ? [optionDescriptor, true, parsed]
        : [optionDescriptor, false]));
    },
    "decimal-f64x3-parse"(input) {
      if (typeof input !== "string")
        reject("invalid-typed-operation", "decimal f64x3 input must be a string");
      const optionDescriptor = abi.descriptors.find(candidate => {
        const vector = Array.isArray(candidate) && candidate[0] === "option" ? candidate[1] : null;
        return Array.isArray(vector) && vector[0] === "vector" &&
          vector[1].length === 3 && vector[1].every(type => type === "f64");
      });
      if (optionDescriptor === undefined)
        reject("invalid-typed-operation", "decimal f64x3 option descriptor is absent");
      const bytes = new TextEncoder().encode(input).length;
      const allowed = bytes <= 194 && /^[0-9eE+.\- \t\r\n]+$/u.test(input);
      const stripped = allowed ? input.replace(/^[ \t\r\n]+|[ \t\r\n]+$/gu, "") : "";
      const parts = stripped === "" ? [] : stripped.split(/[ \t\r\n]+/u);
      const decimal = /^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?)|(?:\.[0-9]+))(?:[eE][+-]?[0-9]{1,3})?$/u;
      const values = parts.map(part => part.length <= 64 && decimal.test(part) ? Number(part) : Number.NaN);
      const present = parts.length === 3 && values.every(Number.isFinite);
      const vectorDescriptor = optionDescriptor[1];
      const vector = present
        ? admitValue(vectorDescriptor, Object.freeze([vectorDescriptor, ...values]))
        : null;
      return admitValue(optionDescriptor, Object.freeze(present
        ? [optionDescriptor, true, vector]
        : [optionDescriptor, false]));
    }
  });
  const assoc = (descriptorId, value, index, replacement) => {
    const descriptor = descriptorAt(descriptorId);
    const checked = assertValue(descriptor, value);
    if (descriptor[0] === "vector-i64") {
      if (!Number.isInteger(index) || index < 0 || index >= checked.length - 1)
        reject("invalid-typed-operation", "vector-i64 assoc index is invalid");
      const result = [...checked];
      result[index + 1] = i64(replacement);
      return admitValue(descriptor, Object.freeze(result));
    }
    const types = descriptor[0] === "vector" ? descriptor[1]
      : descriptor[0] === "record" ? descriptor[2].map(([, type]) => type) : null;
    if (types === null || !Number.isInteger(index) || index < 0 || index >= types.length)
      reject("invalid-typed-operation", "typed assoc index is invalid");
    assertValue(types[index], replacement);
    const result = [...checked];
    result[index + 1] = replacement;
    return admitValue(descriptor, Object.freeze(result));
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
    return admitValue(descriptor, Object.freeze([descriptor, Object.freeze(items)]));
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
  const stringIndexEntry = (descriptorId, value, key) => {
    const descriptor = descriptorAt(descriptorId);
    if (!Array.isArray(descriptor) || descriptor[0] !== "string-index" || typeof key !== "string")
      reject("invalid-typed-operation", "string-index operation requires its descriptor and string key");
    if (utf8Length(key) > 65536) reject("invalid-typed-value", "string-index key is oversized");
    const checked = assertValue(descriptor, value);
    return [descriptor, checked, checked[1].findIndex(entry => entry[0] === key)];
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
    return admitValue(optionDescriptor, Object.freeze(index < 0
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
      return admitValue(optionDescriptor, Object.freeze([optionDescriptor, false]));
    const entry = checked[1][Number(index)];
    const entryValue = admitValue(entryDescriptor,
      Object.freeze([entryDescriptor, entry[0], entry[1]]));
    return admitValue(optionDescriptor, Object.freeze([
      optionDescriptor, true, entryValue]));
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
    return admitValue(descriptor, Object.freeze([descriptor, Object.freeze(entries)]));
  };
  const mapDissoc = (descriptorId, value, key) => {
    const [descriptor, checked, index] = mapIndex(descriptorId, value, key);
    if (index < 0) return checked;
    return admitValue(descriptor, Object.freeze([
      descriptor, Object.freeze(checked[1].filter((_, itemIndex) => itemIndex !== index))]));
  };
  const vectorDescriptor = abi.descriptors.find(descriptor =>
    Array.isArray(descriptor) && descriptor[0] === "vector-i64");
  const hostVector = items => {
    if (vectorDescriptor === undefined)
      reject("invalid-typed-value", "module does not admit vector-i64 values");
    if (!Array.isArray(items) || items.length > 16384)
      reject("invalid-typed-value", "host vector-i64 input is invalid or oversized");
    return admitValue(vectorDescriptor,
      Object.freeze([vectorDescriptor, ...items.map(i64)]));
  };
  const vectorF64Descriptor = abi.descriptors.find(descriptor =>
    Array.isArray(descriptor) && descriptor[0] === "vector-f64");
  const hostVectorF64 = items => {
    if (vectorF64Descriptor === undefined)
      reject("invalid-typed-value", "module does not admit vector-f64 values");
    if (!Array.isArray(items) || items.length > 16384)
      reject("invalid-typed-value", "host vector-f64 input is invalid or oversized");
    return admitValue(vectorF64Descriptor,
      Object.freeze([vectorF64Descriptor, ...items.map(f64)]));
  };
  const stringIndexDescriptor = abi.descriptors.find(descriptor =>
    Array.isArray(descriptor) && descriptor[0] === "string-index");
  const hostStringIndex = entries => {
    if (stringIndexDescriptor === undefined)
      reject("invalid-typed-value", "module does not admit string-index values");
    if (!Array.isArray(entries) || entries.length > 128)
      reject("invalid-typed-value", "host string-index input is invalid or oversized");
    const canonical = entries.map(entry => {
      if (!Array.isArray(entry) || entry.length !== 2 || typeof entry[0] !== "string")
        reject("invalid-typed-value", "host string-index entry is invalid");
      return Object.freeze([entry[0], i64(entry[1])]);
    }).sort((left, right) => left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0);
    return admitValue(stringIndexDescriptor,
      Object.freeze([stringIndexDescriptor, Object.freeze(canonical)]));
  };
  const disjointSetDescriptor = abi.descriptors.find(descriptor =>
    Array.isArray(descriptor) && descriptor[0] === "disjoint-set-i64");
  const hostDisjointSet = size => {
    size = i64(size);
    if (disjointSetDescriptor === undefined || size < 0n || size > 128n)
      reject("invalid-typed-value", "module does not admit requested disjoint-set-i64 value");
    const parents = Object.freeze(Array.from({length: Number(size)}, (_, index) => BigInt(index)));
    const ranks = Object.freeze(Array.from({length: Number(size)}, () => 0n));
    return admitValue(disjointSetDescriptor,
      Object.freeze([disjointSetDescriptor, parents, ranks]));
  };
  const hostDocument = value => {
    if (documentDescriptor === undefined)
      reject("invalid-typed-value", "module does not admit document values");
    return admitDocument(value);
  };
  const values = Object.freeze({
    vectorI64: hostVector,
    vectorF64: hostVectorF64,
    stringIndex: hostStringIndex,
    disjointSetI64: hostDisjointSet,
    document: hostDocument,
    bytes(items) {
      if (!(Array.isArray(items) || ArrayBuffer.isView(items)) || items.length > 16384)
        reject("invalid-typed-value", "host byte input is invalid or oversized");
      return hostVector(Array.from(items, item => {
        if (!Number.isInteger(item) || item < 0 || item > 255)
          reject("invalid-typed-value", "host byte input contains a non-byte value");
        return BigInt(item);
      }));
    }
  });
  return Object.freeze({ imports, values });
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
  if (options.typedCapCall !== undefined && typeof options.typedCapCall !== "function")
    reject("invalid-policy", "typedCapCall must be a function");
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
  const typed = createTypedRuntime(typedAbi, options.typedCapCall, allow);
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
    typedValues: typed?.values ?? null,
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
