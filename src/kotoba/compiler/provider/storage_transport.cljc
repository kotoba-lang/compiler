(ns kotoba.compiler.provider.storage-transport
  "Production transport for the bounded storage capability kit (ADR 0028,
  `kotoba.compiler.provider.storage`), backed by a real blocking
  `java.net.http.HttpClient` call to a host-configured durable key/value
  service. See docs/adr/0070-production-storage-transport-host-configured-kv-endpoint.md
  for the full design rationale; this docstring is the summary.

  This namespace does NOT define a new provider or a new capability, and does
  NOT modify `storage.cljc`. Every bound `storage.cljc` already enforces --
  bounded keys, bounded 65536-byte values, `:option i64` conditional versions
  validated before the transport is ever called, backend exceptions redacted
  to a generic typed error -- runs exactly as before, unchanged and
  un-weakened. This namespace builds the one thing every provider in this ADR
  chain has always deferred to the host (ADR 0028: 'The injected transport
  owns durability, quota enforcement, and atomic version checks'): a real
  synchronous `(fn [request] -> reply)` you pass as `:transport` to
  `kotoba.compiler.provider.storage/provider`.

  ## Storage must not acquire ambient filesystem authority (ADR 0049,
  `resources/kotoba/lang/capability-kits/storage-v1.edn`'s own
  `:ambient-filesystem false`)

  Unlike a naive implementation that would reach for `java.io.File`/
  `clojure.java.io` and write bytes under some default location (e.g. the
  process's home directory), this namespace never touches the local
  filesystem at all. It follows the exact same 'host injects a real
  synchronous transport, this namespace never invents an ambient default'
  pattern ADR 0064 (LLM) and ADR 0066 (HTTP) already established: durability
  is delegated ENTIRELY to a durable key/value HTTP service the host
  operates and explicitly points this transport at via a REQUIRED `:endpoint`
  construction option (or the `KOTOBA_STORAGE_ENDPOINT` env var) -- there is
  no default endpoint baked in (unlike LLM's `murakumo-main`), because unlike
  LLM there is no repo-wide well-known storage backend to default to; forcing
  explicit host configuration is itself part of the 'no ambient authority'
  design, not an oversight.

  ## Wire shape -- a single fixed JSON-over-HTTPS operation, not a
  filesystem-shaped path per key

  A single fixed path (default `/storage/v1/transact`, overridable) is POSTed
  to for every operation; the operation, namespace, key, value, and
  conditional version all travel in a JSON request body, never in the URL
  path. This is a deliberate simplification versus (for example) encoding the
  key into a URL path segment: keys are guest-controlled bounded strings, and
  folding them into a path invites a whole class of percent-encoding/path-
  traversal edge cases (`../`, encoded slashes, etc.) that a fixed-path
  JSON-body design sidesteps entirely -- there is no path segment ever built
  from guest-controlled bytes.

  ## No guest-facing SSRF surface, unlike ADR 0066's HTTP transport

  `:storage/transact` has no guest-supplied destination at all --
  `storage-namespace` is fixed once per provider instance by the HOST at
  construction (`storage.cljc`'s own docstring: 'The namespace is host-owned
  and never supplied by guest code'), and this transport's own `:endpoint` is
  likewise fixed once by the host at construction. This makes storage's
  threat model like ADR 0064's LLM transport (one fixed, host-chosen
  destination) rather than ADR 0066's HTTP transport (an arbitrary
  guest-named destination bounded only by an allow-list) -- so this
  namespace has no redirect-revalidation loop, no destination-IP block, and
  no allow-list: there is exactly one destination, chosen entirely by the
  host, never by the guest.

  ## Fail-closed sanitization of the upstream reply

  `storage.cljc`'s own post-transport checks (`entry`'s `value/bounded-string!`
  on the value, `valid-version?` on the version) run OUTSIDE
  `invoke-transport`'s try/catch, exactly like `http.cljc`'s post-transport
  header/body checks (ADR 0066 point 3). An oversized value or an
  out-of-range/non-integer version in the upstream JSON response would
  otherwise throw an unhandled exception past that boundary. This namespace
  defends against that by truncating an oversized value to
  `storage/max-value-bytes` and, more conservatively, by turning an invalid
  version or current-version into an explicit non-retryable
  `:storage/invalid-response` typed error rather than fabricating a
  plausible-looking version number -- a wrong-but-plausible version could
  silently mask a real conditional-version conflict, which is a strictly
  worse failure mode than a typed, visible error.

  JVM only (`:clj`) for now, for the same reason as ADR 0064's LLM transport
  and ADR 0066's HTTP transport: `java.net.http.HttpClient.send` is
  genuinely blocking, matching every reference provider's synchronous
  transport contract in this repo (`kotoba.compiler.reference-runtime` has no
  promise/callback machinery for a provider to return through); nbb/cljs has
  no built-in synchronous HTTP primitive. The `:cljs` branch below throws a
  clearly-labeled 'not yet implemented' instead of silently pretending to
  support it."
  (:require [clojure.string :as string]
            [kotoba.compiler.provider.storage :as storage]
            [kotoba.compiler.value :as value]
            #?(:clj [clojure.data.json :as json]))
  #?(:clj
     (:import (java.net URI)
              (java.net.http HttpClient HttpClient$Version HttpRequest
                             HttpRequest$BodyPublishers HttpResponse
                             HttpResponse$BodyHandlers)
              (java.time Duration))))

