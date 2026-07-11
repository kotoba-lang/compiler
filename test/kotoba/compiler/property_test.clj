(ns kotoba.compiler.property-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.verifier :as verifier])
  (:import [java.util Random]))

(def vars ['x 'y])
(def arithmetic ['+ '- '*])
(def comparisons ['= '< '> '<= '>=])

(defn- choose [^Random rng xs] (nth xs (.nextInt rng (count xs))))
(defn- leaf [^Random rng]
  (if (zero? (.nextInt rng 3)) (- (.nextInt rng 11) 5) (choose rng vars)))

(defn- expression [^Random rng depth]
  (if (zero? depth)
    (leaf rng)
    (case (.nextInt rng 5)
      0 (list (choose rng arithmetic)
              (expression rng (dec depth)) (expression rng (dec depth)))
      1 (list 'if
              (list (choose rng comparisons)
                    (expression rng (dec depth)) (expression rng (dec depth)))
              (expression rng (dec depth)) (expression rng (dec depth)))
      2 (list 'let ['z (expression rng (dec depth))]
              (list (choose rng arithmetic) 'z (expression rng (dec depth))))
      3 (list (choose rng comparisons)
              (expression rng (dec depth)) (expression rng (dec depth)))
      (leaf rng))))

(defn- source [^Random rng]
  (let [helper (expression rng 3)
        body (if (zero? (.nextInt rng 2))
               (list 'helper 'x 'y)
               (list (choose rng arithmetic) (list 'helper 'x 'y) (expression rng 2)))]
    (str "(defn helper [x y] " (pr-str helper) ")\n"
         "(defn run [x y] " (pr-str body) ")\n"
         "(defn main [] (run 3 -2))\n")))

(deftest deterministic-cross-backend-property-corpus
  (let [rng (Random. 0x4b4f544f4241)]
    (dotimes [case-id 100]
      (let [src (source rng)
            results (into {} (map (fn [target] [target (compiler/compile-source src target)])
                                  compiler/targets))
            kirs (map :kir (vals results))
            x86 (:artifact (get results :x86_64-kotoba-v1))
            arm (:artifact (get results :aarch64-kotoba-v1))
            x86-again (:artifact (compiler/compile-source src :x86_64-kotoba-v1))
            kir (first kirs)]
        (is (= 1 (count (set kirs))) (str "KIR mismatch case " case-id))
        (is (= (:oracle-value kir) (ir/execute kir 'main []))
            (str "reference execution mismatch case " case-id))
        (doseq [[x y] [[Long/MIN_VALUE -1] [Long/MAX_VALUE 1] [0 0]
                       [(.nextLong rng) (.nextLong rng)]]]
          (let [first-run (ir/execute kir 'run [x y])]
            (is (= first-run (ir/execute kir 'run [x y]))
                (str "reference execution is nondeterministic case " case-id))))
        (is (= (:code x86) (:code x86-again)) (str "nondeterministic code case " case-id))
        (is (= (:sha256 x86) (:sha256 x86-again)) (str "nondeterministic seal case " case-id))
        (is (= x86 (verifier/verify-artifact! x86)))
        (is (= arm (verifier/verify-artifact! arm)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (verifier/verify-artifact! (update-in x86 [:code 0] bit-xor 1))))))))
