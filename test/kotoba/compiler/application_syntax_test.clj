(ns kotoba.compiler.application-syntax-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]))

(defn- compile-kir [source]
  (:kir (compiler/compile-source source :wasm32-kotoba-v1)))

(defn- oracle [source]
  (:oracle-value (compile-kir source)))

(deftest bounded-case-is-single-evaluation-literal-dispatch
  (is (= 20 (oracle "(defn choose [x] (case x 1 10 2 20 30))
                     (defn main [] (choose 2))")))
  (is (= 7 (oracle "(defn main [] (case :ready :hold 3 :ready 7 9))")))
  (is (= 23 (oracle "(defn main [] (case :ready (:hold :ready) 23 9))")))
  (is (thrown? ArithmeticException
               (oracle "(defn main [] (case 8 1 10 2 20))")))
  (doseq [bad ["(defn main [] (case))"
               "(defn main [] (case 1 (+ 0 1) 2))"
               "(defn main [] (case 1 1 2 1 3))"
               "(defn main [] (case 1 (1 2) 3 2 4))"]]
    (is (thrown? clojure.lang.ExceptionInfo (compiler/check-source bad)))))

(deftest binding-conditionals-evaluate-once
  (is (= 9 (oracle "(defn main [] (if-let [x 9] x 0))")))
  (is (= 4 (oracle "(defn main [] (if-let [x 0] 9 4))")))
  (is (= 5 (oracle "(defn main [] (when-let [x 1] (+ x 1) (+ x 4)))")))
  (doseq [bad ["(defn main [] (if-let [x] x 0))"
               "(defn main [] (if-let [x 1]))"
               "(defn main [] (when-let [x 1]))"]]
    (is (thrown? clojure.lang.ExceptionInfo (compiler/check-source bad)))))

(deftest threading-is-pure-call-insertion
  (is (= 9 (oracle "(defn add [a b] (+ a b))
                    (defn main [] (-> 2 (add 3) (add 4)))")))
  (is (= 7 (oracle "(defn sub [a b] (- a b))
                    (defn main [] (->> 3 (sub 10)))")))
  (is (thrown? clojure.lang.ExceptionInfo
               (compiler/check-source "(defn main [] (->))"))))

(deftest variadic-comparisons-short-circuit-through-binary-core
  (let [source "(defn main []
                  (+ (=) (= 1) (= 2 2 2)
                     (not= 2 2 3)
                     (< 1 2 3 4)
                     (> 4 3 2 1)
                     (<= 1 1 2)
                     (>= 2 2 1)))"
        kir (compile-kir source)
        printed (pr-str kir)]
    (is (= 8 (:oracle-value kir)))
    (doseq [surface ["(not= " "(-> " "(->> " "(case " "(if-let " "(when-let "]]
      (is (not (.contains printed surface)))))
  (testing "ordered comparison still rejects zero operands"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one operand"
                          (compiler/check-source "(defn main [] (<))")))))

(deftest application-sugar-has-identical-results-on-all-targets
  (let [source "(defn f [x] (case x 0 10 1 20 30))
                (defn main [] (if-let [x (-> 0 (f))] (if (< 1 x 30) x 0) 0))"
        compiled (mapv #(compiler/compile-source source %) compiler/targets)]
    (is (= [10 10 10 10 10]
           (mapv #(get-in % [:kir :oracle-value]) compiled)))))
