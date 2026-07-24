(ns kotoba.compiler.llm-transport-test
  "Unit tests for `kotoba.compiler.provider.llm-transport` run against local
  fake HTTP servers (`com.sun.net.httpserver`) -- no real network access, no
  network flakiness in CI. These exercise the production transport's own
  request/response mapping and the ①/③ resolution steps deterministically.

  A separate, REAL integration test against the live murakumo-main endpoint
  is gated behind `KOTOBA_LLM_INTEGRATION_TEST=1` (and requires
  `MURAKUMO_API_KEY` to be set) so ordinary `clojure -M:test` runs never
  depend on network access or a live credential -- see
  `real-murakumo-main-endpoint-answers` below."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.llm :as llm]
            [kotoba.compiler.provider.llm-transport :as transport]
            [kotoba.compiler.reference-runtime :as runtime])
  (:import (com.sun.net.httpserver HttpServer HttpHandler)
           (java.net InetSocketAddress)))

(defn- respond! [exchange status ^String body]
  (let [bytes (.getBytes body "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (doto (.getResponseBody exchange)
      (.write bytes)
      (.close))))

(defn- fake-server
  "Starts a `com.sun.net.httpserver` HttpServer on an OS-assigned free port
  bound to 127.0.0.1. `handlers` is {path (fn [exchange] ...)}. Returns
  {:server s :port p :origin \"http://127.0.0.1:p\"}. Caller must `.stop`."
  [handlers]
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
  (str "(ns app.llm (:export [generate]) (:capabilities #{:llm/generate}))"
       "(defn generate [request " (pr-str llm/request-type) "] "
       (pr-str llm/result-type) " (typed-cap-call :llm/generate "
       (pr-str llm/request-type) " " (pr-str llm/result-type) " request))"))

;; ---------------------------------------------------------------------------
;; resolve-model — ① override / ③ fallback (deterministic, no live network)
;; ---------------------------------------------------------------------------

(deftest override-wins-outright-with-no-network-call
  (let [http-client (-> (java.net.http.HttpClient/newBuilder) .build)]
    (is (= {:endpoint "https://pinned.example.test" :model "pinned-model" :resolution :override}
           (transport/resolve-model
            {:http-client http-client
             :endpoint-override "https://pinned.example.test"
             :model-override "pinned-model"})))))

(deftest alias-resolution-consults-only-alias-for-not-endpoint
  (let [{:keys [origin] :as fake} (fake-server
                                   {"/infer/models/murakumo-main"
                                    (fn [ex]
                                      (respond! ex 200
                                                (str "{\"id\":\"murakumo-main\","
                                                     "\"alias-for\":\"qwen3.6-35b-a3b\","
                                                     "\"endpoint\":\"https://a-totally-different-shaped-host.test/v1/chat/completions\"}")))})
        http-client (-> (java.net.http.HttpClient/newBuilder) .build)]
    (try
      (let [alias-url (str origin "/infer/models/murakumo-main")
            resolved (#'transport/http-get-json http-client alias-url 2000)]
        (is (= "qwen3.6-35b-a3b" (:alias-for resolved)))
        (is (= "https://a-totally-different-shaped-host.test/v1/chat/completions"
               (:endpoint resolved))
            "the alias entry DOES carry an :endpoint field, but resolve-model must never route there (docstring: different API shape/host, verified live against api.murakumo.cloud)"))
      (finally (stop! fake)))))

(deftest fallback-when-alias-endpoint-is-unreachable
  (let [http-client (-> (java.net.http.HttpClient/newBuilder) .build)
        ;; an unroutable TEST-NET-1 address (RFC 5737) that never accepts a
        ;; connection -- deterministic, fast (connect-timeout below), and
        ;; makes no real network request to any live host.
        unreachable-endpoint "http://192.0.2.1:1"]
    (with-redefs [transport/default-endpoint unreachable-endpoint]
      (is (= {:endpoint unreachable-endpoint :model transport/alias-name :resolution :fallback}
             (transport/resolve-model {:http-client http-client :connect-timeout-ms 300}))))))

;; ---------------------------------------------------------------------------
;; production-transport — full request/response mapping against a fake
;; `/v1/messages` server (Anthropic Messages API shape)
;; ---------------------------------------------------------------------------

(defn- hosted [transport-fn]
  (let [provider (llm/provider {:allowed-models #{:kotoba/murakumo-main} :transport transport-fn})
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 11]}})))]
    (runtime/instantiate kir {:allow #{11} :providers {11 provider}})))

(deftest successful-generation-crosses-the-typed-boundary
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex]
            (respond! ex 200
                      (str "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],"
                           "\"stop_reason\":\"end_turn\","
                           "\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}")))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "Be concise" "Say hello" 64 250]]
        (is (= [llm/result-type :ok
                [llm/completion-type "hello" :end_turn [llm/usage-type 12 3]]]
               ((:invoke runtime) 'generate [request]))))
      (finally (stop! fake)))))

(deftest empty-content-yields-empty-text-not-nil
  (testing "a refusal / thinking-only reply (no text block) still round-trips"
    (let [{:keys [origin] :as fake}
          (fake-server
           {"/v1/messages"
            (fn [ex]
              (respond! ex 200
                        (str "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"...\"}],"
                             "\"stop_reason\":\"max_tokens\","
                             "\"usage\":{\"input_tokens\":5,\"output_tokens\":16}}")))})]
      (try
        (let [transport-fn (transport/production-transport
                            {:endpoint-override origin :model-override "test-model"})
              runtime (hosted transport-fn)
              request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
          (is (= [llm/result-type :ok
                  [llm/completion-type "" :max_tokens [llm/usage-type 5 16]]]
                 ((:invoke runtime) 'generate [request]))))
        (finally (stop! fake))))))

(deftest http-429-maps-to-a-retryable-typed-error
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex] (respond! ex 429 "{\"error\":{\"message\":\"slow down\"}}"))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        (is (= [llm/result-type :error [llm/error-type :llm/rate-limited
                                        "murakumo transport HTTP 429: {\"error\":{\"message\":\"slow down\"}}"
                                        true]]
               ((:invoke runtime) 'generate [request]))))
      (finally (stop! fake)))))

(deftest http-401-maps-to-a-non-retryable-typed-error
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex] (respond! ex 401 "{\"error\":{\"message\":\"invalid key\"}}"))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        (is (= [llm/result-type :error [llm/error-type :llm/unauthorized
                                        "murakumo transport HTTP 401: {\"error\":{\"message\":\"invalid key\"}}"
                                        false]]
               ((:invoke runtime) 'generate [request]))))
      (finally (stop! fake)))))

(deftest http-500-maps-to-a-retryable-typed-error
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex] (respond! ex 500 "{\"error\":{\"message\":\"boom\"}}"))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        (is (= [llm/result-type :error [llm/error-type :llm/upstream-error
                                        "murakumo transport HTTP 500: {\"error\":{\"message\":\"boom\"}}"
                                        true]]
               ((:invoke runtime) 'generate [request]))))
      (finally (stop! fake)))))

(deftest api-key-is-sent-as-a-bearer-header-when-provided
  (let [seen-auth (atom nil)
        {:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex]
            (reset! seen-auth (.getFirst (.getRequestHeaders ex) "authorization"))
            (respond! ex 200
                      (str "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                           "\"stop_reason\":\"end_turn\","
                           "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"
                           :api-key "secret-token-value"})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        ((:invoke runtime) 'generate [request])
        (is (= "Bearer secret-token-value" @seen-auth)))
      (finally (stop! fake)))))

(deftest on-call-audit-hook-observes-every-attempt-without-affecting-the-result
  (let [events (atom [])
        {:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex]
            (respond! ex 200
                      (str "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                           "\"stop_reason\":\"end_turn\","
                           "\"usage\":{\"input_tokens\":2,\"output_tokens\":1}}")))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"
                           :on-call (fn [event] (swap! events conj event))})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        (is (= [llm/result-type :ok [llm/completion-type "ok" :end_turn [llm/usage-type 2 1]]]
               ((:invoke runtime) 'generate [request])))
        (is (= [{:resolution :override :wire-model "test-model" :status :ok
                 :input-tokens 2 :output-tokens 1}]
               (mapv #(dissoc % :latency-ms) @events))))
      (finally (stop! fake)))))

(deftest on-call-hook-exceptions-are-swallowed-and-never-break-the-call
  (let [{:keys [origin] :as fake}
        (fake-server
         {"/v1/messages"
          (fn [ex]
            (respond! ex 200
                      (str "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                           "\"stop_reason\":\"end_turn\","
                           "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")))})]
    (try
      (let [transport-fn (transport/production-transport
                          {:endpoint-override origin :model-override "test-model"
                           :on-call (fn [_] (throw (ex-info "audit sink is down" {})))})
            runtime (hosted transport-fn)
            request [llm/request-type :kotoba/murakumo-main "" "hi" 16 0]]
        (is (= [llm/result-type :ok [llm/completion-type "ok" :end_turn [llm/usage-type 1 1]]]
               ((:invoke runtime) 'generate [request]))))
      (finally (stop! fake)))))

;; Note: the cljs/nbb host branch of `production-transport` is a documented
;; gap (ns docstring: no synchronous HTTP primitive available there today),
;; not something this JVM test suite can exercise -- see docs/adr/0064.

;; ---------------------------------------------------------------------------
;; REAL integration test — gated, opt-in, never runs in a normal `clojure
;; -M:test` invocation. Requires KOTOBA_LLM_INTEGRATION_TEST=1 AND
;; MURAKUMO_API_KEY (kagi vault item MURAKUMO_CRITIC_TOKEN, compartment
;; gftdcojp, per 90-docs/... secrets map -- "new /v1/messages consumers can
;; onboard on the same secondary slot without rotation").
;; ---------------------------------------------------------------------------

(defn- integration-enabled? []
  (and (= "1" (System/getenv "KOTOBA_LLM_INTEGRATION_TEST"))
       (seq (System/getenv "MURAKUMO_API_KEY"))))

(deftest real-murakumo-main-endpoint-answers
  (if-not (integration-enabled?)
    (println "skipping real-murakumo-main-endpoint-answers: set KOTOBA_LLM_INTEGRATION_TEST=1 and MURAKUMO_API_KEY to run")
    (let [transport-fn (transport/production-transport {})
          runtime (hosted transport-fn)
          request [llm/request-type :kotoba/murakumo-main
                   "You are terse. Reply with only the requested word, nothing else."
                   "Reply with exactly the word: pong" 300 0]
          [result-type tag payload] ((:invoke runtime) 'generate [request])]
      (is (= llm/result-type result-type))
      (is (= :ok tag) (str "expected :ok, got " tag " payload " payload))
      (when (= :ok tag)
        (let [[_ text finish-reason [_ input-tokens output-tokens]] payload]
          (is (string? text))
          (is (keyword? finish-reason))
          (is (pos? input-tokens))
          (is (pos? output-tokens)))))))
