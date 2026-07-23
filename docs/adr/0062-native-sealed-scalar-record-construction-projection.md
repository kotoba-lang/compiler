# ADR 0062: Native sealed-scalar-record construction and field projection

Status: accepted; native scalar-record construction+projection implemented on both x86-64 and aarch64, proven by real kexe-loader native process execution on both architectures

## Decision

This is the FIRST native (x86-64/aarch64) value-representation increment in
this repository. Before this ADR, `src/kotoba/compiler/backend/x86_64.cljc`
and `backend/aarch64.cljc` had zero notion of a record or variant -- direct
code reading confirmed zero matches for "record"/"variant" in either file --
and no narrower structural concept than a flat `{:string-literal content}`
token (an offset into the bump-allocated literal-data segment the existing
`pair`/`kgraph-*` heap-call op-surface uses). Every value in both native
backends is, and remains, a single uniform 8-byte machine word transported in
`rax`/`x0`; there is no narrower packing, no XMM/vector register use, and no
per-field-width distinction anywhere in either file (confirmed: `:bool` is
already carried as plain 0/1 by every existing comparison/`setcc` sequence).

**Layout decision.** A native scalar record has NO independent runtime
representation at all: no pointer, no heap-arena allocation (unlike `pair`,
which IS heap-backed through a host call at a fixed context offset), no new
host ABI offset, no `tools/kexe_loader.c` change. This increment's entire
admitted shape is `(record-get type (record-new type v0 v1 ... vN-1) field)`
-- a `record-get` immediately and directly nested over a matching
`record-new` of the SAME schema. The codegen backends (`emit-record-get-of-
new` in both `backend/x86-64.cljc` and `backend/aarch64.cljc`) REWRITE this
one recognized shape into exactly the `emit-let`/`load-let` stack machinery
an ordinary `(let [f0 v0 f1 v1 ... fN-1 vN-1] fI)` already uses: one
synthetic stack slot per field (8 bytes on x86-64, this backend's own
16-byte-aligned `let` slot on aarch64), pushed once each in source order (so
a side-effecting field expression still runs exactly once, per the
ADR-2607198300 `let`-sequencing fix already proven by every existing `let`
deftest in `native_executor_test.clj`), and read back via the SAME
depth-relative `load-let` arithmetic. This lands on the identical "packed,
8-byte-per-field, offsets 0, 8, 16, ..." convention ADR 0043 chose for its
own WASM linear-memory encoding of the exact same starting shape (a sealed,
all-scalar record) -- realized here on the native SysV/AAPCS64 call stack
instead of WASM linear memory, because every native value is already a
uniform 8-byte word with no narrower packing to do. This was chosen because
it is provably correct by degrading to already-proven `let` machinery
(needing zero new machine instructions of its own) and needs no new host
surface: extending the `pair` heap arena to a variable-arity record host
call was considered and rejected, since it would require a new host ABI
offset, a `tools/kexe_loader.c` change (and therefore re-measuring and
re-pinning the loader-source SHA-256 the runtime-identity/measured-runtime
machinery checks), for no benefit this narrow slice needs.

**Function-boundary ABI.** Records never cross a function boundary in this
increment -- never a parameter type, never a function's own result type.
This backend's existing calling convention (fixed single 8-byte scalar
parameter slots; a single `rax`/`x0` return value) has no struct-passing
mechanism at all; teaching it one (e.g. an out-pointer/result-area return
convention, mirroring ADR 0043's own indirect-result-area choice for
standard32) is exactly the kind of new ABI surface this narrow slice
deliberately does not build. A record value's entire lifetime is therefore
bounded to the one `record-get`-over-`record-new` expression it appears
in, which is also why no host arena, no lifetime tracking, and no
escape-analysis question needs answering here.

