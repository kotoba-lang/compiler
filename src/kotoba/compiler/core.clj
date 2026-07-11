(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.native :as native]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.verifier :as verifier]))

(def targets #{:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1})

(defn compile-source [source target]
  (when-not (contains? targets target)
    (throw (ex-info "unsupported target" {:target target :supported targets})))
  (let [hir (frontend/analyze source)
        kir (ir/lower hir)
        value (:oracle-value kir)]
    (if (= target :wasm32-kotoba-v1)
      {:format :wasm/v1 :target target :hir hir :kir kir :bytes (wasm/emit kir)}
      (let [code ((case target
                    :x86_64-kotoba-v1 native/emit-x86-64
                    :aarch64-kotoba-v1 native/emit-aarch64) value)
            artifact (artifact/seal
                      {:format :kotoba.kexe/v1 :target target :value value
                       :kir-sha256 (artifact/sha256 kir)
                       :lowering :closed-program-specialization
                       :effects #{} :limits {:memory-bytes 0 :fuel 1 :stack-bytes 0}
                       :code (mapv #(bit-and (int %) 0xff) code)})]
        (verifier/verify-artifact! artifact)
        {:format :kexe/v1 :target target :hir hir :kir kir :artifact artifact}))))
