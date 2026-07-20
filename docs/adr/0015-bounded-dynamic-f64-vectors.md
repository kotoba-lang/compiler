# ADR 0015: Bounded dynamic f64 vectors

- Status: Accepted
- Date: 2026-07-20

## Context

ADR 0014 admits floating-point values in fixed heterogeneous vectors, records,
algebraic values, and reference-shaped graphs. Heightmaps, meshes, physics
buffers, and instance data also require a homogeneous collection whose runtime
length is not fixed in its type. Treating such data as an unvalidated
`externref`, a JavaScript array, or an i64 vector would lose the language's
cross-target safety contract.

## Decision

Add the leaf type `:vector-f64` and the closed operations
`vector-f64-new`, `vector-f64-count`, `vector-f64-get`, `vector-f64-at`,
`vector-f64-drop`, `vector-f64-assoc`, and `vector-f64-conj`. Source syntax
`(vector-f64 ...)` lowers to `vector-f64-new`.

The value is an immutable persistent vector of at most 16,384 finite or
non-finite IEEE-754 binary64 values. NaN remains an observable NaN class and
signed zero is preserved. Indices and counts are checked i64 values. `at`,
`assoc`, and invalid `drop` trap; `get` evaluates its fallback only when the
index is invalid. All constructors, parameters, results, and host crossings
revalidate shape, item type, and budget.

The same contract is implemented by the reference executor, Kotoba Script,
typed Wasm, and the browser host. Typed Wasm ABI version 5 adds descriptor tag
14 plus dedicated f64 vector access/update imports. Version 4 hosts therefore
reject version 5 modules instead of interpreting the extension unsafely.

## Consequences

- Dynamic f64 buffers no longer require JavaScript-owned array semantics.
- Inputs cannot smuggle BigInt, references, mutable arrays, or oversized
  buffers across the host boundary.
- Persistent updates copy the bounded vector; this profile does not promise
  zero-copy shared memory.
- Direct floating set members and direct floating map keys or values remain
  outside the admitted ABI.
- Larger or mutable GPU buffers require a future capability-scoped resource
  profile, not a silent relaxation of this value profile.

## Verification

- Kotoba Script: 37 tests / 125 assertions.
- Compiler reference, restricted JavaScript, and typed Wasm tests cover NaN,
  signed zero, persistence, invalid inputs, index traps, lazy fallback, and the
  16,384-item bound.
- Browser-host admission tests pass with typed ABI version 5.
- JVM and nbb emit byte-identical Wasm for the 19-case corpus including
  `vector-f64.kotoba`.
