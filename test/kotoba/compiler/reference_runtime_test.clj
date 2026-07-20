(ns kotoba.compiler.reference-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.reference-runtime :as runtime]))

(def source
  "(ns app.reference (:export [invoke]) (:capabilities #{:http/post}))
   (defn invoke [request :string] :string
     (typed-cap-call :http/post :string :string request))")

(defn- kir []
  (ir/lower (:hir (compiler/check-source source {:allow #{[:cap/call 4]}}))))

(deftest portable-reference-runtime-dispatches-an-exact-provider
  (let [host (runtime/instantiate
              (kir)
              {:allow #{4}
               :providers {4 {:request-type :string :result-type :string
                              :invoke #(str % "!")}}})]
    (is (= :kotoba.reference-runtime/v1 (:format host)))
    (is (= "hello!" ((:invoke host) 'invoke ["hello"])))))

(deftest provider-contracts-are-closed-and-deny-by-default
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                        (runtime/instantiate
                         (kir)
                         {:allow #{4}
                          :providers {4 {:request-type :i64 :result-type :string
                                         :invoke identity}}})))
  (let [host (runtime/instantiate (kir))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"capability denied"
                          ((:invoke host) 'invoke ["hello"])))))
