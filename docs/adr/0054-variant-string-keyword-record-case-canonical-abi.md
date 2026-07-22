# ADR 0054: Variant case wrapping a string/keyword-bearing record Canonical ABI identity

Status: accepted; variant-case-payload-kind-mixing identity slice implemented

## Decision

A direct identity export (the body is exactly its sole parameter, unchanged)
may use one sealed variant whose every case's payload is, independently,
a Canonical scalar (`i64`/`f32`/`f64`/`bool`), a sealed all-scalar record
(ADR 0052), or a sealed flat `string`/`keyword`-bearing record (ADR 0053) --
cases may freely mix all three kinds within one variant, with no
requirement that every record case be the same kind. This is exactly the
combination both ADR 0052's and ADR 0053's own "Remaining gaps" sections
named as the next-needed step -- ADR 0052: "strings or keywords inside a
case's record payload, so `state-v1`'s actual `entry`/`error` records
remain closed"; ADR 0053: "A variant case wrapping a record with
string/keyword fields (so `state-v1`'s actual `result` type -- a variant
over `entry`/`error`, both of which need this ADR's fields -- remains
unqualified end to end)". A case payload that is itself an ADR 0051
one-level-nested record, or another variant, remains rejected exactly as
before -- this ADR does not widen case-payload nesting depth, only the set
of *flat* leaf types a case's own record fields may use.

