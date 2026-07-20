# ADR 0034: Bounded keyword-set constants

## Status

Accepted.

## Decision

Kotoba admits a top-level `def` whose value is a non-empty set literal of at
most 32 keywords. Constant substitution lowers the literal immediately to the
existing `[:set :keyword]` typed-value profile, sorting by the language-owned
canonical order before KIR construction.

Empty sets, mixed item types, floating items, more than 32 items, computed
forms, and host-derived values fail closed. Code needing another set type must
state the descriptor explicitly with `typed-set`; the compiler does not guess.

## Rationale

Portable CLJC libraries commonly use closed keyword sets as finite enum-like
declarations. They carry no execution or host authority, while rejecting them
forces mechanical rewrites that add no safety. Restricting inference to
non-empty keywords makes the type unambiguous, reuses the already bounded and
validated typed-set ABI, and prevents host set iteration order from entering
the artifact contract.

Compatibility is established through observable reference/Kotoba Script/Wasm
semantics, ABI validation, resource bounds, and rejection tests. Wasm byte
identity is not a language-compatibility requirement.

## Verification

- Reference execution proves count and membership semantics.
- Restricted JavaScript and Wasm compilation consume the same canonical KIR.
- JVM-free nbb compilation parses the source set and emits valid Wasm.
- Empty, heterogeneous, and 33-item constants are rejected.
- Canonical fleet probes advance `engineer-render`, `mine-ai`, and `postfx`
  past their former set-constant admission gap on both Web and Wasm targets.
