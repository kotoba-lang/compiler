# ADR 0049: Component application-language gap ledger

Status: accepted; living ledger, current snapshot updated 2026-07-24

## Context

ADRs 0037 through 0048 established the Component Model/WASI baseline and
implemented progressively wider Canonical ABI slices. Passing a WIT parser,
linker, or identity round-trip is not sufficient evidence that Kotoba is a
qualified safe application language. This ADR records precisely what remains
so later work does not mistake infrastructure progress for provider-semantic or
runtime qualification.

## Closed in this session series

- deterministic WIT worlds derived from checked KIR;
- standard32 Component binaries for scalar exports, bounded strings, and
  sealed nominal records containing scalar fields;
- record construction, one-field update, and scalar field projection slices;
- direct scalar `typed-cap-call` application imports;
- direct same-type sealed scalar-record request/result capability calls;
- compiler-owned scalar and sealed-record identity provider artifacts;
- exact import/provider multiset checking, pinned `wac plug` composition, and
  post-composition `wasm-tools validate`;
- an implementation-only Wasmtime 42 record round-trip.

## Remaining language and Canonical ABI gaps

1. Nested structured values: strings inside records, nested records, lists,
   tuples/vectors, maps, sets, options, results, and variants need recursive
   flatten/lift/lower/store/load plans plus pre-call and post-return validation.
2. Recursive schema identity: Component v1 still rejects general recursive
   Kotoba schemas. A bounded representation or explicit resource/handle design
   must preserve schema digest identity; assigning a WIT name is insufficient.
3. Capability expressions: computed requests, different request/result record
   identities, multiple calls, multiple exported functions, and calls inside
   admitted control flow do not yet have qualified Component lowering.
4. Canonical resource management: nested allocations, ownership transfer,
   post-return cleanup, overflow checks, aggregate byte/node/item budgets, and
   malformed provider-result rejection need executable adversarial vectors.
5. WASI 0.3 async: `async func`, futures, streams, cancellation, deadlines, and
   item/byte budgets remain specification-only and fail-closed.

## Remaining provider and authority gaps

The current providers are identity wiring fixtures, not implementations of the
nine application capabilities. HTTP, log read/append, clock, state, UI commit
and event delivery, LLM generation, and storage still require production
provider Components with bounded typed request/results and the shared semantic
vectors. Each provider must import only the WASI interfaces declared by its
kit. Storage must not acquire ambient filesystem authority, and LLM must not
acquire ambient sockets.

## Remaining runtime qualification gaps

- Wasmtime: run the full provider-conformance manifest and adversarial
  Canonical ABI vectors on pinned Wasmtime major 43 or newer. The local 42.0.1
  round-trip is not qualifying evidence.
- Native: implement the typed provider syscall ABI and structured
  request/result host codec, then run the same provider manifest and boundary
  vectors. Native must not define different capability semantics.
- Release/CI: provision and attest pinned `wasm-tools`, `wac-cli`, and Wasmtime
  artifacts across supported architectures; composition success alone does not
  establish semantic equivalence.

The CLJS backend remains the only backend with a completed shared provider
manifest gate. Wasmtime and native therefore remain `pending`.

## Next completion order

1. Recursive structured Canonical codecs and validators.
2. Production provider Components for all nine capabilities.
3. Wasmtime 43+ semantic/adversarial qualification.
4. Native syscall/codec implementation and the same qualification corpus.
5. Bounded WASI 0.3 async profile.

## Progress addendum (this session's own ledger is not rewritten in place; see ADR 0064)

Per this repo's own established practice for this file (every ADR from 0050
onward that closed part of what this ledger names has cited this ledger
rather than editing its Decision/gap prose in place — confirmed by this
file's own one-commit history), this addendum only records a pointer, not a
rewrite:

