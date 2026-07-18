(ns kotoba.compiler.frontend-limits-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.frontend :as frontend]))

(defn- rejection [source target]
  (try (compiler/compile-source source target) nil
       (catch clojure.lang.ExceptionInfo error error)))

(deftest program-wide-complexity-budgets-fail-closed
  (with-redefs [frontend/max-expression-nodes 4]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expression budget"
                          (compiler/check-source "(defn main [] (+ 1 2 3 4))"))))
  (with-redefs [frontend/max-functions 1]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"function count"
                          (compiler/check-source
                           "(defn helper [] 1) (defn main [] 0)"))))
  (with-redefs [frontend/max-bindings 1]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"binding count"
                          (compiler/check-source
                           "(defn main [] (let [x 1 y 2] (+ x y)))"))))
  (with-redefs [frontend/max-lowered-nodes 10]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lowered program budget"
                          (compiler/check-source
                           "(defn main []
                              (let [a (+ 1 1)
                                    b (+ a a)
                                    c (+ b b)]
                                (+ c c)))")))))

(deftest shared-native-abi-limit-is-a-frontend-contract
  (let [source "(defn six [a b c d e f] (+ a b c d e f)) (defn main [] 0)"
        errors (mapv #(rejection source %) compiler/targets)]
    (is (every? #(instance? clojure.lang.ExceptionInfo %) errors))
    (is (= #{:subset} (set (map #(-> % ex-data :phase) errors))))
    (is (= 1 (count (set (map ex-message errors))))))
  (let [source "(defn five [a b c d e] (+ a b c d e))
                (defn main [] (five 1 2 3 4 5))"]
    (doseq [target compiler/targets]
      (testing target
        (is (= 15 (get-in (compiler/compile-source source target)
                          [:kir :oracle-value])))))))

(deftest names-and-namespace-forms-are-bounded
  (let [long-name (apply str (repeat 129 "x"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid function name"
                          (compiler/check-source
                           (str "(defn " long-name " [] 0) (defn main [] 0)")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ns must contain"
                        (compiler/check-source "(ns demo extra) (defn main [] 0)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ns must contain"
                        (compiler/check-source "(ns one) (ns two) (defn main [] 0)"))))
