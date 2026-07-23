# Component Model and WASI baseline

This document records the official specifications reviewed for
`wasm-component-kotoba-v1`. The machine-readable contract is
`resources/kotoba/lang/component-model-v1.edn`.

## Baseline selected on 2026-07-20

- WebAssembly Component Model at revision
  `e6bb1e456e946abd01173755468134d7c75c9f07`. Component binaries use the
  component-layer preamble and contain core modules, instances, component
  types, canonical functions, imports, and exports.
- WIT from the same revision. Versioned package names, interfaces, and worlds
  define the portable contract. Standard scalar spellings are `s64`, `f32`,
  `f64`, `bool`, and `string`.
- Canonical ABI from the same revision. The compiler must generate the core
  memory, reallocation, lifting/lowering, and required post-return adapters;
  Kotoba's existing `externref` host representation is not the component ABI.
- WASI 0.3.0 at tag revision
  `3ee2a590c766594ae44a54730fc74fc27da5c609` is the platform baseline. Ordinary
  deterministic providers continue to use WIT `func`; native async functions,
  streams, and futures require the explicit bounded async profile.
- WASI 0.2.11 at tag revision
  `ed73919426173babd88ae145e31deca3d484bbd0` remains an explicit legacy
  compatibility profile and is never selected implicitly.

## Kotoba interpretation

WIT describes transport shape, not all Kotoba semantic constraints. Strings,
lists, canonical sets/maps, descriptor depth, and aggregate byte/node budgets
are checked by compiler-generated validators before provider invocation and
after provider return. The optional gated fixed-length-list syntax is not a v1
dependency.

General recursive Kotoba schemas are rejected by Component v1. A digest-bound
Kotoba schema cannot be lowered merely by assigning a WIT name when its
recursive identity and bounds are not preserved.

An application world imports exactly the interfaces corresponding to its
declared capabilities. WASI interfaces appear only in provider components and
only as listed in the relevant capability rule. In particular, storage does
not imply filesystem access and LLM does not imply socket access.

The compiler rejects `async func`, `future<T>`, and `stream<T>` unless the
checked language effect declares async execution and supplies cancellation,
deadline, item, and byte budgets. Selecting WASI 0.3 alone grants no authority.

Deterministic synchronous WIT generation from checked KIR is implemented by
`kotoba.compiler.core/compile-component-wit` (ADR 0037). The first validated
component binary slice is implemented by `compile-component` for scalar,
capability-free exports (ADR 0038). Structured request/result Canonical ABI
lowering, provider composition, and runtime semantic vectors remain pending
stages. The scalar component core uses standard32 `cm32p2` names and passes
the pinned toolchain's `--reject-legacy-names` qualification (ADR 0039).
Canonical layout planning for bounded UTF-8 strings is implemented separately
from admission (ADR 0040). A byte-preserving bounded string identity export is
now executable through linear memory (ADR 0041); records, variants, lists, and
capability request/results remain fail-closed.
String parameters, UTF-8 literals, and nested concatenation are lowered with a
single bounded allocation (ADR 0042); other string operations remain closed.
Named non-empty records containing only scalar fields have checked layout and
an executable identity slice (ADR 0043). Record operations, nested aggregates,
and provider request/results remain closed.
Direct field projection from those records to a scalar result is executable
(ADR 0044); construction, update, and nested record expressions remain closed.
Direct scalar-parameter construction and one-field scalar `record-assoc` are
executable through a shared field-source/result-area plan (ADR 0045). Computed
and nested record expressions remain closed.
Direct typed capability calls with scalar request/result now lower to a
standard32 application component import (ADR 0046). Provider composition and
all structured capability payloads remain closed.
Compiler-owned scalar provider artifacts can now close that import through the
current `wac plug` path plus official `wasm-tools` validation (ADR 0047). The identity
provider proves wiring only; kit semantics and structured payloads remain
unqualified.
One sealed nominal record containing only scalar fields can now cross a direct
capability call and an identity provider through Canonical ABI result areas
(ADR 0048). Nested aggregates and production provider semantics remain closed.

