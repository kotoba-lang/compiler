# ADR 0068: Structural `option<T>`/`result<T, E>` Canonical ABI layout plan

Status: accepted; layout-plan slice implemented (type-level plan only, no
codegen)

## Decision

`kotoba.compiler.canonical-abi/layout` gains two more admitted schema
shapes, `[:option item-descriptor]` and `[:result ok-descriptor
err-descriptor]`, alongside the existing scalar/`:string`/`:keyword`/
`:symbol`/`:record`/`:variant`/`:list` shapes. This closes another narrow
slice of ADR 0049's remaining-gaps item 1 ("Nested structured values ...
lists, tuples/vectors, maps, sets, **options, results**, and variants need
recursive flatten/lift/lower/store/load **plans**") -- exactly as ADR 0065
did for `list`, the word "plans" is taken literally: this ADR adds the
Canonical ABI *layout plan* for `option`/`result`, the same abstraction level
`layout`/`layout-leaves`/`export-plan` already give record, variant, and
list, not a new runtime marshaling/codegen path.

### Shape: structural, sugar over the variant union-of-cases math

Per the Component Model spec, `option<T>` and `result<T, E>` are anonymous
type constructors, exactly like `list<T>` -- not sealed named types a caller
registers once in a `schemas` table and references by identity thereafter,
the way `:record`/`:variant` are. Following ADR 0065's own precedent
directly ("A list is structural, not nominal"), `option`/`result` are
likewise **structural**: every use is the inline form `[:option
item-descriptor]` / `[:result ok-descriptor err-descriptor]`, with no
schema-table entry and no `identity` of their own.

Structurally, `option<T>` is a 2-case union (a payload-less `none` case and a
`some` case carrying `T`) and `result<T, E>` is also a 2-case union (`ok`
carrying `T`, `err` carrying `E`) -- both are the same in-memory shape
`variant-layout` already computes for a sealed variant schema (discriminant
byte width from `discriminant-byte-size`, an aligned payload area sized to
the wider case, plus a component-level `:flat` core-value sequence via
`variant-flatten-payload`/`join-core-type`). Rather than re-deriving this
math, a new shared private helper, `structural-union-layout`, factors out
exactly the parts of `variant-layout` that do not depend on a `schemas`
lookup, an `identity`, or a `visited`-set entry of their own (`variant-layout`
itself is untouched -- it still computes its own `identity`/`visited`
handling directly, since a *sealed* variant schema genuinely needs it in a
way a structural union does not). `option-layout` and `result-layout` each
build their own two-element `case-layouts` sequence (`{:tag :layout}`, the
same shape `variant-layout` builds internally) and pass it to
`structural-union-layout`, reusing `discriminant-byte-size`,
`variant-flatten-payload`, and `join-core-type` completely unchanged. This is
the "reuse the existing `:variant` machinery" path the task's own design hint
named as preferable when structurally sound, and it is: no wheel was
reinvented for the discriminant/payload-area/flat-join math.

