# ADR 0022: String equality remains an i64 value in typed Wasm

Status: implemented and qualified

Kotoba's sealed `string=?` operation returns `:i64`, with `1` for equal UTF-8
content and `0` otherwise. This is the existing reference, restricted
JavaScript, and native contract. Typed Wasm must preserve that value contract;
it may use the host's internal i32 equality result, but it zero-extends that
result directly to i64 instead of wrapping it as a typed boolean externref.

The previous lowering treated `string=?` as `:bool` only inside the typed Wasm
backend. Conditions happened to work, but value composition such as
`(= (string=? left right) 0)` could emit an invalid Wasm call by mixing an
externref with i64. The frontend had already checked the expression under the
correct i64 contract, so the backend disagreement was both a conformance and a
validation defect.

The regression test calls a recursive function carrying a string externref and
an i64 counter, then observes `string=?` as an exported i64 result. This covers
both mixed reference/scalar recursive signatures and value-level equality.

Evidence: `clojure -M:test` passes 323 tests / 4,001 assertions; browser-host
admission and denial vectors pass; and all 24 JVM/nbb typed-Wasm artifact
identity cases pass.
