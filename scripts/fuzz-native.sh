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
FUZZ_SEED=${KOTOBA_NATIVE_FUZZ_SEED:-424242}
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
  FUZZ_LOG=$TMP/libfuzzer.log
  if ! ASAN_OPTIONS=detect_leaks=0:abort_on_error=1 \
       UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1 \
       "$TMP/kexe-parser-fuzz" "$TMP/corpus" "$LIMIT" -max_len=1024 \
       -timeout=2 -seed="$FUZZ_SEED" -artifact_prefix="$ARTIFACT_PREFIX" \
       -print_final_stats=1 -verbosity=1 >"$FUZZ_LOG" 2>&1; then
    cat "$FUZZ_LOG" >&2
    exit 1
  fi
  DONE=$(grep 'DONE.*cov:' "$FUZZ_LOG" | tail -1)
  COV=$(printf '%s' "$DONE" | sed -n 's/.* cov: \([0-9][0-9]*\).*/\1/p')
  FEATURES=$(printf '%s' "$DONE" | sed -n 's/.* ft: \([0-9][0-9]*\).*/\1/p')
  CORPUS=$(printf '%s' "$DONE" | sed -n 's/.* corp: \([0-9][0-9]*\)\/.*/\1/p')
  [ -n "$COV" ] && [ -n "$FEATURES" ] && [ -n "$CORPUS" ] || {
    cat "$FUZZ_LOG" >&2
    echo "native-fuzz: unable to parse libFuzzer coverage summary" >&2
    exit 1
  }
  BASELINE=$ROOT/fuzz/baselines/native-parser.edn
  MIN_COV=$(sed -n 's/.*:min-cov \([0-9][0-9]*\).*/\1/p' "$BASELINE")
  MIN_FEATURES=$(sed -n 's/.*:min-features \([0-9][0-9]*\).*/\1/p' "$BASELINE")
  MIN_CORPUS=$(sed -n 's/.*:min-corpus \([0-9][0-9]*\).*/\1/p' "$BASELINE")
  EXPECTED_SOURCE=$(sed -n 's/.*:loader-source-sha256 "\([0-9a-f][0-9a-f]*\)".*/\1/p' "$BASELINE")
  ACTUAL_SOURCE=$(sha256sum "$ROOT/tools/kexe_loader.c" | awk '{print $1}')
  [ -n "$MIN_COV" ] && [ -n "$MIN_FEATURES" ] && [ -n "$MIN_CORPUS" ] &&
    [ ${#EXPECTED_SOURCE} -eq 64 ] || {
      echo "native-fuzz: malformed reviewed coverage baseline" >&2; exit 1;
    }
  [ "$ACTUAL_SOURCE" = "$EXPECTED_SOURCE" ] || {
    echo "native-fuzz: coverage baseline does not match loader source" >&2; exit 1;
  }
  [ "$COV" -ge "$MIN_COV" ] && [ "$FEATURES" -ge "$MIN_FEATURES" ] &&
    [ "$CORPUS" -ge "$MIN_CORPUS" ] || {
      echo "native-fuzz: coverage regression: cov=$COV/$MIN_COV features=$FEATURES/$MIN_FEATURES corpus=$CORPUS/$MIN_CORPUS" >&2
      exit 1
    }
  SUMMARY="{:format :kotoba.fuzz-coverage/v1 :engine :libfuzzer :seed $FUZZ_SEED :cov $COV :features $FEATURES :corpus $CORPUS :limit \"$LABEL\"}"
  printf '%s\n' "$SUMMARY"
  if [ -n "$OUTPUT_DIR" ]; then printf '%s\n' "$SUMMARY" >"$OUTPUT_DIR/coverage.edn"; fi
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
  SUMMARY="{:format :kotoba.fuzz-coverage/v1 :engine :deterministic-sanitized :cases $RUNS}"
  printf '%s\n' "$SUMMARY"
  if [ -n "$OUTPUT_DIR" ]; then printf '%s\n' "$SUMMARY" >"$OUTPUT_DIR/coverage.edn"; fi
fi

printf '%s\n' "native-fuzz: $LABEL $MODE parser fuzz passed"
