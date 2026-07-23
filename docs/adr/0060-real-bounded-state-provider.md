# ADR 0060: A real (non-wiring-only) bounded key/value provider for `state-v1` crosses a real `typed-cap-call` boundary with a real stateful sequence

Status: accepted; the first real-semantics provider in this ADR chain --
real request-payload dispatch, real bounded persistent mutable table state
across calls within one component instance, and a real byte-content key
comparison -- proven through a real composed component and real Wasmtime
14-step stateful sequence execution (not merely round trips -- see Remaining
gaps for exactly what is still not proven).

## Decision

Every provider built in this ADR chain through ADR 0059 is, in ADR 0059's
own words, "a wiring-only fixture that never reads request payload values
and writes compile-time-literal result constants." This ADR builds the
first provider that does neither: `kotoba.compiler.component-core/state-
provider-wat` is a genuine, small, bounded, in-memory key/value store for
`state-v1`'s own literal request/result shape (`resources/kotoba/lang/
capability-kits/state-v1.edn`), with real dispatch on the request's own
discriminant, real reads of the request's own `key` (and, for `put`,
`value`) payload leaves, real persistent mutable state across calls within
one component instance, and a real byte-content equality check over linear
memory to match a request's key against each stored slot's own key -- a
kind of Wasm logic no prior ADR in this chain needed.

`get`/`put`/`delete` semantics match `src/kotoba/compiler/provider/
state.cljc` (the bounded, pure-Clojure reference implementation this task
names as the semantic spec, not the implementation target) exactly, verified
against that file and its own test (`test/kotoba/compiler/
state_provider_test.clj`), not assumed:

- `get` on a present key returns `found` with the stored `entry` (key,
  value, version). `get` on an absent key returns `missing(false)` -- the
  reference always writes a literal `false` payload for `missing`, never
  `true`; this provider matches that exactly.
- `put` on a NEW key, when the table is not yet full, allocates the lowest
  free slot; on an EXISTING key, updates that slot in place. Both cases
  return `written` with the resulting `entry`. `version` is a GLOBAL,
  per-provider-instance monotonic `i64` (a real WASM mutable global,
  matching the reference's own per-instance `atom`), pre-incremented on
  EVERY successful write regardless of which key -- reproducing the
  reference's `(swap! next-version inc)` convention verbatim, including its
  slightly surprising "first write in a fresh instance is version 2, not
  1" consequence (the counter starts at 1 and is incremented BEFORE use).
  This was confirmed, not assumed: `state_provider_test.clj` itself asserts
  `version 2` after exactly one `put` on a fresh reference instance.
- `put` on a NEW key when the table is already full returns `error` with
  `code: "state/capacity"`, `message: "state entry limit reached"` -- the
  version counter is left untouched (matching the reference's own
  early-return-before-`swap!` capacity check). `put` on an EXISTING key
  still succeeds even when the table is full (only NEW keys are rejected).
- `delete` ALWAYS succeeds and ALWAYS returns `deleted`, never `missing` or
  `error`: `deleted(true)` when the key was present (and the slot is
  freed), `deleted(false)` when it was already absent. Verified directly
  against the reference's own `(result :deleted present?)` -- deleting an
  absent key is not a special case, error, or `missing` result in the
  reference, and this provider reproduces that precisely.

## Scope

**Table capacity: 4 slots, deliberately far smaller than the reference's
256** (`kotoba.compiler.component-core/state-provider-table-capacity`).
This is an honest, deliberate narrowing along exactly the axis the task
asked for -- growing capacity later is a straightforward, mechanical
follow-up (more slots, the identical per-slot layout/unrolled-scan shape
scales linearly), not a new semantic dimension. `4`, not `1`, was chosen
because the task's own stateful-sequence evidence needs to distinguish two
genuinely different fail-closed shapes a 1-slot table could not tell apart
("rejects a second DISTINCT key once full" vs. "rejects every key
unconditionally"), and needs at least a few slots to prove no cross-key
contamination among multiple simultaneously-stored keys, not only two.

**Stored key/value byte capacity is NOT narrowed below Kotoba's own
declared bound.** Each slot's key buffer is sized to the full
`value/keyword-value-byte-limit` (512 bytes) and each value buffer to the
full `value/string-value-byte-limit` (65536 bytes) -- not a narrower
internal cap. This was a deliberate choice, not an oversight: a request
already admitted by the reused `asymmetric-request-validation-chain`
byte-bound check is therefore GUARANTEED to fit its own slot, with no
second, narrower, provider-private fail-closed dimension the task did not
ask for. (Total table memory for 4 slots is ~264KB, comfortably within a
handful of Wasm pages -- see Evidence.)

**What is reused UNCHANGED from ADR 0055-0059, with zero modification.**
`asymmetric-request-validation-chain` (REQUEST-side byte-bound/pointer-range
validation -- unlike ADR 0058/0059's own asymmetric provider, which
validates a leaf it otherwise never reads, THIS provider's own dispatch
genuinely needs the key/value bytes in-bounds before `$bytes-equal`/
`memory.copy` ever touch them, so the same validation is necessary here for
a different, more direct reason), `string-headroom-bytes` (REQUEST-side
memory-page sizing for the Canonical ABI's own cross-instance string-copy-in
glue), `bounded-bump-realloc-wat` (the transient scratch allocator, used
here ONLY for this call's own `$ret` struct and whatever the glue's copy-in
needs -- never for the persistent table), and `asymmetric-variant-wit` (the
WIT text this provider exports is byte-for-byte identical to ADR 0058/
0059's own wiring-only asymmetric provider's WIT -- only the WASM BODY
differs). No admission predicate (`asymmetric-variant-capability-case?`,
`different-variant-capability-call`, etc.) changed; no application-side WAT
emitter (`variant-capability-wat`) changed. This ADR is entirely additive:
one new provider WAT emitter (`state-provider-wat`) and one new packaging
function (`kotoba.compiler.component-composition/package-state-provider`),
both narrowly scoped to `state-v1`'s own literal shape (checked by a new,
strict predicate, `state-provider-shape`, that rejects even structurally
close look-alikes -- see Evidence).

**The one genuinely new piece of memory-sizing math.** This module's total
capacity must additionally cover the FIXED, permanent table region
(`state-slot-layout`'s own `:table-size`) and a small FIXED literal region
for the one compile-time-constant string content this provider ever writes
without deriving it from a request value (the `error`/`:state/capacity`
code+message pair, embedded via `(data ...)` exactly like ADR 0059's own
`plan-result-string-data`/`deterministic-constant-string` technique,
narrowly reused here for just this one case) -- both placed below
`arena-base`, ADDED to (not replacing) the existing REQUEST-headroom-plus-
result-size sum ADR 0059's own `asymmetric-variant-capability-provider-wat`
already established.

**The genuinely new kind of Wasm logic: `$bytes-equal`.** Every prior
provider in this chain either never compared payload bytes at all
(wiring-only identity/fixture) or only ever `memory.copy`'d bytes (moving
them), never compared byte CONTENT of a runtime-length range. Matching a
request's `key` against each occupied table slot's own stored key needs a
real equality check over two `(pointer, length)` linear-memory ranges whose
length is a RUNTIME value (up to 512 bytes), which the nested `if`/`else`
chains every other dispatch function in this namespace uses (correct only
for a small, fixed, COMPILE-time case count) cannot express. `$bytes-equal`
is a real bounded WASM `loop` reading and comparing one byte at a time
(`i32.load8_u`), the only function in `component-core.clj` that uses `loop`
or an early `return` mid-body rather than nested `if`/`else` -- a
deliberate, narrow, and load-bearing exception (see its own docstring for
why `return` inside `if`/`loop` is standard, well-defined WASM control flow,
the same "push a value then diverge" shape `if unreachable end` already
uses pervasively in this namespace for traps, just diverging via `return`
instead of `unreachable`).

**The persistent-vs-transient memory split.** The bounded table (below
`arena-base`) and the `$version` global are the first PERSISTENT provider
state in this ADR chain -- untouched by BOTH `_post` (called once per
external call, resetting only the transient bump allocator's `$next`) and
`cm32p2_initialize` (called once at instantiation). Every prior provider's
"memory" was purely transient scratch, freshly reset every call, because
every prior provider was wiring-only and had nothing to remember. `found`/
`written` result leaves point DIRECTLY at the slot's own persistent buffer
(no extra `memory.copy` into scratch first) -- safe because the slot lives
strictly below `arena-base`, so the Canonical ABI's own cross-instance
result-copy glue (which reads those bytes immediately after this function
returns) never races a future call's scratch reuse.

**What is deliberately NOT admitted or attempted.** `state-provider-shape`
(the schema predicate `state-provider-wat` runs before generating any WAT
at all) checks `state-v1`'s own literal case tags, field names, types, and
order EXACTLY -- not merely "a 3-case request crossing a 5-case result"
structurally admitted by `asymmetric-variant-capability-schema`. This
provider is deliberately narrow to `state-v1`'s own shape specifically, not
a generic "real provider for any asymmetric variant crossing." A
structurally-close-but-not-identical shape (ADR 0058's own `demo/state-
request`/`demo/state-result`, same case count and record shape but every
field `:i64` rather than `:keyword`/`:string`) is rejected at the
WAT-emission layer before any `wac`/`wasm-tools` command ever runs (see
Evidence). `component-composition.clj`'s own `:ref`-only discipline for a
variant case's record payload (ADR 0059's own remaining gap 2) is untouched
by this ADR -- `state-provider-wat` reaches `state-v1.edn`'s own literal
shape only via the same test-only `ref-ify` converter ADR 0059's own
primary fixture uses, unchanged.

## Evidence

All of the following used the actual, non-monkeypatched code path
(`component-core/state-provider-wat` for the provider core module,
`component-composition/package-state-provider` for embedding/validation,
`component-composition/compose-closed` for `wac plug` + `wasm-tools
validate`), not hand-assembled WAT, and every Wasmtime invocation ran the
**composed** (application-or-driver + provider) component:

- **Shape rejection (fail-closed, before any tool runs).**
  `package-state-provider` on ADR 0058's own `demo/state-request`/`demo/
  state-result` (structurally close -- same 3-case/5-case shape, same
  record field COUNT -- but every field `:i64`, not `:keyword`/`:string`)
  throws `"state provider requires state-v1's own literal request/result
  shape"` before generating any WAT (`state-real-provider-rejects-non-
  state-v1-shape` deftest).
- **`state-v1`'s own literal shape composes and validates.**
  `state-real-provider-closes-the-application-world` builds the STANDARD
  application (`variant-capability-wat`, unchanged, via the
  `different-variant-capability-call` KIR path) against the REAL provider
  for `state-v1.edn`'s own literal `:request`/`:result` EDN (via `ref-ify`,
  the same pure structural re-nesting ADR 0059's own primary fixture uses,
  read directly from the resource file). `wac plug` + `wasm-tools validate
  --features component-model` both pass.
- **Single-call round trips, real Wasmtime 42.0.1 execution of the composed
  (application + real provider) component, on a FRESH instance each time**
  (proving real dispatch and real value reflection, not yet cross-call
  state -- see the stateful-sequence fixture below for that):
  - `invoke(get({key: "kotoba/status"})) -> missing(false)` (empty table).
  - `invoke(put({key: "namae/漢字", value: "日本語のテスト🎉"})) ->
    written({key: "namae/漢字", value: "日本語のテスト🎉", version: 2})`
    (multi-byte UTF-8 in both a keyword and a string leaf, and confirming
    the "first write in a fresh instance is version 2" convention the
    reference itself establishes).
  - `invoke(delete({key: "kotoba/status"})) -> deleted(false)` (delete on
    an absent key succeeds with `false`, never `missing`/`error`).
  - A 513-byte `key` on `get` (one byte past `keyword-value-byte-limit`,
    512) traps for real (`wasm trap: wasm \`unreachable\` instruction
    executed`, process exit 134); a 512-byte `key` at exactly the bound
    succeeds (`missing(false)`).
  - A 65537-byte `value` on `put` (one byte past `string-value-byte-limit`,
    65536) traps the same way; a 65536-byte `value` at exactly the bound
    succeeds (`written(...)`). Both traps confirm the reused, unmodified
    `asymmetric-request-validation-chain` still engages correctly for this
    new provider.
