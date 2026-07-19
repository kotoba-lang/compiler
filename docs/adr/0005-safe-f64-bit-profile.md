# ADR 0005: Admit f64 through an exact bit-preserving profile

Status: superseded by its Phase 2 extension below, 2026-07-20.

## Decision

Kotoba adopts `:kotoba.floating-point/ieee-754-f64-bits-v1` as its first
floating-point policy. Phase 1 admits scalar `:f64` values, finite decimal and
exponent literals, `##NaN`, `##Inf`, `##-Inf`, and the two explicit operations
`f64-to-bits` and `f64-from-bits`.

The source reader converts every f64 literal to `(f64-from-bits signed-i64)`.
Consequently the checked HIR/KIR and generated Wasm do not depend on the
compiler host's decimal printer or on JavaScript number serialization. Signed
zero and infinities retain their exact bits. Source NaN is canonicalized to
`0x7ff8000000000000`; arbitrary NaN payloads can be constructed only by the
explicit bits operation.

The profile is implemented by the reference interpreter, Kotoba Script, and
Wasm. Scalar-only Wasm modules use native f64 parameters/results and no typed
host imports. Their compatibility descriptor is
`:kotoba.typed/mixed-f64-v2`, and typed metadata ABI v3 reserves descriptor tag
12 for f64. JVM-hosted and JVM-free nbb compilation must produce byte-identical
Wasm golden artifacts.

## Safety boundary

This decision does not admit floating arithmetic, comparison, implicit
integer conversion, f32, transcendental operations, or nested f64 inside
algebraic/container values. It does not enable f64 on native or CLJS targets.
Each unsupported path is rejected at admission or target selection, rather
than being delegated to ambient host semantics.

## Follow-up sequence

1. Specify deterministic f64 arithmetic and exceptional-result tests.
2. Add checked i64/f64 conversions with explicit overflow and NaN behavior.
3. Add f32 only with an independently versioned rounding contract.
4. Qualify square-root and transcendental operations against pinned vectors.
5. Run Kami engine geometry, physics, and rendering goldens before widening
   the production language profile.

## Phase 2 extension

The policy advances to `:kotoba.floating-point/ieee-754-f64-arithmetic-v1`.
It adds explicitly typed `f64-add`, `f64-sub`, `f64-mul`, `f64-div`,
`f64-neg`, `f64-abs`, `f64-eq`, `f64-lt`, `f64-le`, `f64-gt`, `f64-ge`, and
`f64-unordered`. Reference, restricted JavaScript, and Wasm agree on finite
rounding, division by zero, signed zero, infinities, ordered/unordered NaN
behavior, and canonical NaN observation. This does not authorize implicit
conversion, fused operations, remainder, square root, or transcendentals.
