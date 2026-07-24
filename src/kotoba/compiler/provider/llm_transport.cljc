(ns kotoba.compiler.provider.llm-transport
  "Production transport for the bounded LLM capability kit (ADR 0027,
  `kotoba.compiler.provider.llm`), wired to the repo-wide `murakumo-main`
  fleet alias (root superproject CLAUDE.md, 'LLM モデル選択 -- murakumo-main
  alias', ADR-2607173100).

  This namespace does NOT define a new provider or a new capability. It
  builds the one thing every provider in this ADR chain has always deferred
  to the host (ADR 0027: 'Each provider instance owns ... an injected
  synchronous transport'; ADR 0049's gap ledger: 'current providers are
  identity wiring fixtures, not implementations' of the nine application
  capabilities) -- a real synchronous `(fn [request] -> reply)` you pass as
  `:transport` to `kotoba.compiler.provider.llm/provider`. `llm.cljc` itself
  is untouched: every bound (string/output-token/temperature/model-allowlist)
  it already enforces before calling the transport is unchanged and un-
  weakened by this namespace.

  Model resolution follows the repo-wide mandatory order (CLAUDE.md, 'LLM
  モデル選択'): ① env var / constructor-option override → ② `murakumo-main`
  alias resolution (`GET {default-endpoint}/infer/models/murakumo-main` →
  its `:alias-for`, consulted only for the concrete model name) → ③ on
  failure, an endpoint-only fallback that bakes ONLY the default gateway
  endpoint and sends the literal alias name `\"murakumo-main\"` as `model`
  (never a concrete model id) so the `/v1/messages` worker resolves it
  server-side via its own KV -- this fallback still adapts to a model swap
  with zero redeploy, unlike baking a concrete id ever would. The wire
  ENDPOINT this namespace calls is always the default gateway (or an
  explicit override) -- never the alias entry's own `:endpoint` field, which
  (verified live 2026-07-24) points at a differently-shaped OpenAI
  chat.completions route on a different host; see `resolve-model`'s
  docstring for the full investigation.

  JVM only (`:clj`) for now. `java.net.http.HttpClient` gives a genuinely
  BLOCKING `.send`, which is what every reference provider's synchronous
  `(fn [request] -> reply)` transport contract in this repo assumes --
  `kotoba.compiler.reference-runtime` has no promise/callback machinery for a
  provider to return through. nbb/cljs has no built-in synchronous HTTP
  primitive (Node's global `fetch` is Promise-based), so faking synchrony
  there (busy-polling, a hand-rolled event-loop pump) would be a bigger,
  separately-reviewable undertaking than this task -- the `:cljs` branch
  below throws a clearly-labeled 'not yet implemented' instead of silently
  pretending to support it. See docs/adr/0064-production-llm-transport-murakumo-main.md."
  (:require [clojure.string :as string]
            #?(:clj [clojure.data.json :as json]))
  #?(:clj
     (:import (java.net URI)
              (java.net.http HttpClient HttpClient$Version HttpRequest
                             HttpRequest$BodyPublishers HttpResponse
                             HttpResponse$BodyHandlers)
              (java.time Duration))))

(def default-endpoint
  "The murakumo gateway. Fixed default for the ③ endpoint-only fallback --
  never itself a concrete model id."
  "https://api.murakumo.cloud")

(def alias-name "murakumo-main")
(def alias-path "/infer/models/murakumo-main")
(def messages-path "/v1/messages")

(def default-connect-timeout-ms 5000)
(def default-request-timeout-ms 60000)
(def default-alias-cache-ttl-ms
  "How long a resolved {:endpoint :model} pair is reused before re-resolving
  via ②. Keeps a hot loop from hitting the alias endpoint on every single
  `:llm/generate` call while still picking up a fleet-main swap (PUT to the
  alias entry, CLAUDE.md 'LLM モデル選択') within one TTL window."
  60000)

(def env-endpoint-var "KOTOBA_LLM_MURAKUMO_ENDPOINT")
(def env-model-var "KOTOBA_LLM_MURAKUMO_MODEL")
(def env-api-key-var "MURAKUMO_API_KEY")

;; ---------------------------------------------------------------------------
;; ① / ② / ③ resolution — endpoint + wire model, no I/O leaks into result
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- getenv [name]
     (let [v (System/getenv name)]
       (when (and v (seq (string/trim v))) v))))

