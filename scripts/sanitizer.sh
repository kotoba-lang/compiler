#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-loader-sanitizer-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) TARGET=x86_64; ISA=x86_64 ;;
  Darwin-arm64|Linux-aarch64) TARGET=aarch64; ISA=aarch64 ;;
  *) echo "sanitizer: unsupported host" >&2; exit 2 ;;
esac

cc -std=c11 -O1 -g -Wall -Wextra -Werror \
  -DKEXE_SANITIZER_TEST \
  -fsanitize=address,undefined -fno-omit-frame-pointer \
  "$ROOT/tools/kexe_loader.c" -o "$TMP/kexe-loader-sanitized"

printf '%s\n' '(defn main [] 42)' >"$TMP/program.kotoba"
"$ROOT/bin/kotoba" -M compile "$TMP/program.kotoba" --target "$TARGET" \
  --output "$TMP/program.kexe" >/dev/null
META=$("$ROOT/bin/kotoba" -M extract-native "$TMP/program.kexe" --symbol main \
  --output "$TMP/program.bin")
OFFSET=$(printf '%s' "$META" | sed -n 's/.*:offset \([0-9][0-9]*\).*/\1/p')

ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
  "$TMP/kexe-loader-sanitized" "$TMP/program.bin" "$OFFSET" 0 "$ISA" - \
  >"$TMP/result.out" 2>"$TMP/result.err"
[ "$(cat "$TMP/result.out")" = 42 ]
[ ! -s "$TMP/result.err" ]

expect_usage_rejection() {
  if ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
     UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
     "$TMP/kexe-loader-sanitized" "$@" >"$TMP/reject.out" 2>"$TMP/reject.err"; then
    echo "sanitizer: malformed loader input was accepted: $*" >&2
    exit 1
  fi
  if grep -Eq 'AddressSanitizer|runtime error:|UndefinedBehaviorSanitizer' "$TMP/reject.err"; then
    cat "$TMP/reject.err" >&2
    exit 1
  fi
}

expect_usage_rejection
expect_usage_rejection "$TMP/program.bin" "" 0 "$ISA" -
expect_usage_rejection "$TMP/program.bin" -1 0 "$ISA" -
expect_usage_rejection "$TMP/program.bin" +1 0 "$ISA" -
expect_usage_rejection "$TMP/program.bin" 18446744073709551616 0 "$ISA" -
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 18446744073709551616 "$ISA" -
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 0 invalid-isa -
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 0 "$ISA" 256
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 0 "$ISA" 7,
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 1 "$ISA" - 9223372036854775808
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 1 "$ISA" - -9223372036854775809
expect_usage_rejection "$TMP/program.bin" "$OFFSET" 1 "$ISA" - +1

printf '%s\n' "sanitizer: $ISA valid execution and malformed input corpus passed"
