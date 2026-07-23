# ADR 0055: Scalar-case variant `typed-cap-call` Canonical ABI crossing

Status: accepted; scalar-only-case variant capability-call slice implemented

## Decision

A direct `typed-cap-call` may now use one sealed variant, whose every case's
payload is a bare Canonical scalar (`i64`/`f32`/`f64`/`bool`), as its
request *and* result (same identity, matching `record-capability-call`'s own
same-type discipline, ADR 0048, rather than ADR 0046's scalar slice, which
lets request and result differ). This is the first slice to cross a
*structured* (multi-case, discriminated) type through a capability-call
boundary -- ADR 0046 crossed one bare scalar, ADR 0048 crossed one flat
all-scalar record; this ADR crosses one variant. It closes *both* the
request and the result side at once, because they are the same admitted
type -- the "bonus" scope the task briefing allowed for, reached by
reusing ADR 0048's own same-identity pattern rather than attempting a
different-shape request/result pair (which would need real provider
semantics, not identity wiring).

`kotoba.compiler.component-core` gains `scalar-variant-capability-schema`
(admission) and `variant-capability-wat`/`variant-capability-provider-wat`
(application-side and provider-side WAT), reusing the joined-flat layout
(`canonical/layout`'s `:flat` on the variant descriptor) and, on the
provider side, the *unmodified* `variant-case-chain`/`variant-disc-store`
codegen `variant-wat` (the ADR 0052/0054 identity-export path) already
built -- no duplication of the disc-range-check/coercion/store logic.
`kotoba.compiler.component-composition` gains `variant-wit`/
`package-variant-identity-provider`, the provider-side counterpart to
`record-wit`/`package-record-identity-provider`. `component-wit.clj` needed
no changes at all: `type-text`'s existing `[:ref name]` case and
`capability-contracts`' existing generic `typed-cap-call` body walk already
render a variant request/result exactly like a record one, confirming the
same "no changes needed" finding ADR 0054 reached for the identity-export
side now also holds for the application-side WIT of a capability call.

The division of labor between application and provider deliberately departs
from `record-capability-wat`'s own precedent: `record-capability-wat`
validates bool fields on the *application* side before crossing, even
though its own provider does not revalidate. For a variant, disc-dependent
per-case validation can only be done correctly by whichever side already
knows which case is active -- which is exactly the side performing the
case-dispatch store. This ADR puts both disc-range-checking and per-case
bool validation on the *provider* side only (reusing `variant-wat`'s own
case-chain unmodified), leaving the application module a thin pass-through
that forwards the discriminant and every joined payload value plus a
caller-allocated result pointer to the import, unchanged, exactly mirroring
`scalar-capability-wat`'s own no-validation precedent for a single scalar
leaf.

**A materially narrower final scope than initially attempted, for a
concrete, reproduced reason, not a guess.** The initial implementation
target was `scalar-variant-capability-schema` admitting the same case-kind
set the identity-export path already admits (bare scalar, sealed
all-scalar record -- ADR 0052 -- or sealed string/keyword-bearing record --
ADR 0053/0054), applied to `demo/state-result` itself (`found: entry`/
`missing: bool`, `entry` being `state-v1`'s real `key: keyword, value:
string, version: i64` shape) as both request and result, deliberately
close to the task's own suggested target. The application half and the
provider half of this each independently packaged and validated as
well-formed components (`wasm-tools component new --reject-legacy-names`
succeeded for both alone), but composing them with `wac plug` (pinned
0.9.0) failed every time with `error: encoding produced a component that
failed validation / Caused by: type not valid to be used as import`. This
was reproduced across four independent variations, ruling out several
hypotheses before accepting it as a genuine tooling boundary rather than a
bug in this change: (1) a single record-only case (no scalar case mixed
in), (2) three cases mixing scalar and record, (3) a one-field record case
(simplifying the record itself), and (4) a hand-written provider WIT/WAT
(bypassing this ADR's own generator entirely) declaring the variant
*before* its record dependency, matching the application side's own
alphabetical schema-name declaration order -- all four failed identically,
ruling out case count, case mix, record field count, and declaration order
as the cause. The common factor across all four failures, and the one
respect in which they differ from every already-proven capability-call
shape (ADR 0046's bare scalar, ADR 0048's flat record, and this ADR's own
scalar-only variant), is that the shared `types` WIT interface exports two
types where one (the variant) references the other (the record) *and* that
interface crosses a capability import/export boundary composed by `wac
plug`. Every already-proven capability-call shape has exactly one exported
type in `types`. This is recorded as a currently-blocked path (see
`kotoba.compiler.component-core/scalar-only-variant-case?`'s docstring for
the full reproduction detail), not force-fitted around with a workaround
whose correctness could not be checked against a real crossing.

The scope was cut back to bare-scalar-only variant cases, which keeps
`types` to exactly one exported type per capability-call boundary and
composes correctly. This does *not* close any part of `state-v1`'s actual
`entry`/`result` shape (both need a record case, which remains blocked
here) -- it proves the variant-crossing *wiring* discipline (disc-based
case dispatch through a real cross-component call, join/coercion table
included) on the narrower case-kind set that is currently known to survive
`wac plug`, exactly the same "narrow but real, not force-fitted" discipline
every prior ADR in this series has followed.

Real Wasmtime 42.0.1 execution of the composed (application + provider)
component, not only `wasm-tools validate` on the composed bytes:
`demo/three-way` (`found: i64`/`missing: bool`/`failed: f32`, exercising
the i64-widening join) round-trips `found(-9223372036854775808)`,
`found(9223372036854775807)`, `missing(true)`, `missing(false)`, and
`failed(3.5)` unchanged; `demo/flag-or-ratio` (the exact ADR 0052
bool/f32 join fixture, exercising the `i32`/`f32` special-case join and its
`f32.reinterpret_i32` coercion) round-trips `urgent(true)`, `urgent(false)`,
and `weight(2.75)` unchanged, now crossing a real application-component to
provider-component boundary rather than staying inside one module.

Fail-closed boundaries verified directly: a variant case wrapping a sealed
all-scalar record used as a capability request/result is rejected by
`component-core/emit` ("component function body has no qualified Canonical
lowering", before any encoding is attempted) *and* independently rejected
by `component-composition/package-variant-identity-provider` ("provider
variant requires scalar-only cases") -- both layers fail closed rather than
relying on the downstream `wac`-level encoding error as the only signal;
different request/result variant identities remain fail-closed, matching
`record-capability-call`'s own same-identity discipline; a computed
capability request and an unknown capability id remain fail-closed,
matching every prior `typed-cap-call` slice.

`resources/kotoba/lang/capability-kits/*.edn` is untouched by this ADR.
None of the seven capability kits' `:wasm-aot`/`:native-aot`/`:jit`
qualification changes -- this ADR proves ABI/wiring for a *narrower*
variant shape than `state-v1` actually needs (no record case at all, so
`state-v1`'s own `entry`/`error` records inside its `result` variant remain
unreached by a capability-call crossing), still with an identity provider
(no real state semantics), matching ADR 0047's own explicit framing.

## Evidence

- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`): 33 tests, 105
  assertions, 0 failures, run against the pinned `wasm-tools 1.243.0` and
  `wac-cli 0.9.0`.
- full `clojure -M:test` suite: 410 tests, 4366 assertions, 0 failures
  before this change (confirmed identical to ADR 0054's own recorded
  baseline); run again after with the new tests included.
- `wasm-tools validate --features component-model` on both composed
  (application + provider) closed components (`demo/three-way`,
  `demo/flag-or-ratio`): passed.
- manual Wasmtime 42.0.1 invocation of both composed components (eight
  successful round trips total, listed above, covering full i64 range and
  both special-case entries of the Component Model spec's join/coercion
  table): each returned the input unchanged, now crossing a real
  application-to-provider component boundary.
- reproduced tooling boundary: `wac plug` (pinned 0.9.0) fails encoding any
  record-referencing-variant provider crossing a capability boundary
  (`type not valid to be used as import`), across four independent
  variations (case count, case mix, record field count, declaration order),
  while every already-proven capability-call shape and this ADR's own
  scalar-only variant compose correctly -- recorded as a currently-blocked
  path, not attempted further here.

## Remaining gaps

`state-v1`'s actual request (`get`/`put`/`delete`, both records with
string/keyword fields) and result (`found`/`written`: `entry`, a
string/keyword record; `missing`/`deleted`: `bool`; `error`: a
string/keyword record) types are not closed by this ADR at all -- every
one of `state-v1`'s own non-bool cases wraps a record, exactly the shape
this ADR found blocked at the `wac plug` layer. Closing `state-v1` for real
needs, in some order: (1) resolving or working around the
record-referencing-variant-crossing `wac plug` limitation this ADR
discovered and reproduced (candidates not yet tried: a newer `wac`/
`wasm-tools` release past the pinned versions, restructuring the shared
`types` interface so the record and variant are not co-exported from the
same instance, or filing the reproduction upstream), (2) string/keyword
leaves crossing a capability boundary at all (no case kind exercises this
yet -- even a *record* capability call, ADR 0048, has only ever crossed
all-scalar records), and (3) a real production `state` provider (this ADR's
providers, like every prior capability-call ADR's, are wiring-only identity
fixtures -- `src/kotoba/compiler/provider/state.cljc`'s bounded 256-entry
reference semantics remain entirely unwired to any Component-level
capability call). Different request/result variant identities, computed
capability requests inside admitted control flow, multiple capability
calls, and multiple exported functions all remain fail-closed exactly as
ADR 0049 already recorded. Every production provider's real semantics for
all nine capabilities remain closed. No capability kit's `:wasm-aot`
qualification changes as a result of this ADR.
