(ns kotoba.compiler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.verifier :as verifier]))

(def source "(ns demo) (defn main [] (+ 40 2))")

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
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rejected"
                            (verifier/verify-artifact! (update artifact :code assoc 0 0)))))))

(deftest fails-closed
  (doseq [bad ["(defn main [] (eval '(+ 1 2)))"
               "#=(java.lang.System/exit 0)"
               "(defn main [] (slurp \"/etc/passwd\"))"
               "(defn main [x] x)"
               "(defmacro pwn [] 1) (defn main [] 1)"]]
    (testing bad
      (is (thrown? clojure.lang.ExceptionInfo
                   (compiler/compile-source bad :x86_64-kotoba-v1))))))
