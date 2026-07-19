# ADR 0013: Canonical ULEB128 encoding for Wasm local indices

Status: accepted, implemented

Geo policy-v7 qualification exposed that the typed Wasm emitter writes local
get/set operands directly as one byte. Indices through 127 are valid one-byte
ULEB128 values; index 128 or greater requires multi-byte ULEB128. Previously a
large generated function could therefore compile into a malformed module that
was rejected only by the Wasm engine.

The temporary fail-closed limit was appropriate while operands were emitted as
single bytes. The emitter now represents every local.get/local.set/local.tee
operand symbolically until the complete function instruction stream is sealed,
then validates the non-negative index and encodes it with canonical ULEB128.
This applies to both legacy i64 and typed Wasm lowering; the 128-local guard is
removed.

The executable regression instantiates typed and untyped functions containing
local index 130. The nbb corpus also compiles the typed boundary fixture and
compares its complete Wasm artifact byte-for-byte with the JVM-authored golden.
CI runs that 18-case cross-host corpus directly. Thus malformed output cannot be
mistaken for successful scalability, and JVM-free compilation remains aligned
with the reference compiler host.