`state-v1.edn`'s actual `:result` type is a five-case variant
(`found`/`written`: `entry`; `missing`/`deleted`: `bool`; `error`: an
`error` record). This ADR's primary validating target is a concrete
structural slice of it -- `demo/state-result`, two cases,
`found: demo/entry` (`key: keyword, value: string, version: i64`, `entry`'s
exact real shape) and `missing: bool` -- matching the task's own
representative-slice guidance ("just `:found` wrapping the real `entry`
record plus `:missing bool`") rather than reproducing all five cases in one
pass. A second shape, `demo/mixed-outcome` (three cases:
`found: demo/mixed-entry` a string/keyword record, `tally: demo/mixed-tally`
an all-scalar record, `missing: bool`), demonstrates the broader claim that
a variant may mix all three case kinds freely, not just the two the
state-v1 slice itself needs.

Two separate, honestly-scoped layers needed checking to reach this, and
only one of them needed code changes.

**`kotoba.compiler.canonical-abi` needed no changes at all.** `variant-layout`
already computes each case's own layout via a plain recursive `layout*`
call on that case's `payload-type` (`(layout* payload-type schemas (conj
visited identity))`), and `layout*` already dispatches a record payload
type to `record-layout`, which already handles `:string`/`:keyword` fields
correctly since ADR 0053 (each such field's own `layout*` call already
produces the `size 8 alignment 4 flat [:i32 :i32]` leaf, unconditionally,
regardless of whether that record happens to be a variant case's payload or
a top-level export type). `variant-flatten-payload`'s fold over each case's
own `:flat` sequence is likewise payload-shape-agnostic: it was already
folding whatever flat sequence a case's own layout produced, one core value
at a time, with no assumption baked in about what produced that sequence.
Confirmed directly (`canonical-abi-test`,
`variant-with-string-keyword-record-case-flattens-payload-fields-
generically`): `canonical/layout` on a `[found: entry, missing: bool]`
variant already returns `:flat [:i32 :i32 :i32 :i32 :i32 :i64]` (the
leading `:i32` discriminant, then `found`'s own five-position flat sequence
-- keyword pointer, keyword length, string pointer, string length, `i64`
version -- joined against `missing`'s single `:i32` position at index 0
only, exactly as `join-core-type`'s existing three-line rule already
predicts) with zero lines of `canonical-abi.cljc` touched to make that
true. `component-wit/emit`/`type-text` also needed no changes: a variant
case's payload type is rendered the same generic way a record field type
already is, and `:string`/`:keyword` already mapped to WIT `string` since
before ADR 0053.

**`kotoba.compiler.component-core`'s admission predicate and WAT emitter
both needed real, coupled changes.** `sealed-scalar-variant-schema` (ADR
0052) is widened in place into `variant-case-schema`, backed by a new
`record-or-scalar-variant-case?` that accepts a payload type when it is a
scalar, `sealed-scalar-record` (ADR 0052's admitted case shape), or
`string-field-record-schema` (ADR 0053's admitted record shape, previously
never wired into variant-case admission). This was the only caller of the
old `sealed-scalar-variant-schema`, so widening it in place carries no risk
of a different WAT emitter silently claiming admission for a shape it
cannot handle -- the matching emitter change is made in the same commit.

The codegen side needed three coupled changes, all in
`kotoba.compiler.component-core`:

- **`variant-case-leaves`**: previously assumed one field == one flat
  position (`flat-index` was a plain field index), true for every field
  type ADR 0052 admitted (each is exactly one core value) but false for a
  string/keyword field (two core values, pointer then length). `flat-index`
  is now a running sum of each preceding field's own flat *width* (1 for a
  scalar, 2 for a string/keyword leaf, read off the field's own
  `:max-bytes` presence -- the same signal ADR 0051's `layout-leaves`
  already uses for the analogous nested-record case), so it still lines up
  exactly with `variant-flatten-payload`'s own per-case, per-position fold.
- **`variant-flat-value-expr`** (new, factored out of the unchanged-in-
  spirit `variant-payload-value-expr`): the single-position un-join step
  (`variant-coerce-ops` between the joined param's core type and the leaf's
  own wanted core type) is now reusable at an explicit `flat-index`, not
  only at a leaf's own single position, so a string/keyword leaf can call
  it twice -- once at its pointer position, once at `flat-index`+1 for its
  length position, both always wanting `:i32` (the Component Model's own
  representation for both halves of a string/keyword's flat pair, so no new
  entry in `variant-coerce-ops`' `join-core-type`-derived table was
  needed).
- **`variant-case-body`**: a string/keyword leaf now gets its own
  validation-then-store treatment inside the active case's own branch,
  parallel to the existing bool-range-check pattern -- length checked
  against the leaf's own `:max-bytes` (`keyword-value-byte-limit` 512 or
  `string-value-byte-limit` 65536, exactly as `layout*` already bounds
  them) and pointer range checked against the module's own linear-memory
  `capacity`, reusing `string-field-record-wat`'s own bounds-check shape
  verbatim; then stored as the pointer+length pair at the field's own
  offset/offset+4, the same linear-memory shape ADR 0040/0041/0053 already
  gave a bare string. Scoping this validation inside the case's own branch
  (not a shared pre-check before the discriminant dispatch) matters for the
  same reason the existing bool-range check is already branch-scoped:
  validating a shared joined position unconditionally, before knowing which
  case is active, would wrongly reject a legitimate payload belonging to a
  *different* case occupying that same flat position.

`variant-wat`'s memory sizing follows `string-field-record-wat`'s
generous-not-tight precedent (one extra 64 KiB page per string-like leaf,
not a tightly computed allocator), but keyed off the *widest single case*
(`max-string-leaves-per-case`, the maximum count of string-like leaves any
one case has), not a sum across every case -- only one case's own payload
is ever validated or stored per call, so only that one case's own
string-like leaf count needs headroom. A variant with no string-like leaf
in *any* case (every ADR 0052 shape already proven) takes the unchanged
one-page/65536-byte path, so this change does not regress memory sizing,
WAT shape, or behavior for any already-tested ADR 0052 shape -- confirmed
directly by the unmodified `variant-with-record-and-scalar-cases-identity-
is-admitted`/`variant-with-only-bool-and-f32-cases-identity-is-admitted`
tests (both `demo/entry`-free, no string-like leaf anywhere) still passing
unchanged.

Both shapes' preambles and `wasm-tools validate --features component-model`
passed. Both were run under locally available Wasmtime 42.0.1 with manual
`wasmtime run --invoke` calls, following the same manual-invocation
precedent as ADR 0048/0051/0052/0053 -- no automated Wasmtime test exists
in this repository:

- `demo/state-result` (the `state-v1`-slice shape, `found: entry`/
  `missing: bool`): `echo(found({key: "kotoba/status", value: "ready",
  version: 42}))`, `echo(missing(true))`, `echo(missing(false))`, and a
  full-range/multi-byte-UTF-8 case
  (`echo(found({key: "namae/漢字", value: "日本語のテスト🎉",
  version: -9223372036854775808}))`) each returned the same value
  unchanged.
- `demo/mixed-outcome` (three case kinds mixed: `found` a string/keyword
  record, `tally` an all-scalar record, `missing` a bare bool):
  `echo(found({key: "a", value: "b", version: 1}))`,
  `echo(tally({count: 99, ok: true}))`, and `echo(missing(false))` each
  returned the same value unchanged -- direct evidence that a single
  variant may mix all three admitted case-payload kinds, not only the two
  the `state-v1` slice itself needs.
- Both byte bounds trap for real when exceeded, exercised directly against
  `demo/state-result`'s own `found` case rather than only implemented
  defensively: a 513-byte `key` (one byte past `keyword-value-byte-limit`)
  traps (`wasm trap: wasm \`unreachable\` instruction executed`, process
  exit 134), a 512-byte `key` at exactly the bound succeeds, and a
  65537-byte `value` (one byte past `string-value-byte-limit`) traps the
  same way.

Fail-closed boundaries verified directly (`component-core/assert-
supported!` and `component-artifact/assert-scalar-slice!` both reject with
"component function body has no qualified Canonical lowering" before any
component encoding is attempted):

