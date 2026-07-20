# ADR 0016: Sealed i32 wrapping and xorshift32

- Status: Accepted
- Date: 2026-07-20

## Context

Terrain lattice hashing requires Rust-compatible signed i32 wrapping multiply
and arithmetic right shift. Deterministic vegetation scatter requires unsigned
xorshift32. Reusing JavaScript bitwise coercion, JVM overflow behavior, or
Wasm's masked shift counts without a language contract would produce
target-dependent worlds.

## Decision

Admit the closed i64-transport operations `i32-wrap`, `u32-wrap`,
`i32-wrapping-add`, `i32-wrapping-mul`, `i32-xor`, `i32-shift-left`,
`i32-shift-right`, `u32-shift-right`, and `xorshift32`.

All operands and results cross the public ABI as checked i64 values. Signed
operations return the sign-extended two's-complement i32 result; unsigned
operations return the zero-extended u32 value in `[0, 2^32-1]`. Shift counts
must be integer literals in `[0,31]`. This rejects dynamic or out-of-range
counts rather than inheriting Wasm/JavaScript masking behavior.

`xorshift32` performs the standard `(13,17,5)` sequence, evaluates its state
argument once, wraps after every xor/shift stage, and returns a u32. Seed-zero
policy belongs to the caller; the primitive maps zero to zero.

## Consequences

- Terrain hashes and vegetation scatter can be migrated without host integer
  coercion.
- JVM reference execution, nbb, restricted JavaScript, and Wasm use the same
  bit-level contract.
- Variable-count rotations and shifts remain outside this profile until they
  gain an explicit checked lowering; masked shifts are not silently admitted.
- These operations are not admitted on native backends that have not
  implemented the profile.

## Verification

- Boundary vectors cover signed/unsigned wrap, overflow add/multiply, signed
  and unsigned shifts, and the first three xorshift32 outputs from seed 1.
- Compiler: 312 tests / 3,960 assertions.
- Kotoba Script: 38 tests / 130 assertions.
- Browser host passes and JVM/nbb Wasm output is byte-identical for all 20
  fixtures, including `i32-profile.kotoba`.
