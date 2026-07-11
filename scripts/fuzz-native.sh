#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-native-fuzz-$$
mkdir -p "$TMP/corpus"
cp "$ROOT"/fuzz/corpus/parser/* "$TMP/corpus/"
OUTPUT_DIR=${KOTOBA_FUZZ_ARTIFACT_DIR:-}
if [ -n "$OUTPUT_DIR" ]; then
  mkdir -p "$OUTPUT_DIR"
  ARTIFACT_PREFIX=$OUTPUT_DIR/crash-
else
  ARTIFACT_PREFIX=$TMP/crash-
fi

cleanup() {
  code=$?
  trap - EXIT HUP INT TERM
  if [ -n "$OUTPUT_DIR" ]; then
    mkdir -p "$OUTPUT_DIR/corpus"
    cp -R "$TMP/corpus/." "$OUTPUT_DIR/corpus/" || true
  fi
  rm -rf "$TMP"
  exit "$code"
}
trap cleanup EXIT HUP INT TERM

RUNS=${KOTOBA_NATIVE_FUZZ_RUNS:-20000}
if [ "$(uname -s)" = Linux ]; then
  clang -std=c11 -O1 -g -Wall -Wextra \
    -fsanitize=fuzzer,address,undefined -fno-omit-frame-pointer \
    -I"$ROOT/tools" "$ROOT/tools/kexe_parser_fuzz.c" -o "$TMP/kexe-parser-fuzz"
  if [ -n "${KOTOBA_NATIVE_FUZZ_SECONDS:-}" ]; then
    LIMIT=-max_total_time=$KOTOBA_NATIVE_FUZZ_SECONDS
    LABEL="${KOTOBA_NATIVE_FUZZ_SECONDS}s"
  else
    LIMIT=-runs=$RUNS
    LABEL=$RUNS
  fi
  ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
    "$TMP/kexe-parser-fuzz" "$TMP/corpus" "$LIMIT" -max_len=1024 \
    -timeout=2 -artifact_prefix="$ARTIFACT_PREFIX" \
    -print_final_stats=1 -verbosity=0
  MODE=coverage-guided
else
  clang -std=c11 -O1 -g -Wall -Wextra -DKEXE_STANDALONE_FUZZ \
    -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I"$ROOT/tools" "$ROOT/tools/kexe_parser_fuzz.c" -o "$TMP/kexe-parser-fuzz"
  ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
  UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
    "$TMP/kexe-parser-fuzz" "$RUNS"
  MODE=deterministic-sanitized
  LABEL=$RUNS
fi

printf '%s\n' "native-fuzz: $LABEL $MODE parser fuzz passed"
