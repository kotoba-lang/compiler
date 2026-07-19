const encoder = new TextEncoder();

function uleb(value) {
  const bytes = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value !== 0) byte |= 0x80;
    bytes.push(byte);
  } while (value !== 0);
  return bytes;
}

function text(value) {
  const bytes = [...encoder.encode(value)];
  return [...uleb(bytes.length), ...bytes];
}

export function withCompatibility(moduleBytes, {
  target = "wasm32-browser-kotoba-v1",
  runtime = "kotoba-browser-host-v1",
  kir = "kotoba.kir/v3",
  valueAbi = "kotoba.i64/direct-v1"
} = {}) {
  const name = text("kotoba.compatibility");
  const payload = [
    ...name,
    1,
    ...text("kotoba-compiler/1"),
    ...text("kotoba.language/safe-v1"),
    ...text(kir),
    ...text(target),
    ...text(runtime),
    ...text(valueAbi),
    ...text("kototama/component-tender-v1"),
    ...text("kotoba.capability-host/v1")
  ];
  return Uint8Array.from([...moduleBytes, 0, ...uleb(payload.length), ...payload]);
}