(def default-path
  "Fixed request path for every operation -- see ns docstring for why the
  key/namespace never appear in the URL path itself."
  "/storage/v1/transact")

(def default-connect-timeout-ms 5000)
(def default-request-timeout-ms 10000)

(def env-endpoint-var "KOTOBA_STORAGE_ENDPOINT")
(def env-api-key-var "KOTOBA_STORAGE_API_KEY")

;; A small headroom over storage/max-value-bytes for the JSON envelope
;; (quoting, escaping, the tag/version/key fields) around the value itself --
;; bounds how much this namespace will ever buffer for one response body,
;; independent of whatever `storage.cljc`'s own bounded-string! check does
;; afterward.
(def response-byte-limit (+ storage/max-value-bytes 8192))

;; ---------------------------------------------------------------------------
;; env / option resolution -- intentionally duplicated small helper, same
;; shape as llm-transport.cljc's and http-transport.cljc's own `getenv` (each
;; is `defn-`, not shared, to keep each transport namespace independently
;; readable -- see those namespaces' own comments for the same rationale).
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- getenv [name]
     (let [v (System/getenv name)]
       (when (and v (seq (string/trim v))) v))))

(defn resolve-endpoint
  "Resolve the fixed, host-configured storage-backend origin: an explicit
  `:endpoint` construction option, else the `KOTOBA_STORAGE_ENDPOINT` env
  var, else throws. There is deliberately no baked-in default (unlike LLM's
  `murakumo-main`) -- see ns docstring's 'ambient filesystem authority'
  section for why forcing explicit configuration here is itself part of this
  namespace's safety design, not an oversight."
  [opts]
  (or (:endpoint opts)
      #?(:clj (getenv env-endpoint-var) :cljs nil)
      (throw (ex-info
              (str "storage-transport requires an :endpoint construction option (or "
                   env-endpoint-var
                   " env var) -- there is no repo-wide well-known storage backend to "
                   "default to, so the destination must be explicitly host-configured")
              {:phase :storage-transport}))))

;; ---------------------------------------------------------------------------
;; keyword <-> wire string -- never used to build a URL path (see ns
;; docstring); only ever a JSON string value.
;; ---------------------------------------------------------------------------

(defn- kw->wire [kw]
  (if-let [ns (namespace kw)]
    (str ns "/" (name kw))
    (name kw)))

;; ---------------------------------------------------------------------------
;; request body
;; ---------------------------------------------------------------------------

