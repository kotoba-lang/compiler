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

(deftest explicit-platform-targets-bind-os-abi-and-runtime-profile
  (let [linux (:artifact (compiler/compile-source source :x86_64-linux-kotoba-v1))
        macos (:artifact (compiler/compile-source source :x86_64-macos-kotoba-v1))
        windows (:artifact (compiler/compile-source source :x86_64-windows-kotoba-v1))
        android (:artifact (compiler/compile-source source :aarch64-android-kotoba-v1))
        ios (:artifact (compiler/compile-source source :aarch64-ios-kotoba-v1))
        browser (compiler/compile-source source :wasm32-browser-kotoba-v1)
        wasi (compiler/compile-source source :wasm32-wasi-kotoba-v1)]
    (is (= (:code linux) (:code macos)))
    (is (= (:code linux) (:code windows)))
    (is (not= (:sha256 linux) (:sha256 macos)))
    (is (not= (:sha256 linux) (:sha256 windows)))
    (is (= {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64
            :os :linux :abi :sysv :runtime :kotoba-linux-supervisor-v1}
           (:target-profile linux)))
    (is (= {:format :kotoba.target-profile/v1 :execution :native :isa :x86_64
            :os :windows :abi :kotoba-sysv-v1 :runtime :kotoba-windows-supervisor-v1}
           (:target-profile windows)))
    (is (= {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64
            :os :android :abi :aapcs64 :runtime :kotoba-android-isolated-host-v1}
           (:target-profile android)))
    (is (= {:format :kotoba.target-profile/v1 :execution :native :isa :aarch64
            :os :ios :abi :aapcs64 :runtime :kotoba-ios-static-host-v1}
           (:target-profile ios)))
    (is (= (:code android) (:code ios)))
    (is (not= (:sha256 android) (:sha256 ios)))
    (is (= :browser (get-in browser [:target-profile :os])))
    (is (= :wasm (get-in browser [:target-profile :execution])))
    (is (= {:format :kotoba.target-profile/v1 :execution :wasm :isa :wasm32
            :os :wasi :abi :wasm-mvp :runtime :kotoba-wasi-host-v1}
           (:target-profile wasi)))
    (is (not= (seq (:bytes browser)) (seq (:bytes wasi))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target profile"
                          (verifier/verify-artifact!
                           (artifact/seal
                            (assoc linux :target-profile (:target-profile macos))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target profile"
                          (verifier/verify-artifact!
                           (artifact/seal
                            (assoc windows :target-profile (:target-profile linux))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"target profile"
                          (verifier/verify-artifact!
                           (artifact/seal
                            (assoc android :target-profile (:target-profile ios))))))))

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

(deftest artifact-schema-and-oracle-metadata-are-recomputed
  (let [kexe (:artifact (compiler/compile-source source :x86_64-kotoba-v1))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema rejected"
                          (verifier/verify-artifact!
                           (artifact/seal (assoc kexe :attacker-field true)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"oracle value rejected"
                          (verifier/verify-artifact!
                           (artifact/seal (update kexe :value inc)))))))

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

(deftest bounded-pair-semantics-are-shared-and-fail-closed
  (let [source "(defn read-pair [handle] (+ (pair-first handle) (pair-second handle)))
                (defn main [] (read-pair (pair 20 22)))"
        results (mapv #(compiler/compile-source source %) compiler/targets)
        kir (:kir (first results))]
    (is (= 42 (:oracle-value kir)))
    (is (= 1 (count (set (map :kir results)))))
    (is (= :heap-exhausted
           (:trap (ex-data
                   (try
                     (ir/execute kir 'main [] {:pair-capacity 0})
                     (catch clojure.lang.ExceptionInfo error error))))))
    (is (= :invalid-pair-handle
           (:trap (ex-data
                   (try
                     (ir/execute kir 'read-pair [1])
                     (catch clojure.lang.ExceptionInfo error error))))))))

(deftest persistent-list-syntax-desugars-to-the-bounded-pair-arena
  (let [source "(defn sum2 [xs] (+ (first xs) (first (rest xs))))
                (defn main [] (+ (sum2 (list 20 22))
                                 (empty? (rest (cons 9 (list))))))"
        results (mapv #(compiler/compile-source source %) compiler/targets)
        kir (:kir (first results))
        printed (pr-str kir)]
    (is (= 43 (:oracle-value kir)))
    (is (= 1 (count (set (map :kir results)))))
    (doseq [surface ["(list " "(cons " "(first " "(rest " "(empty? "]]
      (is (not (.contains printed surface))))
    (is (= :invalid-pair-handle
           (:trap (ex-data
                   (try
                     (ir/execute kir 'sum2 [0])
                     (catch clojure.lang.ExceptionInfo error error))))))))

(deftest list-surface-profile-fails-closed
  (doseq [bad ["(defn list [] 1) (defn main [] 0)"
               "(defn first [x] x) (defn main [] 0)"
               "(defn main [] (cons 1))"
               (str "(defn main [] (list " (apply str (interpose " " (repeat 129 "1"))) "))")]]
    (testing bad
      (is (thrown? clojure.lang.ExceptionInfo
                   (compiler/compile-source bad :wasm32-kotoba-v1))))))

(deftest safe-predicates-and-second-desugar-to-verified-core-operations
  (let [source "(defn classify [x] (+ (zero? x) (pos? x) (neg? x) (not x)))
                (defn main [] (+ (second (list 40 38))
                                 (classify 0) (classify 4) (classify -4)))"
        results (mapv #(compiler/compile-source source %) compiler/targets)
        kir (:kir (first results))
        printed (pr-str kir)]
    (is (= 42 (:oracle-value kir)))
    (is (= 1 (count (set (map :kir results)))))
    (doseq [surface ["(second " "(not " "(zero? " "(pos? " "(neg? "]]
      (is (not (.contains printed surface)))))
  (doseq [bad ["(defn main [] (second))"
               "(defn main [] (zero? 1 2))"
               "(defn not [x] x) (defn main [] 0)"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/compile-source bad :wasm32-kotoba-v1)))))

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
