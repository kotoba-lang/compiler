# ADR 0056: Sealed-record variant case crosses `typed-cap-call`, resolving the ADR 0055 `wac plug` limitation

Status: accepted; scalar-or-record-case variant capability-call slice implemented

## Decision

A direct `typed-cap-call` may now use one sealed variant whose every case's
payload is a bare Canonical scalar (ADR 0055's original scope) *or* a sealed
all-scalar record (the ADR 0052 shape), as its same-identity request and
result. This closes the exact gap ADR 0055 recorded as blocked: a case
wrapping a record, crossing a capability boundary via `wac plug`.

**The blocker was a real, reproduced `wac` defect, not a Kotoba WIT/Canonical
ABI generation bug, and it is now fixed by upgrading the pinned tool.** ADR
0055's own text records `wac plug` (pinned 0.9.0) failing every
record-referencing-variant capability crossing it tried with `error: encoding
produced a component that failed validation / Caused by: type not valid to be
used as import`, reproduced across four independent variations, and left it
as a currently-blocked path with three named candidates for closing it later:
a newer `wac`/`wasm-tools` release, restructuring the shared `types`
interface, or filing the reproduction upstream.

This ADR investigated the first candidate and confirmed it, before touching
any Kotoba source:

1. **Independently reproduced ADR 0055's exact failure first**, without
   trusting the prior ADR's own account. A minimal fixture matching the
   task's own suggested shape (`[:variant :demo/outcome [[:found [:ref
   :demo/entry]] [:missing :bool]]]`, `entry` a two-field scalar-only record)
   was built through this namespace's own already-proven WIT/WAT generation
   (`variant-capability-wat`/`variant-capability-provider-wat`, called
   directly to bypass the then-unwidened admission gate), packaged into two
   independently valid components, and composed with the pinned `wac plug`
   0.9.0: it failed identically, `error: encoding produced a component that
   failed validation / Caused by: type not valid to be used as import (at
   offset 0xa4)`.
2. **Searched `wac`'s own release history and issue/PR tracker for a known
   fix matching this exact error, rather than guessing at a workaround.**
   `bytecodealliance/wac` PR #205, "Alias `use`'d types during composition
   instead of re-encoding them locally" (merged into v0.10.0, 2026-06):
   > Composing components fails validation with `type not valid to be used
   > as import` whenever a `use`'d type is involved: the encoder re-encodes
   > a type reachable only by id as a fresh local definition rather than
   > referencing the imported instance's export it denotes. A world-level
   > type import whose definition points at a local definition is not a
   > valid import.

   This is an exact match: ADR 0055's own diagnosis already identified the
   common factor across its four failed variations as "the shared `types`
   WIT interface exports two types where one (the variant) references the
   other (the record) *and* that interface crosses a capability
   import/export boundary composed by `wac plug`" -- precisely the `use`'d
   type re-encoding defect PR #205 fixes.
3. **Confirmed the fix locally before committing to it**, without touching
   the machine's shared global `wac` install destructively: downloaded the
   v0.10.1 release binary to a scratch path first, re-ran the identical
   failing pair of components from step 1 through it -- `wac plug` now
   succeeded, `wasm-tools validate --features component-model` on the
   composed output passed, and real Wasmtime 42.0.1 execution
   (`wasmtime run --invoke`) round-tripped `found({key: ..., value: ...})`
   across the full `i64` range and both `missing(true)`/`missing(false)`
   cases through the composed (not merely single-module) component -- before
   any Kotoba source in this repository changed. Only after this independent
   confirmation was the machine's shared `~/.cargo/bin/wac` reinstalled via
   the same `cargo install --locked --version 0.10.1 wac-cli` path CI itself
   uses (not the scratch release binary), and the repository's own pin
   (`kotoba.compiler.component-composition/wac-version`,
   `resources/kotoba/lang/component-model-v1.edn`'s
   `:spec-baseline :wasi :toolchain :wac-cli`, and
   `.github/workflows/test.yml`'s install step) bumped from `0.9.0` to
   `0.10.1` (0.10.0 plus bytecodealliance/wac#207, a release-process-only
   patch with no further behavior change relevant here). Before this
   reinstall, the concurrent-work check this task required
   (`gh pr list`/`git branch -r`) confirmed no other open PR or active
   branch on this repository touches Component Model / `wac`, and `wac` is
   invoked nowhere else in this codebase, so the narrow, deliberate global
   binary bump was judged to have no blast radius against other concurrent
   work on this machine.
4. The second and third candidates ADR 0055 named (restructuring the shared
   `types` interface; filing upstream) were not needed once the first
   candidate was confirmed, and were not attempted.

The second thing this ADR seriously checked, per the task's own instruction
not to dismiss the possibility without looking: whether the failure was
actually a bug in Kotoba's own WIT/Canonical ABI generation
(`kotoba.compiler.component-wit`/`canonical-abi`) rather than external
tooling. It is not. ADR 0054 already established that
`component-wit.clj` needs zero changes to render a variant case wrapping a
record correctly (the `interface types { record ... variant ... }` block
ADR 0055/0056 both rely on was already exactly right), and this ADR's own
WAT-generation review confirms `variant-capability-wat`/
`variant-capability-provider-wat` are already case-kind agnostic --
they operate purely on `canonical/layout`'s already-flattened `:flat` core
value list and reuse `variant-case-chain` unmodified, neither of which
inspects whether a case's payload was a scalar or a record. Zero lines of
WAT-emission code changed in this ADR. The failure was entirely external:
`wac`'s own plug-time type re-encoding.

## Scope

`kotoba.compiler.component-core` renames ADR 0055's `scalar-only-variant-case?`/
`scalar-variant-capability-schema` to `variant-capability-case?`/
`variant-capability-schema` and widens the predicate to admit
`sealed-scalar-record` (ADR 0052's shape) in addition to a bare scalar.
`kotoba.compiler.component-composition` widens `variant-wit` (the
provider-side WIT generator) to declare each case's referenced record type(s)
inside the same `interface types {...}` block as the variant, mirroring
`record-wit`'s own record-declaration style, instead of the single-type-only
footprint ADR 0055 scoped down to once the `wac` fix removed the reason for
that narrowing. No WAT-emission code changed, in either namespace.

**Still deliberately narrower than the identity-export path.** ADR 0054's
`record-or-scalar-variant-case?` additionally admits a sealed
string/keyword-bearing record case (the ADR 0053 shape) for an
*identity-export* variant. This ADR does **not** widen the capability-call
path that far: string/keyword data crossing a capability-call boundary at
all is a separate, unattempted gap that predates and is independent of the
`wac plug` limitation this ADR closes -- no case kind has ever exercised it,
not even a plain record `typed-cap-call` (ADR 0048, which has only ever
crossed all-scalar records). Fixing `wac plug`'s type-aliasing defect says
nothing about whether Kotoba's own hand-written Canonical ABI codegen
correctly copies string bytes between two separate component instances'
linear memories (as opposed to within one module, which ADR 0041/0042
already prove) -- that is real, unverified work this ADR does not attempt.
A case wrapping a string/keyword-bearing record remains fail-closed at both
the KIR-admission layer (`component-core/emit`) and the provider layer
(`component-composition/package-variant-identity-provider`), verified
directly by test.

**`state-v1`'s actual request/result types are still not closed by this
ADR.** `state-v1`'s real `entry` record is `key: keyword, value: string,
version: i64` -- a string/keyword-bearing record, exactly the case kind this
ADR leaves closed. `demo/state-outcome`, this ADR's own concrete evidence
fixture, uses a structural stand-in `entry` (`key: i64, value: i64`,
scalar-only) deliberately narrower than `state-v1`'s real shape, matching the
task's own suggested minimal target and ADR 0055's own naming convention.
Closing `state-v1` for real still needs, in order: (1) string/keyword data
crossing a capability-call boundary at all (unattempted, as above), and (2) a
real production `state` provider (every provider in this ADR chain, like
every prior one, is a wiring-only identity fixture --
`src/kotoba/compiler/provider/state.cljc`'s bounded 256-entry reference
semantics remain entirely unwired to any Component-level capability call).

`resources/kotoba/lang/capability-kits/*.edn` is untouched by this ADR. None
of the seven capability kits' `:wasm-aot`/`:native-aot`/`:jit` qualification
changes.

## Evidence

- Independent reproduction of ADR 0055's own failing shape against the
  then-pinned `wac plug` 0.9.0: identical failure,
  `error: encoding produced a component that failed validation / Caused by:
  type not valid to be used as import`, confirmed *before* any source in
  this repository changed.
- The identical fixture, unchanged, composed successfully against `wac`
  0.10.1 (downloaded to a scratch path, not yet the machine's installed
  binary at that point): `wac plug` succeeded, `wasm-tools validate
  --features component-model` passed, and real Wasmtime 42.0.1 execution
  round-tripped `found({key: -9223372036854775808, value:
  9223372036854775807})` (full `i64` range), `found({key: 0, value: 0})`,
  `missing(true)`, and `missing(false)` through the composed application+
  provider component -- this was the confirmation step before the pin
  bumped.
- After widening `component-core`/`component-composition` and bumping the
  pin (`wac-version`, `resources/kotoba/lang/component-model-v1.edn`,
  `.github/workflows/test.yml`): the *actual, non-monkeypatched* code path
  (`kotoba.compiler.component-wit/emit` ->
  `kotoba.compiler.component-core/emit` ->
  `kotoba.compiler.component-artifact/package` for the application,
  `kotoba.compiler.component-composition/package-variant-identity-provider`
  for the provider, `kotoba.compiler.component-composition/compose-closed`
  for `wac plug` + `wasm-tools validate`) reproduces the same successful
  composition and the same four Wasmtime round trips on
  `demo/state-outcome` (`found: entry` where `entry` is `key: i64, value:
  i64`, `missing: bool` -- a structural slice of `state-v1`'s own
  `found`/`missing` shape, narrower only in `entry`'s field types, exactly
  matching this ADR's own "Scope" section).
- focused suite (`canonical-abi-test`, `component-artifact-test`,
  `component-composition-test`, `component-wit-test`,
  `backend-qualification-test`): 41 tests, 203 assertions, 0 failures, run
  against `wasm-tools 1.243.0` and `wac-cli 0.10.1`.
- full `clojure -M:test` suite: 421 tests, 4445 assertions, 0 failures.
- Fail-closed boundaries re-verified directly by test after the widening: a
  variant case wrapping a sealed *string/keyword-bearing* record used as a
  capability request/result is rejected by `component-core/emit`
  ("component function body has no qualified Canonical lowering") *and*
  independently rejected by
  `component-composition/package-variant-identity-provider` ("provider
  variant requires scalar or sealed all-scalar record cases") -- both layers
  still fail closed, matching ADR 0055's own two-layer discipline, now
  narrowed to exactly the case kind this ADR does not admit rather than
  every record case kind. Different request/result variant identities, a
  computed capability request, and an unknown capability id all remain
  fail-closed exactly as ADR 0055 already verified (unchanged code paths,
  re-run by the existing tests).

## Toolchain change and its blast radius

`wac-cli` pin: `0.9.0` -> `0.10.1`. This is a narrow, verified bump made
because it directly and reproducibly closes a defect this codebase's own
test suite hit (not a routine or blind toolchain refresh):

- Read `wac`'s own release notes for both intervening versions
  (`bytecodealliance/wac` v0.10.0 and v0.10.1) rather than jumping to the
  latest tag blindly. v0.10.0's changelog contains PR #205, the exact fix
  for the exact reproduced error; also several unrelated fixes (subtype
  checks, panic-to-error conversions, semver-compatible interface
  composition) that do not touch anything this codebase's `wac plug`
  invocation shape depends on. v0.10.1 is a release-process-only bump on top
  (`bytecodealliance/wac#204`/`#206`/`#207`), no further behavior change.
- Confirmed the fix against this codebase's own exact failing shape (not
  merely trusting the changelog's description) before bumping the pin, per
  the above.
- Checked for blast radius before touching the machine's shared global
  `wac` binary: this task's own required concurrent-work check
  (`gh pr list --repo kotoba-lang/compiler --state open`,
  `git branch -r --sort=-committerdate`) found no other open PR or active
  branch touching Component Model / `wac` / `canonical-abi` /
  `typed-cap-call`; `wac` is invoked nowhere in this codebase outside
  `kotoba.compiler.component-composition`. CI installs its own fresh,
  ephemeral `wac-cli` per run (`.github/workflows/test.yml`'s
  `cargo install --locked --version <pin> wac-cli` step) rather than relying
  on a pre-existing machine image, so the CI-side change has zero blast
  radius on other repositories or other CI runs by construction; only the
  local development machine's single shared `~/.cargo/bin/wac` binary is a
  genuinely shared resource, and it was reinstalled only after the above
  checks.

## Remaining gaps

String/keyword data crossing a capability-call boundary at all (not merely
inside a variant case -- no case kind, and no plain `record-capability-call`
either, has ever exercised this) is unattempted and is the concrete blocker
to closing `state-v1`'s real `entry`/`error` shape. A real production `state`
provider (as opposed to every provider in this ADR chain's wiring-only
identity semantics) is unattempted. Different request/result variant
identities, computed capability requests inside admitted control flow,
multiple capability calls, and multiple exported functions all remain
fail-closed exactly as ADR 0049/0055 already recorded. Every production
provider's real semantics for all nine capabilities remain closed. No
capability kit's `:wasm-aot` qualification changes as a result of this ADR.