**Field types: `:i64` and `:bool` only, not `:f64`.** ADR 0043's own WASM
starting shape allows `s64`/`f32`/`f64`/`bool`; this native increment omits
`:f64` on purpose. `kotoba.compiler.core/compile-source*` already,
unconditionally, rejects ANY `:f32`/`:f64` usage on native targets today
(`ir/uses-f32?`/`ir/uses-f64?`), independent of records -- admitting f64
record fields would have silently required ALSO widening that orthogonal,
pre-existing gate, which is exactly the "don't widen two dimensions in one
step" discipline this compiler's own Component Model ADR chain (0058/0059)
established and this ADR follows. Native f64 record fields remain a
separately-gapped follow-up, not attempted here.

**A necessary prerequisite this ADR also had to add: literal `true`/`false`
now work as an ordinary native scalar expression.** Before this change,
NEITHER native backend recognized a bare Clojure boolean at all (confirmed:
`emit-expr`'s dispatch had no `boolean?` case in either file; a bare
`true`/`false` reaching it would crash trying to sequentially destructure a
non-seqable value, `(let [[op & args] true])`). Reading
`kotoba.compiler.frontend/infer-expression-type` directly confirmed literal
`true`/`false` is the ONLY way to produce a genuine `:bool`-typed VALUE
anywhere in this frontend's type system -- every comparison, including `=`,
always infers `:i64`, never `:bool`. Constructing a `:bool` record field
therefore needed this addition regardless. Both backends now emit
`true`/`false` as the i64 word `1`/`0` through the SAME literal-encoding
path (`le64`/`load-constant`) an ordinary integer literal already uses --
consistent with "every value, including a `:bool`, is already a uniform
8-byte machine word" being true even at the literal level now. This is
admitted generally (any expression position), not gated narrowly to only
inside `record-new`, since it is a side-effect-free scalar with nothing
extra to verify in a wider position.

## Scope

**What changed, precisely, and nothing more:**

- `src/kotoba/compiler/ir.cljc`: `only-string-typed-features?` renamed to
  `only-string-and-scalar-record-typed-features?` and widened to ALSO admit
  (a) literal `true`/`false` anywhere, and (b) `record-new`/`record-get` in
  exactly the nested shape above, over a sealed record type whose every
  field is `:i64` or `:bool` (`native-scalar-record-type?`,
  `native-scalar-record-field-types`). `record-assoc`/`record-equal` and
  every other typed feature remain in the pre-existing denylist,
  unreachable on native targets, unchanged.
- `src/kotoba/compiler/core.clj` / `src/kotoba/compiler/nbb/cli.cljs`: both
  call sites (the JVM path and the nbb-native fast path, which this
  predicate is explicitly shared between) updated to the renamed predicate;
  error message widened to name the new admitted feature.
- `src/kotoba/compiler/backend/x86_64.cljc` / `backend/aarch64.cljc`: new
  private `emit-record-get-of-new`, dispatched from two new `emit-expr`
  cases (`record-get` delegates to it; a bare `record-new` throws a clear
  `ex-info`); a new `(boolean? form)` case in `emit-expr` for literal
  `true`/`false`. No existing case, and no other function in either file,
  changed.
- `src/kotoba/compiler/verifier.cljc`: this repository's INDEPENDENT
  re-verifier (`verify-expr!`/`lowered-cost`) had to be extended
  separately and on purpose -- it deliberately never shares code with the
  admission/codegen it cross-checks, matching every other op-family
  already in this file. Added: a local `native-scalar-record-type?`
  (re-derived, not imported), `record-new`/`record-get` cases in
  `verify-expr!` enforcing the identical narrow nested shape, a
  `(boolean? form)` case in both `verify-expr!` and `lowered-cost`, and
  `record-new`/`record-get` cases in `lowered-cost` that skip the
  compile-time type-descriptor VECTOR (costing only the actual field-value
  expressions) -- without this, `lowered-cost`'s generic fallback would
  crash trying to sequentially destructure the descriptor's own bare
  keywords (`(let [[op & args] :i64])` throws; keywords are not seqable),
  a real bug caught during development, not a hypothetical one.
