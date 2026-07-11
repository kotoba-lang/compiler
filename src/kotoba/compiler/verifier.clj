(ns kotoba.compiler.verifier
  (:require [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.backend.aarch64 :as aarch64]
            [kotoba.compiler.backend.x86-64 :as x86-64]))

(defn- reject! [message data]
  (throw (ex-info message (assoc data :phase :verify))))

(def target-contracts
  {:x86_64-kotoba-v1 {:lowering :runtime-sysv-v1 :emit x86-64/emit-program}
   :aarch64-kotoba-v1 {:lowering :runtime-aapcs64-v1 :emit aarch64/emit-program}})

(defn- verify-runtime! [{:keys [target program code exports lowering limits fuel-abi]}]
  (let [{expected-lowering :lowering emit :emit} (get target-contracts target)]
    (when-not emit (reject! "not a native verifier target" {:target target}))
    (when-not (= expected-lowering lowering)
      (reject! "native runtime lowering mode is not admitted"
               {:target target :lowering lowering}))
    (when-not (= :kotoba.kir/v3 (:format program))
      (reject! "native artifact requires runtime KIR v3"
               {:target target :program-format (:format program)}))
    (let [expected (try (emit program)
                        (catch Exception e
                          (reject! "runtime KIR cannot be safely lowered"
                                   {:target target :cause (.getMessage e)})))]
      (when-not (= (:exports expected) exports)
        (reject! "native export table rejected" {:target target}))
      (when-not (= (:code expected) code)
        (reject! "native instruction stream rejected" {:target target})))
    (let [expected-fuel-abi (case target
                              :x86_64-kotoba-v1 {:mode :hidden-context-r9 :initial 256}
                              :aarch64-kotoba-v1 {:mode :process-limit-only})
          expected-limits {:memory-bytes 0
                           :fuel (if (= target :x86_64-kotoba-v1) 256 (count code))
                           :stack-bytes 4096}]
      (when-not (= expected-fuel-abi fuel-abi)
        (reject! "fuel ABI is not admitted" {:target target :fuel-abi fuel-abi}))
      (when-not (= expected-limits limits)
        (reject! "resource limits are not admitted" {:target target :limits limits})))))

(defn verify-artifact! [{:keys [format target code effects kir-sha256] :as kexe}]
  (when-not (= :kotoba.kexe/v1 format) (reject! "unknown artifact format" {}))
  (when-not (and (string? kir-sha256) (re-matches #"[0-9a-f]{64}" kir-sha256))
    (reject! "missing or malformed KIR identity" {}))
  (when-not (= #{} effects) (reject! "native target admits no effects" {}))
  (when-not (and (vector? code) (<= 1 (count code) (* 1024 1024))
                 (every? #(and (integer? %) (<= 0 % 255)) code))
    (reject! "malformed code bytes" {}))
  (when-not (artifact/valid-seal? kexe) (reject! "artifact integrity mismatch" {}))
  (when-not (= kir-sha256 (artifact/sha256 (:program kexe)))
    (reject! "runtime KIR identity mismatch" {}))
  (verify-runtime! kexe)
  kexe)
