# ADR 0047: Closed provider Component composition

Status: accepted; scalar composition infrastructure implemented

## Decision

Compiler-owned provider artifacts use the same pinned capability contract and
standard32 names as application components. Composition runs through pinned
`wac plug`; the compiler first requires an exact multiset match between the
application imports and provider capabilities, then validates the result with
official `wasm-tools`. A missing, duplicate, or mismatched provider is therefore
a build failure rather than a runtime ambient lookup. The deprecated
`wasm-tools compose` command is not part of the implementation.

The first provider artifact is deliberately an identity implementation for one
scalar request/result. It is validation evidence for Component interface
wiring only. It is not semantic qualification for HTTP, state, UI, LLM,
storage, clock, or logging. Production provider kits still require their
bounded request/result codecs and shared semantic vectors.

This separation prevents a successful linker test from being reported as
capability correctness or authority confinement evidence.
