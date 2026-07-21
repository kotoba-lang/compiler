(ns kotoba.compiler.backend-qualification-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.backend-qualification :as qualification]))

(defn- read-resource [path]
  (edn/read-string (slurp (io/resource path))))

(def manifest
  (read-resource "kotoba/lang/provider-conformance-v1.edn"))
(def registry
  (read-resource "kotoba/compiler/capability-registry.edn"))
(def claims
  (read-resource "kotoba/lang/backend-provider-qualification-v1.edn"))

(deftest component-model-policy-is-compiler-owned-and-closed
  (let [policy (:component-model
                (read-resource "kotoba/lang/application-language.edn"))
        contract (read-resource "kotoba/lang/component-model-v1.edn")]
    (is (= :specified (:status policy)))
    (is (= :kotoba-lang/compiler (:artifact-owner policy)))
    (is (= :declared-typed-capabilities-only (get-in policy [:world :imports])))
    (is (= :reject (get-in policy [:world :undeclared-imports])))
    (is (false? (get-in policy [:wasi :application-ambient-authority])))
    (is (= :provider-component (get-in policy [:wasi :owner])))
    (is (= "0.3.0" (get-in contract [:spec-baseline :wasi :default])))
    (is (= :func (get-in contract [:spec-baseline :wasi :profiles :sync :function-mode])))
    (is (= :async-func (get-in contract [:spec-baseline :wasi :profiles :async :function-mode])))
    (is (= :legacy-explicit
           (get-in contract [:spec-baseline :wasi :compatibility 0 :status])))
    (is (= {:wasm-tools "1.243.0" :wac-cli "0.9.0"}
           (get-in contract [:spec-baseline :wasi :toolchain])))
    (is (= :reject-v1 (get-in contract [:types :recursive-schema :disposition])))
    (is (= :not-required (get-in contract [:limits :wit-bounded-list-feature])))))

(deftest component-capability-inventory-and-provider-authority-are-closed
  (let [contract (read-resource "kotoba/lang/component-model-v1.edn")
        expected (->> (:kits manifest) (mapcat :capabilities) set)
        actual (->> (:capabilities contract) (map #(select-keys % [:name :id])) set)
        entries (:capabilities contract)]
    (is (= expected actual))
    (is (= (count entries) (count (set (map (juxt :interface :function) entries)))))
    (is (every? #(and (string? (:interface %))
                      (string? (:function %))
                      (vector? (:provider-wasi %))) entries))
    (is (= ["wasi:http/outgoing-handler@0.3.0"]
           (:provider-wasi (first (filter #(= :http/post (:name %)) entries)))))
    (is (= #{"wasi:clocks/wall-clock@0.3.0"
             "wasi:clocks/monotonic-clock@0.3.0"}
           (set (:provider-wasi
                 (first (filter #(= :clock/now (:name %)) entries)))))
    (is (every? empty?
                (map :provider-wasi
                     (remove #(contains? #{:http/post :clock/now} (:name %)) entries)))))))

(deftest every-backend-is-bound-to-the-same-manifest-gate
  (doseq [backend [:wasmtime :native :cljs]]
    (let [receipt (qualification/verify! backend)]
      (is (= :kotoba.backend-provider-qualification/receipt-v1 (:format receipt)))
      (is (= backend (:backend receipt)))
      (is (= 9 (:capability-count receipt)))
      (is (= :passed (:manifest-gate receipt)))
      (if (= :cljs backend)
        (do (is (= :qualified (:execution-status receipt)))
            (is (empty? (:gaps receipt))))
        (do (is (= :pending (:execution-status receipt)))
            (is (seq (:gaps receipt))))))))

(deftest stale-manifest-and-false-qualification-claims-fail-closed
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not bound to this provider manifest"
       (qualification/verify-data!
        (update manifest :invariants conj :invented) registry claims :wasmtime)))
  (let [false-claim (-> claims
                        (assoc-in [:backends :native :execution-status] :qualified)
                        (assoc-in [:backends :native :gaps] []))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"lacks closed semantic evidence"
         (qualification/verify-data! manifest registry false-claim :native)))))

(deftest registry-drift-fails-every-backend-gate
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"disagrees with capability registry"
       (qualification/verify-data! manifest (assoc registry :clock/now 255)
                                   claims :cljs))))