ADR 0049 is the authoritative closing gap ledger for this implementation
session. In particular, identity providers and successful composition are
wiring evidence only. Wasmtime and native remain pending until nested Canonical
codecs, production providers, shared semantic vectors, and their respective
runtime gates are complete.
A sealed record whose fields are scalar or exactly one level of nested sealed
all-scalar record now has a checked layout, a Canonical-flattened leaf plan,
and an executable identity slice (ADR 0051), with a manual Wasmtime 42.0.1
round trip on a two-level record shape. Two or more levels of nesting,
strings inside records, lists/tuples/options/results/variants (including a
variant case wrapping a record), nested aggregates crossing a capability
request/result boundary, and every production provider's semantics remain
closed; no capability kit's `:wasm-aot` qualification changed.
A sealed variant whose every case is a Canonical scalar or a sealed
all-scalar record now has both a checked in-memory union layout and a
checked joined component-flat signature, and an executable identity slice
(ADR 0052), with manual Wasmtime 42.0.1 round trips on a four-case
scalar-and-record shape and a two-case bool/f32 shape covering the full
join/coercion table the Component Model spec defines for shared flat
positions. A variant case wrapping an ADR 0051 one-level-nested record or
another variant, strings or keywords inside a case's record payload (so
`state-v1`'s actual result type remains unqualified), a variant used as a
record field or crossing a `typed-cap-call` request/result boundary,
lists/tuples/options/results, and every production provider's semantics all
remain closed; no capability kit's `:wasm-aot` qualification changed.
`:keyword` now has a Canonical ABI layout for the first time (treated
identically to a bounded `string` -- pointer+length in linear memory, the
same ADR 0040/0041 machinery -- but bounded by `value/keyword-value-byte-limit`
instead of `value/string-value-byte-limit`), and a sealed flat nominal record
whose fields are each a Canonical scalar or a bounded `string`/`keyword` leaf
now has an executable identity slice (ADR 0053), with manual Wasmtime 42.0.1
round trips on `state-v1`'s own `entry` shape (`key: keyword, value: string,
version: i64`) covering full i64 range and multi-byte UTF-8 in both a string
and a keyword leaf in the same call, plus both byte bounds exercised as real
Wasmtime traps (not only implemented defensively). A variant case wrapping a
record with string/keyword fields (so `state-v1`'s actual result type, a
variant over `entry`/`error`, remains unqualified end to end), string/keyword
leaves inside an ADR 0051 nested-record field, a variant used as a record
field, string/keyword fields crossing a `typed-cap-call` request/result
boundary, lists/tuples/options/results, and every production provider's
semantics all remain closed; no capability kit's `:wasm-aot` qualification
changed.
A sealed variant whose every case's payload is independently a Canonical
scalar, a sealed all-scalar record (ADR 0052), or a sealed flat
string/keyword-bearing record (ADR 0053) -- cases may freely mix all three
kinds within one variant -- now has an executable identity slice (ADR 0054),
closing exactly the combination both ADR 0052's and ADR 0053's own remaining
gaps named next. `canonical-abi.cljc` needed no code changes at all (its
existing recursive layout/flatten already handled this shape generically);
the admission predicate and WAT emitter in `component-core.clj` did, with
manual Wasmtime 42.0.1 round trips on a concrete structural slice of
`state-v1`'s own `result` type (`found: entry`/`missing: bool`, `entry`
being `state-v1`'s real `key: keyword, value: string, version: i64` shape)
covering full i64 range and multi-byte UTF-8, a second three-case shape
proving all three case kinds can mix freely in one variant, and both byte
bounds exercised as real Wasmtime traps inside a variant case for the first
time. `state-v1`'s actual five-case `result` type is not closed end to end
(two representative cases proven, not all five; the request side, a real
`typed-cap-call` crossing, and a real production state provider remain
separately gapped), string/keyword leaves inside an ADR 0051 nested-record
field, a variant used as a record field or nested inside another variant's
case, lists/tuples/options/results, and every production provider's
semantics all remain closed; no capability kit's `:wasm-aot` qualification
changed.
A direct `typed-cap-call` may now use one sealed variant, whose every case's
payload is a bare Canonical scalar, as its same-identity request *and*
result, crossing a real composed application-plus-provider component for
the first time (ADR 0055) -- ADR 0046 crossed one bare scalar and ADR 0048
crossed one flat all-scalar record; this is the first *structured, multi-
case* type to cross a capability-call boundary, closing both the request
and result side at once (same identity, matching ADR 0048's own discipline).
Manual Wasmtime 42.0.1 execution of the composed component round-trips the
full i64-widening join and the i32/f32 special-case join/coercion table
(the exact ADR 0052 `flag-or-ratio` fixture), now crossing a real
application-to-provider boundary rather than staying inside one module.
A materially narrower case-kind than the identity-export path admits
(scalar only, no record case) for a concrete, reproduced reason: a variant
case wrapping a record, crossing a capability boundary, was tried first and
found to fail `wac plug` (pinned 0.9.0) encoding with `type not valid to be
used as import`, reproduced across four independent variations (case
count, case mix, record field count, and `types`-interface declaration
order all ruled out as the cause) -- recorded as a currently-blocked path,
not force-fitted around. `state-v1`'s actual request/result types are not
closed by this ADR at all (every one of its own non-bool cases wraps a
record, exactly the shape found blocked); string/keyword leaves crossing
any capability boundary, different request/result variant identities, and
every production provider's semantics all remain closed; no capability
kit's `:wasm-aot` qualification changed.
The ADR 0055 `wac plug` limitation is now resolved: it was a real,
independently reproduced `wac` defect (plug-time re-encoding of a `use`'d
type that references another `use`'d type as a fresh local definition
rather than aliasing the imported instance's own export, `type not valid to
be used as import`), not a bug in Kotoba's own WIT/Canonical ABI generation
(confirmed by review -- the WAT emitters were already case-kind agnostic,
zero lines changed) and not a permanent tooling ceiling. `bytecodealliance/
wac` v0.10.0 (PR #205, "Alias `use`'d types during composition instead of
re-encoding them locally") fixes exactly this failure mode; ADR 0056
independently confirmed the fix against ADR 0055's own exact failing
fixture before touching any source, then narrowly bumped the pinned
`wac-cli` from 0.9.0 to 0.10.1 (checking blast radius first: no concurrent
PR/branch touches Component Model, `wac` is invoked nowhere else in this
codebase, and CI installs its own fresh ephemeral `wac-cli` per run). A
sealed variant whose every case is a bare Canonical scalar *or* a sealed
all-scalar record (the ADR 0052 shape) now crosses a real `typed-cap-call`
capability boundary (ADR 0056), widening ADR 0055's scalar-only admission by
exactly the record-case kind the `wac plug` limitation had blocked, with a
real composed application-plus-provider component and real Wasmtime 42.0.1
execution proven on `demo/state-outcome` (`found: entry` / `missing: bool`,
`entry` a structural stand-in for `state-v1`'s own `entry` with scalar-only
fields rather than its real keyword/string fields) covering the full i64
range and both variant cases. Still deliberately narrower than the
identity-export path: a case wrapping a sealed *string/keyword-bearing*
record (the ADR 0053 shape) remains fail-closed for a capability-call
boundary, because string/keyword data crossing a capability-call boundary at
all is a separate, still-unattempted gap the `wac` fix does not touch (no
case kind, and no plain record `typed-cap-call` either, has ever exercised
it). `state-v1`'s actual request/result types (whose real `entry`/`error`
records are string/keyword-bearing) remain unclosed by this ADR for that
reason; a real production `state` provider also remains unattempted (every
provider in this ADR chain, like every prior one, is a wiring-only identity
fixture); no capability kit's `:wasm-aot` qualification changed.
A sealed variant whose every case is a bare Canonical scalar, a sealed
all-scalar record (ADR 0052), *or* -- new here -- a sealed flat record
whose fields are each a Canonical scalar or a bounded `string`/`keyword`
leaf (the ADR 0053 shape) now crosses a real `typed-cap-call` capability
boundary too (ADR 0057), closing the exact gap ADR 0055's and ADR 0056's
own "Remaining gaps" both named as separately unattempted: string/keyword
data crossing a capability-call boundary at all. `demo/cap-outcome`
(`found: cap-entry`/`missing: bool`), the concrete evidence fixture, uses
`cap-entry = {key: keyword, value: string, version: i64}` -- `state-v1`'s
own real `entry` record verbatim, not a narrower structural stand-in --
composed with a real identity provider and run under real Wasmtime 42.0.1
execution, round-tripping the full `i64` range, multi-byte UTF-8 in both a
keyword and a string leaf in the same call, and both Kotoba byte bounds
(512-byte keyword, 65536-byte string) exercised as genuine traps for the
first time across a cross-component boundary rather than only within one
module. Unlike ADR 0056's own "zero lines of WAT-emission code changed"
finding, this ADR required real new engineering: once a case can carry a
string/keyword leaf, the Canonical ABI's own generated cross-instance
string-lowering glue calls a module's *exported* `cm32p2_realloc` itself,
an unpredictable extra number of times, before that module's own function
body runs -- the provider's previous fixed-constant-address result
allocation (correct only when it was ever the sole allocation in a call)
would have silently collided with those glue-driven calls, corrupting
data; both the application and provider modules now route their result
allocation through a real, capacity-bounded bump allocator sized with
string headroom (reusing `variant-wat`'s own single-module allocator
pattern), confirmed not to regress the ADR 0055/0056 no-string fixtures by
rebuilding and re-running both through real Wasmtime execution after the
change. A bare `:string`/`:keyword` case payload with no record wrapper, a
case wrapping an ADR 0051 one-level-nested record, `record-capability-call`
(a bare record, not variant-wrapped) with string/keyword fields,
lists/tuples/options/results, different request/result variant identities,
and every production provider's real semantics all remain closed; no
capability kit's `:wasm-aot` qualification changed. `state-v1` is still not
closed end to end: its request and result are two *different* variant
identities, while every capability-call ADR including this one has only
proven the *same*-identity case, and a real production `state` provider
remains unattempted.
A `typed-cap-call` may now use two INDEPENDENTLY admitted but genuinely
DIFFERENT sealed variant identities as its request and result (ADR 0058),
closing exactly the different-identity gap ADR 0055/0056/0057 each named,
in the same words, as still unattempted -- provided each side's every case
is a bare Canonical scalar or a sealed all-scalar record (ADR 0055/0056's
own case-kind union; ADR 0057's string/keyword-bearing record case is
deliberately not admitted for this specific, asymmetric boundary yet, for
the same "don't widen two dimensions in one step" discipline every prior
step in this chain followed). The application-side WAT emitter
(`variant-capability-wat`) needed a genuine bug fix, not a rename: it
previously sized its result-area allocation from the REQUEST layout alone,
correct only by coincidence when request and result were, by construction,
the same schema; it now sizes that allocation from the RESULT layout
independently, confirmed a no-op for every ADR 0055/0056/0057 same-identity
fixture by rebuilding and re-running them through the changed function.
The provider needed a genuinely new kind of implementation, not merely a
widened admission check: unlike every prior capability-call ADR's provider
(which echoes the active request case's own payload into a result area of
the identical shape), an asymmetric provider cannot echo a request case
into an unrelated result shape at all -- the new
`asymmetric-variant-capability-provider-wat` instead reads only the
request's own discriminant (never any request payload leaf) and writes one
of the result variant's own cases with a fixed, deterministically-chosen
compile-time constant, an explicitly non-semantic wiring fixture matching
the task's own framing. Concrete evidence spans a small 2-case/2-case
scalar pair, a record-cased pair, and -- reaching further than any prior
ADR in this chain -- `demo/state-request`/`demo/state-result`, matching
`state-v1.edn`'s own literal case names, case count (3 request cases vs. 5
result cases), and record-field names exactly, narrower than the real
`state-v1` only in using `:i64` fields as a structural stand-in for its
real `:keyword`/`:string` fields. `state-v1` is still not closed end to
end: a string/keyword-bearing case crossing this specific (different-
identity) boundary, and a real production `state` provider, both remain
unattempted; no capability kit's `:wasm-aot` qualification changed.
The different-identity (asymmetric) `typed-cap-call` crossing now also
admits ADR 0057's sealed flat string/keyword-bearing record case on EITHER
side (ADR 0059), closing exactly the gap ADR 0058's own "Remaining gaps"
named first. Combined with ADR 0058's own different-identity admission,
this reaches `state-v1.edn`'s own literal `:request`/`:result` EDN exactly
for the first time in this ADR chain -- `descriptor`/`result-descriptor`/
`schemas` derived programmatically (a pure structural re-nesting, not a
hand transcription) from the real resource file, composed successfully,
`wasm-tools validate` passed, and real Wasmtime 42.0.1 execution round-
tripped `get`/`put`/`delete` requests (including multi-byte UTF-8 in both a
keyword and a string leaf) to `found`/`missing`/`written` results (`found`
and `written` correctly sharing one `entry` record type at different
output-case indices), with both Kotoba byte bounds (512-byte keyword,
65536-byte string) exercised as genuine Wasmtime traps on the request side
for the first time on this boundary. Two real memory-sizing fixes were
required, matching the task's own suspicion that the existing formula would
not simply carry over: the application module's memory-page headroom is
now the SUM (not the max, and no longer sized from the request side alone)
of independently-computed request-side and result-side string headroom,
since both are real sequential draws against the one shared arena within a
single call; the provider module gains a genuinely new allocation kind
(`plan-result-string-data`, fixed compile-time `(data ...)` segments for
its own literal string/keyword RESULT constants, distinct from the
REQUEST-side headroom it separately needs for the Canonical ABI's own
cross-instance copy-in glue). A new request-side-only validation chain
(`asymmetric-request-validation-chain`) was also added so an oversized
request string/keyword leaf still traps even though this wiring-only
provider never otherwise reads the value. `state-v1` is closed at the ABI-
crossing layer only, not as a usable capability: a real production `state`
provider remains entirely unattempted (this provider is, if anything,
further from real semantics than ever -- its result content is a
compile-time literal, not derived from any request value), and
`component-composition.clj`'s own `:ref`-only discipline for a variant
case's record payload still does not accept `state-v1.edn`'s own literal
inline-`:record`-in-a-case representation directly (reached here only via a
test-only structural converter into this codebase's established
`:ref`+`schemas` convention); no capability kit's `:qualification` changed,
and `resources/kotoba/lang/capability-kits/state-v1.edn` is not modified.
The first REAL (non-wiring-only) provider in this ADR chain now exists
(ADR 0060): `kotoba.compiler.component-core/state-provider-wat` is a
genuine, small (4-slot, deliberately far smaller than the pure-Clojure
reference's 256), bounded, in-memory key/value store for `state-v1`'s own
literal shape, with real dispatch on the request's own discriminant, real
reads of the request's own `key`/`value` payload leaves, real persistent
mutable state across calls within one component instance (a bounded table
plus a real WASM mutable global for the version counter, both untouched by
the transient bump-allocator reset every prior provider already used), and
a real byte-CONTENT equality check over linear memory (`$bytes-equal`, a
genuine bounded WASM `loop` -- the only function in this namespace that
uses `loop` or early `return`, since matching a request's key against a
runtime-length range of stored bytes cannot be expressed by the nested
`if`/`else` case dispatch every other emitter in this namespace uses).
`get`/`put`/`delete` semantics were verified against
`src/kotoba/compiler/provider/state.cljc`'s own reference implementation
and its own test, not assumed, including its slightly surprising
conventions (a GLOBAL, not per-key, monotonic version counter; `delete`
always succeeds and reports `deleted(true/false)`, never `missing`/
`error`, even for an absent key). Composed against the STANDARD, UNCHANGED
application-side `variant-capability-wat` for `state-v1`'s own literal
shape, real Wasmtime 42.0.1 execution round-tripped `get`/`put`/`delete`
single calls (including multi-byte UTF-8 and both Kotoba byte-bound traps,
reusing the unmodified `asymmetric-request-validation-chain`). Because
`wasmtime run --invoke` instantiates a composed component fresh on every
process invocation, proving REAL cross-call persistence needed a
different evidence shape than any prior ADR: a small, hand-written,
test-only "driver" application component
(`test/kotoba/compiler/component_composition_test.clj`'s own
`state-driver-wat`) issues 14 sequential calls to the real provider from
within ONE exported function and ONE Wasmtime invocation, folding a
pass/fail bit per step into a returned bitmask -- real execution returned
`16383` (all 14 checks passed: get-before-put missing, put/get/put proving
persistence and a real incrementing version counter, cross-key isolation,
delete and re-delete of an absent key, filling the 4-slot table, a NEW key
rejected with `error{code: "state/capacity"}` once full, and an EXISTING
key still succeeding once full), with a deliberate negative control
(one expected value corrupted) returning `16379` (exactly the corrupted
step's own bit cleared), confirming the harness genuinely discriminates
failure. `state-provider-wat`'s own admission (`state-provider-shape`) is
deliberately narrow to `state-v1`'s own literal case tags/field names/
types/order, not a generic "real provider for any asymmetric variant
crossing" -- a structurally-close-but-not-identical shape (ADR 0058's own
all-`:i64` `demo/state-request`/`demo/state-result`) is rejected before
any WAT is even generated. Full 256-entry capacity, native-AOT, JIT,
production/security hardening of this provider, every other capability's
real provider semantics, and a compiled Kotoba application's own ability to
issue a multi-step capability sequence (the driver's own multi-call shape
is test-only, not reachable through the standard KIR/`typed-cap-call`
admission pipeline) all remain closed; no capability kit's `:qualification`
changed, and `resources/kotoba/lang/capability-kits/state-v1.edn` is not
modified.

## Official sources

- https://github.com/WebAssembly/component-model
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/WIT.md
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/CanonicalABI.md
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/Binary.md
- https://github.com/WebAssembly/WASI/releases/tag/v0.2.11
- https://github.com/WebAssembly/WASI/releases/tag/v0.3.0
- https://wasi.dev/releases/wasi-p3
- https://wasi.dev/roadmap
