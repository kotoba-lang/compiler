(ns kotoba.compiler.llm-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.llm :as llm]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.llm (:export [generate]) (:capabilities #{:llm/generate}))"
       "(defn generate [request " (pr-str llm/request-type) "] "
       (pr-str llm/result-type) " (typed-cap-call :llm/generate "
       (pr-str llm/request-type) " " (pr-str llm/result-type) " request))"))

(defn- hosted [transport]
  (let [provider (llm/provider {:allowed-models #{:example/text-v1}
                                :transport transport})
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 11]}})))]
    (runtime/instantiate kir {:allow #{11} :providers {11 provider}})))

(deftest generation-crosses-only-the-typed-boundary
  (let [seen (atom nil)
        runtime (hosted (fn [request]
                          (reset! seen request)
                          {:text "Hello" :finish-reason :llm/stop
                           :input-tokens 12 :output-tokens 3}))
        request [llm/request-type :example/text-v1 "Be concise" "Say hello" 64 250]]
    (is (= [llm/result-type :ok
            [llm/completion-type "Hello" :llm/stop [llm/usage-type 12 3]]]
           ((:invoke runtime) 'generate [request])))
    (is (= {:model :example/text-v1 :system "Be concise" :prompt "Say hello"
            :max-output-tokens 64 :temperature-milli 250}
           @seen))))

(deftest models-and-budgets-fail-closed
  (let [called? (atom false)
        runtime (hosted (fn [_] (reset! called? true)))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"model is not allowed"
         ((:invoke runtime) 'generate
          [[llm/request-type :example/other "" "hello" 64 0]])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"token budget is outside"
         ((:invoke runtime) 'generate
          [[llm/request-type :example/text-v1 "" "hello" 4097 0]])))
    (is (false? @called?))))

(deftest provider-errors-and-exceptions-are-typed
  (let [reported (hosted (fn [_] {:error {:code :llm/rate-limited
                                          :message "try later"
                                          :retryable true}}))
        crashed (hosted (fn [_] (throw (ex-info "secret credential" {}))))
        request [llm/request-type :example/text-v1 "" "hello" 64 0]]
    (is (= [llm/result-type :error
            [llm/error-type :llm/rate-limited "try later" true]]
           ((:invoke reported) 'generate [request])))
    (is (= [llm/result-type :error
            [llm/error-type :llm/transport "provider failed" false]]
           ((:invoke crashed) 'generate [request])))))
