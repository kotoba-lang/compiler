(ns kotoba.compiler.security-fuzz-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.signing :as signing]
            [kotoba.compiler.verifier :as verifier])
  (:import [java.util Random]))

(def default-seed 0x4b4f544f4241465a)

(defn- configured-long [name fallback maximum]
  (let [value (if-let [text (System/getenv name)] (Long/parseLong text) fallback)]
    (when-not (<= 1 value maximum)
      (throw (ex-info "invalid fuzz configuration" {:name name :value value})))
    value))

(defn- flip-string [value]
  (str (if (= \0 (first value)) \1 \0) (subs value 1)))

(defn- reseal [value] (artifact/seal (dissoc value :sha256)))
(defn- reseal-program [kexe program]
  (reseal (assoc kexe :program program :kir-sha256 (artifact/sha256 program))))

(defn- mutate-artifact [kexe choice]
  (case choice
    0 (reseal (update-in kexe [:code 0] bit-xor 1))
    1 (update kexe :sha256 flip-string)
    2 (reseal (assoc kexe :target :unknown-kotoba-v1))
    3 (reseal (assoc kexe :lowering :attacker-controlled))
    4 (reseal (assoc-in kexe [:limits :fuel] 257))
    5 (reseal (assoc-in kexe [:context-abi :cap-call-offset] 40))
    6 (reseal (assoc-in kexe [:fuel-abi :initial] 257))
    7 (reseal (update-in kexe [:exports 'main :offset] inc))
    8 (reseal (assoc kexe :effects #{[:cap/call 256]}))
    9 (reseal (update-in kexe [:program :functions 0 :body] inc))
    10 (let [changed (update-in kexe [:program :functions 0 :body] inc)]
         (reseal (assoc changed :kir-sha256 (artifact/sha256 (:program changed)))))
    11 (reseal (assoc-in kexe [:code 0] -1))
    12 (reseal (assoc kexe :format :kotoba.kexe/unknown))
    13 (reseal (assoc kexe :kir-sha256 "not-a-hash"))
    14 (reseal (assoc-in kexe [:program :effects] #{[:cap/call 7]}))
    15 (reseal-program kexe (assoc-in (:program kexe) [:functions 0 :body]
                                      '(cap-call 7 1)))
    16 (reseal-program kexe (assoc-in (:program kexe) [:functions 0 :body]
                                      '(attacker-op 1)))
    17 (reseal-program kexe (assoc-in (:program kexe) [:functions 0 :params]
                                      '[a b c d e f]))))

(defn- mutate-envelope [envelope choice]
  (case choice
    0 (update envelope :signature flip-string)
    1 (assoc-in envelope [:statement :signer] (apply str (repeat 64 "0")))
    2 (update-in envelope [:artifact :code 0] bit-xor 1)
    3 (assoc-in envelope [:statement :public-key] "malformed")
    4 (assoc-in envelope [:statement :expires] 999)
    5 (assoc envelope :format :kotoba.signed-kexe/unknown)))

(defn- receipt-hash [value]
  (artifact/sha256 (dissoc value :receipt-sha256 :executor)))

(defn- mutate-receipt [value choice]
  (let [changed (case choice
                  0 (update value :receipt-sha256 flip-string)
                  1 (assoc value :status :unknown)
                  2 (assoc-in value [:fuel :remaining] 257)
                  3 (update value :output-sha256 flip-string)
                  4 (update-in value [:executor :signature] flip-string)
                  5 (assoc value :entry 'attacker))]
    ;; Half of the mutations model an attacker who can recompute unkeyed hashes.
    (if (odd? choice) (assoc changed :receipt-sha256 (receipt-hash changed)) changed)))

(deftest deterministic-security-boundary-mutation-corpus
  (let [seed (configured-long "KOTOBA_FUZZ_SEED" default-seed Long/MAX_VALUE)
        cases (configured-long "KOTOBA_FUZZ_CASES" 600 5000)
        rng (Random. seed)
        kexe (:artifact (compiler/compile-source "(defn main [] 42)"
                                                  :x86_64-kotoba-v1))
        key (signing/generate-keypair)
        envelope (signing/sign kexe key {:not-before 1000 :expires 2000})
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}
        policy {:allow #{}}
        input {:args []}
        output {:status :ok :result 42}
        run-receipt (receipt/create
                     envelope trust policy input output
                     {:now 1500 :started-at 1400 :finished-at 1401 :status :ok
                      :target :x86_64-kotoba-v1 :entry 'main
                      :fuel-initial 256 :fuel-remaining 255
                      :parent nil :executor-key key})]
    (dotimes [case-id cases]
      (let [domain (.nextInt rng 3)
            assertion-message (str "seed=" seed " case=" case-id " domain=" domain)]
        (case domain
          0 (is (thrown? clojure.lang.ExceptionInfo
                         (verifier/verify-artifact!
                          (mutate-artifact kexe (.nextInt rng 18))))
                assertion-message)
          1 (is (thrown? clojure.lang.ExceptionInfo
                         (signing/verify
                          (mutate-envelope envelope (.nextInt rng 6)) trust 1500))
                assertion-message)
          2 (is (thrown? clojure.lang.ExceptionInfo
                         (receipt/verify
                          (mutate-receipt run-receipt (.nextInt rng 6))
                          envelope trust policy input output {:now 1500 :parent nil}))
                assertion-message))))))
