# ADR 0058: Different request/result variant identities cross `typed-cap-call`

Status: accepted; different-identity (asymmetric), scalar-or-record-cased variant capability-call slice implemented

## Decision

A direct `typed-cap-call` may now use TWO independently-admitted but
GENUINELY DIFFERENT sealed variant identities as its request and result,
provided each side's every case is a bare Canonical scalar
(`i64`/`f32`/`f64`/`bool`, ADR 0055) or a sealed all-scalar record (the ADR
0052 shape, ADR 0056). This closes the exact gap every capability-call ADR
through 0057 named, in the same words, as still unattempted:

> `state-v1`'s request and result are two *different* variant identities,
> while every capability-call ADR ... has only ever proven the
> *same*-identity case.

`kotoba.compiler.component-core` gains `different-variant-capability-call`
(admission, structurally identical to `variant-capability-call` except it
checks `(not= request-type result)` instead of `(= request-type result)`,
and each side against the narrower `asymmetric-variant-capability-schema`
rather than `variant-capability-schema`) and
`asymmetric-variant-capability-provider-wat` (a new provider WAT emitter --
see "Why the provider needed new engineering" below).
`kotoba.compiler.component-composition` gains `asymmetric-variant-wit`/
`package-variant-asymmetric-provider`, the different-identity counterparts
to `variant-wit`/`package-variant-identity-provider`.
`kotoba.compiler.component-wit` needed no changes at all: its
`capability-interface-text` already renders `func(request: <request-type>)
-> <result-type>` from two independently-walked descriptors (confirmed by
direct inspection, not merely by analogy to ADR 0054/0055/0056/0057's own
"no changes needed" findings for that file -- this is the first ADR to
actually exercise the request-type/result-type-independent code path that
function's own generic signature always had).

`demo/state-request` (`get`/`put`/`delete`, matching `state-v1.edn`'s own
request case names and record field names exactly) crossing to
`demo/state-result` (`found`/`missing`/`written`/`deleted`/`error`,
matching `state-v1.edn`'s own result case names exactly, `found` and
`written` sharing one record type exactly as the real `state-v1` result
does) is this ADR's own concrete evidence fixture reaching the FULL literal
case count, case names, and record-field names of `state-v1`'s actual
request and result types for the first time in this ADR chain. It is
narrower than `state-v1`'s own literal EDN only in field *type*: every
field here is `:i64`, a structural stand-in for `state-v1`'s real
`:keyword`/`:string` fields -- deliberately, for a reason recorded below,
not an oversight.

## Scope

**What is new, precisely.** Two admission functions now govern a variant
crossing a `typed-cap-call` boundary: `variant-capability-call` (unchanged,
still requires `(= request-type result)`, still admits ADR 0057's
string/keyword-bearing record case) and the new
`different-variant-capability-call` (`(not= request-type result)`, admits
only ADR 0055/0056's scalar-or-all-scalar-record case-kind union). They are
mutually exclusive by construction and `assert-supported!` checks both, so
neither widens nor narrows the other. On the provider-composition side,
`package-variant-identity-provider` (unchanged) and the new
`package-variant-asymmetric-provider` follow the identical split.

**Why the case-kind set is narrower here than ADR 0057's same-identity
path (deliberately, not as a limitation discovered late).** ADR 0057's own
"Remaining gaps" named two separate, ordered next steps after string/
keyword crossing: "(1) `state-v1`'s request and result are two *different*
variant identities ... (2) a real production `state` provider ... remains
unattempted." This ADR closes exactly step (1) and deliberately does not
also re-open the string/keyword-crossing risk ADR 0057 already closed for
the *same*-identity path, for the same reason ADR 0055 -> 0056 -> 0057
never widened by two dimensions in one step: combining "genuinely different
request/result identity" (which by itself requires new engineering, see
below) with "string/keyword leaves crossing an asymmetric boundary" (which
would require proving that the Canonical ABI's cross-instance string-copy
glue and this ADR's own independent request/result memory sizing compose
correctly together -- an unattempted risk, not yet reduced to the "generous,
not tight" formula this codebase already trusts for the *same*-layout
case) is not the smallest honest increment. `asymmetric-variant-capability-
case?`'s own docstring records this reasoning at the point where a future
increment would need to revisit it.

**Why the provider needed genuinely new engineering, not just a widened
admission check.** Every prior capability-call ADR's provider
(`variant-capability-provider-wat`) is, in essence, an *echo*: it reuses
`variant-wat`'s own case-chain to store the ACTIVE REQUEST case's own
payload into a result area sized and shaped like the SAME schema, because
request and result were, by construction, identical. That machinery is not
merely narrower than what an asymmetric crossing needs -- it is
categorically inapplicable: there is no way to "echo" a `get` request case
(payload `{key: i64}`) into a `found` result case (payload `{key: i64,
value: i64, version: i64}`) of an unrelated shape. The new
`asymmetric-variant-capability-provider-wat` is therefore a genuinely
different kind of wiring fixture, matching the task's own framing exactly:
it range-checks the request discriminant, never reads any request PAYLOAD
leaf at all, and writes one of the RESULT variant's own cases with a fixed
compile-time-constant payload, the case chosen deterministically by
`(mod request-case-index (count result-cases))` so every request case maps
to *some* valid result case even when the two case counts differ (3 vs. 5
for `state-v1`'s own shape), and different request discriminants are
provably distinguishable in a round trip because the constant payload is
itself keyed off the chosen output case's own index (`constant-leaf-wat`).
This is explicitly NOT `state`'s real semantics -- no request payload value
ever informs the result's own value, only which case gets written -- the
same "wiring only" framing every prior identity provider in this chain
already carried, now applied to a provider that, unlike an identity
provider, cannot even in principle be semantically neutral, because request
and result are different types.

**The application-side fix this ADR required, and why it is a genuine bug
fix rather than a rename.** `variant-capability-wat` (the application-side
module, shared unchanged between the same-identity and different-identity
paths since ADR 0055) previously computed ONE `variant-layout` from
`(:request plan)` alone and used it BOTH to build the joined param
signature AND to size the `$realloc` call that allocates the out-pointer
handed to the provider as the result destination. For every ADR
0055/0056/0057 fixture this was correct only because request and result
were, by construction, the identical schema -- sizing the result area from
the request layout was silently relying on that coincidence, never
exercised against a case where it would matter. This ADR generalizes the
function to compute `request-layout` and `result-layout` independently
(from `(:request plan)` and `(:result plan)` respectively) and uses
`result-layout`'s own `:alignment`/`:size` for the `$realloc` call --
required for correctness in the different-identity case, and confirmed to
be exactly a no-op for the same-identity case (`request-layout` and
`result-layout` are structurally `=` there) by rebuilding and re-running
the ADR 0055/0056/0057 fixtures through the CHANGED function and confirming
byte-for-byte identical Wasmtime round trips (see Evidence). The joined
param signature and string-headroom sizing remain keyed off the REQUEST
layout only, unchanged -- correct because (per the case-kind restriction
above) this ADR's own admitted shapes never carry a string/keyword leaf on
either side, so no cross-instance string-copy glue ever triggers for this
crossing at all, and a fixed one page is always enough for any scalar/
record-only struct this admission produces.

**What is deliberately *not* admitted.** A case wrapping a sealed
string/keyword-bearing record (the ADR 0053/0057 shape) remains fail-closed
for the different-identity crossing specifically (though already admitted
for the *same*-identity crossing since ADR 0057) -- see the case-kind
scoping reasoning above. `state-v1`'s own literal request/result (whose
`entry`/`error` records really are `:keyword`/`:string`-bearing) is
therefore still not closed end to end by this ADR. A real production
`state` provider remains entirely unattempted, as it has been since ADR
0055 -- every provider in this ADR chain, including this one, is a
wiring-only fixture; `src/kotoba/compiler/provider/state.cljc`'s bounded
256-entry reference semantics remain entirely unwired to any Component-
level capability call.

## Evidence

All of the following used the *actual, non-monkeypatched* code path
(`component-core/emit` for the application half,
`component-composition/package-variant-asymmetric-provider` for the
provider half, `component-composition/compose-closed` for `wac plug` +
`wasm-tools validate`), not hand-assembled WAT, and every Wasmtime
invocation ran the **composed** (application + provider) component:

- **Primary fixture, exactly the task's own suggested minimal step:**
  `demo/flag-or-ratio` (`urgent: bool`/`weight: f32`, request, the exact
  ADR 0052/0055 fixture reused unmodified) crossing to `demo/status-outcome`
  (`ready: bool`/`failed: f64`, result, a genuinely different, differently
  named and differently shaped 2-case variant): composed successfully,
  `wasm-tools validate --features component-model` passed, real Wasmtime
  42.0.1 execution of the composed component round-tripped
  `invoke(urgent(true)) -> ready(true)`,
  `invoke(urgent(false)) -> ready(true)` (request case 0 always maps to
  result case 0, `ready`, with its own fixed constant, regardless of the
  request's own payload value -- confirming the provider genuinely never
  reads the request payload), and `invoke(weight(2.5)) -> failed(104)`
  (request case 1 maps to result case 1, `failed`, with a DIFFERENT fixed
  constant of the DIFFERENT descriptor `f64` -- confirming the dispatch is
  keyed off the request's own discriminant, not a shared position).
- **Record-case fixture, proving the widened admission is case-kind
  agnostic on the asymmetric path too, not only scalar:** `demo/cap-outcome`
  (`tally: cap-tally{count: i64}`/`empty: bool`, request, the exact ADR
  0055/0056 fixture reused unmodified) crossing to
  `demo/other-record-outcome` (`total: cap-total{sum: i64, ok: bool}`,
  result, a differently-shaped single-case record variant): composed,
  validated, and round-tripped `invoke(tally({count: 42})) ->
  total({sum: 3, ok: false})` and `invoke(empty(true)) -> total({sum: 3,
  ok: false})` (both request cases map to the sole result case with its own
  fixed constant, correctly stored at each of the record's two distinct
  field offsets).
- **Stretch fixture, reaching `state-v1`'s own literal case count, case
  names, and record-field names for the first time in this ADR chain:**
  `demo/state-request` (`get: {key: i64}`/`put: {key: i64, value:
  i64}`/`delete: {key: i64}`) crossing to `demo/state-result` (`found:
  state-entry{key, value, version: i64}`/`missing: bool`/`written:
  state-entry`/`deleted: bool`/`error: state-error{code, message: i64}`,
  `found` and `written` sharing one record type exactly as `state-v1`'s
  own real result does): composed successfully, `wasm-tools validate
  --features component-model` passed, and real Wasmtime 42.0.1 execution
  round-tripped `invoke(get({key: 5})) -> found({key: 3, value: 10,
  version: 17})`, `invoke(put({key: 5, value: 99})) -> missing(false)`, and
  `invoke(delete({key: 5})) -> written({key: 205, value: 212, version:
  219})` -- three different request cases (of three total) producing three
  different, correctly-shaped, correctly-offset result cases (of five
  total), each a distinct constant per leaf. Because `request-case-count`
  (3) is less than `result-case-count` (5) here -- exactly `state-v1`'s own
  real case-count asymmetry -- `deleted` and `error` are never selected by
  this fixed `mod`-based dispatch; this is recorded honestly as a
  limitation of the wiring-only dispatch's *coverage*, not of the
  crossing's own correctness (every case this dispatch does reach is
  verified correct, and reachability of every result case was never a
  requirement this ADR set out to prove).
- **No-regression check on the shared, changed application-side function
  (`variant-capability-wat`):** the ADR 0055 fixture (`demo/flag-or-ratio`,
  same identity), the ADR 0056 fixture (`demo/state-outcome`, scalar-only
  `entry`, same identity), and the ADR 0057 fixture (`demo/cap-outcome`,
  string/keyword-bearing `entry`, same identity) were rebuilt through the
  CHANGED code (`variant-capability-wat` now computes `request-layout`/
  `result-layout` independently rather than one shared `variant-layout`)
  and re-run through real Wasmtime execution: `urgent(true)`,
  `weight(2.75)`, `found({key: -9223372036854775808, value:
  9223372036854775807})`, `missing(true)`,
  `found({key: "kotoba/status", value: "ready", version: 42})`, and
  `missing(false)` each round-tripped unchanged, byte-for-byte matching
  ADR 0055/0056/0057's own recorded results -- confirming the layout-
  separation fix is a true no-op when request and result coincide.
- **Fail-closed boundaries re-verified directly, at both the KIR-admission
  layer AND the provider-composition layer, matching every prior ADR's
  double-layer discipline:**
  - Same-identity input is rejected by `package-variant-asymmetric-provider`
    itself ("requires two distinct admitted scalar-or-record variant
    identities"), independent of the fact that `different-variant-
    capability-call` already refuses to admit it at the KIR layer.
  - A different-identity crossing where one side's case wraps a sealed
    string/keyword-bearing record (the ADR 0053/0057 shape) is rejected at
    both layers: `component-core/emit` ("no qualified Canonical lowering")
    and `package-variant-asymmetric-provider` ("requires two distinct
    admitted scalar-or-record variant identities").
  - A different-identity crossing where one side's case wraps an ADR 0051
    one-level-nested record remains fail-closed at the KIR-admission layer,
    for the same underlying reason it was already fail-closed on the
    same-identity path.
- `wasm-tools validate --features component-model` passed on every composed
  component built above.
- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`,
  `backend-qualification-test`): 46 tests, 234 assertions, 0 failures (up
  from ADR 0057's own recorded 42 tests / 212 assertions -- the 4 new
  `deftest` forms this ADR adds).
- full `clojure -M:test` suite: 429 tests, 4485 assertions, 0 failures
  (up from ADR 0057's own recorded 425 tests / 4463 assertions), against
  the pinned `wasm-tools 1.243.0` and `wac-cli 0.10.1` (unchanged pins --
  this ADR needed no toolchain change).
- `clojure -M -m kotoba.compiler.backend-qualification verify wasmtime`
  (and, checked additionally beyond ADR 0057's own evidence, `verify
  native` and `verify cljs`): all three report the identical
  `:provider-manifest-sha256` and `:gaps` list recorded before this change
  (confirmed by running the identical command against the pre-change tree
  in the same session), confirming no capability kit's qualification moved
  as a side effect and `resources/kotoba/lang/capability-kits/*.edn` is
  untouched.

## Remaining gaps

`state-v1`'s actual request (`get`/`put`/`delete`, each a record with
`:keyword`/`:string` fields) and result (`found`/`written`: the
`:keyword`/`:string`-bearing `entry`; `missing`/`deleted`: bare `bool`;
`error`: a similarly `:keyword`/`:string`-bearing record) are still not
closed *end to end* by this ADR, for two remaining reasons:

1. **String/keyword-bearing cases crossing the different-identity
   boundary.** This ADR deliberately narrowed the different-identity
   crossing to exactly ADR 0055/0056's scalar-or-all-scalar-record case-kind
   union, leaving ADR 0057's string/keyword-bearing record case unattempted
   for this specific (asymmetric) boundary -- see "What is deliberately not
   admitted" above for why. Closing this needs, at minimum: (a) confirming
   the Canonical ABI's cross-instance string-copy glue composes correctly
   when request-layout and result-layout genuinely differ (this ADR's own
   `variant-capability-wat` fix establishes the *result-area sizing*
   correctness for that case, but headroom is still deliberately computed
   from the request layout alone, correct only because neither side may
   carry a string leaf in this ADR's scope); (b) a provider that, unlike
   this ADR's own fixed-constant dispatch, can allocate and write literal
   string/keyword byte data for a chosen result case, which this ADR's
   provider never needed to do.
2. **A real production `state` provider.** Every provider in this ADR
   chain, including this one, is a wiring-only fixture. This ADR's own
   provider is *further* from real semantics than every prior identity
   provider, not closer: it never even reads the request's own payload,
   only its discriminant, because (unlike an identity provider) it has no
   principled way to derive a result of an unrelated shape from the
   request's own data. `src/kotoba/compiler/provider/state.cljc`'s bounded
   256-entry reference semantics remain entirely unwired to any
   Component-level capability call.

Both gaps were already named, in this order, in ADR 0055's, 0056's, and
0057's own "Remaining gaps" sections as the two steps left after
different-identity crossing; this ADR closes only the different-identity
crossing step that preceded them, for the case-kind set that composes
correctly today without also re-opening the string/keyword-crossing risk.

Also still closed, unchanged from ADR 0057: a bare `:string`/`:keyword`
case payload with no record wrapper; a variant case wrapping an ADR 0051
one-level-nested record; `record-capability-call` (a bare record, not
variant-wrapped, as a capability request/result) with string/keyword
fields or with different request/result record identities (this ADR
touches only the *variant* capability-call path, not the *record*
capability-call path -- widening `record-capability-call` to admit
different request/result record identities, mirroring this ADR's own
`variant-capability-call` widening, is a separate, unattempted slice);
lists/tuples/options/results; multiple capability calls; multiple exported
functions; and every production provider's real semantics for all nine
capabilities. No capability kit's `:wasm-aot`/`:native-aot`/`:jit`
qualification changes as a result of this ADR; none of
`resources/kotoba/lang/capability-kits/*.edn` is modified.
