# ADR 0057: String/keyword-bearing variant case crosses `typed-cap-call`

Status: accepted; string/keyword-bearing variant-case capability-call slice implemented

## Decision

A direct `typed-cap-call` may now use one sealed variant whose every case's
payload is a bare Canonical scalar (ADR 0055), a sealed all-scalar record
(ADR 0052, admitted for a capability boundary by ADR 0056), *or* -- new
here -- a sealed flat record whose fields are each a Canonical scalar or a
bounded `string`/`keyword` leaf (the ADR 0053 shape), as its same-identity
request and result. This closes the exact gap both ADR 0055's and ADR
0056's own "Remaining gaps" sections named and left unattempted: "string/
keyword data crossing a capability-call boundary at all -- not merely
inside a variant case -- no case kind, and no plain `record-capability-call`
either, has ever exercised this."

`demo/cap-outcome` (`found: cap-entry`/`missing: bool`), this ADR's own
concrete evidence fixture, uses `cap-entry = {key: keyword, value: string,
version: i64}` -- `state-v1`'s own real `entry` record, verbatim, not a
structural stand-in narrower than the real shape the way ADR 0056's
`demo/state-outcome` (`key: i64, value: i64`) deliberately was. This is
the closest any ADR in this chain has come to `state-v1`'s literal shape:
its `found`/`written` cases and its `error` case all use exactly this
`entry`/`error` record kind, and this ADR proves that record kind, inside a
variant case, crosses a real `typed-cap-call` boundary. `state-v1` is still
not closed end to end -- see "Remaining gaps" below.

## Scope

**What is new, precisely.** `kotoba.compiler.component-core/variant-
capability-case?` (the predicate governing what a capability-crossing
variant's case payload may be) is widened by exactly one more disjunct:
`string-field-record-schema` (ADR 0053's own admission function, already
used by the *identity-export* path since ADR 0053/0054 and by nothing else
until now). `kotoba.compiler.component-composition/variant-record-case-
schema` (the provider-side WIT-generation admission twin) is widened the
same way, via a new `record-field-wit-type` map (`variant-case-wit-type`
plus `:string`/`:keyword` -> WIT `string`). Both widenings are exactly the
kind of "no case kind ever admitted a record field wider than
`sealed-scalar-record`'s set" gap named in the two prior ADRs' texts, and
nothing else.

**What is deliberately *not* admitted.** A bare `:string`/`:keyword` case
payload (a case whose own payload type is `:string`/`:keyword` directly,
with no record wrapper) remains fail-closed -- only a record *field*
carries string/keyword data across this boundary in this slice, matching
`state-v1`'s own actual shape (every one of its cases wraps either a bare
`:bool` or a record; none wraps a bare string). A case wrapping an ADR
0051 one-level-nested record (a field that is itself a record reference,
rather than a scalar or string/keyword leaf) also remains fail-closed,
exactly as before -- `string-field-record-schema` itself only ever admitted
one flat level, unchanged by this ADR. `record-capability-call` (a bare
record, not wrapped in a variant, as the whole capability request/result)
is untouched and still admits `sealed-scalar-record` only -- widening that
separate path is not attempted here, deliberately, because `state-v1`'s own
request and result are both variants, never a bare record, so it is not
needed to reach the motivating target.

