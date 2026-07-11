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
  const runtime = instance.exports.score(-7n, 2n);
  if (runtime !== 12n) throw new Error(`runtime argument path expected 12n, got ${runtime}`);
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/program.wasm"

if [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ]; then
  cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
  META=$("$ROOT/bin/kotoba" -M extract-native "$TMP/x86_64.kexe" --symbol score --output "$TMP/x86_64.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  GOT=$("$TMP/kexe-loader" "$TMP/x86_64.bin" "$OFFSET" 2 -7 2)
  [ "$GOT" = 12 ] || { echo "native x86_64 expected 12, got $GOT" >&2; exit 1; }
  printf '%s\n' 'conformance: native x86_64 score(-7,2)=12 under W^X loader'
fi

printf '%s\n' 'conformance: wasm32 main=42, wasm32 score(-7,2)=12, x86_64 verified, aarch64 verified'
