# ADR 0066: Production transport for the HTTP capability kit, with bounded per-hop redirect revalidation

Status: accepted; production `:clj` transport implemented and unit-tested against local fake servers; `:cljs` remains an explicit, documented gap

## Decision

`kotoba.compiler.provider.http/provider` (ADR 0026) has always taken its
`:transport` as a host-injected `(fn [request] -> reply)`. Every existing
test and every capability kit in this ADR chain has only ever supplied an
identity/fixture transport (ADR 0049's gap ledger: "current providers are
identity wiring fixtures, not implementations" of the nine application
capabilities). This ADR adds the first REAL transport for `:http/post`:
`kotoba.compiler.provider.http-transport/production-transport`, a `:clj`-only
synchronous function backed by `java.net.http.HttpClient`.

`http.cljc` itself is unmodified. Every bound it already enforces before
calling the transport -- exact-origin allowlist, absolute-HTTPS-only, no
fragments, header count/size, body size, timeout range -- is unchanged and
un-weakened.

### This is a genuinely different threat model from ADR 0064's LLM transport

ADR 0064's LLM transport always calls ONE fixed, host-chosen endpoint
(`murakumo-main`). `:http/post` calls whatever HTTPS origin the GUEST names,
bounded only by the host's own closed `allowed-origins` set -- so this
transport, unlike the LLM one, is a genuine server-side-request-forgery
(SSRF) surface, and was designed and reviewed with that as the primary
concern rather than an afterthought. Three defenses, all additive on top of
(never in place of) `http.cljc`'s own bounds:

1. **Redirects are never auto-followed by the JDK.** The `HttpClient` is
   built with `HttpClient$Redirect/NEVER`. This namespace owns the redirect
   loop itself (`follow-and-collect!`), and re-validates EVERY hop's
   resolved origin against the exact same closed `allowed-origins` set the
   host also gave to `http/provider` -- a REQUIRED constructor option here,
   not optional, and it must be the identical set (there is deliberately no
   way to configure this transport with a wider or different allow-list than
   the provider's own; that would silently reintroduce the exact SSRF
   surface ADR 0026 closed). This matches the capability kit's own
   already-declared semantics
   (`resources/kotoba/lang/capability-kits/http-v1.edn`:
   `:redirects :transport-must-not-follow-outside-allowlist`) rather than
   deviating from it.

   **Why not simply never follow at all (`Redirect.NEVER` and stop there)?**
   That would also satisfy "must not follow outside the allowlist"
   (vacuously -- it never follows anything), and was considered. It was
   rejected because the capability kit's spec phrasing ("must not follow
   *outside* the allowlist") implies within-allowlist following is the
   intended v1 semantic, and a v1 that silently never followed ANY redirect
   -- including same-origin ones, which are extremely common in real HTTPS
   APIs -- would be a materially less useful implementation of an
   already-accepted contract without a new ADR revising that contract.
   Building the bounded per-hop revalidation loop is more code than
   `Redirect.NEVER` alone, but it is what ADR 0026 already promised.

   Two asymmetric outcomes, both deliberate (see `follow-and-collect!`'s own
   docstring for the code-level detail):
   - The FIRST hop -- the guest's own URL, already validated once by
     `http.cljc` before this transport ever runs -- is re-validated here too
     (defense in depth: this transport has no independent authority and must
     never trust a caller that skipped `http.cljc`). If it fails, this call
     refuses OUTRIGHT with a typed `:http/destination-blocked` error.
   - Every SUBSEQUENT hop (an actual redirect target) is validated the same
     way BEFORE being connected to, but a failure there is NOT an error --
     the prior response (the 3xx itself, with its `Location` header and any
     other headers intact) is returned to the guest exactly as received,
     un-followed, so the guest or its host can decide whether to issue a
     fresh, independently-bounded `:http/post` call of its own. Exceeding
     `:max-redirects` (default 5, `0` disables following entirely) is
     handled identically -- the last response in the chain becomes final.
     This is the one deliberate asymmetry in this namespace: refuse the call
     the guest actually asked for with a typed error, but never error out
     over a redirect this transport merely chose not to chase.

