# ADR 0019: Bounded decimal to binary64 ABI

Status: implemented and qualified

Kotoba admits `decimal-f64-parse : string -> [:option :f64]` as the only
decimal-to-binary64 conversion in this profile. It shares the grammar and
limits sealed by kotoba-script ADR 0019: at most 64 ASCII bytes, a signed
decimal significand with optional point, and an optional signed exponent of
one to three digits. Finite IEEE-754 binary64 results are `some`; malformed
syntax and non-finite overflow are `none`. Underflow, subnormals, and signed
zero are values rather than errors.

The reference evaluator owns a pure CLJC oracle. Restricted JavaScript uses
the pinned kotoba-script implementation. Typed browser Wasm imports only
`kotoba:typed/decimal-f64-parse` when the operation is present; no parser,
locale, I/O, or ambient JavaScript authority is exposed to guest code. Typed
Wasm metadata advances to ABI v7. The browser host continues to admit v5 and
v6 metadata while new compiler output always claims v7.

Data-invalid decimal input fails closed as typed `none`; invalid UTF-16 remains
an enclosing string-ABI violation. The grammar rejects whitespace, NaN and
infinity spellings, hexadecimal forms, separators, locale forms, oversized
exponents, and inputs above the byte bound.

Evidence:

- kotoba-script merge `d2c766ead05f42233c94d588971e3956e912b824`;
- reference, restricted-JavaScript, and real browser-hosted typed Wasm parity
  over signed zero, underflow, minimum subnormal, maximum finite, overflow,
  malformed syntax, and the input bound;
- `npm run test-nbb-wasm32`: 23 cases, 0 failed, including a dedicated decimal
  fixture whose Wasm bytes match the JVM compiler golden;
- `clojure -M:test`: 320 tests and 3,995 assertions after qualification.
