(ns kotoba.compiler.verifier
  (:require [kotoba.compiler.artifact :as artifact]
            [kotoba.compiler.backend.native :as native]
            [kotoba.compiler.backend.x86-64 :as x86-64]))

(defn- unsigned [bs] (mapv #(bit-and (int %) 0xff) bs))

(defn- reject! [message data]
  (throw (ex-info message (assoc data :phase :verify))))

(defn- verify-specialized-aarch64! [{:keys [value code lowering limits]}]
  (when-not (= :closed-program-specialization lowering)
    (reject! "native lowering mode is not admitted" {:lowering lowering}))
  (when-not (= {:memory-bytes 0 :fuel 1 :stack-bytes 0} limits)
    (reject! "resource limits are not admitted" {:limits limits}))
  (when-not (= (unsigned (native/emit-aarch64 value)) code)
    (reject! "native instruction stream rejected" {:target :aarch64-kotoba-v1})))

(defn- verify-runtime-x86-64! [{:keys [program code exports lowering limits]}]
  (when-not (= :runtime-sysv-v1 lowering)
    (reject! "x86-64 runtime lowering mode is not admitted" {:lowering lowering}))
  (when-not (= :kotoba.kir/v3 (:format program))
    (reject! "x86-64 artifact requires runtime KIR v3" {:program-format (:format program)}))
  (let [expected (try (x86-64/emit-program program)
                      (catch Exception e
                        (reject! "runtime KIR cannot be safely lowered"
                                 {:cause (.getMessage e)})))]
    (when-not (= (:exports expected) exports)
      (reject! "native export table rejected" {:target :x86_64-kotoba-v1}))
    (when-not (= (:code expected) code)
      (reject! "native instruction stream rejected" {:target :x86_64-kotoba-v1})))
  (when-not (= {:memory-bytes 0 :fuel (count code) :stack-bytes 4096} limits)
    (reject! "resource limits are not admitted" {:limits limits})))

(defn verify-artifact! [{:keys [format target code effects kir-sha256] :as kexe}]
  (when-not (= :kotoba.kexe/v1 format)
    (reject! "unknown artifact format" {}))
  (when-not (and (string? kir-sha256) (re-matches #"[0-9a-f]{64}" kir-sha256))
    (reject! "missing or malformed KIR identity" {}))
  (when-not (= #{} effects)
    (reject! "bootstrap native target admits no effects" {}))
  (when-not (and (vector? code) (<= 1 (count code) (* 1024 1024))
                 (every? #(and (integer? %) (<= 0 % 255)) code))
    (reject! "malformed code bytes" {}))
  (when-not (artifact/valid-seal? kexe)
    (reject! "artifact integrity mismatch" {}))
  (when (and (= target :x86_64-kotoba-v1)
             (not= kir-sha256 (artifact/sha256 (:program kexe))))
    (reject! "runtime KIR identity mismatch" {}))
  (case target
    :x86_64-kotoba-v1 (verify-runtime-x86-64! kexe)
    :aarch64-kotoba-v1 (verify-specialized-aarch64! kexe)
    (reject! "not a native verifier target" {:target target}))
  kexe)
