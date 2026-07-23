# ADR 0059: String/keyword-bearing cases cross the asymmetric `typed-cap-call` boundary -- `state-v1`'s literal request/result shape reached

Status: accepted; asymmetric (different-identity) string/keyword-bearing variant-case capability-call slice implemented, `state-v1`'s own literal EDN shape composed and executed end to end (ABI crossing only, not provider semantics -- see Remaining gaps)

## Decision

A direct `typed-cap-call` whose request and result are two INDEPENDENTLY
admitted but GENUINELY DIFFERENT sealed variant identities (ADR 0058) may
now use ADR 0057's sealed flat string/keyword-bearing record case
(`string-field-record-schema`) on EITHER side, in addition to ADR
0055/0056's bare-scalar-or-all-scalar-record case-kind union ADR 0058
admitted alone. This closes the exact gap ADR 0058's own "Remaining gaps"
named first, in these words:

> String/keyword-bearing cases crossing the different-identity boundary.
> This ADR deliberately narrowed the different-identity crossing to exactly
> ADR 0055/0056's scalar-or-all-scalar-record case-kind union, leaving ADR
> 0057's string/keyword-bearing record case unattempted for this specific
> (asymmetric) boundary ... Closing this needs, at minimum: (a) confirming
> the Canonical ABI's cross-instance string-copy glue composes correctly
> when request-layout and result-layout genuinely differ ... (b) a provider
> that ... can allocate and write literal string/keyword byte data for a
> chosen result case.