(defn- request-body
  [{:keys [namespace operation key value expected-version]}]
  (cond-> {"namespace" (kw->wire namespace)
           "operation" (name operation)
           "key" (kw->wire key)}
    (= operation :put) (assoc "value" value)
    (contains? #{:put :delete} operation) (assoc "expected_version" expected-version)))

;; ---------------------------------------------------------------------------
;; bounded response body decoding -- defends `storage.cljc`'s own
;; post-transport `entry`/`option-version` checks (outside
;; `invoke-transport`'s try/catch) from an unhandled exception, exactly like
;; ADR 0066's `read-bounded-bytes`/`truncate-to-byte-limit` do for `http.cljc`.
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- read-bounded-bytes
     ^bytes [^java.io.InputStream in ^long max-bytes]
     (let [out (java.io.ByteArrayOutputStream.)
           buf (byte-array (int (min max-bytes 8192)))]
       (loop [total 0]
         (if (>= total max-bytes)
           (.toByteArray out)
           (let [to-read (int (min (alength buf) (- max-bytes total)))
                 n (.read in buf 0 to-read)]
             (if (neg? n)
               (.toByteArray out)
               (do (.write out buf 0 n)
                   (recur (+ total n))))))))))

(defn- truncate-to-byte-limit
  "Trims `s` until its own re-encoded UTF-8 byte count is `<= limit`. See
  http-transport.cljc's identically-named helper for why this loop (rather
  than a raw-byte substring) is the safe way to shrink a string that may have
  been decoded from truncated raw bytes."
  [s limit]
  (loop [s s]
    (if (<= (value/utf8-byte-count! s) limit)
      s
      (recur (subs s 0 (max 0 (dec (count s))))))))

#?(:clj
   (defn- decode-bounded-body [^bytes raw]
     (truncate-to-byte-limit (String. raw java.nio.charset.StandardCharsets/UTF_8)
                              response-byte-limit)))

;; ---------------------------------------------------------------------------
;; wire reply -> the {:tag ...} shape kotoba.compiler.provider.storage's
;; `typed-result` expects. `key` is intentionally NOT part of the wire reply
;; -- `typed-result` already has it from the original request, so there is no
;; need for the wire protocol to echo it back (see ns docstring).
;; ---------------------------------------------------------------------------

(defn- sanitize-value
  "Bounds an upstream `value` to `storage/max-value-bytes`, coercing a
  non-string to its `str` form defensively (never throws)."
  [v]
  (truncate-to-byte-limit (str v) storage/max-value-bytes))

(defn- valid-version-number? [v]
  (and (integer? v) (<= 1 v)))

(defn- invalid-response-error [message]
  {:tag :error
   :error {:code :storage/invalid-response :message message :retryable false}})

(defn- finalize-reply
  "Final fail-closed sanitization pass over an already-tag-dispatched wire
  reply -- see ns docstring's 'Fail-closed sanitization' section. Never
  throws; a malformed value/version becomes a typed, non-retryable error
  instead of an uncaught exception past `storage.cljc`'s own boundary."
  [reply]
  (case (:tag reply)
    (:found :written)
    (let [{:keys [value version]} reply]
      (if (and (string? value) (valid-version-number? version))
        (update reply :value sanitize-value)
        (invalid-response-error
         "storage transport returned a malformed value/version for found/written")))

    :conflict
    (let [current-version (:current-version reply)]
      (if (or (nil? current-version) (valid-version-number? current-version))
        reply
        (invalid-response-error
         "storage transport returned a malformed current-version for conflict")))

    reply))

(defn- bounded-error-code
  "Coerces an upstream error code to a keyword whose UTF-8 byte length fits
  `value/keyword-value-byte-limit`, never throws. `storage.cljc`'s own
  `error` function re-validates this with `value/bounded-keyword!` OUTSIDE
  `invoke-transport`'s try/catch, so an oversized upstream code string would
  otherwise throw past that boundary exactly like an oversized value would."
  [code]
  (let [text (truncate-to-byte-limit (str code) value/keyword-value-byte-limit)]
    (if (seq text) (keyword text) :storage/upstream-error)))