`option`'s `none` case has no payload. No admitted top-level descriptor in
this file produces a zero-size, zero-flat shape via `layout*` itself -- every
real type in this profile carries at least one flat core value -- so a small
local helper, `unit-case-layout`, synthesizes `{:size 0 :alignment 1 :flat
[]}` directly rather than routing a fabricated "unit type" through `layout*`.
`result`'s two cases (`ok`/`err`) both always carry a real payload type;
`result`'s spec-legal payload-less forms (`result<>`/`result<T>`/`result<_,
E>`) are not admitted by this slice, matching this codebase's own existing
domain-level `[:result T E]` descriptor shape at
`kotoba.compiler.value/validate-value-type!`, which likewise always requires
both `T` and `E` present -- this ADR's Canonical ABI shape stays consistent
with that pre-existing convention rather than inventing a wider one.

`item-descriptor` (option) and `ok-descriptor`/`err-descriptor` (result) may
be any already-admitted Canonical ABI descriptor -- a bare scalar, a bounded
`:string`/`:keyword`/`:symbol`, a `[:ref identity]`/inline
`[:record ...]`/`[:variant ...]`, a `[:list ...]`, or **another
`:option`/`:result`** -- recursively re-entering `layout*`, so an option of a
record, a result whose ok case is a list, an option nested inside a variant
case, a list of options, or an option of an option all work for free through
the existing generic recursive call sites (`record-layout`'s per-field
`layout*` call, `variant-layout`'s per-case `layout*` call, `list-layout`'s
own item `layout*` call, `export-plan`'s param/result `layout` calls) with
**zero changes** to any of those four call sites (verified directly, see
Evidence).

### Explicitly *not* rejected: option-of-option, result-of-result

ADR 0065 explicitly rejected `[:list [:list ...]]` because a list-of-lists
needs a second, independent variable length and stride nested inside the
first -- a genuinely harder shape. `option`/`result` have no such problem:
both are always a single **fixed-size** union payload area, and nesting one
inside another's payload area is exactly the same recursive shape a variant
case whose own payload is itself another variant (via `[:ref ...]`) already
has, unconditionally supported today. There is nothing here that grows an
unbounded or doubly-indirect shape the way list-of-list would, so no
analogous rejection is warranted, and none is added -- `option-layout`/
`result-layout` place no special-case guard on their own item/ok/err
descriptor's shape beyond what `layout*`'s own dispatch and the recursive
schema-identity guard already provide.

### Memory and flat-core layout

Both `option-layout` and `result-layout` return maps carrying `:descriptor`,
`:kind` (`:option`/`:result`, the same key `variant-layout` already uses),
the payload layout(s) kept alongside (`:item-layout` for option;
`:ok-layout`/`:err-layout` for result -- the same "kept alongside, not
flattened away" design choice `list-layout`'s own `:item-layout` and a
record field's own nested `:fields` layout already make, so a caller can
derive a payload's own stride or recurse into a nested aggregate without
re-deriving it from the descriptor), plus `:discriminant-size`,
`:payload-offset`, `:alignment`, `:size`, and `:flat` from
`structural-union-layout`.

A `:validation [:bounded-discriminant]` tag is attached to both, documenting
the same kind of future codegen obligation every other `:validation` tag in
this file already documents (declarative metadata only; confirmed by grep,
nothing in this repository reads the `:validation` key programmatically
today) -- here, that a codegen consumer must check the raw discriminant
value against the two valid cases (0/1) before trusting which payload
interpretation applies. This is a deliberate difference from
`variant-layout`, which carries no analogous tag for its own discriminant
today; that omission is a pre-existing gap in `variant-layout`, not one this
ADR closes (touching `variant-layout` itself is out of this task's scope --
see "Remaining gaps").

`layout*`'s dispatch cond gains two new clauses, placed after the existing
`:list` clause and before the final `:else` rejection, exactly mirroring the
`:list` clause's own placement and shape. `visited` (the existing
recursive-schema-identity guard set) is threaded straight through
`option-layout`/`result-layout` into the payload type's own `layout*` call
unchanged -- neither carries an identity of its own to add to `visited`, so
a payload type reaching back to an *enclosing* record/variant's own identity
(including by way of an option or result) is still caught by
`record-layout`/`variant-layout`'s own existing `(contains? visited
identity)` check, exactly as a list's item type already is (verified
directly, see Evidence).

Both layout functions also check descriptor arity up front
(`[:option item-descriptor]` must have exactly 2 elements;
`[:result ok-descriptor err-descriptor]` must have exactly 3) and `reject`
with a specific message rather than let a raw `IndexOutOfBoundsException`
propagate from a malformed descriptor -- the same fail-closed, explicit-error
discipline this file uses everywhere else (e.g. `variant-layout`'s "variant
reference has no matching schema identity", `list-layout`'s "list item type
must not itself be a list").

### `layout-leaves`

Unlike `list-layout`, no leaf-shape branch is added to `layout-leaves` for
option/result. `layout-leaves` today has **no leaf-shape branch for a
`:variant`-typed record field either** -- a record whose field is a variant
falls through to the plain-scalar `:else` branch, silently losing the
field's own discriminant/payload substructure (an existing, undocumented gap
this ADR did not introduce and does not fix). `list` earned its own
`:max-items`/`:item-layout` leaf shape because list's memory layout is a
pointer+length pair, the same shape family `:string`/`:keyword`/`:symbol`
already had a shared leaf treatment for (`:max-bytes`); `option`/`result`'s
memory layout is a discriminant+payload union, structurally identical to
`variant`'s own layout shape, the *nominal* member of which has no such leaf
treatment today. Giving `option`/`result` their own bespoke leaf-shape ahead
of `variant` itself would be inconsistent, not more complete -- so this ADR
leaves this exactly where `variant`-as-record-field already is, and names it
explicitly as a remaining gap (see below) rather than silently deferring it.
Confirmed directly: `record-layout`'s own `:size`/`:alignment`/`:flat` for a
record with an option/result field are still fully and correctly computed
regardless, since `record-layout` folds every field's own raw `layout*`
result directly and never depends on `layout-leaves` to do so (see Evidence).

## Evidence

- New tests in `test/kotoba/compiler/canonical_abi_test.clj` (16 new
  `deftest`, 19 pre-existing from ADR 0065, 35 total in this namespace):
  - `[:option :i64]` is the expected discriminant+payload union shape
    (`:size 16 :alignment 8 :flat [:i32 :i64]`), carries the scalar item's
    own layout under `:item-layout`, `:discriminant-size 1`,
    `:payload-offset 8`, and `:validation [:bounded-discriminant]`;
    `export-plan` on a bare option parameter/result produces the same
    indirect-result convention a bare string/list already does.
  - descriptor-arity checks: `[:option]` and both `[:result :i64]` (missing
    `err-descriptor`) and `[:result :i64 :bool :string]` (extra element) are
    rejected with the specific arity message.
  - `[:option [:ref :demo/point]]` (option of a sealed all-scalar record)
    carries exactly the same `:item-layout` a direct
    `(layout [:ref :demo/point] schemas)` call would produce.
  - `[:result :i64 :bool]` and `[:result :bool :f32]` (mirroring ADR 0065's
    own bool/f32-join test for a sealed variant) confirm `join-core-type`'s
    i64-widening and i32/f32-special-case behavior both apply unchanged to a
    structural result's two cases.
  - `[:result [:ref :demo/point] :bool]` carries both `:ok-layout` and
    `:err-layout` correctly, the record layout unchanged from a direct call.
  - an option payload of type `[:option :i64]` and a result payload of type
    `[:result :i64 :bool]`, each used as one case of a sealed variant, join
    with a sibling `:bool` case's own flat core types exactly the way ADR
    0052/0065 already showed for a record/list case payload, with zero
    changes to `variant-flatten-payload`/`join-core-type`.
  - `[:list [:option :i64]]` and `[:option [:list :i64]]` both work with no
    special-case rejection, demonstrating list/option compose freely in
    either nesting direction.
  - `[:option [:option :i64]]` and `[:result [:result :i64 :bool] :bool]`
    are **not** rejected (contrasting directly with ADR 0065's deliberate
    list-of-list rejection), confirming the "no analogous rejection" design
    decision above.
  - `[:option :not-a-real-descriptor]` and both payload positions of
    `[:result ... ...]` with an invalid descriptor are rejected by the
    existing generic "descriptor has no qualified Canonical ABI layout"
    fallthrough, unchanged.
  - a record whose only field is `[:option [:ref <the record's own
    identity>]]` (and, separately, `[:result [:ref <identity>] :bool]`) is
    still rejected by the existing "recursive schema has no bounded
    Canonical ABI layout" guard, confirming `visited` threads through
    `option-layout`/`result-layout` correctly with no new code needed in
    `record-layout`/`variant-layout` for either case.
  - a record whose only aggregate field is `[:result :i64 :bool]` still
    flattens to the fully correct top-level `:size`/`:alignment`/`:flat`
    (folded directly from each field's own raw `layout*` result), pinning
    that `layout-leaves`'s lack of a dedicated leaf shape for this field
    does not affect the record's own overall layout correctness.
- Full `clojure -M:test` suite with this ADR's changes applied: 505 tests,
  4703 assertions, 0 failures, 0 errors -- confirms every pre-existing
  record/variant/list/string/keyword consumer of `canonical-abi.cljc`
  (`component-core.clj`, `component-composition.clj`, and their own test
  namespaces) is unaffected (no capability kit, provider component, or WIT
  emission path touches `:option`/`:result` today, so none of them exercise
  the new dispatch clauses at all).

## Remaining gaps

1. **No component_core.clj codegen.** Exactly ADR 0065's own gap 1, restated
   for `option`/`result`: this ADR is deliberately scoped to the Canonical
   ABI *layout plan* layer only. No `.kotoba` export, `typed-cap-call`, or
   provider Component can actually take or return an option/result value
   yet. A future ADR is needed to admit an option/result-identity/
   passthrough function shape into `component-core.clj` and emit the actual
   discriminant-range runtime check the `:bounded-discriminant` tag
   describes as an obligation.
2. **`:bounded-discriminant` is declarative metadata only, not an executable
   check**, exactly like every other `:validation` tag in this file. There
   is no instance-level "option value"/"result value" concept in this
   namespace at all -- `layout` only ever inspects *type descriptors*, never
   concrete data.
3. **`variant`-typed record fields (and now, by the same pre-existing gap,
   `option`/`result`-typed record fields) have no dedicated `layout-leaves`
   leaf shape.** This ADR does not close this gap (closing it for
   `option`/`result` alone, ahead of `variant` itself, would be
   inconsistent) -- it is named here explicitly rather than left implicit,
   matching this ADR chain's own practice of naming what a slice does not
   yet cover.
4. **`result`'s spec-legal payload-less forms are not admitted** (`result<>`,
   `result<T>`, `result<_, E>`) -- only `[:result ok-descriptor
   err-descriptor]` with both payload types always present, matching this
   codebase's own pre-existing domain-level `[:result T E]` convention in
   `kotoba.compiler.value`.
5. **Every other unimplemented shape from ADR 0049's item 1 remains
   unimplemented**: tuples/vectors, maps, sets are still entirely
   unimplemented in `canonical-abi.cljc`'s `layout*` (three admitted
   aggregate/union shapes before this ADR -- `:record`/`:variant`/`:list` --
   now five with `:option`/`:result`). Recursive schema identity itself (ADR
   0049 remaining gap 2) is unchanged and explicitly out of scope here.
6. **No Wasmtime/native evidence**, consistent with every layout-only ADR in
   this chain (0051/0052/0065 note the same); there is nothing to execute
   yet since gap 1 above is unaddressed.

## Related

- ADR 0049 (component/application-language gap ledger) -- names this exact
  gap ("options, results ... need recursive flatten/lift/lower/store/load
  plans") as part of next-completion-order item 1; this ADR closes another
  narrow slice of it, per the addendum appended to that file alongside this
  ADR.
- ADR 0065 (bounded `list<T>` Canonical ABI layout plan) -- the direct
  precedent this ADR follows for "structural, not nominal" scoping,
  descriptor placement in `layout*`'s dispatch cond, the "kept alongside,
  not flattened away" payload-layout design, and the explicit
  rejected/not-rejected nesting-shape reasoning (list-of-list rejected;
  option-of-option/result-of-result not, for a stated structural reason).
- ADR 0052 (variant-of-record-and-scalar Canonical ABI identity) -- the
  source of `variant-layout`'s own union-of-cases math this ADR's
  `structural-union-layout` helper reuses, and confirms
  `variant-flatten-payload`/`join-core-type` are already fully generic over
  any case payload type, requiring zero changes for a variant case whose
  payload is `[:option ...]`/`[:result ...]` (verified directly, see
  Evidence).
- `kotoba.compiler.value/validate-value-type!` -- the pre-existing
  domain-value-type layer's own `[:option T]`/`[:result T E]` descriptor
  shape convention, which this ADR's Canonical ABI descriptor shape matches
  (both always-present payload types for `result`; a single payload type for
  `option`) rather than inventing an independent shape.
- ADR 0040/0041 (canonical string layout plans, bounded string identity) --
  cited by ADR 0065 as the origin of the pointer+length shape; not directly
  reused by this ADR (option/result are a union shape, not a buffer), but
  part of the same layout-plan lineage this file's `layout*` dispatch now
  extends six ways from four.