Both (a) and (b) are addressed below. Combined with ADR 0058's own
different-identity admission, this ADR reaches `resources/kotoba/lang/
capability-kits/state-v1.edn`'s own literal `:request`/`:result` EDN
exactly -- the first ADR in this chain to do so. `demo/state-request`
(3-case `get`/`put`/`delete`, matching `state-v1.edn` in case count, case
names, and record field names) crossing to `demo/state-result` (5-case
`found`/`missing`/`written`/`deleted`/`error`, `found`/`written` sharing
one `entry` record type exactly as the real `state-v1` result does) was ADR
0058's own closest approach, narrowed only by using `:i64` fields
throughout as a structural stand-in for `state-v1`'s real `:keyword`/
`:string` fields. This ADR's own primary evidence fixture removes that
stand-in: `descriptor`/`result-descriptor`/`schemas` are derived
PROGRAMMATICALLY (a pure structural re-nesting, `ref-ify` in
`component_composition_test.clj`, not a hand transcription) from
`state-v1.edn`'s own `:request`/`:result` EDN, read directly from the real
resource file via `edn/read-string`/`slurp`. The resulting variant/record
identity names, case tags, field names, and field types are `state-v1.edn`'s
own, not retyped -- confirmed both by direct equality assertion in the test
(comparing the converter's output against the literal expected shape) and
by `wasm-tools component wit` on the composed artifact (see Evidence),
which renders `kotoba-state-get`/`kotoba-state-put`/`kotoba-state-delete`/
`kotoba-state-request`/`kotoba-state-entry`/`kotoba-state-error`/
`kotoba-state-result` with `key`/`value`/`version`/`code`/`message` fields
typed `string`/`string`/`s64`/`string`/`string` -- byte-for-byte the same
shape `state-v1.edn` declares, modulo only the WIT-mandated kebab-case
renaming `wit-name` already applied to every prior fixture in this chain.

## Scope

**What is new, precisely.** `kotoba.compiler.component-core/asymmetric-
variant-capability-case?` is widened by exactly one more disjunct
(`string-field-record-schema`), making it structurally identical to the
same-identity path's own `variant-capability-case?` for the first time.
`kotoba.compiler.component-composition/asymmetric-variant-record-case-
schema` (the provider-side admission twin) is widened the same way, via
`record-field-wit-type` in place of `variant-case-wit-type` -- also making
it identical in shape to `variant-record-case-schema`. Both widenings
mirror ADR 0057's own same-identity widening exactly, now applied to the
different-identity path ADR 0058 opened.

**Two representational gaps found and fixed along the way, neither a new
semantic dimension.** (1) `asymmetric-variant-wit`'s own record-field WIT
rendering loop had a latent bug, dormant since ADR 0058: it rendered every
record field's WIT type via `variant-case-wit-type` (the scalar-only map,
no `:string`/`:keyword` entry) rather than `record-field-wit-type` (the map
every OTHER record-field WIT emission site in this namespace already uses),
which would have silently emitted malformed WIT text (`key: ,`) for a
string/keyword field the moment one was ever fed through this specific code
path -- nothing before this ADR ever did, since `asymmetric-variant-record-
case-schema` itself rejected such a case first. Fixed by using
`record-field-wit-type`, matching `record-wit`/`variant-wit`. (2) Every
fixture in this ADR chain, on both `component-core.clj`'s (admission) and
`component-composition.clj`'s (provider) sides, has always used `[:ref
name]` + a separate `schemas` map for both a variant case's record payload
and the crossing's own top-level request/result type -- never `state-v1.edn`'s
own literal inline-`[:record ...]`-inside-a-case representation.
`component-core.clj`'s admission functions (`sealed-scalar-record`,
`string-field-record-schema`, `variant-case-schema`, etc.) have always
accepted BOTH forms (`[:ref name]` or an inline `[:record name fields]`
whose own name is separately keyed into `schemas`), but
`component-composition.clj`'s twins (`variant-record-case-schema`,
`record-wit`, `variant-wit`, `asymmetric-variant-schema-valid?`) have only
ever accepted `[:ref name]`. Reaching `state-v1.edn`'s exact byte-for-byte
inline representation would therefore require widening the PROVIDER side's
`:ref`-only discipline too -- a genuinely separate, third dimension of
change this ADR does NOT attempt (deliberately: combining "different
identity" + "string/keyword crossing" + "inline-vs-ref representational
parity" in one increment is not the smallest honest step, matching this
chain's own "never widen two dimensions at once" discipline extended here
to a THIRD). Instead, `ref-ify` (test-only, `component_composition_test.clj`)
converts `state-v1.edn`'s own inline shape into this codebase's established
`:ref`+`schemas` convention via a pure structural re-nesting (preserving
every name/tag/type unchanged) -- reaching `state-v1`'s own literal case
count, case names, record identity names, field names, and field types
exactly, while staying within the representational convention every
fixture in this chain already uses. `component-composition.clj`'s
`:ref`-only discipline for record case payloads and top-level variant types
is recorded below as a remaining, deliberately unattempted gap.

**Why the provider needed genuinely new engineering (closing gap (b)).**
`asymmetric-variant-capability-provider-wat` never reads or stores any
request PAYLOAD value (ADR 0058's own design -- it range-checks the
request discriminant and writes a FIXED, request-independent constant into
one of the result's own cases). Once a RESULT case can carry a
string/keyword leaf, that fixed constant can no longer be a bare WAT
literal push instruction (`constant-leaf-wat`, ADR 0058's own scalar-only
mechanism) -- a string/keyword leaf is a pointer+length pair into linear
memory, not a single core value. `plan-result-string-data` (new) walks
every RESULT case's own leaves (via the unmodified `variant-case-leaves`)
and assigns each string/keyword leaf a FIXED, sequential, 8-byte-aligned
data-segment address plus deterministic literal UTF-8 content
(`deterministic-constant-string`, distinct per `(case-index, leaf-index)`
pair for the same "tell two chosen cases/leaves apart" reason
`constant-leaf-wat` already was) -- embedded via `(data ...)`, exactly
`string-expression-wat`'s own literal-string convention, since this content
is a compile-time constant needing no runtime allocation at all (unlike a
copied-in REQUEST string, which genuinely must be realloc'd because it
arrives from an unknown caller). This module's own dynamic bump allocator
(`$next`) now starts strictly after that fixed literal region
(`:arena-base`, generalizing the prior hard-coded `8`) -- a no-op for every
case set with no string/keyword RESULT leaf (`plan-result-string-data`
returns `:arena-base 8` exactly, confirmed by rebuilding and re-running the
ADR 0058 fixtures unchanged through the changed function).

**The memory-sizing fix this ADR required on BOTH sides, and why the
existing "generous, not tight" formula did not simply carry over
unchanged** (the task's own suspicion, confirmed correct by investigation):

- **Provider side.** Before this ADR, `asymmetric-variant-capability-
  provider-wat` declared a flat one page / 65536-byte capacity
  unconditionally, correct only because neither admitted case kind ever
  carried a string/keyword leaf. Now that a REQUEST case can, the Canonical
  ABI's own cross-instance string-copy glue calls this module's *exported*
  `cm32p2_realloc` once per string-like leaf in the ACTIVE REQUEST case,
  *before this module's own body runs at all* -- exactly ADR 0057's own
  finding for the SAME-identity provider, reached for the asymmetric
  provider for the first time. Total required capacity is now `arena-base`
  (the fixed literal-data region above) PLUS `(string-headroom-bytes
  request-layout)` (the SAME generous per-leaf formula ADR 0057 already
  established, reused unchanged) PLUS the result struct's own `:size`.
- **Application side (`variant-capability-wat`).** ADR 0058 already fixed
  this function's `$realloc` call SIZE to use `result-layout` independently
  from the joined param signature's REQUEST layout, but left its memory-PAGE
  sizing keyed off `request-layout` alone (`variant-capability-string-
  headroom request-layout`) -- correct for every ADR 0055/0056/0057 fixture
  (request-layout = result-layout there) and for ADR 0058's own scope
  (neither side ever carried a string/keyword leaf), wrong in general. This
  module's own declared memory is the ONLY memory a caller can write a
  REQUEST string's bytes into (via this module's own exported
  `cm32p2_realloc`, exactly as every prior string-bearing fixture in this
  chain already required) *and* the memory this module's own `$realloc`
  must have room in when the crossing later lowers a RESULT string's bytes
  back into it (ADR 0057's own finding). These are two genuinely different,
  sequential draws against the SAME shared arena within one call, now that
  a string/keyword leaf can appear on EITHER side independently. The fix:
  `string-headroom-bytes` (new, factored out of `variant-capability-
  string-headroom`'s own formula) is computed separately for
  `request-layout` and `result-layout` and SUMMED, not maxed -- both
  amounts are real, sequential draws against the one arena, so declared
  capacity must cover both at once. This is a strict widening: when neither
  side carries a string/keyword leaf both summands are `0` and the formula
  reduces to exactly the prior one-page result.
- **Investigation finding (matching the task's own framing exactly):**
  the allocator sizing math needed BOTH "account for the larger of the two
  shapes' string headroom" AND "handle request-side and result-side
  allocation independently" -- not a single fix. The application side needs
  the SUM of both sides' headroom (not the max: the two draws are
  sequential against one arena, not exclusive alternatives). The provider
  side needs REQUEST-side headroom (realloc'd, for the glue's copy-in) PLUS
  a SEPARATE, FIXED (not realloc'd) allocation for its own literal RESULT
  constants -- genuinely two different kinds of allocation, not one formula
  applied twice.

**A new fail-closed boundary this ADR adds, matching the task's own
requirement to verify byte bounds independently on both sides.**
`asymmetric-variant-capability-provider-wat` never reads or stores any
request payload VALUE, so without an explicit check an oversized request
string/keyword leaf would cross the boundary entirely unvalidated, breaking
every prior ADR's fail-closed byte-bound discipline for no principled
reason. `variant-case-validation` (new -- the validation half of
`variant-case-body`, factored out with no behavior change) and
`asymmetric-request-validation-chain` (new -- the same per-case dispatch
shape as `variant-case-chain`, running validation only, no store) close
this: the active REQUEST case's own string/keyword leaves are now
range-checked (length against `:max-bytes`, pointer range against this
module's own capacity) even though their VALUES are never otherwise used.
Confirmed as a real Wasmtime trap for both Kotoba byte bounds on the
REQUEST side (see Evidence). The RESULT side's own string/keyword leaves
need no such runtime check and receive none: they are compile-time literals
this ADR's own code generates, always well under either bound by
construction -- this asymmetry (request bounds trap for real, result bounds
are trivially satisfied and not independently exercised as traps) is
recorded here explicitly, not left implicit.

**What is deliberately *not* admitted.** A case wrapping an ADR 0051
one-level-nested record remains fail-closed on the asymmetric path, exactly
as on the same-identity path (`string-field-record-schema` itself only
ever admitted one flat level, unchanged by this ADR). A bare
`:string`/`:keyword` case payload with no record wrapper remains
fail-closed on both paths, unchanged. `record-capability-call` (a bare
record, not variant-wrapped, as the whole capability request/result) is
untouched. `component-composition.clj`'s `:ref`-only discipline for a
variant case's record payload and for the crossing's own top-level type is
untouched (see "Two representational gaps" above) -- `state-v1.edn`'s own
literal inline-`:record`-in-a-case representation is reached only via the
test-only `ref-ify` converter, not by widening admission to accept it
directly.

## Evidence

All of the following used the *actual, non-monkeypatched* code path
(`component-core/emit` for the application half, `component-composition/
package-variant-asymmetric-provider` for the provider half,
`component-composition/compose-closed` for `wac plug` + `wasm-tools
validate`), not hand-assembled WAT, and every Wasmtime invocation ran the
**composed** (application + provider) component:

- **Primary fixture -- `state-v1`'s own literal shape, reached for the
  first time in this ADR chain.** `descriptor`/`result-descriptor`/
  `schemas` derived programmatically from `resources/kotoba/lang/
  capability-kits/state-v1.edn`'s own `:request`/`:result` EDN (`ref-ify`,
  see Scope). `wasm-tools component wit` on the composed artifact renders:

  ```
  variant kotoba-state-request { get(kotoba-state-get), put(kotoba-state-put), delete(kotoba-state-delete) }
  variant kotoba-state-result { found(kotoba-state-entry), missing(bool), written(kotoba-state-entry), deleted(bool), error(kotoba-state-error) }
  record kotoba-state-get { key: string }
  record kotoba-state-put { key: string, value: string }
  record kotoba-state-delete { key: string }
  record kotoba-state-entry { key: string, value: string, version: s64 }
  record kotoba-state-error { code: string, message: string }
  ```

  byte-for-byte the same case names, record identity names, field names,
  and field types as `state-v1.edn` (WIT `string` covers both Kotoba
  `:keyword` and `:string`, `s64` covers `:i64`, matching every prior
  ADR's own WIT rendering; `found`/`written` share `kotoba-state-entry`
  exactly as the real result does). `wasm-tools validate --features
  component-model` passed. Real Wasmtime 42.0.1 execution of the composed
  component round-tripped:
  - `invoke(get({key: "kotoba/status"})) -> found({key:
    "kotoba/state-case-0-leaf-0", value: "kotoba/state-case-0-leaf-1",
    version: 17})` (request case 0 maps to result case `mod(0,5)=0`,
    `found`, a string-bearing record case reached through the crossing for
    the first time).
  - `invoke(put({key: "namae/漢字", value: "日本語のテスト🎉"})) ->
    missing(false)` (request case 1, carrying multi-byte UTF-8 in both a
    keyword and a string leaf, maps to result case `mod(1,5)=1`, a bare
    bool -- confirming a string-bearing REQUEST can cross to a
    non-string-bearing RESULT case).
  - `invoke(delete({key: "a"})) -> written({key:
    "kotoba/state-case-2-leaf-0", value: "kotoba/state-case-2-leaf-1",
    version: 219})` (request case 2 maps to result case `mod(2,5)=2`,
    `written` -- the SAME `entry` record type as `found`, at a DIFFERENT
    output-case index, confirming the shared record type is planned
    correctly per case).
  - A 513-byte request `key` (one byte past `keyword-value-byte-limit`,
    512) on `get` traps for real (`wasm trap: wasm \`unreachable\`
    instruction executed`, process exit 134) -- confirming the new
    request-side validation chain (`asymmetric-request-validation-chain`)
    engages even though this provider never otherwise reads the value.
  - A 512-byte `key` at exactly the bound succeeds (`found(...)` returned).
  - A 65537-byte `value` (one byte past `string-value-byte-limit`, 65536)
    on `put` traps the same way.
  - A 65536-byte `value` at exactly the bound succeeds (`missing(false)`
    returned).
  - Both traps and both at-bound successes reproduced against `get`/`put`
    independently, confirming both Kotoba byte bounds (`keyword`: 512,
    `string`: 65536) are exercised as genuine Wasmtime traps on the
    REQUEST side of the asymmetric crossing, for the first time.
