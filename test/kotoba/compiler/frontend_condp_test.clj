(ns kotoba.compiler.frontend-condp-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(defn- checked [source]
  (compiler/check-source source))

(defn- oracle [source]
  (ir/execute (ir/lower (:hir (checked source))) 'main []))

(defn- rejection-message [source]
  (try (checked source) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest condp-selects-the-first-match-or-default
  (is (= 20 (oracle "(defn main [] (condp = 2 1 10 2 20 30))")))
  (is (= 30 (oracle "(defn main [] (condp = 3 1 10 2 20 30))")))
  (is (= 9 (oracle "(defn main [] (condp = 1 1 9 99 (quot 1 0) 0))"))
      "a selected clause must short-circuit all later tests"))

(deftest condp-dispatch-expression-is-present-once-after-desugaring
  (let [functions (:functions
                   (:hir (checked "(defn dispatch [] 2)
                                   (defn main [] (condp = (dispatch) 1 10 2 20 30))")))
        body (:body (first (filter #(= 'main (:name %)) functions)))]
    (is (= 1 (count (re-seq #"\(dispatch\)" (pr-str body)))))))

(deftest condp-without-a-default-traps-on-a-miss
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle "(defn main [] (condp = 3 1 10 2 20))"))))

(deftest condp-validates-its-portable-shape
  (doseq [[source message]
          [["(defn main [] (condp))"
            "condp requires a predicate and dispatch expression"]
           ["(defn main [] (condp =))"
            "condp requires a predicate and dispatch expression"]
           ["(defn main [] (condp (if 1 = =) 1 1 10))"
            "condp predicate must be an unqualified function symbol"]
           ["(defn main [] (condp qualified/predicate 1 1 10))"
            "condp predicate must be an unqualified function symbol"]
           ["(defn main [] (condp = 1 1 :>> identity 0))"
            "condp :>> clauses are not supported by this portable profile"]]]
    (testing source
      (is (= message (rejection-message source))))))
