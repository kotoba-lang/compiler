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

(deftest every-backend-is-bound-to-the-same-manifest-gate
  (doseq [backend [:wasmtime :native :cljs]]
    (let [receipt (qualification/verify! backend)]
      (is (= :kotoba.backend-provider-qualification/receipt-v1 (:format receipt)))
      (is (= backend (:backend receipt)))
      (is (= 9 (:capability-count receipt)))
      (is (= :passed (:manifest-gate receipt)))
      (is (= :pending (:execution-status receipt)))
      (is (seq (:gaps receipt)))
      (when (= :cljs backend)
        (is (= [:full-i64-structured-values] (:gaps receipt)))))))

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
