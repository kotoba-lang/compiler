# ADR 0028: Bounded canonical document values

Status: accepted

## Context

The fleet pilot `kotoba-lang/annotation` exposes a real gap between scalar
maps and nominal schemas. Its public value is an extensible EDN/JSON-LD
document: keys are known in part, extension keys are permitted, and values can
be strings, booleans, integers, nested documents, or vectors. The existing
`:map` remains deliberately limited to keyword-to-i64 data. Weakening that
type, admitting arbitrary JavaScript objects, or silently dropping extension
properties would violate the safety and compatibility goals.

## Decision

Introduce a distinct immutable `:document` value in typed ABI v11. A document
is a canonical tagged tree, never a host object. Its admitted nodes are:

- null;
- boolean, signed i64, finite f64, bounded string, or bounded keyword;
- a vector of document nodes;
- a map from bounded keywords to document nodes.

Every value is validated as a whole with all of these fixed limits:

- maximum depth 8;
- maximum 256 nodes;
- maximum 32 entries in any map;
- maximum 32 items in any vector;
- maximum 65,536 aggregate UTF-8 bytes across strings, keywords, and keys;
- unique map keys in canonical keyword order;
- no NaN, infinity, functions, symbols, host references, prototypes, getters,
  cycles, or shared mutable containers.

Construction and update operations return newly frozen/canonical values.
Map association, dissociation, and merge are deterministic; later maps win,
and exceeding any limit traps instead of truncating. Lookup returns
`[:option :document]`. Scalar accessors also return typed options so a caller
must handle a kind mismatch explicitly. Runtime operations are pure and grant
no capability.

The constructor surface is `document-null`, scalar constructors for
bool/i64/f64/string/keyword, `document-vector`, and `document-map`. Container
operations are count, contains, get, assoc, dissoc, and right-biased merge.
String, bool, i64, and f64 accessors return typed options. Container-kind or
scalar-kind mismatches trap; no coercion or truthiness conversion occurs.

Typed Wasm ABI v11 assigns descriptor tag 18 to `:document`. The browser host
admits older ABIs unchanged and exposes document imports only when the sealed
module uses them. Host-created values are validated and registered; copied or
forged externrefs are rejected at the Wasm boundary. Restricted JavaScript
implements the same representation and limits independently.

## Consequences

Portable document libraries can migrate without pretending heterogeneous data
is an i64 map or exposing ambient JavaScript objects. Nominal records remain
preferred when a schema is closed. `:document` is the bounded extension seam,
not a replacement for application schemas.

Qualification covers the reference evaluator, restricted JavaScript, real
typed Wasm instantiation, hostile host values, limits, canonicalization, and
persistent updates in `document-value-test`. The browser host regression suite
also proves continued admission of ABI versions 5 through 10, while modules
using `:document` select ABI v11. `kotoba-lang/annotation` is the first fleet
consumer and remains a separate migration so compiler publication is its
immutable dependency boundary.
