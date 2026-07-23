(ns kotoba.compiler.frontend-doseq-test
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

(deftest doseq-walks-the-pair-chain-and-returns-zero
  (is (= 0 (oracle "(defn main [] (doseq [x [1 2 3]] (+ x 10)))")))
  (is (= 0 (oracle "(defn main [] (doseq [x []] (quot 1 0)))"))
      "an empty collection must skip the body")
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle "(defn main [] (doseq [x [1 2 3]]
                                        (if (= x 3) (quot 1 0) 0)))"))
      "the loop must reach the final element"))

(deftest doseq-collection-expression-is-present-once-after-desugaring
  (let [functions (:functions
                   (:hir (checked "(defn item [] 3)
                                   (defn main []
                                    (doseq [x [1 2 (item)]] x))")))
        body (:body (first (filter #(= 'main (:name %)) functions)))]
    (is (= 1 (count (re-seq #"\(item\)" (pr-str body)))))))

(deftest doseq-validates-its-bounded-binding-shape
  (doseq [source ["(defn main [] (doseq [x] x))"
                  "(defn main [] (doseq [x [1] y [2]] (+ x y)))"
                  "(defn main [] (doseq [:x [1]] 0))"
                  "(defn main [] (doseq [qualified/x [1]] 0))"
                  "(defn main [] (doseq [x [1] :while x] x))"]]
    (testing source
      (is (some? (rejection-message source))))))

(deftest doseq-admits-dynamic-bounded-vector-expressions
  (is (= 0
         (oracle
          "(defn main []
             (let [xs (if 1 [1 2 3] [])]
               (doseq [x xs] (+ x 1))))")))
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle
                "(defn main []
                   (let [xs (if 1 [1 2 3] [])]
                     (doseq [x xs]
                       (if (= x 3) (quot 1 0) 0))))"))))

(deftest doseq-supports-ordered-let-and-when-modifiers
  (is (= 0
         (oracle
          "(defn main []
             (doseq [x [1 2 3] :let [y (+ x 10)] :when (= y 99)]
               (quot 1 0)))")))
  (is (thrown? clojure.lang.ExceptionInfo
               (oracle
                "(defn main []
                   (doseq [x [1 2 3] :let [y (+ x 10)] :when (= y 12)]
                     (quot 1 0)))"))))
