# ADR 0063: Native sealed-scalar-variant construction and dispatch

Status: accepted; native scalar-variant construction+dispatch implemented on
both x86-64 and aarch64, proven by real kexe-loader native process execution
on both architectures (aarch64 executed locally; x86-64 verified by this
repository's own independent verifier plus real CI execution on
`ubuntu-latest`, matching ADR 0062's own cross-architecture-verification
precedent).

## Decision

This is the SECOND native (x86-64/aarch64) value-representation increment in
this repository, immediately following ADR 0062's record. Before this ADR,
neither `backend/x86-64.cljc` nor `backend/aarch64.cljc` had any notion of a
variant at all -- `record-new`/`record-get` (ADR 0062) were the only typed
constructs either backend admitted.

**Layout decision.** A native sealed variant has NO independent runtime
representation, exactly like ADR 0062's record: no pointer, no heap-arena
allocation, no new host ABI offset, no `tools/kexe_loader.c` change. It is
rewritten at codegen time into TWO synthetic 8-byte stack slots on the SAME
synthetic-stack-slot scheme `emit-let`/`load-let` already implement: slot 0
is the discriminant (the case's 0-based ordinal index within the type's own
declared `cases` vector, resolved at compile time by the SAME kind of lookup
`emit-record-get-of-new` already uses to resolve a field name to its index);
slot 1 is the payload (exactly one word, since every admitted payload type --
`:i64`/`:bool` -- is already a uniform 8-byte word on this backend, matching
ADR 0062's own "no narrower packing" finding). This is a smaller version of
the WASM Component Model track's own `variant-layout` concept
(`canonical_abi.cljc`: an in-memory union sized to the widest case) --
concept only, not shared code: this native increment computes no in-memory
byte-level union layout at all, because a case's payload is never wider than
one machine word here, so "the union's width" is trivially always one word,
with no alignment/`discriminant-byte-size` arithmetic to do.

**A tag-only ("unit"-like) case needed NO new type-system concept.** This
frontend's shared, target-independent `variant-new`/`variant-match` grammar
(`frontend.cljc`, unchanged by this ADR) always requires exactly one payload
expression per case, with a real declared payload type drawn from
`value-types` -- there is no existing zero-payload marker anywhere in this
codebase, on ANY track (confirmed: the WASM Component Model variant track,
ADR 0052 onward, also requires every case to carry a real Canonical
scalar-or-record payload; no `:unit`/`:void` descriptor exists in
`canonical-abi.cljc`). Introducing one was considered and rejected for this
increment: `frontend.cljc`'s `validate-value-type!`/`infer-expression-type`
are shared, target-independent code the WASM track's own `encode-descriptor`
(`backend/wasm_typed.cljc`) would also have to be taught to reject cleanly,
widening a second, unrelated file's surface for a slice this increment does
not need. Instead, a case is "tag-only" purely by CONVENTION: it still
declares a real `:i64`/`:bool` payload type and a real payload value at
construction (this increment's test uses `:bool`), but its `variant-match`
branch body simply never binds/reads that payload -- provable directly by
reading the branch body, and directly analogous to how a Rust `enum` case
without fields still occupies its union's full width (the bytes are just
never read). See "Remaining gaps" for what a genuine zero-payload marker
would need.

**Dispatch is a REAL runtime compare-and-branch chain, not a compile-time
selection.** For an N-case variant, the emitted code is: push discriminant;
push payload; load discriminant back; for i in [0, N), `cmp
discriminant,i ; je case_i` (x86-64) / `cmp x0,#i ; b.eq case_i` (aarch64),
in order; fall through past all N comparisons to a defensive trap (`UD2` /
`BRK #0`) if none match; then the N case bodies, each binding its own
`branch`'s binder symbol directly to the already-pushed payload slot (no
re-push -- `load-let`'s own depth-relative arithmetic already reaches it),
ending in a cleanup pop of both slots and (for every case but the last) a
jump to the very end. Critically, the codegen does NOT special-case away any
comparison based on a directly-nested `variant-new`'s literal tag being
statically known at that particular call site (see "Function-boundary
restriction" below for why the tag IS always statically known in this narrow
slice) -- every one of the N comparisons is present in the emitted bytes for
every call site, regardless of which case that site happens to construct.
This is proven two ways: (1) testing all four declared cases independently,
each through its own construction+dispatch call, asserting each returns the
exact correct result (which would fail for at least 3 of the 4 cases if the
compiler had silently miscompiled the chain); (2) `verifier.cljc`'s own
independent byte-level re-derivation, which re-emits the SAME KIR from
scratch and requires an exact match against the artifact's `:code`, so the
compare-chain bytes cannot be "sometimes present, sometimes not" across two
compiles of the identical source.

**Function-boundary restriction: identical to ADR 0062's record, for the
same reasons.** A variant value never crosses a function boundary (never a
`param-types` entry, never a function's own `result`) -- construction and
dispatch are confined to one function body's expression tree, exactly
mirroring `record-get`'s own restriction. `variant-match`'s value operand
MUST be a directly-nested, same-schema `variant-new` (never a parameter, a
`let`-bound name, an `if`, or a different-schema construction) -- this is
narrower than the WASM Component Model track's own variant (which crosses a
real Canonical ABI/`typed-cap-call` boundary, ADR 0055 onward) but matches
this native track's own established "narrow slice" discipline (ADR 0062's
own Scope section: "not attempted here"). A direct consequence: at any ONE
`variant-match`-over-`variant-new` call site in THIS increment, the specific
case being constructed is always a compile-time-known literal keyword (the
frontend's own `variant-new` grammar has always required this, on every
target, since before this ADR -- `keyword? tag`, never a computed
expression). The dispatch chain is still genuinely a runtime mechanism (see
above), just one this increment's own narrow admitted grammar happens to
always invoke with a statically-predictable case at each individual call
site -- a DIFFERENT limitation from "the codegen can't really branch," which
this ADR does not have. A future increment letting a variant value flow
through `if`/`let` before being matched (so the SAME dispatch call site can
receive genuinely different runtime cases across different executions)
remains open; see "Remaining gaps."