2. **A best-effort private/loopback/link-local/multicast destination-IP
   block runs before every hop's actual connection**
   (`InetAddress/getAllByName` -> reject if ANY resolved address is
   loopback, link-local (including the `169.254.169.254` cloud-metadata
   address), RFC1918/site-local, IPv6 unique-local (`fc00::/7`, which
   `InetAddress.isSiteLocalAddress` does NOT cover -- it only recognizes the
   deprecated IPv6 `fec0::/10` site-local range), multicast, or
   any-local/wildcard).

   **This is explicitly NOT a complete DNS-rebinding defense** -- see
   Remaining gaps. The resolved address checked here is not pinned for the
   actual `HttpClient.send` connection that follows moments later, so a
   TOCTOU race against attacker-controlled DNS with a very short TTL is not
   fully closed by this check alone. It was still judged worth doing: it
   closes the far more common "static misconfiguration" case (an
   allow-listed hostname that resolves directly to an internal address, no
   active rebinding attack required) at negligible cost, and is honestly
   documented as incomplete rather than presented as a full fix.

   No option is exposed to disable or widen this check. This was a
   deliberate choice: adding such a knob (even for legitimate use cases like
   an intentionally internal-only allow-listed service) would be exactly the
   kind of escape hatch the task's own instructions warn against ("if
   judgment is unclear, choose the safer option").

3. **The response body is read through a length-bounded stream reader**
   (`read-bounded-bytes`, never `BodyHandlers/ofByteArray`'s fully-unbounded
   in-memory buffer), and every response header is folded to a bounded
   count/size (`bounded-response-headers`) before being handed back to
   `http.cljc`. Both defend `http.cljc`'s own post-transport checks
   (`value/bounded-string!` on the body, `typed-headers` on the header set)
   -- which run OUTSIDE `invoke-transport`'s try/catch -- from throwing an
   unhandled exception on an ordinary large-body or many-header real-world
   response. Without this, ANY real HTTPS API returning a body over 64KB or
   more than 32 response headers (both common -- CDN-fronted APIs routinely
   send 40+ headers) would violate ADR 0026's own invariant that "the guest
   never receives a connection, stream, promise, or host exception" the
   first time it happened. This is bounded/defended in the transport, not by
   loosening `http.cljc`'s own limits.

### Wire shape

Always POST (the only method ADR 0026 v1 defines), body/headers passed
through verbatim from the already-bounded guest request, HTTP/1.1 pinned
explicitly (same reason as ADR 0064: the JDK's default HTTP/2 attempt cannot
negotiate against a plain HTTP/1.1 test server, surfacing as an opaque
IOException rather than a clean protocol error; real HTTPS endpoints
ALPN-negotiate fine either way). A small, fixed set of headers the JDK
client itself refuses to set directly (`connection`, `content-length`,
`expect`, `host`, `upgrade`) is dropped defensively before building the
request, so a guest that happens to set one of these degrades quietly
instead of the whole call failing with an opaque `IllegalArgumentException`
from deep inside the JDK client.

Non-2xx HTTP status codes are **not** mapped to typed errors -- unlike ADR
0064's LLM transport. ADR 0026's own result shape is a plain
response-or-transport-error variant with no status-code-to-error mapping in
`http.cljc` itself, and this transport does not add one on its own
initiative; a 404 or 500 response is returned as an ordinary `:ok` result
with that status, exactly as a real HTTP client library would report it to
a caller that asked for the raw response. `:error` in this transport's
output is reserved for cases where THIS namespace itself refused to
connect (destination-blocked) or a genuine network/IO exception occurred.

### Authentication / credentials

Out of scope for this transport, by design, matching ADR 0026 v1's own
declared scope ("Async requests, cancellation, streaming, DNS/IP policy,
additional methods, and credentials require later versioned kits rather
than silently widening v1"). Whatever headers the guest supplies (bounded,
validated, already admitted by `http.cljc`) are forwarded verbatim; this
transport adds no credential injection of its own.

### Optional audit/observability hook

`:on-call` is an optional `(fn [event-map])` invoked after every attempt
with `{:url :status :error? :latency-ms}`. Exceptions raised by this hook
are swallowed and never affect the HTTP call. This is additive, not a new
required contract -- matching ADR 0064's own `:on-call` rationale (no
capability kit in this repo mandates quota/audit wiring at the
transport-construction layer today).

### `:cljs` is an explicit, documented gap, not a silent one

