# ADR 0023: Specification-first application language and CLJS reference runtime

Status: accepted; reference runtime and provider registry implemented

## Context

Requiring every safe language addition to land simultaneously in Wasm, native
AOT, and future JIT backends makes application-language maturation depend on
the slowest code generator. It also confuses an implementation gap with a
language safety decision.

## Decision

The normative order is specification, portable CLJ/CLJS reference execution,
then backend qualification. The machine-readable application contract is
`resources/kotoba/lang/application-language.edn`; grammar coverage records
separate implementation and qualification states.

Checked KIR runs in `kotoba.compiler.reference-runtime` on CLJ or CLJS. Its
provider registry is closed and deny-by-default. Each provider declares an
exact request type, result type, and invoke function. The registry contract
must equal the guest's `typed-cap-call` contract. Runtime value validation
still occurs immediately before and after provider invocation in the KIR
executor.

CLJS does not grant ambient JavaScript authority. DOM, fetch, state, storage,
LLM, clocks, and logs enter only through versioned typed provider kits.
Arbitrary interop, host object identity, eval, require, and mutable guest
atoms remain outside the language.

Wasm/native AOT and future JIT compilers are qualification targets. A backend
may report a specified feature as not yet qualified, but must not redefine or
silently weaken its semantics. Shared conformance vectors compare each backend
with the reference runtime before qualification.

## Consequences

Application migration can proceed on the CLJS reference runtime while code
generators mature independently. Compiler work becomes batched conformance
work instead of a prerequisite for specifying every application feature.
