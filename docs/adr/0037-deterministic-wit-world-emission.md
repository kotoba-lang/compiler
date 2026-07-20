# ADR 0037: Deterministic closed WIT world emission

Status: accepted; synchronous WIT emitter implemented

## Decision

The compiler derives WIT only from checked KIR. Named schemas are emitted into
one package-local `types` interface. Typed capability IDs are resolved through
the pinned Component Model capability contract and grouped into versioned
interfaces. The application world imports only those used interfaces and
exports only declared KIR exports with their checked signatures.

WIT identifiers are canonicalized deterministically and collisions reject the
build. Unknown capability IDs, unsupported descriptors, schema/nominal identity
mismatch, and recursive or otherwise unrepresentable values reject before any
component encoding.

`kotoba.compiler.core/compile-component-wit` exposes this stage after frontend
analysis, admission, and KIR lowering. Its result includes canonical UTF-8 WIT,
the raw-text SHA-256, exact capability/export inventories, target identity, and
WASI 0.3 identity. It deliberately does not return a component binary.

The official `wasm-tools component embed --dummy` parser validates generated
packages in tests. This closes WIT syntax generation as an implementation
slice, but it does not close backend qualification: Canonical ABI core exports,
component encoding, provider composition, and runtime semantic vectors remain.
