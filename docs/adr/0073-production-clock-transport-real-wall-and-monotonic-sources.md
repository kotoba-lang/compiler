# ADR 0073: Production wall/monotonic clock sources for the typed clock capability kit, plus a `:cljs` portability fix to `clock.cljc`'s own i64 representation

Status: accepted; production `:clj` AND `:cljs` sources implemented and
tested end to end (unlike ADR 0064/0066/0071's `:cljs` gap)

## Decision

`kotoba.compiler.provider.clock/provider` (ADR 0029) has always taken
`{:wall-now (fn [] tick) :monotonic-now (fn [] tick)}` as host-injected
zero-argument source functions -- ADR 0049's gap ledger named `clock`
(alongside `llm`/`http`/`storage`) as one of the remaining "identity wiring
fixtures, not implementations": every existing test only ever supplied
`(constantly 0)`-style fakes. This ADR adds the first REAL sources for
`:clock/now`: `kotoba.compiler.provider.clock-transport/production-clock-
source`, a constructor returning
`{:wall-now (fn [] ...) :monotonic-now (fn [] ...)}` ready to spread
directly into `clock/provider`.

`clock.cljc` itself is functionally unmodified for `:clj` and gets one
narrow, non-weakening portability fix for `:cljs` (see "The `:cljs` fix to
`clock.cljc` itself" below). Every bound it already enforces -- non-
negative-whole-number validation on both domains, monotonic-regression
detection via `:clock/regressed`, provider-local observation sequencing,
redacted typed `:clock/source` errors for a throwing source -- is unchanged
and un-weakened.

### Why this capability's scope is much smaller than ADR 0064/0066/0071's LLM/HTTP/storage transports

`clock-v1.edn` differs from `llm-v1.edn`/`http-v1.edn`/`storage-v1.edn` on
the one axis that made those three transports large: `:source-authority
:host` names the HOST's own already-running process clock as the source,
not a third party over a network the host must be configured (endpoint,
allow-list, credential) to reach. `:ambient-clock false` says only what it
has always said -- the COMPILER itself must never bake in a call to a time
API; only a host-constructed provider may read one. Reading a process's own
clock is not new ambient authority a host must be granted; every host this
repo targets already has one, synchronously, for free. So there is no
endpoint, allow-list, credential, or wire protocol to design here -- the
entire production namespace is two zero-argument reads per host, a small
fraction of ADR 0064/0066/0071's size (`clock-transport.cljc` is under 140
lines against their 371/472/422).

### `:cljs` gets a REAL implementation too, unlike its LLM/HTTP/storage siblings

ADR 0064/0066/0071 each leave `:cljs` an explicit, documented gap for the
same reason: every reference provider's `:transport` contract in this repo
is a plain synchronous `(fn [request] -> reply)`
(`kotoba.compiler.reference-runtime` has no promise/callback machinery a
provider could return through), and nbb/Node's `fetch` is Promise-based --
faking synchrony over a genuinely asynchronous network call is a separate,
larger, independently-reviewable design decision none of those ADRs make
unilaterally on cljs's behalf. Reading a clock has no such asynchrony to
fake: `js/Date.now` and `js/performance.now` are exactly as synchronous as
`System/currentTimeMillis`/`System/nanoTime` are on the JVM, and are
available globally on every `:cljs` host this repo targets (Node's own
Performance Timing API has been global since Node 16; the W3C High
Resolution Time spec guarantees the same for browsers). So this ADR
implements BOTH hosts for real, not a stub -- the first capability in this
ADR chain (0064/0066/0067/0071/0072) to do so.

`:clj`'s `production-clock-source` supplies `System/currentTimeMillis` for
wall time verbatim (already Unix milliseconds, matching `clock-v1.edn`'s
`:wall {:unit :unix-milliseconds}`), and a monotonic source anchored to a
single `System/nanoTime` reading taken once at construction
(`(- (System/nanoTime) origin)`). The anchor exists because
`System/nanoTime`'s own Javadoc is explicit its raw return value is
"nanoseconds since some fixed but arbitrary origin time (perhaps in the
future, so values may be negative)" -- i.e. the JVM does NOT guarantee the
raw value is non-negative, only that differences between two readings in
the same JVM instance are meaningful. Anchoring is a constant per-instance
offset, so it changes nothing about ordering or regression detection: a
genuine backward jump in the underlying source still regresses the anchored
sequence by exactly the same amount, and `clock.cljc`'s own
`:clock/regressed` check (not this namespace) still catches it. The only
effect is that the anchored sequence is guaranteed non-negative by
construction rather than "non-negative in practice on every tested
JDK/platform" (see Remaining gaps for the one edge case this trades away).

