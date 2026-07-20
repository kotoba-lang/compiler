# ADR 0026: Bounded HTTP capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Kotoba HTTP access is the typed capability `:http/post`, not a general socket,
host fetch object, URL callback, or ambient network namespace. Version 1 sends
an HTTPS POST with bounded headers, body, and timeout, and returns a nominal
response-or-error variant. The guest never receives a connection, stream,
promise, or host exception.

Network authority belongs to each provider instance. Its host supplies a
closed exact-origin allowlist and a transport implementation. URLs must be
absolute HTTPS URLs, fragments are rejected, and redirects must not escape the
allowlist. An empty allowlist grants no destinations. Provider construction,
request validation, and result validation fail closed.

The reference provider uses a synchronous injected transport so its contract
is deterministic and testable on CLJ and CLJS without granting network access
to the language runtime. Production hosts may implement deadlines and I/O
outside the guest, but must return the same bounded result. Async requests,
cancellation, streaming, DNS/IP policy, additional methods, and credentials
require later versioned kits rather than silently widening v1.
