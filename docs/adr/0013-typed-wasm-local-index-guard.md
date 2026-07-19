# ADR 0013: Fail closed above the typed Wasm one-byte local-index profile

Status: accepted

Geo policy-v7 qualification exposed that the typed Wasm emitter writes local
get/set operands directly as one byte. Indices through 127 are valid one-byte
ULEB128 values; index 128 or greater requires multi-byte ULEB128. Previously a
large generated function could therefore compile into a malformed module that
was rejected only by the Wasm engine.

Until every local operand site is migrated to canonical ULEB128 encoding, the
backend rejects a function whose parameters plus generated locals exceed 128.
This is a safety boundary, not the final scalability design: component authors
can split large computations into named functions, and the remaining compiler
work is to encode every local get/set/tee operand canonically and then remove
this temporary limit with byte-parity and over-127 execution tests.
