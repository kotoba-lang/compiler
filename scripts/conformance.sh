#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-compiler-conformance-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP"

printf '%s\n' '{} {}' >"$TMP/trailing-policy.edn"
printf '%s\n' '(defn main [] 0)' >"$TMP/bounded-source.kotoba"
if "$ROOT/bin/kotoba" -M check "$TMP/bounded-source.kotoba" \
     --policy "$TMP/trailing-policy.edn" >"$TMP/trailing.out" 2>"$TMP/trailing.err"; then
  echo "trailing EDN control-plane form was accepted" >&2
  exit 1
fi
grep -q 'EDN input contains trailing forms' "$TMP/trailing.err"

dd if=/dev/zero of="$TMP/oversized-source.kotoba" bs=1048577 count=1 2>/dev/null
if "$ROOT/bin/kotoba" -M check "$TMP/oversized-source.kotoba" \
     >"$TMP/oversized.out" 2>"$TMP/oversized.err"; then
  echo "oversized source file was accepted" >&2
  exit 1
fi
grep -q 'input exceeds byte limit' "$TMP/oversized.err"

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
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/i64-semantics.kotoba" --target wasm32 --output "$TMP/i64.wasm"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target x86_64 --output "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/fuel.kotoba" --target x86_64 --output "$TMP/x86_64-fuel.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/i64-semantics.kotoba" --target x86_64 --output "$TMP/x86_64-i64.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/structured.kotoba" --target aarch64 --output "$TMP/aarch64.kexe"
"$ROOT/bin/kotoba" -M compile "$ROOT/examples/i64-semantics.kotoba" --target aarch64 --output "$TMP/aarch64-i64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/x86_64.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/x86_64-fuel.kexe"
"$ROOT/bin/kotoba" -M verify "$TMP/aarch64.kexe"

"$ROOT/bin/kotoba" -M keygen --output "$TMP/signing-key.edn"
case "$(uname -s)" in
  Darwin) KEY_MODE=$(stat -f '%Lp' "$TMP/signing-key.edn") ;;
  Linux) KEY_MODE=$(stat -c '%a' "$TMP/signing-key.edn") ;;
esac
[ "$KEY_MODE" = 600 ] || { echo "signing key permissions expected 600, got $KEY_MODE" >&2; exit 1; }
"$ROOT/bin/kotoba" -M public-key "$TMP/signing-key.edn" --output "$TMP/verification-key.edn"
if grep -q ':private-key' "$TMP/verification-key.edn"; then
  echo "verification key leaked private key material" >&2
  exit 1
fi
"$ROOT/bin/kotoba" -M trust-key "$TMP/verification-key.edn" --output "$TMP/trust.edn"
"$ROOT/bin/kotoba" -M sign "$TMP/x86_64.kexe" --key "$TMP/signing-key.edn" \
  --not-before 1000 --expires 2000 --output "$TMP/x86_64.signed.kexe"
"$ROOT/bin/kotoba" -M verify-signed "$TMP/x86_64.signed.kexe" \
  --trust "$TMP/trust.edn" --now 1500 >"$TMP/signature-verification.edn"
printf '%s\n' '{:allow #{}}' >"$TMP/pure-policy.edn"
printf '%s\n' '{:args []}' >"$TMP/input.edn"
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
printf '[%s]\n' "$(cat "$TMP/run.receipt.edn")" >"$TMP/receipt-chain.edn"
"$ROOT/bin/kotoba" -M verify-chain "$TMP/receipt-chain.edn" \
  --trust "$TMP/trust.edn" >"$TMP/chain-verification.edn"
