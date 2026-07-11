(ns kotoba.compiler.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.ir :as ir]
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
    (is (= [0 97 115 109] (mapv #(bit-and (int %) 0xff) (take 4 bytes))))
    (is (some #{0x7c} (map #(bit-and (int %) 0xff) bytes))
        "Wasm contains runtime i64.add rather than only a folded constant")))

(deftest emits-and-verifies-native-machine-code
  (doseq [target [:x86_64-kotoba-v1 :aarch64-kotoba-v1]]
    (let [artifact (:artifact (compiler/compile-source source target))]
      (is (= artifact (verifier/verify-artifact! artifact)))
      (is (re-matches #"[0-9a-f]{64}" (:sha256 artifact)))
      (is (re-matches #"[0-9a-f]{64}" (:kir-sha256 artifact)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integrity mismatch"
                            (verifier/verify-artifact!
                             (update-in artifact [:code 0] bit-xor 1)))))))

(deftest structured-program-conforms-across-backends
  (let [results (mapv #(compiler/compile-source structured-source %) compiler/targets)]
    (is (= 42 (-> results first :kir :blocks first :instructions first second)))
    (is (= :kotoba.kir/v3 (-> results first :kir :format)))
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

(deftest x86-runtime-artifact-is-derived-from-sealed-kir
  (let [kexe (:artifact (compiler/compile-source structured-source :x86_64-kotoba-v1))]
    (is (= :runtime-sysv-v1 (:lowering kexe)))
    (is (= 2 (get-in kexe [:exports 'score :arity])))
    (is (pos? (get-in kexe [:exports 'score :length])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"KIR identity mismatch"
         (verifier/verify-artifact!
          (artifact/seal (assoc kexe :kir-sha256 (apply str (repeat 64 "0")))))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"export table rejected|instruction stream rejected"
         (verifier/verify-artifact!
          (let [changed (assoc-in kexe [:program :functions 0 :body] 999)]
            (artifact/seal
             (assoc changed :kir-sha256 (artifact/sha256 (:program changed))))))))))

(deftest recursion-and-i64-semantics-are-bounded
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel-exhausted"
                        (compiler/compile-source
                         "(defn forever [x] (forever x)) (defn main [] (forever 0))"
                         :wasm32-kotoba-v1)))
  (let [result (compiler/compile-source
                "(defn main [] (+ 9223372036854775807 1))"
                :x86_64-kotoba-v1)]
    (is (= Long/MIN_VALUE (get-in result [:kir :oracle-value])))))

(deftest normative-kir-execution-covers-runtime-exports
  (let [kir (:kir (compiler/compile-source structured-source :wasm32-kotoba-v1))]
    (is (= 12 (ir/execute kir 'score [-7 2])))
    (is (= Long/MIN_VALUE
           (ir/execute (:kir (compiler/compile-source
                              "(defn wrap [x] (+ x 1)) (defn main [] 0)"
                              :wasm32-kotoba-v1))
                       'wrap [Long/MAX_VALUE])))
    (is (= :division-by-zero
           (:trap (ex-data (try (ir/execute
                                 (:kir (compiler/compile-source
                                        "(defn divide [x y] (quot x y)) (defn main [] 0)"
                                        :wasm32-kotoba-v1))
                                 'divide [1 0])
                                (catch clojure.lang.ExceptionInfo error error))))))))

(deftest wasm-recursion-is-fuel-bounded-and-native-fails-closed
  (let [source "(defn fact [n] (if (<= n 1) 1 (* n (fact (- n 1)))))
                (defn forever [n] (forever (+ n 1)))
                (defn main [] (fact 5))"
        wasm (compiler/compile-source source :wasm32-kotoba-v1)]
    (is (= {:fuel 256 :replenishable? false} (:limits wasm)))
    (is (= 120 (:oracle-value (:kir wasm))))
    (let [x86 (:artifact (compiler/compile-source source :x86_64-kotoba-v1))
          arm (:artifact (compiler/compile-source source :aarch64-kotoba-v1))]
      (is (= {:mode :hidden-context-r9 :initial 256} (:fuel-abi x86)))
      (is (= {:mode :hidden-context-x7 :initial 256} (:fuel-abi arm)))
      (is (pos? (get-in x86 [:exports 'forever :length])))
      (is (pos? (get-in arm [:exports 'forever :length])))
      (is (some #{0xe8} (:code x86)) "contains a real x86 CALL rel32")
      (is (some #{0x0f} (:code x86)) "contains the fuel-exhaustion UD2 prefix")
      (is (some #(<= 0x94 % 0x97) (take-nth 4 (drop 3 (:code arm))))
          "contains an AArch64 BL imm26 opcode"))))

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
