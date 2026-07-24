(ns kotoba.compiler.provider.http-transport
  "Production transport for the bounded HTTP capability kit (ADR 0026,
  `kotoba.compiler.provider.http`), backed by a real blocking
  `java.net.http.HttpClient`. See
  docs/adr/0066-production-http-transport-bounded-redirect-following.md for
  the full design rationale; this docstring is the summary.

  This namespace does NOT define a new provider or a new capability, and does
  NOT modify `http.cljc`. Every bound `http.cljc` already enforces on the
  guest-facing request -- exact-origin allowlist, absolute-HTTPS-only, no
  fragments, header-count/size/body/timeout bounds -- runs exactly as before,
  unchanged and un-weakened, before this transport is ever invoked. This
  namespace builds the one thing every provider in this ADR chain has always
  deferred to the host (ADR 0026: 'Production hosts may implement deadlines
  and I/O outside the guest, but must return the same bounded result'): a
  real synchronous `(fn [request] -> reply)` you pass as `:transport` to
  `kotoba.compiler.provider.http/provider`.

  ## SSRF is the primary threat model here, not an afterthought

  ADR 0064's LLM transport always calls ONE fixed, host-chosen endpoint.
  `:http/post` calls whatever HTTPS origin the GUEST names, bounded only by
  the host's own closed `allowed-origins` set -- so, unlike the LLM
  transport, this is a genuine server-side-request-forgery surface. Three
  defenses this namespace adds ON TOP OF (never in place of) `http.cljc`'s
  own bounds:

  1. **Redirects are never auto-followed by the JDK.** The `HttpClient` is
     built with `HttpClient$Redirect/NEVER`; THIS namespace owns the redirect
     loop instead, and re-validates EVERY hop's resolved origin against the
     exact same closed `allowed-origins` set the host also gave to
     `http/provider` (a required constructor option here, not optional, and
     it must be the identical set) before following it -- matching the
     capability kit's own already-declared semantics
     (`resources/kotoba/lang/capability-kits/http-v1.edn`:
     `:redirects :transport-must-not-follow-outside-allowlist`). A redirect
     whose target origin is not in the allow-list, or that would exceed
     `:max-redirects` (default 5), is simply NOT followed -- the 3xx
     response is returned to the guest as an ordinary typed `:ok` response
     (never silently swallowed, never a thrown exception) so the guest or
     its host can decide whether to issue a fresh, independently-bounded
     `:http/post` call of its own.
  2. **A best-effort private/loopback/link-local/multicast destination-IP
     block runs before every hop's actual connection**
     (`InetAddress/getAllByName` -> reject if ANY resolved address is
     loopback, link-local, RFC1918/site-local, IPv6 unique-local
     (`fc00::/7`), multicast, or any-local/wildcard). This is explicitly NOT
     a complete DNS-rebinding defense -- see `destination-blocked?` and
     docs/adr/0066 'Remaining gaps': the address checked here is not pinned
     for the actual `HttpClient.send` connection that follows, so a TOCTOU
     race against attacker-controlled DNS with a very short TTL is not fully
     closed by this check alone.
  3. **The response body is read through a length-bounded stream reader**
     (never `BodyHandlers/ofByteArray`'s fully-unbounded in-memory buffer),
     and every response header is folded to a bounded count/size before
     being handed back to `http.cljc`. Both defend `http.cljc`'s own
     post-transport checks (`value/bounded-string!` on the body,
     `typed-headers` on the header set) -- which run OUTSIDE
     `invoke-transport`'s try/catch -- from throwing an unhandled exception
     on an ordinary large-body or many-header real-world response, which
     would otherwise violate ADR 0026's own invariant that 'the guest never
     receives a connection, stream, promise, or host exception'.

  JVM only (`:clj`) for now, for the same reason as ADR 0064's LLM
  transport: `java.net.http.HttpClient.send` is genuinely blocking, matching
  every reference provider's synchronous transport contract in this repo
  (`kotoba.compiler.reference-runtime` has no promise/callback machinery for
  a provider to return through); nbb/cljs has no built-in synchronous HTTP
  primitive. The `:cljs` branch below throws a clearly-labeled 'not yet
  implemented' instead of silently pretending to support it."
  (:require [clojure.string :as string]
            [kotoba.compiler.provider.http :as http]
            [kotoba.compiler.value :as value])
  #?(:clj
     (:import (java.net InetAddress URI URISyntaxException UnknownHostException)
              (java.net.http HttpClient HttpClient$Version HttpClient$Redirect
                             HttpRequest HttpRequest$Builder
                             HttpRequest$BodyPublishers
                             HttpResponse HttpResponse$BodyHandlers)
              (java.io ByteArrayOutputStream InputStream)
              (java.nio.charset StandardCharsets)
              (java.time Duration))))

