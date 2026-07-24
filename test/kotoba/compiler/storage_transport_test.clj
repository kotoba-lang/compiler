(ns kotoba.compiler.storage-transport-test
  "Unit and integration tests for `kotoba.compiler.provider.storage-transport`.

  Two groups, deliberately separated (same split as
  `http_transport_test.clj`):

  1. **Pure/deterministic unit tests, no network at all** -- of the private
     helpers directly (`kw->wire`, `truncate-to-byte-limit`, `wire->reply`/
     `finalize-reply`, `bounded-error-code`, `error-for-status`,
     `resolve-endpoint`). These prove the wire-shape mapping and the
     fail-closed sanitization of a malformed upstream reply work correctly
     in isolation.
  2. **End-to-end wire-protocol / typed-boundary tests** against a local
     `com.sun.net.httpserver.HttpServer` fake that implements a small
     stateful in-memory key/value backend speaking this namespace's own wire
     protocol (same fake-server technique as `llm_transport_test.clj` /
     `http_transport_test.clj`), run through `production-transport` and the
     full typed `kotoba.compiler.provider.storage/provider` boundary --
     get/put/delete round trips, conditional-version conflicts, a
     malformed/oversized upstream reply sanitized rather than crashing, a
     non-2xx status mapped to a typed error, and the `:on-call` audit hook.

     No live-network integration test is included: unlike ADR 0064's LLM
     transport (a fixed, real, already-deployed `murakumo-main` endpoint),
     there is no repo-wide well-known storage backend this transport's wire
     protocol is designed to be pinned against -- see
     docs/adr/0071-production-storage-transport-host-configured-kv-endpoint.md
     'Remaining gaps'."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.storage :as storage]
            [kotoba.compiler.provider.storage-transport :as transport]
            [kotoba.compiler.reference-runtime :as runtime]
            [kotoba.compiler.value :as value])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress)))

;; ---------------------------------------------------------------------------
;; group 1 -- pure unit tests, no network
;; ---------------------------------------------------------------------------