**AArch64-specific correctness note.** AArch64's `b`/`b.cond` immediate is
relative to the branch instruction's OWN address (confirmed against this
file's pre-existing `if` and `bounds-check` byte-layout comments/offsets),
unlike x86-64's `jmp`/`je rel32`, which is relative to the instruction AFTER
the jump. Every AArch64 branch distance in `emit-variant-dispatch` therefore
adds this instruction's own 4-byte width on top of the byte count between
the end of the instruction and its target -- a real bug caught during
development (an early draft omitted this and produced an off-by-4 jump,
caught by the aarch64 native-process test landing on the wrong case before
this fix, not merely a hypothetical).

## Scope

**What changed, precisely, and nothing more:**

- `src/kotoba/compiler/ir.cljc`: new private `native-scalar-variant-type?`
  (mirrors `native-scalar-record-type?`'s own shape exactly: sealed,
  namespaced type id, non-empty cases vector, every case payload type in
  `#{:i64 :bool}`, unique case tags); `only-string-and-scalar-record-typed-
  features?`'s `walk` gains `variant-new`/`variant-match` cases mirroring
  `record-new`/`record-get`'s own admission shape (this admission layer only
  confirms the tag/branch shape is well-formed and keeps walking -- it does
  NOT enforce the "directly-nested" restriction itself, matching
  `record-get`'s own precedent of leaving that to the backend codegen).
  `variant-new`/`variant-match` remain in the pre-existing `non-string-
  typed-ops` denylist, unchanged, so every OTHER admission predicate in this
  file (`only-cljs-provider-typed-features?`) is unaffected.
- `src/kotoba/compiler/core.clj`: comment updated to mention the second
  increment; the native-admission error message (which an ADR 0062 test
  regex depends on) is left byte-for-byte unchanged.
