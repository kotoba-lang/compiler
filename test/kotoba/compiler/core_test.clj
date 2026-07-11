(ns kotoba.compiler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.verifier :as verifier]))

(def source "(ns demo) (defn main [] (+ 40 2))")
(def structured-source
  "(ns demo)
   (defn abs [x] (if (< x 0) (- x) x))
   (defn score [x bonus] (let [m (* x 2) total (+ m bonus)] (abs total)))
   (defn main [] (score -20 -2))")

(deftest all-backends-share-one-kir
  (let [results (map #(compiler/compile-source source %) compiler/targets)]
    (is (= 1 (count (set (map :kir results)))))
    (is (every? #(= #{} (get-in % [:kir :effects])) results))))

(deftest emits-real-wasm
  (let [bytes (:bytes (compiler/compile-source source :wasm32-kotoba-v1))]
    (is (= [0 97 115 109] (mapv #(bit-and (int %) 0xff) (take 4 bytes))))))

(deftest emits-and-verifies-native-machine-code
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (let [artifact (:artifact (compiler/compile-source source target))]
      (is (= artifact (verifier/verify-artifact! artifact)))
      (is (re-matches #"[0-9a-f]{64}" (:sha256 artifact)))
      (is (re-matches #"[0-9a-f]{64}" (:kir-sha256 artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integrity mismatch"
                            (verifier/verify-artifact! (update artifact :code assoc 0 0)))))))

(deftest structured-program-conforms-across-backends
  (let [results (mapv #(compiler/compile-source structured-source %) compiler/targets)]
    (is (= 42 (-> results first :kir :blocks first :instructions first second)))
    (is (= 1 (count (set (map :kir results)))))
    (is (= #{'main 'abs 'score}
           (set (map :name (-> results first :hir :functions)))))))

(deftest artifact-metadata-is-covered-by-integrity-seal
  (let [artifact (:artifact (compiler/compile-source source :x86_64-kotoba-v1))]
    (doseq [mutated [(assoc-in artifact [:limits :fuel] 2)
                     (assoc artifact :effects #{:ambient/network})
                     (assoc artifact :target :aarch64-kotoba-v1)
                     (assoc artifact :kir-sha256 (apply str (repeat 64 "0")))]]
      (is (thrown? clojure.lang.ExceptionInfo (verifier/verify-artifact! mutated))))))

(deftest recursion-and-overflow-are-bounded
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"call depth exhausted"
                        (compiler/compile-source
                         "(defn forever [x] (forever x)) (defn main [] (forever 0))"
                         :wasm32-kotoba-v1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integer overflow"
                        (compiler/compile-source
                         "(defn main [] (+ 9223372036854775807 1))"
                         :x86_64-kotoba-v1))))

(deftest fails-closed
  (doseq [bad ["(defn main [] (eval '(+ 1 2)))"
               "#=(java.lang.System/exit 0)"
               "(defn main [] (slurp \"/etc/passwd\"))"
               "(defn main [x] x)"
               "(defn f [x] x) (defn main [] (f))"
               "(defn f [x x] x) (defn main [] 1)"
               "(defn main [] (let [x 1 x 2] x))"
               "(defn main [] (if 1 2))"
               "(defn main [] (atom 1))"
               "(defmacro pwn [] 1) (defn main [] 1)"]]
    (testing bad
      (is (thrown? clojure.lang.ExceptionInfo
                   (compiler/compile-source bad :x86_64-kotoba-v1))))))
