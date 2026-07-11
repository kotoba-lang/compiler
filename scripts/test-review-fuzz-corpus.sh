#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TMP=${TMPDIR:-/tmp}/kotoba-review-corpus-test-$$
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/valid" "$TMP/unsafe" "$TMP/oversize" "$TMP/empty"

printf '%s' 'review candidate' >"$TMP/valid/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
REPORT=$("$ROOT/scripts/review-fuzz-corpus.sh" "$TMP/valid" --dry-run)
printf '%s' "$REPORT" | grep -q ':files 1'
printf '%s' "$REPORT" | grep -q ':new 1'

printf '%s' 'hostile' >"$TMP/unsafe/not-a-content-hash"
if "$ROOT/scripts/review-fuzz-corpus.sh" "$TMP/unsafe" --dry-run >/dev/null 2>&1; then
  echo "review-corpus test: unsafe filename accepted" >&2; exit 1
fi
rm "$TMP/unsafe/not-a-content-hash"
ln -s "$TMP/valid/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  "$TMP/unsafe/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
if "$ROOT/scripts/review-fuzz-corpus.sh" "$TMP/unsafe" --dry-run >/dev/null 2>&1; then
  echo "review-corpus test: symlink accepted" >&2; exit 1
fi

dd if=/dev/zero of="$TMP/oversize/cccccccccccccccccccccccccccccccccccccccc" \
  bs=1025 count=1 2>/dev/null
if "$ROOT/scripts/review-fuzz-corpus.sh" "$TMP/oversize" --dry-run >/dev/null 2>&1; then
  echo "review-corpus test: oversized input accepted" >&2; exit 1
fi
if "$ROOT/scripts/review-fuzz-corpus.sh" "$TMP/empty" --dry-run >/dev/null 2>&1; then
  echo "review-corpus test: empty corpus accepted" >&2; exit 1
fi

printf '%s\n' 'review-corpus: validation self-test passed'
