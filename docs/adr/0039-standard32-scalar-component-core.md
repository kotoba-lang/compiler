# ADR 0039: Standard32 scalar component core

Status: accepted; scalar standard32 emission implemented

## Decision

`compile-component` emits a dedicated core module using the Component Model
standard32 naming scheme. It does not change the exports of ordinary Wasm
targets. Each declared scalar export is published as `cm32p2||<name>` with its
matching `<name>_post` function, alongside `cm32p2_memory`, `cm32p2_realloc`,
and `cm32p2_initialize`.

The scalar slice performs no memory transfer, so its post-return and initialize
functions are no-ops. Its realloc function returns the null pointer and is not
reachable from any admitted scalar Canonical ABI call. Structured signatures
remain rejected before emission; admitting them requires a real bounded
allocator and generated lift/lower validation, not reuse of this scalar stub.

Component packaging now requires pinned `wasm-tools 1.243.0` with
`--reject-legacy-names`. The artifact receipt records
`:component-model/standard32`, so a legacy-named core cannot be represented as
qualified output.
