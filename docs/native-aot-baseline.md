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

A sealed, all-scalar-cased (`:i64`/`:bool` payloads only) variant now has
the analogous executable, real-native-process-proven construction+dispatch
slice, on BOTH x86-64 and aarch64 (ADR 0063, the second native
value-representation increment). Like the record, a variant has no
independent runtime representation: `(variant-match type (variant-new type
tag payload) branches)` is rewritten at codegen time into TWO synthetic
8-byte stack slots (discriminant ordinal, payload) on the SAME
`emit-let`/`load-let` machinery -- but unlike the record's field
projection (a plain depth-relative load, no branching at all), dispatch is
a genuine runtime compare-and-branch chain over the stored discriminant (a
sequence of `cmp`/`je` on x86-64, `cmp`/`b.eq` on aarch64, one pair per
declared case, in order), falling through to a defensive `UD2`/`BRK` trap
if no case matches. This trap is provably unreachable from any program this
repository's own pipeline will ever admit, sign, or execute -- confirmed by
reading `signing.clj`: BOTH `sign` and `verify` (the latter invoked on
EVERY execution, not merely once at compile time) unconditionally re-run
the full `verifier/verify-artifact!`, on top of frontend's own unconditional
declared-tag check and this backend's own independently re-derived tag
lookup -- so proving the trap fires as real machine code required directly
exercising the dispatch-chain primitive with a hand-fed out-of-range
ordinal, bypassing the compile/sign/verify pipeline entirely (this is a
genuine, discovered defense-in-depth property of this repository's own
security architecture, not a workaround). A "tag-only" case needed no new
type-system concept: it still declares a real, uniformly-represented
`:i64`/`:bool` payload at construction, and is "tag-only" purely by the
convention that its dispatch branch body never reads that payload back. A
variant value never crosses a function boundary, and `variant-match`'s
value operand must be a directly-nested, same-schema `variant-new`,
mirroring the record's own restrictions exactly (which also means the
SPECIFIC case constructed at any one call site in this increment is always
statically known, even though the compare-and-branch machinery itself does
not special-case around that -- see the ADR's own Decision for the
distinction). Records/variants nested inside each other, string/keyword-
bearing cases, f64 payloads, a genuinely dynamic dispatch site, a genuine
zero-payload marker type, and case counts beyond a handful all remain
closed; there is still no native provider/capability mechanism of any
kind, and no capability kit's qualification is affected by ADR 0063.

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
at minimum, native records/variants with string/keyword fields, records
and variants nested inside each other, and a native provider/capability-
linking mechanism -- none of which exist yet, all separately gapped by
ADR 0062's and ADR 0063's own "Remaining gaps" sections.