(def default-connect-timeout-ms
  "HttpClient-level TCP connect timeout. Independent of the guest-supplied
  per-request `timeout-ms` (which bounds each hop's whole request/response
  cycle via `HttpRequest.Builder/timeout`, enforced already by `http.cljc`
  to be within `[1, http/max-timeout-ms]`)."
  5000)

(def default-max-redirects
  "How many redirect hops this namespace's own loop will follow before
  giving up and returning the last 3xx response as-is. `0` disables
  redirect-following entirely (every 3xx is returned immediately). Bounded
  small on purpose: worst-case wall-clock time for one guest `:http/post`
  call is bounded by `(inc max-redirects) * timeout-ms`, so this value
  directly controls how much that can multiply the guest's own bounded
  per-call timeout."
  5)

(def restricted-header-names
  "Header names `java.net.http.HttpRequest.Builder/header` refuses to set
  itself (a JDK client restriction, not a Kotoba bound). Dropped
  defensively before building the outbound request so a guest that
  happens to set one of these degrades quietly -- the header is simply not
  forwarded -- instead of the whole call failing with an opaque
  `IllegalArgumentException` thrown from deep inside the JDK client (which
  would still be caught and typed by `http.cljc`'s `invoke-transport`, but
  with no useful information)."
  #{"connection" "content-length" "expect" "host" "upgrade"})

;; ---------------------------------------------------------------------------
;; origin canonicalization -- intentionally duplicated from
;; `kotoba.compiler.provider.http`'s private `https-origin` (that fn is
;; `defn-`, not exported, and this namespace needs a non-throwing predicate
;; form for the redirect loop). Keep the regex in sync if `http.cljc`'s ever
;; changes -- see docs/adr/0066 for why this is a deliberate duplication
;; rather than reaching into `http.cljc`'s private var.
;; ---------------------------------------------------------------------------

(def origin-pattern
  #"https://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::([0-9]+))?(?:/[^ ]*)?")

(defn canonical-origin
  "Returns the canonical `\"https://host[:port]\"` origin for `url`, or nil
  if it is not an absolute HTTPS URL, carries a fragment, or otherwise fails
  to parse -- never throws. Mirrors `http.cljc`'s own `https-origin`
  acceptance rule exactly (same regex, same lower-cased host) so a set of
  origins already validated as canonical by `http/provider` also validates
  identically here."
  [url]
  (when (and (string? url) (not (string/includes? url "#")))
    (when-let [[_ host port] (re-matches origin-pattern url)]
      (str "https://" (string/lower-case host) (when port (str ":" port))))))

;; ---------------------------------------------------------------------------
;; destination-IP check (best-effort; see ns docstring point 2 and
;; docs/adr/0066 Remaining gaps for what this does NOT close)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- unique-local-ipv6?
     "`fc00::/7` -- IPv6 unique-local addresses. `InetAddress/isSiteLocalAddress`
     only recognizes the deprecated `fec0::/10` IPv6 site-local range, not
     this one, so it needs its own check."
     [^InetAddress addr]
     (and (instance? java.net.Inet6Address addr)
          (let [b (.getAddress addr)]
            (= 0xfc (bit-and (int (aget ^bytes b 0)) 0xfe))))))

#?(:clj
   (defn- private-address?
     [^InetAddress addr]
     (or (.isLoopbackAddress addr)
         (.isLinkLocalAddress addr)
         (.isSiteLocalAddress addr)
         (.isMulticastAddress addr)
         (.isAnyLocalAddress addr)
         (unique-local-ipv6? addr))))

#?(:clj
   (defn- destination-blocked?
     "Resolve `host` and reject if ANY returned address is loopback,
     link-local, RFC1918/site-local, IPv6 unique-local, multicast, or
     any-local/wildcard. Returns false (not blocked) on a DNS resolution
     failure -- that is an ordinary network error the actual connection
     attempt will raise on its own moments later, propagating to
     `http.cljc`'s `invoke-transport` catch-all exactly like any other
     connect failure; this predicate does not need to duplicate that path."
     [^String host]
     (try
       (boolean (some private-address? (InetAddress/getAllByName host)))
       (catch UnknownHostException _ false))))

