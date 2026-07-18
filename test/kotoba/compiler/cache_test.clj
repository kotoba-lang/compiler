(ns kotoba.compiler.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.cache :as cache]
            [kotoba.compiler.core :as compiler]
            [kotoba.compiler.signing :as signing]))

(def source "(ns cache.demo) (defn main [] (+ 40 2))")
(defn- trust [key]
  {:format :kotoba.trust/v1 :trusted-signers #{(:signer key)}
   :revoked-signers #{} :revoked-artifacts #{}})
(defn- reason [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (:reason (ex-data error)))))

(deftest signed-cache-admission-is-input-exact-and-fail-closed
  (let [key (signing/generate-keypair)
        result (compiler/compile-source source :wasm32-kotoba-v1)
        entry (cache/store source {} {} result key 1000 2000)
        admitted (cache/admit! source :wasm32-kotoba-v1 {} {} entry (trust key) 1500)]
    (is (:hit? admitted))
    (is (= (seq (:bytes result)) (seq (get-in admitted [:result :bytes]))))
    (is (= (:provenance result) (get-in admitted [:result :provenance])))
    (testing "current inputs and target are part of admission"
      (is (= :identity-mismatch
             (reason #(cache/admit! "(defn main [] 43)" :wasm32-kotoba-v1
                                    {} {} entry (trust key) 1500))))
      (is (= :schema
             (reason #(cache/admit! source :wasm32-wasi-kotoba-v1
                                    {} {} entry (trust key) 1500)))))
    (testing "payload mutation and attacker resealing cannot replace trust"
      (let [mutated (assoc-in entry [:payload :target] :wasm32-wasi-kotoba-v1)
            resealed (-> mutated
                         (assoc-in [:statement :payload-sha256]
                                   (artifact/sha256 (:payload mutated))))]
        (is (= :schema (reason #(cache/admit! source :wasm32-kotoba-v1
                                               {} {} mutated (trust key) 1500))))
        (is (= :signature (reason #(cache/admit! source :wasm32-kotoba-v1
                                                  {} {} resealed (trust key) 1500))))))
    (testing "validity, trust, revocation and exact schema are enforced"
      (is (= :expired (reason #(cache/admit! source :wasm32-kotoba-v1
                                              {} {} entry (trust key) 2000))))
      (is (= :untrusted
             (reason #(cache/admit! source :wasm32-kotoba-v1 {} {} entry
                                    (assoc (trust key) :trusted-signers #{}) 1500))))
      (is (= :schema
             (reason #(cache/admit! source :wasm32-kotoba-v1 {} {}
                                    (assoc entry :attacker-field true) (trust key) 1500)))))))

(deftest core-cache-path-never-silently-falls-back-after-rejection
  (let [key (signing/generate-keypair)
        result (compiler/compile-source source :x86_64-kotoba-v1)
        entry (cache/store source {} {} result key 1 10)]
    (is (false? (:hit? (compiler/compile-source-cached
                        source :x86_64-kotoba-v1 {} {} nil nil 5))))
    (is (true? (:hit? (compiler/compile-source-cached
                       source :x86_64-kotoba-v1 {} {} entry (trust key) 5))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/compile-source-cached
                  "(defn main [] 0)" :x86_64-kotoba-v1 {} {}
                  entry (trust key) 5)))))
