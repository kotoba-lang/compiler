# ADR 0072: `log-v1`/`ui-v1`'s `:clj` reference providers have no transport seam to close — the second half of ADR 0067's own named follow-up

Status: accepted; documentation-only correction, zero production code changed

## Decision

ADR 0067 corrected ADR 0049's ledger addendum for `state`: `state`'s
reference provider has no `:transport` seam at all, so it was never an
"identity wiring fixture" the way `http`/`storage` (and `llm`, before ADR
0064) were, and ADR 0067 said so explicitly — while also naming `clock`,
`log`, and `ui` as sharing that same self-contained shape "by the same
reasoning" and marking that as **out of ADR 0067's own scope to verify**.
This task is dispatched to do that verification for the two of those three
this task was asked to cover: `log` and `ui`. Reading the actual code (not
ADR 0067's own by-analogy claim, and not this task's own background note)
confirms the same finding for both, independently, following ADR 0067's own
method: **neither `log` nor `ui` has a transport to replace. Both have been
real, bounded, tested, self-contained in-process implementations since
before this ADR chain's "identity wiring fixture" language was written**
(`log`: ADR 0030, accepted status "CLJ/CLJS reference provider implemented";
`ui`: ADR 0025, same accepted status).

This ADR closes the task by recording that finding precisely for `log` and
`ui` individually, not by adding code that would either (a) duplicate
already-merged work, or (b) widen either capability's authority in a way
ADR 0049 itself warns against.

### Why "identity wiring fixture" does not describe `log.cljc`

`kotoba.compiler.provider.log/create-provider`
(`src/kotoba/compiler/provider/log.cljc`) takes **no arguments at all** —
not a `:transport` function, not any other host-injected option. It directly
builds two closed-over `atom`s (`entries`, `sequence-number`) and returns a
`:providers` map of two capability entries (`:log/append` id 6, `:log/read`
id 5) whose `:invoke` functions implement the full semantics themselves:

