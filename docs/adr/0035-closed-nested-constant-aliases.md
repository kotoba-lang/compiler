# ADR 0035: Closed nested constant aliases

## Status

Accepted.

## Decision

A top-level constant may reference another declared top-level constant by
symbol, including from a bounded vector or map. The frontend resolves the
entire graph before desugaring and accepts it only when every leaf terminates
in already admitted closed literal data.

Unknown symbols, namespaced host symbols, self-reference, and indirect cycles
fail closed. Resolution executes no form and performs no host lookup.

## Rationale

Portable CLJC commonly factors a scalar constant into a larger immutable
configuration value. Treating that reference as forbidden forces duplication
without removing authority. A finite, declared-only, acyclic substitution is
equivalent to writing the resolved literal inline and preserves Kotoba's
compile-time safety boundary.

## Verification

- Scalar aliases compile to the same KIR on restricted JavaScript and Wasm.
- Aliases nested in bounded maps and vectors execute through the reference
  oracle after full resolution.
- JVM-free nbb compilation emits valid Wasm for an alias fixture.
- Unknown, namespaced host, and cyclic references are rejected.
- The canonical fleet probe advances `ghosthacker-echoes` past its former
  nested-constant admission failure on Web and Wasm targets.
