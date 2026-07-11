(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.native :as native]
            [kotoba.compiler.verifier :as verifier]))

(def targets #{:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1})

(defn compile-source [source target]
  (when-not (contains? targets target)
    (throw (ex-info "unsupported target" {:target target :supported targets})))
  (let [hir (frontend/analyze source)
        kir (ir/lower hir)
        value (second (first (:instructions (first (:blocks kir)))))]
    (if (= target :wasm32-kotoba-v1)
      {:format :wasm/v1 :target target :hir hir :kir kir :bytes (wasm/emit value)}
      (let [code ((case target
                    :x86_64-kotoba-v1 native/emit-x86-64
                    :aarch64-kotoba-v1 native/emit-aarch64) value)
            artifact {:format :kotoba.kexe/v1 :target target :value value
                      :effects #{} :limits {:memory-bytes 0 :fuel 1}
                      :code (mapv #(bit-and (int %) 0xff) code)}]
        (verifier/verify-artifact! artifact)
        {:format :kexe/v1 :target target :hir hir :kir kir :artifact artifact}))))
