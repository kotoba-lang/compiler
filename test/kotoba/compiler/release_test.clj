(ns kotoba.compiler.release-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.compiler.release :as release]
            [kotoba.compiler.signing :as signing])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- write! [^Path path value] (Files/writeString path value (make-array java.nio.file.OpenOption 0)))
(defn- trust [key]
  {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
   :revoked-signers #{} :revoked-artifacts #{}})

(deftest deterministic-spdx-and-signed-release-attestation
  (let [directory (Files/createTempDirectory "kotoba-release-" (make-array FileAttribute 0))
        artifact (.resolve directory "service.wasm")
        sbom (.resolve directory "service.spdx")
        key (signing/generate-keypair)]
    (try
      (write! artifact "sealed artifact")
      (let [first (release/sbom-bytes (str artifact))
            second (release/sbom-bytes (str artifact))]
        (is (= (seq first) (seq second)))
        (Files/write sbom first (make-array java.nio.file.OpenOption 0))
        (let [text (String. first "UTF-8")
              envelope (release/attest (str artifact) (str sbom)
                                       :wasm32-wasi-kotoba-v1 key 1000 2000)]
          (is (.contains text "SPDXVersion: SPDX-2.3"))
          (is (.contains text "Created: 1970-01-01T00:00:00Z"))
          (is (= :wasm32-wasi-kotoba-v1
                 (:target (release/verify! envelope (str artifact) (str sbom) (trust key) 1500))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside validity"
                                (release/verify! envelope (str artifact) (str sbom) (trust key) 2000)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"signature rejected"
                                (release/verify! (assoc-in envelope [:statement :not-before] 1001)
                                                 (str artifact) (str sbom) (trust key) 1500)))
          (write! sbom "SPDXVersion: SPDX-2.3\n")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SBOM identity mismatch"
                                (release/verify! envelope (str artifact) (str sbom) (trust key) 1500)))
          (Files/write sbom first (make-array java.nio.file.OpenOption 0))
          (write! artifact "mutated")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"artifact identity mismatch"
                                (release/verify! envelope (str artifact) (str sbom) (trust key) 1500)))))
      (finally
        (Files/deleteIfExists sbom)
        (Files/deleteIfExists artifact)
        (Files/deleteIfExists directory)))))
