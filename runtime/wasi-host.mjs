import { instantiateKotoba, KotobaHostError } from "./browser-host.mjs";

const MAX_MODULE_BYTES = 1024 * 1024;
const TARGET = "wasm32-wasi-kotoba-v1";
const decoder = new TextDecoder("utf-8", { fatal: true });

function reject(code, message, cause) {
  throw new KotobaHostError(code, message, cause);
}

function copiedBytes(source) {
  let view;
  if (source instanceof ArrayBuffer) view = new Uint8Array(source);
  else if (ArrayBuffer.isView(source))
    view = new Uint8Array(source.buffer, source.byteOffset, source.byteLength);
  else reject("invalid-module", "WASI host source must be an ArrayBuffer or typed-array view");
  if (view.byteLength < 8 || view.byteLength > MAX_MODULE_BYTES)
    reject("invalid-module", "Wasm source is outside the admitted byte limit");
  return new Uint8Array(view);
}

function admitTarget(module) {
  const sections = WebAssembly.Module.customSections(module, "kotoba.target");
  if (sections.length !== 1)
    reject("invalid-target", "WASI module must contain exactly one Kotoba target identity");
  let target;
  try { target = decoder.decode(sections[0]); }
  catch (error) { reject("invalid-target", "Kotoba target identity is not canonical UTF-8", error); }
  if (target !== TARGET)
    reject("target-mismatch", "Wasm module is not sealed for the Kotoba WASI profile");
}

export async function instantiateKotobaWasi(source, options) {
  const bytes = copiedBytes(source);
  let module;
  try { module = await WebAssembly.compile(bytes); }
  catch (error) { reject("invalid-module", "Wasm compilation failed", error); }
  admitTarget(module);
  // The shared closed host admits only kotoba:cap and kotoba:heap functions.
  // wasi_snapshot_preview1, filesystem, sockets, clocks, random, environment,
  // and process authority therefore fail before instantiation.
  return instantiateKotoba(bytes, options);
}

export const wasiProfile = Object.freeze({
  format: "kotoba.wasi-host/v1",
  target: TARGET,
  maxModuleBytes: MAX_MODULE_BYTES,
  ambientWasi: false
});
