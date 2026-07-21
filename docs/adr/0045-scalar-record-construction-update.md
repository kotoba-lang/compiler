# ADR 0045: Scalar record construction and update

Status: accepted; Canonical record construction/update implemented

## Decision

The Component slice admits direct construction of one sealed scalar record
from scalar parameters, and direct `record-assoc` of one scalar field from a
record plus replacement parameter. Bodies must contain only the exact
constructor or update form; computed field expressions remain rejected.

The compiler creates a field-source plan over flattened core parameters.
Construction maps each field to its corresponding parameter. Update maps every
unchanged field to the flattened input record and the selected field to the
replacement parameter. Both paths share the checked Canonical result-area
writer, width-specific stores, boolean validation, and bounded allocator.

The result descriptor and operation descriptor must equal the sealed nominal
schema. Field names and replacement types must match exactly. Nested and
string-containing records, multiple updates, and general record expressions
remain outside this slice.
