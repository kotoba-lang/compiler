# ADR 0064: Production transport for the LLM capability kit, wired to the repo-wide murakumo-main fleet alias

Status: accepted; production `:clj` transport implemented and verified live; `:cljs` remains an explicit, documented gap

## Decision

`kotoba.compiler.provider.llm/provider` (ADR 0027) has always taken its
`:transport` as a host-injected `(fn [request] -> reply)`. Every existing
test and every capability kit in this ADR chain has only ever supplied an
identity/fixture transport (ADR 0049's gap ledger: "current providers are
identity wiring fixtures, not implementations" of the nine application
capabilities). This ADR adds the first REAL transport for `:llm/generate`:
`kotoba.compiler.provider.llm-transport/production-transport`, a `:clj`-only
synchronous function that performs the actual network call to the
`murakumo-main` fleet alias described in the superproject's root `CLAUDE.md`
("LLM モデル選択", ADR-2607173100).

`llm.cljc` itself is unmodified. Every bound it already enforces before
calling the transport -- system/prompt byte limits, output-token budget,
temperature range, model allow-list -- is unchanged and un-weakened.

### Model/endpoint resolution (the mandatory ①→②→③ order)

1. **① env var / constructor-option override.** `KOTOBA_LLM_MURAKUMO_ENDPOINT`
   / `KOTOBA_LLM_MURAKUMO_MODEL`, or `:endpoint-override` / `:model-override`
   passed to `production-transport`. Either one present skips network
   resolution entirely.
2. **② `murakumo-main` alias resolution.** `GET {default-endpoint}
   /infer/models/murakumo-main` and read its `:alias-for` field for the
   concrete currently-serving model name (purely informational/audit --
   confirmed live that sending either the literal alias name or the
   resolved concrete id as `model` both work identically, see Evidence).
3. **③ endpoint-only fallback.** If the GET fails (network error, non-200,
   missing field), bake ONLY the default gateway endpoint and send the
   LITERAL alias string `"murakumo-main"` as `model` -- never a concrete
   model id -- so the `/v1/messages` worker resolves it server-side via its
   own KV. This still tracks a future fleet-main swap with zero redeploy on
   the caller's side, exactly like a successful ② resolution would.

**The wire endpoint this namespace calls is ALWAYS the default gateway
(`https://api.murakumo.cloud`) or an explicit override -- never the alias
entry's own `:endpoint` field.** This was not an assumption; it was
discovered by hitting a real, wrong-shaped 404 during development (see
Evidence). The alias entry's own `:endpoint` (e.g. `https://infer.murakumo
.cloud/v1/chat/completions`) points at a DIFFERENT host serving the OpenAI
chat.completions shape -- the route `70-tools/bmc/src/gftd/murakumo.cljc`
already targets, a separate consumer this ADR does not touch. The alias
entry's own `note` field confirms both routes are independently valid
("consumers resolve THIS entry via GET /infer/models/murakumo-main or send
model=murakumo-main to /v1/messages") -- this namespace always takes the
second form.

### Wire shape

`POST {endpoint}/v1/messages` with the Anthropic Messages API request shape
(`model`, `max_tokens`, `system` when non-blank, `messages: [{role: "user",
content: <prompt>}]`, `temperature` as `temperature-milli / 1000.0`), parsing
the same shape back: `content` (concatenate `type="text"` blocks -- a
thinking-only or refused reply legitimately yields `""`, never `nil`),
`stop_reason` (sanitized into a bounded kotoba keyword), and `usage.
{input,output}_tokens`.

Non-2xx HTTP responses become typed `{:error {:code ... :message ...
:retryable ...}}` replies (429 → `:llm/rate-limited` retryable; ≥500 →
`:llm/upstream-error` retryable; 401/403/404 → non-retryable; anything else →
`:llm/request-rejected`) rather than thrown exceptions -- `llm.cljc`'s own
`invoke-transport` only catches exceptions as a last-resort generic
`:llm/transport` fallback, so a typed HTTP-status error here carries more
useful (and correct) `:retryable` information than that fallback could.
Network/IO exceptions (DNS failure, connection refused, timeout) are
deliberately left to propagate into that existing generic catch rather than
duplicated here.

### Authentication

An optional bearer token (`:api-key` constructor option, or
`MURAKUMO_API_KEY` env var) is sent as `Authorization: Bearer <token>` when
present. Verified live (see Evidence) that `api.murakumo.cloud/v1/messages`
returns `401 invalid x-api-key / bearer token` without one -- so in practice
a caller of the real fleet gateway needs a key, even though this namespace
does not hard-require one (a differently-configured/self-hosted deployment
of the same worker shape could be open). The known onboarding path for a new
`/v1/messages` consumer is the existing kagi-vault secondary token slot
(`MURAKUMO_CRITIC_TOKEN`, compartment `gftdcojp` -- per the repo's secrets
location map: "新しい `/v1/messages` consumer も同じ secondary スロットで
rotation なしにオンボードできる"), used for the live verification below and
documented as the credential source for anyone wiring this transport into a
running host.

### Optional audit/observability hook

`:on-call` is an optional `(fn [event-map])` invoked after every attempt with
`{:resolution :wire-model :status :input-tokens :output-tokens :latency-ms}`
(`:status` ∈ `:ok` / `:http-error`; network exceptions bypass it, per above).
Exceptions raised by this hook are swallowed and never affect the LLM call.
This is additive, not a new required contract: no capability kit in this ADR
chain mandates quota/audit wiring at the transport-construction layer today
-- ADR 0027's own docstring and the `storage.cljc`/`log.cljc` reference
providers frame quota and audit as host responsibility, not a required
parameter -- so `:on-call` is offered as a convenience for a host that wants
one, not retrofitted onto `llm.cljc` or any other provider.

### `:cljs` is an explicit, documented gap, not a silent one

`production-transport` is `#?(:clj ...)` for the real implementation and
`#?(:cljs ...)` for a stub that throws a clear "not yet implemented" message.
Every reference provider's `:transport` contract in this repo
(`kotoba.compiler.reference-runtime`) is a plain synchronous `(fn [request]
-> reply)` -- there is no promise/callback plumbing for a provider to return
through. `java.net.http.HttpClient.send` is genuinely blocking, which fits
that contract exactly. nbb/Node's `fetch` is Promise-based; faking synchrony
over it (busy-polling, a hand-rolled microtask pump) is a separate, larger,
independently-reviewable design decision this task does not make unilaterally
on cljs's behalf. This mirrors the repo's own JVM/Chicory-first host pattern
for prior capability kits (ADR 0024-0030) and the root CLAUDE.md runtime
priority note that JVM app-runtime status is downgraded for *application*
code, not for host-side compiler-repo infrastructure written in this
`.cljc` reference-provider style.

