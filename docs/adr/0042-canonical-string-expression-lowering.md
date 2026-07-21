# ADR 0042: Canonical string literal and concatenation lowering

Status: accepted; bounded string expression lowering implemented

## Decision

The structured Component slice admits pure exported functions whose parameters
and result are strings and whose body is composed only of parameters, UTF-8
string literals, and nested `string-concat` forms.

The compiler flattens the concatenation tree into byte segments. It sums their
lengths in `i64`, traps above the 65,536-byte language bound, allocates once,
and copies every segment into the final result. Consequently allocator usage
does not grow with expression nesting and no intermediate string is exposed.

Literal bytes occupy an immutable prefix of component memory. The arena begins
at the next eight-byte boundary. Its fixed page count is derived from literal
bytes and the number of Canonical string parameters, allowing every admitted
input plus one bounded output and result area without memory growth.

Operations that inspect or transform Unicode content, including replacement,
remain rejected until their UTF-8 semantics have dedicated linear-memory
lowering and conformance vectors.