- a case wrapping a record with a field outside the whole admitted set
  (`i64`/`f32`/`f64`/`bool`/`string`/`keyword` -- e.g. `[:vector :i64]`),
  updated as the regression case in `variant-with-record-and-scalar-cases-
  identity-is-admitted` (previously a bare `:string` field there, which is
  now admitted by design, exactly mirroring ADR 0053's own update to its
  ADR 0043 regression test) and exercised again on `demo/state-result`'s
  own `entry` shape in the new
  `variant-with-string-keyword-record-and-scalar-cases-identity-is-
  admitted` test;
- a case wrapping a record that is itself one level nested (the ADR 0051
  shape), still rejected unchanged (this test predates ADR 0054 and was not
  modified);
- a case wrapping another variant, still rejected unchanged (same);
- a case schema whose sealed identity has drifted, still rejected unchanged
  (same).

Record projection, construction, update, `typed-cap-call` request/result
payloads, and provider components (`kotoba.compiler.component-composition`)
are all untouched by this change and remain restricted to top-level-scalar
records only, exactly as ADR 0043 through ADR 0053 left them. A variant
used as a record field, a variant nested inside another variant's case, a
string/keyword leaf inside an ADR 0051 one-level-nested record field
(whether or not that nested record is itself a variant case payload), and
a variant crossing a `typed-cap-call` request/result boundary all remain
closed -- `variant-case-schema` only ever admits a variant as the *direct*
top-level parameter/result type of an identity export, never as something
else's field or payload, unchanged from ADR 0052. None of the seven
capability kits' `:wasm-aot`/`:native-aot`/`:jit` qualification changes as
a result of this ADR; none of `resources/kotoba/lang/capability-kits/*.edn`
is modified -- `state-v1`'s actual five-case `result` type (this ADR closes
two representative cases, not all five; `error`'s own record shape is not
attempted here) still needs the request side, a real `typed-cap-call`
crossing carrying this shape, and a real production state provider, all of
which remain separately gapped. Native execution is not attempted here
either, consistent with ADR 0049/0051/0052/0053.

## Evidence

- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`): 31 tests, 137
  assertions, 0 failures, run against the pinned `wasm-tools 1.243.0`.
- full `clojure -M:test` suite: 410 tests, 4366 assertions, 0 failures, same
  pinned toolchain (baseline before this change, same toolchain: 407 tests,
  4349 assertions, 0 failures -- confirmed identical to ADR 0053's own
  recorded baseline before writing any code for this ADR).
- `wasm-tools validate --features component-model` on both produced variant
  identity components (`demo/state-result`, `demo/mixed-outcome`): passed.
- manual Wasmtime 42.0.1 invocation of both variant identity exports (seven
  successful round trips total across the two shapes, listed above,
  covering the exact `state-v1` `entry` shape, full i64 range, multi-byte
  UTF-8 in both a string and a keyword leaf in the same call, and all three
  admitted case-payload kinds mixed in one variant): each returned the
  input unchanged.
- manual Wasmtime 42.0.1 invocation exercising both byte bounds for real,
  inside a variant case for the first time (previously only exercised
  inside a top-level record export in ADR 0053): a 513-byte keyword field
  and a 65537-byte string field each trap (`unreachable`, process exit
  134); a 512-byte keyword field at exactly the bound succeeds.

## Remaining gaps

`state-v1`'s actual five-case `result` type is still not closed end to end
-- this ADR proves two representative cases (`found`/`missing`) plus a
separate three-case mix demonstrating general kind-mixing, not all five
`state-v1` cases in one shape (`written`/`deleted` are structurally
identical to `found`/`missing` and so add no new coverage; `error`'s own
record shape, distinct fields on a string/keyword-bearing record, is not
separately attempted here but is already covered by ADR 0053's own `entry`
evidence plus this ADR's `found` case -- the combination is the same kind
of leaf set). String/keyword leaves inside an ADR 0051 one-level-nested
record field (whether or not that nested record is itself a variant case
payload), a variant used as a record field, a variant nested inside another
variant's case, nested lists/tuples/options/results, more than three case
kinds' worth of memory-pressure combinations (`max-string-leaves-per-case`
was exercised at 0, 1, and 2 string-like leaves in one case here, not
higher), discriminant-out-of-range trapping still only implemented
defensively and not exercised through a real Wasmtime call (unchanged
limitation carried over from ADR 0052 -- `wasmtime run --invoke`'s WAVE
argument parser still only ever constructs values matching a known case), a
variant case's string/keyword leaves crossing a `typed-cap-call`
request/result boundary, and every production provider's real semantics
all remain closed. This ADR closes the specific combination named as the
next-needed step in both ADR 0052's and ADR 0053's own remaining-gaps
sections; it does not close the broader "nested structured values" gap
recorded as remaining gap 1 in ADR 0049, it does not make `state-v1`
executable end to end, and it does not move any capability kit's
qualification status.
