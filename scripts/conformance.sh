#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-compiler-conformance-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP"

"$ROOT/bin/kotoba" -M check "$ROOT/examples/capability.kotoba" \
  --policy "$ROOT/examples/capability-policy.edn" >"$TMP/capability-check.edn"
if "$ROOT/bin/kotoba" -M check "$ROOT/examples/capability.kotoba" >"$TMP/capability-deny.out" 2>&1; then
  echo "capability source unexpectedly admitted without policy" >&2
  exit 1
fi
grep -q 'capability policy denies required effects' "$TMP/capability-deny.out"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/capability.kotoba" --target wasm32 \
  --policy "$ROOT/examples/capability-policy.edn" --output "$TMP/capability.wasm"

"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target wasm32 --output "$TMP/program.wasm"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/fuel.kotoba" --target wasm32 --output "$TMP/fuel.wasm"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target x86_64 --output "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/fuel.kotoba" --target x86_64 --output "$TMP/x86_64-fuel.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target aarch64 --output "$TMP/aarch64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/x86_64-fuel.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/aarch64.kexe"

"$ROOT/bin/kotoba" -M keygen --output "$TMP/signing-key.edn"
"$ROOT/bin/kotoba" -M trust-key "$TMP/signing-key.edn" --output "$TMP/trust.edn"
"$ROOT/bin/kotoba" -M sign "$TMP/x86_64.kexe" --key "$TMP/signing-key.edn" \
  --not-before 1000 --expires 2000 --output "$TMP/x86_64.signed.kexe"
"$ROOT/bin/kotoba" -M verify-signed "$TMP/x86_64.signed.kexe" \
  --trust "$TMP/trust.edn" --now 1500 >"$TMP/signature-verification.edn"
printf '%s\n' '{:allow #{}}' >"$TMP/pure-policy.edn"
printf '%s\n' '{:argv []}' >"$TMP/input.edn"
printf '%s\n' '42' >"$TMP/output.edn"
"$ROOT/bin/kotoba" -M receipt --signed "$TMP/x86_64.signed.kexe" \
  --trust "$TMP/trust.edn" --policy "$TMP/pure-policy.edn" \
  --input "$TMP/input.edn" --result "$TMP/output.edn" --now 1500 \
  --started-at 1400 --finished-at 1401 --status ok --target x86_64 \
  --entry main --fuel-initial 256 --fuel-remaining 255 \
  --executor-key "$TMP/signing-key.edn" --output "$TMP/run.receipt.edn"
"$ROOT/bin/kotoba" -M verify-receipt "$TMP/run.receipt.edn" \
  --signed "$TMP/x86_64.signed.kexe" --trust "$TMP/trust.edn" \
  --policy "$TMP/pure-policy.edn" --input "$TMP/input.edn" \
  --result "$TMP/output.edn" --now 1500 >"$TMP/receipt-verification.edn"

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
  let zeroTrap = false;
  try { instance.exports.calc(20n, 0n); } catch (error) { zeroTrap = error instanceof WebAssembly.RuntimeError; }
  if (!zeroTrap) throw new Error("Wasm zero division did not trap");
  let overflowTrap = false;
  try { instance.exports.calc(-9223372036854775808n, -1n); } catch (error) { overflowTrap = error instanceof WebAssembly.RuntimeError; }
  if (!overflowTrap) throw new Error("Wasm signed division overflow did not trap");
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/program.wasm"

