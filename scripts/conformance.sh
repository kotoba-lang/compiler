#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-compiler-conformance-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP"

"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target wasm32 --output "$TMP/program.wasm"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target x86_64 --output "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target aarch64 --output "$TMP/aarch64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/aarch64.kexe"

node -e '
const fs = require("fs");
WebAssembly.instantiate(fs.readFileSync(process.argv[1])).then(({instance}) => {
  const got = instance.exports.main();
  if (got !== 42n) throw new Error(`expected 42n, got ${got}`);
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/program.wasm"

printf '%s\n' 'conformance: wasm32 execution=42, x86_64 verified, aarch64 verified'