;; ---------------------------------------------------------------------------
;; bounded response decoding -- defends `http.cljc`'s own post-transport
;; `value/bounded-string!` / `typed-headers` checks (outside
;; `invoke-transport`'s try/catch) from an unhandled exception on an
;; ordinary large-body or many-header real response.
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- read-bounded-bytes
     "Reads at most `max-bytes` from `in`, never buffering more than that in
     memory regardless of how large the real response body is."
     ^bytes [^InputStream in ^long max-bytes]
     (let [out (ByteArrayOutputStream.)
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

#?(:clj
   (defn- truncate-to-byte-limit
     "Trims `s` (already produced by a UTF-8-with-replacement decode of at
     most `limit` raw bytes) until its OWN re-encoded UTF-8 byte count is
     `<= limit`. A trailing malformed multi-byte sequence in the raw input
     is replaced by the JDK decoder with a single U+FFFD (3 UTF-8 bytes),
     which can occasionally make the decoded string's own byte count
     slightly EXCEED the raw byte count it was decoded from -- this loop is
     the safety net that guarantees the value handed to
     `value/bounded-string!` downstream never trips it."
     [s limit]
     (loop [s s]
       (if (<= (value/utf8-byte-count! s) limit)
         s
         (recur (subs s 0 (max 0 (dec (count s)))))))))

#?(:clj
   (defn- decode-bounded-body [^bytes raw]
     (truncate-to-byte-limit (String. raw StandardCharsets/UTF_8)
                              value/string-value-byte-limit)))

#?(:clj
   (defn- bounded-response-headers
     "Folds a real `java.net.http.HttpHeaders` down to the same shape
     `http.cljc`'s `typed-headers` requires: at most `http/max-headers`
     entries, each a keyword name within `value/keyword-value-byte-limit`
     bytes and a string value within `value/string-value-byte-limit` bytes.
     Known, documented lossy simplifications (see docs/adr/0066): header
     names are case-folded (so e.g. `Content-Type` and `content-type` from
     a misbehaving upstream would collide -- the second one wins, arbitrary
     order); when a name repeats only its FIRST value is kept (relevant to
     e.g. multiple `Set-Cookie` headers); and which headers survive the
     `max-headers` cap when a response has more than that is unspecified
     order, not guest-visible priority."
     [^java.net.http.HttpHeaders response-headers]
     (->> (.map response-headers)
          (keep (fn [[name values]]
                  (let [folded (string/lower-case name)]
                    (when (and (seq values)
                               (<= (value/utf8-byte-count! folded) value/keyword-value-byte-limit))
                      [(keyword folded)
                       (truncate-to-byte-limit (first values) value/string-value-byte-limit)]))))
          (take http/max-headers)
          (into {}))))

;; ---------------------------------------------------------------------------
;; per-hop request/response
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- send-hop
     "Issues one bounded synchronous POST to `uri` and returns the raw
     `HttpResponse<InputStream>` -- caller decides whether to follow (and
     discard/close the body) or finish (bounded-read the body and close)."
     ^HttpResponse [^HttpClient http-client ^URI uri headers body timeout-ms]
     (let [safe-headers (remove (fn [[k _]]
                                  (contains? restricted-header-names
                                             (string/lower-case (name k))))
                                headers)
           builder (-> (HttpRequest/newBuilder uri)
                      (.timeout (Duration/ofMillis (long timeout-ms))))
           ^HttpRequest$Builder builder
           (reduce (fn [^HttpRequest$Builder b [k v]]
                     (.header b (name k) v))
                   builder safe-headers)
           req (-> builder
                  (.POST (HttpRequest$BodyPublishers/ofString body))
                  (.build))]
       (.send http-client req (HttpResponse$BodyHandlers/ofInputStream)))))

#?(:clj
   (defn- finish!
     "Bounded-reads and closes the response body, and folds the response
     headers, producing the `{:status :headers :body}` shape
     `http.cljc`'s `:invoke` expects back from a successful transport call."
     [^HttpResponse resp]
     (let [status (.statusCode resp)
           response-headers (.headers resp)
           ^InputStream in (.body resp)
           raw (try (read-bounded-bytes in value/string-value-byte-limit)
                    (finally (.close in)))]
       {:status status
        :headers (bounded-response-headers response-headers)
        :body (decode-bounded-body raw)})))