- **LLM (`:llm/generate`) now has a real, live-verified `:clj` production
  provider transport** — `kotoba.compiler.provider.llm-transport` (ADR
  0064) — wired to the repo-wide `murakumo-main` fleet alias, closing one
  entry of item 2's "identity wiring fixtures, not implementations" gap
  for the nine application capabilities named in this ledger's own
  "Remaining provider and authority gaps" section above. The other eight
  capabilities (HTTP, log, clock, state, UI, storage, and the remainder)
  are UNCHANGED by this addendum and remain identity wiring fixtures at
  the `:clj` reference-provider layer discussed in this ledger — ADR
  0064 does not claim otherwise. `:cljs`/nbb transport for LLM itself also
  remains an explicit, documented gap (ADR 0064's own "Remaining gaps").
  This progress is at the JVM/Chicory reference-provider layer this ledger
  itself discusses, not yet the WASM Component Model layer ADR chain
  0037-0063 builds toward — see ADR 0064 for the precise scope.
- **Item 1 ("Nested structured values ... need recursive flatten/lift/
  lower/store/load plans") now has a `[:list item-descriptor]` Canonical ABI
  layout plan** — `kotoba.compiler.canonical-abi`'s `list-layout` (ADR
  0065) — closing one narrow slice of this ledger's own item 1, at the
  layout-plan level the item's own wording asks for ("plans", not codegen).
  `layout*` now admits three aggregate schema shapes (`:record`/`:variant`/
  `:list`); the list item type may recursively be any existing scalar/
  string/keyword/symbol/record/variant, but a list-of-lists is explicitly
  rejected and remains closed. Tuples/vectors, maps, sets, options, and
  results named in this same ledger item are UNCHANGED by ADR 0065 and
  remain entirely unimplemented in `layout*`. ADR 0065 adds no
  `component-core.clj` codegen for `list` (no `.kotoba` export, capability
  call, or provider Component can take or return a list value yet) and no
  instance-level list-value validator — both are explicit remaining gaps
  in ADR 0065 itself, matching this ledger's own "plans... plus pre-call and
  post-return validation" phrasing only at the declarative-metadata level
  (`:max-items`/`:validation` tags), not as an executable check. Recursive
  schema identity itself (this ledger's own item 2) is unchanged and out of
  scope for ADR 0065.
- **HTTP (`:http/post`) now also has a real `:clj` production provider
  transport** — `kotoba.compiler.provider.http-transport` (ADR 0066) —
  backed by `java.net.http.HttpClient` with bounded, per-hop
  allow-list-revalidated redirect following and a best-effort private/
  loopback/link-local destination-IP block, closing a second entry of item
  2's "identity wiring fixtures, not implementations" gap for the nine
  application capabilities named in this ledger's own "Remaining provider
  and authority gaps" section above. The remaining seven capabilities (log,
  clock, state, UI, storage, and the rest) are UNCHANGED by this addendum
  and remain identity wiring fixtures at the `:clj` reference-provider layer
  this ledger discusses — ADR 0066 does not claim otherwise. `:cljs`/nbb
  transport for HTTP itself also remains an explicit, documented gap (ADR
  0066's own "Remaining gaps"), as does full DNS-rebinding closure (same
  section, item 1) — unlike LLM's fixed-endpoint design, HTTP's
  guest-chosen-destination design makes DNS-rebinding a live, honestly
  documented residual risk rather than a closed one. This progress is at
  the JVM/Chicory reference-provider layer this ledger itself discusses,
  not yet the WASM Component Model layer ADR chain 0037-0063 builds toward
  — see ADR 0066 for the precise scope.
- **Correction to this addendum's own prior wording, not a new capability
  closed** — ADR 0067: this ledger's own preceding bullets (added by ADR
  0064's and ADR 0066's PRs) say the capabilities other than LLM/HTTP
  "remain identity wiring fixtures at the `:clj` reference-provider layer."
  That is accurate for `storage` (and was accurate for `http` and `llm`
  before their own transports landed above). It overstates `state`:
  `kotoba.compiler.provider.state` (ADR 0024) has no `:transport` seam at
  all and has been a real, bounded, tested in-process key/value
  implementation since PR #154 (2026-07-20), read directly and confirmed by
  ADR 0067, which also confirms ADR 0060 and ADR 0061 already proved a real
  (non-wiring-only), full-256-entry-capacity WASM Component Model provider
  for `state-v1` through real Wasmtime execution — the "production provider
  Components for all nine capabilities" next-completion-order item is
  further along for `state` specifically than this ledger's own prior
  wording implied. `clock`, `log`, and `ui` share `state`'s same
  self-contained, no-transport-seam shape, so the same overstatement likely
  applies to them too, but ADR 0067 is scoped to `state` only and does not
  verify or correct those three. Native-AOT, JIT, and production-hardening
  review for `state`'s WASM Component Model provider remain open (ADR
  0060/0061's own named gaps, unchanged) — this correction is about the
  `:clj` reference-provider layer's status, not a claim that `state` is now
  fully qualified end to end.
