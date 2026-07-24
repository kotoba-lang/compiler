# ADR 0067: `state-v1`'s `:clj` reference provider has no identity-wiring-fixture gap to close — correcting ADR 0049's own addendum

Status: accepted; documentation-only correction, zero production code changed

## Decision

This task was dispatched to give `kotoba.compiler.provider.state` the same
kind of treatment ADR 0064 gave `:llm/generate`: replace an "identity wiring
fixture" transport with a real production backend, following ADR 0064's own
bounded-constraints-unweakened, fake-server-plus-env-gated-integration-test
pattern. Reading the actual code (not the ledger prose) before writing any
line of production code shows that premise does not hold for `state`: **there
is no transport to replace, and the reference provider has been a real,
bounded, tested implementation since PR #154 (2026-07-20), before this ADR
chain's "identity wiring fixture" language was even written.**

This ADR closes the task by recording that finding precisely, not by adding
code that would either (a) duplicate already-merged work, or (b) widen
`state`'s authority in a way ADR 0049 itself warns against.

### Why "identity wiring fixture" does not describe `state.cljc`

`kotoba.compiler.provider.llm/provider` and `kotoba.compiler.provider.
storage/provider` (and `http`'s provider, in the sibling in-flight PR #247)
are each constructed around a host-injected `:transport` function — a
`(fn [request] -> reply)` the provider itself never implements. Before ADR
0064, LLM's only `:transport` implementations anywhere in this repo were
fixtures that returned canned or `:disabled`-error replies
(`test/kotoba/compiler/provider_conformance_test.clj`'s shared `fixtures`
function still does exactly this for `llm`, `http`, and `storage` today —
each is constructed with a `:transport (fn [_] {:error {:code
:*/disabled ...}})`). ADR 0064 is precisely the story of adding the first
REAL implementation behind that indirection for one of those three
capabilities.

`kotoba.compiler.provider.state/provider` (ADR 0024, `src/kotoba/compiler/
provider/state.cljc`) has no `:transport` parameter and never has. Its
`provider` function directly implements `get`/`put`/`delete` against an
`atom`-backed map, with real bounded-keyword/bounded-string checks on every
key and value, a real per-instance monotonic version counter, and a real
256-entry capacity limit enforced by counting the live table — not a stub
that ignores its input or returns a literal constant. This is confirmed
directly in `test/kotoba/compiler/state_provider_test.clj`
(`state-provider-round-trips-versioned-values`,
`state-provider-instances-are-isolated`), and in the very same
`provider_conformance_test.clj` fixture list cited above: unlike `llm`/
`http`/`storage`, the `state` entry there is simply `{:name :state/transact
:id 8 :provider (state/provider)}` — the REAL provider, used directly, with
no fixture indirection to disable. `clock`, `log`, and `ui` are constructed
the same self-contained way in that same fixture list, for the same reason:
none of the four have an external system to reach, so none of the four have
a transport seam an "identity fixture" could occupy in the first place.

### The WASM Component Model layer for `state` is further along than the task's own background note assumed — and further than this ADR's job is to re-verify

Two ADRs already landed in this exact repo before this task started, on this
exact file's semantic spec:

- **ADR 0060** (`docs/adr/0060-real-bounded-state-provider.md`) built the
  first non-wiring-only WASM Component Model provider for `state-v1`
  (`kotoba.compiler.component-core/state-provider-wat`), proved via a real
  14-step stateful Wasmtime sequence (get/put/delete, cross-call persistence,
  cross-key isolation, capacity rejection, existing-key-still-writable
  semantics) — all checked byte-for-byte against `state.cljc`'s own behavior,
  not merely composed and validated.
- **ADR 0061** (`docs/adr/0061-state-provider-full-capacity.md`) grew that
  same WASM provider's table from ADR 0060's deliberate 4-slot proving size to
  the reference's real 256-entry capacity, proved via a real 262-step
  Wasmtime sequence (fill-to-capacity, over-capacity rejection, last-slot
  correctness, freed-slot reuse).

This task's own background note said a prior investigation found this
("state-v1 already proven ... 256-slot capacity") and reported it as
"corrected" by a separate investigation to "actually, all capabilities remain
identity wiring fixtures." Reading `docs/adr/0049-component-application-
language-gap-ledger.md` directly resolves the apparent contradiction: its
"Progress addendum" (added by ADR 0064's own PR #242) says LLM's ADR 0064
closes one gap "at the JVM/Chicory reference-provider layer... not yet the
WASM Component Model layer ADR chain 0037-0063 builds toward," and, in the
same breath, that the other eight capabilities including `state` "remain
identity wiring fixtures at the `:clj` reference-provider layer." That
sentence is true of `http`/`storage` (and was true of `llm` until ADR 0064)
because those three have a `:transport` seam that has only ever held a
fixture. It is not true of `state`: there never was a fixture standing in
for a missing backend at that layer, because `state.cljc` does not have that
kind of seam — its "backend" is the bounded, host-owned, in-process table
itself, which is real. The ledger addendum's "the other eight... remain
identity wiring fixtures" is accurate for the WASM Component Model layer
(ADR 0060/0061 are explicit that native-AOT, JIT, and production-hardening
review remain open there) but overstates the `:clj` reference-provider layer
specifically for `state` (and, on the same reasoning, for `clock`, `log`, and
`ui` — out of this ADR's scope to fix, named here only so a future reader
does not repeat this same investigation from the same imprecise sentence).

### Why this ADR does not add an in-memory-with-injectable-backend abstraction to `state.cljc` anyway

The task's own suggested design ("most simply and safely: an in-process
atom-backed map") is exactly what is already implemented. Retrofitting a
`:transport`-style indirection onto `state.cljc` purely for architectural
symmetry with `llm`/`http`/`storage` would not close any gap this repo's own
ADR 0049 names — it would only invite a future caller to plug in a durable
backend (disk, network, external KV) behind `state`, which is precisely the
authority `state-v1.edn`'s own declared semantics forbid: `:ambient-access
false`, `:isolation :per-provider-instance`. Durable, namespace-scoped,
version-conflict-aware persistence with a host-supplied transport already
exists as its own capability — `kotoba.compiler.provider.storage` (ADR 0024) —
and deliberately is not `state`. Blurring that boundary is the exact
confusion the task itself warned against avoiding ("state と storage の役割
分担を先に確認し、混同しない"), and ADR 0049's own remaining-gaps language
("Storage must not acquire ambient filesystem authority") reads as a
guardrail against exactly this kind of scope creep for the sibling
capability, not an invitation to reproduce it in `state`.

## Scope

**What changed:** nothing in `src/` or `test/`. This ADR file, and a
pointer-only addendum appended to `docs/adr/0049-component-application-
language-gap-ledger.md` (its own established practice for this file, per its
existing "Progress addendum" section: append a pointer, never rewrite the
Decision/gap prose in place).

**What did not change:** `src/kotoba/compiler/provider/state.cljc`,
`test/kotoba/compiler/state_provider_test.clj`,
`resources/kotoba/lang/capability-kits/state-v1.edn` (`:qualification`
remains exactly `{:reference :implemented :wasm-aot :pending :native-aot
:pending :jit :pending}`, confirmed unchanged by direct read), and every
file touched by ADR 0060/0061 or the two other in-flight sibling tasks
(`agent/http-capability-production-transport`,
`agent/canonical-abi-option-result-support`) — this task never touched
`state`-adjacent files those branches also do not touch, and touches none of
their files either.

## Evidence

- Direct reads of `src/kotoba/compiler/provider/state.cljc`,
  `test/kotoba/compiler/state_provider_test.clj`,
  `test/kotoba/compiler/provider_conformance_test.clj`,
  `src/kotoba/compiler/provider/storage.cljc`,
  `src/kotoba/compiler/provider/llm.cljc`, `src/kotoba/compiler/provider/
  clock.cljc`, `resources/kotoba/lang/capability-kits/{state,llm,http,
  storage}-v1.edn`, `docs/adr/{0060,0061,0064}-*.md`, and `docs/adr/0049-
  component-application-language-gap-ledger.md` in full — not summarized
  from memory or from this task's own background note.
- `gh pr list --repo kotoba-lang/compiler --search "state provider"` /
  `--search "state capability"` (run before writing any code): PR #219
  ("implement first real (non-identity) state provider through Canonical
  ABI", ADR 0060) and PR #226 ("grow state-v1 real provider to full
  256-entry capacity", ADR 0061) are both already MERGED to `main`
  (2026-07-23 06:06 and 09:06 respectively), before this task's own worktree
  was created from `origin/main`.
- `resources/kotoba/lang/capability-kits/state-v1.edn`'s `:qualification`
  map (`{:reference :implemented ...}`) was read directly and confirmed
  identical to `llm-v1.edn`, `http-v1.edn`, and `storage-v1.edn`'s own
  `:reference :implemented` entries — this single flag does not by itself
  distinguish "has a real self-contained implementation" (`state`, `clock`,
  `log`, `ui`) from "has a provider shape and bounds but only a disabled
  fixture transport" (`llm` before ADR 0064, `http`, `storage`); the
  distinguishing evidence is the presence or absence of a `:transport`
  constructor key in each provider's own source, read directly.
- Full `clojure -M:test` suite, run unmodified against this branch (which
  makes no production changes): 489 tests, 4644 assertions, 0 failures, 0
  errors — identical to what an untouched `origin/main` HEAD (`70c58e4`)
  reports, confirming this ADR's own claim that no code changed.

## Remaining gaps

This ADR closes no code gap, by its own finding that none exists for
`state`'s `:clj` reference-provider layer specifically. What ADR 0060/0061
themselves already named as still open for `state` remains open and
unchanged by this ADR:

1. Native-AOT and JIT backends for the WASM Component Model `state-v1`
   provider remain entirely untouched (ADR 0060 gap 2, ADR 0061 gap 1).
2. The WASM Component Model provider (`component-core.clj`'s
   `state-provider-wat`) is not reviewed for production/security hardening
   (ADR 0060 gap 3, ADR 0061 gap 2) — this is a distinct, larger memory
   footprint (~16.9MB per instance at full 256-entry capacity) than the
   `:clj` reference implementation's plain Clojure map, and that footprint
   has not itself been separately audited.
3. `component-composition.clj`'s `:ref`-only representational discipline for
   a variant case's record payload (ADR 0059's own gap, restated unchanged
   by ADR 0060/0061) is untouched.
4. The stateful/full-capacity Wasmtime drivers proving ADR 0060/0061 remain
   test-only constructs; the standard KIR/`typed-cap-call` admission pipeline
   still only admits a function body that IS a single `typed-cap-call`, so a
   real compiled Kotoba application cannot yet express a multi-step `:state/
   transact` sequence the way the test drivers do.
5. `docs/adr/0049-component-application-language-gap-ledger.md`'s own
   "remain identity wiring fixtures at the `:clj` reference-provider layer"
   sentence is, on this ADR's own reading, imprecise for `clock`, `log`, and
   `ui` too (the same self-contained, no-transport-seam shape as `state`) —
   named here for a future reader's benefit, but out of this task's scope to
   correct (this task is `state`-only; a similar correction for those three
   would need its own investigation, not assumed from `state`'s own case by
   analogy).
6. `http` and `storage` genuinely do still have only a disabled-fixture
   `:transport` at the `:clj` reference-provider layer (unchanged by this
   ADR) — PR #247 (`agent/http-capability-production-transport`, ADR 0066)
   is already open addressing `http`; `storage` remains open and untouched by
   any in-flight work this task found.

## Related

- ADR 0024 (bounded state capability kit) — the schema, bounds, and initial
  real reference implementation this ADR's finding is about; unmodified.
- ADR 0060 / ADR 0061 — the WASM Component Model real-provider work for
  `state-v1`, already merged, whose existence this task's own background note
  flagged as possibly superseded and which this ADR confirms is not.
- ADR 0064 (production LLM transport) — the pattern this task was asked to
  replicate for `state`, and the source of the ADR 0049 addendum sentence
  this ADR narrows for `state`'s own case.
- ADR 0049 (component/application-language gap ledger) — this ADR appends a
  pointer-only addendum there, per that file's own established practice,
  rather than rewriting its Decision/gap prose in place.