## Evidence

- **Real, live 2026-07-24 verification against the production
  `api.murakumo.cloud` deployment** (not a fixture, not a fake server):
  - `GET https://api.murakumo.cloud/infer/models/murakumo-main` returns the
    live alias entry: `{"id":"murakumo-main","format":"alias","alias-for":
    "qwen3.6-35b-a3b","endpoint":"https://infer.murakumo.cloud/v1/chat/
    completions","status":"serving", ...}`.
  - `POST https://api.murakumo.cloud/v1/messages` with `{"model":
    "murakumo-main", ...}` and no `Authorization` header returns `401
    {"error":{"type":"authentication_error","message":"invalid x-api-key /
    bearer token"}}` -- confirms the endpoint is real and live, and that an
    API key is required in practice.
  - The SAME request with `Authorization: Bearer <MURAKUMO_CRITIC_TOKEN>`
    (kagi vault, compartment `gftdcojp`) returns `200` with a genuine
    Anthropic-Messages-shaped reply (`content`/`stop_reason`/`usage`).
  - **The exact bug this ADR's design fixes, caught live, not by
    inspection:** an earlier version of `resolve-model` used the alias
    entry's own `:endpoint` field (`https://infer.murakumo.cloud/v1/chat/
    completions`) as the base for THIS namespace's `/v1/messages` POST,
    producing the URL `https://infer.murakumo.cloud/v1/chat/completions/v1
    /messages` -- a real `404 {"error":{"message":"File Not Found",...}}`
    from the live server. Fixed by never routing on the alias entry's
    `:endpoint`; the wire endpoint is always the default gateway or an
    explicit override (see Decision).
  - Sending either the literal alias name `"murakumo-main"` or the resolved
    concrete id `"qwen3.6-35b-a3b"` as `model` to `api.murakumo.cloud
    /v1/messages` both succeed identically (both verified live, `200`),
    confirming step ③'s literal-alias fallback is not a degraded path.
  - A full round trip through `kotoba.compiler.provider.llm/provider` (typed
    KIR `typed-cap-call` boundary, not a bare HTTP call) with
    `production-transport` wired as `:transport`, no overrides (i.e.
    exercising the real ② alias-resolution branch end to end), returned
    `[llm/result-type :ok [llm/completion-type "pong\n</think>\n\npong"
    :end_turn [llm/usage-type 37 7]]]` for the prompt "Reply with exactly
    the word: pong".
- **Unit test suite** (`test/kotoba/compiler/llm_transport_test.clj`, 12
  `deftest` / 13 assertions, all against local
  `com.sun.net.httpserver.HttpServer` fakes -- no real network, no live
  credential needed for normal `clojure -M:test` runs):
  - ① override resolves with zero network calls.
  - ② alias-entry parsing is exercised directly against a fake alias server,
    asserting the `:endpoint` field is present in the parsed response (so a
    future regression back to routing on it would be visible) while
    `resolve-model` itself never uses it for the outgoing request.
  - ③ fallback triggers deterministically against an unroutable TEST-NET-1
    address (`192.0.2.1`, RFC 5737) with a short connect timeout -- no real
    network dependency, no flakiness.
  - Full success round trip through the typed `llm/provider` boundary.
  - Empty-content (thinking-only / refused) replies yield `""`, not `nil`.
  - HTTP 429 → `:llm/rate-limited` (retryable), 401 → `:llm/unauthorized`
    (non-retryable), 500 → `:llm/upstream-error` (retryable).
  - The `Authorization: Bearer <api-key>` header is sent verbatim when
    `:api-key` is configured.
  - The `:on-call` audit hook observes exactly one event per attempt and
    does not affect the result; an exception thrown from the hook itself is
    swallowed and does not break the call.
  - A REAL integration test (`real-murakumo-main-endpoint-answers`) exists in
    the same file but is gated behind `KOTOBA_LLM_INTEGRATION_TEST=1` AND a
    set `MURAKUMO_API_KEY` -- absent either, it prints a skip message and
    passes trivially, so ordinary `clojure -M:test` runs never depend on
    network access or a live credential.
- Full `clojure -M:test` suite with this ADR's changes applied: 479 tests,
  4606 assertions, 1 pre-existing failure unrelated to this change
  (`cli_test.clj`'s `structured-diagnostic-has-stable-code-and-bounded-
  source-span`, a source-span regression already fixed on `main` by PR #239
  "Preserve source spans through doseq annotation", which landed after this
  branch's fork point and is picked up once this branch merges current
  `main` -- confirmed by running the identical test against a fresh clone of
  `origin/main` HEAD, where it passes with 0 failures / 0 errors across 467
  tests). This ADR's own 12 new tests contribute 0 failures / 0 errors on
  both bases.

## Remaining gaps

1. **`:cljs`/nbb transport remains unimplemented**, by design (see Decision)
   -- not attempted here, and not silently claimed as done. A future ADR
   would need to either design an async provider-transport contract (a
   change to `kotoba.compiler.reference-runtime` and every reference
   provider's `:transport` shape, not just this one) or find/adopt a
   synchronous HTTP primitive for nbb.
2. **Streaming, tool use, and multi-turn conversation state remain out of
   scope**, unchanged from ADR 0027's own v1 scope declaration -- this
   transport is a single-shot `generate` call matching `llm.cljc`'s existing
   request/result shape exactly; it does not widen that shape.
3. **No retry/backoff policy is implemented in this namespace.** A 429/5xx
   reply is surfaced as a typed `:retryable true` error to the CALLER (the
   guest `.kotoba` application or its host), which must decide whether and
   how to retry; this transport does not retry on the host's behalf. This
   matches every other capability kit's provider in this repo, none of which
   implement caller-transparent retry either.
4. **This transport is not reviewed for production security hardening**
   beyond what is described above (bearer-token auth, HTTP/1.1 explicit
   pinning, bounded timeouts). It is a genuine, live-verified first
   real-network provider for `:llm/generate` -- a milestone beyond every
   prior wiring-only reference-provider transport in this repo -- but not an
   audited, hardened deployment artifact.
5. **The `murakumo-main` alias GET (② resolution) is cached per
   `production-transport` instance for `alias-cache-ttl-ms` (default 60s),
   not shared across instances or processes.** A caller constructing many
   short-lived `production-transport` instances would re-resolve on every
   construction; this is an acceptable default for the expected usage shape
   (one instance per long-lived host/provider registration), not a
   fundamental limitation, and is a straightforward follow-up if a different
   usage shape needs it.

## Related

- ADR 0027 (bounded LLM capability kit v1) -- the schema and bounds this
  transport is a `:transport` for; unmodified by this ADR.
- ADR 0049 (component/application-language gap ledger) -- names "production
  provider Components for all nine capabilities" as next-completion-order
  item 2. This ADR is a step toward that for `:llm/generate` specifically,
  at the JVM/Chicory reference-provider layer (not yet the WASM Component
  Model layer ADR 0037-0063 build toward) -- see that ADR's own updated
  ledger entry for the precise scope of what remains.
- Root superproject `CLAUDE.md`, "LLM モデル選択 — murakumo-main alias"
  (ADR-2607173100) -- the repo-wide mandatory model-resolution order this
  transport implements.