- **Item 1's remaining `options`/`results` sub-slice now also has Canonical
  ABI layout plans** — `kotoba.compiler.canonical-abi`'s `option-layout`/
  `result-layout` (ADR 0068) — closing another narrow slice of this
  ledger's own item 1, at the same layout-plan level ADR 0065 closed for
  `list`. `layout*` now admits five aggregate/union schema shapes
  (`:record`/`:variant`/`:list`/`:option`/`:result`); `option`/`result` are
  structural (no schema-table identity), sugar over the same
  discriminant+payload-area math `variant-layout` already computes for a
  sealed variant, and their own item/ok/err type may recursively be any
  existing admitted descriptor including nested options/results/lists —
  unlike ADR 0065's deliberate list-of-list rejection, option-of-option and
  result-of-result are not rejected, since neither has list's
  second-independent-stride problem (see ADR 0068 for the reasoning).
  Tuples/vectors/maps/sets named in this same ledger item are UNCHANGED by
  ADR 0068 and remain entirely unimplemented in `layout*`. ADR 0068 adds no
  `component-core.clj` codegen (no `.kotoba` export, capability call, or
  provider Component can take or return an option/result value yet) and no
  instance-level option/result-value validator — both explicit remaining
  gaps in ADR 0068 itself, matching this ledger's own "plans... plus
  pre-call and post-return validation" phrasing only at the
  declarative-metadata level (a new `:bounded-discriminant` tag), not as an
  executable check. `layout-leaves` also gains no dedicated leaf shape for
  an option/result-typed record field, mirroring `variant`-typed record
  fields' own pre-existing, still-open gap (named explicitly in ADR 0068,
  not silently left implicit). Recursive schema identity itself (this
  ledger's own item 2) is unchanged and out of scope for ADR 0068.
- **Item 1's remaining "tuples/vectors" sub-slice is now resolved into
  exactly one still-open half, and that half now also has a Canonical ABI
  layout plan** — `kotoba.compiler.canonical-abi`'s `tuple-layout` (ADR
  0070) — at the same layout-plan level ADR 0065/0068 closed for
  `list`/`option`/`result`. Before writing any code, ADR 0070 first settled
  whether this ledger's slash-joined "tuples/vectors" phrase names one gap
  or two, by reading this codebase's own pre-existing, untouched
  `kotoba.compiler.value` (`[:vector item-types]`, a heterogeneous
  fixed-length domain-value shape) and `kotoba.compiler.component-wit`
  (which already renders that exact shape as WIT `tuple<...>`, and
  separately renders `vector-i64`/`vector-f64` as WIT `list<s64>`/`list<f64>`)
  — concluding that "vector" (homogeneous, variable-length) was already
  fully closed by ADR 0065's `:list`, and only "tuple" (fixed-length,
  possibly-heterogeneous product) remained open. `layout*` now admits seven
  aggregate/union schema shapes (`:record`/`:variant`/`:list`/`:option`/
  `:result`/`:tuple`); `tuple` is structural (no schema-table identity),
  sugar over the same sequential offset/alignment fold `record-layout`
  already computes for a sealed record's fields (via a new shared
  `structural-product-layout` helper, the `record`/`tuple` counterpart to
  ADR 0068's own `structural-union-layout` for `variant`/`option`/`result`),
  and its own item types may recursively be any existing admitted descriptor
  including nested tuples/lists/options/results — tuple-of-tuple is not
  rejected, for the same reason ADR 0068 already gave for option-of-option/
  result-of-result. Deliberately, `structural-product-layout` reuses the key
  name `:fields` for its own positional entries, so a tuple-typed record
  field is recursed into by `layout-leaves`'s pre-existing nested-record
  clause with **zero changes to `layout-leaves`** — the one respect in which
  `tuple` gets a more complete `layout-leaves` treatment today than
  `variant`/`option`/`result`-typed record fields do (that pre-existing gap,
  named in ADR 0068, is unchanged and not touched by ADR 0070). Maps and sets
  named in this same ledger item are UNCHANGED by ADR 0070 and remain
  entirely unimplemented in `layout*`. ADR 0070 adds no `component-core.clj`
  codegen (no `.kotoba` export, capability call, or provider Component can
  take or return a tuple value yet) and no instance-level tuple-value
  validator — both explicit remaining gaps in ADR 0070 itself. Recursive
  schema identity itself (this ledger's own item 2) is unchanged and out of
  scope for ADR 0070.

## Current remaining-gap snapshot (2026-07-24)

This section is the current operational summary. The original 2026-07-21
ledger and chronological addenda above remain as historical evidence, but
their present-tense statements must not be read as overriding this snapshot.
In particular, a type-level layout plan is not executable Canonical ABI
support, and an intentionally closed authority boundary is not an
implementation gap.

### P0: correctness bugs in already-admitted programs

1. **Five-parameter `:f64` Wasm functions can emit an invalid local index.**
   Four parameters work; five are admitted but the generated module can fail
   validation. This is tracked by compiler issue #206 and is a correctness
   bug, not an unsupported-language decision.
2. **Project linking cannot synthesize an import stub for `:f64`/`:f32`
   results.** Standalone floating functions work, but a cross-file call can
   fail in `kotoba.compiler.project/stub-value`. This is the second open item
   in issue #206.

The JavaScript self-shadowing `let` bug formerly tracked as issue #225 is
closed by compiler PR #250 and kotoba-script PR #68. Lexical parameters,
nested `let` bindings, and match binders now receive deterministic unique
JavaScript names, so this is no longer a remaining gap.

### P1: planned layouts that still lack executable Component codegen

The Canonical ABI layout planner now covers:

- scalar/string/keyword/symbol;
- sealed record and variant;
- bounded structural `list<T>` (ADR 0065);
- structural `option<T>` and `result<T,E>` (ADR 0068);
- structural tuple (ADR 0070).

For `list`, `option`, `result`, and tuple, this is still **type-level planning
only**. `component-core.clj` has no general lowering/lifting/store/load path
for these shapes, no `.kotoba` export or typed capability call can use them
end to end, and the planner's validation tags are not executable instance
checks. The next coherent implementation slice is:

1. runtime value validation and bounded allocation;
2. export parameter/result lowering and lifting;
3. typed capability request/result lowering and lifting;
4. malformed-provider-result and overflow adversarial tests;
5. real Wasmtime execution evidence.

This is one codegen gap per shape, not evidence that their layouts remain
unimplemented.

### P1: Canonical ABI shapes without a layout plan

- **Map and set** remain absent from `canonical-abi.cljc/layout*`.
- **List-of-list** remains explicitly rejected by ADR 0065. Supporting it
  requires independently bounded nested buffers and checked stride/range
  arithmetic.
- **Payload-less WIT result forms** remain outside the current
  `[:result ok-type err-type]` model.
- `layout-leaves` still lacks a dedicated representation for
  variant/option/result-typed record fields.

There is no separate homogeneous-vector gap: ADR 0070 records that Canonical
ABI `list<T>` is that shape. The heterogeneous fixed-length domain vector is
the structural tuple shape.

### P1: backend parity still deliberately narrow

- Native parametric option/result values are implemented internally by ADR
  0069, including nested one-word values, but are not admitted as generic
  structured exported parameters/results. A structured host codec remains a
  separate boundary.
- Native record/variant support remains the sealed bounded slices documented
  by ADRs 0062/0063, not general escaping recursive aggregates.
- The ClojureScript backend still traps outside JavaScript's exact safe
  integer range rather than implementing modulo-2^64 arithmetic with BigInt
  end to end. This is an explicit fail-closed parity limitation, not silent
  miscompilation.

### P2: recursive identity, ownership, and async

- General recursive schema identity remains rejected. A future bounded
  representation or resource/handle design must preserve schema identity.
- Canonical ownership transfer, nested post-return cleanup, aggregate
  byte/node/item accounting, and adversarial malformed-result coverage remain
  incomplete for newly planned aggregates.
- WASI 0.3 async functions, futures, streams, cancellation, deadlines, and
  their fuel/item/byte budgets remain specification-only and fail closed.

### Provider and authority status

- `:llm/generate` and `:http/post` have real JVM transports (ADRs 0064/0066);
  their CLJS/nbb transports remain unimplemented.
- `state` is a real bounded reference implementation and has real Wasmtime
  Component evidence (ADRs 0060/0061/0067); it is not an identity fixture.
  Native-AOT/JIT qualification and production hardening remain open.
- Storage remains the clearest production-transport gap and must not gain
  ambient filesystem authority.
- Clock, log, and UI must be assessed against their actual self-contained
  implementations and conformance evidence rather than grouped
  automatically with transport-backed HTTP/LLM/storage. This ledger does not
  claim production qualification merely from a reference implementation.
- All capability kits still need one shared cross-runtime semantic and
  adversarial corpus with release evidence; per-provider implementation does
  not by itself close runtime parity.

### Explicit non-gaps

The following remain intentionally excluded by the safe-language and
authority design and must not be scheduled as ordinary missing coverage:
ambient Java/JavaScript interop, `eval`/dynamic loading, unrestricted
mutation or threads, unrestricted exceptions, and un-fuelled looping. Any
proposal to admit one of these requires a new safety decision, not a desugar
or backend-parity patch.

## Updated completion order

1. Fix both issue #206 floating-point correctness bugs and add real Wasm/
   cross-file regression tests.
2. Implement executable Component lowering/lifting for one already-planned
   aggregate, starting with bounded `list<T>`; validate every pointer, length,
   stride, discriminant, and allocation before widening to the other shapes.
3. Add map/set layout plans only after their deterministic ordering,
   duplicate-key/item, and aggregate-budget rules are explicit.
4. Extend the same aggregate codec through typed capability boundaries and
   provider-result validation.
5. Close production provider/runtime qualification gaps with a shared
   Wasmtime/native/CLJS corpus.
6. Address recursive resources and bounded WASI 0.3 async as separate,
   explicitly reviewed authority-bearing designs.