grep -q ':scope :executor-attested-chain/v1' "$TMP/chain-verification.edn"

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
WebAssembly.instantiate(fs.readFileSync(process.argv[1])).then(({instance: {exports: e}}) => {
  const vectors = [
    ["add", [9223372036854775807n, 1n], -9223372036854775808n],
    ["subtract", [-9223372036854775808n, 1n], 9223372036854775807n],
    ["multiply", [9223372036854775807n, 2n], -2n],
    ["negate", [-9223372036854775808n], -9223372036854775808n],
    ["choose", [0n, 11n, 22n], 22n],
    ["choose", [-1n, 11n, 22n], 11n]
  ];
  for (const [name, args, expected] of vectors) {
    const got = e[name](...args);
    if (got !== expected) throw new Error(`${name}: expected ${expected}, got ${got}`);
  }
}).catch(error => { console.error(error); process.exit(1); });
' "$TMP/i64.wasm"
printf '%s\n' 'conformance: Wasm normative i64 boundary vector passed'

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
  SIGNAL=$4
  shift 4
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol "$SYMBOL" --output "$TMP/$ISA-$SYMBOL-trap.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  ARITY=$#
  if "$TMP/kexe-loader" "$TMP/$ISA-$SYMBOL-trap.bin" "$OFFSET" "$ARITY" "$ISA" - "$@" >"$TMP/trap.out" 2>"$TMP/trap.err"; then
    echo "native $ISA $SYMBOL unexpectedly returned instead of trapping" >&2
    exit 1
  fi
  grep -q "^KEXE_TRAP {:kind :signal :signal :$SIGNAL}$" "$TMP/trap.err"
}

native_cap_check() {
  ARTIFACT=$1 ISA=$2 ALLOW=$3 EXPECTED=$4 SIGNAL=$5
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol helper --output "$TMP/$ISA-cap.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  GOT=$("$TMP/kexe-loader" "$TMP/$ISA-cap.bin" "$OFFSET" 1 "$ISA" "$ALLOW" 41)
  [ "$GOT" = "$EXPECTED" ] || { echo "native $ISA capability expected $EXPECTED, got $GOT" >&2; exit 1; }
  if "$TMP/kexe-loader" "$TMP/$ISA-cap.bin" "$OFFSET" 1 "$ISA" - 41 >"$TMP/cap-deny.out" 2>"$TMP/cap-deny.err"; then
    echo "native $ISA capability unexpectedly bypassed runtime policy" >&2
    exit 1
  fi
  grep -q "^KEXE_TRAP {:kind :signal :signal :$SIGNAL}$" "$TMP/cap-deny.err"
}

native_sandbox_probe() {
  ARTIFACT=$1 ISA=$2 PROBE=$3 REASON=$4
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol main --output "$TMP/$ISA-sandbox.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  if env "$PROBE=1" "$TMP/kexe-loader" "$TMP/$ISA-sandbox.bin" "$OFFSET" 0 "$ISA" - \
       >"$TMP/sandbox-probe.out" 2>"$TMP/sandbox-probe.err"; then
    echo "native $ISA sandbox allowed forbidden $REASON operation" >&2
    exit 1
  fi
  if [ "$(uname -s)" = Linux ]; then
    grep -q '^KEXE_TRAP {:kind :signal :signal :SIGSYS}$' "$TMP/sandbox-probe.err"
  else
    grep -q "^KEXE_TRAP {:kind :sandbox :reason :$REASON-denied}$" "$TMP/sandbox-probe.err"
  fi
}

native_timeout_probe() {
  ARTIFACT=$1 ISA=$2
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol main --output "$TMP/$ISA-timeout.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  if KEXE_TIMEOUT_PROBE=1 "$TMP/kexe-loader" "$TMP/$ISA-timeout.bin" "$OFFSET" 0 "$ISA" - \
       >"$TMP/timeout-probe.out" 2>"$TMP/timeout-probe.err"; then
    echo "native $ISA supervisor failed to terminate a stuck child" >&2
    exit 1
  fi
  grep -q '^KEXE_TRAP {:kind :supervisor :reason :wall-timeout}$' "$TMP/timeout-probe.err"
}

native_report_check() {
  ARTIFACT=$1 ISA=$2
  META=$("$ROOT/bin/kotoba" -M extract-native "$ARTIFACT" --symbol main --output "$TMP/$ISA-report.bin")
  OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')
  REPORT=$(KEXE_STRUCTURED_REPORT=1 "$TMP/kexe-loader" "$TMP/$ISA-report.bin" "$OFFSET" 0 "$ISA" -)
  [ "$REPORT" = '{:status :ok :result 42 :fuel {:initial 256 :remaining 253}}' ] || {
    echo "native $ISA supervisor report mismatch: $REPORT" >&2
    exit 1
  }
}

