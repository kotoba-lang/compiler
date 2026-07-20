# ADR 0038: First validated Component Model artifact slice

Status: accepted; scalar component packaging implemented

## Decision

The first `compile-component` slice accepts only exported `s64`, `f32`, and
`f64` signatures with no capability imports. These signatures already match
the Canonical ABI flat scalar representation. The compiler generates the exact
WIT world, embeds its metadata into the emitted core module, and creates a
validated Component Model binary with pinned `wasm-tools` 1.243.0.

This first artifact uses the current `wit-component` legacy core-module name
encoding. It is valid in the pinned toolchain. The optional
`--reject-legacy-names` qualification mode is not claimed: its standard32
encoding also requires `cm32p2` export names plus memory, realloc, initialize,
and post-return functions even for this scalar slice. Those adapters belong to
the next Canonical ABI lowering milestone and remain explicit work, rather than
being represented by placeholder exports.

Structured signatures and typed provider imports fail before packaging. They
must not reuse the legacy `externref` host ABI: the next slice must generate
Canonical memory, realloc, lift/lower, and post-return adapters.

The component receipt binds raw component SHA-256, WIT SHA-256, exact imports
and exports, WASI 0.3, core-name encoding, and tool identity. The Component
Model binary preamble is checked after official validation.
