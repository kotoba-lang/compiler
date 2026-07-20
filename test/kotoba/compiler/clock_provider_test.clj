(ns kotoba.compiler.clock-provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.provider.clock :as clock]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  (str "(ns app.clock (:export [now]) (:capabilities #{:clock/now}))"
       "(defn now [request " (pr-str clock/request-type) "] "
       (pr-str clock/result-type) " (typed-cap-call :clock/now "
       (pr-str clock/request-type) " " (pr-str clock/result-type) " request))"))

(defn- hosted [wall-now monotonic-now]
  (let [provider (clock/provider {:wall-now wall-now :monotonic-now monotonic-now})
        kir (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 7]}})))]
    (runtime/instantiate kir {:allow #{7} :providers {7 provider}})))

(deftest wall-and-monotonic-domains-are-distinct-and-ordered
  (let [ticks (atom [1000 1001])
        runtime (hosted (constantly 1700000000000)
                        #(let [tick (first @ticks)] (swap! ticks subvec 1) tick))]
    (is (= [clock/result-type :wall [clock/wall-type 1700000000000 1]]
           ((:invoke runtime) 'now [[clock/request-type :wall false]])))
    (is (= [clock/result-type :monotonic [clock/monotonic-type 1000 2]]
           ((:invoke runtime) 'now [[clock/request-type :monotonic false]])))
    (is (= [clock/result-type :monotonic [clock/monotonic-type 1001 3]]
           ((:invoke runtime) 'now [[clock/request-type :monotonic false]])))))

(deftest monotonic-regression-is-a-typed-error
  (let [ticks (atom [10 9])
        runtime (hosted (constantly 0)
                        #(let [tick (first @ticks)] (swap! ticks subvec 1) tick))]
    ((:invoke runtime) 'now [[clock/request-type :monotonic false]])
    (is (= [clock/result-type :error
            [clock/error-type :clock/regressed "monotonic clock regressed"]]
           ((:invoke runtime) 'now [[clock/request-type :monotonic false]])))))

(deftest source-exceptions-are-redacted-and-typed
  (let [runtime (hosted #(throw (ex-info "secret host detail" {})) (constantly 0))]
    (is (= [clock/result-type :error
            [clock/error-type :clock/source "clock source failed"]]
           ((:invoke runtime) 'now [[clock/request-type :wall false]])))))