attested_run_check() {
  SIGNED=$1 ISA=$2
  "$ROOT/bin/kotoba" -M run "$SIGNED" --trust "$TMP/trust.edn" \
    --policy "$TMP/pure-policy.edn" --input "$TMP/input.edn" \
    --executor-key "$TMP/signing-key.edn" --now 1500 \
    --result-output "$TMP/$ISA-run-result.edn" --output "$TMP/$ISA-run-receipt.edn" \
    >"$TMP/$ISA-run-summary.edn"
  "$ROOT/bin/kotoba" -M trust-runtime "$TMP/$ISA-run-result.edn" \
    --trust "$TMP/trust.edn" --output "$TMP/$ISA-runtime-trust.edn" \
    >"$TMP/$ISA-runtime-trust-summary.edn"
  "$ROOT/bin/kotoba" -M verify-receipt "$TMP/$ISA-run-receipt.edn" \
    --signed "$SIGNED" --trust "$TMP/$ISA-runtime-trust.edn" --policy "$TMP/pure-policy.edn" \
    --input "$TMP/input.edn" --result "$TMP/$ISA-run-result.edn" --now 1500 \
    >"$TMP/$ISA-run-verification.edn"
  grep -q ':status :ok' "$TMP/$ISA-run-result.edn"
  grep -q ':result 42' "$TMP/$ISA-run-result.edn"
  grep -q ':format :kotoba.native-runtime/v1' "$TMP/$ISA-run-result.edn"
  grep -Eq ':loader-binary-sha256 "[0-9a-f]{64}"' "$TMP/$ISA-run-result.edn"
  grep -Eq ':runtime-sha256 "[0-9a-f]{64}"' "$TMP/$ISA-runtime-trust-summary.edn"
  grep -q ':remaining 253' "$TMP/$ISA-run-receipt.edn"
  grep -q ':verified? true' "$TMP/$ISA-run-verification.edn"
}