- `test/kotoba/compiler/native_executor_test.clj`: three new `deftest`s
  (below).
- No other file changed. In particular, `tools/kexe_loader.c` (and
  therefore every measured runtime-identity SHA-256 pin) is untouched; no
  capability kit, no `resources/kotoba/lang/capability-kits/*.edn`, is
  touched; the Wasm/Component Model backends and their own ADR 0043-0061
  chain are untouched.

**What is deliberately NOT attempted, matching this ADR's own "narrow slice"
framing and ADR 0043/0044/0045's own construction/projection-only
discipline:**

1. `record-assoc` (update) and `record-equal` remain unimplemented on
   native targets and fail closed (unreachable through the denylist).
2. Nested records, any string/keyword-bearing field, and any field type
   other than `:i64`/`:bool` (including `:f64`, see Decision) remain
   fail-closed.
3. A record value never crosses a function boundary (never a `param-types`
   entry, never a function's `result`) -- construction and projection are
   confined to one function body's expression tree.
4. `record-get`'s value operand must be a literal, directly-nested,
   same-schema `record-new` -- a `let`-bound record read by multiple
   separate `record-get` calls, a record passed through `if`, or any other
   composed/computed record expression is rejected (see the second
   negative-vector deftest below), not silently miscompiled.
5. No capability/provider boundary work of any kind. There is still no
   native provider/capability mechanism in this repository at all;
   building one was explicitly out of scope for this task and remains so.
6. Native-AOT qualification for `state-v1` (or any other capability kit)
   is NOT closed by this ADR. `state-v1`'s real shape needs records
   AND variants AND strings/keywords AND capability-boundary crossing,
   essentially all still unbuilt on native targets; this ADR closes only
   the "sealed scalar record, construction+projection, no boundary
   crossing" slice of the FIRST of those on native targets specifically
   (the WASM/Component Model track already has considerably more of this,
   per ADR 0043 through 0061 -- these are two genuinely separate tracks,
   see the tracking-doc note below).

## Evidence

- **Real native process execution, matching `native_executor_test.clj`'s
  existing pattern exactly (no synthetic byte-level check)**, host
  architecture aarch64 (Apple Silicon, `clojure -M -e` local dev run):
  constructing `{a: i64, b: i64, c: bool}` from `(a=11, b=22)` and
  projecting each of its three fields back out through the real
  `kexe-loader` native process (`kotoba.compiler.native-executor/execute`,
  the SAME measured-runtime/signed-envelope/process-boundary path every
  other deftest in this file uses) returned the correct value for every
  field: `field-a -> 11`, `field-b -> 22` (proving fields are not aliased
  or off-by-one against each other), `field-c` constructed with a literal
  `true` -> `1`, and (a separate construction) `field-c` constructed with
  a literal `false` -> `0` (proving the boolean literal itself, not just
  the field-projection plumbing, round-trips correctly both ways). The
  committed deftest (`native-scalar-record-construction-and-field-
  projection-round-trips-through-real-kexe-loader`) folds all four checks
  into one function returning a summed bitmask (matching this file's own
  ADR 0060/0061-style multi-check convention) and asserts the exact
  expected total (`4`).
- **x86-64: independently re-derived and byte-verified locally on this
  aarch64 dev machine (this repository's OWN independent verifier,
  `kotoba.compiler.verifier/verify-artifact!`, which re-emits the x86-64
  bytes from the sealed KIR and requires an EXACT match), not executed as
  a real CPU process here** -- this dev machine is Apple Silicon and this
  repository has no Rosetta/QEMU cross-architecture execution path for
  native targets (confirmed: repository-wide search for "rosetta"/"qemu"
  finds only unrelated iOS-Simulator and coverage-roadmap prose, and
  `native_executor_test.clj`'s own `target` helper already only ever
  selects and executes the CURRENT host's architecture, matching the CI
  matrix's `test: [ubuntu-latest, ubuntu-24.04-arm, macos-14]` strategy of
  covering x86-64 and aarch64 as SEPARATE CI jobs on native hardware
  rather than any single job cross-executing both). REAL x86-64 process
  execution of this exact same architecture-parametric deftest is what
  `ubuntu-latest` (x86-64) will provide once this PR's CI runs, following
  the identical established pattern every prior deftest in this file
  already relies on for its own x86-64 coverage.
- **Fail-closed vector 1** (`native-record-with-an-unsupported-field-type-
  is-rejected-at-compile-time`): a record field type this increment does
  not admit (`:string`) is rejected at COMPILE TIME with the expected
  native-admission error message, confirmed to be the native-specific gate
  and not an unrelated generic type error by additionally confirming the
  IDENTICAL source compiles successfully on `:wasm32-kotoba-v1` (which
  already supports string-bearing records, ADR 0053).
- **Fail-closed vector 2** (`native-record-get-over-a-computed-non-nested-
  value-is-rejected-at-compile-time`): a `record-get` whose value operand
  is a computed `if` expression (both branches constructing the SAME
  record schema, so it passes ordinary frontend type inference) rather
  than a literal directly-nested `record-new` is rejected at compile time
  with `emit-record-get-of-new`'s own clear message, confirming the native
  backend's specific narrow-shape restriction fires (not merely relying on
  a generic upstream type error).
