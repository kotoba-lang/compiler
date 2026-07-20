# ADR 0031: Provider conformance suite v1

Status: accepted; CLJ/CLJS structural qualifier and reference suite implemented

## Decision

Every application capability provider is qualified through one shared suite in
addition to its kit-specific semantic vectors. The suite binds the provider's
qualified capability name to the compiler-local numeric ID, requires the exact
closed provider map (`request-type`, `result-type`, `invoke`), and validates
both boundary types against the bounded structured-value profile.

The machine-readable suite manifest is the inventory of reference-implemented
application kits. It must agree with the capability registry, each kit resource,
and the application-language catalog. Duplicate names and IDs fail closed.

Structural qualification does not replace semantic tests. Each kit still owns
vectors for limits, authority confinement, errors, ordering, and host behavior.
The reference runtime continues to enforce deny-by-default admission, exact
guest/provider contract matching, request validation before dispatch, and
result validation after dispatch. AOT and JIT backends must execute the same
manifest and semantic vectors before claiming kit qualification.