if [ "$(uname -s)-$(uname -m)" = "Linux-x86_64" ]; then
  cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
  native_timeout_probe "$TMP/x86_64.kexe" x86_64
  native_report_check "$TMP/x86_64.kexe" x86_64
  attested_run_check "$TMP/x86_64.signed.kexe" x86_64
  native_sandbox_probe "$TMP/x86_64.kexe" x86_64 KEXE_FILESYSTEM_PROBE filesystem
  native_sandbox_probe "$TMP/x86_64.kexe" x86_64 KEXE_NETWORK_PROBE network
  native_sandbox_probe "$TMP/x86_64.kexe" x86_64 KEXE_PROCESS_PROBE process
  native_check "$TMP/x86_64.kexe" x86_64 score 12 -7 2
  native_check "$TMP/x86_64.kexe" x86_64 calc 21 20 4
  native_check "$TMP/x86_64.kexe" x86_64 relations 10 7 3
  native_check "$TMP/x86_64.kexe" x86_64 relations 13 3 3
  native_expect_trap "$TMP/x86_64.kexe" x86_64 calc SIGFPE 20 0
  native_expect_trap "$TMP/x86_64.kexe" x86_64 calc SIGFPE -9223372036854775808 -1
  native_check "$TMP/x86_64-fuel.kexe" x86_64 fact 3628800 10
  native_expect_trap "$TMP/x86_64-fuel.kexe" x86_64 forever SIGILL 0
  native_check "$TMP/x86_64-i64.kexe" x86_64 add -9223372036854775808 9223372036854775807 1
  native_check "$TMP/x86_64-i64.kexe" x86_64 subtract 9223372036854775807 -9223372036854775808 1
  native_check "$TMP/x86_64-i64.kexe" x86_64 multiply -2 9223372036854775807 2
  native_check "$TMP/x86_64-i64.kexe" x86_64 negate -9223372036854775808 -9223372036854775808
  native_check "$TMP/x86_64-i64.kexe" x86_64 choose 22 0 11 22
  native_check "$TMP/x86_64-i64.kexe" x86_64 choose 11 -1 11 22
  "$ROOT/bin/kotoba" -M compile "$ROOT/examples/capability.kotoba" --target x86_64 \
    --policy "$ROOT/examples/capability-policy.edn" --output "$TMP/x86_64-cap.kexe"
  "$ROOT/bin/kotoba" -M verify "$TMP/x86_64-cap.kexe"
  native_cap_check "$TMP/x86_64-cap.kexe" x86_64 7 42 SIGILL
  printf '%s\n' 'conformance: native x86_64 runtime vector passed under W^X loader'
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Linux-aarch64)
    cc -std=c11 -O2 -Wall -Wextra -Werror "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader"
    native_timeout_probe "$TMP/aarch64.kexe" aarch64
    native_report_check "$TMP/aarch64.kexe" aarch64
    "$ROOT/bin/kotoba" -M sign "$TMP/aarch64.kexe" --key "$TMP/signing-key.edn" \
      --not-before 1000 --expires 2000 --output "$TMP/aarch64.signed.kexe"
    attested_run_check "$TMP/aarch64.signed.kexe" aarch64
    native_sandbox_probe "$TMP/aarch64.kexe" aarch64 KEXE_FILESYSTEM_PROBE filesystem
    native_sandbox_probe "$TMP/aarch64.kexe" aarch64 KEXE_NETWORK_PROBE network
    native_sandbox_probe "$TMP/aarch64.kexe" aarch64 KEXE_PROCESS_PROBE process
    native_check "$TMP/aarch64.kexe" aarch64 score 12 -7 2
    native_check "$TMP/aarch64.kexe" aarch64 calc 21 20 4
    native_check "$TMP/aarch64.kexe" aarch64 relations 10 7 3
    native_check "$TMP/aarch64.kexe" aarch64 relations 13 3 3
    native_expect_trap "$TMP/aarch64.kexe" aarch64 calc SIGTRAP 20 0
    native_expect_trap "$TMP/aarch64.kexe" aarch64 calc SIGTRAP -9223372036854775808 -1
    "$ROOT/bin/kotoba" -M compile "$ROOT/examples/fuel.kotoba" --target aarch64 --output "$TMP/aarch64-fuel.kexe"
    "$ROOT/bin/kotoba" -M verify "$TMP/aarch64-fuel.kexe"
    native_check "$TMP/aarch64-fuel.kexe" aarch64 fact 3628800 10
    native_expect_trap "$TMP/aarch64-fuel.kexe" aarch64 forever SIGTRAP 0
    native_check "$TMP/aarch64-i64.kexe" aarch64 add -9223372036854775808 9223372036854775807 1
    native_check "$TMP/aarch64-i64.kexe" aarch64 subtract 9223372036854775807 -9223372036854775808 1
    native_check "$TMP/aarch64-i64.kexe" aarch64 multiply -2 9223372036854775807 2
    native_check "$TMP/aarch64-i64.kexe" aarch64 negate -9223372036854775808 -9223372036854775808
    native_check "$TMP/aarch64-i64.kexe" aarch64 choose 22 0 11 22
    native_check "$TMP/aarch64-i64.kexe" aarch64 choose 11 -1 11 22
    "$ROOT/bin/kotoba" -M compile "$ROOT/examples/capability.kotoba" --target aarch64 \
      --policy "$ROOT/examples/capability-policy.edn" --output "$TMP/aarch64-cap.kexe"
    "$ROOT/bin/kotoba" -M verify "$TMP/aarch64-cap.kexe"
    native_cap_check "$TMP/aarch64-cap.kexe" aarch64 7 42 SIGTRAP
    printf '%s\n' 'conformance: native aarch64 runtime vector passed under W^X loader'
    ;;
esac

printf '%s\n' 'conformance: wasm32 main=42, wasm32 score(-7,2)=12, x86_64 verified, aarch64 verified'
