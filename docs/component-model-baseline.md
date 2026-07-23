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

## Official sources

- https://github.com/WebAssembly/component-model
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/WIT.md
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/CanonicalABI.md
- https://github.com/WebAssembly/component-model/blob/main/design/mvp/Binary.md
- https://github.com/WebAssembly/WASI/releases/tag/v0.2.11
- https://github.com/WebAssembly/WASI/releases/tag/v0.3.0
- https://wasi.dev/releases/wasi-p3
- https://wasi.dev/roadmap
