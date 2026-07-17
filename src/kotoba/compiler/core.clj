(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.cljs :as cljs]
            [kotoba.script :as script]
            [kotoba.compiler.backend.x86-64 :as x86-64]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.packaging.elf64 :as elf64]
            [kotoba.compiler.packaging.pe32plus :as pe32plus]
            [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.target :as target-profile]
            [kotoba.compiler.verifier :as verifier])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def compiler-version "kotoba-compiler/1")

(defn- text-sha256 [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

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
        _ (when (and (= :kotoba.hir/v3 (:format hir))
                     (not= backend :js-kotoba-v1))
            (throw (ex-info "typed string values currently require the kotoba-script web target"
                            {:phase :target :target target :backend backend
                             :value-profile :kotoba.value/typed-v1})))
        _ (when (and (nil? (:entry hir)) (not= backend :js-kotoba-v1))
            (throw (ex-info "entryless libraries currently require the kotoba-script web target"
                            {:phase :target :target target :backend backend})))
        admission (admission/check hir policy)
        kir (ir/lower hir)
        value (:oracle-value kir)]
    (cond
      (= backend :wasm32-kotoba-v1)
      {:format :wasm/v1 :target target :target-profile profile
       :hir hir :kir kir :admission admission
       :limits {:fuel 256 :replenishable? false} :bytes (wasm/emit kir target)}

      ;; ADR-2607151500: cljs backend emits SOURCE TEXT, not bytes -- no
      ;; kexe sealing (that artifact shape is native-code-specific: raw
      ;; :code bytes + a fuel/context ABI for a machine-code caller). A
      ;; cljs host just requires the returned source's namespace directly.
      (= backend :cljs-kotoba-v1)
      {:format :cljs/v1 :target target :target-profile profile
       :hir hir :kir kir :admission admission
       :limits {:fuel 256 :replenishable? false} :source (cljs/emit kir)}

      (= backend :js-kotoba-v1)
      (let [source-digest (text-sha256 source)
            kir-digest (artifact/sha256 kir)
            typed-values? (= :kotoba.kir/v4 (:format kir))
            value-profile (if typed-values? :kotoba.value/typed-v1 :kotoba.value/i64-v1)
            limits (cond-> {:fuel 256 :replenishable? false}
                     typed-values? (assoc :string-literal-bytes 4096
                                          :string-module-literal-bytes 65536
                                          :string-value-bytes 65536))
            js-source (script/emit kir {:source-digest source-digest
                                        :kir-digest kir-digest
                                        :compiler-version compiler-version})
            output-digest (text-sha256 js-source)]
        {:format :javascript/v1 :target target :target-profile profile
         :hir hir :kir kir :admission admission
         :value-profile value-profile :limits limits :source js-source
         :manifest {:kotoba.artifact/schema "kotoba-js-artifact/v1"
                    :kotoba.artifact/source-digest source-digest
                    :kotoba.artifact/kir-digest kir-digest
                    :kotoba.artifact/output-digest output-digest
                    :kotoba.artifact/compiler-version compiler-version
                    :kotoba.artifact/value-profile value-profile
                    :kotoba.artifact/limits limits
                    :kotoba.artifact/target target
                    :kotoba.artifact/target-profile profile
                    :kotoba.artifact/effects (:effects kir)}})

      :else
      (let [emitted ((case backend
                       :x86_64-kotoba-v1 x86-64/emit-program
                       :aarch64-kotoba-v1 aarch64/emit-program) kir)
            code (:code emitted)
            program (select-keys kir [:format :entry :exports :signature :effects :functions])
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
          (= target :x86_64-aiueos-uefi-v1)
          (assoc :binary (pe32plus/package-efi artifact))

          (= target :x86_64-aiueos-kernel-v1)
          (assoc :binary (elf64/package-kernel artifact)
                 :object (elf64/package-kernel-object artifact))

          (= target :aarch64-aiueos-kernel-v1)
          (assoc :binary (elf64/package-kernel-aarch64 artifact))

          (= target :x86_64-aiueos-user-v1)
          (assoc :binary (elf64/package-user artifact))))))))
