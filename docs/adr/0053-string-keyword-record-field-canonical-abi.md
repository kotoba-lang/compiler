# ADR 0053: String/keyword record-field Canonical ABI identity

Status: accepted; string/keyword record-field identity slice implemented

## Decision

A direct identity export (the body is exactly its sole parameter, unchanged)
may use one sealed flat nominal record whose fields are each a Canonical
scalar (`i64`/`f32`/`f64`/`bool`) or a bounded `string`/`keyword` leaf. No
nesting and no variant payload are admitted in this slice -- a field that is
itself a record reference (the ADR 0051 shape) remains fail-closed exactly as
before. This is the shape named as the next-needed gap in both ADR 0051's own
"Remaining gaps" ("strings inside records at any depth") and, more precisely,
ADR 0052's own "Remaining gaps" section: "strings or keywords inside a case's
record payload, so `state-v1`'s actual `entry`/`error` records remain
closed." `state-v1.edn`'s `entry` record (`key: keyword, value: string,
version: i64`) is exactly this shape, and is the concrete motivating and
validating target built and run here.

Two separate, honestly-scoped gaps needed closing to reach that target:
whether `:keyword` has any Canonical ABI representation at all, and how a
record field (as opposed to a bare top-level parameter/result) carries a
variable-length value through the flat core-wasm parameter list and the
in-memory result area.

**Keyword has no prior Canonical ABI representation anywhere in this
codebase** (`kotoba.compiler.canonical-abi/layout*` only recognized
`:i64`/`:f32`/`:f64`/`:bool`/`:string` plus `:ref`/inline record/variant
descriptors; `:keyword` fell through to "descriptor has no qualified
Canonical ABI layout"). `kotoba.compiler.value` already treats a keyword as a
bounded string at the KIR/value level -- `bounded-keyword!` validates UTF-8
byte length against `keyword-value-byte-limit` (512), the exact same shape
`bounded-string!`/`string-value-byte-limit` (65536) give a plain string, just
a tighter bound -- and the machine-readable contract
(`resources/kotoba/lang/component-model-v1.edn`) already declared this intent
before any code implemented it: `:keyword {:wit :string :validation
:kotoba-canonical-keyword}`, distinct from `:string {:wit :string :validation
:kotoba-utf8-byte-limit}` only in which bound applies. This ADR chose to
treat a keyword identically to a bounded string for Canonical ABI purposes
(pointer+length in linear memory, UTF-8 encoding, the exact ADR 0040/0041
machinery), bounded by `value/keyword-value-byte-limit` instead of
`value/string-value-byte-limit` -- reusing the existing mechanism rather than
inventing a second one, as the task instructions and the contract file's own
prior intent both pointed to. `kotoba.compiler.component-wit/type-text`
already mapped both `:string` and `:keyword` to WIT `string` before this ADR
(`(contains? #{:string :keyword} descriptor) "string"`, unchanged since
before this session), so WIT generation needed no changes here either.