#?(:clj
   (defn- redirect-target
     "Resolves `location` against `base-uri` and returns the resolved
     absolute URL string, or nil if it does not parse, exceeds
     `http/max-url-bytes`, or resolves to something other than an absolute
     HTTPS URL. Never throws."
     [^URI base-uri ^String location]
     (try
       (let [resolved (str (.resolve base-uri (URI. location)))]
         (when (<= (value/utf8-byte-count! resolved) http/max-url-bytes)
           resolved))
       (catch URISyntaxException _ nil)
       (catch IllegalArgumentException _ nil))))

#?(:clj
   (defn- connect-refusal-reason
     "nil if `url` is safe to connect to right now (its canonical origin is
     in `allowed-origins` AND its resolved host is not a blocked private/
     loopback/link-local/multicast destination); otherwise a keyword saying
     which check failed: `:not-in-allow-list` or `:destination-blocked`.
     Used both for the FIRST hop (where a non-nil reason means refuse the
     whole call with a typed error -- see `follow-and-collect!`) and for
     each candidate redirect target (where a non-nil reason means decline
     to follow, not error -- the prior response is returned as-is)."
     [url allowed-origins]
     (let [origin (canonical-origin url)]
       (cond
         (or (nil? origin) (not (contains? allowed-origins origin))) :not-in-allow-list
         (destination-blocked? (.getHost (URI/create url))) :destination-blocked
         :else nil))))

#?(:clj
   (defn- refusal-error [reason url]
     {:error {:code :http/destination-blocked
              :message (case reason
                         :destination-blocked
                         (str "resolved address for " (.getHost (URI/create url))
                              " is not an allowed destination"
                              " (loopback/link-local/private/multicast)")
                         :not-in-allow-list
                         (str "destination is outside the allowed-origins set: " url))
              :retryable false}}))

#?(:clj
   (defn- follow-and-collect!
     "The redirect loop.

     The FIRST hop -- the guest's own original URL, already validated once
     by `http.cljc`'s own `:invoke` before this transport ever runs -- is
     re-validated here too (defense in depth: this transport has no
     independent authority and must never trust a caller that skipped
     `http.cljc`). If it fails either check, this call refuses outright
     with a typed `:http/destination-blocked` error.

     Every SUBSEQUENT hop (a redirect target) is validated the same way
     BEFORE it is ever connected to, but a failure there is NOT an error --
     the prior response (the 3xx itself) is simply returned to the guest
     as-is, un-followed. Exceeding `max-redirects` is handled identically
     (the candidate is treated as absent, so the loop's last response
     becomes final). This is the one asymmetry in this namespace: refuse
     the call the guest actually asked for with a typed error, but never
     error out over a redirect this transport merely chose not to chase."
     [{:keys [http-client allowed-origins max-redirects]} initial-url headers body timeout-ms]
     (if-let [reason (connect-refusal-reason initial-url allowed-origins)]
       (refusal-error reason initial-url)
       (loop [current-url initial-url
              redirects-left max-redirects]
         (let [uri (URI/create current-url)
               resp (send-hop http-client uri headers body timeout-ms)
               status (.statusCode resp)
               location (some-> (.firstValue (.headers resp) "location")
                                (.orElse nil))
               candidate (when (and (<= 300 status 399) location (pos? redirects-left))
                          (redirect-target uri location))]
           (if (and candidate (nil? (connect-refusal-reason candidate allowed-origins)))
             (do (.close ^InputStream (.body resp))
                 (recur candidate (dec redirects-left)))
             (finish! resp)))))))

;; ---------------------------------------------------------------------------
;; public constructor
;; ---------------------------------------------------------------------------