#?(:clj
   (defn- http-get-json
     "GET `url` and parse a JSON object body to a keyword-keyed map. Returns
     nil on any failure (non-2xx, network error, unparsable body) -- callers
     treat nil as 'alias resolution unavailable, use fallback ③', never as an
     exception to propagate."
     [^HttpClient http-client url connect-timeout-ms]
     (try
       (let [req (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofMillis (long connect-timeout-ms)))
                     (.header "accept" "application/json")
                     (.GET)
                     (.build))
             resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
         (when (= 200 (.statusCode resp))
           (json/read-str (.body resp) :key-fn keyword)))
       (catch Exception _ nil))))

#?(:clj
   (defn resolve-model
     "Resolve {:endpoint <string> :model <string>} per the repo-wide mandatory
     order. `:model` is always a non-nil wire-ready string: either an explicit
     override, the alias entry's own `:alias-for`, or (fallback ③) the literal
     alias name `\"murakumo-main\"` -- never a concrete id baked by this
     namespace. `:resolution` in the returned map is one of `:override`,
     `:alias`, `:fallback` for observability/audit -- not sent over the wire.

     `:endpoint` is ALWAYS `default-endpoint` (or an explicit override) --
     never the `:endpoint` field from the alias GET response. Verified live
     2026-07-24 against the real `api.murakumo.cloud` deployment: the alias
     entry's own `:endpoint` (e.g. `https://infer.murakumo.cloud/v1/chat/
     completions`) points at a DIFFERENT host serving the OpenAI
     chat.completions shape (the route `70-tools/bmc/src/gftd/murakumo.cljc`
     targets) -- routing THIS namespace's Anthropic-Messages-shaped POST
     there 404s. The alias entry's own note text confirms both routes are
     independently valid ('consumers resolve THIS entry via GET
     /infer/models/murakumo-main OR send model=murakumo-main to
     /v1/messages') -- this namespace always takes the second form; the GET
     is consulted only for its `:alias-for` (the concrete model name, purely
     informational/audit -- confirmed live that sending either the literal
     alias name or the resolved concrete id as `model` to `api.murakumo.cloud
     /v1/messages` both succeed identically).

     opts:
       :endpoint-override / :model-override -- ① explicit, highest priority.
       :http-client -- required; a `java.net.http.HttpClient`.
       :connect-timeout-ms -- ② GET timeout, default `default-connect-timeout-ms`."
     [{:keys [http-client endpoint-override model-override connect-timeout-ms]
       :or {connect-timeout-ms default-connect-timeout-ms}}]
     (let [endpoint-override (or endpoint-override (getenv env-endpoint-var))
           model-override (or model-override (getenv env-model-var))]
       (cond
         ;; ① env / constructor override wins outright, no network call at all.
         (or endpoint-override model-override)
         {:endpoint (or endpoint-override default-endpoint)
          :model (or model-override alias-name)
          :resolution :override}

         ;; ② alias resolution via the murakumo-main KV entry -- consulted
         ;; only for :alias-for (the concrete model name). The endpoint we
         ;; call is always `default-endpoint`; see docstring above.
         :else
         (let [alias-url (str default-endpoint alias-path)
               resolved (http-get-json http-client alias-url connect-timeout-ms)
               resolved-model (:alias-for resolved)]
           (if resolved-model
             {:endpoint default-endpoint :model resolved-model :resolution :alias}
             ;; ③ endpoint-only fallback: bake the gateway only; the literal
             ;; alias name (not a concrete id) lets the worker resolve via its
             ;; own KV, so this still tracks a fleet-main swap automatically.
             {:endpoint default-endpoint :model alias-name :resolution :fallback}))))))

;; ---------------------------------------------------------------------------
;; wire request / response — Anthropic Messages API shape (murakumo's
;; documented live text-inference contract; see docs/adr/0064)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- request-body [{:keys [wire-model system prompt max-output-tokens
                                temperature-milli]}]
     (cond-> {"model" wire-model
              "max_tokens" (int max-output-tokens)
              "messages" [{"role" "user" "content" prompt}]
              "temperature" (/ (double temperature-milli) 1000.0)}
       (seq system) (assoc "system" system))))

#?(:clj
   (defn- extract-text
     "Concatenate every `type=\"text\"` content block. A refusal or an
     otherwise-empty completion yields \"\" (allowed by
     `value/bounded-string!` -- zero bytes is within any positive limit),
     never nil."
     [content-blocks]
     (->> content-blocks
          (filter #(= "text" (:type %)))
          (map #(:text % ""))
          (apply str))))

