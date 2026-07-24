# ADR 0070: Structural `tuple<T1, T2, ...>` Canonical ABI layout plan

Status: accepted; layout-plan slice implemented (type-level plan only, no
codegen)

## Decision

`kotoba.compiler.canonical-abi/layout` gains one more admitted schema shape,
`[:tuple item-descriptor-1 item-descriptor-2 ... item-descriptor-n]`,
alongside the existing scalar/`:string`/`:keyword`/`:symbol`/`:record`/
`:variant`/`:list`/`:option`/`:result` shapes. This closes another narrow
slice of ADR 0049's remaining-gaps item 1 ("Nested structured values ...
lists, **tuples/vectors**, maps, sets, options, results, and variants need
recursive flatten/lift/lower/store/load **plans**") -- exactly as ADR 0065
did for `list` and ADR 0068 did for `option`/`result`, the word "plans" is
taken literally: this ADR adds the Canonical ABI *layout plan* for `tuple`,
the same abstraction level `layout`/`layout-leaves`/`export-plan` already
give record, variant, list, option, and result, not a new runtime marshaling/
codegen path.

## Investigation: does ADR 0049's "tuples/vectors" name one gap or two?

The task that produced this ADR required settling, before writing any code,
whether ADR 0049's slash-joined "tuples/vectors" phrase names one Canonical
ABI gap or two -- because ADR 0065's already-implemented `[:list
item-descriptor]` (a bounded, single-item-type, variable-length,
pointer+length buffer) is *already* structurally identical to the Component
Model spec's own `vector<T>` (WIT/Wasmtime historically used `list<T>` and
`vector<T>` as synonyms for exactly this shape before the spec settled on
`list<T>` as the canonical name). Adding a second, independent `:vector`
aggregate shape to `layout*` alongside `:list` for the same shape would be
pure duplication, not a new capability.

Three pieces of pre-existing evidence in this codebase settle the question
directly, without needing to guess at ADR 0049's original intent:

1. **`kotoba.compiler.value` already has two, clearly distinct existing
   concepts that could plausibly be called "vector", and only one of them is
   the shape ADR 0049 is pointing at.** `vector-item-limit` (16384) bounds
   the *runtime domain value* `vector-i64`/`vector-f64` -- a homogeneous,
   variable-length sequence of one scalar type, exactly the shape ADR 0065's
   `:list` already gives the Canonical ABI layer (ADR 0065 itself already
   names this exact relationship: "the two bound genuinely different domains
   ... that happen to share a magnitude today, not one concept wearing two
   names"). Separately, `kotoba.compiler.value/validate-value-type!` admits
   a domain-level `[:vector item-types]` shape (guarded by
   `heterogeneous-vector-item-limit`, 32) where `item-types` is itself a
   *vector of possibly-different types* -- a fixed-length, **heterogeneous**
   product, not a homogeneous buffer. These are not the same thing wearing
   one name; this codebase's own `value.cljc` already needed two different
   words for two different shapes and picked "vector" (homogeneous,
   variable-length) and "heterogeneous vector" (fixed-length, mixed-type) as
   those two words.
2. **`kotoba.compiler.component-wit` (the existing WIT text emitter) already
   renders that second, heterogeneous-`:vector` domain shape as WIT's
   `tuple<...>` syntax, not as `vector<...>` or `list<...>`:**
   `(and (vector? descriptor) (= :vector (first descriptor))) (str "tuple<"
   (str/join ", " (map type-text (second descriptor))) ">")` (`type-text`,
   `component_wit.clj`). This is direct, pre-existing evidence -- not a new
   design choice made for this ADR -- that this codebase already identifies
   "fixed-length, possibly-heterogeneous product" with the Component Model's
   own `tuple<T1, T2, ...>`, confirmed by a part of the codebase this task
   did not write or need to touch.
3. **The homogeneous, variable-length shape (`vector-i64`/`vector-f64`) is
   independently rendered by that same emitter as `list<s64>`/`list<f64>`**
   (`(= descriptor :vector-i64) "list<s64>"`), confirming the *other* half of
   ADR 0049's "tuples/vectors" phrase -- the WIT `vector<T>`/`list<T>` shape
   -- is already, separately, understood by this codebase as the same thing
   ADR 0065's `:list` Canonical ABI layout already closes.

Put together: this codebase's own pre-existing, untouched code already
resolves "tuples/vectors" into exactly two shapes it already has two
different names for -- and both of those names already point one to
`tuple<...>` (heterogeneous, fixed-length; not yet a Canonical ABI layout
plan before this ADR) and the other to `list<T>`/`vector<T>` (homogeneous,
variable-length; already closed by ADR 0065's `:list`). This is not a fresh
interpretation invented to justify the smaller/cheaper of two options -- it
is the reading the codebase's own untouched, pre-existing WIT emitter already
committed to before this task began.

**Conclusion, and scope decision:** this ADR implements a Canonical ABI
`:tuple` layout plan (the still-open half). It explicitly declares the other
half -- an anonymous, variable-length, *homogeneous* `vector<T>` shape at the
Canonical ABI layer -- as **already fully closed by ADR 0065's `:list`**, not
a distinct remaining gap needing its own second aggregate shape in
`layout*`. No new `:vector` clause is added to `layout*`; adding one for a
shape `:list` already covers exactly would be duplication, not a closed gap.

## Shape: structural, sugar over the record union-of-fields math

Per the Component Model spec, `tuple<T1, T2, ...>` is an anonymous type
constructor, exactly like `list<T>`/`option<T>`/`result<T, E>` -- not a
sealed named type a caller registers once in a `schemas` table and
references by identity thereafter, the way `:record`/`:variant` are.
Following ADR 0065/0068's own precedent directly, `tuple` is likewise
**structural**: every use is the inline, variadic form `[:tuple
item-descriptor-1 item-descriptor-2 ... item-descriptor-n]`, with no
schema-table entry and no `identity` of its own.

Structurally, `tuple<T1, T2, ...>` is a fixed-length, positional (unnamed)
*product* -- exactly the same in-memory shape `record-layout` already
computes for a sealed record's fields (a sequential offset/alignment fold,
no discriminant), minus the field names and the schema identity/`visited`
bookkeeping a *nominal* record genuinely needs. Rather than re-deriving this
math, a new shared private helper, `structural-product-layout`, factors out
exactly the parts of `record-layout`'s per-field loop that do not depend on
a `schemas` lookup, an `identity`, or a `visited`-set entry of their own
(`record-layout` itself is untouched -- it still computes its own
`identity`/`visited` handling directly, since a *sealed* record genuinely
needs it in a way a structural tuple does not) -- this is exactly the
`structural-union-layout`/`variant-layout` precedent ADR 0068 already
established for `option`/`result`, mirrored here for `tuple`/`record`. This
is the "reuse the existing machinery" path both prior ADRs in this chain
favored when structurally sound, and it is: no wheel was reinvented for the
sequential-offset/alignment-fold math.

`tuple-layout` requires **at least one** item type (an empty tuple, `[:tuple]`,
is rejected) -- matching this codebase's own pre-existing convention that a
sealed record requires at least one field (`kotoba.compiler.value`'s own
`validate-value-type!` record-field check: `(seq fields)`). A tuple of
exactly one item is *not* rejected (unlike some reference implementations'
convention of requiring two or more) -- this slice treats `tuple<T>` as a
legitimate, if degenerate, one-element fixed-length product, the same way a
one-field record is legitimate.

### Deliberate key-name reuse: `structural-product-layout` returns `:fields`

`structural-product-layout`'s returned map uses the key name `:fields` for
its own ordered `{:offset :layout}` entries (positional -- no `:name`, unlike
a record field's own `{:name :offset :layout}`) rather than a bespoke name
such as `:elements`. This is a deliberate reuse, not an accidental
collision: `layout-leaves`'s pre-existing nested-record recursion clause,
`(contains? layout :fields)`, only ever destructures `:offset`/`:layout`
from each entry and never depends on `:name` being present -- so a
tuple-typed record field is recursed into by that *same, unmodified* clause
with **zero changes to `layout-leaves`** (verified directly, see Evidence).
This is the correct reuse, not a coincidental key-name match: a tuple, like a
nested record and unlike a variant/option/result, is a pure fixed-offset
product with no discriminant, so every element's own absolute offset is
already the final answer for flattening -- exactly the same reason a nested
record's own `:fields` are recursed into today, and exactly the boundary ADR
0068 already drew when it explained why `option`/`result` (discriminant-gated
unions) get *no* dedicated `layout-leaves` leaf shape while `list`
(runtime-length buffer) gets its own bespoke one. `tuple` is the third case
this ADR chain has now met -- pure fixed-offset product -- and the correct
treatment for that case is "reuse the nested-record path exactly", not a
fourth bespoke leaf shape.

### Memory and flat-core layout

`tuple-layout` returns a map carrying `:descriptor`, `:kind :tuple` (the same
key `variant-layout`/`option-layout`/`result-layout` already use), the
ordered item layouts kept alongside twice, for two different purposes:
`:item-layouts` (a plain vector of each element's own `layout*` result, no
offset annotation -- for a caller who wants to inspect element *N*'s own
shape without walking `:fields`, the same "kept alongside" reason
`list-layout`'s `:item-layout` and `option-layout`/`result-layout`'s
`:item-layout`/`:ok-layout`/`:err-layout` already give) and `:fields` (each
entry `{:offset :layout}`, from `structural-product-layout`, the shape that
makes the `layout-leaves` reuse above work). `:size`, `:alignment`, and
`:flat` come directly from `structural-product-layout`'s own fold.

No `:validation` tag is attached: a tuple, like a plain record, carries no
discriminant and no separately-addressed buffer of its own -- both the
pointer-range check a `list`/`string`/`keyword` leaf documents and the
discriminant-range check an `option`/`result` layout documents are
inapplicable here, exactly as `record-layout` itself carries no
`:validation` tag today.

`layout*`'s dispatch cond gains one new clause, placed after the existing
`:result` clause and before the final `:else` rejection, exactly mirroring
the `:list`/`:option`/`:result` clauses' own placement and shape. `visited`
(the existing recursive-schema-identity guard set) is threaded straight
through `tuple-layout` into each item type's own `layout*` call unchanged: a
tuple carries no identity of its own to add to `visited`, so an item type
reaching back to an *enclosing* record/variant's own identity (including by
way of a tuple) is still caught by `record-layout`/`variant-layout`'s own
existing `(contains? visited identity)` check, exactly as a list/option/
result item/payload type already is (verified directly, see Evidence).

### Item count bound

Item count is bounded by a new constant, `value/canonical-tuple-item-limit`
(32) -- kept as its own named constant rather than a direct reuse of either
of the two existing 32-magnitude bounds this codebase already has for a
closely related concept (`record-field-limit`, a sealed record's own
field-count bound, since a tuple is structurally an anonymous record; and
`heterogeneous-vector-item-limit`, `kotoba.compiler.value`'s pre-existing
domain-level fixed-length heterogeneous-product bound, which
`component-wit.clj` already renders to this exact WIT `tuple<...>` syntax
today), for exactly the reason ADR 0065 gave `canonical-list-item-limit` its
own name alongside `vector-item-limit`: these bound genuinely different
domains that happen to share a magnitude today, not one concept wearing
three names. Unlike `list`'s `:max-items` (a runtime-instance bound on a
buffer whose actual length is unknown until execution), a tuple's item count
is a **type-level** fact -- fully known and fixed once the descriptor is
written, the same way a record's field count is -- so this bound is enforced
directly by `tuple-layout` rejecting an over-length descriptor outright
(`"tuple item count exceeds bound"`), not carried forward as a
`:validation` tag documenting a future runtime obligation the way
`:max-items`/`:bounded-item-count` are for list.

### Explicitly *not* rejected: tuple-of-tuple

Contrasting directly with ADR 0065's deliberate list-of-list rejection: an
item type that is itself `[:tuple ...]` is **not** rejected. A list-of-lists
needs a second, independent variable length and stride nested inside the
first -- a genuinely harder shape. A tuple has no such problem: every level
is still a single fixed-size, fixed-offset product with no independent
runtime-variable length or stride of its own, exactly the same reasoning ADR
0068 already gave for not rejecting option-of-option/result-of-result.
`tuple-layout` places no special-case guard on any item descriptor's own
shape beyond what `layout*`'s own dispatch and the recursive schema-identity
guard already provide.

Any already-admitted Canonical ABI descriptor may appear as a tuple item,
including another `:tuple`, `:list`, `:option`, or `:result` -- and a tuple
may itself appear as a list item, an option/result payload, or a variant
case payload -- all working for free through the existing generic recursive
call sites (`record-layout`'s per-field `layout*` call, `variant-layout`'s
per-case `layout*` call, `list-layout`'s own item `layout*` call,
`option-layout`/`result-layout`'s own payload `layout*` calls,
`export-plan`'s param/result `layout` calls) with **zero changes** to any of
those five call sites (verified directly, see Evidence).

## Evidence

- New tests in `test/kotoba/compiler/canonical_abi_test.clj` (14 new
  `deftest`, 35 pre-existing from ADR 0065/0068, 49 total in this namespace):
  - `[:tuple :i64]` (a legal one-element tuple) is a single-flat-value
    product (`:size 8 :alignment 8 :flat [:i64]`), not treated as a
    degenerate/rejected shape.
  - `[:tuple :i64 :bool]` is the expected fixed-offset product
    (`:size 16 :alignment 8 :flat [:i64 :i32]`), with `:item-layouts` holding
    each element's own raw `layout*` result and `:fields` holding the
    `{:offset :layout}` pairs at offsets `[0 8]`; `export-plan` on a bare
    tuple parameter/result produces the same indirect-result convention a
    bare list/option/result already does.
  - `[:tuple]` (no item types) is rejected with the new, explicit "tuple
    descriptor must have at least one item type" message.
  - a tuple with `value/canonical-tuple-item-limit` items succeeds and a
    tuple with one more than the limit is rejected with the new "tuple item
    count exceeds bound" message.
  - `[:tuple [:ref :demo/point] :bool]` (tuple of a sealed all-scalar record
    and a scalar) carries the record's own exact `layout*` result as its
    first `:item-layouts` entry, unchanged, at the expected absolute
    offsets.
  - a record whose field is `[:tuple :i64 :bool]` flattens, via
    `layout-leaves`, all the way down to each tuple element's own absolute
    leaf -- not one opaque leaf -- with **zero changes to
    `layout-leaves`**, confirming the deliberate `:fields`-key-reuse design
    above.
  - a tuple used as one variant case's payload joins with a sibling `:bool`
    case's own flat core types exactly the way ADR 0052/0065/0068 already
    generalized for record/list/option/result case payloads, with zero
    changes to `variant-flatten-payload`/`join-core-type`.
  - `[:tuple [:tuple :i64 :bool] :f32]` (tuple-of-tuple) is **not** rejected,
    contrasting directly with ADR 0065's list-of-list rejection.
  - `[:tuple :not-a-real-descriptor]` is rejected by the existing generic
    "descriptor has no qualified Canonical ABI layout" fallthrough,
    unchanged.
  - a record whose only field is `[:tuple [:ref <the record's own
    identity>] :bool]` (a tuple-mediated recursive nominal schema) is still
    rejected by the existing "recursive schema has no bounded Canonical ABI
    layout" guard, confirming `visited` threads through `tuple-layout`
    correctly with no new code needed in `record-layout`/`variant-layout`
    for this case.
  - `[:list [:tuple ...]]`, `[:tuple [:list ...] ...]`,
    `[:option [:tuple ...]]`, `[:tuple [:option ...] ...]`, and
    `[:result [:tuple ...] :bool]` all work with no special-case rejection
    in either nesting direction, demonstrating tuple composes freely with
    every other structural aggregate this ADR chain has already admitted.
- Full `clojure -M:test` suite with this ADR's changes applied: 536 tests,
  4807 assertions, 0 failures, 0 errors -- confirms every pre-existing
  record/variant/list/option/result/string/keyword consumer of
  `canonical-abi.cljc` (`component-core.clj`, `component-composition.clj`,
  and their own test namespaces) is unaffected by this change (no capability
  kit, provider component, or WIT emission path touches the Canonical-ABI
  `:tuple` shape today, so none of them exercise the new dispatch clause at
  all -- this is a separate vocabulary from `component-wit.clj`'s own
  pre-existing, untouched `[:vector item-types]` domain-value-type
  emission discussed above).

## Remaining gaps

1. **No `component_core.clj` codegen.** Exactly ADR 0065/0068's own gap 1,
   restated for `tuple`: this ADR is deliberately scoped to the Canonical
   ABI *layout plan* layer only. No `.kotoba` export, `typed-cap-call`, or
   provider Component can actually take or return a tuple value yet. A
   future ADR is needed to admit a tuple-identity/passthrough function shape
   into `component-core.clj`.
2. **No instance-level "tuple value" concept in this namespace at all** --
   `layout` only ever inspects *type descriptors*, never concrete data, so
   there is no function here that could construct, project, or validate an
   actual tuple instance at this layer; that is a future domain-value-layer
   or codegen-layer concern (gap 1), not this ADR's.
3. **`layout-leaves` still has no dedicated leaf shape for a
   `variant`/`option`/`result`-typed record field** -- unchanged,
   pre-existing gap named explicitly by ADR 0068, not touched or worsened by
   this ADR. Tuple-typed fields, uniquely among the aggregate shapes this
   ADR chain has added since `:list`, do *not* have this gap (see "Deliberate
   key-name reuse" above) -- an asymmetry that is a direct consequence of
   tuple's own product (not union) shape, not an oversight.
4. **The Canonical ABI layer still has no independent `:vector` aggregate
   shape**, by explicit design (see "Investigation" above) -- this is not a
   remaining gap but a closed question: the homogeneous-vector half of ADR
   0049's "tuples/vectors" phrase is ADR 0065's `:list`, already closed.
5. **Maps and sets remain entirely unimplemented** in `canonical-abi.cljc`'s
   `layout*` (six admitted aggregate/union shapes before this ADR --
   `:record`/`:variant`/`:list`/`:option`/`:result` -- now seven with
   `:tuple`). Recursive schema identity itself (ADR 0049 remaining gap 2) is
   unchanged and explicitly out of scope here.
6. **No Wasmtime/native evidence**, consistent with every layout-only ADR in
   this chain (0051/0052/0065/0068 note the same); there is nothing to
   execute yet since gap 1 above is unaddressed.

## Related

- ADR 0049 (component/application-language gap ledger) -- names this exact
  gap ("tuples/vectors ... need recursive flatten/lift/lower/store/load
  plans") as part of next-completion-order item 1; this ADR closes the
  `tuple` half of it, and explicitly closes the `vector` half by pointing at
  ADR 0065's own prior work, per the addendum appended to that file alongside
  this ADR.
- ADR 0065 (bounded `list<T>` Canonical ABI layout plan) -- the direct
  precedent this ADR follows for "structural, not nominal" scoping,
  descriptor placement in `layout*`'s dispatch cond, the "kept alongside, not
  flattened away" item-layout design, and -- decisively -- the reasoning that
  settles this ADR's own "tuples vs. vectors" scope question: `:list` is
  already the homogeneous, variable-length, pointer+length shape ADR 0049's
  "vector" half describes.
- ADR 0068 (structural `option`/`result` Canonical ABI layout plan) -- the
  direct precedent for factoring a `variant-layout`-shaped/`record-layout`-
  shaped helper (`structural-union-layout`) out of the nominal function for
  reuse by a structural sibling; this ADR's own `structural-product-layout`
  helper (factored out of `record-layout`) is the exact same move applied to
  `record`/`tuple` instead of `variant`/`option`/`result`. Also the direct
  precedent for the "not rejected" nesting-shape reasoning this ADR reuses
  for tuple-of-tuple.
- ADR 0051 (one-level nested-record Canonical ABI identity) -- the origin of
  the `:fields`-keyed, recursed-into leaf-flattening design in
  `layout-leaves` that this ADR's own `:fields`-key reuse (in
  `structural-product-layout`) rides on with zero code changes.
- `kotoba.compiler.value`'s `[:vector item-types]` domain-value-type shape
  (`validate-value-type!`) and `kotoba.compiler.component-wit`'s `type-text`
  (`(= :vector (first descriptor)) "tuple<...>"`) -- the pre-existing,
  untouched evidence this ADR's own "Investigation" section reads directly to
  settle the tuple/vector scope question, rather than guessing at ADR 0049's
  original intent.
- ADR 0052 (variant-of-record-and-scalar Canonical ABI identity) -- confirms
  `variant-flatten-payload`/`join-core-type` are already fully generic over
  any case payload type, requiring zero changes for a variant case whose
  payload is `[:tuple ...]` (verified directly, see Evidence).
