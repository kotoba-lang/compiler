# ADR 0033: CLJS typed provider ABI and structured host codec

Status: accepted; provider dispatch and application-kit codec implemented

## Decision

The CLJS backend emits a sealed typed provider registry independently from the
legacy integer `cap-call` dispatcher. Hosts install an exact `allow` set and
provider map through `set-typed-providers!`. Provider contracts must exactly
match the request/result descriptors embedded in the compiled module.

Generated code validates every structured exported function input and output,
each capability request before dispatch, and each result after dispatch. The
codec covers records, variants, options, sets, maps, heterogeneous vectors,
schema references, bounded UTF-8 strings and keywords, booleans, and numeric
leaves. No provider installed or admitted means no authority.

All nine v1 application capabilities execute through the emitted CLJS boundary,
and a nested set/option vector executes under real nbb. These close the
`typed-provider-dispatch-abi` and `request-result-host-codec` gaps.

The backend is not yet declared fully qualified. Its historical integer model
still traps outside JavaScript's exact integer range rather than representing
the full signed-i64 domain. Qualification therefore remains `pending` with the
single explicit gap `full-i64-structured-values`. Closing that gap requires an
end-to-end BigInt representation; it must not be hidden by the provider ABI.
