# ADR 0071: Production transport for the storage capability kit, delegating durability entirely to a host-configured key/value HTTP endpoint

Status: accepted; production `:clj` transport implemented and unit/integration-tested against local fake servers; `:cljs` remains an explicit, documented gap

## Decision

`kotoba.compiler.provider.storage/provider` (ADR 0028) has always taken its
`:transport` as a host-injected `(fn [request] -> reply)`. Every existing
test in this repo has only ever supplied an identity/fixture transport
(`provider_conformance_test.clj`'s shared fixture list still constructs
`storage/provider` with `:transport (fn [_] {:tag :error :error {:code
:storage/disabled ...}})`; ADR 0049's own gap ledger names `storage` as one
of the remaining "identity wiring fixtures, not implementations"). This ADR
adds the first REAL transport for `:storage/transact`:
`kotoba.compiler.provider.storage-transport/production-transport`, a
`:clj`-only synchronous function backed by `java.net.http.HttpClient`.

`storage.cljc` itself is unmodified. Every bound it already enforces before
calling the transport -- bounded keyword keys, 65536-byte-bounded string
values, `[:option :i64]` conditional versions validated up front, backend
exceptions redacted to a generic typed error -- is unchanged and
un-weakened.

### Storage must not acquire ambient filesystem authority

`resources/kotoba/lang/capability-kits/storage-v1.edn` declares
`:ambient-filesystem false` directly in its machine-readable semantics, and
ADR 0049's own "Remaining provider and authority gaps" section states this
explicitly in prose too. A naive implementation of "make storage durable"
would reach for `java.io.File`/`clojure.java.io` and write bytes somewhere
under the process's home directory by default -- exactly the anti-pattern
this ADR avoids. This transport never touches the local filesystem at all.
It follows the same pattern ADR 0064 (LLM) and ADR 0066 (HTTP) already
established for their own capabilities: the HOST, not this namespace,
supplies a real synchronous transport; durability is delegated ENTIRELY to
whatever durable backend the host operates and explicitly points this
transport at.

Concretely: `production-transport` takes a REQUIRED `:endpoint` construction
option (or the `KOTOBA_STORAGE_ENDPOINT` env var) naming a host-operated
key/value HTTP service's origin, and forwards every `:storage/transact`
operation to it as a single fixed-path JSON POST. There is deliberately **no
default endpoint baked in** -- unlike ADR 0064's `murakumo-main` alias,
there is no repo-wide well-known storage backend for this namespace to
default to, and forcing explicit host configuration (construction throws if
neither `:endpoint` nor the env var is given) is itself part of the "no
ambient authority" design, not an oversight. A host that wants an in-process
or in-memory transport instead (for a test, or a deployment that genuinely
has no durable requirement) can still supply its own `:transport` fn
directly to `storage/provider`, exactly as before -- this ADR adds one more
option, not the only option.

