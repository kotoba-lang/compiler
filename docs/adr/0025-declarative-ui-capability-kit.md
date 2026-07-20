# ADR 0025: Declarative UI capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Kotoba UI is a typed declarative boundary, not direct DOM/CSS access. A guest
commits a flat bounded set of nominal nodes through `:ui/commit`; parent IDs
form the tree without recursive or host-owned object identity. Commits use a
base revision and fail on stale state. Node IDs and referenced parents must be
valid within the same commit.

Host input is delivered through `:ui/next-event` as a bounded pull queue of
nominal events. Version 1 is synchronous and deliberately excludes callbacks,
subscriptions, arbitrary attributes, HTML injection, CSS evaluation, and DOM
handles. Async subscription and cancellation require a later versioned kit.

The reference provider admits at most 32 nodes and 64 queued events. A host
receives separate enqueue and snapshot controls; neither crosses into guest
code. AOT and JIT implementations qualify later against the reference vectors.
