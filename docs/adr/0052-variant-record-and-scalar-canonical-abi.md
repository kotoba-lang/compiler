# ADR 0052: Variant-of-record-and-scalar Canonical ABI identity

Status: accepted; variant identity slice implemented

## Decision

A direct identity export (the body is exactly its sole parameter, unchanged)
may use one sealed variant whose every case's payload is a Canonical scalar
(`i64`/`f32`/`f64`/`bool`) or a sealed all-scalar record (the ADR 0043 flat
shape; a case payload that is itself an ADR 0051 one-level-nested record, or
another variant, is rejected before component encoding). This is the shape
named as the next-needed gap in ADR 0051's own "Remaining gaps" section --
"variants (including a variant case wrapping a record, the shape
`state-v1`'s result actually needs)" -- narrowed to scalar/flat-record case
payloads only; `state-v1`'s actual `entry`/`error` records also contain
`:string`/`:keyword` fields, which remain closed regardless (ADR 0041/0042
only cover bounded string *parameters*, not record fields), so this ADR does
not close `state-v1` end to end.

A variant's Canonical ABI shape is genuinely two different layouts, not one
reused for two purposes the way a record's is. `kotoba.compiler.canonical-abi`
gains both: `variant-layout` computes the in-memory union (discriminant byte
width from `discriminant-byte-size` -- u8 up to 256 cases, matching the
Component Model spec's `discriminant_type` -- plus an aligned payload area
sized to the widest case, mirroring `elem_size_variant`/`alignment_variant`)
used to plan the indirect-result area; `variant-flatten-payload` computes the
*joined* component-flat core signature (one core wasm value per position any
case's own flattened payload reaches, folded left to right across cases via
`join-core-type`) used to plan the wasm core function's actual parameters,
mirroring the spec's `flatten_variant`/`join` exactly -- identical types at a
shared position need no coercion, an `i32`/`f32` mismatch (a bool case
sharing a position with an f32 case) still fits in `i32`, and every other
mismatch widens to `i64`. Both layouts live side by side on the same
`variant-layout` layout map (`:size`/`:alignment`/`:payload-offset` for the
union, `:flat` for the join) rather than reusing one `:flat` for both
purposes; `canonical-abi/layout*`'s existing `:ref` and inline dispatch now
resolve to `variant-layout` instead of `record-layout` whenever the
referenced or inline schema is a `:variant`, so every existing record
consumer is unaffected.

`kotoba.compiler.component-core`'s new `sealed-scalar-variant-schema`/
`variant-identity-function?` mirror `nested-scalar-record-schema`/
`nested-record-identity-function?`'s admission shape. The new `variant-wat`
emitter receives the caller's already-flattened, already-joined core values
as plain wasm parameters (an `i32` discriminant plus one value per joined
payload position -- exactly what a real `canon lower` produces for this
variant type, so nothing about the parameter list is invented), range-checks
the discriminant, allocates the union result area, stores the discriminant,
and then -- in exactly the branch a generated `if`/`else` chain selects by
discriminant value -- un-joins each of that case's own leaves back to its
natural type and stores it (`variant-coerce-ops`, the exact inverse of the
spec's `lift_flat_variant` `CoerceValueIter` table: `i32`↔`f32` via
`f32.reinterpret_i32`, `i64`→`i32` via `i32.wrap_i64`, `i64`→`f32` via both in
sequence, `i64`→`f64` via `f64.reinterpret_i64`). This table is exhaustive
over what `join-core-type` can actually produce in this codebase (only `i32`
or `i64` are ever a join *result*, never `f32`/`f64`), not a hand-picked
subset of the spec's full table. Bool leaves are range-validated (`i32.gt_u`
against 1) after coercion, inside their own case branch only -- validating a
shared joined position unconditionally, before knowing which case is active,
would wrongly reject a legitimate non-boolean payload from a different case
occupying the same position. WIT generation and Canonical export planning
needed no changes: `component-wit/emit` already emitted `variant` schema text
generically (proven by the pre-existing `component-wit-test` fixture, whose
variant case payload is itself a record), and `canonical-abi/export-plan`
already treats any layout's `:flat` polymorphically for `core-params` and the
`indirect-result?` decision.

Two case shapes were built, `wasm-tools`-validated, and manually run under
Wasmtime 42.0.1 (`wasmtime run --invoke '<function>(<case>(<payload>))'
<component>.wasm`, following the same manual-invocation precedent as ADR
0048/0051 -- no automated Wasmtime test exists in this repository):