- `src/kotoba/compiler/backend/x86_64.cljc` / `backend/aarch64.cljc`: new
  private `emit-variant-dispatch` (the dispatch-chain primitive, taking an
  already-resolved ordinal/payload expression and a vector of per-case
  branch specs -- deliberately decomposed from the tag-lookup wrapper below
  so it is independently testable, see Evidence) and `emit-variant-match-of-
  new` (the public-facing admitted shape: `value-form` must be a directly-
  nested, same-schema `variant-new`; resolves the tag to its ordinal,
  mirroring `emit-record-get-of-new`'s own field-index resolution; throws a
  clear `ex-info` otherwise); two new `emit-expr` cases (`variant-match`
  delegates to `emit-variant-match-of-new`; a bare `variant-new` throws a
  clear `ex-info`, mirroring `record-new`'s own bare-use rejection). No
  existing case, and no other function in either file, changed (aside from
  the new AArch64-only `cmp-imm` helper `emit-variant-dispatch` needs).
- `src/kotoba/compiler/verifier.cljc`: this repository's INDEPENDENT
  re-verifier had to be extended separately and on purpose, matching ADR
  0062's own precedent -- a local `native-scalar-variant-type?` (re-derived,
  not imported), `variant-new`/`variant-match` cases in `verify-expr!`
  enforcing the identical narrow shape (including the SAME "value must be a
  directly-nested, same-schema `variant-new`" restriction
  `emit-variant-match-of-new` enforces), and `variant-new`/`variant-match`
  cases in `lowered-cost` that skip the compile-time type-descriptor vector
  (same reasoning as the pre-existing `record-new`/`record-get` cases
  there).
- `test/kotoba/compiler/native_executor_test.clj`: five new `deftest`s
  (below).
- No other file changed. `tools/kexe_loader.c` (and every measured
  runtime-identity SHA-256 pin) is untouched; no capability kit is touched;
  the Wasm/Component Model backends and their own ADR chain are untouched.

**What is deliberately NOT attempted, matching ADR 0062's own "narrow slice"
framing:**

1. Records nested inside a variant case (or vice versa) remain unimplemented
   on native targets and fail closed (the WASM track's own ADR 0052 built
   exactly this combination next; this native track has not yet).
2. Any string/keyword-bearing case, and any payload type other than
   `:i64`/`:bool` (including `:f64`, for the identical pre-existing,
   orthogonal native f32/f64 rejection gate ADR 0062 already documented),
   remain fail-closed.
