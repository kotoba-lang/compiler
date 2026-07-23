# Native AOT (x86-64/aarch64) value-representation baseline

This document tracks the native (`x86_64-kotoba-v1`/`aarch64-kotoba-v1`,
`backend/x86-64.cljc`/`backend/aarch64.cljc`) value-representation track,
separately from `docs/component-model-baseline.md` (which tracks the Wasm
Component Model track -- ADR 0036 through 0061 and onward). The two tracks
are genuinely independent: this repository's native backends emit raw
machine code directly and never invoke the Component Model machinery
(`component-core.clj`, `component-wit.clj`, `component-composition.clj`),
and as of this writing there is still no native provider/capability
mechanism at all. Conflating native-track progress into the Component
Model doc would misrepresent it; this doc exists so native-track ADRs have
their own honestly-scoped running summary instead.

## Baseline before this doc existed

Before ADR 0062, both native backends' entire value universe was a single
uniform 8-byte machine word transported in `rax`/`x0` (integers, booleans
represented as bare 0/1 without a dedicated boolean literal path, and a
`pair` heap-arena handle -- a fixed 2-word cell allocated through a host
call at a sealed context offset, used generically for cons-lists and, via
the same pair-is-`(offset,length)` convention, for strings). Neither
backend had any notion of a record or variant, nor any narrower structural
concept than the flat `{:string-literal content}` deferred-offset token the
literal-data segment uses (confirmed by direct code reading: zero matches
for "record"/"variant" in either file before ADR 0062).

## Running summary

A sealed, all-scalar (`:i64`/`:bool` fields only, no `:f64` -- see the
ADR's own Decision for why) record now has an executable, real-native-
process-proven construction+field-projection slice on BOTH x86-64 and
aarch64 (ADR 0062), the first native value-representation increment in
this repository. The record has no independent runtime representation at
all: `(record-get type (record-new type ...) field)` is rewritten at
codegen time into the same `emit-let`/`load-let` stack machinery an
ordinary multi-binding `let` already uses, one 8-byte-per-field synthetic
stack slot each, addressed by the same depth-relative arithmetic this
backend's own `let` already proves correct -- no new heap arena, no new
host ABI offset, no `tools/kexe_loader.c` change, no record value ever
crosses a function boundary (never a parameter or result type). As a
necessary prerequisite, literal `true`/`false` also now work as an
ordinary native scalar expression on both backends for the first time
(the only source of a genuine `:bool`-typed value in this frontend's type
system; every comparison, including `=`, always infers `:i64`), emitted as
the i64 word `1`/`0` through the same literal path an integer literal
already uses. `record-assoc`, `record-equal`, nested records,
string/keyword-bearing fields, f64 fields, any record crossing a function
or capability/provider boundary, and a `let`-bound record read by more
than one `record-get` call all remain closed; there is still no native
provider/capability mechanism of any kind, and no capability kit's
qualification is affected by ADR 0062.

## Relationship to the Wasm Component Model track

The Wasm Component Model track (`docs/component-model-baseline.md`)
already has considerably more value-representation surface -- sealed
scalar records (ADR 0043-0045), one level of record nesting (ADR 0051),
variants (ADR 0052), string/keyword-bearing records (ADR 0053), mixed
variants (ADR 0054), and records/variants crossing a real
`typed-cap-call` capability boundary against a real composed
application-plus-provider component, including a first genuinely
production-shaped provider for `state-v1` (ADR 0055-0061). None of that
is reused or re-implemented here: the two tracks share no code (the
native backends do not run Canonical ABI lowering, WIT generation, or
component composition at all), and this doc's own running summary should
not be read as claiming native parity with any of that progress. Native
AOT reaching that same level of capability-kit qualification would need,
at minimum, native records with string/keyword fields, native variants,
and a native provider/capability-linking mechanism -- none of which exist
yet, all separately gapped by ADR 0062's own "Remaining gaps" section.