`:cljs`'s `production-clock-source` supplies `js/Date.now` for wall time and
a monotonic source built from `js/performance.now()` (converted to
nanoseconds via `* 1e6` and `js/Math.round`). No anchoring is needed on
`:cljs`: unlike `System/nanoTime`, the W3C spec guarantees
`performance.now()` is always non-negative AND monotonically non-decreasing
by construction (it exists specifically to replace `Date.now()` for
interval measurement, because wall time can jump backward on NTP
adjustment). Both a positive multiplication and `Math.round` are
monotonically non-decreasing functions of their input, so this conversion
cannot itself introduce a spurious regression.

### The `:cljs` fix to `clock.cljc` itself

Building the `:cljs` sources and testing them through the FULL typed
`clock/provider` boundary (a compiled `.kotoba` guest calling
`typed-cap-call`, via `kotoba.compiler.reference-runtime`, exactly the way
`clock_provider_test.clj` already exercises the `:clj` side) surfaced a
real, independent, pre-existing `clock.cljc` defect that no source function
this ADR could supply would work around:

1. `clock.cljc`'s own `valid-tick?` was `(and (integer? tick) (<= 0
   tick))` -- `cljs.core/integer?` does NOT reliably recognize a JS
   `bigint` (confirmed live: `(integer? (js/BigInt 5))` => `false`; the
   sibling `kotoba.compiler.cljs-i64` namespace's own docstring already
   warns of exactly this for `zero?`/`neg?`/`pos?`/`integer?`). So a
   `bigint` tick -- the ONLY representation the ABI boundary accepts for an
   `:i64` value crossing back into a compiled guest on `:cljs` (per
   `kotoba.compiler.value`/`cljs-i64`) -- was unconditionally rejected as
   `:clock/invalid` by `clock.cljc`'s own gate, while a plain cljs number
   tick passed that SAME gate but then failed the ABI boundary's own
   post-provider check one level up in `ir/execute` ("value is not a signed
   i64"). No tick value could satisfy both checks simultaneously under
   `:cljs` -- this was not a shape a transport could route around.
2. `clock.cljc`'s own provider-local `sequence-number` counter
   (`(atom 0)`, incremented via plain `swap! ... inc`) is ITSELF an
   `:i64`-typed field (`:observation-sequence` in both `wall-type` and
   `monotonic-type`), generated entirely inside `clock.cljc`, never
   touched by a host's source functions at all. On `:cljs` this counter
   produced a plain number every time, which fails the exact same ABI
   check for the exact same reason, regardless of what `wall-now`/
   `monotonic-now` return.

Both were reproduced live (see Evidence) with a direct call through
`reference-runtime/instantiate` + a compiled one-function `.kotoba` guest,
independent of this ADR's own source functions. This ADR's fix is narrow
and confined entirely to `clock.cljc`, and changes nothing about `:clj`
(both `#?(:clj ...)` branches are byte-for-byte the prior `:clj` behavior):

- `valid-tick?` on `:cljs` now checks `(and (i64/bigint-value? tick) (not
  (i64/k-neg? tick)))` instead of `(integer? tick)`/`(<= 0 tick)` -- the
  SAME non-negative-whole-number requirement, expressed in the host's own
  canonical i64 representation instead of a representation only `:clj`
  ever actually produces.
- The `sequence-number` atom now initializes to `i64/zero` (`:cljs`) /
  `0` (`:clj`) and increments via a new `next-sequence!` helper that adds
  `i64/one` with JS's native bigint `+` on `:cljs` (an exact, unbounded-
  precision addition; cljs's own `+` compiles down to the native operator
  for two bigint operands) or plain `inc` on `:clj`, unchanged.