- **PRIMARY evidence -- a real 14-step STATEFUL SEQUENCE, real Wasmtime
  execution of ONE composed component through ONE instantiation, proving
  real cross-call persistence** (qualitatively different from every prior
  ADR's "round-trips unchanged" evidence, which only ever needed a single
  call). `state-driver-wat`/`state-driver-wit`
  (`test/kotoba/compiler/component_composition_test.clj`) build a small,
  hand-written, test-only "driver" application component -- necessary
  because `wasmtime run --invoke` instantiates the composed component
  FRESH on every process invocation (confirmed via `wasmtime run --help`:
  exactly one `--invoke` per process), so cross-call state could not be
  observed through separate CLI invocations at all; the driver instead
  issues all 14 calls to the REAL, imported provider from within ONE
  exported function, checking each result against an independently-computed
  expectation and folding a pass/fail bit per step into one returned `u32`
  bitmask (`state-driver-steps`, full sequence documented in its own
  docstring). Composed against the SAME real provider
  (`package-state-provider`), validated (`state-stateful-sequence-driver-
  closes-and-validates` deftest), and run once via `wasmtime run --invoke
  'run()'`:

  ```
  get(k1) -> missing                                    (bit 0)
  put(k1, v1) -> written{k1, v1, version=2}              (bit 1)
  get(k1) -> found{k1, v1, version=2}                     (bit 2, PROVES cross-call persistence)
  put(k1, v2) -> written{k1, v2, version=3}                (bit 3, PROVES the version counter is real)
  get(k2) -> missing                                        (bit 4, PROVES no cross-key contamination)
  delete(k1) -> deleted(true)                                (bit 5)
  get(k1) -> missing                                          (bit 6, PROVES deletion is real)
  delete(k1) again -> deleted(false)                           (bit 7, delete-on-absent-key semantics)
  put(k2, a) -> written{k2, a, version=4}                       (bit 8)
  put(k3, b) -> written{k3, b, version=5}                        (bit 9)
  put(k4, c) -> written{k4, c, version=6}                         (bit 10)
  put(k5, d) -> written{k5, d, version=7}                          (bit 11, table now full: 4/4 slots)
  put(k6, e) -> error{code: "state/capacity", ...}                 (bit 12, NEW key rejected when full)
  put(k2, z) -> written{k2, z, version=8}                           (bit 13, EXISTING key still ok when full)
  ```

  Real Wasmtime execution of this exact composed driver+provider component
  returned `16383` (`0b11111111111111`, `2**14 - 1`) -- every one of the 14
  checks passed, in the SAME component instance, across all 14 sequential
  calls to the real provider.
