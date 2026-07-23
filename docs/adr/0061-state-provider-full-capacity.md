# ADR 0061: `state-v1`'s real provider grows from ADR 0060's deliberate 4-slot table to the reference's real 256-entry capacity

Status: accepted; `kotoba.compiler.component-core/state-provider-table-capacity` is now `256`, matching `kotoba.compiler.provider.state/max-entries` and `state-v1.edn`'s own declared `:limits {:entries 256 ...}` exactly, proven via real Wasmtime execution of the composed application-or-driver-plus-provider component at the full target capacity (see Evidence for exactly what "full capacity" covers)

## Decision

ADR 0060's own "Remaining gaps" named this explicitly, not by omission:

> **256-entry capacity is not implemented.** `state-provider-table-capacity`
> is `4`. Growing it is a mechanical, separate follow-up (the per-slot
> layout and unrolled scan generalize directly to any fixed compile-time
> slot count -- 256 unrolled branches would simply make the generated WAT
> large, not incorrect), not attempted here.

This ADR closes exactly that gap and nothing else. The target capacity was
confirmed, not assumed, from BOTH places `state-v1`'s real bound is
declared: `resources/kotoba/lang/capability-kits/state-v1.edn`'s own
`:limits {:entries 256 :key :bounded-keyword :value :bounded-string}`, and
`src/kotoba/compiler/provider/state.cljc`'s own `(def max-entries 256)` (the
pure-Clojure reference implementation this whole ADR chain treats as the
semantic spec). Both were read directly, and `state-provider-table-capacity`
was confirmed at runtime, in this ADR's own change, to equal both:

```
:capacity 256
:max-entries-ref 256
:kit-limits {:entries 256, :key :bounded-keyword, :value :bounded-string}
```

