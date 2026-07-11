(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.native :as native]
            [kotoba.compiler.backend.x86-64 :as x86-64]
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
      (let [runtime? (= target :x86_64-kotoba-v1)
            emitted (when runtime? (x86-64/emit-program kir))
            code (if runtime? (:code emitted) (native/emit-aarch64 value))
            program (select-keys kir [:format :entry :signature :effects :functions])
            artifact (artifact/seal
                      (cond-> {:format :kotoba.kexe/v1 :target target :value value
                               :kir-sha256 (artifact/sha256 program)
                               :lowering (if runtime? :runtime-sysv-v1
                                            :closed-program-specialization)
                               :effects #{}
                               :limits {:memory-bytes 0
                                        :fuel (if runtime? (count code) 1)
                                        :stack-bytes (if runtime? 4096 0)}
                               :code (mapv #(bit-and (int %) 0xff) code)}
                        runtime? (assoc :program program :exports (:exports emitted))))]
        (verifier/verify-artifact! artifact)
        {:format :kexe/v1 :target target :hir hir :kir kir :artifact artifact}))))
