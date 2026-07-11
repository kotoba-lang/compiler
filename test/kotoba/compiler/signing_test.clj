(ns kotoba.compiler.signing-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.signing :as signing]))

(def source "(defn main [] 42)")

(defn fixture []
  (let [kexe (:artifact (compiler/compile-source source :x86_64-kotoba-v1))
        key (signing/generate-keypair)
        envelope (signing/sign kexe key {:not-before 1000 :expires 2000})
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}]
    {:kexe kexe :key key :envelope envelope :trust trust}))

(deftest signed-kexe-verifies-through-native-verifier
  (let [{:keys [kexe key envelope trust]} (fixture)
        result (signing/verify envelope trust 1500)]
    (is (:verified? result))
    (is (= (:signer key) (:signer result)))
    (is (= kexe (:artifact result)))
    (is (= envelope (signing/sign kexe key {:not-before 1000 :expires 2000}))
        "Ed25519 statement signatures are reproducible")))

(deftest trust-expiry-and-revocation-fail-closed
  (let [{:keys [kexe key envelope trust]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not trusted"
                          (signing/verify envelope (assoc trust :trusted-signers #{}) 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed trust policy"
                          (signing/verify envelope (assoc trust :trusted-signers [(:signer key)]) 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signer is revoked"
                          (signing/verify envelope
                                          (assoc trust :revoked-signers #{(:signer key)}) 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"artifact is revoked"
                          (signing/verify envelope
                                          (assoc trust :revoked-artifacts #{(:sha256 kexe)}) 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not yet valid"
                          (signing/verify envelope trust 999)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired"
                          (signing/verify envelope trust 2000)))))

(deftest signature-and-artifact-tampering-are-rejected
  (let [{:keys [envelope trust]} (fixture)
        statement-tamper (update-in envelope [:statement :expires] inc)
        changed (update-in envelope [:artifact :code 0] bit-xor 1)
        resealed-artifact (artifact/seal (:artifact changed))
        resealed (assoc changed :artifact resealed-artifact)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signature verification failed"
                          (signing/verify statement-tamper trust 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"integrity mismatch"
                          (signing/verify changed trust 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"statement mismatch"
                          (signing/verify resealed trust 1500)))))

(deftest versioned-signature-and-trust-schemas-reject-unknown-fields
  (let [{:keys [envelope trust]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"envelope schema"
                          (signing/verify (assoc envelope :ignored true) trust 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"statement mismatch"
                          (signing/verify (assoc-in envelope [:statement :ignored] true)
                                          trust 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed trust policy"
                          (signing/verify envelope (assoc trust :ignored true) 1500)))))

(deftest signing-and-verification-key-material-is-validated
  (let [first-key (signing/generate-keypair)
        second-key (signing/generate-keypair)
        public (signing/verification-key first-key)
        mismatch (assoc first-key :private-key (:private-key second-key))]
    (is (signing/valid-key? first-key))
    (is (signing/valid-verification-key? public))
    (is (= (:signer first-key) (signing/trusted-signer-id! public)))
    (is (= (:signer first-key) (signing/trusted-signer-id! first-key))
        "legacy trust provisioning from a private key remains accepted")
    (is (not (contains? public :private-key)))
    (is (not (signing/valid-key? mismatch)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"malformed Ed25519 signing key"
                          (signing/sign-value mismatch {:value 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"verification key"
                          (signing/trusted-signer-id!
                           (assoc public :public-key "malformed"))))))
