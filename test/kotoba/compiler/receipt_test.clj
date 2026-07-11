(ns kotoba.compiler.receipt-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.signing :as signing]))

(defn fixture []
  (let [kexe (:artifact (compiler/compile-source "(defn main [] 42)" :x86_64-kotoba-v1))
        key (signing/generate-keypair)
        envelope (signing/sign kexe key {:not-before 1000 :expires 3000})]
    {:envelope envelope :key key
     :trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
             :revoked-signers #{} :revoked-artifacts #{}}
     :policy {:allow #{}} :input {:argv []} :output 42}))

(def opts {:now 1500 :started-at 1400 :finished-at 1401 :status :ok
           :target :x86_64-kotoba-v1 :entry 'main
           :fuel-initial 256 :fuel-remaining 255 :parent nil})

(deftest receipt-binds-verified-execution-evidence
  (let [{:keys [envelope key trust policy input output]} (fixture)
        value (receipt/create envelope trust policy input output (assoc opts :executor-key key))
        result (receipt/verify value envelope trust policy input output {:now 1500 :parent nil})]
    (is (:verified? result))
    (is (= 1 (get-in value [:fuel :consumed])))
    (is (= (:receipt-sha256 value) (:receipt-sha256 result)))
    (is (re-matches #"[0-9a-f]{64}" (:receipt-sha256 value)))))

(deftest receipt-evidence-and-current-trust-fail-closed
  (let [{:keys [envelope key trust policy input output]} (fixture)
        value (receipt/create envelope trust policy input output (assoc opts :executor-key key))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evidence mismatch"
                          (receipt/verify value envelope trust policy {:argv ["changed"]} output
                                          {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integrity mismatch"
                          (receipt/verify (update value :status (constantly :trap))
                                          envelope trust policy input output
                                          {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"executor receipt attestation rejected"
                          (receipt/verify (update-in value [:executor :signature]
                                                     #(str (if (= \A (first %)) "B" "A")
                                                           (subs % 1)))
                                          envelope trust policy input output
                                          {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signer is revoked"
                          (receipt/verify value envelope
                                          (assoc trust :revoked-signers
                                                 #{(get-in envelope [:statement :signer])})
                                          policy input output {:now 1500 :parent nil})))))

(deftest receipt-chain-links-and-rejects-reordering
  (let [{:keys [envelope key trust policy input output]} (fixture)
        first-receipt (receipt/create envelope trust policy input output
                                      (assoc opts :executor-key key))
        second-receipt (receipt/create envelope trust policy input 43
                                       (assoc opts :started-at 1402 :finished-at 1403
                                              :fuel-remaining 254 :parent first-receipt
                                              :executor-key key))]
    (is (= {:verified? true :count 2 :head (:receipt-sha256 second-receipt)}
           (receipt/verify-chain [first-receipt second-receipt])))
    (is (:verified? (receipt/verify second-receipt envelope trust policy input 43
                                    {:now 1500 :parent first-receipt})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parent link mismatch"
                          (receipt/verify-chain [second-receipt first-receipt])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evidence mismatch"
                          (receipt/verify second-receipt envelope trust policy input 43
                                          {:now 1500 :parent nil})))))

(deftest receipt-creation-validates-accounting
  (let [{:keys [envelope key trust policy input output]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel accounting"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :fuel-remaining 257 :executor-key key))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"time interval"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :finished-at 1399 :executor-key key))))))
