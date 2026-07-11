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
  if (instance.exports.calc(20n, 4n) !== 21n) throw new Error("calc mismatch");
  if (instance.exports.relations(7n, 3n) !== 10n) throw new Error("relations gt mismatch");
  if (instance.exports.relations(3n, 3n) !== 13n) throw new Error("relations eq mismatch");
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/program.wasm"

native_check() {
  ARTIFACT=$1
  ISA=$2
  SYMBOL=$3
  EXPECTED=$4
  shift 4
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol "$SYMBOL" --output "$TMP/$ISA-$SYMBOL.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  ARITY=$#
  GOT=$("$TMP/kexe-loader" "$TMP/$ISA-$SYMBOL.bin" "$OFFSET" "$ARITY" "$@")
  [ "$GOT" = "$EXPECTED" ] || { echo "native $ISA $SYMBOL expected $EXPECTED, got $GOT" >&2; exit 1; }
}

if [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ]; then
  cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
  native_check "$TMP/x86_64.kexe" x86_64 score 12 -7 2
  native_check "$TMP/x86_64.kexe" x86_64 calc 21 20 4
  native_check "$TMP/x86_64.kexe" x86_64 relations 10 7 3
  native_check "$TMP/x86_64.kexe" x86_64 relations 13 3 3
  printf '%s\n' 'conformance: native x86_64 runtime vector passed under W^X loader'
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Linux-aarch64)
    cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
    native_check "$TMP/aarch64.kexe" aarch64 score 12 -7 2
    native_check "$TMP/aarch64.kexe" aarch64 calc 21 20 4
    native_check "$TMP/aarch64.kexe" aarch64 relations 10 7 3
    native_check "$TMP/aarch64.kexe" aarch64 relations 13 3 3
    printf '%s\n' 'conformance: native aarch64 runtime vector passed under W^X loader'
    ;;
esac

printf '%s\n' 'conformance: wasm32 main=42, wasm32 score(-7,2)=12, x86_64 verified, aarch64 verified'