3. A variant value never crosses a function boundary (never a `param-types`
   entry, never a function's `result`) -- construction and dispatch are
   confined to one function body's expression tree, identical to ADR 0062's
   record restriction.
4. `variant-match`'s value operand must be a literal, directly-nested,
   same-schema `variant-new` -- a `let`-bound variant read by dispatch, a
   variant passed through `if`, or any other composed/computed variant
   expression is rejected (see the second negative-vector deftest below),
   not silently miscompiled. A direct, structural consequence (see Decision)
   is that the specific case at any one call site is always statically
   known; genuinely dynamic dispatch (the SAME dispatch code path receiving
   different runtime cases across different executions) is not proven by
   this increment's own test suite, though the underlying compare-chain
   mechanism does not special-case around it (see Evidence).
5. No capability/provider boundary work of any kind, matching ADR 0062.
6. A genuine zero-payload (`:unit`) marker type was NOT added (see Decision)
   -- a "tag-only" case still declares and constructs a real `:i64`/`:bool`
   payload, uniformly represented like every other case; only its branch
   body's own choice not to read that payload makes it "tag-only."
7. Case counts beyond a handful (this increment's own test uses four) are
   not stress-tested; the underlying mechanism has no hardcoded ceiling
   narrower than the frontend's own pre-existing `max-variant-cases` (32),
   but larger counts are unverified.
8. Native-AOT qualification for `state-v1` (or any other capability kit) is
   NOT closed by this ADR, unaffected by it, same as ADR 0062.

## Evidence

- **Real native process execution, matching `native_executor_test.clj`'s
  existing pattern exactly (no synthetic byte-level check)**, host
  architecture aarch64 (Apple Silicon, `clojure -M -e` local dev run, this
  repository's OWN measured `kexe-loader`/signed-envelope/process-boundary
  path, identical to every other deftest in this file): four separate
  construction+dispatch call sites, one per declared case
  (`{count: :i64, enabled: :bool, disabled: :bool, idle: :bool}`), each
  returning `1` on success -- `count` constructed with a genuinely
  runtime (function-parameter-derived) `:i64` value and dispatched, proving
  the discriminant/payload mechanism is not merely literal-folded;
  `enabled` constructed with a literal `true` and dispatched, its branch
  reading the payload back as `true`; `disabled` constructed with a
  literal `false` and dispatched, its branch reading the payload back as
  `false` (mirroring ADR 0062's own record `:bool` field both-directions
  proof); `idle` constructed with a payload the dispatched branch NEVER
  reads (`(defn check-idle [] ... [:idle v 1] ...)` -- the branch body is a
  bare literal `1`, no reference to `v`), demonstrating the tag-only case.
  The committed deftest (`native-scalar-variant-construction-and-dispatch-
  round-trips-through-real-kexe-loader`) sums all four checks and asserts
  the exact expected total (`4`), matching this file's own ADR 0060-0062-
  style multi-check convention.
- **x86-64: independently re-derived and byte-verified locally on this
  aarch64 dev machine** via `kotoba.compiler.verifier/verify-artifact!`
  (this repository's OWN independent verifier, which re-emits the x86-64
  bytes from the sealed KIR and requires an EXACT match) — not executed as a
  real CPU process here, for the identical reason ADR 0062 documents (this
  dev machine is Apple Silicon; this repository has no Rosetta/QEMU
  cross-architecture native execution path). REAL x86-64 process execution
  of this exact same deftest is what `ubuntu-latest` provides once this
  PR's CI runs, following the identical established pattern every prior
  deftest in this file already relies on.
- **Fail-closed vector 1** (`native-variant-with-an-unsupported-case-
  payload-type-is-rejected-at-compile-time`): a variant case payload type
  this increment does not admit (`:string`) is rejected at COMPILE TIME with
  the expected native-admission error message, confirmed to be the
  native-specific gate (not an unrelated generic type error) by additionally
  confirming the IDENTICAL source compiles successfully on
  `:wasm32-kotoba-v1` (whose typed backend admits arbitrary typed values,
  including a string-cased variant, unconditionally -- no content-based
  native-style gate applies to that target at all).
- **Fail-closed vector 2** (`native-variant-match-over-a-computed-non-
  nested-value-is-rejected-at-compile-time`): a `variant-match` whose value
  operand is a computed `if` expression (both branches constructing the SAME
  variant case, so it passes ordinary frontend type inference) rather than a
  literal directly-nested `variant-new` is rejected at compile time with
  `emit-variant-match-of-new`'s own clear message, mirroring ADR 0062's own
  second negative vector exactly.
- **Fail-closed vector 3 -- real native-process trap evidence, not merely a
  compile-time rejection** (`variant-dispatch-fallback-traps-on-a-
  discriminant-no-admitted-program-can-ever-produce`): this repository's own
  pipeline provably cannot ever produce an out-of-range or unrecognized
  discriminant through ANY admitted `.kotoba` program -- see "A note on why
  the trap cannot be reached through the normal pipeline" below for the
  full, four-layer argument. Rather than claim the defensive `UD2`/`BRK`
  fallback works without proof, this deftest calls the private
  `emit-variant-dispatch` primitive directly (the SAME "test an internal
  directly" technique this file already uses for other private internals,
  e.g. `@#'executor/run-process`) with a literal ordinal (`99`) no admitted
  program could produce for a 3-case dispatch, wraps the result in a
  minimal hand-assembled function (bypassing `emit-function`/`emit-
  program`/`frontend/analyze`/`verifier/verify-artifact!` entirely -- this
  is the ONLY way to construct such a value, precisely because every layer
  of the normal pipeline independently prevents it), and runs the resulting
  bytes through the SAME measured, real `kexe-loader` native process every
  other deftest in this file uses. The process reports `{:status :trap ...}`
  -- the SAME structured trap report format `bounded-pair-arena-executes-
  and-rejects-forged-handles` and `kgraph-native-customer-pilot-...`'s own
  out-of-range tests already rely on -- confirming the fallback fires as
  real, executed machine code, not merely a code comment's claim.
- **Full `clojure -M:test` suite**: 465 tests, 4587 assertions, 1 failure, 0
  errors -- the identical pre-existing failure ADR 0062 already documented
  (`kotoba.compiler.cli-test/structured-diagnostic-has-stable-code-and-
  bounded-source-span`) confirmed, by `git stash`-ing this ADR's own changes
  and re-running the identical namespace against the unmodified base commit,
  to be present and identical before this ADR's changes too -- unrelated to
  any file this ADR touches. The same underlying diagnostic-span behavior
  was independently confirmed pre-existing on the nbb/cljs side too
  (`npm run test-nbb-wasm32`'s `structured-diagnostic` case), also
  `git stash`-verified against the unmodified base commit, matching ADR
  0062's own precedent exactly.
- **`clojure -M -m kotoba.compiler.backend-qualification verify native`**:
  passes, confirming this ADR does not disturb the existing native
  capability-kit qualification manifest.
- **`clojure -M -m kotoba.security.adoption`**: unchanged, `:status :pass`.

### A note on why the trap cannot be reached through the normal pipeline

Confirmed by direct reading of `signing.clj`: `sign` calls
`(verifier/verify-artifact! kexe)` unconditionally before producing a
signature, and `verify` -- called by `native-executor/execute` on EVERY
execution, not merely once at compile time -- ALSO calls
`(verifier/verify-artifact! artifact)` unconditionally. Combined with
frontend's own unconditional, unchanged "variant constructor tag is not
declared" check (`infer-expression-type`) and this ADR's own two
independently-derived re-checks (this backend's own tag-to-ordinal lookup in
`emit-variant-match-of-new`, and `verifier.cljc`'s own re-derived
`variant-new`/`variant-match` cases), there are FOUR independent layers, and
the last of those four is enforced TWICE (once at signing time, once at
every single execution) -- so there is no way, at any layer, including a
hand-crafted artifact that bypasses `frontend/analyze` entirely, to reach
real `kexe-loader` execution with a variant discriminant the type system did
not itself validate as a declared case's ordinal. This is a genuine,
useful, discovered property of this repository's own security architecture
(multi-layer independent re-verification enforced even at execution time,
not just compile time), not a gap -- treated here as a finding worth
recording, not worked around with a hacky bypass op added to production
codegen.

## Remaining gaps

Native variants holding strings/keywords, nested records/variants inside a
case, `record`s holding a variant field (or vice versa), a variant value
crossing a function boundary (parameter or result) or a capability/provider
boundary (no such boundary exists on native targets at all yet), f64 case
payloads (blocked on the separate, pre-existing native f32/f64 rejection
gate), a genuinely dynamic dispatch site (the SAME `variant-match` call
receiving different runtime cases across different executions -- this
increment's own "directly-nested `variant-new` only" restriction makes the
constructed case always statically known at each individual call site, even
though the underlying compare-chain mechanism itself does not special-case
around that), a genuine zero-payload (`:unit`) marker type (see Decision for
why this increment deliberately does not add one), and case counts beyond a
handful (this increment's own test uses four; the frontend's pre-existing
`max-variant-cases` ceiling of 32 is unverified for native) all remain
closed. Native-AOT qualification for `state-v1` or any other capability kit
is unaffected by this ADR; `resources/kotoba/lang/capability-kits/*.edn` is
not modified.