(defn- wire->reply
  [parsed]
  (let [tag (keyword (str (:tag parsed)))]
    (finalize-reply
     (case tag
       :found {:tag :found :value (:value parsed) :version (:version parsed)}
       :missing {:tag :missing}
       :written {:tag :written :value (:value parsed) :version (:version parsed)}
       :deleted {:tag :deleted}
       :conflict {:tag :conflict :current-version (:current_version parsed)}
       :error {:tag :error
               :error {:code (bounded-error-code (get-in parsed [:error :code]))
                       :message (truncate-to-byte-limit
                                 (str (get-in parsed [:error :message] ""))
                                 value/string-value-byte-limit)
                       :retryable (boolean (get-in parsed [:error :retryable]))}}
       (throw (ex-info "storage transport received an unknown wire tag"
                       {:phase :storage-transport :tag tag}))))))

;; ---------------------------------------------------------------------------
;; non-2xx HTTP status -> a typed {:tag :error ...} reply, same status-code
;; taxonomy as ADR 0064's LLM transport (`error-for-status`).
;; ---------------------------------------------------------------------------

(defn- truncate-for-error-message [s limit]
  (let [s (str s)]
    (if (> (count s) limit) (str (subs s 0 limit) "...") s)))

(defn- error-for-status [status body]
  (let [retryable? (or (= status 429) (>= status 500))
        code (case (int status)
               429 :storage/rate-limited
               401 :storage/unauthorized
               403 :storage/forbidden
               404 :storage/not-found
               (if (>= status 500) :storage/upstream-error :storage/request-rejected))]
    {:tag :error
     :error {:code code
             :message (str "storage transport HTTP " status ": "
                           (truncate-for-error-message body 400))
             :retryable retryable?}}))

;; ---------------------------------------------------------------------------
;; wire I/O
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- send-transact-request
     [^HttpClient http-client
      {:keys [endpoint path api-key request-timeout-ms]}
      body-json]
     (let [url (str endpoint path)
           builder (-> (HttpRequest/newBuilder (URI/create url))
                      (.timeout (Duration/ofMillis (long request-timeout-ms)))
                      (.header "content-type" "application/json")
                      (.header "accept" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString body-json)))
           builder (if api-key (.header builder "authorization" (str "Bearer " api-key)) builder)
           req (.build builder)
           resp (.send http-client req (HttpResponse$BodyHandlers/ofInputStream))
           status (.statusCode resp)
           ^java.io.InputStream in (.body resp)
           raw (try (read-bounded-bytes in response-byte-limit)
                    (finally (.close in)))]
       {:status status :body (decode-bounded-body raw)})))

;; ---------------------------------------------------------------------------
;; public constructor
;; ---------------------------------------------------------------------------