`production-transport` is `#?(:clj ...)` for the real implementation and
`#?(:cljs ...)` for a stub that throws a clear "not yet implemented"
message, for the identical reason as ADR 0064: every reference provider's
`:transport` contract in this repo is a plain synchronous `(fn [request] ->
reply)`, `java.net.http.HttpClient.send` is genuinely blocking (fits that
contract), and nbb/Node's `fetch` is Promise-based (faking synchrony over it
is a separate, larger, independently-reviewable design decision this task
does not make unilaterally on cljs's behalf).

## Evidence

- **Unit test suite** (`test/kotoba/compiler/http_transport_test.clj`, 16
  `deftest` / 51 assertions), split into two groups by design (see the test
  namespace's own docstring for the full rationale):
  1. Pure/deterministic unit tests of the security-critical private helpers,
     with NO network at all: `canonical-origin` (HTTPS-only, fragment
     rejection, case-folding), `destination-blocked?`/`private-address?`
     (loopback, link-local including the cloud-metadata address, RFC1918
     site-local, IPv6 unique-local `fc00::/7`, multicast, and a TEST-NET-3
     literal correctly NOT blocked, plus an unresolvable hostname correctly
     NOT treated as blocked at this layer), `redirect-target` (relative
     resolution, absolute passthrough, malformed input never throws,
     oversized target refused), `truncate-to-byte-limit`, and
     `bounded-response-headers` (count cap, case-fold collapsing, value
     truncation).
  2. End-to-end tests against a local `com.sun.net.httpserver.HttpServer`
     fake (same technique as ADR 0064's `llm_transport_test.clj`), through
     the full typed `http/provider` boundary: successful POST with
     request/response headers and body verified on both sides; a non-2xx
     status (404) returned as a plain `:ok` response, not a typed error
     (explicitly proving the different-from-LLM semantic above); a
     same-origin redirect followed to completion (two real hops observed);
     a redirect to an origin outside the allow-list NOT followed (the 3xx
     itself returned, only the first hop ever requested); `:max-redirects
     0` disabling following entirely; the REAL (non-redefed)
     `destination-blocked?` check refusing a loopback fixture end-to-end
     with a typed error; a restricted header (`host`) silently dropped
     without breaking the call while an ordinary header still reaches the
     server; construction-time rejection of an empty or non-canonical
     `:allowed-origins`; and the `:on-call` audit hook observing every
     attempt (including when the hook itself throws) without affecting the
     result.

     A local test fixture is intrinsically plaintext HTTP on loopback. Both
     of this namespace's own SSRF defenses (HTTPS-only scheme, no-loopback)
     would correctly refuse that in real usage -- exactly what group 1
     already proves in isolation -- so group 2 uses `with-redefs` to relax
     PRECISELY those two checks (never the allow-list MEMBERSHIP check
     itself, which is exercised for real in every test) for the duration of
     a given test, so the real redirect-loop/header/body/timeout/allow-list
     logic can be exercised end-to-end against a local fixture. This
     required widening not only this namespace's own `canonical-origin` but
     also `kotoba.compiler.provider.http`'s own private `https-origin` (via
     `with-redefs` on its private var, valid on the JVM even across
     namespaces) -- `http/provider` validates its own `:allowed-origins`
     construction option and the guest's request URL independently of this
     transport, and would otherwise reject the plaintext fixture origin
     before this transport's own code ever ran.
  - No live-network integration test is included in this ADR (unlike ADR
    0064's `real-murakumo-main-endpoint-answers`, gated behind an env var
    against ONE fixed, known-good endpoint). There is no single fixed
    "real" HTTPS endpoint this capability's design would make sense to pin
    a test against -- the guest names an arbitrary allow-listed origin, so
    a genuine live-network test here would need either a stable, dedicated
    third-party HTTPS echo endpoint (an external dependency this test suite
    does not otherwise take) or a self-signed local HTTPS server (rejected
    as unnecessary complexity for this task -- see Remaining gaps item 4).
- Full `clojure -M:test` suite with this ADR's changes applied, run from
  this branch: see the PR description for the exact pass/fail/error counts
  from the run performed at PR-open time; this ADR's own 16 new tests
  contribute 0 failures / 0 errors.

## Remaining gaps

1. **DNS-rebinding is not fully closed.** `destination-blocked?` resolves
   the target host and rejects known-private/loopback/link-local ranges
   BEFORE connecting, but the actual `HttpClient.send` call that follows
   performs its OWN independent DNS resolution -- if an attacker controls
   authoritative DNS for an allow-listed hostname with a very short TTL,
   they could in principle return a public/safe address for THIS
   namespace's check and then a private address for the JDK's own
   subsequent connect (a classic TOCTOU race). Fully closing this would
   require either pinning the exact resolved address for the actual
   connection (not straightforwardly available through
   `java.net.http.HttpClient`'s public API without either weakening TLS
   hostname verification -- a strictly worse mistake -- or introducing a
   custom low-level resolver/socket-factory layer judged out of scope for
   this task) or enforcing destination-IP policy at the network/egress
   layer outside this JVM process entirely (the standard real-world
   mitigation for this class of problem). This is a genuine, known,
   documented limitation, not silently claimed as solved.
2. **IPv4-mapped/compatible IPv6 literal edge cases are not specially
   unwrapped** beyond whatever `java.net.InetAddress`'s own built-in
   `isXxxAddress()` methods already normalize. An address expressed as an
   unusual literal form that Java's own address-classification methods
   don't recognize as private could in principle slip past
   `private-address?`. This was judged a narrow, low-likelihood residual
   given DNS resolution (not literal-parsing) is the actual code path in
   normal use.
3. **No retry/backoff policy is implemented.** Matches every other
   capability kit's provider in this repo -- a redirect not followed, a
   non-2xx status, or a network exception are all surfaced to the CALLER
   (the guest `.kotoba` application or its host), which decides whether and
   how to retry.
4. **No live-network integration test against a real third-party HTTPS
   endpoint is included** (see Evidence). A genuinely live end-to-end proof
   analogous to ADR 0064's would need either a stable dedicated external
   test endpoint or a self-signed local HTTPS server fixture (`keytool` +
   `com.sun.net.httpserver.HttpsServer` + a JVM-wide `SSLContext.setDefault`
   override scoped to a single test) -- judged more infrastructure than this
   task's scope warranted given the unit test suite already exercises the
   real wire protocol (headers, body, status codes, redirect-following)
   against a real (if plaintext) local HTTP server. A follow-up ADR could
   add this.
5. **This transport is not reviewed for production security hardening**
   beyond what is described above. It is a genuine first real-network
   provider for `:http/post` -- a milestone beyond every prior wiring-only
   reference-provider transport in this repo -- but not an audited,
   penetration-tested deployment artifact.
6. **Response header handling has known, documented lossy simplifications**
   (see `bounded-response-headers`'s own docstring): header names are
   case-folded (so two differently-cased instances of the same header from
   a misbehaving upstream would collide, with an unspecified winner); when
   a name repeats, only its first value is kept (relevant to e.g. multiple
   `Set-Cookie` response headers); and which headers survive the
   `max-headers` (32) cap when a real response exceeds that count is
   unspecified order, not a guest-visible priority.
7. **Redirect method handling does not implement the legacy 301/302/303
   POST-to-GET downgrade** some HTTP clients (and browsers) perform for
   historical reasons. Since ADR 0026 v1 is POST-only (there is no "GET"
   shape in this capability's typed contract at all), every followed
   redirect -- 301/302/303/307/308 alike -- re-issues the SAME POST with the
   SAME body to the new (allow-listed) location. This is a deliberate
   simplification, not an oversight: implementing method downgrade would
   require inventing a capability-level concept ("this hop was a GET") that
   ADR 0026 does not define.

## Related

- ADR 0026 (bounded HTTP capability kit v1) -- the schema and bounds this
  transport is a `:transport` for; unmodified by this ADR.
- ADR 0064 (production LLM transport) -- this ADR's own structural template
  (Decision/Evidence/Remaining gaps/Related, fake-server-first testing,
  `:on-call` audit-hook convention, `:cljs` gap framing) and the direct
  point of contrast for why HTTP's threat model (guest-chosen destination)
  differs from LLM's (host-fixed destination).
- ADR 0049 (component/application-language gap ledger) -- names "production
  provider Components for all nine capabilities" as next-completion-order
  item 2. This ADR is a step toward that for `:http/post` specifically, at
  the JVM/Chicory reference-provider layer (not yet the WASM Component
  Model layer ADR 0037-0063 builds toward) -- see that ADR's own updated
  ledger entry (Progress addendum) for the precise scope of what remains.
- `resources/kotoba/lang/capability-kits/http-v1.edn` -- the capability
  kit's own machine-readable semantics
  (`:redirects :transport-must-not-follow-outside-allowlist`) this ADR's
  redirect-loop design implements rather than reinterprets.