This is a portability fix, not a policy change: the non-negative-whole-
number requirement `valid-tick?` enforces, and the fact that the
observation sequence is a plain per-provider-instance auditable counter,
are identical on both hosts before and after -- only the concrete host-
native representation of "whole number" the `:cljs` branch checks against
changed, to the SAME representation `kotoba.compiler.value`/`cljs-i64`
already require everywhere else in this codebase for an `:i64` crossing the
ABI boundary. Neither branch admits anything the other branch would reject.

## Evidence

- **Live repro of the pre-existing `clock.cljc` defect, before this ADR's
  fix, via `nbb`** (a compiled one-function `.kotoba` guest -- source string
  built the same way `clock_provider_test.clj` builds its own -- run
  through `frontend/analyze` + `admission/check` + `ir/lower` +
  `reference-runtime/instantiate`, exactly the full typed boundary, not a
  raw call to `clock/provider`'s `:invoke`):
  - Supplying a `bigint` tick (`(constantly (js/BigInt 1700000000000))`) ->
    `[result-type :error [error-type :clock/invalid "wall clock value is
    invalid"]]` -- `valid-tick?`'s own `integer?` check rejected it.
  - Supplying a plain-number tick (`(constantly 1700000000000)`) -> threw
    `{:phase :ir :trap :invalid-parametric-value :message "value is not a
    signed i64"}` from `ir/execute`'s own post-provider ABI check -- past
    `valid-tick?`, but rejected one level up.
  - Confirms: no possible tick value passed both gates before this ADR's
    fix, independent of any source function.
- **Live repro that the `sequence-number` counter itself was the second,
  independent half of the same defect**: even after supplying THIS ADR's
  real `:cljs` sources (already coerced to `bigint`, see Decision), the
  SAME `invalid-parametric-value`/"value is not a signed i64" trap still
  fired -- traced to the plain-number `(swap! sequence-number inc)` result,
  never touched by a host's source functions at all. Fixed by
  `next-sequence!` (see Decision); after the fix, the identical repro
  script returns a genuine `[result-type :wall [wall-type #object[BigInt
  ...] #object[BigInt 1]]]`.
- **Unit test suite** (`test/kotoba/compiler/clock_transport_test.clj`, 7
  `deftest`s, `:clj` only -- no fake server needed, unlike ADR 0064/0066/
  0071, since there is no network to fake):
  - `wall-now`/`monotonic-now` from `production-clock-source` each return a
    non-negative integer.
  - `wall-now`'s value is sandwiched between two `System/currentTimeMillis`
    readings taken immediately around the call.
  - `monotonic-now`'s anchor makes the very first observation read well
    under one second (proving the anchoring logic actually anchors).
  - 10,000 tight-loop `monotonic-now` calls are all non-negative integers
    and never regress (`apply <=`).
  - Two independently-constructed `production-clock-source` calls each
    have their own independently-anchored monotonic origin (no shared
    mutable state leaking between provider instances).
  - A full round trip through `clock/provider` + `reference-runtime` for
    both `:wall` and `:monotonic` domains: wall value sandwiched around the
    real call, 500 tight-loop monotonic observations all typed `:monotonic`
    with strictly-sequential observation sequences (`1..500`) and non-
    decreasing nanosecond values, and a shared, incrementing observation
    sequence across the `:wall`/`:monotonic` domains on the SAME provider
    instance.
  - Registered in `test/kotoba/compiler/test_runner.clj` alongside the
    sibling `*-transport-test` namespaces.
- **`:cljs`/nbb test suite** (`test/nbb/clock-transport.cljs`, run via `npm
  run test-nbb-clock-transport` -> `scripts/test-nbb-clock-transport.cljs`,
  which mirrors `scripts/test-nbb-wasm32.cljs`'s own `clojure -Spath`-
  resolved-classpath launcher pattern exactly, needed because
  `kotoba.compiler.admission` requires the `kotoba.security.abac` git
  dependency, invisible to a literal `--classpath .:src`): 5 cases, all
  passing --
  - `wall-now`/`monotonic-now` each return a non-negative `bigint`
    (`kotoba.compiler.cljs-i64/bigint-value?`), sandwiched between two
    independent `js/Date.now`/`js/performance.now`-derived readings taken
    immediately around the call.
  - 10,000 tight-loop `monotonic-now` calls are all non-negative bigints
    and never regress.
  - A full round trip through `clock/provider` + `reference-runtime`,
    through a COMPILED `.kotoba` guest, on `:cljs`: `:wall` sandwiched and
    typed correctly with observation sequence `i64/one`; 500 tight-loop
    `:monotonic` observations all typed `:monotonic`, non-decreasing
    nanosecond bigints, with strictly-sequential bigint observation
    sequences `1n..500n` -- this is the first ADR in this chain (0064/
    0066/0067/0071/0072) whose `:cljs` branch is verified through the full
    typed provider boundary rather than left as a documented gap.
  - Every equality assertion in this test file compares `bigint` against
    `bigint` explicitly (`kotoba.compiler.cljs-i64`'s `one`/`zero`, or an
    explicit `js/BigInt` literal), never against a bare cljs integer
    literal -- `cljs.core/=`, unlike `<=`, does NOT consider a bigint and a
    same-valued plain number equal (`(= 1 (js/BigInt 1))` => `false`,
    confirmed live), a second, independent place this ADR's own
    investigation had to route around the bigint/plain-number split.
- Full `clojure -M:test` suite with this ADR's changes applied: 563 tests,
  4871 assertions, 0 failures, 0 errors -- including this ADR's own new
  `clock-transport-test` namespace, and confirming the `clock.cljc` fix
  changes nothing observable on `:clj` (every pre-existing `clock-
  provider-test`/`provider-conformance-test` assertion, none of which
  exercise `:cljs`, still passes unchanged).

## Remaining gaps

1. **The `:clj` monotonic anchor's one accepted edge case**: a regression
   in the underlying raw `System/nanoTime` source below the value it had at
   provider-construction time (not merely below a LATER observation, which
   `:clock/regressed` still catches correctly) would surface the anchored
   difference as negative, and thus `:clock/invalid` rather than
   `:clock/regressed`. This requires the raw JVM source to regress below
   its OWN very first-ever recorded reading for this provider instance --
   an already-vanishingly-rare hardware/OS-bug scenario beyond ordinary
   expected monotonic-clock regressions, which this ADR accepts as a
   trade-off for guaranteeing non-negativity by construction rather than by
   accident of the JVM's own arbitrary origin (see Decision).
2. **`:cljs`'s nanosecond conversion is bounded by IEEE-754 double
   precision before the `BigInt` coercion, not after.** `js/Math.round (*
   (.now js/performance) 1e6)` happens in ordinary double-precision
   arithmetic; `Number.MAX_SAFE_INTEGER` (~9.007e15) is reached after
   roughly 104 days of continuous `performance.now()` uptime. The `BigInt`
   coercion at the end is exact and lossless for whatever double-precision
   value it is given, but does not extend the precision of the
   multiplication that produced it. A `:cljs` host whose process/page has
   been running continuously for months could see nanosecond monotonic
   values lose precision (though still remain non-decreasing, since
   `Math.round`/multiplication-by-a-positive-constant are both
   monotonically non-decreasing functions of an already-monotonic input).
3. **`:cljs`'s monotonic value is NOT anchored to construction time the
   way `:clj`'s is.** `performance.now()`'s origin is the host's own
   process/page start, which may already be well underway by the time a
   `production-clock-source` is constructed -- unlike `:clj`, there is no
   guarantee the first `:cljs` observation reads as "close to zero." This
   is intentional (see Decision: no anchoring is needed for non-negativity
   on `:cljs`, since the spec already guarantees it), but it is a real,
   documented asymmetry between the two hosts' monotonic magnitude
   (ordering/non-negativity guarantees are identical; absolute starting
   magnitude is not).
4. **No live-deployment/production-host integration test.** Every test
   here runs the actual real `System.*`/`js/Date`/`js/performance` APIs
   directly (there is no fake server to stand up, unlike ADR 0064/0066/
   0071 -- this capability has no network to fake), but none of them
   exercise this transport wired into an actual running compiler host
   process outside this repo's own test suite.
5. **This ADR does not touch the WASM Component Model layer** (ADR chain
   0037-0063) at all -- it closes a gap only at the JVM/Chicory AND
   browser/nbb reference-provider layer, the same scope ADR 0064/0066/0071
   closed for LLM/HTTP/storage respectively. `clock-v1.edn`'s
   `:qualification` map (`{:reference :implemented :wasm-aot :pending
   :native-aot :pending :jit :pending}`) is unchanged by this ADR.
6. **Delays, deadlines, scheduling, timezone data, calendar arithmetic,
   virtual clocks, and event subscriptions remain out of scope**, unchanged
   from ADR 0029's own v1 scope declaration (its docstring: "This
   capability observes time only"). This ADR supplies REAL sources for the
   existing `:clock/now` observation semantics; it does not widen them.
7. **No retry/backoff, quota, or audit-hook convention is added.** Unlike
   ADR 0064/0066/0071's optional `:on-call` observability hook, this
   namespace deliberately omits one: those three capabilities' `:on-call`
   hooks exist to observe outbound calls to a THIRD PARTY (a network round
   trip worth auditing independently of the guest's own typed result); a
   local, synchronous clock read has no comparable I/O event to observe
   beyond the typed result `clock.cljc` itself already returns and
   sequences. A future host that wants one can wrap
   `production-clock-source`'s returned functions itself; this ADR does
   not add ceremony without an identified operational need.

## Related

- ADR 0029 (typed clock capability kit v1) -- the schema, bounds, and
  host-injection seam (`{:wall-now :monotonic-now}`) this transport
  supplies real sources for; its "CLJ/CLJS reference provider implemented"
  status line is, for the first time, actually verified end to end through
  the full typed boundary on BOTH hosts by this ADR (previously true only
  in the sense that the file loaded under both readers, not that a
  compiled guest's `typed-cap-call` result actually validated on `:cljs`).
- ADR 0064 (production LLM transport) / ADR 0066 (production HTTP
  transport) / ADR 0071 (production storage transport) -- this ADR's own
  structural template (Decision/Evidence/Remaining gaps/Related) and the
  direct point of contrast for why THIS capability's scope is far smaller
  (no host-chosen network destination) and why, uniquely among the four,
  `:cljs` gets a real implementation rather than a documented gap.
- ADR 0067 (state provider has no transport gap) / ADR 0072 (log and UI
  providers have no transport gap) -- the prior installments of ADR 0049's
  "assess clock/log/ui against their actual self-contained
  implementations" bullet; this ADR is the one that assesses `clock`
  specifically (ADR 0072's own "Related"/closing paragraph named `clock` as
  the one remaining capability of the three without its own correction/
  transport ADR). Unlike `state`/`log`/`ui` (which turned out to need no
  transport at all -- fully self-contained against closed-over atoms),
  `clock` DOES have a real host-injection seam (`clock-v1.edn`'s
  `:source-authority :host`), so this ADR's shape matches ADR 0064/0066/
  0071's "add a real transport" pattern, scaled down, rather than ADR
  0067/0072's "no transport gap exists" pattern.
- ADR 0049 (component/application-language gap ledger) -- names "production
  provider Components for all nine capabilities" as next-completion-order
  item 2, and (per ADR 0072's own addendum) named `clock` as the one
  capability among clock/log/ui still lacking its own correction ADR. This
  ADR closes that naming for `clock` specifically, at the JVM/Chicory AND
  cljs/nbb reference-provider layer (not yet the WASM Component Model
  layer) -- see that ADR's own updated ledger entry (Progress addendum) for
  the precise scope of what remains.
- `resources/kotoba/lang/capability-kits/clock-v1.edn` -- the capability
  kit's own machine-readable semantics (`:source-authority :host`,
  `:ambient-clock false`, `:observation-sequence :provider-local`) this
  ADR's sources implement rather than reinterpret.
- `kotoba.compiler.cljs-i64` -- the shared i64-as-`bigint` helper namespace
  (already used by `frontend`/`ir`/`backend.wasm`/`value`) this ADR's
  `clock.cljc` fix and `clock-transport.cljc`'s `:cljs` sources both adopt,
  rather than inventing a parallel bigint convention.
