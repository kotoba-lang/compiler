# ADR 0006: Safety-first binary32 scalar profile

Status: accepted

## Decision

Kotoba adds `:f32` as an explicit scalar type under
`:kotoba.floating-point/ieee-754-f32-f64-v1`. Decimal literals remain `:f64`.
An f32 value can arise only from `f32-from-bits`, `f64-to-f32-rounded`, an
explicit i64 conversion, or an f32 operation. Nested f32/f64 descriptors remain
rejected until their canonical ordering and container ABI are specified.

NaN payloads are unobservable: `f32-to-bits` returns canonical quiet NaN
`0x7fc00000`. Signed zero, infinities, and finite bits are preserved. Arithmetic
rounds to binary32 after every named operation. Checked conversions reject any
information loss; `rounded` and `truncating` operation names explicitly request
loss.

Wasm typed metadata advances to ABI v4 with f32 descriptor tag 13. Scalar f32
uses native Wasm `f32`, while browser hosts reject older metadata before
instantiation. Artifacts using f32 seal `:kotoba.typed/mixed-f32-f64-v3`.
Native and legacy CLJS backends fail closed; the web path is the restricted
Kotoba Script backend, not ambient ClojureScript semantics.

## Verification

Reference, Kotoba Script, JVM-produced Wasm, and JVM-free nbb-produced Wasm
must agree on canonical bits, rounding thresholds, signed zero, infinities,
NaN, comparisons, and checked-conversion failures. Browser admission tests pin
the metadata version and descriptor parser.
