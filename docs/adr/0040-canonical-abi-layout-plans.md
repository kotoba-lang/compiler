# ADR 0040: Canonical ABI layout plans precede structured emission

Status: accepted; scalar and bounded-string planning implemented

## Decision

Structured component emission must consume a checked Canonical ABI layout plan
derived from KIR descriptors. It must not infer transport shapes ad hoc inside
the binary emitter or bridge through the existing `externref` host runtime.

The first plan covers `s64`, `f32`, `f64`, and bounded UTF-8 `string`. A string
has the standard32 memory shape `(pointer: i32, length: i32)`, four-byte
alignment, and an explicit 65,536-byte language bound. String inputs require
checked pointer arithmetic and UTF-8 validation. A string result uses the
indirect result-area convention and therefore has a core `i32` result plus an
`i32` post-return parameter.

This ADR does not admit string component artifacts. The current Wasm function
body representation remains `externref`; the next implementation must provide
linear-memory lift/lower code and a bounded allocator, then switch admission
only after semantic execution tests pass.
