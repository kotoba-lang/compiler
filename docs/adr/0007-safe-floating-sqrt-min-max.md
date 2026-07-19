# ADR 0007: Cross-backend square root, minimum, and maximum

Status: accepted

## Decision

The floating policy advances to
`:kotoba.floating-point/ieee-754-f32-f64-v2`. It adds `f32-sqrt`, `f64-sqrt`,
`f32-min`, `f32-max`, `f64-min`, and `f64-max`.

Square root preserves both zero signs, returns positive infinity for positive
infinity, and propagates NaN for negative finite values, negative infinity, or
NaN. Minimum and maximum propagate NaN if either input is NaN. Between
opposite-signed zeros, minimum returns negative zero and maximum returns
positive zero. Binary32 results round after the named operation.

The reference interpreter uses Java primitive floating operations, the web
backend uses restricted Kotoba Script operations, and Wasm uses the matching
scalar opcodes. Canonical bit observation continues to erase NaN payload
differences. General host math-library calls remain forbidden.