**ADR 0060's own "mechanical" premise held, verified by reading the code
before changing it, not assumed from the premise's own wording.** The task
that produced this ADR asked, explicitly, whether "256 unrolled branches"
would need `state-scan-wat` itself restructured (a hand-written WAT emitter
needing a genuine code-generation rewrite) or whether the existing 4-slot
implementation ALREADY built its per-slot branches via a Clojure-side
generator. It already does: `state-scan-wat` (`component_core.clj`) builds
its per-slot occupied/key-length/`$bytes-equal` branches via `(map (fn
[index] ...) (range capacity))` -- a Clojure function looping over
`capacity` to EMIT WAT text, not a fixed sequence of hand-written `if`/
`else` cases. `state-slot-layout`'s own byte-layout math (`:table-size (*
capacity slot-size)`) and `state-provider-wat`'s own memory-page sizing
(`required-bytes`, `pages`) are likewise already `capacity`-parametric, not
literal-4-anywhere. Both `state-provider-wat` and `package-state-provider`
already exposed an explicit-`capacity` arity precisely so a caller (this ADR,
and ADR 0060's own test suite) could request a size other than the default.

**The result: this really was the one-constant change ADR 0060 predicted,
in production code.** `state-provider-table-capacity`'s value changed from
`4` to `256`; nothing else in `component_core.clj`'s WAT-EMISSION logic
changed. The generated WAT for capacity 256 is proportionally larger (a
bigger `(module ...)`, per `state-scan-wat`'s own docstring's own
prediction), not differently shaped.

**One genuinely new piece of code was needed, and it is TEST-only, not
production.** ADR 0060's own stateful-sequence driver (`state-driver-wat`)
folds a pass/fail bit per step into a `u32` bitmask -- correct and
sufficient for its own 14 steps, but a `u32` cannot address the ~262 steps
a full 256-entry fill-to-capacity-and-back sequence needs (256 fill steps
alone exceed 32 bits). Rather than force-fitting a wider bitmask (still
capped, still opaque about WHICH step failed without further decoding),
`state-full-capacity-steps`/`state-full-capacity-driver-wat`
(`component_composition_test.clj`) are a parallel, generalized construct:
`steps` is now a Clojure-side `(range capacity)`-driven generator
(`state-full-capacity-steps`) rather than a hand-written literal vector
(the same "Clojure code loop emits WAT text" shape `state-scan-wat` already
established, NOT a runtime WASM loop -- the driver module still fully
unrolls one straight-line provider-call-plus-check per step at emission
time), and the returned aggregate is a first-FAILING-step `i32` INDEX (or
`-1` if every step passed) rather than a bitmask -- strictly more
diagnostic for a long sequence, not merely a workaround for the bit-width
ceiling. `state-driver-wat`/`state-driver-steps`/`state-driver-expected-
mask` (ADR 0060's own 14-step fixture) are UNCHANGED, byte-for-byte, by
this ADR; the one production-code touch to how they are invoked is that
`state-stateful-sequence-driver-closes-and-validates` now passes `capacity`
to `package-state-provider` EXPLICITLY as `4` (previously implicit via that
function's own default, which this ADR changes from `4` to `256`), so the
exact ADR 0060 fixture -- table size included -- re-runs unchanged as a
no-regression check rather than silently inheriting the new production
default.

## Scope

**What changed, precisely, and nothing more.**

- `kotoba.compiler.component-core/state-provider-table-capacity`: `4` ->
  `256`. One constant.
- Docstrings on `state-provider-table-capacity`, `state-provider-wat`, and
  `component-composition/package-state-provider` updated to describe the
  new default and point back to this ADR and ADR 0060's own history --
  no behavior in these functions changed beyond what following the
  now-256 constant already implies.
- `component_composition_test.clj`: `state-stateful-sequence-driver-closes-
  and-validates` now passes `capacity` explicitly (`4`) to
  `package-state-provider` instead of relying on the (now-changed) default;
  no other line of that deftest, nor `state-driver-wat`/`state-driver-
  steps`/`state-driver-wit`/`state-driver-expected-mask` themselves,
  changed. Two new functions
  (`state-full-capacity-steps`/`state-full-capacity-driver-wat`) plus their
  own packaging helper (`package-state-full-capacity-driver`) and one new
  `deftest` (`state-real-provider-full-capacity-driver-closes-and-
  validates`, composition/validation-only, matching every ADR in this
  chain's own test-suite/manual-evidence split) were added.
- `resources/kotoba/lang/capability-kits/state-v1.edn` is **NOT touched**.
  In particular its `:qualification` map is untouched -- see "What this ADR
  does NOT close" below.
- No other file in `src/` or `test/` changed.

**What is deliberately NOT attempted, matching ADR 0060's own remaining
gaps 2-6 verbatim (none of those gaps are about capacity, and none of them
are closed by growing capacity):**

1. Native-AOT and JIT remain entirely untouched.
2. This provider is still not reviewed for production/security hardening.
   Growing the table 64x (4 -> 256 slots, and correspondingly the module's
   total linear-memory footprint from ~264KB to ~16.9MB below `arena-base`
   -- see Evidence for the exact figure) is a SIZE change to an unaudited
   reference implementation, not a hardening pass; the bounded bump
   allocator's own trap-on-overflow behavior was re-confirmed to still
   engage correctly at the new size (see Evidence) but was not re-audited
   beyond that.
3. `component-composition.clj`'s `:ref`-only discipline for a variant
   case's record payload is still untouched.
4. Lists/tuples/options/results, multiple capabilities in one exported
   function, and every OTHER capability's real provider semantics all
   remain closed, unchanged.
5. The stateful-sequence/full-capacity drivers are still test-only
   constructs, not a new application-language capability -- the standard
   KIR/`typed-cap-call` admission pipeline still only admits a function body
   that IS a single `typed-cap-call`.

## Evidence

All of the following used the actual, non-monkeypatched code path
(`component-core/state-provider-wat` for the provider core module,
`component-composition/package-state-provider` for embedding/validation,
`component-composition/compose-closed` for `wac plug` + `wasm-tools
validate`), not hand-assembled WAT, against the pinned `wasm-tools 1.243.0`
/ `wac-cli 0.10.1` / Wasmtime `42.0.1` (unchanged pins -- this ADR needed no
toolchain change, confirmed by direct version check before running
anything).

- **Target capacity confirmed at runtime from BOTH declared sources, not
  assumed from ADR 0060's prose:**
  ```
  :capacity 256
  :max-entries-ref 256
  :kit-limits {:entries 256, :key :bounded-keyword, :value :bounded-string}
  ```
  (`component-core/state-provider-table-capacity`,
  `kotoba.compiler.provider.state/max-entries`, and `state-v1.edn`'s own
  `:limits`, respectively, read directly, not hand-copied.)
- **Full test suite**: `clojure -M:test` -- 454 tests, 4562 assertions, 0
  failures, 0 errors (baseline immediately before this ADR's changes: 453
  tests, 4557 assertions -- so the delta, +1 test/+5 assertions, is exactly
  and only `state-real-provider-full-capacity-driver-closes-and-validates`
  and its own 5 `is` forms, confirmed by diff, not estimated).
- **`clojure -M -m kotoba.compiler.backend-qualification verify
  {wasmtime,native,cljs}`**: all three report the IDENTICAL
  `:provider-manifest-sha256`
  (`5d7599b5701b6fb9660de7488afbfa0b85314f90208a3f11b2ea28ca502e476e`) and
  `:gaps` list on this ADR's own branch and on a separate, untouched clone
  of `main` HEAD (`06a8201`) checked side by side -- confirming no
  capability kit's qualification moved as a side effect of this change and
  `resources/kotoba/lang/capability-kits/*.edn` is genuinely untouched, not
  merely unedited-and-hoped-unaffected.
- **PRIMARY evidence -- real Wasmtime execution of the composed
  (full-capacity driver + real 256-slot provider) component, ONE
  instantiation, 262 sequential calls to the real provider, covering every
  part of the task's own evidence bar:**
  - (a) **Filling the table to EXACTLY its new capacity via sequential
    `put`s on 256 distinct keys** (`key0`..`key255`), each checked against
    an independently-computed expected `written{key, value, version}` (the
    GLOBAL version counter's own documented "Nth successful write is
    version N+1" convention, derived the same way ADR 0060's own 14-step
    fixture derives its literal versions, not copied from provider output).
  - (b) **A `put` for one more NEW key (`key256`) beyond capacity returns
    `error{code: "state/capacity"}`** -- checked by content, not merely "an
    error occurred" -- proving the capacity check itself scales to the real
    256-entry bound, not merely that 256 slots physically exist.
  - (c) **`get`/`delete` on `key255`, the 256th (LAST) slot** -- both
    checked against independently-computed expectations -- specifically
    exercising whether `state-scan-wat`'s own unrolled scan is genuinely
    complete across the FULL 256-slot range, not silently truncated to an
    early subset a fixture that only ever touched the first few slots could
    not catch.
  - Plus two steps beyond the task's own minimum bar, extending ADR 0060's
    own "existing key still succeeds when full" idea to the new scale: an
    EXISTING key (`key0`) update still succeeding once the table is full
    (not affecting `key255`'s own independently-checked state), and a FINAL
    `put` for the previously-rejected `key256` succeeding once `key255`'s
    slot frees from the delete -- 256 (fill) + 6 (boundary/near-end/
    existing-key/freed-slot) = **262 total steps**.
  - Real Wasmtime 42.0.1 execution of this exact composed driver+provider
    component (`wasmtime run --invoke 'run()'`) returned `4294967295`
    (`0xFFFFFFFF`, the `u32` encoding of this driver's own `-1` sentinel) --
    every one of the 262 checks passed, in the SAME component instance,
    across all 262 sequential calls to the real provider.
  - **Negative control on the SAME full-capacity harness**, corrupting step
    258's own expected `version` (the `get(key255)` check, step index 258
    of 0-261) via a rebuilt driver: real Wasmtime execution returned `258`
    -- EXACTLY the corrupted step's own index, not a different step, not a
    generic failure code. Confirms this harness's first-failing-step-index
    design genuinely discriminates failure (and pinpoints it precisely),
    not merely returns a fixed sentinel regardless of outcome.
  - Honest accounting of what this covers versus what it does not: this is
    "filled to capacity via a driver loop, spot-checked slot 0 (the
    EXISTING-key-update target), slot 255 (the LAST slot, both `get` and
    `delete`), and the boundary transition at slot 256" -- not "all 256
    slots individually `get`/`put`/`deleted` one at a time with a
    separately recorded result per slot" (only the FILL phase touches all
    256; the post-fill phase spot-checks the two slots (`key0`, `key255`)
    most likely to expose an off-by-one or early-truncation bug, matching
    the task's own suggested tightened-but-real evidence shape).
- **Regression check -- ADR 0060's OWN unchanged 14-step fixture
  (`state-driver-wat`/`state-driver-steps`, byte-for-byte identical source)
  re-run with `capacity` now EXPLICIT (`4`)**: real Wasmtime execution
  returned `16383` (`0b11111111111111`, `2**14 - 1`) -- identical to ADR
  0060's own recorded result. No regression at the small-table shape ADR
  0060 originally proved.
- **Single-call round trips at the production default (256), STANDARD
  application (`variant-capability-wat`, unchanged) composed with the real
  provider**, real Wasmtime execution:
  - `invoke(get({key: "kotoba/status"})) -> missing(false)` (empty
    256-slot table -- confirms the empty-table path still short-circuits
    correctly at the new size, not merely "large enough to never be
    exercised").
  - `invoke(put({key: "kotoba/status", value: "ok"})) -> written({key:
    "kotoba/status", value: "ok", version: 2})` -- the "first write in a
    fresh instance is version 2" convention re-confirmed unchanged at the
    new capacity.
- `wasm-tools validate --features component-model` passed on every
  composed component built above (full-capacity driver, its negative
  control, the capacity-4 regression fixture, and the single-call
  application fixture alike) -- in addition to the `wasm-tools validate`
  `compose-closed` itself already runs as part of building each artifact.
- **Memory footprint at the new capacity, computed and confirmed, not
  estimated:** one table slot is 66080 bytes (`state-slot-layout`'s own
  layout: 4-byte `occupied` + 8-byte `version` + 4-byte `key-len` + 512-byte
  `key-bytes` (`value/keyword-value-byte-limit`) + 4-byte `value-len` +
  65536-byte `value-bytes` (`value/string-value-byte-limit`), 8-byte
  aligned). 256 slots = 16,916,480 bytes (~16.9MB) of FIXED persistent
  table region below `arena-base`, well within Wasmtime's default 4GiB
  (65536-page) linear-memory ceiling and requiring no `memory64` or other
  toolchain change -- `state-provider-wat`'s own `pages`/`(memory ...)`
  declaration is computed dynamically from `required-bytes`, so this scaled
  automatically with no separate sizing code to add.
- This ADR adds exactly 1 new `deftest` to `component-composition-
  test.clj` (`state-real-provider-full-capacity-driver-closes-and-
  validates`) with 5 new `is` assertions, plus 4 new supporting functions
  (`state-full-capacity-steps`, `state-full-capacity-literal-plan`,
  `state-full-capacity-driver-wat`, `package-state-full-capacity-driver`)
  and a 1-line behavioral change to one EXISTING `deftest`
  (`state-stateful-sequence-driver-closes-and-validates`, passing `capacity`
  explicitly) -- confirmed by diff, not estimated.

## What this ADR does NOT close

`resources/kotoba/lang/capability-kits/state-v1.edn` is **not modified** by
this ADR. Its `:qualification` map remains exactly `{:reference
:implemented :wasm-aot :pending :native-aot :pending :jit :pending}` --
confirmed unchanged by direct read after this ADR's own changes, and by the
identical `backend-qualification verify` receipts recorded in Evidence
above. Reaching full 256-entry capacity closes exactly ADR 0060's own
first-named remaining gap and nothing else: it is one necessary condition
of a genuinely production-usable `state` capability, not a sufficient one.
Specifically, per ADR 0060's own remaining-gaps list (items 2-6, all
unchanged by this ADR, restated in Scope above): native-AOT and JIT remain
entirely untouched; this provider is still not reviewed for production/
security hardening (a 64x larger unaudited memory footprint is, if
anything, a LARGER attack surface to eventually review, not a smaller one);
`component-composition.clj`'s `:ref`-only representational discipline is
still untouched; every other capability's real provider semantics remain
closed; and the stateful/full-capacity sequence drivers are still test-only
constructs, unreachable through the standard KIR/`typed-cap-call` admission
pipeline. This decision -- leaving `:qualification` exactly as ADR 0060 left
it -- is deliberate and conservative, matching every prior ADR in this
chain without exception, and is left for a human/future ADR once the
remaining gaps above are individually closed and reviewed.