This was a deliberate design choice among the three sketched in the task
brief:
(a) a bounded read/write scoped to one host-injected directory handle,
(b) an in-memory default with durability fully delegated to a host-injected
    transport (this ADR's choice, matching LLM/HTTP), or
(c) some other host-owned persistence mechanism.
(a) was rejected even though a single fixed host-chosen directory would
also avoid *guest*-controlled paths: it would still add a NEW kind of
authority to the compiler process itself (local disk I/O) that this
namespace does not need in order to satisfy ADR 0028/0049's contract, and
the task's own guidance is explicit that "if judgment is unclear, choose the
side that adds no ambient authority" -- which for storage means the same
network-delegation shape LLM and HTTP already use, not a new filesystem
code path. (b) required no discussion once (a) was rejected: `storage.cljc`
already has the full transport-injection seam this ADR needed; nothing in
`storage.cljc` itself needed to change.

### Wire shape -- one fixed JSON-over-HTTP operation, not a filesystem-shaped path per key

A single fixed path (`/storage/v1/transact` by default, overridable via
`:path`) is POSTed to for every operation. `namespace`, `operation`, `key`,
`value` (put only), and `expected_version` (put/delete only) all travel in
a JSON request body -- never in the URL path. This is a deliberate
simplification versus, say, encoding the key into a URL path segment: keys
are guest-controlled bounded strings, and folding them into a path invites
percent-encoding/path-traversal edge cases (`../`, encoded slashes, etc.)
that a fixed-path JSON-body design sidesteps entirely, since no path
segment is ever built from guest-controlled bytes. The expected response is
a 200 status with a JSON body carrying a `tag` field
(`found`/`missing`/`written`/`deleted`/`conflict`/`error`) plus that tag's
own fields (`value`/`version`, or `current_version` for `conflict`, or a
nested `error` object) -- deliberately NOT re-echoing the request `key`,
since `storage.cljc`'s own `typed-result` already has it from the original
request and does not need the wire protocol to round-trip it.

### No guest-facing SSRF surface, unlike ADR 0066's HTTP transport

`:storage/transact` has no guest-supplied destination at all:
`storage-namespace` is fixed once per provider instance by the HOST at
construction (`storage.cljc`'s own docstring: "The namespace is host-owned
and never supplied by guest code"), and this transport's own `:endpoint` is
likewise fixed once by the host at construction. This makes storage's
threat model like ADR 0064's LLM transport (one fixed, host-chosen
destination) rather than ADR 0066's HTTP transport (an arbitrary
guest-named destination bounded only by an allow-list) -- so this namespace
has no redirect-revalidation loop, no destination-IP block, and no
allow-list. There is exactly one destination, chosen entirely by the host,
never influenced by guest input.

### Fail-closed sanitization of the upstream reply

`storage.cljc`'s own post-transport checks (`entry`'s
`value/bounded-string!` on the value, `valid-version?` on the version) run
OUTSIDE `invoke-transport`'s try/catch -- exactly like `http.cljc`'s
post-transport header/body checks (ADR 0066 point 3) -- so an oversized
value or an out-of-range/non-integer version in the upstream JSON response
would otherwise throw an unhandled exception past that boundary, the first
time a real backend misbehaved even slightly. This namespace's
`finalize-reply` defends against that:

- an oversized `value` is truncated to `storage/max-value-bytes` (same
  `truncate-to-byte-limit` technique as ADR 0066's `decode-bounded-body`);
- an invalid `version`/`current-version` (non-integer, or less than 1)
  becomes an explicit, non-retryable `:storage/invalid-response` typed
  error, rather than fabricating a plausible-looking version number.
  Fabricating a version was considered and rejected: a wrong-but-plausible
  version could silently mask a real conditional-version conflict (the
  entire point of ADR 0028's "atomic version checks"), which is a strictly
  worse failure mode than a typed, visible error that the guest or its host
  can see and act on.

An upstream error code/message is bounded the same way
(`bounded-error-code` truncates to `value/keyword-value-byte-limit` rather
than letting `storage.cljc`'s own `error` function's
`value/bounded-keyword!` throw on an oversized code past the same
try/catch-external boundary).

### Non-2xx HTTP status -> a typed retryable/non-retryable error

Same status-code taxonomy as ADR 0064's LLM transport's `error-for-status`:
`429`/`5xx` are `:retryable true`; `401`/`403`/`404` map to specific
non-retryable codes; anything else non-2xx is `:storage/request-rejected`.
Unlike ADR 0066's HTTP transport (whose capability has NO status-to-error
mapping in `http.cljc` itself, so a non-2xx is an ordinary `:ok` response),
`storage.cljc`'s result variant has no "raw HTTP response" case at all --
every non-`:found`/`:missing`/`:written`/`:deleted`/`:conflict` outcome must
become a typed `:error`, so mapping non-2xx statuses here (rather than
passing them through) is required by the capability's own shape, not a
transport-level policy choice.

### Authentication / credentials

An optional `:api-key` (or `KOTOBA_STORAGE_API_KEY` env var) is sent as a
bearer token, matching ADR 0064's LLM transport's own optional-credential
shape. Whatever the host configures here never touches guest code -- the
guest's own request never carries or sees a credential, matching ADR 0028's
"backend exceptions and credentials are redacted" wording.

### Optional audit/observability hook

`:on-call` is an optional `(fn [event-map])` invoked after every attempt
with `{:namespace :operation :key :status :http-status :latency-ms}`
(`:status` is `:ok` or `:http-error`). Exceptions raised by this hook are
swallowed and never affect the storage call -- additive, matching ADR
0064/0066's own `:on-call` rationale (no capability kit in this repo
mandates quota/audit wiring at the transport-construction layer today).