- `demo-outcome`, four cases (`found: demo-entry` record with `i64`/`f64`/
  `bool` fields, `missing: bool`, `failed: f32`, `short: demo-short` record
  with `i64`/`bool` fields). Chosen so `short`'s trailing `bool` field forces
  the joined payload position `found`'s `f64` field occupies to widen to
  `i64`, exercising direct passthrough, `i64`→`i32` wrap (`missing`, and
  `short`'s own bool field), `i64`→`f32` wrap-then-reinterpret (`failed`), and
  `i64`→`f64` reinterpret (`found`'s `ratio` field) all in one shape.
  `echo(found({code: 7, ratio: 1.5, present: true}))`,
  `echo(missing(true))`, `echo(missing(false))`, `echo(failed(2.5))`, and
  `echo(short({code: 42, flag: false}))` each returned the same value
  unchanged, including a full-range check
  (`found({code: -9223372036854775808, ratio: -0.5, present: false})`).
- `demo-flag-or-ratio`, two cases (`urgent: bool`, `weight: f32`), neither
  ever touching `i64`/`f64`, dedicated to the one join outcome the first
  shape cannot reach: the spec's `i32`/`f32` special case, and its
  single-instruction `f32.reinterpret_i32` coercion (no wrap needed).
  `echo(urgent(true))`, `echo(urgent(false))`, and `echo(weight(3.5))` each
  returned the same value unchanged.

Both components' preambles and `wasm-tools validate --features
component-model` passed. As with ADR 0048/0051, this is implementation
evidence only; the pinned baseline still requires Wasmtime major 43 or newer.
Discriminant-out-of-range trapping (`i32.ge_u` against the case count) is
implemented the same defensive way the existing bool-range checks are, but --
like those -- is not independently exercised through Wasmtime here; `wasmtime
run --invoke`'s own WAVE argument parser only ever constructs values that
already match a known case, so there is no way to drive an out-of-range
discriminant through that interface.

Fail-closed boundaries verified directly (`component-core/assert-supported!`
and `component-artifact/assert-scalar-slice!` both reject with "component
function body has no qualified Canonical lowering" before any component
encoding is attempted):

- a case wrapping a record with a non-scalar (string) field;
- a case wrapping a record that is itself one level nested (the ADR 0051
  shape a variant case payload is *not* extended to in this slice);
- a case wrapping another variant;
- a case schema whose sealed identity has drifted from the schema table;
- a computed variant body (anything other than a bare parameter passthrough).

At the pure layout level (`canonical-abi-test`), a variant schema whose own
sealed identity does not match its reference key rejects with "variant
reference has no matching schema identity" (mirroring the equivalent record
check), and a variant schema recursing through one of its own case payloads
rejects with "recursive schema has no bounded Canonical ABI layout" (the same
message and the same `visited`-set mechanism the record path already used,
now shared by both).

Record projection, construction, update, `typed-cap-call` request/result
payloads, and provider components (`component-composition`) are all
untouched by this change and remain restricted to top-level-scalar records
only, exactly as ADR 0043 through ADR 0051 left them. A variant used as a
record field, a variant nested inside another variant's case, and a variant
crossing a `typed-cap-call` request/result boundary all remain closed --
`sealed-scalar-variant-schema` only ever admits a variant as the *direct*
top-level parameter/result type of an identity export, never as something
else's field or payload. None of the seven capability kits'
`:wasm-aot`/`:native-aot`/`:jit` qualification changes as a result of this
ADR; none of `resources/kotoba/lang/capability-kits/*.edn` is modified --
`state-v1`'s actual request/result types still need string/keyword record
fields this ADR does not touch. Native execution is not attempted here
either, consistent with ADR 0049/0051.

## Evidence

- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`): 25 tests, 103
  assertions, 0 failures, run against the pinned `wasm-tools 1.243.0`.
- full `clojure -M:test` suite: 404 tests, 4332 assertions, 0 failures, same
  pinned toolchain.
- `wasm-tools validate --features component-model` on both produced variant
  identity components: passed.
- manual Wasmtime 42.0.1 invocation of both variant identity exports (nine
  case invocations total across the two shapes, listed above): each returned
  the input unchanged.

## Remaining gaps

Nested lists, tuples, options, results, a variant case wrapping an ADR 0051
one-level-nested record or another variant, strings or keywords inside a
case's record payload (so `state-v1`'s actual `entry`/`error` records remain
closed), a variant used as a record field or nested inside a `typed-cap-call`
request/result payload, more than four cases exercised (the `:wasm-aot`
admission path itself has no case-count ceiling below `value.cljc`'s
`variant-case-limit`, but only four- and two-case shapes were actually built
and run here), discriminant-out-of-range trapping exercised through a real
Wasmtime call rather than only implemented defensively, and every production
provider's real semantics all remain closed. This ADR closes one further
narrow, honestly-scoped slice of the "nested structured values" gap recorded
as remaining gap 1 in ADR 0049 and named explicitly in ADR 0051's own
remaining gaps; it does not close that gap, it does not make `state-v1`
executable end to end, and it does not move any capability kit's
qualification status.