- **Negative control on the SAME stateful-sequence harness, proving it
  genuinely discriminates failure rather than vacuously returning
  all-ones.** With step 3's own expected `version` deliberately corrupted
  from `2` to `999` (via `with-redefs` on `state-driver-steps`, rebuilding
  and recomposing the driver through the unchanged `state-driver-wat`),
  real Wasmtime execution returned `16379` (`16383 - 4`) -- EXACTLY bit 2
  (the corrupted step, 0-indexed) cleared, every other bit still set.
  Confirms the harness's own per-step checks are load-bearing, not
  decorative.
- `wasm-tools validate --features component-model` passed on every composed
  component built above (single-call application fixture and the
  stateful-sequence driver fixture alike).
- This ADR adds exactly 3 new `deftest` forms to `component-composition-
  test.clj` (shape rejection, single-call composition/validation,
  stateful-sequence driver composition/validation -- 15 to 18 total in that
  namespace) and exactly 10 new `is` assertions across them, confirmed by
  diff, not estimated.
- Full `clojure -M:test` suite with this ADR's changes applied: 450 tests,
  4548 assertions, 0 failures, against the pinned `wasm-tools 1.243.0` and
  `wac-cli 0.10.1` (unchanged pins -- this ADR needed no toolchain change).
  (Other, unrelated PRs landed on `main` between ADR 0059's own recorded
  436/4513 and this ADR's baseline, so the delta from 436/4513 to 450/4548
  is not attributable to this ADR alone -- the precise, verifiable
  attribution is the 3 `deftest`/10 `is` figure above.)
