# ADR 0065: Bounded `list<T>` Canonical ABI layout plan

Status: accepted; layout-plan slice implemented (type-level plan only, no
codegen)

## Decision

`kotoba.compiler.canonical-abi/layout` gains a fifth admitted schema shape,
`[:list item-descriptor]`, alongside the existing scalar/`:string`/`:keyword`/
`:symbol`/`:record`/`:variant` shapes. This closes one narrow slice of ADR
0049's remaining-gaps item 1 ("Nested structured values: strings inside
records, nested records, **lists**, tuples/vectors, maps, sets, options,
results, and variants need recursive flatten/lift/lower/store/load **plans**
plus pre-call and post-return validation") -- the word "plans" there is
taken literally: this ADR adds the Canonical ABI *layout plan* for a bounded
list, the same abstraction level `layout`/`layout-leaves`/`export-plan`
already give record and variant, not a new runtime marshaling/codegen path.
Exactly as ADR 0051 (nested record) and ADR 0052 (variant) did for their own
shapes, this is a plan a future codegen ADR consumes; it is not codegen
itself (see "Remaining gaps" below).

### Shape

A `list` is **structural, not nominal** -- unlike `:record`/`:variant`, it
carries no sealed schema identity and is never registered in a `schemas`
table entry; every use is the inline two-element form `[:list
item-descriptor]`, exactly parallel to how `:string`/`:keyword`/`:symbol` are
self-contained bounded scalars needing no identity. `item-descriptor` may be
any already-admitted Canonical ABI descriptor: a bare scalar (`:i64`/`:f32`/
`:f64`/`:bool`), a bounded `:string`/`:keyword`/`:symbol`, or a `[:ref
identity]`/inline `[:record ...]`/`[:variant ...]` resolving through the
caller's `schemas` table -- recursively re-entering `layout*`, so a list of
records or a list nested inside a variant case or record field all work for
free through the existing generic recursive call sites (`record-layout`'s
per-field `layout*` call, `variant-layout`'s per-case `layout*` call,
`export-plan`'s param/result `layout` calls) with **zero changes** to any of
those three functions.

### Memory and flat-core layout

A list's Canonical ABI shape is a pointer+length pair in linear memory --
the same two-core-value `[:i32 :i32]` shape ADR 0040/0041 already gave bare
`string`/`keyword` parameters and results, generalized from a byte-addressed
buffer (length in UTF-8 bytes) to an element-addressed one (length in
items). `list-layout` computes `:size 8 :alignment 4 :flat [:i32 :i32]`,
identical in shape to the existing `:string`/`:keyword`/`:symbol` layouts,
plus:

- `:item-layout` -- the item descriptor's own recursive `layout*` result,
  kept alongside rather than flattened away (the same design choice a
  record field's own nested `:fields` layout already makes), so a caller can
  derive the buffer's per-element stride (`align-up` of the item's own
  `:size` to its own `:alignment`) or recurse into a nested record/variant
  item without re-deriving its shape from the descriptor.
- `:max-items` -- the fixed item-count ceiling, playing the same role
  `:max-bytes` plays for a bounded string/keyword. Backed by a new constant,
  `kotoba.compiler.value/canonical-list-item-limit` (16384), the same
  magnitude as this file's existing `vector-item-limit` (the codebase's
  established "bounded sequential collection" order of magnitude) but kept
  as its own named constant rather than a direct reuse, because the two
  bound genuinely different domains -- a `vector-i64` runtime domain value
  vs. a Canonical ABI wire-transport schema shape whose item type is not
  restricted to i64 -- that happen to share a magnitude today, not one
  concept wearing two names. No new arbitrary number was invented.
- `:validation [:checked-pointer-range :bounded-item-count]` -- the same
  declarative tag list `:string`/`:keyword`/`:symbol` already carry
  (`:checked-pointer-range` plus a type-specific content check,
  `:valid-utf8` for strings, `:bounded-item-count` for a list), documenting
  the two checks a *future* codegen consumer must perform before trusting a
  guest-supplied list: the pointer+length pair falls inside the module's own
  addressable memory (`:checked-pointer-range`, shared with every other
  pointer-bearing leaf), and the length does not exceed `:max-items`
  (`:bounded-item-count`, the list-specific analog of `:valid-utf8`). As with
  every existing `:validation` tag in this file today, this is declarative
  metadata describing an obligation -- confirmed by grep, nothing in this
  repository currently reads the `:validation` key programmatically; the
  actual pointer-range/length **enforcement** for `:max-bytes` leaves lives
  in `kotoba.compiler.component-core`'s WAT emitters (e.g. the `capacity`
  checks around line 764-820 there), not in this namespace. See "Remaining
  gaps" for what an analogous `:max-items` enforcement in that namespace
  would still require.