(deftest kw->wire-round-trips-qualified-and-unqualified-keywords
  (is (= "example/app-data" (#'transport/kw->wire :example/app-data)))
  (is (= "profile/name" (#'transport/kw->wire :profile/name)))
  (is (= "solo" (#'transport/kw->wire :solo))))

(deftest truncate-to-byte-limit-trims-to-a-valid-utf8-boundary
  (is (= "abc" (#'transport/truncate-to-byte-limit "abc" 10)))
  (is (= "" (#'transport/truncate-to-byte-limit "abcdef" 0)))
  (let [s (apply str (repeat 100 \a))]
    (is (= 40 (count (#'transport/truncate-to-byte-limit s 40))))))

(deftest resolve-endpoint-requires-explicit-host-configuration
  (testing "an explicit :endpoint wins outright"
    (is (= "https://kv.example.test" (transport/resolve-endpoint {:endpoint "https://kv.example.test"}))))
  (testing "no :endpoint and no env var throws -- no ambient default"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":endpoint"
                          (transport/resolve-endpoint {})))))

(deftest wire-reply-maps-every-tag-to-the-typed-boundary-shape
  (is (= {:tag :found :value "hello" :version 3}
         (#'transport/wire->reply {:tag "found" :value "hello" :version 3})))
  (is (= {:tag :missing}
         (#'transport/wire->reply {:tag "missing"})))
  (is (= {:tag :written :value "hello" :version 4}
         (#'transport/wire->reply {:tag "written" :value "hello" :version 4})))
  (is (= {:tag :deleted}
         (#'transport/wire->reply {:tag "deleted"})))
  (is (= {:tag :conflict :current-version 7}
         (#'transport/wire->reply {:tag "conflict" :current_version 7})))
  (is (= {:tag :conflict :current-version nil}
         (#'transport/wire->reply {:tag "conflict" :current_version nil})))
  (is (= {:tag :error :error {:code :storage/upstream-down :message "backend unreachable" :retryable true}}
         (#'transport/wire->reply {:tag "error"
                                   :error {:code "storage/upstream-down"
                                           :message "backend unreachable"
                                           :retryable true}}))))

(deftest wire-reply-rejects-an-unknown-tag
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown wire tag"
                        (#'transport/wire->reply {:tag "not-a-real-tag"}))))

(deftest finalize-reply-sanitizes-an-oversized-value-instead-of-crashing-storage-cljc
  (let [huge (apply str (repeat (+ 100 storage/max-value-bytes) \a))
        sanitized (#'transport/finalize-reply {:tag :found :value huge :version 1})]
    (is (= :found (:tag sanitized)))
    (is (<= (value/utf8-byte-count! (:value sanitized)) storage/max-value-bytes))))

(deftest finalize-reply-turns-an-invalid-version-into-a-typed-error-not-a-fabricated-version
  (testing "a non-integer version"
    (is (= [:error :storage/invalid-response]
           ((juxt :tag (comp :code :error))
            (#'transport/finalize-reply {:tag :found :value "x" :version "not-a-number"})))))
  (testing "a zero/negative version"
    (is (= [:error :storage/invalid-response]
           ((juxt :tag (comp :code :error))
            (#'transport/finalize-reply {:tag :written :value "x" :version 0})))))
  (testing "a malformed current-version on conflict"
    (is (= [:error :storage/invalid-response]
           ((juxt :tag (comp :code :error))
            (#'transport/finalize-reply {:tag :conflict :current-version "nope"}))))))

(deftest bounded-error-code-truncates-and-never-throws
  (is (= :storage/upstream-error (#'transport/bounded-error-code nil)))
  (is (= :storage/upstream-error (#'transport/bounded-error-code "")))
  (let [huge (apply str (repeat (+ 100 value/keyword-value-byte-limit) \a))
        code (#'transport/bounded-error-code huge)]
    (is (keyword? code))
    (is (<= (value/utf8-byte-count! (name code)) value/keyword-value-byte-limit))))

(deftest error-for-status-maps-common-statuses-to-retryable-and-code
  (is (= {:code :storage/rate-limited :retryable true}
         (-> (#'transport/error-for-status 429 "slow down") :error (select-keys [:code :retryable]))))
  (is (= {:code :storage/unauthorized :retryable false}
         (-> (#'transport/error-for-status 401 "nope") :error (select-keys [:code :retryable]))))
  (is (= {:code :storage/upstream-error :retryable true}
         (-> (#'transport/error-for-status 503 "down") :error (select-keys [:code :retryable]))))
  (is (= {:code :storage/request-rejected :retryable false}
         (-> (#'transport/error-for-status 400 "bad request") :error (select-keys [:code :retryable])))))

;; ---------------------------------------------------------------------------
;; test-server plumbing -- a small stateful in-memory KV backend speaking
;; this namespace's own wire protocol, mirroring llm/http's own fake-server
;; helper technique.
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

(defn- kv-backend-handler
  "A minimal, genuinely stateful fake KV backend implementing this
  namespace's own wire protocol -- so the end-to-end tests below exercise
  the real request/response mapping (including conditional-version
  conflicts) rather than a one-shot canned reply."
  [store]
  (fn [exchange]
    (let [seen-headers (.getRequestHeaders exchange)
          parsed (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword)
          store-key [(:namespace parsed) (:key parsed)]
          reply
          (case (:operation parsed)
            "get"
            (if-let [entry (get @store store-key)]
              {"tag" "found" "value" (:value entry) "version" (:version entry)}
              {"tag" "missing"})

            "put"
            (let [current (get @store store-key)
                  expected (:expected_version parsed)]
              (if (or (nil? expected) (= expected (:version current)))
                (let [next-version (inc (:version current 0))]
                  (swap! store assoc store-key {:value (:value parsed) :version next-version})
                  {"tag" "written" "value" (:value parsed) "version" next-version})
                {"tag" "conflict" "current_version" (:version current)}))

            "delete"
            (let [current (get @store store-key)
                  expected (:expected_version parsed)]
              (cond
                (and expected current (not= expected (:version current)))
                {"tag" "conflict" "current_version" (:version current)}
                :else (do (swap! store dissoc store-key) {"tag" "deleted"})))

            {"tag" "error" "error" {"code" "storage/unknown-operation"
                                    "message" (str "unknown operation " (:operation parsed))
                                    "retryable" false}})]
      ;; record the auth header for tests that check credential forwarding
      (swap! store assoc ::last-authorization (.getFirst seen-headers "authorization"))
      (respond! exchange 200 (json/write-str reply)))))

(def storage-provider-test-source
  (str "(ns app.storage (:export [transact]) (:capabilities #{:storage/transact}))"
       "(defn transact [request " (pr-str storage/request-type) "] "
       (pr-str storage/result-type) " (typed-cap-call :storage/transact "
       (pr-str storage/request-type) " " (pr-str storage/result-type) " request))"))

(defn- hosted [transport-fn]
  (let [provider (storage/provider {:storage-namespace :example/app-data
                                    :transport transport-fn})
        kir (ir/lower (:hir (compiler/check-source storage-provider-test-source
                                                    {:allow #{[:cap/call 12]}})))]
    (runtime/instantiate kir {:allow #{12} :providers {12 provider}})))

(defn- get-request [key]
  [storage/request-type :get [storage/get-type key]])

(defn- expected-version-option
  "The generic `[:option :i64]` value shape the runtime's
  `value/bounded-typed-value!` enforces: exactly 2 elements (`[type
  false]`) when absent, exactly 3 (`[type true version]`) when present --
  a trailing placeholder value alongside `false` is rejected outright (see
  `kotoba.compiler.value/bounded-typed-value!`'s `:option` case). Mirrors
  `storage.cljc`'s own private `option-version` construction exactly."
  [expected]
  (if (nil? expected)
    [storage/expected-version-type false]
    [storage/expected-version-type true expected]))

(defn- put-request [key value expected]
  [storage/request-type :put
   [storage/put-type key value (expected-version-option expected)]])

(defn- delete-request [key expected]
  [storage/request-type :delete
   [storage/delete-type key (expected-version-option expected)]])

;; ---------------------------------------------------------------------------
;; group 2 -- end-to-end, through the typed storage/provider boundary
;; ---------------------------------------------------------------------------

(deftest put-then-get-round-trips-through-a-real-wire-call
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        (is (= [storage/result-type :written [storage/entry-type :profile/name "Kotoba" 1]]
               ((:invoke runtime) 'transact [(put-request :profile/name "Kotoba" nil)])))
        (is (= [storage/result-type :found [storage/entry-type :profile/name "Kotoba" 1]]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

(deftest get-of-an-absent-key-is-missing
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        (is (= [storage/result-type :missing false]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

(deftest a-version-mismatched-put-is-a-typed-conflict-not-an-error
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        ((:invoke runtime) 'transact [(put-request :profile/name "first" nil)])
        (is (= [storage/result-type :conflict
                [storage/conflict-type :profile/name [storage/expected-version-type true 1]]]
               ((:invoke runtime) 'transact [(put-request :profile/name "second" 99)]))))
      (finally (stop! fake)))))

(deftest delete-then-get-shows-the-key-is-gone
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        ((:invoke runtime) 'transact [(put-request :profile/name "Kotoba" nil)])
        (is (= [storage/result-type :deleted true]
               ((:invoke runtime) 'transact [(delete-request :profile/name nil)])))
        (is (= [storage/result-type :missing false]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

(deftest an-oversized-upstream-value-is-truncated-not-a-crash
  (let [huge (apply str (repeat (+ 100 storage/max-value-bytes) \a))
        {:keys [origin] :as fake}
        (fake-server
         {"/storage/v1/transact"
          (fn [ex]
            (slurp (.getRequestBody ex))
            (respond! ex 200 (json/write-str {"tag" "found" "value" huge "version" 1})))})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)
            [_ tag [_ _ value _]] ((:invoke runtime) 'transact [(get-request :profile/name)])]
        (is (= :found tag))
        (is (<= (value/utf8-byte-count! value) storage/max-value-bytes)))
      (finally (stop! fake)))))

(deftest a-malformed-upstream-version-becomes-a-typed-error-not-a-crash
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/storage/v1/transact"
          (fn [ex]
            (slurp (.getRequestBody ex))
            (respond! ex 200 (json/write-str {"tag" "found" "value" "x" "version" "not-a-number"})))})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        (is (= [storage/result-type :error
                [storage/error-type :storage/invalid-response
                 "storage transport returned a malformed value/version for found/written"
                 false]]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

(deftest a-non-2xx-status-is-a-typed-retryable-error
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/storage/v1/transact"
          (fn [ex] (slurp (.getRequestBody ex)) (respond! ex 503 "backend unavailable"))})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)
            [_ tag [_ code _ retryable]] ((:invoke runtime) 'transact [(get-request :profile/name)])]
        (is (= :error tag))
        (is (= :storage/upstream-error code))
        (is (true? retryable)))
      (finally (stop! fake)))))

(deftest malformed-json-body-is-redacted-to-a-generic-transport-error
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/storage/v1/transact"
          (fn [ex] (slurp (.getRequestBody ex)) (respond! ex 200 "not json at all"))})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin})
            runtime (hosted transport-fn)]
        (is (= [storage/result-type :error
                [storage/error-type :storage/transport "storage provider failed" false]]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

(deftest the-api-key-is-forwarded-as-a-bearer-token
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport {:endpoint origin :api-key "s3cr3t"})
            runtime (hosted transport-fn)]
        ((:invoke runtime) 'transact [(get-request :profile/name)])
        (is (= "Bearer s3cr3t" (get @store ::last-authorization))))
      (finally (stop! fake)))))

(deftest on-call-audit-hook-observes-every-attempt-without-affecting-the-result
  (let [store (atom {})
        events (atom [])
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint origin :on-call (fn [event] (swap! events conj event))})
            runtime (hosted transport-fn)]
        ((:invoke runtime) 'transact [(get-request :profile/name)])
        (is (= [{:namespace :example/app-data :operation :get :key :profile/name
                 :status :ok :http-status 200}]
               (mapv #(dissoc % :latency-ms) @events))))
      (finally (stop! fake)))))

(deftest on-call-hook-exceptions-are-swallowed-and-never-break-the-call
  (let [store (atom {})
        {:keys [origin] :as fake} (fake-server {"/storage/v1/transact" (kv-backend-handler store)})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint origin
                           :on-call (fn [_] (throw (ex-info "audit sink is down" {})))})
            runtime (hosted transport-fn)]
        (is (= [storage/result-type :missing false]
               ((:invoke runtime) 'transact [(get-request :profile/name)]))))
      (finally (stop! fake)))))

;; Note: the cljs/nbb host branch of `production-transport` is a documented
;; gap (ns docstring: no synchronous HTTP primitive available there today),
;; not something this JVM test suite can exercise -- see
;; docs/adr/0071-production-storage-transport-host-configured-kv-endpoint.md.
