(ns kotoba.compiler.coverage-evidence-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.coverage-evidence :as evidence]
            [kotoba.compiler.signing :as signing]))

(def digest (apply str (repeat 64 "a")))

(defn fixture []
  (let [key (signing/generate-keypair)
        claim {:platform :linux
               :paths #{:native :wasm}
               :target-profiles #{:x86_64-linux-kotoba-v1}
               :conformance-sha256 digest :runtime-sha256 digest
               :ci-run-url "https://ci.example/run/1"
               :tested-at 1000 :expires 2000}
        envelope (evidence/sign claim key)
        trust {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
               :revoked-signers #{} :revoked-artifacts #{}}]
    {:key key :claim claim :envelope envelope :trust trust}))

(deftest signed-coverage-evidence-binds-platform-runtime-and-conformance
  (let [{:keys [key envelope trust]} (fixture)
        verified (evidence/verify envelope trust 1500)]
    (is (= (:signer key) (:signer verified)))
    (is (= :linux (:platform verified)))
    (is (= #{:native :wasm} (:paths verified)))
    (is (= (artifact/sha256 (:statement envelope)) (:digest verified)))))

(deftest coverage-evidence-tampering-expiry-and-revocation-fail-closed
  (let [{:keys [envelope trust]} (fixture)
        digest (artifact/sha256 (:statement envelope))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/verify (assoc-in envelope [:statement :platform] :windows)
                                  trust 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expired"
                          (evidence/verify envelope trust 2000)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not trusted"
                          (evidence/verify envelope (assoc trust :trusted-signers #{}) 1500)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"revoked"
                          (evidence/verify envelope
                                           (assoc trust :revoked-artifacts #{digest}) 1500)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (evidence/verify (assoc envelope :ignored true) trust 1500)))))

(deftest evidence-bundles-reject-duplicates
  (let [{:keys [envelope trust]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate"
                          (evidence/verify-bundle [envelope envelope] trust 1500)))))
