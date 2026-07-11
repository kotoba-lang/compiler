#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-native-fuzz-$$
mkdir -p "$TMP/corpus"
cp "$ROOT"/fuzz/corpus/parser/* "$TMP/corpus/"
if [ -n "${KOTOBA_FUZZ_IMPORT_DIR:-}" ]; then
  count=0 total=0
  for candidate in "$KOTOBA_FUZZ_IMPORT_DIR"/*; do
    [ -e "$candidate" ] || continue
    [ -f "$candidate" ] && [ ! -L "$candidate" ] || {
      echo "native-fuzz: imported corpus contains a non-regular file" >&2; exit 2;
    }
    name=$(basename "$candidate")
    case "$name" in
      *[!0-9a-f]*|'')
        if [ -f "$ROOT/fuzz/corpus/parser/$name" ] &&
           cmp -s "$candidate" "$ROOT/fuzz/corpus/parser/$name"; then
          continue
        fi
        echo "native-fuzz: unsafe corpus name: $name" >&2; exit 2
        ;;
    esac
    length=${#name}
    [ "$length" -eq 40 ] || [ "$length" -eq 64 ] || {
      echo "native-fuzz: corpus name is not a content hash: $name" >&2; exit 2;
    }
    size=$(wc -c <"$candidate" | tr -d ' ')
    [ "$size" -le 1024 ] || { echo "native-fuzz: corpus input exceeds 1024 bytes" >&2; exit 2; }
    count=$((count + 1)); total=$((total + size))
    [ "$count" -le 10000 ] && [ "$total" -le 1048576 ] || {
      echo "native-fuzz: imported corpus exceeds review limits" >&2; exit 2;
    }
    cp "$candidate" "$TMP/corpus/$name"
  done
fi
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
    "$TMP/kexe-parser-fuzz" "$RUNS" "$TMP"/corpus/*
  MODE=deterministic-sanitized
  LABEL=$RUNS
fi

printf '%s\n' "native-fuzz: $LABEL $MODE parser fuzz passed"
