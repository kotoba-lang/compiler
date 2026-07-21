# ADR 0046: Scalar typed capability Component import

Status: accepted; scalar capability import lowering implemented

## Decision

The first provider-facing Component slice admits one direct `typed-cap-call`
whose request and result are Canonical scalars. The capability ID must resolve
through the pinned contract, and the body request must be the function's sole
parameter with exactly matching declared types.

The core imports the standard32 interface function using its versioned
`cm32p2|kotoba:application/<interface>@1` module name and delegates the exported
function directly. WIT still imports only the declared capability interface.
Unknown IDs, structured request/results, computed requests, and multiple calls
remain rejected.

This closes scalar application-side import lowering. Provider component
construction, closed-world composition, structured codecs, and runtime
semantic vectors remain separate qualification requirements.
