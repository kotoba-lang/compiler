(ns kotoba.compiler.frontend-dotimes-test
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

(deftest dotimes-uses-the-fuel-trapped-loop-path
  (is (= 0 (oracle "(defn main [] (dotimes [i 4] (+ i 1)))")))
  (is (= 0 (oracle "(defn main [] (dotimes [i 0] (quot 1 0)))"))
      "zero iterations must not evaluate the body")
  (is (= 0 (oracle "(defn main [] (dotimes [i -3] (quot 1 0)))"))
      "a negative count also performs no iterations")
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle "(defn main [] (dotimes [i 4] (if (= i 3) (quot 1 0) 0)))"))
      "the index reaches the final iteration"))

(deftest dotimes-count-expression-is-present-once-after-desugaring
  (let [functions (:functions (:hir
                               (checked "(defn limit [] 3)
                                         (defn main [] (dotimes [i (limit)] i))")))
        body (:body (first (filter #(= 'main (:name %)) functions)))]
    (is (= 1 (count (re-seq #"\(limit\)" (pr-str body)))))))

(deftest dotimes-validates-its-bounded-binding-shape
  (doseq [source ["(defn main [] (dotimes [i] i))"
                  "(defn main [] (dotimes [i 2 extra] i))"
                  "(defn main [] (dotimes [:i 2] 0))"
                  "(defn main [] (dotimes [qualified/i 2] 0))"]]
    (testing source
      (is (= "dotimes requires [unqualified-symbol count]"
             (rejection-message source))))))