#?(:clj
   (defn- finish-reason-keyword
     "Map an upstream `stop_reason` string to a bounded kotoba keyword,
     defensively sanitized -- an upstream value this namespace doesn't
     control must never surface a byte-limit-violating or non-keyword-safe
     string past this boundary."
     [stop-reason]
     (if (string? stop-reason)
       (let [safe (-> stop-reason
                      (subs 0 (min (count stop-reason) 64))
                      (string/replace #"[^a-zA-Z0-9_.-]" "-"))]
         (if (seq safe) (keyword safe) :unknown))
       :unknown)))

#?(:clj
   (defn- token-count [usage k]
     (let [v (get usage k)]
       (if (and (integer? v) (<= 0 v)) v 0))))

#?(:clj
   (defn- truncate-for-error-message [s limit]
     (let [s (str s)]
       (if (> (count s) limit) (str (subs s 0 limit) "...") s))))

#?(:clj
   (defn- error-for-status
     "Non-2xx HTTP status -> a typed `{:error ...}` reply (never thrown --
     `kotoba.compiler.provider.llm/invoke-transport` only catches exceptions
     as a last-resort fallback; a real HTTP error is not exceptional here and
     carries useful `:retryable` information the generic catch can't)."
     [status body]
     (let [retryable? (or (= status 429) (>= status 500))
           code (case (int status)
                  429 :llm/rate-limited
                  401 :llm/unauthorized
                  403 :llm/forbidden
                  404 :llm/not-found
                  (if (>= status 500) :llm/upstream-error :llm/request-rejected))]
       {:error {:code code
                :message (str "murakumo transport HTTP " status ": "
                              (truncate-for-error-message body 400))
                :retryable retryable?}})))

#?(:clj
   (defn- send-messages-request
     [^HttpClient http-client {:keys [endpoint api-key request-timeout-ms]} body-map]
     (let [url (str endpoint messages-path)
           body-json (json/write-str body-map)
           builder (-> (HttpRequest/newBuilder (URI/create url))
                       (.timeout (Duration/ofMillis (long request-timeout-ms)))
                       (.header "content-type" "application/json")
                       (.header "anthropic-version" "2023-06-01")
                       (.POST (HttpRequest$BodyPublishers/ofString body-json)))
           builder (if api-key (.header builder "authorization" (str "Bearer " api-key)) builder)
           req (.build builder)
           resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode resp) :body (.body resp)})))

;; ---------------------------------------------------------------------------
;; public constructor
;; ---------------------------------------------------------------------------

