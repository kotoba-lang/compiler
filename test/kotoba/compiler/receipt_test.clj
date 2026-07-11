(ns kotoba.compiler.receipt-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.receipt :as receipt]
            [kotoba.compiler.runtime-identity :as runtime-identity]
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

(deftest receipt-schema-rejects-unknown-fields-before-hash-or-signature
  (let [{:keys [envelope key trust policy input output]} (fixture)
        value (receipt/create envelope trust policy input output
                              (assoc opts :executor-key key))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema rejected"
                          (receipt/verify (assoc value :ignored true)
                                          envelope trust policy input output
                                          {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema rejected"
                          (receipt/verify (assoc-in value [:fuel :ignored] 0)
                                          envelope trust policy input output
                                          {:now 1500 :parent nil})))))

(deftest receipt-chain-links-and-rejects-reordering
  (let [{:keys [envelope key trust policy input output]} (fixture)
        first-receipt (receipt/create envelope trust policy input output
                                      (assoc opts :executor-key key))
        second-receipt (receipt/create envelope trust policy input 43
                                       (assoc opts :started-at 1402 :finished-at 1403
                                              :fuel-remaining 254 :parent first-receipt
                                              :executor-key key))]
    (is (= {:verified? true :scope :executor-attested-chain/v1
            :count 2 :head (:receipt-sha256 second-receipt)}
           (receipt/verify-chain [first-receipt second-receipt] trust)))
    (is (:verified? (receipt/verify second-receipt envelope trust policy input 43
                                    {:now 1500 :parent first-receipt})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parent link mismatch"
                          (receipt/verify-chain [second-receipt first-receipt] trust)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"evidence mismatch"
                          (receipt/verify second-receipt envelope trust policy input 43
                                          {:now 1500 :parent nil})))))

(deftest receipt-chain-requires-every-executor-attestation
  (let [{:keys [envelope key trust policy input output]} (fixture)
        value (receipt/create envelope trust policy input output
                              (assoc opts :executor-key key))
        changed (assoc value :status :trap)
        forged (assoc changed :receipt-sha256
                      (artifact/sha256 (dissoc changed :receipt-sha256 :executor)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attestation rejected"
                          (receipt/verify-chain [forged] trust)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attestation rejected"
                          (receipt/verify-chain
                           [value] (assoc trust :revoked-signers #{(:signer key)}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"attestation rejected"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :parent forged
                                                 :executor-key key))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not be empty"
                          (receipt/verify-chain [] trust)))))

(deftest receipt-creation-validates-accounting
  (let [{:keys [envelope key trust policy input output]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fuel accounting"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :fuel-remaining 257 :executor-key key))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"time interval"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :finished-at 1399 :executor-key key))))))

(deftest receipt-runtime-identity-can-be-pinned-and-revoked
  (let [{:keys [envelope key trust policy input]} (fixture)
        runtime {:format :kotoba.native-runtime/v2
                 :loader-source-sha256 runtime-identity/loader-source-sha256
                 :loader-binary-sha256 (apply str (repeat 64 "a"))
                 :compiler-binary-sha256 (apply str (repeat 64 "b"))
                 :compiler-version-sha256 (apply str (repeat 64 "c"))}
        output {:status :ok :result 42 :runtime runtime}
        identity (runtime-identity/identity-sha256 runtime)
        pinned-trust (assoc trust :trusted-runtime-sha256 #{identity})
        value (receipt/create envelope pinned-trust policy input output
                              (assoc opts :executor-key key))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not trusted"
                          (receipt/create envelope trust policy input output
                                          (assoc opts :executor-key key))))
    (is (:verified? (receipt/verify value envelope
                                    pinned-trust
                                    policy input output {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not trusted"
                          (receipt/verify value envelope
                                          (assoc trust :trusted-runtime-sha256 #{})
                                          policy input output {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"revoked"
                          (receipt/verify value envelope
                                          (assoc trust :revoked-runtime-sha256 #{identity})
                                          policy input output {:now 1500 :parent nil})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity rejected"
                          (receipt/verify value envelope trust policy input
                                          (assoc-in output [:runtime :loader-source-sha256]
                                                    (apply str (repeat 64 "0")))
                                          {:now 1500 :parent nil})))))

(deftest runtime-measurement-schema-is-exact
  (let [runtime {:format :kotoba.native-runtime/v2
                 :loader-source-sha256 runtime-identity/loader-source-sha256
                 :loader-binary-sha256 (apply str (repeat 64 "a"))
                 :compiler-binary-sha256 (apply str (repeat 64 "b"))
                 :compiler-version-sha256 (apply str (repeat 64 "c"))}
        measurement {:format :kotoba.runtime-measurement/v1 :runtime runtime}]
    (is (= measurement (runtime-identity/validate-measurement! measurement)))
    (is (not= (runtime-identity/identity-sha256 runtime)
              (runtime-identity/identity-sha256
               (assoc runtime :compiler-binary-sha256
                      (apply str (repeat 64 "d"))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identity rejected"
                          (runtime-identity/validate!
                           (-> runtime
                               (assoc :format :kotoba.native-runtime/v1)
                               (dissoc :compiler-binary-sha256)
                               (assoc :compiler-identity-sha256
                                      (apply str (repeat 64 "b")))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema mismatch"
                          (runtime-identity/validate-measurement!
                           (assoc measurement :ignored true))))))
