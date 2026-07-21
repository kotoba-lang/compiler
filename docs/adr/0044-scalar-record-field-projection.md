# ADR 0044: Scalar record field projection

Status: accepted; Canonical record field projection implemented

## Decision

The Component slice admits a pure function with one named scalar-record
parameter and one scalar result when its body is exactly `record-get` for a
field declared by that record schema. The body descriptor must be either the
same nominal reference or the exact sealed schema descriptor.

The standard32 export receives record fields in canonical flattened order,
validates every boolean field, and returns the selected core scalar directly.
No `externref`, result allocation, or record reconstruction is involved. The
post-return function accepts the returned scalar type required by standard32
and performs no memory action.

Unknown fields, result-type disagreement, non-scalar fields, schema identity
mismatch, computed record expressions, construction, and update remain
rejected before component encoding.
