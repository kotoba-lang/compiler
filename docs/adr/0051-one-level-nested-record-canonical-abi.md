# ADR 0051: One-level nested-record Canonical ABI identity

Status: accepted; nested-record identity slice implemented

## Decision

A direct identity export (the body is exactly its sole parameter, unchanged)
may use one sealed nominal record whose fields are each a Canonical scalar
(`i64`/`f32`/`f64`/`bool`) or exactly one level of nested sealed all-scalar
record. A nested field's own fields must all be scalar; a field that is
itself nested-of-nested is rejected before component encoding, so nesting
depth stays bounded at exactly one level in this slice.

`kotoba.compiler.canonical-abi/layout` already computed a correct recursive
memory layout for nested records before this change (it calls itself on
every field type, including `[:ref ...]` field types that resolve to another
record schema); this was previously unused by any codegen path. The new
`canonical-abi/layout-leaves` walks a layout's nested `:fields` to produce a
flat, depth-first list of `{:offset :descriptor}` scalar leaves in the same
order as the layout's own `:flat` vector. `kotoba.compiler.component-core`'s
new `nested-record-identity-function?` predicate and `nested-record-wat`
emitter are the exact same shape as the existing scalar-record identity path
(`scalar-record-identity-function?`/`scalar-record-wat`), generalized to
consume `layout-leaves` instead of the record layout's top-level `:fields`
directly; a pure-scalar record's leaves are identical to its top-level
fields, so the existing scalar-only identity slice is unaffected and
continues to dispatch through its own unchanged predicate and case. WIT
generation (`component-wit/emit`) and Canonical export planning
(`canonical-abi/export-plan`) required no changes: both were already generic
over nested record references.

An executable Wasmtime check of
`echo({id: 7, inner: {code: 3, ratio: 1.5}, active: true})`, for a record
`demo-outer { id: s64, inner: demo-inner, active: bool }` wrapping
`demo-inner { code: s64, ratio: f64 }`, returns the same nested record
unchanged, under locally available Wasmtime 42.0.1. As with ADR 0048, this is
implementation evidence only; the pinned baseline still requires Wasmtime
major 43 or newer, and no automated test in this repository invokes
Wasmtime (the check was run manually, matching how the ADR 0048 record round
trip was verified).

Fail-closed boundaries verified directly (`kotoba.compiler.component-core/
assert-supported!` and `kotoba.compiler.component-artifact/
assert-scalar-slice!` both reject with "component function body has no
qualified Canonical lowering" before any component encoding is attempted):

- two levels of nesting (a nested field whose own field is itself a nested
  record);
- a nested field with a non-scalar (string) leaf;
- a nested field descriptor whose sealed schema identity does not resolve
  back to itself in the schema table (drifted/unsealed identity);
- a computed nested-record body (construction, projection, or anything other
  than a bare parameter passthrough).

Record projection, construction, update, `typed-cap-call` request/result
payloads, and provider components (`kotoba.compiler.component-composition`)
are all untouched by this change and remain restricted to top-level-scalar
records only, exactly as ADR 0043 through ADR 0048 left them; none of the
seven capability kits' `:wasm-aot` qualification changes as a result of this
ADR; none of `resources/kotoba/lang/capability-kits/*.edn` is modified.
Native execution is not attempted here either, consistent with ADR 0049 (no
Component Model slice in this repository has native evidence yet; the native
provider syscall/codec ABI is a separate, still-pending gap).

## Evidence

- focused suite (`kotoba.compiler.canonical-abi-test`,
  `kotoba.compiler.component-artifact-test`,
  `kotoba.compiler.component-composition-test`,
  `kotoba.compiler.component-wit-test`): 19 tests, 75 assertions, 0 failures,
  run against the pinned `wasm-tools 1.243.0`.
- full `clojure -M:test` suite: 398 tests, 4304 assertions, 0 failures, same
  pinned toolchain.
- `wasm-tools validate` on the produced nested-record identity component:
  passed.
- manual Wasmtime 42.0.1 invocation of the nested-record identity export
  (above): returned the input unchanged.

## Remaining gaps

Nested lists, tuples, options, results, variants (including a variant case
wrapping a record, the shape `state-v1`'s result actually needs), strings
inside records at any depth, more than one level of record nesting, nested
aggregates crossing a `typed-cap-call` request/result boundary, and every
production provider's real semantics all remain closed. This ADR closes one
narrow, honestly-scoped slice of the "nested structured values" gap recorded
as remaining gap 1 in ADR 0049; it does not close that gap, and it does not
move any capability kit's qualification status.