node -e '
const fs = require("fs");
WebAssembly.instantiate(fs.readFileSync(process.argv[1])).then(({instance}) => {
  if (instance.exports.fact(10n) !== 3628800n) throw new Error("finite recursion mismatch");
  let trapped = false;
  try { instance.exports.forever(0n); } catch (error) { trapped = error instanceof WebAssembly.RuntimeError; }
  if (!trapped) throw new Error("fuel-exhausted recursion did not trap");
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/fuel.wasm"
printf '%s\n' 'conformance: Wasm finite recursion passed; infinite recursion fuel-trapped'

node -e '
const fs = require("fs");
const bytes = fs.readFileSync(process.argv[1]);
const allow = new Set([7n]);
WebAssembly.instantiate(bytes, {"kotoba:cap": {call(cap, value) {
  if (!allow.has(cap)) throw new Error(`capability denied: ${cap}`);
  return value + 1n;
}}}).then(({instance}) => {
  if (instance.exports.helper(41n) !== 42n) throw new Error("capability trampoline mismatch");
  return WebAssembly.instantiate(bytes, {"kotoba:cap": {call() {
    throw new Error("runtime capability denied");
  }}});
}).then(({instance}) => {
  let denied = false;
  try { instance.exports.helper(41n); } catch (error) { denied = /runtime capability denied/.test(error.message); }
  if (!denied) throw new Error("runtime policy denial was bypassed");
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/capability.wasm"
printf '%s\n' 'conformance: Wasm capability trampoline allowed and denied paths passed'

native_check() {
  ARTIFACT=$1
  ISA=$2
  SYMBOL=$3
  EXPECTED=$4
  shift 4
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol "$SYMBOL" --output "$TMP/$ISA-$SYMBOL.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  ARITY=$#
  GOT=$("$TMP/kexe-loader" "$TMP/$ISA-$SYMBOL.bin" "$OFFSET" "$ARITY" "$ISA" - "$@")
  [ "$GOT" = "$EXPECTED" ] || { echo "native $ISA $SYMBOL expected $EXPECTED, got $GOT" >&2; exit 1; }
}

native_expect_trap() {
  ARTIFACT=$1
  ISA=$2
  SYMBOL=$3
  shift 3
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol "$SYMBOL" --output "$TMP/$ISA-$SYMBOL-trap.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  ARITY=$#
  if "$TMP/kexe-loader" "$TMP/$ISA-$SYMBOL-trap.bin" "$OFFSET" "$ARITY" "$ISA" - "$@" >"$TMP/trap.out" 2>"$TMP/trap.err"; then
    echo "native $ISA $SYMBOL unexpectedly returned instead of trapping" >&2
    exit 1
  fi
  grep -q '^KEXE_TRAP signal$' "$TMP/trap.err"
}

native_cap_check() {
  ARTIFACT=$1 ISA=$2 ALLOW=$3 EXPECTED=$4
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol helper --output "$TMP/$ISA-cap.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  GOT=$("$TMP/kexe-loader" "$TMP/$ISA-cap.bin" "$OFFSET" 1 "$ISA" "$ALLOW" 41)
  [ "$GOT" = "$EXPECTED" ] || { echo "native $ISA capability expected $EXPECTED, got $GOT" >&2; exit 1; }
  if "$TMP/kexe-loader" "$TMP/$ISA-cap.bin" "$OFFSET" 1 "$ISA" - 41 >"$TMP/cap-deny.out" 2>"$TMP/cap-deny.err"; then
    echo "native $ISA capability unexpectedly bypassed runtime policy" >&2
    exit 1
  fi
  grep -q '^KEXE_TRAP signal$' "$TMP/cap-deny.err"
}

if [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ]; then
  cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
  native_check "$TMP/x86_64.kexe" x86_64 score 12 -7 2
  native_check "$TMP/x86_64.kexe" x86_64 calc 21 20 4
  native_check "$TMP/x86_64.kexe" x86_64 relations 10 7 3
  native_check "$TMP/x86_64.kexe" x86_64 relations 13 3 3
  native_expect_trap "$TMP/x86_64.kexe" x86_64 calc 20 0
  native_expect_trap "$TMP/x86_64.kexe" x86_64 calc -9223372036854775808 -1
  native_check "$TMP/x86_64-fuel.kexe" x86_64 fact 3628800 10
  native_expect_trap "$TMP/x86_64-fuel.kexe" x86_64 forever 0
  "$ROOT/bin/kotoba" -M compile "$ROOT/examples/capability.kotoba" --target x86_64 \
    --policy "$ROOT/examples/capability-policy.edn" --output "$TMP/x86_64-cap.kexe"
  "$ROOT/bin/kotoba" -M verify "$TMP/x86_64-cap.kexe"
  native_cap_check "$TMP/x86_64-cap.kexe" x86_64 7 42
  printf '%s\n' 'conformance: native x86_64 runtime vector passed under W^X loader'
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Linux-aarch64)
    cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
    native_check "$TMP/aarch64.kexe" aarch64 score 12 -7 2
    native_check "$TMP/aarch64.kexe" aarch64 calc 21 20 4
    native_check "$TMP/aarch64.kexe" aarch64 relations 10 7 3
    native_check "$TMP/aarch64.kexe" aarch64 relations 13 3 3
    native_expect_trap "$TMP/aarch64.kexe" aarch64 calc 20 0
    native_expect_trap "$TMP/aarch64.kexe" aarch64 calc -9223372036854775808 -1
    "$ROOT/bin/kotoba" -M compile "$ROOT/examples/fuel.kotoba" --target aarch64 --output "$TMP/aarch64-fuel.kexe"
    "$ROOT/bin/kotoba" -M verify "$TMP/aarch64-fuel.kexe"
    native_check "$TMP/aarch64-fuel.kexe" aarch64 fact 3628800 10
    native_expect_trap "$TMP/aarch64-fuel.kexe" aarch64 forever 0
    "$ROOT/bin/kotoba" -M compile "$ROOT/examples/capability.kotoba" --target aarch64 \
      --policy "$ROOT/examples/capability-policy.edn" --output "$TMP/aarch64-cap.kexe"
    "$ROOT/bin/kotoba" -M verify "$TMP/aarch64-cap.kexe"
    native_cap_check "$TMP/aarch64-cap.kexe" aarch64 7 42
    printf '%s\n' 'conformance: native aarch64 runtime vector passed under W^X loader'
    ;;
esac

printf '%s\n' 'conformance: wasm32 main=42, wasm32 score(-7,2)=12, x86_64 verified, aarch64 verified'