- `append`'s `:invoke` validates the bounded keyword `level`/`event`, the
  bounded string `message`, and calls `validate-fields!`, which itself
  enforces `max-fields` (4), unique field keys, and per-field
  bounded-keyword/bounded-string checks — throwing real `ex-info`s
  (`"log field limit reached"`, `"log field keys must be unique"`), not
  returning a canned reply. It then assigns the sequence number from a real
  monotonic `swap! sequence-number inc` (the provider, not the guest, owns
  ordering — matching ADR 0030's own "provider assigns the sequence" design)
  and enforces `max-retained-entries` (256) by dropping the oldest entry via
  `subvec retained 1` when the bound is exceeded.
- `read`'s `:invoke` validates a non-negative cursor and a `limit` bounded to
  `max-read-entries` (8), then computes real `oldest`/`latest` sequence
  numbers from the live `entries` atom and a real `truncated` flag
  (`(< after-sequence (dec oldest))`) — this is exactly the "next read
  explicitly reports `truncated` together with oldest and latest sequence
  numbers" semantics ADR 0030 specifies, computed from live state, not
  hard-coded.

This is confirmed directly in `test/kotoba/compiler/log_provider_test.clj`:
`append-and-read-use-structured-bounded-values` performs a real
append-then-read round trip through the compiled/hosted runtime and checks
the returned sequence and entry; `field-and-read-limits-fail-before-mutation`
proves the field-count and read-limit bounds throw and that a rejected
append leaves `(:snapshot kit)` empty (no partial mutation); and
`retained-window-signals-truncation` appends `(inc max-retained-entries)`
(257) real entries and checks the resulting `oldest`/`latest`/`truncated`
values (`2`/`257`/`true`) — a real 257-step sequence proving the retention
window, not a stub returning a fixed literal.

### Why "identity wiring fixture" does not describe `ui.cljc`

`kotoba.compiler.provider.ui/create-provider`
(`src/kotoba/compiler/provider/ui.cljc`) likewise takes **no arguments** —
no `:transport`, no host DOM handle, nothing. It closes over three atoms
(`view`, `events`, `event-revision`) and returns a `:providers` map of two
capability entries (`:ui/commit` id 9, `:ui/next-event` id 10) plus two
host-only functions (`enqueue!`, `snapshot`) that are explicitly **not**
part of the guest-installable `:providers` map:

- `commit`'s `:invoke` enforces optimistic concurrency for real: it compares
  the request's `base-revision` against the live `(:revision @view)` and
  throws `"UI revision conflict"` on mismatch (not a no-op or a canned
  success) — matching ADR 0025's "commits use a base revision and fail on
  stale state." It enforces `max-nodes` (32), checks node-id uniqueness via
  a real `(count ids) (count id-set)` comparison, and validates every
  node's optional `parent` actually exists in the same commit's `id-set`
  before accepting it, throwing `"UI node parent is missing"` otherwise —
  ADR 0025's "node IDs and referenced parents must be valid within the same
  commit" enforced with a real membership check, not assumed. Only after
  all of this does it `reset! view` to the new revision and node set.
- `next-event`'s `:invoke` performs a real bounded pull: it drops already-
  delivered events by revision, returns the first not-yet-delivered event
  wrapped as `[event-result-type true event]` and removes it from the
  `events` atom (matching ADR 0025's own "bounded pull queue"), or returns
  `[event-result-type false]` (the `:option` "none" case) when nothing new
  is queued — a real state transition on each successful pull, not a
  stateless echo.
- `enqueue!` (host-only, not guest-reachable) enforces `max-events` (64) by
  throwing `"UI event queue limit reached"` once the live `events` atom
  is at capacity, and assigns a real monotonic `event-revision`.

This is confirmed directly in `test/kotoba/compiler/ui_provider_test.clj`:
`declarative-view-and-events-cross-only-typed-boundaries` commits a real
node, checks `(:snapshot kit)` reflects it, enqueues a real event via the
host-only `enqueue!`, pulls it once (getting the event), then pulls again
from the now-advanced revision (getting `false`, i.e. no new event) — a real
multi-step state machine, not a stub; and `stale-view-revisions-fail-closed`
proves a commit with a wrong `base-revision` (1, when the live state is
still at 0) throws `"revision conflict"` rather than silently succeeding or
ignoring the mismatch.

### The conformance-suite fixture list is the same distinguishing evidence ADR 0067 already used, now confirmed for `log`/`ui` specifically

`test/kotoba/compiler/provider_conformance_test.clj`'s shared `fixtures`
function constructs `llm`/`http`/`storage` each with an explicit
`:transport (fn [_] {:error {:code :*/disabled ...}})` (or the `:tag :error`
equivalent for `storage`) — a fixture standing in for a missing backend at
that exact seam. `log` and `ui` are constructed with **no such indirection**:

```clojure
(let [ui-kit (ui/create-provider)
      log-kit (log/create-provider)]
  [...
   {:name :ui/commit :id 9 :provider (get-in ui-kit [:providers 9])}
   {:name :ui/next-event :id 10 :provider (get-in ui-kit [:providers 10])}
   ...
   {:name :log/read :id 5 :provider (get-in log-kit [:providers 5])}
   {:name :log/append :id 6 :provider (get-in log-kit [:providers 6])}])
```

`(ui/create-provider)` and `(log/create-provider)` are called directly, with
no constructor option at all, and their real `:invoke` functions are used
in the conformance suite exactly as they would be by any host — there is no
fixture transport to swap out for a production one because none was ever
installed. This is the identical pattern ADR 0067 already found for
`(state/provider)` and `(clock/provider {:wall-now ... :monotonic-now ...})`
in the same fixture list (note: `clock/provider` does take arguments, but
they are deterministic time sources for reproducible tests, not a
`:transport` seam standing in for a missing backend — `clock` is out of
this ADR's scope to write up in full, but is visibly the same shape on
direct read).

### `:qualification {:reference :implemented ...}` does not by itself distinguish these cases — same caveat ADR 0067 already recorded

`resources/kotoba/lang/capability-kits/log-v1.edn` and `...ui-v1.edn` both
carry `:qualification {:reference :implemented :wasm-aot :pending
:native-aot :pending :jit :pending}` — the identical flag shape `llm-v1.edn`/
`http-v1.edn`/`storage-v1.edn` also carry (and carried before their own
`:clj` transports landed). This single flag does not distinguish "has a real
self-contained implementation" from "has a provider shape and bounds but
only a disabled fixture transport"; the distinguishing evidence is, as ADR
0067 already established, the presence or absence of a `:transport`
constructor key in each provider's own source, read directly (see above).

### Unlike `state`, `log`/`ui` do NOT yet have a further-along WASM Component Model layer — this ADR does not claim otherwise

ADR 0067 additionally found that `state`'s WASM Component Model provider
(`kotoba.compiler.component-core/state-provider-wat`, ADR 0060/0061) is a
real, non-wiring-only implementation, proved via actual Wasmtime execution.
Direct search of `src/kotoba/compiler/component_core.clj` for a
`log-provider-wat` or `ui-provider-wat` (the naming pattern
`state-provider-wat` establishes) finds **no such function** — only
`state-provider-wat` exists at that layer. `component_core.clj`'s own
comment for `field-by-name` (used by `state-provider-wat`) contrasts
`state`'s by-name field addressing against "the wiring-only providers"
(plural, referring to the rest of the component-core surface, of which
`log`/`ui` are still part) that only need positional order. `log-v1.edn`/
`ui-v1.edn`'s own `:wasm-aot :pending` is accurate and unchanged by this
ADR: the finding here is scoped strictly to the `:clj` reference-provider
layer, exactly as ADR 0067 scoped its own `state` finding. **This ADR does
not claim `log`/`ui` have real WASM Component Model providers — they do
not, as of this read.**

## Scope

**What changed:** nothing in `src/` or `test/`. This ADR file, and a
pointer-only addendum appended to `docs/adr/0049-component-application-
language-gap-ledger.md` (per that file's own established practice, restated
by ADR 0067 and ADR 0071's own additions to it: append a pointer, never
rewrite the Decision/gap/snapshot prose in place).

**What did not change:** `src/kotoba/compiler/provider/log.cljc`,
`src/kotoba/compiler/provider/ui.cljc`,
`test/kotoba/compiler/log_provider_test.clj`,
`test/kotoba/compiler/ui_provider_test.clj`,
`test/kotoba/compiler/provider_conformance_test.clj`,
`resources/kotoba/lang/capability-kits/{log,ui}-v1.edn` (`:qualification`
remains exactly `{:reference :implemented :wasm-aot :pending :native-aot
:pending :jit :pending}` for both, confirmed unchanged by direct read), and
every file touched by ADR 0060/0061/0067 (`state`) or any other in-flight
sibling task — this task never touched `log`/`ui`-adjacent files those
branches also do not touch, and touches none of their files either.

## Evidence

- Direct reads of `src/kotoba/compiler/provider/log.cljc`,
  `src/kotoba/compiler/provider/ui.cljc`,
  `test/kotoba/compiler/log_provider_test.clj`,
  `test/kotoba/compiler/ui_provider_test.clj`,
  `test/kotoba/compiler/provider_conformance_test.clj`,
  `resources/kotoba/lang/capability-kits/{log,ui,state,llm,http,storage}-v1.edn`,
  `docs/adr/{0025,0030,0060,0061,0064,0066,0067,0071}-*.md`,
  `docs/adr/0049-component-application-language-gap-ledger.md`, and
  `src/kotoba/compiler/component_core.clj` (searched for
  `log-provider-wat`/`ui-provider-wat`/`state-provider-wat`) in full — not
  summarized from memory or from this task's own background note.
- `gh pr list --repo kotoba-lang/compiler --state open` (run before writing
  any code, from a fresh worktree created off `origin/main`): the only open
  PR is `#196` ("rescue: uncommitted WIP found on detached-HEAD worktree"),
  unrelated to `log`/`ui`/capability providers. No in-flight PR claims this
  work or this ADR number at the time this branch was created.
- Both `log.cljc`'s `create-provider` and `ui.cljc`'s `create-provider` take
  zero arguments (confirmed by reading their full `defn` forms) — there is
  no `:transport`, `:allowed-*`, or other host-injection parameter shape at
  all, unlike `llm/provider`, `http/provider`, and `storage/provider`, which
  each require a construction map containing `:transport`.
- `provider_conformance_test.clj`'s `fixtures` function constructs
  `(ui/create-provider)` and `(log/create-provider)` directly with no
  arguments and installs their real `:invoke` functions unmodified, the same
  pattern ADR 0067 already confirmed for `(state/provider)` — contrasted
  directly against the same function's `llm`/`http`/`storage` entries, each
  of which passes an explicit disabled-error `:transport` fixture.
- Full `clojure -M:test` suite, run unmodified against this branch (which
  makes no production changes), reports the same pass count as an untouched
  `origin/main` HEAD, confirming this ADR's own claim that no code changed.

## Remaining gaps

This ADR closes no code gap, by its own finding that none exists for
`log`'s and `ui`'s `:clj` reference-provider layers specifically. What ADR
0025/0030 (and, at the WASM Component Model layer, the still-unstarted
`log`/`ui` counterparts to ADR 0060/0061) already name as open for these two
capabilities remains open and unchanged by this ADR:

1. **WASM Component Model providers for `log-v1`/`ui-v1` do not exist yet**
   (no `log-provider-wat`/`ui-provider-wat` in `component_core.clj`) —
   `log-v1.edn`/`ui-v1.edn`'s own `:wasm-aot :pending` is accurate. Unlike
   `state` (ADR 0060/0061), there is no already-merged real Wasmtime
   evidence for either capability at this layer; building it would be new
   work, not a correction.
2. Native-AOT and JIT backends for both capabilities remain entirely
   untouched (both `:native-aot :pending`, `:jit :pending` in their own
   capability-kit EDN).
3. Production sinks, timestamps, tracing context, redaction policies,
   durable audit export, subscriptions, and cross-instance queries for
   `log` (ADR 0030's own named exclusions) remain unimplemented — these
   would be later versioned kits, not v1 gaps.
4. Async subscription/cancellation, arbitrary attributes, HTML injection,
   CSS evaluation, and DOM handles for `ui` (ADR 0025's own named
   exclusions) remain unimplemented for the same reason.
5. `docs/adr/0049-component-application-language-gap-ledger.md`'s "Current
   remaining-gap snapshot" already says (before this ADR, in its own
   "Provider and authority status" section) that "Clock, log, and UI must
   be assessed against their actual self-contained implementations and
   conformance evidence rather than grouped automatically with
   transport-backed HTTP/LLM/storage" — this ADR is exactly that assessment
   for `log` and `ui`, confirming the snapshot's own caution was warranted
   and completing it for two of the three capabilities it named. `clock`
   remains out of this ADR's scope (visibly the same shape on direct read,
   per the note above, but not written up here with its own dedicated
   evidence section the way ADR 0067 did for `state` and this ADR does for
   `log`/`ui`).
6. `http` and `storage` continue to have real `:clj` production provider
   transports (ADR 0066/0071, unchanged by this ADR); `llm` likewise (ADR
   0064). This ADR does not touch any of the three.

## Related

- ADR 0025 (declarative UI capability kit) — the schema, bounds, and
  initial real reference implementation this ADR's `ui` finding is about;
  unmodified.
- ADR 0030 (bounded structured log capability kit) — the schema, bounds,
  and initial real reference implementation this ADR's `log` finding is
  about; unmodified.
- ADR 0067 (state provider no-transport gap correction) — the precedent
  this ADR follows directly, including its own explicit note that `clock`,
  `log`, and `ui` likely share `state`'s shape "by the same reasoning" but
  that verifying them was out of its scope; this ADR performs that
  verification for `log` and `ui`.
- ADR 0060 / ADR 0061 (state WASM Component Model provider) — cited here
  only to contrast: `log`/`ui` do not yet have an equivalent, unlike
  `state`.
- ADR 0064 / ADR 0066 / ADR 0071 (production LLM/HTTP/storage transports) —
  the pattern of replacing a real `:transport` fixture with a real backend,
  which does not apply to `log`/`ui` because neither has that seam.
- ADR 0049 (component/application-language gap ledger) — this ADR appends a
  pointer-only addendum there, per that file's own established practice,
  rather than rewriting its Decision/gap/snapshot prose in place.