`layout*`'s dispatch cond gains one new clause, `(and (vector? descriptor)
(= :list (first descriptor))) (list-layout descriptor schemas visited)` --
placed after the existing `:record`/`:variant` inline clauses, before the
final `:else` rejection. `visited` (the existing recursive-schema-identity
guard set) is threaded straight through `list-layout` into the item type's
own `layout*` call, unchanged: a list carries no identity of its own to add
to `visited`, so a list's item type reaching back to an *enclosing*
record/variant's own identity is still caught by `record-layout`/
`variant-layout`'s own existing `(contains? visited identity)` check,
exactly as a plain record field or variant case payload's own type already
is (verified directly, see Evidence).

### Explicitly rejected: list-of-list

An item type that is itself `[:list ...]` is rejected before any layout is
computed (`"list item type must not itself be a list in this slice"`).
Unlike ADR 0051's one-level nested record (whose fields are still bounded to
plain scalars, so nesting stops one level down structurally), a
list-of-lists would need a second, independent variable length and stride
nested inside the first -- a genuinely different and strictly harder shape,
named as its own still-open item in ADR 0049's remaining gaps ("lists,
tuples/vectors, maps, sets, options, results, and variants"), not a
narrowing of this slice's one already-admitted case. Keeping this rejection
explicit (rather than letting it fail some other way, or silently accepting
an unbounded-looking recursive buffer shape) keeps this slice's item-type
surface exactly "scalar, string/keyword/symbol, record, or variant" --
matching the task's own scope boundary -- while still failing closed on the
one shape most likely to be reached for next.

### `layout-leaves`

A field whose own layout carries `:max-items` (a list) is now exposed by
`layout-leaves` as its own leaf shape, `{:offset :descriptor :max-items
:item-layout}`, inserted between the existing `:max-bytes` (string/keyword)
branch and the catch-all scalar branch. A list field is **not** recursed
into the way a nested record's `:fields` are: a list's own items live in a
separately-addressed buffer at a length only known at runtime, not at a
further fixed offset inside the enclosing record's own layout, so there is
nothing further to flatten at this static-layout layer -- `:item-layout` is
carried on the leaf itself precisely so a caller does not lose access to the
item shape/stride by this leaf-level flattening choice.

## Evidence

- New tests in `test/kotoba/compiler/canonical_abi_test.clj` (8 new
  `deftest`, 11 pre-existing, 19 total in this namespace):
  - a bare `[:list :i64]` layout is the expected bounded pointer+length
    shape (`:size 8 :alignment 4 :flat [:i32 :i32]`), carries the scalar
    item's own layout under `:item-layout`, `:max-items` equal to
    `value/canonical-list-item-limit`, and the expected `:validation` tags;
    `export-plan` on a bare list parameter/result produces the same
    indirect-result convention as a bare string already does
    (`:core-results [:i32]`, `:core-params [:i32 :i32]`).
  - the layout of `[:list :bool]` is stable/deterministic across repeated
    calls (a type-level plan has no notion of "how many items an instance
    holds", unlike a sealed record/variant's per-field shape).
  - `[:list [:ref :demo/point]]` (list of a sealed all-scalar record) carries
    exactly the same `:item-layout` a direct `(layout [:ref :demo/point]
    schemas)` call would produce, unchanged.
  - a record field of type `[:list :i64]` flattens (via `layout-leaves`)
    to its own `{:offset :max-items :item-layout}` leaf, not exploded into
    per-item fields, alongside its sibling scalar fields at the correct
    absolute offsets/alignment.
  - a variant case payload of type `[:list :i64]` joins with a sibling
    `:bool` case's own flat core types exactly the way ADR 0052's existing
    `variant-flatten-payload`/`join-core-type` already generalizes for any
    case type, with zero changes to that function.
  - `[:list [:list :i64]]` is rejected with the new, explicit
    "list item type must not itself be a list in this slice" message.
  - `[:list :not-a-real-descriptor]` is rejected by the existing generic
    "descriptor has no qualified Canonical ABI layout" fallthrough, unchanged.
  - a record whose only field is `[:list [:ref <the record's own identity>]]`
    (a list-mediated recursive nominal schema) is still rejected by the
    existing "recursive schema has no bounded Canonical ABI layout" guard,
    confirming `visited` threads through `list-layout` correctly with no
    new code needed in `record-layout`/`variant-layout` for this case.
- Full `clojure -M:test` suite with this ADR's changes applied: 489 tests,
  4642 assertions, 0 failures, 0 errors -- confirms every pre-existing
  record/variant/string/keyword consumer of `canonical-abi.cljc`
  (`component-core.clj`, `component-composition.clj`, and their own test
  namespaces) is unaffected by this change (no capability kit, provider
  component, or WIT emission path touches `:list` today, so none of them
  exercise the new dispatch clause at all).

## Remaining gaps

1. **No component_core.clj codegen.** This ADR is deliberately scoped to
   the Canonical ABI *layout plan* layer only (`canonical_abi.cljc`), per
   the task that produced it and matching ADR 0049's own "plans" wording for
   this gap-ledger item. There is no WAT emitter here analogous to
   `component_core.clj`'s `nested-record-wat`/`variant-wat`: no `.kotoba`
   export, `typed-cap-call`, or provider Component can actually take or
   return a `list` value yet. A future ADR is needed to (a) admit a
   list-identity/list-passthrough function shape into `component-core.clj`
   and (b) emit the actual pointer-range-against-`capacity` and
   length-against-`:max-items` runtime checks the `:validation` tags above
   describe as an obligation -- exactly the two-phase pattern this repo's
   own ADR chain already used for record (ADR 0051's layout, then later
   ADRs' codegen) and variant (ADR 0052 did both together, but 0055/0056/
   0057/0058/0059 kept extending the codegen side afterward).
2. **`:max-items`/`:bounded-item-count` is declarative metadata only, not an
   executable check**, exactly like every other `:validation` tag in this
   file today (confirmed by grep: nothing in this repository reads the
   `:validation` key programmatically). There is no instance-level "list
   value" concept in this namespace at all -- `layout` only ever inspects
   *type descriptors*, never concrete data -- so there is no function here
   that could reject an actual over-length list at this layer; that
   enforcement can only exist in a codegen consumer (gap 1) or a future
   domain-value validator analogous to `kotoba.compiler.value`'s
   `bounded-vector-i64!`/`bounded-string!`, neither of which this ADR adds.
3. **List-of-list remains closed**, by explicit design (see Decision) --
   named as a distinct future slice, not silently subsumed by this one.
4. **Every other unimplemented shape from ADR 0049's item 1 remains
   unimplemented**: tuples/vectors, maps, sets, options, results are still
   entirely unimplemented in `canonical-abi.cljc`'s `layout*` (still exactly
   two admitted aggregate shapes before this ADR -- `:record`/`:variant` --
   now three with `:list`). String-in-record and one-level nested record
   (ADR 0051/0053) and variant-of-record/string/keyword (ADR 0052/0054) were
   already closed before this ADR and are unaffected. Recursive schema
   identity itself (ADR 0049 remaining gap 2, "Component v1 still rejects
   general recursive Kotoba schemas") is unchanged and explicitly out of
   scope here, per the task that produced this ADR.
5. **No Wasmtime/native evidence**, consistent with every layout-only ADR
   in this chain (0051/0052 note the same); there is nothing to execute yet
   since gap 1 above is unaddressed.

## Related

- ADR 0049 (component/application-language gap ledger) -- names this exact
  gap ("lists ... need recursive flatten/lift/lower/store/load plans") as
  next-completion-order item 1; this ADR closes one narrow slice of it, per
  the addendum appended to that file alongside this ADR.
- ADR 0051 (one-level nested-record Canonical ABI identity) -- the closest
  prior-art shape for "add a new aggregate to `layout*`/`layout-leaves`
  without disturbing existing consumers"; this ADR's `:item-layout`-kept-
  alongside design and `layout-leaves` leaf-shape extension directly mirror
  its `:fields`-recursion and `:max-bytes`-leaf design.
- ADR 0052 (variant-of-record-and-scalar Canonical ABI identity) -- confirms
  `variant-flatten-payload`/`join-core-type` are already fully generic over
  any case payload type, requiring zero changes for a variant case whose
  payload is `[:list ...]` (verified directly, see Evidence).
- ADR 0040/0041 (canonical string layout plans, bounded string identity) --
  the pointer+length two-core-value shape this ADR generalizes from a
  byte-addressed buffer to an element-addressed one.
- ADR 0064 (production LLM transport) -- the most recent prior ADR to use
  this file's now-established "Progress addendum, not an in-place rewrite"
  convention for ADR 0049's own ledger; this ADR's own addendum to that file
  follows the same convention.
