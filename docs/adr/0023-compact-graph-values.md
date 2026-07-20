# ADR 0023: Compact graph values

Status: accepted

## Context

The general typed-map ABI recursively charges every key and value against the
64-node ADT budget. That is the correct default for arbitrary structured data,
but it limits graph validation to roughly twenty named nodes. Raising the
global ADT or map limit would enlarge every program's attack surface.

## Decision

ABI version 10 adds two purpose-specific immutable value types, extending the
schema and typed-capability metadata introduced by ABI version 9:

- `:string-index` is a canonical sorted string-to-i64 index with at most 128
  unique entries and at most 65,536 aggregate UTF-8 key bytes.
- `:disjoint-set-i64` is a persistent union-find forest with at most 128
  items. `disjoint-set-i64-union` performs bounded union-by-rank in the trusted
  runtime and returns `[:option :disjoint-set-i64]`; `none` means the endpoints
  were already connected and therefore identifies a graph cycle.

These values do not consume one ADT node per contained scalar. Their dedicated
validators enforce their entire shape and resource budgets. All operations are
pure and grant no authority. Out-of-range indexes, malformed parent forests,
cycles in supplied parent data, duplicate/non-canonical keys, oversized values,
and aggregate UTF-8 overflow fail closed.

The typed Wasm host admits ABI versions 5 through 10. ABI 9 retains tag 15 for
schema references; only ABI 10 defines compact-value tags 16 and 17 and their
imports. Runtime-created compound values are registered as
trusted; copied arrays with otherwise matching descriptors are rejected at the
Wasm boundary. Restricted JavaScript independently implements the same
observable operations and limits through the pinned kotoba-script dependency.

## Consequences

Graph consumers can validate up to 128 named nodes within the fixed 512-call
guest fuel budget without relaxing the general structured-value limits. Larger
or streaming graphs remain outside this ABI and require a separately reviewed
profile rather than an implicit limit increase.

Conformance covers reference execution, restricted JavaScript, real Node
WebAssembly, host factories, forged externrefs, canonical ordering, aggregate
key bytes, invalid parent forests, invalid indexes, and nbb/JVM reproducible
Wasm artifacts.
