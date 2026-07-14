(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.x86-64 :as x86-64]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.packaging.elf64 :as elf64]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.target :as target-profile]
            [kotoba.compiler.verifier :as verifier]))

(def targets target-profile/compatibility-targets)
(def supported-targets (set (keys target-profile/profiles)))

(defn check-source
  ([source] (check-source source {}))
  ([source policy]
   (let [hir (frontend/analyze source)]
     {:hir hir :admission (admission/check hir policy)})))

(defn compile-source
  ([source target] (compile-source source target {}))
  ([source target policy]
   (when-not (contains? supported-targets target)
     (throw (ex-info "unsupported target" {:target target :supported supported-targets})))
   (let [profile (target-profile/profile target)
        backend (target-profile/backend target)
        hir (frontend/analyze source)
        admission (admission/check hir policy)
        kir (ir/lower hir)
        value (:oracle-value kir)]
    (if (= backend :wasm32-kotoba-v1)
      {:format :wasm/v1 :target target :target-profile profile
       :hir hir :kir kir :admission admission
       :limits {:fuel 256 :replenishable? false} :bytes (wasm/emit kir target)}
      (let [emitted ((case backend
                       :x86_64-kotoba-v1 x86-64/emit-program
                       :aarch64-kotoba-v1 aarch64/emit-program) kir)
            code (:code emitted)
            program (select-keys kir [:format :entry :signature :effects :functions])
            artifact (artifact/seal
                      {:format :kotoba.kexe/v1 :target target :target-profile profile :value value
                       :kir-sha256 (artifact/sha256 program)
                       :lowering (case backend
                                   :x86_64-kotoba-v1 :runtime-sysv-v1
                                   :aarch64-kotoba-v1 :runtime-aapcs64-v1)
                       :fuel-abi (case backend
                                   :x86_64-kotoba-v1 {:mode :hidden-context-r9 :initial 256}
                                   :aarch64-kotoba-v1 {:mode :hidden-context-x7 :initial 256})
                       :context-abi {:version 2 :fuel-offset 8 :allow-bitmap-offset 16
                                     :allow-bitmap-bytes 32 :cap-call-offset 48
                                     :pair-new-offset 56 :pair-first-offset 64
                                     :pair-second-offset 72 :pair-capacity 4096}
                       :effects (:effects hir)
                       :limits {:memory-bytes 65536
                                :fuel 256
                                :stack-bytes 4096}
                       :code (mapv #(bit-and (int %) 0xff) code)
                       :program program :exports (:exports emitted)})]
        (verifier/verify-artifact! artifact)
        (cond-> {:format :kexe/v1 :target target :hir hir :kir kir
                 :admission admission :artifact artifact}
          (= target :x86_64-aiueos-kernel-v1)
          (assoc :binary (elf64/package-kernel artifact))))))))