**The WAT-emission work this ADR required, and why it was *not* zero
lines, unlike ADR 0056's own "zero lines of WAT-emission code changed"
finding.** ADR 0054 already extended `variant-case-leaves`/`variant-case-
body`/`variant-case-chain` (the in-branch store/validation logic a
capability-crossing variant's provider reuses unmodified) to handle a
`:max-bytes` leaf, for the *identity-export* path. `variant-capability-
provider-wat` already reused that case-chain verbatim, so the per-leaf
store/validation logic needed zero changes here either. What did need
changing, and is the substantive new engineering this ADR contributes, is
memory management on both sides of the crossing:

- `variant-capability-provider-wat`'s result pointer was a fixed constant
  address (`i32.const 8`, ADR 0055/0056's own choice, correct only because
  no other allocation ever happened in that call). Once a case's payload
  can carry a string/keyword leaf, the Canonical ABI's own generated
  cross-instance string-lowering glue calls this module's *exported*
  `cm32p2_realloc` itself -- once per string-like leaf in the active
  request case -- to copy each leaf's bytes into this module's own memory,
  *before* this module's own function body runs at all. A fixed-address
  realloc would silently collide with that glue-driven allocation: the
  copied request bytes and this function's own result struct would land at
  the identical address, corrupting whichever was written second. This ADR
  replaces the fixed address with a real, capacity-bounded bump allocator
  (`bounded-bump-realloc-wat`, the same allocator body `variant-wat`
  already uses for its own single-module string leaves, factored out so
  both `variant-capability-wat` and `variant-capability-provider-wat` can
  use it) and routes the result-struct allocation through it
  (`call $realloc`, exactly the pattern every other struct-producing WAT
  emitter in this namespace already follows). Because a bump allocator's
  own construction guarantees sequential, non-overlapping addresses
  regardless of call order, this composes correctly whether the glue's
  string-copy call happens before this module's own result allocation or
  not -- no case-specific ordering logic was needed, only routing through
  one shared allocator.
- Both `variant-capability-wat` (application side) and `variant-
  capability-provider-wat` (provider side) previously declared a fixed
  one-page (65536-byte) memory, sufficient when no string ever needed
  headroom. `variant-capability-string-headroom` reuses `variant-wat`'s own
  generous-not-tight sizing formula (keyed off the widest single case's own
  string-like leaf count, since only one case's payload is ever active per
  call) so both sides now grow their page count to hold a copied string's
  bytes.
- `variant-capability-wat`'s own realloc was previously an *unbounded* bump
  pointer (no capacity check at all) -- harmless when it was ever called
  exactly once with a size known in advance (the result struct's own
  fixed size), genuinely unsafe once an attacker/caller-controlled string
  length can flow into a realloc call. This ADR replaces it with the same
  bounded allocator, so an oversized string traps (`unreachable`) rather
  than silently writing past the module's declared memory.

A case with no string-like leaf at all is unaffected in observable
behavior by any of this: with no extra realloc call preceding it, the bump
allocator's first call still returns the same fixed address 8 ADR
0055/0056 hard-coded (8 is a multiple of every alignment this codebase's
Canonical ABI layouts produce), and the memory page count formula still
returns exactly 1 page. This is confirmed directly: the ADR 0055
(`demo/flag-or-ratio`, bool/f32, no records at all) and ADR 0056
(`demo/state-outcome`, scalar-only `entry` record) fixtures were rebuilt
and re-run through real Wasmtime execution after this change (not merely
re-run through the existing test suite) and round-tripped identically to
their own prior ADRs' recorded results.

`kotoba.compiler.component-wit.clj` needed no changes at all, continuing
the finding ADR 0054/0056 already reached: its generic `[:ref name]`
rendering and `typed-cap-call` body walk already treat a variant
request/result exactly like a record one regardless of what the variant's
cases contain.

## Evidence

All of the following used the *actual, non-monkeypatched* code path
(`component-core/emit` for the application half, `component-composition/
package-variant-identity-provider` for the provider half, `component-
composition/compose-closed` for `wac plug` + `wasm-tools validate`), not
hand-assembled WAT, and every Wasmtime invocation below ran the **composed**
(application + provider) component, not either half alone:

- `demo/cap-outcome` (`found: cap-entry`/`missing: bool`, `cap-entry` =
  `state-v1`'s own real `{key: keyword, value: string, version: i64}`
  shape verbatim): composed successfully, `wasm-tools validate --features
  component-model` passed, and real Wasmtime 42.0.1 execution round-tripped
  `found({key: "kotoba/status", value: "ready", version: 42})`,
  `found({key: "a", value: "", version: -9223372036854775808})` (full
  negative `i64` range plus an empty string leaf),
  `found({key: "namae/漢字", value: "日本語のテスト🎉", version:
  9223372036854775807})` (full positive `i64` range plus multi-byte UTF-8
  in both a keyword and a string leaf in the same call), `missing(true)`,
  and `missing(false)`, each returned unchanged.
- Both Kotoba byte bounds trap for real, exercised as genuine Wasmtime
  traps rather than only implemented defensively, now crossing a real
  cross-component boundary rather than staying inside one module: a
  513-byte `key` (one byte past `keyword-value-byte-limit`, 512) traps
  (`wasm trap: wasm \`unreachable\` instruction executed`, process exit
  134); a 512-byte `key` at exactly the bound succeeds; a 65537-byte
  `value` (one byte past `string-value-byte-limit`, 65536) traps the same
  way.
- A degenerate shape with no scalar field at all inside the record case
  (`demo/cap-tag-outcome`, `found: {tag: keyword}`/`missing: bool`),
  mirroring ADR 0053's own degenerate single-leaf test: composed,
  validated, and round-tripped `found({tag:
  "single-field-keyword-only"})` and `missing(true)` unchanged, confirming
  the crossing does not depend on a scalar field also being present.
- No-string-leaf regression check: the ADR 0055 fixture (`demo/flag-or-
  ratio`, bool/f32 join) and the ADR 0056 fixture (`demo/state-outcome`,
  scalar-only `entry`) were rebuilt through the *changed* code
  (`variant-capability-wat`/`variant-capability-provider-wat` now route
  their result allocation through the new bounded allocator and sizing
  formula) and re-run through real Wasmtime execution: `urgent(true)`,
  `urgent(false)`, `weight(2.75)`,
  `found({key: -9223372036854775808, value: 9223372036854775807})`, and
  `missing(true)` each still round-tripped unchanged, byte-for-byte
  matching ADR 0055/0056's own recorded results -- confirming the new
  allocator/sizing logic does not change behavior for a case shape that
  needs no string headroom.
- `wasm-tools validate --features component-model` passed on every
  composed component built above.
- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`,
  `backend-qualification-test`): 42 tests, 212 assertions, 0 failures.
- full `clojure -M:test` suite: 425 tests, 4463 assertions, 0 failures,
  against the pinned `wasm-tools 1.243.0` and `wac-cli 0.10.1` (unchanged
  pins -- this ADR needed no toolchain change, unlike ADR 0056).
- `clojure -M -m kotoba.compiler.backend-qualification verify wasmtime`:
  `:manifest-gate :passed`, unchanged `:gaps` list, confirming no
  capability kit's qualification moved as a side effect.

Fail-closed boundaries re-verified directly by test after the widening,
both at the KIR-admission layer (`component-core/emit`, "no qualified
Canonical lowering") and independently at the provider layer
(`component-composition/package-variant-identity-provider`, "scalar,
sealed all-scalar record, or sealed string/keyword-bearing record cases"),
so each boundary still fails closed at both layers rather than only
surfacing as a downstream `wac` encoding error:

- a case wrapping an ADR 0051 one-level-nested record (a field itself a
  record reference, one level deeper than the newly admitted flat
  string/keyword-bearing shape);
- a bare `:string`/`:keyword` case payload with no record wrapper;
- different request/result variant identities (unchanged from ADR
  0055/0056, re-run by the existing tests, matching `record-capability-
  call`'s own same-identity discipline);
- a computed capability request and an unknown capability id (unchanged,
  re-run by the existing tests).

## Remaining gaps

`state-v1`'s actual request (`get`/`put`/`delete`, each a record with
string/keyword fields) and result (`found`/`written`: the string/keyword-
bearing `entry`; `missing`/`deleted`: bare `bool`; `error`: a similarly
string/keyword-bearing record) are still not closed *end to end* by this
ADR, for two remaining reasons, in order: (1) `state-v1`'s request and
result are two *different* variant identities (`kotoba.state/request` and
`kotoba.state/result`), while every capability-call ADR in this chain,
including this one, has only ever proven the *same*-identity case (request
and result being the identical sealed type) -- a wiring-only identity
provider cannot answer a `get` request with a `found`/`missing` result of
a genuinely different shape, since an identity provider by construction
returns exactly what it received; closing this needs either a
non-identity (real) provider, or extending the admission/wiring discipline
to a differently-shaped request/result pair, neither attempted here. (2) A
real production `state` provider is still unattempted -- every provider in
this ADR chain, including this one, is a wiring-only identity fixture;
`src/kotoba/compiler/provider/state.cljc`'s bounded 256-entry reference
semantics remain entirely unwired to any Component-level capability call.
Both gaps were already named, in this order, in ADR 0055's and ADR 0056's
own "Remaining gaps" sections as the two steps left after string/keyword
crossing; this ADR closes only the string/keyword-crossing step that
preceded them.

Also still closed: a bare `:string`/`:keyword` case payload with no record
wrapper (as opposed to a record field, which this ADR admits); a variant
case wrapping an ADR 0051 one-level-nested record; `record-capability-call`
(a bare record, not variant-wrapped, as a capability request/result) with
string/keyword fields -- neither side of that separate path was touched by
this ADR: `record-capability-wat` (application side) still admits
`sealed-scalar-record`'s scalar-only field set only, and its own realloc,
while already a bump allocator (not a fixed address), is still unbounded
(no capacity check); its provider counterpart
(`component-composition/record-provider-wat`) still allocates its result
area at the same fixed constant address `i32.const 8` ADR 0055/0056's
variant providers used before this ADR -- so admitting a string/keyword
field on that path would hit the identical realloc-collision hazard this
ADR fixed for the variant path, unaddressed there; lists/tuples/options/
results; multiple capability calls; multiple exported functions; and every
production provider's real semantics for all nine capabilities. No
capability kit's `:wasm-aot`/`:native-aot`/`:jit` qualification changes as
a result of this ADR; none of `resources/kotoba/lang/capability-kits/*.edn`
is modified.