For the record-field mechanism: `canonical-abi/record-layout` already computed
a correct size/alignment/`:flat` for a record with a `:string` (or now
`:keyword`) field before this change (it calls `layout*` on every field type,
and `layout*` already handled `:string` as a `size 8 alignment 4 flat [:i32
:i32]` leaf) -- this was previously unreachable by any codegen path because
every predicate that admitted a record identity export
(`sealed-scalar-record`, and by extension `scalar-record-identity-function?`
and ADR 0051's `nested-scalar-record-schema`) required every field to be in
`#{:i64 :f32 :f64 :bool}` exactly. The new `string-field-record-schema` widens
that admitted field set to `#{:i64 :f32 :f64 :bool :string :keyword}`,
deliberately *not* by widening `sealed-scalar-record` itself: projection,
construction, update, `typed-cap-call` request/result admission
(`record-capability-call`), and a variant case's payload admission
(`sealed-scalar-variant-schema`) all key off `sealed-scalar-record`, and
their WAT emitters (`scalar-record-projection-wat`/`scalar-record-write-wat`/
`record-capability-wat`/`variant-case-body`) only know `wasm-value-type`/
`wasm-store`, neither of which has a `:string`/`:keyword` case -- widening
`sealed-scalar-record` itself would have made those paths silently claim
admission for a shape their own emitters cannot correctly generate. A new,
dedicated `string-field-record-identity-function?` (distinct from
`scalar-record-identity-function?` by requiring at least one field be a
string or keyword leaf, so the two dispatch cases never overlap) and
`string-field-record-wat` are the only consumers of the wider set.

`canonical-abi/layout-leaves` (ADR 0051) is extended, not replaced: a field
whose own layout carries a `:max-bytes` key (a bounded string/keyword) is now
exposed as `{:offset :descriptor :max-bytes}` instead of the plain scalar
leaf's `{:offset :descriptor}`, alongside the unchanged `:fields`-recursion
case for one-level-nested records. `string-field-record-wat` reads this the
same way `nested-record-wat` reads plain scalar leaves, except a
`:max-bytes` leaf takes two core wasm parameters (`$fN-ptr i32`/`$fN-len
i32`) instead of one and is stored as that same pointer+length pair at its
field offset (`offset`/`offset+4`) -- the identical linear-memory shape ADR
0040/0041 already gave a bare string parameter/result. The received pointer
already refers to guest memory the caller populated (via this module's own
`$realloc`) before invoking the export, so passthrough needs no byte copy --
simpler than `string-expression-wat`'s concatenation path, which does copy,
because concatenation genuinely builds new bytes and identity does not. What
*is* reused verbatim from `string-expression-wat` is its bounds check
(`validate-parameters`): before a received pointer is trusted enough to store
into the result record, `string-field-record-wat` checks the leaf's own
length against its field's `max-bytes` and the pointer range against the
module's own linear memory capacity, trapping (`unreachable`) on either
violation. Memory page count is sized the same generous way
`string-expression-wat` already sizes it (one extra 64 KiB page per
string-like leaf plus the record's own struct size), not tightly optimized,
matching that precedent rather than inventing a tighter allocator.

One case was built, `wasm-tools`-validated, and manually run under Wasmtime
42.0.1 (`wasmtime run --invoke 'echo(<record>)' <component>.wasm`, following
the same manual-invocation precedent as ADR 0048/0051/0052 -- no automated
Wasmtime test exists in this repository): `demo-state-entry { key: string,
value: string, version: s64 }` (WIT necessarily spells both `key`'s Kotoba
`:keyword` and `value`'s Kotoba `:string` as WIT `string`; the distinction is
Kotoba-side bound checking, not a WIT-visible type). `echo({key:
"kotoba/status", value: "ready", version: 42})`,
`echo({key: "a", value: "", version: -9223372036854775808})` (full negative
i64 range plus an empty string leaf), and `echo({key: "namae/漢字", value:
"日本語のテスト🎉", version: 9223372036854775807})` (full positive i64 range
plus multi-byte UTF-8 in both leaves) each returned the same record
unchanged. A second, degenerate shape with no scalar field at all
(`demo-label { text: string }`, one bare string leaf) was also built,
validated, and round-tripped (`echo({text: "single-field string-only
record"})`) unchanged, confirming the identity path does not require a
scalar leaf to be present -- only that no field fall outside the admitted
set.

Both components' preambles and `wasm-tools validate --features
component-model` passed. As with ADR 0048/0051/0052, this is implementation
evidence only; the pinned baseline still requires Wasmtime major 43 or newer.

Fail-closed boundaries verified directly. At the schema-admission level
(`component-core/assert-supported!` rejects with "component function body has
no qualified Canonical lowering" before any component encoding is
attempted):

- a field that is itself a nested record reference (the ADR 0051 shape),
  even when another field of the same record is a plain string/keyword leaf
  -- string/keyword leaves are only admitted at the top flat level in this
  slice, never inside a nested field;
- a field type outside the admitted set entirely (`[:vector :i64]`), which
  also serves as the updated regression case for the pre-existing ADR 0043
  test that used to assert a bare `:string` field stayed closed -- it no
  longer does, by design, so that assertion now uses a genuinely
  still-unsupported field type instead;
- a record schema whose sealed identity has drifted from the schema table;
- a computed body (anything other than a bare parameter passthrough).

At the pure layout level (`canonical-abi-test`), `canonical/layout :keyword`
returns the same shape as `:string` with `max-bytes 512` instead of `65536`,
and `layout-leaves` on a record mixing keyword/string/i64 fields
(`demo/state-entry`) returns `[{:offset 0 :descriptor :keyword :max-bytes
512} {:offset 8 :descriptor :string :max-bytes 65536} {:offset 16 :descriptor
:i64}]` with the record's own `:flat` correctly showing five joined core
values (`[:i32 :i32 :i32 :i32 :i64]`, two per string-like leaf).

At the Wasmtime-execution level, both byte bounds trap for real when
exceeded, exercised directly rather than only implemented defensively: a 513
-byte `key` (one byte past `keyword-value-byte-limit`) against the
`demo-state-entry` component traps (`wasm trap: wasm \`unreachable\`
instruction executed`, process exit 134), while a 512-byte `key` at exactly
the bound succeeds; a 65537-byte `value` (one byte past
`string-value-byte-limit`) against the same component traps the same way.

Record projection, construction, update, `typed-cap-call` request/result
payloads, provider components (`component-composition`), one-level-nested
record fields, and variant case payloads are all untouched by this change and
remain restricted to `sealed-scalar-record`'s original
`#{:i64 :f32 :f64 :bool}` field set only, exactly as ADR 0043 through ADR
0052 left them -- confirmed directly by the updated ADR 0043 regression test
now using an out-of-set field type instead of `:string`, and by the
unaffected ADR 0051 nested-record test (a nested field with a `:string` leaf,
one level down through a record ref, remains fail-closed: the outer schema's
field type there is `[:ref :demo/inner-str]`, not `:string`/`:keyword`
directly, so `string-field-record-schema`'s flat field-type check never
admits it). None of the seven capability kits'
`:wasm-aot`/`:native-aot`/`:jit` qualification changes as a result of this
ADR; none of `resources/kotoba/lang/capability-kits/*.edn` is modified --
`state-v1`'s actual `result` type is a *variant* wrapping these records
(`found`/`written`: `entry`; `error`: a similarly shaped `error` record), and
a variant case wrapping a record with string/keyword fields is not attempted
here (ADR 0052's `sealed-scalar-variant-schema` still only admits
`sealed-scalar-record` case payloads); the request side, a real
`typed-cap-call` crossing carrying these fields, and a real production state
provider are all still separately gapped. Native execution is not attempted
here either, consistent with ADR 0049/0051/0052.

## Evidence

- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`): 28 tests, 120
  assertions, 0 failures, run against the pinned `wasm-tools 1.243.0`.
- full `clojure -M:test` suite: 407 tests, 4349 assertions, 0 failures, same
  pinned toolchain.
- `wasm-tools validate --features component-model` on both produced
  string/keyword-field record identity components: passed.
- manual Wasmtime 42.0.1 invocation of both identity exports (five successful
  round trips total, listed above, covering full i64 range and multi-byte
  UTF-8 in both a string and a keyword leaf in the same call): each returned
  the input unchanged.
- manual Wasmtime 42.0.1 invocation exercising both byte bounds for real: a
  513-byte keyword field and a 65537-byte string field each trap
  (`unreachable`, process exit 134); a 512-byte keyword field at exactly the
  bound succeeds.

## Remaining gaps

A variant case wrapping a record with string/keyword fields (so
`state-v1`'s actual `result` type -- a variant over `entry`/`error`, both of
which need this ADR's fields -- remains unqualified end to end), string/
keyword leaves inside an ADR 0051 one-level-nested record field, nested
lists, tuples, options, results, a variant used as a record field, string/
keyword fields crossing a `typed-cap-call` request/result boundary, more than
one string-like leaf's worth of realistic memory pressure (the two shapes
built here have at most two string-like leaves; the page-sizing formula is
generous but untested against many-field records), and every production
provider's real semantics all remain closed. This ADR closes one further
narrow, honestly-scoped slice of the "nested structured values" gap recorded
as remaining gap 1 in ADR 0049 and named explicitly in both ADR 0051's and
ADR 0052's own remaining gaps; it does not close that gap, it does not make
`state-v1` executable end to end, and it does not move any capability kit's
qualification status.
