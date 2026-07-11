#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-native-fuzz-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/corpus"
cp "$ROOT"/fuzz/corpus/parser/* "$TMP/corpus/"

RUNS=${KOTOBA_NATIVE_FUZZ_RUNS:-20000}
if [ "$(uname -s)" = Linux ]; then
  clang -std=c11 -O1 -g -Wall -Wextra \
    -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer \
    -I"$ROOT/tools" "$ROOT/tools/kexe_parser_fuzz.c" -o "$TMP/kexe-parser-fuzz"
  ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
    "$TMP/kexe-parser-fuzz" "$TMP/corpus" -runs="$RUNS" -max_len=1024 \
    -timeout=2 -print_final_stats=1 -verbosity=0
  MODE=coverage-guided
else
  clang -std=c11 -O1 -g -Wall -Wextra -DKEXE_STANDALONE_FUZZ \
    -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I"$ROOT/tools" "$ROOT/tools/kexe_parser_fuzz.c" -o "$TMP/kexe-parser-fuzz"
  ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
    "$TMP/kexe-parser-fuzz" "$RUNS"
  MODE=deterministic-sanitized
fi

printf '%s\n' "native-fuzz: $RUNS $MODE parser runs passed"
