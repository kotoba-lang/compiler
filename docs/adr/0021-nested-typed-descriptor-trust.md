# ADR 0021: Transitive trust for nested typed descriptors

Status: implemented and qualified

Typed Wasm metadata is decoded and recursively frozen before the browser host
constructs its typed runtime. Every array reachable from the admitted metadata
descriptor table is therefore trusted transitively. The host does not infer
whether a two-item array is a record member pair while establishing trust:
descriptor forms such as `[:vector [...]]` have that same shape and must retain
their own identity when nested in options or nominal records.

Structural equality remains insufficient for values supplied across an
`externref` boundary. Compound values must have been constructed by the same
typed runtime, and their embedded descriptor must either be the expected
descriptor object or a metadata-origin descriptor already present in the
runtime's private trust set. A caller-created frozen clone is not trusted, so
this change admits composed compiler values without admitting descriptor
forgery.

The regression case parses two bounded decimal f64 triples and seals them into
an optional nominal `:geometry/pose` record. It crosses the actual
browser-hosted typed Wasm ABI and checks both nested vectors. Existing boundary
tests continue to reject forged compound values and cross-schema substitution.

Evidence: `clojure -M:test` passes 322 tests / 4,000 assertions with zero
failures or errors.
