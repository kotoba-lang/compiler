# ADR 0028: Bounded storage capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Durable Kotoba storage uses the typed capability `:storage/transact`. It does
not expose filesystem paths, file descriptors, database connections, queries,
transactions, or mutable backend objects. Version 1 admits bounded `get`,
`put`, and `delete` operations over keyword keys and string values.

Every provider instance fixes a qualified storage namespace on the host. Guest
requests cannot select or escape it. The injected transport owns durability,
quota enforcement, and atomic version checks; a successful result means the
transport has committed the operation. Conditional versions make lost updates
and deletes explicit through a typed conflict result.

Storage is distinct from the state v1 kit: state is isolated provider-lifetime
application memory, while storage delegates committed persistence to a durable
host transport. Backend exceptions and credentials are redacted. Listing,
prefix scans, watches, binary blobs, cross-key transactions, and schema-less
queries require later versioned capabilities.
