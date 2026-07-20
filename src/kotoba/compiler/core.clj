(ns kotoba.compiler.core
  (:require [kotoba.compiler.frontend :as frontend]
            [kotoba.compiler.compatibility :as compatibility]
            [kotoba.compiler.provenance :as provenance]
            [kotoba.compiler.cache :as cache]
            [kotoba.compiler.project :as project]
            [kotoba.compiler.ir :as ir]
            [kotoba.compiler.admission :as admission]
            [kotoba.compiler.backend.wasm :as wasm]
            [kotoba.compiler.backend.wasm-typed :as typed]
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

(def compiler-version compatibility/compiler-version)
(def floating-point-policy :kotoba.floating-point/ieee-754-f32-f64-v7)

(defn- text-sha256 [text]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String text StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(def targets target-profile/compatibility-targets)
(def supported-targets (set (keys target-profile/profiles)))

;; Native (x86_64/aarch64) targets admit ONLY the string slice of "typed
;; values" (string literals + string-byte-length/string=?/string-concat,
;; ADR-2607198300 follow-up) -- every other typed feature (options, results,
;; variants, records, typed maps/vectors/sets) has zero native backend
;; codegen and must keep producing the same "requires kotoba-script web
;; target" rejection it always has. This is a content check, not a blanket
;; per-backend allowance: :kotoba.hir/v3 covers ALL typed features uniformly,
;; so admitting the format for native without inspecting which features are
;; actually used would silently let unsupported ops reach the backend and
;; crash confusingly instead of rejecting cleanly. Shared with
;; `kotoba.compiler.nbb.cli` (the nbb-native fast path) via
;; `kotoba.compiler.ir/only-string-typed-features?` so both compile paths
;; admit the exact same native-typed-feature subset.

(defn check-source
  ([source] (check-source source {}))
  ([source policy]
   (let [hir (frontend/analyze source)]
     {:hir hir :admission (admission/check hir policy)})))

(defn- compile-source*
  ([source target] (compile-source* source target {}))
  ([source target policy] (compile-source* source target policy {}))
  ([source target policy emit-metadata]
   (when-not (contains? supported-targets target)
     (throw (ex-info "unsupported target" {:target target :supported supported-targets})))
   (let [profile (target-profile/profile target)
        backend (target-profile/backend target)
        hir (frontend/analyze source)
        _ (when (and (or (ir/uses-f32? hir) (ir/uses-f64? hir))
                     (not (contains? #{:js-kotoba-v1 :wasm32-kotoba-v1} backend)))
            (throw (ex-info "floating-point values require the kotoba-script or Wasm target"
                            {:phase :target :target target :backend backend
                             :floating-point-policy floating-point-policy})))
        _ (when (and (= :kotoba.hir/v3 (:format hir))
                     (not (contains? #{:js-kotoba-v1 :wasm32-kotoba-v1} backend))
                     (not (and (contains? #{:x86_64-kotoba-v1 :aarch64-kotoba-v1} backend)
                               (ir/only-string-typed-features? hir))))
            (throw (ex-info "typed values currently require the kotoba-script web target, typed Wasm target, or (native targets) string-only typed features"
                            {:phase :target :target target :backend backend
                             :value-profile :kotoba.value/typed-v1})))
        _ (when (and (nil? (:entry hir))
                     (not (contains? #{:js-kotoba-v1 :wasm32-kotoba-v1} backend)))
            (throw (ex-info "entryless libraries currently require the kotoba-script web target or Wasm target"
                            {:phase :target :target target :backend backend})))
        admission (admission/check hir policy)
        kir (ir/lower hir)
        value (:oracle-value kir)
        typed-values? (= :kotoba.kir/v4 (:format kir))
        value-abi (cond (ir/uses-f32? hir) :kotoba.typed/mixed-f32-f64-v3
                        (ir/uses-f64? hir) :kotoba.typed/mixed-f64-v2
                        typed-values? :kotoba.typed/externref-v1
                        :else :kotoba.i64/direct-v1)
        compatibility (compatibility/descriptor
                       {:hir-format (:format hir) :kir-format (:format kir)
                        :target target :target-profile profile :value-abi value-abi})]
    (cond
      (= backend :wasm32-kotoba-v1)
      (let [typed-values? (= :kotoba.kir/v4 (:format kir))]
        {:format :wasm/v1 :target target :target-profile profile
         :hir hir :kir kir :admission admission
         :compatibility compatibility
         :floating-point-policy floating-point-policy
         :value-profile (if typed-values? :kotoba.value/typed-v1 :kotoba.value/i64-v1)
         :value-abi value-abi
         :wasm-features (cond-> #{}
                          (typed/requires-host-runtime? kir) (conj :reference-types)
                          (ir/uses-f32? kir) (conj :ieee-754-f32)
                          (ir/uses-f64? kir) (conj :ieee-754-f64))
         :limits (cond-> {:fuel 512 :replenishable? false}
                   typed-values? (assoc :parametric-adt-depth 8
                                        :parametric-adt-nodes 64
                                        :variant-cases 32
                                        :heterogeneous-vector-items 32
                                        :typed-set-items 32
                                        :typed-map-entries 31
                                        :record-fields 32
                                        :vector-i64-items 16384
                                        :vector-f64-items 16384))
         :bytes (wasm/emit kir target)})

      ;; ADR-2607151500: cljs backend emits SOURCE TEXT, not bytes -- no
      ;; kexe sealing (that artifact shape is native-code-specific: raw
      ;; :code bytes + a fuel/context ABI for a machine-code caller). A
      ;; cljs host just requires the returned source's namespace directly.
      (= backend :cljs-kotoba-v1)
      {:format :cljs/v1 :target target :target-profile profile
       :hir hir :kir kir :admission admission
       :compatibility compatibility
       :floating-point-policy floating-point-policy
       :limits {:fuel 512 :replenishable? false} :source (cljs/emit kir)}

      (= backend :js-kotoba-v1)
      (let [source-digest (text-sha256 source)
            kir-digest (artifact/sha256 kir)
            typed-values? (= :kotoba.kir/v4 (:format kir))
            value-profile (if typed-values? :kotoba.value/typed-v1 :kotoba.value/i64-v1)
            limits (cond-> {:fuel 512 :replenishable? false}
                     typed-values? (assoc :string-literal-bytes 4096
                                          :string-module-literal-bytes 65536
                                          :string-value-bytes 65536
                                          :keyword-value-bytes 512
                                          :map-entries 128
                                          :option-i64-slots 2
                                          :result-i64-slots 2
                                          :parametric-adt-depth 8
                                          :parametric-adt-nodes 64
                                          :variant-cases 32
                                          :generic-option-max-slots 3
                                          :heterogeneous-vector-items 32
                                          :typed-set-items 32
                                          :typed-map-entries 31
                                          :record-fields 32
                                          :vector-i64-items 16384
                                          :vector-f64-items 16384))
            js-source (script/emit kir (merge {:source-digest source-digest
                                               :kir-digest kir-digest
                                               :compiler-version compiler-version}
                                              emit-metadata))
            output-digest (text-sha256 js-source)]
        {:format :javascript/v1 :target target :target-profile profile
         :hir hir :kir kir :admission admission
         :compatibility compatibility
         :floating-point-policy floating-point-policy
         :value-profile value-profile :limits limits :source js-source
         :manifest {:kotoba.artifact/schema "kotoba-js-artifact/v1"
                    :kotoba.artifact/source-digest source-digest
                    :kotoba.artifact/kir-digest kir-digest
                    :kotoba.artifact/output-digest output-digest
                    :kotoba.artifact/compiler-version compiler-version
                    :kotoba.artifact/compatibility compatibility
                    :kotoba.artifact/floating-point-policy floating-point-policy
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
                                   :x86_64-kotoba-v1 {:mode :hidden-context-r9 :initial 512}
                                   :aarch64-kotoba-v1 {:mode :hidden-context-x7 :initial 512})
                       :context-abi {:version 2 :fuel-offset 8 :allow-bitmap-offset 16
                                     :allow-bitmap-bytes 32 :cap-call-offset 48
                                     :pair-new-offset 56 :pair-first-offset 64
                                     :pair-second-offset 72 :pair-capacity 4096
                                     :kgraph-assert-offset 80 :kgraph-get-offset 88
                                     :kgraph-count-offset 96 :kgraph-entity-at-offset 104
                                     :kgraph-capacity 4096
                                     :string-equal-offset 112 :string-concat-offset 120
                                     :string-pool-capacity 65536}
                      :effects (:effects hir)
                       :compatibility compatibility
                       :limits {:memory-bytes 65536
                                :fuel 512
                                :stack-bytes 4096}
                       :code (mapv #(bit-and (int %) 0xff) code)
                       :program program :exports (:exports emitted)})]
        (verifier/verify-artifact! artifact)
        (cond-> {:format :kexe/v1 :target target :hir hir :kir kir
                 :admission admission :artifact artifact
                 :compatibility compatibility
                 :floating-point-policy floating-point-policy}
          (= target :x86_64-aiueos-uefi-v1)
          (assoc :binary (pe32plus/package-efi artifact))

          (= target :x86_64-aiueos-kernel-v1)
          (assoc :binary (elf64/package-kernel artifact)
                 :object (elf64/package-kernel-object artifact))

          (= target :aarch64-aiueos-kernel-v1)
          (assoc :binary (elf64/package-kernel-aarch64 artifact))

          (= target :x86_64-aiueos-user-v1)
          (assoc :binary (elf64/package-user artifact))))))))

(defn compile-source
  ([source target] (compile-source source target {}))
  ([source target policy] (compile-source source target policy {}))
  ([source target policy emit-metadata]
   (provenance/attach source policy emit-metadata
                      (compile-source* source target policy emit-metadata))))

(defn compile-source-cached
  [source target policy build-metadata cache-entry trust now]
  (if cache-entry
    (cache/admit! source target policy build-metadata cache-entry trust now)
    {:hit? false
     :result (compile-source source target policy build-metadata)}))

(defn compile-project
  "Compile a closed namespace-symbol -> source-text map without ambient lookup."
  ([sources root target] (compile-project sources root target {}))
  ([sources root target policy] (compile-project sources root target policy {}))
  ([sources root target policy supply-chain]
   (let [allowed-keys #{:package-lock-digest :trust-policy-digest
                        :package-receipt-digest}
         values (when (map? supply-chain) (vals supply-chain))
         supplied (count (filter some? values))]
     (when-not (and (map? supply-chain)
                    (every? allowed-keys (keys supply-chain))
                    (or (zero? supplied)
                        (and (= allowed-keys (set (keys supply-chain)))
                             (= 3 supplied)
                             (every? #(and (string? %)
                                           (re-matches #"[0-9a-f]{64}" %))
                                     values))))
       (throw (ex-info "invalid verified supply-chain identity"
                       {:phase :project-link
                        :reason :invalid-supply-chain-identity}))))
   (let [linked (project/link-source sources root)
         module-digests (into (sorted-map)
                              (map (fn [[namespace source]]
                                     [namespace (text-sha256 source)]))
                              (select-keys sources (:module-order linked)))
         graph {:kotoba.module/schema :kotoba.module-graph/v1
                :kotoba.module/root root
                :kotoba.module/order (:module-order linked)
                :kotoba.module/source-digests module-digests}
         graph-digest (artifact/sha256 graph)
         compiled (compile-source (:source linked) target policy
                                  (merge {:module-graph-digest graph-digest
                                          :module-source-digests module-digests}
                                         supply-chain))]
     (cond-> (assoc compiled :project graph :project-digest graph-digest)
       (:manifest compiled)
       (update :manifest merge
               (merge {:kotoba.artifact/module-graph-digest graph-digest
                       :kotoba.artifact/module-source-digests module-digests}
                      (when (seq supply-chain)
                        {:kotoba.artifact/package-lock-digest
                         (:package-lock-digest supply-chain)
                         :kotoba.artifact/trust-policy-digest
                         (:trust-policy-digest supply-chain)
                         :kotoba.artifact/package-receipt-digest
                         (:package-receipt-digest supply-chain)})))))))