- **Minimal fixture, request-side string/keyword.** `demo/cap-string-
  outcome` (`found: cap-entry`/`missing: bool`, `cap-entry` = `state-v1`'s
  own real `entry` shape verbatim, the exact ADR 0057 fixture) as REQUEST,
  crossing to `demo/other-outcome` (bare `urgent: bool`, no string/keyword
  leaf at all) as RESULT: composed, validated, and round-tripped
  `invoke(found({key: "kotoba/status", value: "ready", version: 42})) ->
  urgent(true)` and `invoke(missing(true)) -> urgent(true)` (both request
  cases map to the sole result case, its own fixed constant, confirming the
  dispatch is keyed off the discriminant alone). The same 513-byte-key/
  65537-byte-value traps and 512/65536-byte at-bound successes reproduced
  on this fixture too.
- **Minimal fixture, result-side string/keyword (the reverse pairing,
  proving independence of the two sides' new engineering).** `demo/other-
  outcome` (bare `urgent: bool`) as REQUEST crossing to `demo/cap-string-
  outcome` (`found: cap-entry`/`missing: bool`) as RESULT: composed,
  validated, and round-tripped `invoke(urgent(true)) -> found({key:
  "kotoba/state-case-0-leaf-0", value: "kotoba/state-case-0-leaf-1",
  version: 17})`, confirming `plan-result-string-data`'s fixed literal
  data-segment mechanism and the application side's RESULT-headroom
  summand both function correctly with the string/keyword leaf on the
  opposite side from the fixture above.
- **No-regression check on every changed function.** The ADR 0055 fixture
  (`demo/flag-or-ratio`, same-identity, no strings), the ADR 0056 fixture
  (`demo/state-outcome`, same-identity, scalar-only `entry`), the ADR 0057
  fixture (`demo/cap-outcome`, same-identity, string/keyword-bearing
  `entry`), and all three ADR 0058 fixtures (`demo/flag-or-ratio` ->
  `demo/status-outcome`; `demo/cap-outcome` -> `demo/other-record-outcome`;
  `demo/state-request` -> `demo/state-result`, the all-`:i64` structural
  stand-in) were rebuilt through the CHANGED functions
  (`variant-capability-wat`'s new summed headroom formula,
  `variant-case-body`'s refactor through `variant-case-validation`,
  `asymmetric-variant-capability-provider-wat`'s new `plan-result-string-
  data`/arena-base/headroom logic) and re-run through real Wasmtime
  execution: every one of their previously-recorded round trips reproduced
  byte-for-byte unchanged, confirming every fix in this ADR is a true no-op
  for a case shape that needs none of it.
- `wasm-tools validate --features component-model` passed on every composed
  component built above.
- Focused suite (`component-artifact-test`, `component-composition-test`,
  `component-wit-test`, `canonical-abi-test`, `backend-qualification-test`):
  49 tests, 252 assertions, 0 failures (up from ADR 0058's own recorded
  46/234 -- 3 new `deftest` forms this ADR adds, including the primary
  `state-v1`-literal-shape fixture, both minimal string-on-one-side
  fixtures, and the updated fail-closed boundary tests).
- Full `clojure -M:test` suite: 436 tests, 4513 assertions, 0 failures (up
  from ADR 0058's own recorded 429/4485), against the pinned `wasm-tools
  1.243.0` and `wac-cli 0.10.1` (unchanged pins -- this ADR needed no
  toolchain change).
- `clojure -M -m kotoba.compiler.backend-qualification verify wasmtime`
  (and, matching ADR 0058's own additional checks, `verify native` and
  `verify cljs`): all three report the identical `:provider-manifest-
  sha256` and `:gaps` list recorded before this change (confirmed by
  running the identical command against the pre-change tree in the same
  session), confirming no capability kit's qualification moved as a side
  effect and `resources/kotoba/lang/capability-kits/*.edn` is untouched.

Fail-closed boundaries re-verified directly by test after the widening, at
both the KIR-admission layer (`component-core/emit`) and the
provider-composition layer (`package-variant-asymmetric-provider`):

- a case wrapping an ADR 0051 one-level-nested record on the asymmetric
  path (unchanged from ADR 0058, re-run by the existing tests, now with an
  updated failure fixture since the string-bearing one moved to the
  positive-evidence tests above);
- same-identity input rejected by `package-variant-asymmetric-provider`
  itself, independent of `different-variant-capability-call`'s own
  KIR-layer rejection (unchanged from ADR 0058);
- a bare `:string`/`:keyword` case payload with no record wrapper (unchanged);
- a computed capability request and an unknown capability id (unchanged).

## Remaining gaps

`state-v1`'s literal `:request`/`:result` EDN now crosses a real composed
`typed-cap-call` boundary end to end at the ABI layer, with real Wasmtime
execution and both byte bounds exercised as genuine traps on the request
side. This ADR does NOT close `state-v1` end to end as a *usable
capability*, for reasons every prior ADR in this chain has named and this
one does not change:

1. **A real production `state` provider remains entirely unattempted.**
   Every provider in this ADR chain, including this one, is a wiring-only
   fixture. This ADR's own provider is, if anything, further from real
   semantics than an identity provider: it never reads the request's own
   payload at all (only its discriminant), and its RESULT string/keyword
   content is a compile-time literal, not derived from any request value or
   from `src/kotoba/compiler/provider/state.cljc`'s own bounded 256-entry
   reference semantics, which remain entirely unwired to any Component-level
   capability call.
2. **`component-composition.clj`'s `:ref`-only discipline for a variant
   case's record payload and for the crossing's own top-level type is
   untouched.** `state-v1.edn`'s own literal EDN embeds every case's record
   payload inline (`[:record name fields]`, never `[:ref name]`); this ADR
   reaches its exact case/field shape only through the test-only `ref-ify`
   converter (a pure structural re-nesting into this codebase's established
   `:ref`+`schemas` convention), not by widening `component-composition.clj`
   to accept the inline form directly. A future increment that needs a
   PRODUCTION caller to hand `component-core/emit`/`package-variant-
   asymmetric-provider` `state-v1.edn`'s own EDN verbatim (rather than a
   test converting it first) would need this widening -- a small, purely
   representational fix (matching what `component-core.clj`'s own admission
   functions already accept), not a new semantic capability, but genuinely
   unattempted here to avoid a third dimension of change in one increment.
3. **Native-AOT and JIT remain entirely untouched**, as they have been
   since this whole ADR chain began.
4. **Lists/tuples/options/results, multiple capability calls, multiple
   exported functions, and every production provider's real semantics for
   all nine capabilities** all remain closed, unchanged from every prior
   ADR.

No capability kit's `:qualification` changes as a result of this ADR.
`resources/kotoba/lang/capability-kits/state-v1.edn` is **not modified** --
its `:qualification` map remains exactly `{:reference :implemented
:wasm-aot :pending :native-aot :pending :jit :pending}`. This is a
deliberate, conservative choice, not an oversight: this ADR closes the ABI
CROSSING gap for `state-v1`'s own literal shape completely (structural
proof, real Wasmtime execution, both byte bounds trapping for real), but
"wasm-aot implemented" would need to mean the capability is genuinely
usable end to end, including real provider semantics -- explicitly NOT what
this ADR proves (gap 1 above). Every prior ADR in this chain, without
exception, left `resources/kotoba/lang/capability-kits/*.edn` untouched for
the identical reason; this ADR, despite reaching further than any before
it, follows the same discipline rather than being the first to claim more
than it has actually proven.
