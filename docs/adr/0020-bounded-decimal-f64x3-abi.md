# ADR 0020: Bounded fixed-width decimal f64 vector ABI

Status: implemented and qualified

Kotoba admits `decimal-f64x3-parse` with type
`string -> [:option [:vector [:f64 :f64 :f64]]]`. The operation implements the
fixed-width and atomic grammar sealed by kotoba-script ADR 0020: at most 194
ASCII bytes, exactly three ADR-0019 decimal components, and only ASCII space,
tab, carriage-return, or line-feed separators. Each component remains bounded
to 64 bytes and must produce a finite IEEE-754 binary64 value.

Wrong arity, Unicode whitespace, commas, an invalid or non-finite component,
or either byte bound produces typed none. No partial vector is observable.
Signed zero, underflow, and subnormals are preserved in a canonical sealed
heterogeneous three-f64 vector.

The pure CLJC reference evaluator, pinned restricted-JavaScript backend, and
browser-hosted typed Wasm use the same contract. Typed Wasm metadata advances
to ABI v8 and conditionally imports `kotoba:typed/decimal-f64x3-parse`; the
browser host retains v5, v6, and v7 artifact admission.

Evidence: `clojure -M:test` passes 321 tests / 3,999 assertions; focused
reference, restricted-JavaScript, and real browser-hosted typed Wasm vectors
agree; and `npm run test-nbb-wasm32` passes all 24 JVM/nbb byte-identity cases.