#?(:clj
   (defn production-transport
     "Build a synchronous transport fn for
     `(kotoba.compiler.provider.llm/provider {:transport (production-transport ...) ...})`.

     Input (already bounds-checked and admitted by `llm.cljc` before this fn
     ever runs): `{:model <kw> :system <string> :prompt <string>
     :max-output-tokens <int> :temperature-milli <int>}`.

     Output: `{:text <string> :finish-reason <keyword> :input-tokens <int>
     :output-tokens <int>}` on success, or `{:error {:code <keyword>
     :message <string> :retryable <bool>}}` on a non-2xx HTTP response.
     Network/IO exceptions (DNS failure, connection refused, timeout) are
     deliberately NOT caught here -- they propagate to
     `kotoba.compiler.provider.llm/invoke-transport`'s own catch, which
     redacts them into a generic `:llm/transport` error. Catching them here
     too would just duplicate that fallback with less information; letting
     them bubble keeps this namespace's own error path focused on cases it
     can say something specific about (HTTP status, retryability).

     Options (all optional):
       :endpoint-override / :model-override -- ① explicit pin, also settable
         via KOTOBA_LLM_MURAKUMO_ENDPOINT / KOTOBA_LLM_MURAKUMO_MODEL env vars.
       :api-key -- optional bearer token (also MURAKUMO_API_KEY env var).
         The murakumo `/v1/messages` gateway has historically been reachable
         without one (`70-tools/bmc/src/gftd/murakumo.cljc` docstring: 'public
         try-it path'), so this stays optional rather than required.
       :connect-timeout-ms / :request-timeout-ms
       :alias-cache-ttl-ms -- default `default-alias-cache-ttl-ms`; 0 disables
         caching and re-resolves ② on every call.
       :on-call -- optional `(fn [event-map])` audit/observability hook,
         invoked after every attempt with `{:resolution :wire-model :status
         :input-tokens :output-tokens :latency-ms}` (`:status` is `:ok`,
         `:http-error`, or `:exception`). Exceptions raised by this hook are
         swallowed and never affect the LLM call -- best-effort observability
         only. No capability kit in this repo mandates quota/audit wiring at
         this layer today (ADR 0027's own docstring and
         `storage.cljc`/`log.cljc` frame quota+audit as host responsibility,
         not a required constructor parameter), so this hook is additive and
         optional, not a new required contract."
     ([] (production-transport {}))
     ([opts]
      (let [http-client (-> (HttpClient/newBuilder)
                            ;; Pin HTTP/1.1 explicitly: `java.net.http.HttpClient`
                            ;; defaults to attempting HTTP/2 (with an h2c
                            ;; upgrade over plaintext), which a plain
                            ;; HTTP/1.1-only server -- including the
                            ;; `com.sun.net.httpserver.HttpServer` fakes this
                            ;; namespace's own test suite uses -- cannot
                            ;; negotiate, surfacing as an opaque
                            ;; "HTTP/1.1 header parser received no bytes"
                            ;; IOException rather than a clean protocol
                            ;; error. Real murakumo TLS endpoints ALPN-
                            ;; negotiate fine either way, so this is a
                            ;; strictly-safer default, not a workaround
                            ;; narrowed to tests only.
                            (.version HttpClient$Version/HTTP_1_1)
                            (.connectTimeout (Duration/ofMillis
                                              (long (:connect-timeout-ms opts default-connect-timeout-ms))))
                            (.build))
            api-key (or (:api-key opts) (getenv env-api-key-var))
            request-timeout-ms (:request-timeout-ms opts default-request-timeout-ms)
            connect-timeout-ms (:connect-timeout-ms opts default-connect-timeout-ms)
            ttl (:alias-cache-ttl-ms opts default-alias-cache-ttl-ms)
            on-call (:on-call opts (fn [_]))
            cache (atom nil)
            resolve! (fn []
                       (let [now (System/currentTimeMillis)
                             cached @cache]
                         (if (and cached (pos? ttl) (< (- now (:at cached)) ttl))
                           (:resolved cached)
                           (let [resolved (resolve-model
                                           {:http-client http-client
                                            :endpoint-override (:endpoint-override opts)
                                            :model-override (:model-override opts)
                                            :connect-timeout-ms connect-timeout-ms})]
                             (reset! cache {:resolved resolved :at now})
                             resolved))))]
        (fn [{:keys [system prompt max-output-tokens temperature-milli]}]
          (let [{:keys [endpoint model resolution]} (resolve!)
                started (System/currentTimeMillis)
                body-map (request-body {:wire-model model
                                        :system system
                                        :prompt prompt
                                        :max-output-tokens max-output-tokens
                                        :temperature-milli temperature-milli})
                safe-audit! (fn [event]
                              (try (on-call event) (catch Exception _ nil)))]
            (let [{:keys [status body]}
                  (send-messages-request http-client
                                          {:endpoint endpoint :api-key api-key
                                           :request-timeout-ms request-timeout-ms}
                                          body-map)
                  latency-ms (- (System/currentTimeMillis) started)]
              (if (= 200 status)
                (let [parsed (json/read-str body :key-fn keyword)
                      text (extract-text (:content parsed))
                      finish-reason (finish-reason-keyword (:stop_reason parsed))
                      usage (:usage parsed)
                      input-tokens (token-count usage :input_tokens)
                      output-tokens (token-count usage :output_tokens)]
                  (safe-audit! {:resolution resolution :wire-model model :status :ok
                                :input-tokens input-tokens :output-tokens output-tokens
                                :latency-ms latency-ms})
                  {:text text :finish-reason finish-reason
                   :input-tokens input-tokens :output-tokens output-tokens})
                (do
                  (safe-audit! {:resolution resolution :wire-model model :status :http-error
                                :http-status status :latency-ms latency-ms})
                  (error-for-status status body))))))))))

#?(:cljs
   (defn production-transport
     "Not yet implemented for the cljs/nbb host. See ns docstring: the
     synchronous `(fn [request] -> reply)` transport contract this repo's
     reference providers assume needs a genuinely blocking HTTP call, and
     nbb/Node's `fetch` is Promise-based -- faking synchrony over it (a busy
     poll, a hand-rolled microtask pump) is a separate, reviewable design
     decision this task does not make on cljs's behalf. Use the JVM/:clj
     transport (`kotoba.compiler.provider.llm/provider` hosted via
     `kotoba.compiler.reference-runtime`, the same JVM/Chicory host path
     ADR 0024-0030's other reference providers already run under) until a
     cljs-native synchronous or provider-level-async transport contract is
     designed."
     ([] (production-transport {}))
     ([_opts]
      (throw (ex-info
              "kotoba.compiler.provider.llm-transport/production-transport is JVM-only (:clj) for now; see ns docstring"
              {:phase :llm-transport :host :cljs})))))
