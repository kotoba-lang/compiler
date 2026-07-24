(ns kotoba.compiler.http-transport-test
  "Unit and integration tests for `kotoba.compiler.provider.http-transport`.

  Two groups, deliberately separated:

  1. **Pure/deterministic unit tests, no network at all** -- of the
     security-critical private helpers directly (`canonical-origin`,
     `destination-blocked?`/`private-address?`, `redirect-target`,
     `truncate-to-byte-limit`, `bounded-response-headers`). These prove the
     SSRF defenses (HTTPS-only scheme enforcement, private/loopback/
     link-local destination blocking, bounded redirect-target resolution,
     bounded body/header folding) work correctly in isolation.
  2. **End-to-end wire-protocol / redirect-loop / typed-boundary tests**
     against a local `com.sun.net.httpserver.HttpServer` fake (same
     technique as `llm_transport_test.clj`) -- run through
     `production-transport` and the full typed `http/provider` boundary.

     A local test fixture is intrinsically plaintext HTTP on loopback
     (127.0.0.1). Both of this namespace's own SSRF defenses would
     correctly refuse that in real usage (HTTPS-only, no loopback) -- which
     is exactly what group 1 already proves in isolation. So group 2 tests
     use `with-redefs` to relax EXACTLY those two checks, and ONLY for the
     duration of a given test, so the real redirect-loop/header/body/
     timeout/allow-list-membership logic can be exercised end-to-end
     against a local fixture without either guard getting in the way:
       - `canonical-origin` is temporarily widened to also accept `http://`
         (in addition to `https://`), so a local `http://127.0.0.1:<port>`
         origin can stand in for a remote `https://` one. The allow-list
         MEMBERSHIP check itself (`contains? allowed-origins origin`) is
         NEVER redefined -- it is exercised for real in every test below.
       - `destination-blocked?` is temporarily forced to `false` so the
         loopback fixture address is not refused -- except in
         `real-destination-blocked-check-refuses-a-loopback-target`, which
         deliberately leaves it real to prove the guard fires end-to-end."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.http :as http]
            [kotoba.compiler.provider.http-transport :as transport]
            [kotoba.compiler.reference-runtime :as runtime]
            [kotoba.compiler.value :as value])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress URI)
           (java.net.http HttpHeaders)
           (java.util.function BiPredicate)))

;; ---------------------------------------------------------------------------
;; group 1 -- pure unit tests, no network
;; ---------------------------------------------------------------------------

(deftest canonical-origin-requires-absolute-https-with-no-fragment
  (is (= "https://api.example.test" (transport/canonical-origin "https://api.example.test")))
  (is (= "https://api.example.test" (transport/canonical-origin "https://API.Example.Test/some/path?q=1")))
  (is (= "https://api.example.test:8443" (transport/canonical-origin "https://api.example.test:8443/x")))
  (testing "http (not https) is rejected outright"
    (is (nil? (transport/canonical-origin "http://api.example.test"))))
  (testing "a fragment disqualifies the whole URL"
    (is (nil? (transport/canonical-origin "https://api.example.test/path#frag"))))
  (testing "not a URL at all"
    (is (nil? (transport/canonical-origin "not a url")))
    (is (nil? (transport/canonical-origin nil)))))

