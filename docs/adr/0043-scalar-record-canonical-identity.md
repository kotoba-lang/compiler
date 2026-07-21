# ADR 0043: Named scalar record Canonical identity slice

Status: accepted; scalar-record identity lowering implemented

## Decision

The Component Model artifact admits one pure identity function over one named,
non-empty record whose fields are limited to `s64`, `f32`, `f64`, and `bool`.
The parameter and result must reference the same schema identity. Anonymous,
recursive, string-containing, and otherwise structured records remain rejected.

The Canonical layout planner computes every field offset, aggregate alignment,
final padded size, and flat core signature from the sealed schema. Schema map
identity must equal the record's nominal identity. The component core validates
canonical boolean values, allocates one bounded result area, and stores fields
using their planned offsets and scalar widths.

For example, `{x: s64, weight: f64, visible: bool}` flattens to
`(i64, f64, i32)`, uses offsets `0`, `8`, and `16`, and has size 24 with
alignment 8. Its result is returned indirectly through an `i32` result-area
pointer as required by standard32.

This slice establishes schema-bound record transport without creating an
`externref`. Record construction, field access/update, nested records, and
capability request/results require subsequent checked expression lowering.