- `clojure -M -m kotoba.compiler.backend-qualification verify wasmtime`
  (and `verify native`, `verify cljs`, matching every prior ADR's own
  additional checks): all three report the identical `:provider-manifest-
  sha256` and `:gaps` list recorded before this change, confirming no
  capability kit's qualification moved as a side effect and
  `resources/kotoba/lang/capability-kits/*.edn` is untouched by this ADR.

Fail-closed boundaries re-verified directly by test after this addition:

- a structurally-close-but-not-`state-v1` shape (ADR 0058's own `demo/
  state-request`/`demo/state-result`, `:i64` fields) is rejected at
  `state-provider-wat`'s own admission check, independent of any
  `wac`/`wasm-tools` invocation;
- the 513-byte-key/65537-byte-value REQUEST-side traps, reproduced against
  this new provider (unchanged validation machinery reused, not
  reimplemented, so this is a no-regression confirmation, not new logic
  being tested for the first time).

## Remaining gaps

This ADR proves `state-v1`'s first real (non-wiring-only) provider,
composed through a real `typed-cap-call` boundary, with real dispatch, real
persistent state, real byte comparison, and a real 14-step stateful
sequence executed through real Wasmtime. It does NOT close `state-v1` as a
fully production-ready capability, for reasons this ADR names explicitly,
not by omission:

1. **256-entry capacity is not implemented.** `state-provider-table-
   capacity` is `4`. Growing it is a mechanical, separate follow-up (the
   per-slot layout and unrolled scan generalize directly to any fixed
   compile-time slot count -- 256 unrolled branches would simply make the
   generated WAT large, not incorrect), not attempted here to keep this
   first real-semantics increment to one new dimension (real logic) rather
   than two (real logic AND full capacity) at once.
2. **Native-AOT and JIT remain entirely untouched**, as they have been
   since this whole ADR chain began.
3. **This provider is not reviewed for production/security hardening.**
   It is a real semantic reference implementation reachable through a real
   Component Model boundary for the first time, not an audited, hardened
   deployment artifact. In particular: the bounded bump allocator's own
   trap-on-overflow behavior (shared with every prior string-bearing
   provider in this chain) has not been specifically re-audited for this
   provider's own larger, table-carrying memory footprint beyond confirming
   the byte-bound traps above still fire correctly.
4. **`component-composition.clj`'s `:ref`-only discipline for a variant
   case's record payload is still untouched** (ADR 0059's own remaining
   gap 2, unchanged): a production caller handing `state-v1.edn`'s own
   literal inline-`[:record ...]`-in-a-case EDN directly (rather than via
   the test-only `ref-ify` converter) still cannot use this provider
   without that separate, purely representational widening.
5. **Lists/tuples/options/results, multiple capabilities in one exported
   function, and every OTHER capability's (`ui`, `http`, `llm`, `storage`,
   `clock`, `log`, etc.) real provider semantics** all remain closed,
   unchanged from every prior ADR in this chain.
6. **The stateful-sequence driver is a test-only construct, not a new
   application-language capability.** The standard KIR/`typed-cap-call`
   admission pipeline still only admits a function body that IS a single
   `typed-cap-call`; a real Kotoba application cannot yet express "call
   `:state/transact` multiple times in sequence within one exported
   function" through the compiler's own front end. Reaching that would be a
   genuinely new KIR-admission dimension (a compiled application performing
   a multi-step capability sequence), deliberately out of scope here.

No capability kit's `:qualification` changes as a result of this ADR.
`resources/kotoba/lang/capability-kits/state-v1.edn` is **not modified** --
its `:qualification` map remains exactly `{:reference :implemented
:wasm-aot :pending :native-aot :pending :jit :pending}`. This is a
deliberate, conservative choice, matching every prior ADR in this chain
without exception: this ADR proves real provider SEMANTICS crossing the
boundary for the first time (a milestone beyond every prior ADR's
wiring-only evidence), but "wasm-aot implemented" would need to mean the
capability is genuinely production-usable end to end at full scale (256
entries, hardened, native/JIT-executable) -- explicitly not what this ADR
proves (gaps 1-3 above). This decision is left for a human/future ADR once
this lands and is reviewed, exactly as the task itself directed.
