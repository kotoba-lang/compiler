(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.x86-64 :as x86-64]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.verifier :as verifier]))

(def targets #{:wasm32-kotoba-v1 :x86_64-kotoba-v1 :aarch64-kotoba-v1})

(defn check-source
  ([source] (check-source source {}))
  ([source policy]
   (let [hir (frontend/analyze source)]
     {:hir hir :admission (admission/check hir policy)})))

(defn compile-source
  ([source target] (compile-source source target {}))
  ([source target policy]
   (when-not (contains? targets target)
     (throw (ex-info "unsupported target" {:target target :supported targets})))
   (let [hir (frontend/analyze source)
        admission (admission/check hir policy)
        _ (when (and (seq (:effects hir)) (not= target :wasm32-kotoba-v1))
            (throw (ex-info "native capability trampoline backend is not implemented"
                            {:phase :codegen :target target :effects (:effects hir)})))
        kir (ir/lower hir)
        value (:oracle-value kir)]
    (if (= target :wasm32-kotoba-v1)
      {:format :wasm/v1 :target target :hir hir :kir kir :admission admission
       :limits {:fuel 256 :replenishable? false} :bytes (wasm/emit kir)}
      (let [emitted ((case target
                       :x86_64-kotoba-v1 x86-64/emit-program
                       :aarch64-kotoba-v1 aarch64/emit-program) kir)
            code (:code emitted)
            program (select-keys kir [:format :entry :signature :effects :functions])
            artifact (artifact/seal
                      {:format :kotoba.kexe/v1 :target target :value value
                       :kir-sha256 (artifact/sha256 program)
                       :lowering (case target
                                   :x86_64-kotoba-v1 :runtime-sysv-v1
                                   :aarch64-kotoba-v1 :runtime-aapcs64-v1)
                       :fuel-abi (case target
                                   :x86_64-kotoba-v1 {:mode :hidden-context-r9 :initial 256}
                                   :aarch64-kotoba-v1 {:mode :hidden-context-x7 :initial 256})
                       :effects #{}
                       :limits {:memory-bytes 0
                                :fuel 256
                                :stack-bytes 4096}
                       :code (mapv #(bit-and (int %) 0xff) code)
                       :program program :exports (:exports emitted)})]
        (verifier/verify-artifact! artifact)
        {:format :kexe/v1 :target target :hir hir :kir kir
         :admission admission :artifact artifact})))))