#?(:clj
   (defn production-transport
     "Build a synchronous transport fn for
     `(kotoba.compiler.provider.http/provider {:transport (production-transport ...) :allowed-origins ...})`.

     Input (already bounds-checked and admitted by `http.cljc` before this
     fn ever runs): `{:url <string> :headers {<keyword> <string>}
     :body <string> :timeout-ms <int>}`.

     Output: `{:status <int> :headers {<keyword> <string>} :body <string>}`
     on success (including a 3xx not followed for any reason -- see ns
     docstring), or `{:error {:code <keyword> :message <string>
     :retryable <bool>}}` when this namespace itself refuses to connect
     (destination-blocked). Network/IO exceptions (DNS failure, connection
     refused, timeout, TLS failure) are deliberately NOT caught here --
     they propagate to `kotoba.compiler.provider.http/invoke-transport`'s
     own catch, which redacts them into a generic `:http/transport` error,
     exactly like ADR 0064's LLM transport leaves its own network
     exceptions uncaught for the same reason.

     Options:
       :allowed-origins -- REQUIRED. Must be the identical closed set of
         canonical `\"https://host[:port]\"` strings also given to
         `http/provider`'s own `:allowed-origins`. This namespace has no
         independent authority over destinations; it only ever re-checks
         the SAME allow-list on every redirect hop. There is deliberately
         no way to configure this transport with a wider or different
         allow-list than the provider's own -- that would silently
         reintroduce the exact SSRF surface ADR 0026 closed.
       :max-redirects -- default `default-max-redirects` (5). `0` disables
         redirect-following: every 3xx response is returned immediately.
       :connect-timeout-ms -- HttpClient-level TCP connect timeout, default
         `default-connect-timeout-ms`.
       :on-call -- optional `(fn [event-map])` audit/observability hook,
         invoked after every attempt with `{:url :status :error?
         :latency-ms}`. Exceptions raised by this hook are swallowed and
         never affect the HTTP call -- additive, not a new required
         contract, matching ADR 0064's own `:on-call` rationale (no
         capability kit in this repo mandates quota/audit wiring at the
         transport-construction layer today)."
     ([] (production-transport {}))
     ([{:keys [allowed-origins max-redirects connect-timeout-ms on-call]
        :or {max-redirects default-max-redirects
             connect-timeout-ms default-connect-timeout-ms
             on-call (fn [_])}}]
      (when-not (and (set? allowed-origins) (seq allowed-origins) (every? string? allowed-origins))
        (throw (ex-info "http-transport requires a non-empty :allowed-origins set (the same one given to http/provider)"
                        {:phase :http-transport})))
      (doseq [origin allowed-origins]
        (when-not (= origin (canonical-origin origin))
          (throw (ex-info "http-transport :allowed-origins entries must already be canonical https origins"
                          {:phase :http-transport :origin origin}))))
      (when-not (and (integer? max-redirects) (<= 0 max-redirects 20))
        (throw (ex-info "http-transport :max-redirects must be a small bounded integer in [0, 20]"
                        {:phase :http-transport :max-redirects max-redirects})))
      (let [http-client (-> (HttpClient/newBuilder)
                            ;; Pin HTTP/1.1 explicitly for the same reason as
                            ;; ADR 0064's LLM transport: a plain HTTP/1.1-only
                            ;; server (including this namespace's own
                            ;; `com.sun.net.httpserver` test fakes) cannot
                            ;; negotiate the JDK's default HTTP/2 attempt,
                            ;; surfacing as an opaque IOException rather than
                            ;; a clean protocol error. Real HTTPS endpoints
                            ;; ALPN-negotiate fine either way.
                            (.version HttpClient$Version/HTTP_1_1)
                            ;; NEVER let the JDK auto-follow redirects -- this
                            ;; namespace's own `follow-and-collect!` loop is
                            ;; the only thing allowed to do that, and only
                            ;; within `allowed-origins`. See ns docstring
                            ;; point 1 and docs/adr/0066.
                            (.followRedirects HttpClient$Redirect/NEVER)
                            (.connectTimeout (Duration/ofMillis (long connect-timeout-ms)))
                            (.build))
            allowed-origins-set (set allowed-origins)]
        (fn [{:keys [url headers body timeout-ms]}]
          (let [started (System/currentTimeMillis)
                safe-audit! (fn [event] (try (on-call event) (catch Exception _ nil)))
                result (follow-and-collect!
                        {:http-client http-client
                         :allowed-origins allowed-origins-set
                         :max-redirects max-redirects}
                        url headers body timeout-ms)]
            (safe-audit! {:url url
                          :status (:status result)
                          :error? (boolean (:error result))
                          :latency-ms (- (System/currentTimeMillis) started)})
            result))))))

#?(:cljs
   (defn production-transport
     "Not yet implemented for the cljs/nbb host. See ns docstring: the
     synchronous `(fn [request] -> reply)` transport contract this repo's
     reference providers assume needs a genuinely blocking HTTP call, and
     nbb/Node's `fetch` is Promise-based -- faking synchrony over it is a
     separate, reviewable design decision this task does not make on
     cljs's behalf. Use the JVM/:clj transport
     (`kotoba.compiler.provider.http/provider` hosted via
     `kotoba.compiler.reference-runtime`, the same JVM/Chicory host path
     ADR 0024-0030's other reference providers already run under) until a
     cljs-native synchronous or provider-level-async transport contract is
     designed."
     ([] (production-transport {}))
     ([_opts]
      (throw (ex-info
              "kotoba.compiler.provider.http-transport/production-transport is JVM-only (:clj) for now; see ns docstring"
              {:phase :http-transport :host :cljs})))))
