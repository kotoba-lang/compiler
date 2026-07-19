# ADR 0008: Bounded deterministic trigonometry

Status: accepted

## Decision

The floating policy advances to
`:kotoba.floating-point/ieee-754-f32-f64-v3`. The first qualified
transcendentals are `f64-sin-quarter-turn` and `f64-cos-quarter-turn`.

Both accept only finite radians in the closed interval `[-pi/4, pi/4]` and
trap otherwise. They evaluate fixed binary64 coefficient bits in a fixed
degree-17/16 Horner order. Fused multiply-add is forbidden. Sine preserves
signed zero. The public absolute-error bound is `4e-15` throughout the domain.

The reference, restricted JavaScript, and Wasm backends execute the same
multiply/add sequence. Wasm emits no transcendental host import. JavaScript
does not call `Math.sin` or `Math.cos`; those functions appear only in test
oracles. NaN is rejected at the domain boundary rather than becoming an
unbounded host-dependent result.

## Consequences

Callers needing a wider angular domain must perform a separately qualified
range-reduction operation. This avoids claiming safe full-range trigonometry
before large-argument reduction and accumulated-error behavior are specified.
