# ADR 0022: Structured application schema and capability ABI v9

Status: accepted; Phases 1–3 implemented, Phase 4 staged

## Context

Application migration needs nested structured values, nominal recursive schema
identity, and capability calls whose request and result are checked values.
Adding an unconstrained `[:ref ...]` or widening legacy i64 `cap-call` would
permit ambiguous schemas or move validation outside the trusted boundary.

The existing typed ABI already has nominal record/variant descriptors,
transitively trusted metadata, depth 8 / node 64 runtime budgets, and exact
boundary validation. ABI v9 extends that model; it does not admit host object
identity, reflection, or ambient mutation.

## Decision

### Phase 1 — honest and useful destructuring

Flat map destructuring admits `{:keys [...] :or {...} :as name}`. The source
value is evaluated once, defaults are evaluated only for absent keys, and
`:or` names must be a subset of `:keys`. The grammar catalog is marked
`:implemented-partial`; nested patterns are not claimed before typed lowering
exists.

### Phase 2 — closed recursive schema table

Modules declare at most 32 named schemas. A schema identity is the pair of its
qualified keyword and the SHA-256 digest of its canonical closed schema graph.
Descriptors may contain `[:ref :qualified/schema]` only when that name exists
in the same signed schema table. Validation rejects missing refs, duplicate
names, pure alias cycles, and graphs exceeding 32 schemas / 64 descriptor
nodes. Recursion must be productive through a value constructor such as
Option, Variant, Record, Vector, Set, or Map. Runtime values remain bounded by
the existing depth 8 / node 64 limits, so a recursive schema never authorizes
an unbounded value.

### Phase 3 — typed capability request/result

`typed-cap-call` names a registered capability and statically declares exact
request/result schema identities. Typed Wasm ABI v9 imports one
`kotoba:typed/cap-call` function `(i32, externref) -> externref`; its custom
metadata binds each used capability id to request/result descriptor indices
and schema digests. The host validates the request before provider dispatch and
the result before returning it to guest code. Denial, malformed provider data,
schema substitution, or an unregistered contract fails closed. Legacy i64
`cap-call` remains ABI-compatible and cannot silently satisfy a typed call.

Native backends reject typed capability calls until they implement and qualify
the same contract; there is no lossy pointer/i64 fallback.

The compiler reference executor and browser/Wasm ABI admit
`(typed-cap-call capability request-type result-type request)`. It checks the
request before calling a separately installed typed provider and checks the
provider result before returning it. An untyped `cap-call` provider cannot
satisfy this boundary. ABI v9 seals the closed schema table and each used
capability's request/result descriptor indices in module metadata. The browser
host resolves `[:ref ...]` only through that table and keeps its enforcement
copies private from diagnostic metadata exposed to callers.

### Phase 4 — type-directed nested destructuring

Nested vector/map/record patterns lower after type inference, not in the
untyped surface desugar pass. Each projection is selected from the inferred
descriptor (`hetero-vector-at`, `typed-map-get`, or `record-get`), preserving
the exact child type. The scrutinee and every default expression are evaluated
at most once. Dynamic keys, `:strs`, `:syms`, and schema-erasing projections
remain outside the profile.

## Exit gates

- canonical schema graph digest parity on JVM and nbb;
- forged/cross-schema values rejected on the real browser Wasm boundary;
- request validation occurs before provider invocation and result validation
  occurs after it;
- recursion depth/node exhaustion fails closed across KIR, JS, and Wasm;
- nested patterns have positive and negative conformance on every qualified
  typed backend;
- grammar coverage names each completed phase rather than forecasting it.
