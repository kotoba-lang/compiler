# ADR 0030: Bounded structured log capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Kotoba logging uses typed `:log/append` and `:log/read` capabilities rather
than stdout, console APIs, logger objects, filesystem paths, or arbitrary sink
configuration. Entries contain a bounded level, event name, message, and at
most four unique structured string fields. The provider assigns the sequence;
guest code cannot forge ordering metadata.

The reference provider retains at most 256 entries per isolated instance and
returns at most eight entries per read. When retention removes old entries,
the next read explicitly reports `truncated` together with oldest and latest
sequence numbers. Read access covers only the same provider instance and must
still be separately declared and admitted from append access.

Production sinks, timestamps, tracing context, redaction policies, durable
audit export, subscriptions, and cross-instance queries require host adapters
or later versioned kits. They do not widen v1 or expose sink credentials and
handles to guest code.
