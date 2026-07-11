#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE=${1-}
MODE=${2---dry-run}
DESTINATION=$ROOT/fuzz/corpus/parser
[ -d "$SOURCE" ] || { echo "usage: review-fuzz-corpus.sh <artifact-corpus-dir> [--dry-run|--apply]" >&2; exit 2; }
[ "$MODE" = --dry-run ] || [ "$MODE" = --apply ] || { echo "invalid review mode" >&2; exit 2; }

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

contains_digest() {
  wanted=$1
  for existing in "$DESTINATION"/*; do
    [ -f "$existing" ] || continue
    [ "$(hash_file "$existing")" = "$wanted" ] && return 0
  done
  return 1
}

count=0 total=0 new=0 duplicate=0
for candidate in "$SOURCE"/*; do
  [ -e "$candidate" ] || continue
  [ -f "$candidate" ] && [ ! -L "$candidate" ] || {
    echo "review-corpus: non-regular input rejected" >&2; exit 2;
  }
  name=$(basename "$candidate")
  case "$name" in
    *[!0-9a-f]*|'')
      if [ -f "$DESTINATION/$name" ] && cmp -s "$candidate" "$DESTINATION/$name"; then
        size=$(wc -c <"$candidate" | tr -d ' ')
        count=$((count + 1)); total=$((total + size)); duplicate=$((duplicate + 1))
        [ "$count" -le 10000 ] && [ "$total" -le 1048576 ] || {
          echo "review-corpus: artifact exceeds file or byte limit" >&2; exit 2;
        }
        continue
      fi
      echo "review-corpus: unsafe input name: $name" >&2; exit 2
      ;;
  esac
  length=${#name}
  [ "$length" -eq 40 ] || [ "$length" -eq 64 ] || {
    echo "review-corpus: input name is not a content hash: $name" >&2; exit 2;
  }
  size=$(wc -c <"$candidate" | tr -d ' ')
  [ "$size" -le 1024 ] || { echo "review-corpus: input exceeds 1024 bytes: $name" >&2; exit 2; }
  count=$((count + 1)); total=$((total + size))
  [ "$count" -le 10000 ] && [ "$total" -le 1048576 ] || {
    echo "review-corpus: artifact exceeds file or byte limit" >&2; exit 2;
  }
  digest=$(hash_file "$candidate")
  if contains_digest "$digest"; then duplicate=$((duplicate + 1)); else new=$((new + 1)); fi
done

[ "$count" -gt 0 ] || { echo "review-corpus: empty corpus rejected" >&2; exit 2; }
printf '%s\n' "{:mode :${MODE#--} :files $count :bytes $total :new $new :duplicate $duplicate}"

if [ "$MODE" = --apply ] && [ "$new" -gt 0 ]; then
  KOTOBA_FUZZ_IMPORT_DIR="$SOURCE" KOTOBA_NATIVE_FUZZ_RUNS=20000 \
    "$ROOT/scripts/fuzz-native.sh"
  for candidate in "$SOURCE"/*; do
    digest=$(hash_file "$candidate")
    contains_digest "$digest" || cp "$candidate" "$DESTINATION/$digest"
  done
  printf '%s\n' "review-corpus: promoted $new sanitized inputs"
fi