#?(:clj
   (defn production-transport
     "Build a synchronous transport fn for
     `(kotoba.compiler.provider.storage/provider {:transport (production-transport ...) :storage-namespace ...})`.

     Input (already bounds-checked and admitted by `storage.cljc` before this
     fn ever runs): `{:namespace <qualified-kw> :operation <:get|:put|:delete>
     :key <kw> :value <string, :put only> :expected-version <int-or-nil,
     :put/:delete only>}`.

     Output: a `{:tag <keyword> ...}` map matching what
     `kotoba.compiler.provider.storage`'s `typed-result` dispatches on
     (`:found`/`:missing`/`:written`/`:deleted`/`:conflict`/`:error`), or a
     `{:tag :error ...}` reply itself for a non-2xx HTTP status or a
     malformed upstream body (see `finalize-reply`). Network/IO exceptions
     (DNS failure, connection refused, timeout) are deliberately NOT caught
     here -- they propagate to
     `kotoba.compiler.provider.storage/invoke-transport`'s own catch, which
     redacts them into a generic `:storage/transport` error, exactly like
     ADR 0064/0066's transports leave their own network exceptions uncaught
     for the same reason.

     Options:
       :endpoint -- REQUIRED (or the `KOTOBA_STORAGE_ENDPOINT` env var). The
         host-operated durable key/value service's origin, e.g.
         \"https://storage.internal.example\". See ns docstring's 'ambient
         filesystem authority' section for why there is no default.
       :path -- default `default-path`.
       :api-key -- optional bearer token (also `KOTOBA_STORAGE_API_KEY` env
         var).
       :connect-timeout-ms / :request-timeout-ms
       :on-call -- optional `(fn [event-map])` audit/observability hook,
         invoked after every attempt with `{:namespace :operation :key
         :status :http-status :latency-ms}` (`:status` is `:ok` or
         `:http-error`). Exceptions raised by this hook are swallowed and
         never affect the storage call -- additive, matching ADR 0064/0066's
         own `:on-call` rationale (no capability kit in this repo mandates
         quota/audit wiring at this layer today)."
     ([] (production-transport {}))
     ([opts]
      (let [endpoint (resolve-endpoint opts)
            path (:path opts default-path)
            api-key (or (:api-key opts) (getenv env-api-key-var))
            connect-timeout-ms (:connect-timeout-ms opts default-connect-timeout-ms)
            request-timeout-ms (:request-timeout-ms opts default-request-timeout-ms)
            on-call (:on-call opts (fn [_]))
            http-client (-> (HttpClient/newBuilder)
                            ;; Pin HTTP/1.1 explicitly -- same reason as ADR
                            ;; 0064/0066: a plain HTTP/1.1-only server
                            ;; (including this namespace's own
                            ;; `com.sun.net.httpserver` test fakes) cannot
                            ;; negotiate the JDK's default HTTP/2 attempt.
                            (.version HttpClient$Version/HTTP_1_1)
                            (.connectTimeout (Duration/ofMillis (long connect-timeout-ms)))
                            (.build))]
        (fn [{:keys [namespace operation key value expected-version] :as request}]
          (let [started (System/currentTimeMillis)
                safe-audit! (fn [event] (try (on-call event) (catch Exception _ nil)))
                body-json (json/write-str (request-body request))
                {:keys [status body]}
                (send-transact-request http-client
                                        {:endpoint endpoint :path path :api-key api-key
                                         :request-timeout-ms request-timeout-ms}
                                        body-json)
                latency-ms (- (System/currentTimeMillis) started)]
            (if (= 200 status)
              (let [parsed (json/read-str body :key-fn keyword)
                    reply (wire->reply parsed)]
                (safe-audit! {:namespace namespace :operation operation :key key
                              :status :ok :http-status status :latency-ms latency-ms})
                reply)
              (do
                (safe-audit! {:namespace namespace :operation operation :key key
                              :status :http-error :http-status status :latency-ms latency-ms})
                (error-for-status status body)))))))))

#?(:cljs
   (defn production-transport
     "Not yet implemented for the cljs/nbb host. See ns docstring: the
     synchronous `(fn [request] -> reply)` transport contract this repo's
     reference providers assume needs a genuinely blocking HTTP call, and
     nbb/Node's `fetch` is Promise-based -- faking synchrony over it is a
     separate, reviewable design decision this task does not make on cljs's
     behalf. Use the JVM/:clj transport
     (`kotoba.compiler.provider.storage/provider` hosted via
     `kotoba.compiler.reference-runtime`, the same JVM/Chicory host path
     ADR 0024-0030's other reference providers already run under) until a
     cljs-native synchronous or provider-level-async transport contract is
     designed."
     ([] (production-transport {}))
     ([_opts]
      (throw (ex-info
              "kotoba.compiler.provider.storage-transport/production-transport is JVM-only (:clj) for now; see ns docstring"
              {:phase :storage-transport :host :cljs})))))
