# ADR 0065: Native parametric option/result values

Status: accepted; recursive one-word option/result slice implemented

## Decision

The x86-64 and AArch64 native backends admit parametric `[:option T]` and
`[:result T E]` values when every payload recursively fits one native word.
The admitted leaves are `:i64`, `:bool`, and the existing pair-backed
`:string`; nested option/result values are themselves pair handles and
therefore remain one word. Descriptor recursion is capped at depth 8.

Constructors allocate the existing `(tag,payload)` pair representation.
Predicates read the tag, projections evaluate their fallback lazily, and
`match-option`/`match-result` evaluate the scrutinee once and bind only the
selected payload. No new context callback or platform ABI is introduced.

Admission and the hostile-artifact verifier independently validate descriptor
shape, recursion depth, operation arity, and match binders. Both native
emitters then lower the same sealed KIR forms to the existing bounded pair
arena.

## Deliberate boundary

This increment does not expose parametric values as native exported function
parameters or results: external structured host codecs remain a separate
boundary. Records, variants, vectors, maps, sets, generic typed capability
requests/results, and recursive user schemas are also unchanged and continue
to fail closed unless covered by an existing narrower native slice.

## Evidence

The native executor suite constructs, projects, and exhaustively matches
string-bearing and recursively nested option/result values through the real
measured loader. It checks the exact result and bounded pair allocation count.
The same program emits successfully for both x86-64 and AArch64, while a
record-bearing option remains rejected.
