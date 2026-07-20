# ADR 0024: Bounded state capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Application state is exposed as the typed capability `:state/transact`, not
as guest `atom`, mutable host object identity, or arbitrary callbacks. Version
1 admits synchronous `get`, `put`, and `delete` requests over qualified keyword
keys and bounded string values. Results are a nominal variant containing
versioned entries, missing/deleted outcomes, or a bounded structured error.

Each provider instance owns an isolated map of at most 256 entries. Requests
are linearized by the reference provider. Request validation happens before
dispatch and result validation after dispatch through the reference runtime.
Backend qualification remains independent from this language-level contract.

Subscriptions, compare-and-set, durable persistence, and cross-instance
transactions are intentionally deferred to later versioned capabilities; they
must not silently change the v1 synchronous semantics.
