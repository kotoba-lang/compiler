# ADR 0012: Deterministic bounded wide exp/log scaling

Status: accepted

Floating policy v7 extends the existing near-zero exponential and near-one
logarithm kernels without delegating semantics to a host math library.
`f64-exp-bounded` accepts `[-512*ln(2),512*ln(2)]`, reduces with fixed
`1/ln(2)` and split-`ln(2)` constants, then constructs the exact normal
power-of-two scale from binary64 exponent bits. Its relative-error ceiling is
`1e-13`.

`f64-log-bounded` accepts `[2^-512,2^512]`. It extracts the binary exponent and
mantissa by sealed i64 operations, normalizes the mantissa into `[0.75,1.5]`,
uses the qualified near-one kernel, and reconstructs the result with the same
split-`ln(2)` constants. Its absolute-error ceiling is `1e-13`.

NaN, infinity, non-positive logarithm inputs, and values outside these domains
trap. Reference, restricted JavaScript, and typed Wasm must agree, including
JVM/nbb byte parity. Host exp/log calls and imports remain forbidden.
