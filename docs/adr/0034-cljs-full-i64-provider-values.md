# ADR 0034: Full signed-i64 values at the CLJS provider boundary

Status: accepted; CLJS provider execution qualified

## Decision

The CLJS typed provider codec accepts the complete signed-i64 domain without
converting boundary values through JavaScript Number. Values inside the exact
Number range remain accepted for compatibility with the existing CLJS backend;
larger magnitudes must cross as JavaScript BigInt. The JVM evaluation oracle
uses its native bounded integer representation for the same predicate.

Generated source carries a platform-selected i64 predicate. The CLJS branch
recognizes BigInt by constructor identity and checks the inclusive bounds
`-9223372036854775808` through `9223372036854775807`. Requests are validated
before provider invocation and results afterward, so values one below or above
the domain fail on either side of the boundary.

Real nbb evidence round-trips both endpoints and rejects both out-of-range
requests and a forged out-of-range provider result. Together with the nine-kit
semantic vector introduced by ADR 0033, this closes the final recorded CLJS
provider gap. The CLJS backend is therefore `qualified` for provider manifest
v1. This claim does not broaden the separate arithmetic profile: ordinary
CLJS arithmetic still traps outside the exact Number range.