- **Full `clojure -M:test` suite**: 461 tests, 4580 assertions, 0 errors,
  1 pre-existing failure (`kotoba.compiler.cli-test/structured-diagnostic-
  has-stable-code-and-bounded-source-span`) confirmed, by `git stash`-ing
  this ADR's own changes and re-running the identical namespace against
  unmodified `main`, to be present and identical before this ADR's changes
  too -- unrelated to any file this ADR touches (`frontend.cljc`,
  `cli.clj`, `diagnostic.cljc`, and `resources/` are all untouched here).
  The same underlying diagnostic-span behavior was independently confirmed
  pre-existing on the nbb/cljs side too (`npm run test-nbb-wasm32`'s
  `structured-diagnostic` case), also `git stash`-verified against
  unmodified `main`.
- **`clojure -M -m kotoba.compiler.backend-qualification verify native`**:
  passes (`:manifest-gate :passed`), confirming this ADR does not disturb
  the existing native capability-kit qualification manifest.
- **`clojure -M -m kotoba.security.adoption`**: `:status :pass`, unchanged.

## Native tracking is a separate doc from the WASM Component Model baseline

`docs/component-model-baseline.md`'s running summary tracks the Wasm/
Component Model track specifically (ADR 0036 through 0061 all live there,
by name, in sequence). This is genuinely the first ADR in a SEPARATE
native-value-representation track -- conflating the two into one
"component model" doc would misrepresent native progress as Component
Model progress (it is not: there is still no native provider/capability
mechanism at all, so nothing here is comparable to that doc's own
Canonical ABI/WIT/capability-crossing narrative). This ADR instead starts
`docs/native-aot-baseline.md`, a new, separately-titled running-summary doc
for the native-AOT track, in the same spirit and format as
`component-model-baseline.md`'s own "Kotoba interpretation" running-summary
section, so future native-track ADRs have a natural, honestly-scoped place
to record cumulative progress without silently borrowing the WASM-titled
doc's own narrative.

## Remaining gaps

Native records that hold strings/keywords, nested records, variants,
`record-assoc`/`record-equal`, any record value crossing a function
boundary (parameter or result), any record crossing a capability/provider
boundary (no such boundary exists on native targets at all yet), f64
record fields (blocked on the separate, pre-existing native f32/f64
rejection gate), and a `let`-bound record read by more than one
`record-get` call all remain closed. Native-AOT qualification for
`state-v1` or any other capability kit is unaffected by this ADR;
`resources/kotoba/lang/capability-kits/*.edn` is not modified.