(deftest destination-blocked-detects-private-loopback-link-local-and-multicast-literals
  (testing "loopback"
    (is (true? (#'transport/destination-blocked? "127.0.0.1")))
    (is (true? (#'transport/destination-blocked? "::1"))))
  (testing "link-local, including the cloud-metadata address"
    (is (true? (#'transport/destination-blocked? "169.254.169.254")))
    (is (true? (#'transport/destination-blocked? "fe80::1"))))
  (testing "RFC1918 site-local"
    (is (true? (#'transport/destination-blocked? "10.0.0.5")))
    (is (true? (#'transport/destination-blocked? "192.168.1.1")))
    (is (true? (#'transport/destination-blocked? "172.16.0.1"))))
  (testing "IPv6 unique-local (fc00::/7)"
    (is (true? (#'transport/destination-blocked? "fc00::1")))
    (is (true? (#'transport/destination-blocked? "fd12:3456::1"))))
  (testing "multicast"
    (is (true? (#'transport/destination-blocked? "224.0.0.1"))))
  (testing "a TEST-NET-3 (RFC 5737) literal is not in any blocked range"
    (is (false? (#'transport/destination-blocked? "203.0.113.5"))))
  (testing "an unresolvable hostname is not itself treated as blocked -- the real network error surfaces at connect time"
    (is (false? (#'transport/destination-blocked? "this-host-does-not-resolve.invalid")))))

(deftest redirect-target-resolves-relative-locations-and-bounds-length
  (let [base (URI/create "https://api.example.test/original/path")]
    (testing "relative location resolves against the base"
      (is (= "https://api.example.test/next" (#'transport/redirect-target base "/next"))))
    (testing "absolute location is returned as-is"
      (is (= "https://other.example.test/y" (#'transport/redirect-target base "https://other.example.test/y"))))
    (testing "malformed location does not throw, returns nil"
      (is (nil? (#'transport/redirect-target base "http://[not-a-valid-host"))))
    (testing "a location exceeding http/max-url-bytes is refused"
      (let [huge (str "/" (apply str (repeat (inc http/max-url-bytes) \x)))]
        (is (nil? (#'transport/redirect-target base huge)))))))

(deftest truncate-to-byte-limit-trims-to-a-valid-utf8-boundary
  (is (= "abc" (#'transport/truncate-to-byte-limit "abc" 10)))
  (is (= "" (#'transport/truncate-to-byte-limit "abcdef" 0)))
  (let [s (apply str (repeat 100 \a))]
    (is (= 40 (count (#'transport/truncate-to-byte-limit s 40))))))

(deftest bounded-response-headers-caps-count-case-folds-and-truncates
  (testing "more than http/max-headers entries are capped"
    (let [many (into {} (map (fn [i] [(str "x-header-" i) [(str "v" i)]])
                             (range (+ 10 http/max-headers))))
          headers (HttpHeaders/of many (reify BiPredicate (test [_ _ _] true)))
          result (#'transport/bounded-response-headers headers)]
      (is (<= (count result) http/max-headers))))
  (testing "header names are case-folded so duplicates by case collapse to one keyword"
    (let [raw {"Content-Type" ["application/json"]}
          headers (HttpHeaders/of raw (reify BiPredicate (test [_ _ _] true)))
          result (#'transport/bounded-response-headers headers)]
      (is (= {:content-type "application/json"} result))))
  (testing "an oversized header value is truncated to fit the bound, not dropped"
    (let [big (apply str (repeat (+ 100 value/string-value-byte-limit) \a))
          raw {"x-big" [big]}
          headers (HttpHeaders/of raw (reify BiPredicate (test [_ _ _] true)))
          result (#'transport/bounded-response-headers headers)]
      (is (contains? result :x-big))
      (is (<= (value/utf8-byte-count! (:x-big result)) value/string-value-byte-limit)))))

;; ---------------------------------------------------------------------------
;; test-server plumbing (mirrors llm_transport_test.clj's own fake-server helper)
;; ---------------------------------------------------------------------------

(defn- respond! [exchange status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange)
      (.write bytes)
      (.close))))

(defn- fake-server [handlers]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (doseq [[path handler] handlers]
      (.createContext server path (reify HttpHandler (handle [_ ex] (handler ex)))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :origin (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn- stop! [{:keys [server]}] (.stop server 0))

(def source
  (str "(ns app.http (:export [post]) (:capabilities #{:http/post}))"
       "(defn post [request " (pr-str http/request-type) "] "
       (pr-str http/result-type) " (typed-cap-call :http/post "
       (pr-str http/request-type) " " (pr-str http/result-type) " request))"))

(defn- hosted [transport-fn origin]
  (let [provider (http/provider {:allowed-origins #{origin} :transport transport-fn})
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 4]}})))]
    (runtime/instantiate kir {:allow #{4} :providers {4 provider}})))

(defn- request-for [origin path headers body timeout-ms]
  [http/request-type (str origin path) headers body timeout-ms])

(defn- ok-payload
  "Destructures a `[http/result-type :ok [http/response-type status
  [http/header-set-type headers] body]]` result into
  `{:status :headers {kw->string} :body}`, or nil if the result was
  `:error`. Assertions below check this SUBSET rather than the raw typed
  value so they are not coupled to incidental headers
  `com.sun.net.httpserver` always adds on the wire (`content-length`,
  `date`) that this transport correctly and harmlessly passes through."
  [[_ tag payload]]
  (when (= :ok tag)
    (let [[_ status [_ headers] body] payload]
      {:status status
       :headers (into {} (map (fn [[_ name value]] [name value])) headers)
       :body body})))

;; A test-only widened origin canonicalizer accepting `http://` as well as
;; `https://` -- see ns docstring. The allow-list MEMBERSHIP check itself
;; (`contains? allowed-origins origin`, in both `http.cljc` and
;; `http-transport.cljc`) is never touched -- only the SCHEME acceptance.
;;
;; Note this must widen BOTH `kotoba.compiler.provider.http`'s own private
;; `https-origin` (used by `http/provider` itself to validate its
;; `:allowed-origins` construction option AND the guest's request URL at
;; `:invoke` time) AND `kotoba.compiler.provider.http-transport`'s
;; `canonical-origin` (used by the redirect loop) -- both independently
;; enforce HTTPS-only in production and both need relaxing for a local
;; plaintext loopback fixture to stand in for a remote HTTPS one.
(defn- widen-scheme [origin-str]
  (when (string? origin-str)
    (when-let [[_ scheme host port]
               (re-matches #"(https?)://([A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?)(?::([0-9]+))?(?:/[^ ]*)?"
                           origin-str)]
      (str scheme "://" (string/lower-case host) (when port (str ":" port))))))

(defn- widen-https-origin
  "Drop-in test replacement for `kotoba.compiler.provider.http`'s private
  `https-origin` -- same throw-on-failure contract, widened scheme
  acceptance."
  [url]
  (or (widen-scheme url)
      (throw (ex-info "HTTP URL must be an absolute HTTPS URL" {:phase :http-provider :url url}))))

(defmacro with-relaxed-guards
  "Relaxes exactly the guards a local plaintext loopback fixture would
  otherwise correctly trip (scheme, in both `http.cljc` and this
  transport's own redirect loop, plus this transport's destination-block
  check) -- see ns docstring. The allow-list MEMBERSHIP check is untouched
  in both places."
  [& body]
  `(with-redefs [http/https-origin widen-https-origin
                 transport/canonical-origin widen-scheme
                 transport/destination-blocked? (constantly false)]
     ~@body))

;; ---------------------------------------------------------------------------
;; group 2 -- end-to-end, through the typed http/provider boundary
;; ---------------------------------------------------------------------------

(deftest successful-post-crosses-the-typed-boundary-with-headers-and-body
  (let [seen (atom nil)
        {:keys [origin] :as fake}
        (fake-server
         {"/v1/items"
          (fn [ex]
            (reset! seen {:method (.getRequestMethod ex)
                          :body (slurp (.getRequestBody ex))
                          :content-type (.getFirst (.getRequestHeaders ex) "content-type")})
            (.. ex getResponseHeaders (add "Content-Type" "application/json"))
            (respond! ex 201 "{\"ok\":true}"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
              runtime (hosted transport-fn origin)
              headers [http/header-set-type [[http/header-type :content-type "application/json"]]]
              request (request-for origin "/v1/items" headers "{\"name\":\"kotoba\"}" 5000)]
          (is (= {:status 201 :body "{\"ok\":true}"
                  :headers {:content-type "application/json"}}
                 (-> ((:invoke runtime) 'post [request])
                     ok-payload
                     (update :headers select-keys [:content-type]))))
          (is (= {:method "POST" :body "{\"name\":\"kotoba\"}" :content-type "application/json"}
                 @seen))))
      (finally (stop! fake)))))

(deftest non-2xx-status-is-a-plain-ok-response-not-a-typed-error
  (testing "the HTTP capability (unlike LLM) never maps status codes to errors -- :error is transport-failure-only"
    (let [{:keys [origin] :as fake}
          (fake-server {"/missing" (fn [ex] (respond! ex 404 "not found"))})]
      (try
        (with-relaxed-guards
          (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
                runtime (hosted transport-fn origin)
                request (request-for origin "/missing" [http/header-set-type []] "" 2000)]
            (is (= {:status 404 :body "not found"}
                   (select-keys (ok-payload ((:invoke runtime) 'post [request])) [:status :body])))))
        (finally (stop! fake))))))

(deftest redirect-within-the-allow-list-is-followed-to-completion
  (let [hits (atom [])
        {:keys [origin] :as fake}
        (fake-server
         {"/start" (fn [ex]
                    (swap! hits conj "/start")
                    (.. ex getResponseHeaders (add "Location" "/final"))
                    (respond! ex 302 ""))
          "/final" (fn [ex]
                    (swap! hits conj "/final")
                    (respond! ex 200 "done"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
              runtime (hosted transport-fn origin)
              request (request-for origin "/start" [http/header-set-type []] "" 2000)]
          (is (= {:status 200 :body "done"}
                 (select-keys (ok-payload ((:invoke runtime) 'post [request])) [:status :body])))
          (is (= ["/start" "/final"] @hits))))
      (finally (stop! fake)))))

(deftest redirect-outside-the-allow-list-is-not-followed
  (let [hits (atom [])
        {:keys [origin] :as fake}
        (fake-server
         {"/start" (fn [ex]
                    (swap! hits conj "/start")
                    (.. ex getResponseHeaders (add "Location" "http://192.0.2.1:1/elsewhere"))
                    (respond! ex 302 ""))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
              runtime (hosted transport-fn origin)
              request (request-for origin "/start" [http/header-set-type []] "" 2000)]
          (let [[_ tag payload] ((:invoke runtime) 'post [request])]
            (is (= :ok tag) "not-allowed redirect target -> the 3xx itself is returned, not an error")
            (let [[_ status _ _] payload]
              (is (= 302 status))))
          (is (= ["/start"] @hits) "only the first hop is ever requested")))
      (finally (stop! fake)))))

(deftest max-redirects-zero-disables-following-entirely
  (let [hits (atom [])
        {:keys [origin] :as fake}
        (fake-server
         {"/start" (fn [ex]
                    (swap! hits conj "/start")
                    (.. ex getResponseHeaders (add "Location" "/final"))
                    (respond! ex 302 ""))
          "/final" (fn [ex] (swap! hits conj "/final") (respond! ex 200 "done"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport {:allowed-origins #{origin} :max-redirects 0})
              runtime (hosted transport-fn origin)
              request (request-for origin "/start" [http/header-set-type []] "" 2000)]
          (let [[_ tag [_ status _ _]] ((:invoke runtime) 'post [request])]
            (is (= :ok tag))
            (is (= 302 status)))
          (is (= ["/start"] @hits))))
      (finally (stop! fake)))))

(deftest real-destination-blocked-check-refuses-a-loopback-target
  (testing "with destination-blocked? left REAL (not redefed), a loopback fixture is refused end-to-end"
    (let [{:keys [origin] :as fake} (fake-server {"/x" (fn [ex] (respond! ex 200 "should never be reached"))})]
      (try
        ;; Only the scheme guards relaxed (both http.cljc's own and this
        ;; transport's) -- destination-blocked? is deliberately left REAL.
        (with-redefs [http/https-origin widen-https-origin
                      transport/canonical-origin widen-scheme]
          (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
                runtime (hosted transport-fn origin)
                request (request-for origin "/x" [http/header-set-type []] "" 2000)]
            (is (= [http/result-type :error
                    [http/error-type :http/destination-blocked
                     (str "resolved address for 127.0.0.1 is not an allowed destination"
                          " (loopback/link-local/private/multicast)")
                     false]]
                   ((:invoke runtime) 'post [request])))))
        (finally (stop! fake))))))

(deftest restricted-headers-are-not-forwarded-but-do-not-break-the-call
  (let [seen (atom nil)
        {:keys [origin] :as fake}
        (fake-server
         {"/h" (fn [ex]
                (reset! seen {:host (.getFirst (.getRequestHeaders ex) "host")
                             :x-my-header (.getFirst (.getRequestHeaders ex) "x-my-header")})
                (respond! ex 200 "ok"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport {:allowed-origins #{origin}})
              runtime (hosted transport-fn origin)
              ;; "host" is a restricted header the JDK client refuses to set
              ;; directly -- this namespace drops it defensively rather than
              ;; letting an IllegalArgumentException blow up the call. HTTP/1.1
              ;; always sends SOME Host header (the JDK client fills in the
              ;; real target automatically); the property under test is that
              ;; the GUEST'S attempted override value never reaches the wire.
              headers [http/header-set-type [[http/header-type :host "attacker.example"]
                                             [http/header-type :x-my-header "value"]]]
              request (request-for origin "/h" headers "" 2000)]
          (is (= {:status 200 :body "ok"}
                 (select-keys (ok-payload ((:invoke runtime) 'post [request])) [:status :body])))
          (is (not= "attacker.example" (:host @seen))
              "the guest-supplied Host override never reached the wire")
          (is (= "value" (:x-my-header @seen)) "an ordinary header still reaches the server")))
      (finally (stop! fake)))))

(deftest production-transport-requires-a-non-empty-allowed-origins-set
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"allowed-origins"
                        (transport/production-transport {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"allowed-origins"
                        (transport/production-transport {:allowed-origins #{}}))))

(deftest production-transport-rejects-non-canonical-allowed-origins
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical"
                        (transport/production-transport {:allowed-origins #{"https://Api.Example.Test"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"canonical"
                        (transport/production-transport {:allowed-origins #{"http://api.example.test"}}))))

(deftest on-call-audit-hook-observes-every-attempt-without-affecting-the-result
  (let [events (atom [])
        {:keys [origin] :as fake}
        (fake-server {"/ok" (fn [ex] (respond! ex 200 "ok"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport
                            {:allowed-origins #{origin}
                             :on-call (fn [event] (swap! events conj event))})
              runtime (hosted transport-fn origin)
              request (request-for origin "/ok" [http/header-set-type []] "" 2000)]
          ((:invoke runtime) 'post [request])
          (is (= [{:url (str origin "/ok") :status 200 :error? false}]
                 (mapv #(dissoc % :latency-ms) @events)))))
      (finally (stop! fake)))))

(deftest on-call-hook-exceptions-are-swallowed-and-never-break-the-call
  (let [{:keys [origin] :as fake}
        (fake-server {"/ok" (fn [ex] (respond! ex 200 "ok"))})]
    (try
      (with-relaxed-guards
        (let [transport-fn (transport/production-transport
                            {:allowed-origins #{origin}
                             :on-call (fn [_] (throw (ex-info "audit sink is down" {})))})
              runtime (hosted transport-fn origin)
              request (request-for origin "/ok" [http/header-set-type []] "" 2000)]
            (is (= {:status 200 :body "ok"}
                   (select-keys (ok-payload ((:invoke runtime) 'post [request])) [:status :body])))))
      (finally (stop! fake)))))

;; Note: the cljs/nbb host branch of `production-transport` is a documented
;; gap (ns docstring: no synchronous HTTP primitive available there today),
;; not something this JVM test suite can exercise -- see docs/adr/0066.
