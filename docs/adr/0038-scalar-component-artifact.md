# ADR 0038: First validated Component Model artifact slice

Status: accepted; scalar component packaging implemented

## Decision

The first `compile-component` slice accepts only exported `s64`, `f32`, and
`f64` signatures with no capability imports. These signatures already match
the Canonical ABI flat scalar representation. The compiler generates the exact
WIT world, embeds its metadata into the emitted core module, and creates a
validated Component Model binary with pinned `wasm-tools` 1.243.0.

The initial implementation used the then-current `wit-component` legacy
core-module name encoding. ADR 0039 subsequently replaces that output with
standard32 `cm32p2` names and the required scalar support exports.

Structured signatures and typed provider imports fail before packaging. They
must not reuse the legacy `externref` host ABI: the next slice must generate
Canonical memory, realloc, lift/lower, and post-return adapters.

The component receipt binds raw component SHA-256, WIT SHA-256, exact imports
and exports, WASI 0.3, core-name encoding, and tool identity. The Component
Model binary preamble is checked after official validation.
