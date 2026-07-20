# ADR 0029: Typed clock capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Kotoba time observation uses `:clock/now`, not ambient host time APIs, timer
objects, scheduler handles, sleeps, or callbacks. Version 1 explicitly
separates wall time in Unix milliseconds from monotonic time in nanoseconds.
Both are signed-i64 values constrained to be non-negative.

Each provider receives host-owned wall and monotonic source functions. Results
include a provider-local observation sequence, making the order of observations
auditable without claiming that wall time itself cannot move backward. The
provider enforces nondecreasing monotonic ticks and returns typed, redacted
errors for unavailable, invalid, or regressing sources.

This capability observes time only. Delays, deadlines, scheduling, timezone
data, calendar arithmetic, virtual clocks, and event subscriptions require
separate versioned semantics. Tests and deterministic hosts can inject logical
clock functions without changing guest code.
