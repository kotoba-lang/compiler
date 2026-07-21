# ADR 0048: Sealed scalar-record capability Canonical ABI

Status: accepted; record request/result slice implemented

## Decision

A direct `typed-cap-call` may use one sealed nominal record as its request and
result when every field is `i64`, `f32`, `f64`, or `bool`. The body must pass
the sole function parameter without computation, both descriptors must match
the checked function signature, and both schema identities must resolve in the
sealed schema table.

The application-side standard32 lowering flattens request fields and allocates
the Canonical result area before calling the imported provider function. The
provider-side identity artifact accepts the flattened fields, writes them into
its Canonical result layout, and returns that pointer. `wac plug` connects the
two adapters; `wasm-tools validate` checks the closed component.

An executable Wasmtime check of `invoke({x: 7, weight: 1.5, visible: true})`
returns the same record. This is implementation evidence under locally
available Wasmtime 42.0.1, not formal runtime qualification: the pinned
baseline still requires Wasmtime major 43 or newer.

Nested records, strings, lists, options, results, variants, different
request/result record identities, computed requests, and production provider
semantics remain fail-closed.