### `:cljs` is an explicit, documented gap, not a silent one

`production-transport` is `#?(:clj ...)` for the real implementation and
`#?(:cljs ...)` for a stub that throws a clear "not yet implemented"
message, for the identical reason as ADR 0064/0066: every reference
provider's `:transport` contract in this repo is a plain synchronous `(fn
[request] -> reply)`, `java.net.http.HttpClient.send` is genuinely blocking
(fits that contract), and nbb/Node's `fetch` is Promise-based (faking
synchrony over it is a separate, larger, independently-reviewable design
decision this task does not make unilaterally on cljs's behalf).

## Evidence

- **Unit test suite** (`test/kotoba/compiler/storage_transport_test.clj`),
  split into two groups by design (mirroring `http_transport_test.clj`'s own
  docstring rationale):
  1. Pure/deterministic unit tests of the private helpers directly, with NO
     network at all: `kw->wire` (qualified/unqualified keyword -> wire
     string), `truncate-to-byte-limit`, `resolve-endpoint` (an explicit
     `:endpoint` wins; missing both `:endpoint` and the env var throws --
     proving there is no ambient default), `wire->reply` (every tag mapped
     correctly, an unknown tag rejected), `finalize-reply` (an oversized
     value truncated instead of crashing; a non-integer/zero version, or a
     malformed `current-version`, becomes a typed `:storage/invalid-response`
     error instead of a fabricated version), `bounded-error-code`
     (truncates, never throws), and `error-for-status` (status-code ->
     retryable/code taxonomy).
  2. End-to-end tests against a local `com.sun.net.httpserver.HttpServer`
     fake implementing a small, genuinely stateful in-memory key/value
     backend that speaks this namespace's own wire protocol (not just
     one-shot canned replies) -- through the full typed
     `storage/provider` boundary: a put-then-get round trip; get of an
     absent key returning `:missing`; a version-mismatched put returning a
     typed `:conflict` (not an error); delete-then-get showing the key is
     gone; an oversized upstream value truncated rather than crashing; a
     malformed upstream version becoming a typed `:storage/invalid-response`
     error rather than crashing; a non-2xx (503) status mapped to a typed
     retryable error; a non-JSON response body redacted to the generic
     `:storage/transport` error (the same path an ordinary network
     exception takes); the configured `:api-key` observed as a
     `Bearer` `authorization` header by the fake backend; and the
     `:on-call` audit hook observing every attempt (including when the hook
     itself throws) without affecting the result.
  - No live-network integration test against an already-deployed real
    endpoint is included, unlike ADR 0064's `real-murakumo-main-endpoint-
    answers` (gated behind an env var against ONE fixed, known-good,
    already-live endpoint). There is no repo-wide well-known storage
    backend this transport's wire protocol was designed to be pinned
    against -- see Remaining gaps.
- Full `clojure -M:test` suite, run from this branch: 545 tests, 4811
  assertions, 0 failures, 0 errors -- including this ADR's own new
  `storage-transport-test` namespace (now registered in
  `test/kotoba/compiler/test_runner.clj` alongside the sibling
  `http-transport-test`/`llm-transport-test` namespaces).

## Remaining gaps

1. **No live-network integration test against an already-deployed real
   storage backend.** Unlike LLM's `murakumo-main`, there is no existing
   repo-wide "the" storage service today. If/when one is stood up
   (e.g. a Cloudflare Worker fronting Workers KV/D1 speaking this
   namespace's wire protocol), a follow-up ADR should add an env-gated
   integration test against it, matching ADR 0064's own pattern.
2. **The wire protocol this transport speaks is this namespace's own
   design, not an existing external standard.** A host wanting to point
   this transport at a backend that speaks a DIFFERENT wire shape (e.g. an
   existing Redis-over-HTTP proxy, or a cloud KV REST API with its own
   conventions) would need an adapter service in front of it, or a
   different transport namespace entirely. This transport does not attempt
   to be a universal client for every KV-shaped HTTP API.
3. **No retry/backoff policy is implemented**, matching every other
   capability kit's provider in this repo -- a conflict, a non-2xx status,
   or a network exception are all surfaced to the CALLER (the guest
   `.kotoba` application or its host), which decides whether and how to
   retry.
4. **This transport is not reviewed for production security hardening**
   beyond what is described above (TLS is the host's own responsibility via
   its chosen `:endpoint` scheme; this namespace does not itself enforce
   HTTPS-only, unlike ADR 0066's guest-facing allow-list, because the
   destination here is entirely host-chosen, not guest-influenced). It is a
   genuine first real-network provider for `:storage/transact` -- a
   milestone beyond the prior wiring-only reference-provider transport --
   but not an audited, penetration-tested deployment artifact.
5. **No connection pooling/keep-alive tuning, circuit-breaking, or
   quota/rate-limit enforcement is implemented at the transport layer** --
   `storage-v1.edn`'s own `:limits {:namespace-quota :host-provider-required}`
   already frames quota enforcement as the host-supplied backend's job, not
   this transport's; this transport only shapes and forwards requests.
6. **This ADR does not touch the WASM Component Model layer** (ADR chain
   0037-0063) at all -- it closes a gap only at the JVM/Chicory
   reference-provider layer, the same scope ADR 0064/0066 closed for
   LLM/HTTP respectively. `storage-v1.edn`'s `:qualification` map
   (`{:reference :implemented :wasm-aot :pending :native-aot :pending :jit
   :pending}`) is unchanged by this ADR.

## Related

- ADR 0028 (bounded storage capability kit v1) -- the schema and bounds this
  transport is a `:transport` for; unmodified by this ADR.
- ADR 0064 (production LLM transport) -- this ADR's own structural template
  (Decision/Evidence/Remaining gaps/Related, fake-server-first testing,
  `:on-call` audit-hook convention, `:cljs` gap framing, required-explicit-
  endpoint-with-no-ambient-default resolution) and the direct point of
  contrast for storage's own fixed-destination (not guest-chosen) threat
  model.
- ADR 0066 (production HTTP transport) -- the sibling ADR closing the same
  kind of gap for a different capability in the same PR-adjacent timeframe;
  this ADR's `finalize-reply`/bounded-response-decoding design directly
  mirrors ADR 0066's `bounded-response-headers`/`decode-bounded-body`
  rationale (defending a capability's own post-transport checks, which run
  outside `invoke-transport`'s try/catch, from an unhandled exception on an
  ordinary real-world response).
- ADR 0067 (state provider has no transport gap) -- the ADR that correctly
  distinguished `state` (in-process, ephemeral, no transport seam) from
  `storage` (durable, namespace-scoped, host-supplied-transport) and
  explicitly named `storage` as still open; this ADR is that follow-up.
- ADR 0049 (component/application-language gap ledger) -- names "production
  provider Components for all nine capabilities" as next-completion-order
  item 2, and states storage's own "must not acquire ambient filesystem
  authority" constraint directly; this ADR is a step toward that item for
  `:storage/transact` specifically, at the JVM/Chicory reference-provider
  layer (not yet the WASM Component Model layer) -- see that ADR's own
  updated ledger entry (Progress addendum) for the precise scope of what
  remains.
- `resources/kotoba/lang/capability-kits/storage-v1.edn` -- the capability
  kit's own machine-readable semantics (`:ambient-filesystem false`,
  `:namespace :host-fixed-per-provider`,
  `:durability :transport-commit-before-result`,
  `:concurrency :conditional-version`) this ADR's transport design
  implements rather than reinterprets.
