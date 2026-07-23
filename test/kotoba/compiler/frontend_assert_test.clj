(ns kotoba.compiler.frontend-assert-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(defn- checked [source]
  (compiler/check-source source))

(defn- oracle [source]
  (ir/execute (ir/lower (:hir (checked source))) 'main []))

(defn- rejection-message [source]
  (try (checked source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest assert-returns-zero-on-success-and-traps-on-failure
  (is (= 0 (oracle "(defn main [] (assert 1))")))
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle "(defn main [] (assert 0))"))))

(deftest assert-condition-is-present-once-after-desugaring
  (let [functions (:functions
                   (:hir (checked "(defn condition [] 1)
                                   (defn main [] (assert (condition)))")))
        body (:body (first (filter #(= 'main (:name %)) functions)))]
    (is (= 1 (count (re-seq #"\(condition\)" (pr-str body)))))))

(deftest assert-rejects-unsupported-arities-and-messages
  (is (= "assert requires exactly one condition; messages are not supported"
         (rejection-message "(defn main [] (assert))")))
  (is (= "assert requires exactly one condition; messages are not supported"
         (rejection-message "(defn main [] (assert 1 \"message\"))"))))
