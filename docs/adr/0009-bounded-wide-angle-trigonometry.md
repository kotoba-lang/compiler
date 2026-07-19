# ADR 0009: Bounded wide-angle trigonometry

Status: accepted

## Decision

The floating policy advances to
`:kotoba.floating-point/ieee-754-f32-f64-v4`. `f64-sin-bounded` and
`f64-cos-bounded` accept finite radians in `[-8192*pi,8192*pi]`.

Range reduction multiplies by the fixed binary64 approximation of `2/pi`,
selects the nearest quadrant with ties away from zero, and subtracts fixed
high and low binary64 parts of `pi/2` in a fixed order. The reduced value is
evaluated by the qualified quarter-turn kernels. The absolute-error contract
is `5e-12`; non-finite and larger inputs trap.

Reference, restricted JavaScript, and Wasm use the same constants and
operation order. No backend calls a host trigonometric function or imports a
transcendental provider. The bounded domain prevents unsafe claims about
large-argument reduction where a wider Payne–Hanek implementation would be
required.
